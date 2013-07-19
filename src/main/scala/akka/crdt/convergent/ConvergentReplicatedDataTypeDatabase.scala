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
    log.debug("Updating CvRDT [{}]", counter)
    replicate(toJson(counter))
    counter
  }

  def update(counter: PNCounter): PNCounter = {
    log.debug("Updating CvRDT [{}]", counter)
    replicate(toJson(counter))
    counter
  }

  def update(set: GSet): GSet = {
    log.debug("Updating CvRDT [{}]", set)
    replicate(toJson(set))
    set
  }

  def update(set: TwoPhaseSet): TwoPhaseSet = {
    log.debug("Updating CvRDT [{}]", set)
    replicate(toJson(set))
    set
  }

  def findById[T <: ConvergentReplicatedDataType: ClassTag](id: String = UUID.randomUUID.toString): Future[T] = {
    val promise = Promise[T]()
    (subscriber ? Subscriber.FindById(id, implicitly[ClassTag[T]].runtimeClass)).mapTo[Try[T]] foreach { promise complete _ }
    promise.future
  }

  def create[T <: ConvergentReplicatedDataType: ClassTag](id: String = UUID.randomUUID.toString): T = {
    val clazz = implicitly[ClassTag[T]].runtimeClass
    log.debug("Creating new CvRDT with id [{}] and type [{}]", id, clazz)
    val crdt =
      if (classOf[GCounter].isAssignableFrom(clazz)) update(GCounter(id))
      else if (classOf[PNCounter].isAssignableFrom(clazz)) update(PNCounter(id))
      else if (classOf[GSet].isAssignableFrom(clazz)) update(GSet(id))
      else if (classOf[TwoPhaseSet].isAssignableFrom(clazz)) update(TwoPhaseSet(id))
      else throw new ClassCastException(s"Could not create new CvRDT of type [$clazz]")
    crdt.asInstanceOf[T]
  }

  def findOrCreate[T <: ConvergentReplicatedDataType: ClassTag](id: String = UUID.randomUUID.toString): Future[T] = {
    val clazz = implicitly[ClassTag[T]].runtimeClass
    log.debug("Creating new CvRDT with id [{}] and type [{}]", id, clazz)
    findById[T](id) recoverWith {
      case _ ⇒ Future {
        {
          if (classOf[GCounter].isAssignableFrom(clazz)) update(GCounter(id))
          else if (classOf[PNCounter].isAssignableFrom(clazz)) update(PNCounter(id))
          else if (classOf[GSet].isAssignableFrom(clazz)) update(GSet(id))
          else if (classOf[TwoPhaseSet].isAssignableFrom(clazz)) update(TwoPhaseSet(id))
          else throw new ClassCastException(s"Could not create new CvRDT of type [$clazz]")
        }.asInstanceOf[T]
      }
    }
  }

  def shutdown(): Unit = {
    log.info("Shutting down ConvergentReplicatedDataTypeDatabase...")
    restServer foreach { _.shutdown() }
    system.stop(subscriber)
    system.stop(replicator)
    storage.destroy()
    log.info("ConvergentReplicatedDataTypeDatabase shut down successfully")
  }

  private def replicate(json: JsValue): Unit = replicator ! Replicator.Replicate(json)
}

object Replicator {
  // FIXME Create Protobuf messages for the Replicate and Ack case classes
  case class Replicate(json: JsValue)
  case class Ack(replica: Address)
}

/**
 * Replicating CvRDT changes to all member nodes.
 * Keeps retrying until an ACK is received or the node is leaving the cluster.
 * Uses a configurable batching window.
 */
