/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.crdt.convergent

import scala.collection.immutable
import play.api.libs.json.JsValue

trait ConvergentReplicatedDataType {
  def `type`: String
  def id: String
}

trait ConvergentReplicatedDataTypeCounter extends ConvergentReplicatedDataType {
  def value: Int
}

trait ConvergentReplicatedDataTypeSet extends ConvergentReplicatedDataType {
  def toSet: immutable.Set[JsValue]

  def toSeq: immutable.Seq[JsValue] = toSet.toVector

  def contains(element: JsValue): Boolean = toSet contains element

  def foreach(f: JsValue => Unit): Unit = toSeq foreach f

  def isEmpty: Boolean = toSeq.isEmpty

  def size: Int = toSeq.size
}
