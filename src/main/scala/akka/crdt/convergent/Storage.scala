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
 * Interface for all storage implementations for the CvRDT.
 */
trait Storage {

  //  FIXME: Implement these in Storage: 'def keys(): Set[Array[Byte]]' and 'def values(): Iterator[Array[Byte]]'

  def findById[T <: ConvergentReplicatedDataType: ClassTag](id: String): Try[T]

  /**
   * Store a CvRDT.
   */
  def store[T <: ConvergentReplicatedDataType: ClassTag](crdt: T): Unit

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