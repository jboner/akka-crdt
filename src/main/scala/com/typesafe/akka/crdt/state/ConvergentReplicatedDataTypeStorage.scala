/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package com.typesafe.akka.crdt.state

import scala.collection.JavaConversions.collectionAsScalaIterable

import akka.actor._
import akka.cluster.Cluster
import akka.cluster.ClusterEvent
import akka.cluster.ClusterEvent._

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
}

/**
 * Example:
 * {{{
 * }}}
 */
class ConvergentReplicatedDataTypeStorage(val sys: ExtendedActorSystem) extends Extension {
  private implicit val system = sys

  private val settings = new ConvergentReplicatedDataTypeSettings(system.settings.config, system.name)
  private val cluster = Cluster(system)

  private val nodes = new ConcurrentHashMap[Address, ActorRef]

  private val gCounters = new ConcurrentHashMap[String, IncrementingCounter]
  private val pnCounters = new ConcurrentHashMap[String, IncrementingDecrementingCounter]
  private val gSet = new ConcurrentHashMap[String, AddSet[_]]
  private val ppSet = new ConcurrentHashMap[String, AddRemoveSet[_]]

  private val changeListeners = new ConcurrentHashMap[String, Set[ActorRef]]

  private val clusterListenerDaemon = system.actorOf(Props(new Actor with ActorLogging {

    def register(address: Address) =
      nodes.put(address, system.actorFor(s"${address.toString}/user/cvrdt-change-listener"))

    def receive = {
      case state: CurrentClusterState ⇒ state.members foreach { member => register(member.address) }
      case MemberUp(member)           ⇒ register(member.address)
      case MemberDowned(member)       ⇒ nodes.remove(member.address)
      case MemberLeft(member)         ⇒ nodes.remove(member.address)
      case MemberExited(member)       ⇒ nodes.remove(member.address)
      case _: ClusterDomainEvent      ⇒ {} // ignore
    }
  }), name = "crdt:clusterListenerDaemon")

  cluster.subscribe(clusterListenerDaemon, classOf[ClusterDomainEvent])

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

  private val changeListenerDaemon = system.actorOf(
    Props[ConvergentReplicatedDataTypeChangeListener], name = "cvrdt-change-listener")

  def shutdown(): Unit = {
    system.stop(changeListenerDaemon)
    system.stop(clusterListenerDaemon)
  }

  // FIXME should be specialized
  def crdtFor(crdtId: String): ConvergentReplicatedDataType = {
    IncrementingCounter()
  }

  def subscribe(crdtId: String, listener: ActorRef): Unit = {
    if (!changeListeners.contains(crdtId)) throw new IllegalArgumentException(s"CRDT with id $crdtId can not be found")
    changeListeners.put(crdtId, changeListeners.get(crdtId) + listener)
  }

  def publish(event: ConvergentReplicatedDataType): Unit = {
    scala.collection.JavaConversions.collectionAsScalaIterable(nodes.values).foreach { _ ! event}
  }

  private[crdt] def publishChange(json: JsValue): Unit = {
    scala.collection.JavaConversions.collectionAsScalaIterable(nodes.values).foreach { _ ! stringify(json) }
  }
}

class ConvergentReplicatedDataTypeChangeListener extends Actor with ActorLogging {

  def receive = {
    case jsonString: String =>
      log.debug("Received JSON {}", jsonString)
      val json = parse(jsonString)

      (json \ "type").as[String] match {
        case "g-counter" =>
          val counter = json.as[IncrementingCounter]
          log.info("=================>>>> Received updated IncrementingCounter {}", counter)

        case "pn-counter" =>
          val counter = json.as[IncrementingDecrementingCounter]
          log.info("=================>>>> Received updated IncrementingDecrementingCounter {}", counter)

        case "g-set" =>
          // FIXME add type to JSON (string, json and binary/bytestring) then dispatch on it here
          //log.info("=================>>>> Received updated AddSet {}", set)
          // val set = (json \ "format").as[String] match {
          //   case "string" => json.as[AddSet[String]]
          //   case "json"   => json.as[AddSet[JsValue]]
          //   case "binary" => json.as[AddSet[ByteString]]
        case "2p-set" =>
          // FIXME add type to JSON (string, json and binary/bytestring) then dispatch on it here
          val set = json.as[AddRemoveSet[String]]
          log.info("=================>>>> Received updated AddRemoveSet {}", set)

        case _ => error("Received JSON is not a CvRDT: " + jsonString)
      }
  }
}
