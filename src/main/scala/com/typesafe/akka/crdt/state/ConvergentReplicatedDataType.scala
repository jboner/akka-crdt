/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package com.typesafe.akka.crdt.state

import scala.collection.immutable

trait ConvergentReplicatedDataType {
  def `type`: String
  def id: String
}

trait ConvergentReplicatedDataTypeCounter extends ConvergentReplicatedDataType {
  def value: Int
}

trait ConvergentReplicatedDataTypeSet[T] extends ConvergentReplicatedDataType {
  def toSet: immutable.Set[T]

  def toSeq: immutable.Seq[T] = toSet.toVector

  def contains(element: T): Boolean = toSet contains element

  def foreach(f: T => Unit): Unit = toSeq foreach f

  def isEmpty: Boolean = toSeq.isEmpty

  def size: Int = toSeq.size
}
