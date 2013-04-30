// /**
//  * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
//  */

// package com.typesafe.akka.crdt.commutative

// import scala.concurrent.duration._
// import scala.collection.immutable

// import akka.actor._
// import akka.cluster.Cluster
// import akka.cluster.ClusterEvent._

// import org.eligosource.eventsourced.core._
// import org.eligosource.eventsourced.journal.inmem.InmemJournalProps
// import org.eligosource.eventsourced.patterns.reliable.requestreply._

// trait CommutativeReplicatedDataType {
//   def `type`: String
// }

// trait CommutativeReplicatedDataTypeCounter extends CommutativeReplicatedDataType {
//   def value: Int
// }

// trait CommutativeReplicatedDataTypeSet[T] extends CommutativeReplicatedDataType {

//   def toSet: immutable.Set[T]

//   def toSeq: immutable.Seq[T] = toSet.toVector

//   def contains(element: T): Boolean = toSet contains element

//   def foreach(f: T => Unit): Unit = toSeq foreach f

//   def isEmpty: Boolean = toSeq.isEmpty

//   def size: Int = toSeq.size
// }

// object CommutativeReplicatedDataTypeStorage extends ExtensionId[CommutativeReplicatedDataTypeStorage] with ExtensionIdProvider {
//   override def get(system: ActorSystem): CommutativeReplicatedDataTypeStorage = super.get(system)

//   override def lookup() = CommutativeReplicatedDataTypeStorage

//   override def createExtension(system: ExtendedActorSystem): CommutativeReplicatedDataTypeStorage = new CommutativeReplicatedDataTypeStorage(system)
// }

// /**
//  * Example:
//  * {{{
//  * }}}
//  */
// class CommutativeReplicatedDataTypeStorage(val sys: ExtendedActorSystem) extends Extension {
//   implicit val system = sys

//   val settings = new CommutativeReplicatedDataTypeSettings(system.settings.config, system.name)
//   val journal: ActorRef = Journal(InmemJournalProps(Some("target/crdt")))
//   val extension = EventsourcingExtension(system, journal)
//   val cluster = Cluster(system)

//   val clusterListener = system.actorOf(Props(new Actor with ActorLogging {
//     def receive = {
//       case state: CurrentClusterState ⇒
//         log.info("Current members: {}", state.members)
//       case MemberJoined(member) ⇒
//         log.info("Member joined: {}", member)
//       case MemberUp(member) ⇒
//         log.info("Member is Up: {}", member)
//       case UnreachableMember(member) ⇒
//         log.info("Member detected as unreachable: {}", member)
//       case _: ClusterDomainEvent ⇒ // ignore

//     }
//   }), name = "crdt:clusterListener")

//   cluster.subscribe(clusterListener, classOf[ClusterDomainEvent])

//   val destination = system.actorOf(Props(new Destination with Receiver with Confirm))

//   val processor = extension.processorOf(Props(
// //    new Processor(destination) with ProcessorIdempotency with Emitter with Eventsourced { val id = 1 } ),
//     new Processor(destination) with Emitter with Eventsourced { val id = 1 } ),
//     name = Some("crdt:processor"))

//   extension.recover()

//   // =================================================================================
//   // START WORK

//   processor ! Message(Req("hello"))

//   // =================================================================================
//   // DEFINITIONS

//   case class Req(msg: String) // request
//   case class Rep(msg: String) // reply

//   /**
//    * Eventsourced processor that communicates with `Destination` via a reliable request reply channel.
//    */
//   class Processor(destination: ActorRef) extends Actor { this: Emitter =>

//     var numReplies = 0

//     // FIXME take values from config
//     EventsourcingExtension(context.system).channelOf(ReliableRequestReplyChannelProps(1, destination)
//       .withRedeliveryMax(3)
//       .withRedeliveryDelay(0 seconds)
//       .withRestartMax(1)
//       .withRestartDelay(0 seconds)
//       .withConfirmationTimeout(2 seconds)
//       .withReplyTimeout(1 second))(context)

//     override def receive = {
//       case Req(msg) => {
//         // emit request message to destination via reliable
//         // request-reply channel
//         emitter(1) sendEvent Req(msg)
//         println("request: %s" format msg)
//       }
//       case Rep(msg) => {
//         // update state
//         numReplies += 1
//         // positively confirm delivery of reply (so that channel
//         // can deliver the next message)
//         confirm(true)
//         println("reply: %s" format msg)
//       }
//       case DestinationNotResponding(channelId, failureCount, request) => {
//         // retry according to redelivery policy or escalate after
//         // last redelivery attempt (requires a DeliveryStopped
//         // listener on event stream which is not shown here ...)
//         confirm(false)
//         println("destination of channel %d not responding (%d)" format (channelId, failureCount))
//       }
//       case DestinationFailure(channelId, failureCount, Req(msg), throwable) => {
//         // redeliver (i.e. retry) 3 times and proceed with next message
//         // (queued by reliable channel) if failure persists
//         confirm(failureCount > 2)
//         println("destination of channel %d failed (%d)" format (channelId, failureCount))
//       }
//     }
//   }

//   // trait ProcessorIdempotency extends Actor { this: Receiver =>
//   //   var lastReplyId = 0L
//   //   // - only detect reply duplicates and ignore failure duplicates
//   //   //   (i.e. DestinationNotResponding and DestinationFailure)
//   //   abstract override def receive = {
//   //     case r @ Rep(_, id) => if (id <= lastReplyId) {
//   //       println("reply duplicate: %s" format r)
//   //       confirm(true)
//   //     } else {
//   //       lastReplyId = id
//   //       super.receive(r)
//   //     }
//   //     case msg => super.receive(msg)
//   //   }
//   // }

//   class Destination extends Actor {
//     var reply = false
//     def receive = {
//       case Req(msg) => {
//         // only reply to every seconds request
//         if (reply) sender ! Rep("re: %s".format(msg))
//         reply = !reply
//       }
//     }
//   }
// }
