/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.crdt.convergent

import akka.actor._
import akka.event.{Logging, LogSource}
import akka.contrib.pattern.DistributedPubSubExtension
import akka.contrib.pattern.DistributedPubSubMediator
import akka.contrib.pattern.DistributedPubSubMediator._

import play.api.libs.json.Json._
import play.api.libs.json._

import scala.util.Try
import scala.reflect.ClassTag

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
  private implicit val system = sys

  val log = Logging(sys, ConvergentReplicatedDataTypeDatabase.this)

  private val settings = new ConvergentReplicatedDataTypeSettings(system.settings.config, system.name)

  private val storage = new InMemoryStorage // FIXME make configurable from 'settings'
  	
  private val publisher = system.actorOf(Props[Publisher], name = "crdt:publisher")

  private val subscriber = system.actorOf(Props(new Subscriber(storage)), name = "crdt:subscriber")

  def getOrCreate[T : ClassTag](id: String): Try[T] = Try {
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
    system.stop(subscriber)
    system.stop(publisher)
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

  def receive = {
    case json: JsValue =>
      log.debug("Broadcasting changes {}", json)
      pubsub ! SendToAll(subscriber, stringify(json))

    case unknown => log.error("Received unknown message: {}", unknown)
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

  def receive: Receive = {
    case jsonString: String =>
      val json = parse(jsonString)
      (json \ "type").as[String] match {

      	case "g-counter" =>
          val counter = json.as[GCounter]
          log.debug("Received update g-counter[{}]", counter)
          val id = counter.id
          val newCounter = storage.findById[GCounter](id) map { _ merge counter } getOrElse { counter }
          storage.store(newCounter)
          context.system.eventStream.publish(newCounter)
          log.debug("New merged g-counter [{}]", newCounter)

        case "pn-counter" =>
          val counter = json.as[PNCounter]
          log.debug("Received update pn-counter[{}]", counter)
          val id = counter.id
          val newCounter = storage.findById[PNCounter](id) map { _ merge counter } getOrElse { counter }
          storage.store(newCounter)
          context.system.eventStream.publish(newCounter)
          log.debug("New merged pn-counter [{}]", newCounter)

        case "g-set" =>
          val set = json.as[GSet]
          log.debug("Received update g-set [{}]", set)
          val id = set.id
          val newSet = storage.findById[GSet](id) map { _ merge set } getOrElse { set }
          storage.store(newSet)
          context.system.eventStream.publish(newSet)
          log.debug("New merged g-set [{}]", newSet)

        case "2p-set" =>
          val set = json.as[TwoPhaseSet]
          log.debug("Received update 2p-set [{}]", set)
          val id = set.id
          val newSet = storage.findById[TwoPhaseSet](id) map { _ merge set } getOrElse { set }
          storage.store(newSet)
          context.system.eventStream.publish(newSet)
          log.debug("New merged 2p-set [{}]", newSet)

        case _ => log.error("Received JSON is not a CvRDT: {}", jsonString)
      }

    case unknown => log.error("Received unknown message: {}", unknown)
  }
}
