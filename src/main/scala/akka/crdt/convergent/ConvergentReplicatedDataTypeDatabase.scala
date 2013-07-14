/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.crdt.convergent

import akka.crdt.RestServer
import akka.actor._
import akka.pattern.ask
import akka.util.Timeout
import akka.cluster.{ Cluster, Member, ClusterEvent }
import ClusterEvent._
import akka.event.{ Logging, LogSource, LoggingAdapter }
import akka.contrib.pattern.{ DistributedPubSubExtension, DistributedPubSubMediator }
import DistributedPubSubMediator.{ Put, SendToAll }
import play.api.libs.json.Json.{ toJson, parse, stringify }
import play.api.libs.json.JsValue
import scala.util.Try
import scala.reflect.ClassTag
import scala.collection.immutable
import scala.concurrent.duration._
import scala.concurrent.Future
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
  implicit val queryTimeout: Timeout = Timeout(10 seconds)

  private[akka] val storage: Storage =
    system.dynamicAccess.createInstanceFor[Storage](
      settings.StorageClass, List(
        (classOf[String], nodename),
        (classOf[ConvergentReplicatedDataTypeSettings], settings),
        (classOf[LoggingAdapter], log)))
      .getOrElse(throw new IllegalArgumentException("Could not instantiate Storage class ${settings.StorageClass}"))

  // immutable read-view of the current snapshots of members
  @volatile private var _members: immutable.Set[Address] = immutable.Set.empty[Address] + Cluster(system).selfAddress

  // FIXME: perhaps use common supervisor for the pub/sub actors?
  private val publisher = system.actorOf(Props(classOf[Publisher], settings), name = "crdt:publisher")
  private val subscriber = system.actorOf(Props(classOf[Subscriber], this), name = "crdt:subscriber")
  private val clusterListener = system.actorOf(Props(new Actor with ActorLogging {
    def receive = {
      case state: CurrentClusterState ⇒ _members = _members ++ state.members.map(_.address)
      case MemberUp(member)           ⇒ _members = _members + member.address
      case MemberRemoved(member, _)   ⇒ _members = _members - member.address
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
    _members.toVector map { _.host } collect { case Some(host) ⇒ (host, settings.RestServerPort) }

  def findById[T <: ConvergentReplicatedDataType: ClassTag](id: String = UUID.randomUUID.toString): Future[T] = {
    (subscriber ? Subscriber.FindById(id, implicitly[ClassTag[T]].runtimeClass)).mapTo[T]
  }

  def create[T <: ConvergentReplicatedDataType: ClassTag](id: String = UUID.randomUUID.toString): Future[T] = {
    (subscriber ? Subscriber.Create(id, implicitly[ClassTag[T]].runtimeClass)).mapTo[T]
  }

  def update(counter: GCounter): GCounter = {
    replicate(toJson(counter))
    counter
  }

  def update(counter: PNCounter): PNCounter = {
    replicate(toJson(counter))
    counter
  }

  def update(set: GSet): GSet = {
    replicate(toJson(set))
    set
  }

  def update(set: TwoPhaseSet): TwoPhaseSet = {
    replicate(toJson(set))
    set
  }

  def shutdown(): Unit = {
    log.info("Shutting down ConvergentReplicatedDataTypeDatabase...")
    restServer foreach { _.shutdown() }
    system.stop(subscriber)
    system.stop(publisher)
    system.stop(clusterListener)
    storage.destroy()
    log.info("ConvergentReplicatedDataTypeDatabase shut down successfully")
  }

  private def replicate(json: JsValue): Unit = publisher ! json
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

      // FIXME wait for ACK - if timeout then resend

      // FIXME
      //    Need explicit connection management - not pubsub
      //    Send off batch to connection and to an ACK actor that awaits the ACK and resends if needed
      //    Cluster listener needs to clean up stale connections in the ACK actor
      //    Create is just a subset of Update - so should not be treated special with direct write to subscriber
      //         or (now with explicit connections) should both Create and Update perform direct write before replication?

      pubsub ! SendToAll(subscriber, Subscriber.Update(batch), allButSelf = false)
      //pubsub ! SendToAll(subscriber, Subscriber.Update(batch), allButSelf = true)
    }
    newBatchingWindow()
  }

  def receive = {
    case json: JsValue ⇒
      val jsonString = stringify(json)
      log.debug("Adding JSON to batch {}", jsonString)
      batch = batch :+ jsonString // add to batch
      if (batchingWindow.isOverdue) sendBatch() // if batching window is closed - ship batch and reset window

    case ReceiveTimeout ⇒
      sendBatch() // if no messages within batching window - ship batch and reset window

    case unknown ⇒
      log.error("Received unknown message: {}", unknown)
  }
}

object Subscriber {
  // FIXME Create Protobuf messages for these case classes
  case class Create(id: String, clazz: Class[_])
  case class FindById(id: String, clazz: Class[_])
  case class Update(batch: immutable.Seq[_])
}

/**
 * Subscribing on CRDT changes broadcasted by the Publisher.
 */
class Subscriber(database: ConvergentReplicatedDataTypeDatabase) extends Actor with ActorLogging {
  import Subscriber._
  import database.{ update ⇒ replicate, storage }

  val pubsub = DistributedPubSubExtension(context.system).mediator

  override def preStart(): Unit = {
    log.info("Starting CvRDT change subscriber")
    pubsub ! Put(self)
  }

  def receive: Receive = {
    case Create(id, clazz) ⇒
      // FIXME this now stores the same CRDT two times in the local storage - once in 'store' and once in 'replicate'

      require(!storage.exists(id), s"Can't create new CvRDT with id = $id - already exists")
      val crdt =
        if (classOf[GCounter].isAssignableFrom(clazz)) {
          val counter = GCounter(id)
          storage.store(counter)
          replicate(counter)
        } else if (classOf[PNCounter].isAssignableFrom(clazz)) {
          val counter = PNCounter(id)
          storage.store(counter)
          replicate(counter)
        } else if (classOf[GSet].isAssignableFrom(clazz)) {
          val set = GSet(id)
          storage.store(set)
          replicate(set)
        } else if (classOf[TwoPhaseSet].isAssignableFrom(clazz)) {
          val set = TwoPhaseSet(id)
          storage.store(set)
          replicate(set)
        } else throw new ClassCastException(s"Could create new CvRDT with id [$id] and type [$clazz]")
      println("===============>>>>>>>>>>>>>>>>>>>>> STORING CRDT " + crdt)
      sender ! crdt

    case FindById(id, clazz) ⇒
      val crdt =
        if (classOf[GCounter].isAssignableFrom(clazz)) storage.findById[GCounter](id)
        else if (classOf[PNCounter].isAssignableFrom(clazz)) storage.findById[PNCounter](id)
        else if (classOf[GSet].isAssignableFrom(clazz)) storage.findById[GSet](id)
        else if (classOf[TwoPhaseSet].isAssignableFrom(clazz)) storage.findById[TwoPhaseSet](id)
        else throw new ClassCastException(s"Could create new CvRDT with id [$id] and type [$clazz]")
      println("===============>>>>>>>>>>>>>>>>>>>>> FOUND CRDT " + crdt)
      sender ! crdt

    case Update(batch) ⇒
      var crdts = immutable.Seq.empty[ConvergentReplicatedDataType]
      // TODO can we rewrite this in a functional (yet fast) way?
      batch foreach { item ⇒
        item match {
          case jsonString: String ⇒
            val json = parse(jsonString)
            (json \ "type").as[String] match {

              case "g-counter" ⇒
                val counter = json.as[GCounter]
                val newCounter = storage.findById[GCounter](counter.id) map { _ merge counter } getOrElse { counter }
                crdts = crdts :+ newCounter
                context.system.eventStream.publish(newCounter)
                log.debug("Updated g-counter [{}]", newCounter)

              case "pn-counter" ⇒
                val counter = json.as[PNCounter]
                val newCounter = storage.findById[PNCounter](counter.id) map { _ merge counter } getOrElse { counter }
                crdts = crdts :+ newCounter
                context.system.eventStream.publish(newCounter)
                log.debug("Updated pn-counter [{}]", newCounter)

              case "g-set" ⇒
                val set = json.as[GSet]
                val newSet = storage.findById[GSet](set.id) map { _ merge set } getOrElse { set }
                crdts = crdts :+ newSet
                context.system.eventStream.publish(newSet)
                log.debug("Updated g-set [{}]", newSet)

              case "2p-set" ⇒
                val set = json.as[TwoPhaseSet]
                val newSet = storage.findById[TwoPhaseSet](set.id) map { _ merge set } getOrElse { set }
                crdts = crdts :+ newSet
                context.system.eventStream.publish(newSet)
                log.debug("Updated 2p-set [{}]", newSet)

              case _ ⇒ log.error("Received JSON is not a CvRDT: {}", jsonString)
            }
          case _ ⇒ throw new IllegalStateException("Received batch of non-String change sets: should not happen")
        }
        storage.store(crdts)
      }

    case unknown ⇒ throw new IllegalStateException(s"Received unknown message: $unknown")
  }
}