class Replicator(settings: ConvergentReplicatedDataTypeSettings)
  extends Actor with ActorLogging { replicator ⇒
  import Replicator._
  import Resubmittor._
  import Subscriber._
  import settings._

  val selfAddress = Cluster(context.system).selfAddress
  var replicas: immutable.Set[Address] = immutable.Set.empty[Address] + selfAddress

  // FIXME: Do not send a Seq with JSON strings across the wire - but plain JSON
  var batch: immutable.Seq[String] = _
  var batchingWindow: Deadline = _

  val resubmittor = context.system.actorOf(Props(classOf[Resubmittor], settings), name = "resubmittor")

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
      val changeSet = ChangeSet(batch)
      replicas foreach { replica ⇒
        log.debug("Replicating updated CvRDT batch [{}] to [{}]", batch.mkString(", "), replica)
        resubmittor ! VerifyAckFor(replica, changeSet)
        context.actorSelection(replica + subscriberPath) tell (changeSet, resubmittor)
      }
    }
    newBatchingWindow()
  }

  def receive = {
    case Replicate(json) ⇒
      val jsonString = stringify(json)
      log.debug("Adding updated CvRDT to batch [{}]", jsonString)
      batch = jsonString +: batch // append to batch
      if (batchingWindow.isOverdue) replicateBatch() // if batching window is closed - ship batch and reset window

    case ReceiveTimeout ⇒
      replicateBatch() // if no messages within batching window - ship batch and reset window

    case state: CurrentClusterState ⇒
      replicas = (replicas ++ state.members.map(_.address))
      resubmittor ! ReplicaSetChange(replicas)

    case MemberUp(member) ⇒
      replicas = (replicas + member.address)
      resubmittor ! ReplicaSetChange(replicas)

    case MemberRemoved(member, _) ⇒
      replicas = (replicas - member.address)
      resubmittor ! ReplicaSetChange(replicas)

    case _: ClusterDomainEvent ⇒ // ignore
  }
}

object Resubmittor {
  case class ReplicaSetChange(replicas: immutable.Set[Address])
  case class VerifyAckFor(replica: Address, changeSet: Subscriber.ChangeSet)
  case object ResubmitChangeSets
}

class Resubmittor(settings: ConvergentReplicatedDataTypeSettings) extends Actor with ActorLogging {
  import Subscriber._
  import Replicator._
  import Resubmittor._
  import settings._
  import context.dispatcher

  var replicas: immutable.Set[Address] = immutable.Set.empty[Address]
  var changeSets = immutable.Map.empty[Address, ChangeSet]

  def receive = {
    case ReplicaSetChange(newReplicaSet) ⇒
      val removedReplicas = replicas diff newReplicaSet
      removedReplicas foreach { changeSets -= _ }
      replicas = newReplicaSet
      log.debug("Replica set have changed - new set [{}]", replicas.mkString(", "))

    case VerifyAckFor(replica, changeSet) ⇒
      changeSets += (replica -> changeSet)

    case Ack(replica) ⇒
      log.debug("Received ACK from replica [{}]", replica)
      changeSets -= replica

    case ResubmitChangeSets ⇒
      changeSets foreach {
        case (replica, changeSet) ⇒
          log.debug("Resubmitting change set to replica [{}]", replica)
          context.actorSelection(replica + subscriberPath) ! changeSet
      }
  }

  override def preStart(): Unit = {
    context.system.scheduler.schedule(
      ChangeSetResubmissionInterval, ChangeSetResubmissionInterval, self, ResubmitChangeSets)
  }
}

object Subscriber {
  val subscriberPath = "/user/crdt:subscriber"

  // FIXME Create Protobuf messages for these case classes
  case class FindById(id: String, clazz: Class[_])
  case class ChangeSet(batch: immutable.Seq[_])
}

/**
 * Subscribing on CvRDT changes broadcasted by the Publisher.
 */
class Subscriber(database: ConvergentReplicatedDataTypeDatabase) extends Actor with ActorLogging {
  import Subscriber._
  import Replicator._
  import database.storage

  val selfAddress = Cluster(context.system).selfAddress

  override def preStart(): Unit = {
    log.info("Starting CvRDT change subscriber")
  }

  def receive: Receive = {
    case ChangeSet(batch) ⇒
      log.debug("Received change set from [{}]", sender.path.address)
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

              case _ ⇒ log.error("Received JSON is not a CvRDT [{}]", jsonString)
            }
        }
        database.storage.store(crdts)
      }

    case FindById(id, clazz) ⇒
      log.debug("Find CvRDT of type with id [{}] and type [{}]", id, clazz)
      val crdt =
        if (classOf[GCounter].isAssignableFrom(clazz)) storage.findById[GCounter](id)
        else if (classOf[PNCounter].isAssignableFrom(clazz)) storage.findById[PNCounter](id)
        else if (classOf[GSet].isAssignableFrom(clazz)) storage.findById[GSet](id)
        else if (classOf[TwoPhaseSet].isAssignableFrom(clazz)) storage.findById[TwoPhaseSet](id)
        else throw new ClassCastException(s"Could create new CvRDT with id [$id] and type [$clazz]")
      sender ! crdt

    case unknown ⇒ throw new IllegalStateException(s"Received unknown message [$unknown]")
  }
}
