/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.crdt.convergent

import scala.util.Try
import scala.reflect.ClassTag
import scala.collection.immutable
import akka.actor.ActorSystem
import akka.event.LoggingAdapter

/**
 * 'findById' is called concurrently, 'store' always from a single actor.
 */
trait Storage {

  //  FIXME: IMPLEMENT THESE
  //  def delete(key: Array[Byte]): Option[Array[Byte]]
  //  def keys(): Set[Array[Byte]]
  //  def values(): Iterator[Array[Byte]]

  def findById[T <: ConvergentReplicatedDataType: ClassTag](id: String): Try[T]

  def store(counter: GCounter): Unit
  def store(counter: PNCounter): Unit
  def store(set: GSet): Unit
  def store(set: TwoPhaseSet): Unit

  /**
   * Store a batch.
   */
  def store[T <: ConvergentReplicatedDataType: ClassTag](crdts: immutable.Seq[T]): Unit

  /**
   * Close the database.
   */
  def close(): Unit = {}

  /**
   * Destroy/Delete/Remove the database.
   */
  def destroy(): Unit = {}
}

class InMemoryStorage(
  nodename: String,
  settings: ConvergentReplicatedDataTypeSettings,
  log: LoggingAdapter) extends Storage {

  @volatile private var gCounters = Map.empty[String, GCounter]
  @volatile private var pnCounters = Map.empty[String, PNCounter]
  @volatile private var gSets = Map.empty[String, GSet]
  @volatile private var twoPhaseSets = Map.empty[String, TwoPhaseSet]

  def findById[T <: ConvergentReplicatedDataType: ClassTag](id: String): Try[T] = Try {
    val clazz = implicitly[ClassTag[T]].runtimeClass
    val crdt =
      if (classOf[GCounter].isAssignableFrom(clazz)) gCounters.get(id)
      else if (classOf[PNCounter].isAssignableFrom(clazz)) pnCounters.get(id)
      else if (classOf[GSet].isAssignableFrom(clazz)) gSets.get(id)
      else if (classOf[TwoPhaseSet].isAssignableFrom(clazz)) twoPhaseSets.get(id)
      else throw new ClassCastException(s"Could create new CvRDT with id [$id] and type [$clazz]")
    crdt.getOrElse(throw new NoSuchElementException(s"Could not fint CRDT with id [$id]")).asInstanceOf[T]
  }

  def store(counter: GCounter): Unit = gCounters = gCounters + (counter.id -> counter)
  def store(counter: PNCounter): Unit = pnCounters = pnCounters + (counter.id -> counter)
  def store(set: GSet): Unit = gSets = gSets + (set.id -> set)
  def store(set: TwoPhaseSet): Unit = twoPhaseSets = twoPhaseSets + (set.id -> set)

  /**
   * Store a batch.
   */
  def store[T <: ConvergentReplicatedDataType: ClassTag](crdts: immutable.Seq[T]): Unit = {
    val clazz = implicitly[ClassTag[T]].runtimeClass
    if (classOf[GCounter].isAssignableFrom(clazz)) crdts foreach (crdt ⇒ store(crdt.asInstanceOf[GCounter]))
    else if (classOf[PNCounter].isAssignableFrom(clazz)) crdts foreach (crdt ⇒ store(crdt.asInstanceOf[PNCounter]))
    else if (classOf[GSet].isAssignableFrom(clazz)) crdts foreach (crdt ⇒ store(crdt.asInstanceOf[GSet]))
    else if (classOf[TwoPhaseSet].isAssignableFrom(clazz)) crdts foreach (crdt ⇒ store(crdt.asInstanceOf[TwoPhaseSet]))
    else throw new ClassCastException(s"Could store CvRDT with type [$clazz]")
  }
}
