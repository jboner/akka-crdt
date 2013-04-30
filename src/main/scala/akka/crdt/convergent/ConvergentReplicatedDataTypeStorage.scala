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

import scala.util.Try
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

  private val cvrdts = new ConcurrentHashMap[String, ConvergentReplicatedDataType]

  private val changeListeners = new ConcurrentHashMap[String, Set[ActorRef]]

  private val publisher = system.actorOf(Props[Publisher], name = "crdt:publisher")
  private val subscriber = system.actorOf(Props[Subscriber], name = "crdt:subscriber")

  def shutdown(): Unit = {
    log.info("Shutting down ConvergentReplicatedDataTypeStorage")
    system.stop(subscriber)
    system.stop(publisher)
  }

  def store(crdt: GCounter): Unit = {
    cvrdts.put(crdt.id, crdt)
    store(toJson(crdt))
  }

  def store(crdt: PNCounter): Unit = {
    cvrdts.put(crdt.id, crdt)
    store(toJson(crdt))
  }

  def store(crdt: GSet): Unit = {
    cvrdts.put(crdt.id, crdt)
    store(toJson(crdt))
  }

  def store(crdt: TwoPhaseSet): Unit = {
    cvrdts.put(crdt.id, crdt)
    store(toJson(crdt))
  }

  def findById[T : ClassTag](crdtId: String): Try[T] = Try {
    val crdt =
      if (cvrdts.contains(crdtId)) cvrdts.get(crdtId)
      else throw new NoSuchElementException("Could not find a CvRDT with id [" + crdtId + "]")
    val clazz = implicitly[ClassTag[T]].runtimeClass
    if (isCRDT(clazz)) crdt.asInstanceOf[T]
    else throw new ClassCastException("Could find CvRDT with id [" + crdtId + "] and type [" + clazz + "]")
  }

  def create[T : ClassTag](crdtId: String): Try[T] = Try {
    if (cvrdts.contains(crdtId)) throw new IllegalArgumentException("Can't create a new CvRDT with id [" + crdtId + "], since it already exists")
    val clazz = implicitly[ClassTag[T]].runtimeClass
    if (isCRDT(clazz)) Reflect.instantiate(clazz, crdtId).asInstanceOf[T]
    else throw new ClassCastException("Could create new CvRDT with id [" + crdtId + "] and type [" + clazz + "]")
  }

  def subscribe(crdtId: String, listener: ActorRef): Unit = {
    if (!changeListeners.contains(crdtId)) throw new IllegalArgumentException(s"CRDT with id $crdtId can not be found")
    changeListeners.put(crdtId, changeListeners.get(crdtId) + listener)
  }

  private def isCRDT(clazz: Class[_]): Boolean = {
    classOf[GCounter].isAssignableFrom(clazz) ||
    classOf[PNCounter].isAssignableFrom(clazz) ||
    classOf[GSet].isAssignableFrom(clazz) ||
    classOf[TwoPhaseSet].isAssignableFrom(clazz)
  }

  private def store(json: JsValue): Unit = publisher ! json
}

/**
 * Subscribing on CRDT changes broadcasted by the Publisher.
 */
class Subscriber extends Actor with ActorLogging {
  val pubsub = DistributedPubSubExtension(context.system).mediator

  override def preStart(): Unit = {
    log.info("Starting CvRDT change subscriber")
    pubsub ! Put(self)
  }

  def receive: Receive = {
    case jsonString: String =>
      log.debug("Received JSON {}", jsonString)
      val json = parse(jsonString)

      (json \ "type").as[String] match {
        case "g-counter" =>
          val counter = json.as[GCounter]
          log.info("=================>>>> Received updated GCounter {}", counter)

        case "pn-counter" =>
          val counter = json.as[PNCounter]
          log.info("=================>>>> Received updated PNCounter {}", counter)

        case "g-set" =>
          val set = json.as[GSet]
          log.info("=================>>>> Received updated GSet {}", set)

        case "2p-set" =>
          val set = json.as[TwoPhaseSet]
          log.info("=================>>>> Received updated TwoPhaseSet {}", set)

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

  log.info("Starting CvRDT change publisher")

  def receive = {
    case json: JsValue =>
      log.info("=================>>>> Broadcasting changes {}", json)
      pubsub ! SendToAll("/user/crdt:subscriber", stringify(json))

    case unknown => log.error("Received unknown message: {}", unknown)
  }
}
