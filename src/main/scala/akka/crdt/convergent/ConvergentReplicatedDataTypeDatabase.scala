/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.crdt.convergent

import akka.crdt.RestServer
import akka.actor._
import akka.cluster.{ Cluster, Member, ClusterEvent }
import ClusterEvent._
import akka.event.{ Logging, LogSource, LoggingAdapter }
import akka.contrib.pattern.{ DistributedPubSubExtension, DistributedPubSubMediator }
import DistributedPubSubMediator._
import play.api.libs.json.Json.{ toJson, parse, stringify }
import play.api.libs.json.JsValue
import scala.util.Try
import scala.reflect.ClassTag
import scala.collection.immutable
import scala.concurrent.duration._
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
  //PN: perhaps a more specific error message here

  // immutable read-view of the current snapshots of members
  @volatile private var _members: immutable.SortedSet[Member] = immutable.SortedSet.empty[Member]
  //PN: I think you should keep a Set[Address] instead (you don't care and don't update the status anyway)

  // TODO: perhaps use gossip instead of broadcast using pub/sub?
  //PN: yes, I think this should be gossip based
  //    and if I understand this correctly you send the updated value only once, which means that
  //    things will not be eventually replicated if that message is lost
  // TODO: perhaps we should write to a (configurable) quorum instead of broadcasting to every node?

  // FIXME: perhaps use common supervisor for the pub/sub actors?
  private val publisher = system.actorOf(Props(classOf[Publisher], settings), name = "crdt:publisher")
  private val subscriber = system.actorOf(Props(classOf[Subscriber], storage), name = "crdt:subscriber")
  private val clusterListener = system.actorOf(Props(new Actor with ActorLogging {
    def receive = {
      case state: CurrentClusterState ⇒ _members = state.members
      case MemberUp(member)           ⇒ _members = _members + member
      case MemberRemoved(member)      ⇒ _members = _members - member
      case _: ClusterDomainEvent      ⇒ // ignore
    }
  }), name = "crdt:clusterListener")

  Cluster(system).subscribe(clusterListener, classOf[ClusterDomainEvent])

  system.registerOnTermination(shutdown())

  private val restServer = if (settings.RestServerRun) {
    val rs = new RestServer(this)
    rs.start()
    Some(rs)
  } else None

  // FIXME: Member has the right hostname but the wrong port (the cluster port) - replacing with configuration value, is this sufficient? 
  def nodes: immutable.Seq[(String, Int)] =
    _members.toVector map { _.address.host } collect { case Some(host) ⇒ (host, settings.RestServerPort) }

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

  def update(counter: GCounter): GCounter = {
    val newCounter = storage.findById[GCounter](counter.id) map { _ merge counter } getOrElse { counter } // merge if existing
    storage.store(newCounter) // store locally first
    publish(toJson(counter)) // publish to peers 
    newCounter
  }

  def update(counter: PNCounter): PNCounter = {
    val newCounter = storage.findById[PNCounter](counter.id) map { _ merge counter } getOrElse { counter }
    storage.store(newCounter)
    publish(toJson(counter))
    newCounter
  }

  def update(set: GSet): GSet = {
    val newSet = storage.findById[GSet](set.id) map { _ merge set } getOrElse { set }
    storage.store(newSet)
    publish(toJson(set))
    newSet
  }

  def update(set: TwoPhaseSet): TwoPhaseSet = {
    val newSet = storage.findById[TwoPhaseSet](set.id) map { _ merge set } getOrElse { set }
    storage.store(newSet)
    publish(toJson(set))
    newSet
  }

  //PN: would it be possible to make this generic (without knowing all existing CRDT types)?

  def shutdown(): Unit = {
    log.info("Shutting down ConvergentReplicatedDataTypeDatabase...")
    restServer foreach { _.shutdown() }
    system.stop(subscriber)
    system.stop(publisher)
    system.stop(clusterListener)
    storage.destroy()
    log.info("ConvergentReplicatedDataTypeDatabase shut down successfully")
  }

  private def publish(json: JsValue): Unit = publisher ! json
}

/**
 * Publishing (broadcasting) CRDT changes to all nodes with a Subscriber.
 * Uses a configurable batching window.
 */
