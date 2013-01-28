/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package com.typesafe.akka.crdt

import java.io.File

import scala.concurrent.duration._

import akka.actor._
import akka.cluster.Cluster
import akka.cluster.ClusterEvent._

import org.eligosource.eventsourced.core._
import org.eligosource.eventsourced.journal.LeveldbJournalProps

/**
 * CRDT Extension Id and factory for creating CRDT extension.
 * Example:
 * {{{
 * }}}
 */
object CRDT extends ExtensionId[CRDT] with ExtensionIdProvider {
  override def get(system: ActorSystem): CRDT = super.get(system)

  override def lookup() = CRDT

  override def createExtension(system: ExtendedActorSystem): CRDT = new CRDT(system)
}

/**
 * Example:
 * {{{
 * }}}
 */
class CRDT(val sys: ExtendedActorSystem) extends Extension with ReliableRequestReply {
  implicit val system = sys

  val settings = new CRDTSettings(system.settings.config, system.name)

  /*
    Configuration
    -------------

    Processor --> reliable request-reply channel --> Destination
       ^                                                 |
       |                                                 |
       --------------------------------------------------

    In this example the Destination is local, but usually it is a remote
    actor accessed over an unreliable network. A Processor sends requests
    to a Destination and the Destination eventually replies.

    Properties
    ----------

    - Processor persists request and reply messages (Eventsourced processor)
      so that any state derived from past communications can be recovered
      during replay.
    - Request-reply is re-tried on destination failures (no response, failure
      response, network problems ...) but also during recovery after processor
      failures (JVM crashes, ...).
    - Processor will either receive a reply from Destination or a failure
      message (DestinationNotAvailable or DestinationFailure) from the
      reliable request-reply channel.
    - Delivery guarantees are at-least-once for both requests to Destination
      as well as replies to Processor.
    - Delivery confirmations (acknowledgements) are done by application logic.
      A request-reply is considered as complete when Processor positively
      confirms the receipt of the reply. Otherwise it is re-tried (either
      immediately or during recovery after a crash).
    - Idempotency of Processor and Destination is not covered yet by this
      example (but will be soon).
    - Reliable request-reply also can be used to deal with 'external updates'
      and 'external queries' as described by Martin Fowler
      * http://martinfowler.com/eaaDev/EventSourcing.html#ExternalUpdates
      * http://martinfowler.com/eaaDev/EventSourcing.html#ExternalQueries
    - Reliable request-reply channels can also be used by an event-sourced
      'error kernel' for communication with collaborators that may fail. In
      this example, the Processor is the error kernel and the channel is a
      child actor executing operations that may fail.
  */

  val journal: ActorRef = Journal(LeveldbJournalProps(new File("target/crdt")))
  val extension = EventsourcingExtension(system, journal)
  val cluster = Cluster(system)

  val clusterListener = system.actorOf(Props(new Actor with ActorLogging {
    def receive = {
      case state: CurrentClusterState ⇒
        log.info("Current members: {}", state.members)
      case MemberJoined(member) ⇒
        log.info("Member joined: {}", member)
      case MemberUp(member) ⇒
        log.info("Member is Up: {}", member)
      case UnreachableMember(member) ⇒
        log.info("Member detected as unreachable: {}", member)
      case _: ClusterDomainEvent ⇒ // ignore

    }
  }), name = "clusterListener")

  cluster.subscribe(clusterListener, classOf[ClusterDomainEvent])

  val destination = system.actorOf(Props(new Destination with Receiver))
  val processor = extension.processorOf(Props(new Processor(destination) with Emitter with Eventsourced), name = Some("processor"))

  extension.recover()

  // =================================================================================
  // START WORK

  processor ! Message(Req("hello"))

  // =================================================================================
  // DEFINITIONS

  case class Req(msg: String) // request
  case class Rep(msg: String) // reply

  /**
   * Eventsourced processor that communicates with `Destination` via a reliable request reply channel.
   */
  class Processor(destination: ActorRef) extends Actor { this: Emitter =>
    val id = 1

    // processor state
    var numReplies = 0

    // Create and register a reliable request-reply channel
    EventsourcingExtension(context.system).channelOf(ReliableRequestReplyChannelProps(1, destination)
      .withRedeliveryMax(3)
      .withRedeliveryDelay(0 seconds)
      .withRestartMax(1)
      .withRestartDelay(0 seconds)
      .withConfirmationTimeout(2 seconds)
      .withReplyTimeout(1 second))(context)

    override def receive = {
      case Req(msg) => {
        // emit request message to destination via reliable
        // request-reply channel
        emitter(1) sendEvent Req(msg)
        println("request: %s" format msg)
      }
      case Rep(msg) => {
        // update state
        numReplies += 1
        // positively confirm delivery of reply (so that channel
        // can deliver the next message)
        confirm(true)
        println("reply: %s" format msg)
      }
      case DestinationNotResponding(channelId, failureCount, request) => {
        // retry according to redelivery policy or escalate after
        // last redelivery attempt (requires a DeliveryStopped
        // listener on event stream which is not shown here ...)
        confirm(false)
        println("destination of channel %d not responding (%d)" format (channelId, failureCount))
      }
      case DestinationFailure(channelId, failureCount, Req(msg), throwable) => {
        // redeliver (i.e. retry) 3 times and proceed with next message
        // (queued by reliable channel) if failure persists
        confirm(failureCount > 2)
        println("destination of channel %d failed (%d)" format (channelId, failureCount))
      }
    }
  }

  class Destination extends Actor {
    var reply = false
    def receive = {
      case Req(msg) => {
        // only reply to every seconds request
        if (reply) sender ! Rep("re: %s".format(msg))
        reply = !reply
      }
    }
  }
}
