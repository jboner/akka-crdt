/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.crdt.convergent

import akka.actor._
import akka.cluster.Cluster
import akka.event.{ Logging, LogSource, LoggingAdapter }
import akka.contrib.pattern.{DistributedPubSubExtension, DistributedPubSubMediator}
import DistributedPubSubMediator._
import play.api.libs.json.Json.{ toJson, parse, stringify }
import play.api.libs.json.JsValue
import scala.util.Try
import scala.reflect.ClassTag
import java.util.UUID

object ConvergentReplicatedDataTypeDatabase
  extends ExtensionId[ConvergentReplicatedDataTypeDatabase]
  with ExtensionIdProvider {

  override def get(system: ActorSystem): ConvergentReplicatedDataTypeDatabase = super.get(system)

  override def lookup() = ConvergentReplicatedDataTypeDatabase

  override def createExtension(system: ExtendedActorSystem): ConvergentReplicatedDataTypeDatabase =
    new ConvergentReplicatedDataTypeDatabase(system)

  implicit val logSource: LogSource[AnyRef] = new LogSource[AnyRef] {
    def genString(o: AnyRef): String = o.getClass.getName
    override def getClazz(o: AnyRef): Class[_] = o.getClass
  }
}

class ConvergentReplicatedDataTypeDatabase(sys: ExtendedActorSystem) extends Extension {
  implicit val system = sys

  val log = Logging(sys, ConvergentReplicatedDataTypeDatabase.this)

  val nodename = Cluster(sys).selfAddress.hostPort.replace('@', '_').replace(':', '_')

  val settings = new ConvergentReplicatedDataTypeSettings(system.settings.config, system.name)

  private val storage: Storage =
    system.dynamicAccess.createInstanceFor[Storage](
      settings.StorageClass, List(
        (classOf[String], nodename),
        (classOf[ConvergentReplicatedDataTypeSettings], settings),
        (classOf[LoggingAdapter], log))).get // get the instance or throw the error

  // TODO: perhaps use gossip instead of broadcast using pub/sub?
  private val publisher = system.actorOf(Props[Publisher], name = "crdt:publisher")
  
  private val subscriber = system.actorOf(Props(new Subscriber(storage)), name = "crdt:subscriber")

  private val restServer = if (settings.RestServerRun) {
    val rs = new RestServer(this)
    rs.start()
    Some(rs)
  } else None

  def getOrCreate[T <: ConvergentReplicatedDataType: ClassTag](id: String = UUID.randomUUID.toString): Try[T] = Try {
    val clazz = implicitly[ClassTag[T]].runtimeClass
    if (classOf[GCounter].isAssignableFrom(clazz))
      (storage.findById[GCounter](id) getOrElse update(GCounter(id))).asInstanceOf[T]
    else if (classOf[PNCounter].isAssignableFrom(clazz))
      (storage.findById[PNCounter](id) getOrElse update(PNCounter(id))).asInstanceOf[T]
    else if (classOf[GSet].isAssignableFrom(clazz))
      (storage.findById[GSet](id) getOrElse update(GSet(id))).asInstanceOf[T]
    else if (classOf[TwoPhaseSet].isAssignableFrom(clazz))
      (storage.findById[TwoPhaseSet](id) getOrElse update(TwoPhaseSet(id))).asInstanceOf[T]
    else throw new ClassCastException(s"Could create new CvRDT with id [$id] and type [$clazz]")
  }

  def update(crdt: GCounter): GCounter = {
    publish(toJson(crdt)) // need a separate method for each type for the implicit resolution of JSON Format to work
    crdt
  }

  def update(crdt: PNCounter): PNCounter = {
    publish(toJson(crdt))
    crdt
  }

  def update(crdt: GSet): GSet = {
    publish(toJson(crdt))
    crdt
  }

  def update(crdt: TwoPhaseSet): TwoPhaseSet = {
    publish(toJson(crdt))
    crdt
  }

  def shutdown(): Unit = {
    log.info("Shutting down ConvergentReplicatedDataTypeDatabase")
    restServer foreach { _.shutdown() }
    system.stop(subscriber)
    system.stop(publisher)
    storage.destroy()
  }

  private def publish(json: JsValue): Unit = publisher ! json
}

/**
 * Publishing (broadcasting) CRDT changes to all nodes with a Subscriber.
 */
class Publisher extends Actor with ActorLogging {
  val pubsub = DistributedPubSubExtension(context.system).mediator
  val subscriber = "/user/crdt:subscriber"

  override def preStart(): Unit = {
    log.info("Starting CvRDT change publisher")
  }

  // ================================================================================
  // FIXME make use of batching (add a time window)
  // ================================================================================

  def receive = {
    case json: JsValue ⇒
      log.debug("Broadcasting changes {}", json)
      pubsub ! SendToAll(subscriber, stringify(json))

    case unknown ⇒ log.error("Received unknown message: {}", unknown)
  }
}

/**
 * Subscribing on CRDT changes broadcasted by the Publisher.
 */
class Subscriber(storage: Storage) extends Actor with ActorLogging {
  val pubsub = DistributedPubSubExtension(context.system).mediator

  override def preStart(): Unit = {
    log.info("Starting CvRDT change subscriber")
    pubsub ! Put(self)
  }

  // ================================================================================
  // FIXME make use of batching (add a time window): 'store(crdts: immutable.Seq[T])'
  // ================================================================================

  def receive: Receive = {
    case jsonString: String ⇒
      val json = parse(jsonString)
      (json \ "type").as[String] match {

        case "g-counter" ⇒
          val counter = json.as[GCounter]
          log.debug("Received update g-counter[{}]", counter)
          val id = counter.id
          val newCounter = storage.findById[GCounter](id) map { _ merge counter } getOrElse { counter }
          storage.store(newCounter)
          context.system.eventStream.publish(newCounter)
          log.debug("New merged g-counter [{}]", newCounter)

        case "pn-counter" ⇒
          val counter = json.as[PNCounter]
          log.debug("Received update pn-counter[{}]", counter)
          val id = counter.id
          val newCounter = storage.findById[PNCounter](id) map { _ merge counter } getOrElse { counter }
          storage.store(newCounter)
          context.system.eventStream.publish(newCounter)
          log.debug("New merged pn-counter [{}]", newCounter)

        case "g-set" ⇒
          val set = json.as[GSet]
          log.debug("Received update g-set [{}]", set)
          val id = set.id
          val newSet = storage.findById[GSet](id) map { _ merge set } getOrElse { set }
          storage.store(newSet)
          context.system.eventStream.publish(newSet)
          log.debug("New merged g-set [{}]", newSet)

        case "2p-set" ⇒
          val set = json.as[TwoPhaseSet]
          log.debug("Received update 2p-set [{}]", set)
          val id = set.id
          val newSet = storage.findById[TwoPhaseSet](id) map { _ merge set } getOrElse { set }
          storage.store(newSet)
          context.system.eventStream.publish(newSet)
          log.debug("New merged 2p-set [{}]", newSet)

        case _ ⇒ log.error("Received JSON is not a CvRDT: {}", jsonString)
      }

    case unknown ⇒ log.error("Received unknown message: {}", unknown)
  }
}
