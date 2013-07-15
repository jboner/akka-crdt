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
import play.api.libs.json.Json.{ toJson, parse, stringify }
import play.api.libs.json.JsValue
import scala.util.{ Try, Success, Failure }
import scala.reflect.ClassTag
import scala.collection.immutable
import scala.concurrent.duration._
import scala.concurrent.{ Future, Promise }
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
  import system.dispatcher

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

  // FIXME: perhaps use common supervisor for the pub/sub actors?
  // FIXME move props to companion object
  private val replicator = system.actorOf(Props(classOf[Replicator], settings), name = "crdt:replicator")
  private val subscriber = system.actorOf(Props(classOf[Subscriber], this), name = "crdt:subscriber")

  system.registerOnTermination(shutdown())

  private val restServer = if (settings.RestServerRun) {
    val rs = new RestServer(this)
    rs.start()
    Some(rs)
  } else None

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
    system.stop(replicator)
    storage.destroy()
    log.info("ConvergentReplicatedDataTypeDatabase shut down successfully")
  }

  def findById[T <: ConvergentReplicatedDataType: ClassTag](id: String = UUID.randomUUID.toString): Future[T] = {
    val promise = Promise[T]()
    (subscriber ? Subscriber.FindById(id, implicitly[ClassTag[T]].runtimeClass)).mapTo[Try[T]] foreach { promise complete _ }
    promise.future
  }

  def create[T <: ConvergentReplicatedDataType: ClassTag](id: String = UUID.randomUUID.toString): Future[T] = {
    (subscriber ? Subscriber.Create(id, implicitly[ClassTag[T]].runtimeClass)).mapTo[T]
  }

  private def replicate(json: JsValue): Unit = replicator ! Replicator.Replicate(json)
}

object Replicator {
  // FIXME Create Protobuf messages for the Replicate and Ack case classes
  case class Replicate(json: JsValue)
  case class Ack(address: Address)

  case class MembersChange(addresses: immutable.Set[Address])
  case class VerifyAckFor(address: Address, changeSet: Subscriber.ChangeSet)
  case object ResendChangeSets
}

/**
 * Replicating CRDT changes to all member nodes.
 * Keeps retrying until an ACK is received or the node is leaving the cluster.
 * Uses a configurable batching window.
 */
class Replicator(settings: ConvergentReplicatedDataTypeSettings) extends Actor with ActorLogging { replicator ⇒
  import Replicator._
  import Subscriber._
  import context.dispatcher
  import settings._

  var addresses: immutable.Set[Address] = immutable.Set.empty[Address] + Cluster(context.system).selfAddress
  // FIXME: Do not send a Seq with JSON strings across the wire - but plain JSON
  var batch: immutable.Seq[String] = _
  var batchingWindow: Deadline = _
  val subscriberPath = "/user/crdt:subscriber"

  val resender = context.system.actorOf(Props(new Actor with ActorLogging {
    var addresses: immutable.Set[Address] = replicator.addresses
    var changeSets = immutable.Map.empty[Address, ChangeSet]

    val resendingInterval: FiniteDuration = 2 seconds // FIXME configurable

    def receive = {
      case MembersChange(newAddresses) ⇒
        addresses = newAddresses
        log.debug("Member set have changed - new set {}", addresses.mkString(", "))

      case VerifyAckFor(address, changeSet) ⇒
        changeSets += (address -> changeSet)

      case Ack(address) ⇒
        changeSets -= address

      case ResendChangeSets ⇒
        changeSets foreach {
          case (address, changeSet) ⇒
            log.debug("Resending change set to {}", address)
            context.actorSelection(address + subscriberPath) ! changeSet
        }
    }

    override def preStart(): Unit = {
      context.system.scheduler.schedule(resendingInterval, resendingInterval, self, ResendChangeSets)
    }
  }), name = "resender")

  override def preStart(): Unit = {
    log.info("Starting CvRDT replicator")
    newBatchingWindow()
    Cluster(context.system).subscribe(self, classOf[ClusterDomainEvent])
  }

  def newBatchingWindow(): Unit = {
    batchingWindow = BatchingWindow.fromNow
    context setReceiveTimeout BatchingWindow
    batch = immutable.Seq.empty[String]
  }

  def replicateBatch(): Unit = {
    if (!batch.isEmpty) { // only send a non-empty batch
      log.debug("Replicating batch {}", batch.mkString(", "))

      // FIXME Create is just a subset of Update - so should not be treated special with direct write to subscriber or (now with explicit connections) should both Create and Update perform direct write before replication?
      val changeSet = ChangeSet(batch)
      addresses foreach { address ⇒
        resender ! VerifyAckFor(address, changeSet)
        context.actorSelection(address + subscriberPath) tell (changeSet, resender)
      }
    }
    newBatchingWindow()
  }

  def receive = {
    case Replicate(json) ⇒
      val jsonString = stringify(json)
      log.debug("Adding JSON to batch {}", jsonString)
      batch = batch :+ jsonString // add to batch
      if (batchingWindow.isOverdue) replicateBatch() // if batching window is closed - ship batch and reset window

    case ReceiveTimeout ⇒
      replicateBatch() // if no messages within batching window - ship batch and reset window

    case state: CurrentClusterState ⇒
      addresses = addresses ++ state.members.map(_.address)
      resender ! MembersChange(addresses)

    case MemberUp(member) ⇒
      addresses = addresses + member.address
      resender ! MembersChange(addresses)

    case MemberRemoved(member, _) ⇒
      addresses = addresses - member.address
      resender ! MembersChange(addresses)

    case _: ClusterDomainEvent ⇒
    // ignore

    case unknown ⇒
      log.error("Received unknown message: {}", unknown)
  }
}

object Subscriber {
  // FIXME Create Protobuf messages for these case classes
  case class Create(id: String, clazz: Class[_])
  case class FindById(id: String, clazz: Class[_])
  case class ChangeSet(batch: immutable.Seq[_])
}

/**
 * Subscribing on CRDT changes broadcasted by the Publisher.
 */
class Subscriber(database: ConvergentReplicatedDataTypeDatabase) extends Actor with ActorLogging {
  import Subscriber._
  import Replicator._
  import database.{ update ⇒ replicate, storage }

  val selfAddress = Cluster(context.system).selfAddress

  override def preStart(): Unit = {
    log.info("Starting CvRDT change subscriber")
  }

  def receive: Receive = {
    case ChangeSet(batch) ⇒
      log.debug("Received change set from {}", sender.path.address)
      sender ! Ack(selfAddress)

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

      sender ! crdt

    case FindById(id, clazz) ⇒
      val crdt =
        if (classOf[GCounter].isAssignableFrom(clazz)) storage.findById[GCounter](id)
        else if (classOf[PNCounter].isAssignableFrom(clazz)) storage.findById[PNCounter](id)
        else if (classOf[GSet].isAssignableFrom(clazz)) storage.findById[GSet](id)
        else if (classOf[TwoPhaseSet].isAssignableFrom(clazz)) storage.findById[TwoPhaseSet](id)
        else throw new ClassCastException(s"Could create new CvRDT with id [$id] and type [$clazz]")
      sender ! crdt

    case unknown ⇒ throw new IllegalStateException(s"Received unknown message: $unknown")
  }
}
