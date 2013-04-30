/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package com.typesafe.akka.crdt.commutative

import scala.collection.JavaConversions.collectionAsScalaIterable

import akka.actor._
import akka.event.{Logging, LogSource}
import akka.contrib.pattern.DistributedPubSubExtension
import akka.contrib.pattern.DistributedPubSubMediator
import akka.contrib.pattern.DistributedPubSubMediator._

import play.api.libs.json.Json._
import play.api.libs.json._

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

class ConvergentReplicatedDataTypeStorage(val sys: ExtendedActorSystem) extends Extension {
  private implicit val system = sys

  val log = Logging(sys, this)

  private val settings = new ConvergentReplicatedDataTypeSettings(system.settings.config, system.name)

  private val gCounters = new ConcurrentHashMap[String, IncrementingCounter]
  private val pnCounters = new ConcurrentHashMap[String, IncrementingDecrementingCounter]
  private val gSet = new ConcurrentHashMap[String, AddSet]
  private val ppSet = new ConcurrentHashMap[String, AddRemoveSet]

  private val changeListeners = new ConcurrentHashMap[String, Set[ActorRef]]

  private val publisher = system.actorOf(Props[Publisher], name = "crdt:publisher")

  private val subscriber = system.actorOf(Props(new Actor with ActorLogging {
    val pubsub = DistributedPubSubExtension(context.system).mediator

    override def preStart(): Unit = {
      pubsub ! Subscribe("g-counter", self)
      pubsub ! Subscribe("pn-counter", self)
      pubsub ! Subscribe("g-set", self)
      pubsub ! Subscribe("2p-set", self)
    }

    def receive: Receive = {
      case SubscribeAck(Subscribe("g-counter", `self`)) ⇒
      case SubscribeAck(Subscribe("pn-counter", `self`)) ⇒
      case SubscribeAck(Subscribe("g-set", `self`)) ⇒
      case SubscribeAck(Subscribe("2p-set", `self`)) ⇒
    }
  }), name = "crdt:subscriber")

  // private val clusterListenerDaemon = system.actorOf(Props(new Actor with ActorLogging {

  //   def register(address: Address) =
  //     nodes.put(address, system.actorFor(s"${address.toString}/user/cvrdt-change-listener"))

  //   def receive = {
  //     case state: CurrentClusterState ⇒ state.members foreach { member => register(member.address) }
  //     case MemberUp(member)           ⇒ register(member.address)
  //     case MemberDowned(member)       ⇒ nodes.remove(member.address)
  //     case MemberLeft(member)         ⇒ nodes.remove(member.address)
  //     case MemberExited(member)       ⇒ nodes.remove(member.address)
  //     case _: ClusterDomainEvent      ⇒ {} // ignore
  //   }
  // }), name = "crdt:clusterListenerDaemon")

  // cluster.subscribe(clusterListenerDaemon, classOf[ClusterDomainEvent])

  /*
  **
 * INTERNAL API.
 *
 * ClusterCoreDaemon and ClusterDomainEventPublisher can't be restarted because the state
 * would be obsolete. Shutdown the member if any those actors crashed.
 *
private[cluster] final class ClusterCoreSupervisor extends Actor with ActorLogging {
  import InternalClusterAction._

  val publisher = context.actorOf(Props[ClusterDomainEventPublisher].
    withDispatcher(context.props.dispatcher), name = "publisher")
  val coreDaemon = context.watch(context.actorOf(Props(new ClusterCoreDaemon(publisher)).
    withDispatcher(context.props.dispatcher), name = "daemon"))

  context.parent ! PublisherCreated(publisher)

  override val supervisorStrategy =
    OneForOneStrategy() {
      case NonFatal(e) ⇒
        log.error(e, "Cluster node [{}] crashed, [{}] - shutting down...", Cluster(context.system).selfAddress, e.getMessage)
        self ! PoisonPill
        Stop
    }

  override def postStop(): Unit = Cluster(context.system).shutdown()

  def receive = {
    case InternalClusterAction.GetClusterCoreRef ⇒ sender ! coreDaemon
  }
}*/

  def shutdown(): Unit = {
    log.info("Shutting down ConvergentReplicatedDataTypeStorage")
    system.stop(subscriber)
    system.stop(publisher)
  }

  // FIXME should be specialized
  def crdtFor(crdtId: String): ConvergentReplicatedDataType = {
    IncrementingCounter()
  }

  def subscribe(crdtId: String, listener: ActorRef): Unit = {
    if (!changeListeners.contains(crdtId)) throw new IllegalArgumentException(s"CRDT with id $crdtId can not be found")
    changeListeners.put(crdtId, changeListeners.get(crdtId) + listener)
  }

  def publish(crdt: IncrementingCounter): Unit             = publish(toJson(crdt))
  def publish(crdt: IncrementingDecrementingCounter): Unit = publish(toJson(crdt))
  def publish(crdt: AddSet): Unit                          = publish(toJson(crdt))
  def publish(crdt: AddRemoveSet): Unit                    = publish(toJson(crdt))
  def publish(json: JsValue): Unit                         = publisher ! stringify(json)
}

class Publisher extends Actor with ActorLogging {
  val pubsub = DistributedPubSubExtension(context.system).mediator

  def receive = {
    case jsonString: String =>
      log.debug("Received JSON {}", jsonString)
      val json = parse(jsonString)

      (json \ "type").as[String] match {
        case "g-counter" =>
          val counter = json.as[IncrementingCounter]
          log.info("=================>>>> Received updated IncrementingCounter {}", counter)
          pubsub ! Publish("g-counter", counter)

        case "pn-counter" =>
          val counter = json.as[IncrementingDecrementingCounter]
          log.info("=================>>>> Received updated IncrementingDecrementingCounter {}", counter)
          pubsub ! Publish("pn-counter", counter)

        case "g-set" =>
          val set = json.as[AddSet]
          log.info("=================>>>> Received updated AddSet {}", set)
          pubsub ! Publish("g-set", set)

        case "2p-set" =>
          val set = json.as[AddRemoveSet]
          log.info("=================>>>> Received updated AddRemoveSet {}", set)
          pubsub ! Publish("2p-set", set)

        case _ => error("Received JSON is not a CvRDT: " + jsonString)
      }
  }
}
