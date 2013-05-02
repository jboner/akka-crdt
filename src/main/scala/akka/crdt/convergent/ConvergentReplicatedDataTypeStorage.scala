/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.crdt.convergent

import akka.actor._
import akka.event.{Logging, LogSource}
import akka.util.Reflect
import akka.contrib.pattern.DistributedPubSubExtension
import akka.contrib.pattern.DistributedPubSubMediator
import akka.contrib.pattern.DistributedPubSubMediator._

import play.api.libs.json.Json._
import play.api.libs.json._

import scala.util.{Try, Success, Failure}
import scala.reflect.ClassTag

import java.util.concurrent.ConcurrentHashMap

object ConvergentReplicatedDataTypeStorage
  extends ExtensionId[ConvergentReplicatedDataTypeStorage]
  with ExtensionIdProvider {

  override def get(system: ActorSystem): ConvergentReplicatedDataTypeStorage = super.get(system)

  override def lookup() = ConvergentReplicatedDataTypeStorage

  override def createExtension(system: ExtendedActorSystem): ConvergentReplicatedDataTypeStorage =
    new ConvergentReplicatedDataTypeStorage(system)

  implicit val logSource: LogSource[AnyRef] = new LogSource[AnyRef] {
    def genString(o: AnyRef): String = o.getClass.getName
    override def getClazz(o: AnyRef): Class[_] = o.getClass
  }
}

class ConvergentReplicatedDataTypeStorage(sys: ExtendedActorSystem) extends Extension {
  private implicit val system = sys

  val log = Logging(sys, this)

  private val settings = new ConvergentReplicatedDataTypeSettings(system.settings.config, system.name)

  private val storage = new ConcurrentHashMap[String, ConvergentReplicatedDataType]

  private val changeListeners = new ConcurrentHashMap[String, Set[ActorRef]]

  private val publisher = system.actorOf(Props[Publisher], name = "crdt:publisher")

  private val subscriber = system.actorOf(Props[Subscriber], name = "crdt:subscriber")

  def update(crdt: GCounter): GCounter = {
    publish(toJson(crdt))
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

  def findById[T : ClassTag](crdtId: String): Try[T] = Try {
    val crdt =
      if (storage.containsKey(crdtId)) storage.get(crdtId)
      else throw new NoSuchElementException("Could not find a CvRDT with id [" + crdtId + "]")
    val clazz = implicitly[ClassTag[T]].runtimeClass
    if (isCRDT(clazz)) crdt.asInstanceOf[T]
    else throw new ClassCastException("Could find CvRDT with id [" + crdtId + "] and type [" + clazz + "]")
  }

  def create[T : ClassTag](crdtId: String): Try[T] = Try {
    if (storage.containsKey(crdtId)) throw new IllegalArgumentException("Can't create a new CvRDT with id [" + crdtId + "], since it already exists")
    val clazz = implicitly[ClassTag[T]].runtimeClass
    if (classOf[GCounter].isAssignableFrom(clazz))         update(GCounter(crdtId)).asInstanceOf[T]
    else if (classOf[PNCounter].isAssignableFrom(clazz))   update(PNCounter(crdtId)).asInstanceOf[T]
    else if (classOf[GSet].isAssignableFrom(clazz))        update(GSet(crdtId)).asInstanceOf[T]
    else if (classOf[TwoPhaseSet].isAssignableFrom(clazz)) update(TwoPhaseSet(crdtId)).asInstanceOf[T]
    else throw new ClassCastException("Could create new CvRDT with id [" + crdtId + "] and type [" + clazz + "]")
  }

  def subscribe(crdtId: String, listener: ActorRef): Unit = {
    if (!changeListeners.containsKey(crdtId)) throw new IllegalArgumentException(s"CRDT with id $crdtId can not be found")
    changeListeners.put(crdtId, changeListeners.get(crdtId) + listener)
  }

  def shutdown(): Unit = {
    log.info("Shutting down ConvergentReplicatedDataTypeStorage")
    system.stop(subscriber)
    system.stop(publisher)
  }

  private[crdt] def storeInLocalStorage(crdt: ConvergentReplicatedDataType): Unit = storage.put(crdt.id, crdt)

  private def publish(json: JsValue): Unit = publisher ! json

  private def isCRDT(clazz: Class[_]): Boolean = {
    classOf[GCounter].isAssignableFrom(clazz) ||
    classOf[PNCounter].isAssignableFrom(clazz) ||
    classOf[GSet].isAssignableFrom(clazz) ||
    classOf[TwoPhaseSet].isAssignableFrom(clazz)
  }
}

/**
 * Subscribing on CRDT changes broadcasted by the Publisher.
 */
class Subscriber extends Actor with ActorLogging {
  val pubsub = DistributedPubSubExtension(context.system).mediator
  val storage = ConvergentReplicatedDataTypeStorage(context.system)

  override def preStart(): Unit = {
    log.info("Starting CvRDT change subscriber")
    pubsub ! Put(self)
  }

  def receive: Receive = {
    case jsonString: String =>
      log.info("Received updated CvRDT {}", jsonString)
      val json = parse(jsonString)

      (json \ "type").as[String] match {
        case "g-counter" =>
          val counter = json.as[GCounter]
          storage.findById[GCounter](counter.id) map { _ merge counter } recover { case _ => counter } foreach { storage.storeInLocalStorage(_) }

        case "pn-counter" =>
          val counter = json.as[PNCounter]
          storage.findById[PNCounter](counter.id) map { _ merge counter } recover { case _ => counter } foreach { storage.storeInLocalStorage(_) }

        case "g-set" =>
          val set = json.as[GSet]
          storage.findById[GSet](set.id) map { _ merge set } recover { case _ => set } foreach { storage.storeInLocalStorage(_) }

        case "2p-set" =>
          val set = json.as[TwoPhaseSet]
          storage.findById[TwoPhaseSet](set.id) map { _ merge set } recover { case _ => set } foreach { storage.storeInLocalStorage(_) }

        case _ => log.error("Received JSON is not a CvRDT: {}", jsonString)
      }

    case unknown => log.error("Received unknown message: {}", unknown)
  }
}

/**
 * Publishing (broadcasting) CRDT changes to all nodes with a Subscriber.
 */
class Publisher extends Actor with ActorLogging {
  val pubsub = DistributedPubSubExtension(context.system).mediator
  val subscriber = "/user/crdt:subscriber"

  log.info("Starting CvRDT change publisher")

  def receive = {
    case json: JsValue =>
      log.info("Broadcasting changes {}", json)
      pubsub ! SendToAll(subscriber, stringify(json))

    case unknown => log.error("Received unknown message: {}", unknown)
  }
}
