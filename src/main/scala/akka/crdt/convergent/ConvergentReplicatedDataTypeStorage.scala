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

  private val publisher = system.actorOf(Props[Publisher], name = "crdt:publisher")

  private val subscriber = system.actorOf(Props(new Subscriber with InMemoryStorage), name = "crdt:subscriber")

  @volatile private[crdt] var gCountersView 	= Map.empty[String, GCounter]
  @volatile private[crdt] var pnCountersView 	= Map.empty[String, PNCounter]
  @volatile private[crdt] var gSetView 				= Map.empty[String, GSet]
  @volatile private[crdt] var twoPhaseSetView = Map.empty[String, TwoPhaseSet]

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

  def getOrCreate[T : ClassTag](id: String): Try[T] = Try {
    val clazz = implicitly[ClassTag[T]].runtimeClass
    if (classOf[GCounter].isAssignableFrom(clazz)) {
    	(gCountersView.get(id) getOrElse update(GCounter(id))).asInstanceOf[T]
    } else if (classOf[PNCounter].isAssignableFrom(clazz))
    	(pnCountersView.get(id) getOrElse update(PNCounter(id))).asInstanceOf[T]
    else if (classOf[GSet].isAssignableFrom(clazz))
    	(gSetView.get(id) getOrElse update(GSet(id))).asInstanceOf[T]
    else if (classOf[TwoPhaseSet].isAssignableFrom(clazz))
    	(twoPhaseSetView.get(id) getOrElse update(TwoPhaseSet(id))).asInstanceOf[T]
    else throw new ClassCastException("Could create new CvRDT with id [" + id + "] and type [" + clazz + "]")
  }

  def shutdown(): Unit = {
    log.info("Shutting down ConvergentReplicatedDataTypeStorage")
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
class Subscriber extends Actor with ActorLogging { this: Storage =>
	val storage = ConvergentReplicatedDataTypeStorage(context.system)
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
          val newCounter = findById(counter) map { _ merge counter } getOrElse { counter }
          store(counter)
          context.system.eventStream.publish(newCounter)
          log.debug("New merged g-counter [{}]", newCounter)

        case "pn-counter" =>
          val counter = json.as[PNCounter]
          log.debug("Received update pn-counter[{}]", counter)
          val id = counter.id
          val newCounter = findById(counter) map { _ merge counter } getOrElse { counter }
          store(counter)
          context.system.eventStream.publish(newCounter)
          log.debug("New merged pn-counter [{}]", newCounter)

        case "g-set" =>
          val set = json.as[GSet]
          log.debug("Received update g-set [{}]", set)
          val id = set.id
          val newSet = findById(set) map { _ merge set } getOrElse { set }
          store(set)
          context.system.eventStream.publish(newSet)
          log.debug("New merged g-set [{}]", newSet)

        case "2p-set" =>
          val set = json.as[TwoPhaseSet]
          log.debug("Received update 2p-set [{}]", set)
          val id = set.id
          val newSet = findById(set) map { _ merge set } getOrElse { set }
          store(set)
          context.system.eventStream.publish(newSet)
          log.debug("New merged 2p-set [{}]", newSet)

        case _ => log.error("Received JSON is not a CvRDT: {}", jsonString)
      }

    case unknown => log.error("Received unknown message: {}", unknown)
  }
}

trait Storage {
  def findById(counter: GCounter): Option[GCounter]
  def findById(counter: PNCounter): Option[PNCounter]
  def findById(set: GSet): Option[GSet]
  def findById(set: TwoPhaseSet): Option[TwoPhaseSet]

  def store(counter: GCounter): Unit
  def store(counter: PNCounter): Unit
  def store(set: GSet): Unit
  def store(set: TwoPhaseSet): Unit
}

trait InMemoryStorage extends Storage { this: Subscriber =>

  var gCounters    = Map.empty[String, GCounter]
  var pnCounters   = Map.empty[String, PNCounter]
  var gSets        = Map.empty[String, GSet]
  var twoPhaseSets = Map.empty[String, TwoPhaseSet]

  def findById(counter: GCounter): Option[GCounter]   = gCounters.get(counter.id)
  def findById(counter: PNCounter): Option[PNCounter] = pnCounters.get(counter.id)
  def findById(set: GSet): Option[GSet]               = gSets.get(set.id)
  def findById(set: TwoPhaseSet): Option[TwoPhaseSet] = twoPhaseSets.get(set.id)

  def store(counter: GCounter): Unit = {
    gCounters = gCounters + (counter.id -> counter)
    storage.gCountersView = gCounters
  }

  def store(counter: PNCounter): Unit = {
    pnCounters = pnCounters + (counter.id -> counter)
    storage.pnCountersView = pnCounters
  }

  def store(set: GSet): Unit = {
    gSets = gSets + (set.id -> set)
    storage.gSetView = gSets
  }

  def store(set: TwoPhaseSet): Unit = {
    twoPhaseSets = twoPhaseSets + (set.id -> set)
    storage.twoPhaseSetView = twoPhaseSets
  }
}

trait BytecaskStorage extends Storage { this: Subscriber =>

  def findById(counter: GCounter): Option[GCounter] = {
    Some(counter)
  }

  def findById(counter: PNCounter): Option[PNCounter] = {
    Some(counter)
  }

  def findById(set: GSet): Option[GSet] = {
    Some(set)
  }

  def findById(set: TwoPhaseSet): Option[TwoPhaseSet] = {
    Some(set)
  }

  def store(counter: GCounter): Unit = {
  }

  def store(counter: PNCounter): Unit = {
  }

  def store(set: GSet): Unit = {
  }

  def store(set: TwoPhaseSet): Unit = {
  }
}
