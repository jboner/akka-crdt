/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package com.typesafe.akka.crdt.state

import scala.collection.JavaConversions.collectionAsScalaIterable

import akka.actor._

import akka.cluster.Cluster
import akka.cluster.ClusterEvent._

import org.eligosource.eventsourced.core._
import org.eligosource.eventsourced.journal.inmem.InmemJournalProps

import java.util.concurrent.ConcurrentHashMap

object ConvergentReplicatedDataTypeStorage extends ExtensionId[ConvergentReplicatedDataTypeStorage] with ExtensionIdProvider {
  override def get(system: ActorSystem): ConvergentReplicatedDataTypeStorage = super.get(system)

  override def lookup() = ConvergentReplicatedDataTypeStorage

  override def createExtension(system: ExtendedActorSystem): ConvergentReplicatedDataTypeStorage = new ConvergentReplicatedDataTypeStorage(system)
}

/**
 * Example:
 * {{{
 * }}}
 */
class ConvergentReplicatedDataTypeStorage(val sys: ExtendedActorSystem) extends Extension {
  implicit val system = sys

  val settings = new ConvergentReplicatedDataTypeSettings(system.settings.config, system.name)
  val journal: ActorRef = Journal(InmemJournalProps(Some("cvrdt")))
  val extension = EventsourcingExtension(system, journal)
  val cluster = Cluster(system)

  private val nodes = new ConcurrentHashMap[Address, ActorRef]

  val clusterListener = system.actorOf(Props(new Actor with ActorLogging {
    def register(address: Address) = nodes.put(address, system.actorFor(s"${address.toString}/user/cvrdt-event-channel"))

    def receive = {
      case state: CurrentClusterState ⇒ state.members foreach { member => register(member.address) }
      case MemberUp(member)           ⇒ register(member.address)
      case MemberDowned(member)       ⇒ nodes.remove(member.address)
      case MemberLeft(member)         ⇒ nodes.remove(member.address)
      case MemberExited(member)       ⇒ nodes.remove(member.address)
      case _: ClusterDomainEvent      ⇒ {} // ignore
    }
  }), name = "crdt:clusterListener")

  cluster.subscribe(clusterListener, classOf[ClusterDomainEvent])

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
  val channel = system.actorOf(Props[ConvergentReplicatedDataTypeEventReceiver], name = "cvrdt-event-channel")

  def publish(event: ConvergentReplicatedDataType): Unit = {
    scala.collection.JavaConversions.collectionAsScalaIterable(nodes.values).foreach { _ ! event}
  }
}

class ConvergentReplicatedDataTypeEventReceiver extends Actor with ActorLogging {
  def receive = {
    case event: ConvergentReplicatedDataType =>
      log.debug("Received updated CvRDT {}", event)
  }
}