class Publisher(settings: ConvergentReplicatedDataTypeSettings) extends Actor with ActorLogging {
  final val BatchingWindowInMillis = settings.BatchingWindow
  final val subscriber = "/user/crdt:subscriber"

  val pubsub = DistributedPubSubExtension(context.system).mediator

  // FIXME: Do not send a Seq with JSON strings across the wire - but plain JSON
  var batch: immutable.Seq[String] = _
  var batchingWindow: Deadline = _

  override def preStart(): Unit = {
    log.info("Starting CvRDT change publisher")
    newBatchingWindow()
  }

  def newBatchingWindow(): Unit = {
    batchingWindow = BatchingWindowInMillis.fromNow
    context setReceiveTimeout BatchingWindowInMillis
    batch = immutable.Seq.empty[String]
  }

  def sendBatch(): Unit = {
    if (!batch.isEmpty) { // only send a non-empty batch
      log.debug("Broadcasting changes {}", batch.mkString(", "))

      // FIXME Use 'pubsub ! SendToAll(subscriber, batch, allButSelf = true)' once next release of Akka 2.2 is out.
      pubsub ! SendToAll(subscriber, batch)
    }
    newBatchingWindow()
  }

  def receive = {
    case json: JsValue ⇒
      val jsonString = stringify(json)
      log.debug("Adding JSON to batch {}", jsonString)
      batch = batch :+ jsonString // add to batch    	
      if (batchingWindow.isOverdue) sendBatch() // if batching window is closed - ship batch and reset window
    //PN: else change receiveTimeout

    case ReceiveTimeout ⇒
      sendBatch() // if no messages within batching window - ship batch and reset window

    case unknown ⇒
      log.error("Received unknown message: {}", unknown)
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
    case batch: immutable.Seq[_] ⇒
      // group CRDTs by type to allow writing batches to native storage in an efficient way
      var gcounters = immutable.Seq.empty[GCounter]
      var pncounters = immutable.Seq.empty[PNCounter]
      var gsets = immutable.Seq.empty[GSet]
      var twopsets = immutable.Seq.empty[TwoPhaseSet]

      // TODO can we rewrite this in a functional (yet fast) way? 
      batch foreach { item ⇒
        item match {
          case jsonString: String ⇒
            val json = parse(jsonString)
            (json \ "type").as[String] match {

              case "g-counter" ⇒
                val counter = json.as[GCounter]
                val newCounter = storage.findById[GCounter](counter.id) map { _ merge counter } getOrElse { counter }
                gcounters = gcounters :+ newCounter
                context.system.eventStream.publish(newCounter)
                log.debug("Updated g-counter [{}]", newCounter)

              case "pn-counter" ⇒
                val counter = json.as[PNCounter]
                val newCounter = storage.findById[PNCounter](counter.id) map { _ merge counter } getOrElse { counter }
                pncounters = pncounters :+ newCounter
                context.system.eventStream.publish(newCounter)
                log.debug("Updated pn-counter [{}]", newCounter)

              case "g-set" ⇒
                val set = json.as[GSet]
                val newSet = storage.findById[GSet](set.id) map { _ merge set } getOrElse { set }
                gsets = gsets :+ newSet
                context.system.eventStream.publish(newSet)
                log.debug("Updated g-set [{}]", newSet)

              case "2p-set" ⇒
                val set = json.as[TwoPhaseSet]
                val newSet = storage.findById[TwoPhaseSet](set.id) map { _ merge set } getOrElse { set }
                twopsets = twopsets :+ newSet
                context.system.eventStream.publish(newSet)
                log.debug("Updated 2p-set [{}]", newSet)

              case _ ⇒ log.error("Received JSON is not a CvRDT: {}", jsonString)
            }
          case _ ⇒ throw new IllegalStateException("Received batch of non-String change sets: should not happen")
        }

        // store the batches
        if (!gcounters.isEmpty) storage.store(gcounters)
        if (!pncounters.isEmpty) storage.store(pncounters)
        if (!gsets.isEmpty) storage.store(gsets)
        if (!twopsets.isEmpty) storage.store(twopsets)
      }

    case unknown ⇒ throw new IllegalStateException(s"Received unknown message: $unknown")
  }
}
