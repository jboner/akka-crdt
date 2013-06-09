/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.crdt.convergent

import scala.collection.immutable

import play.api.libs.json.{ JsValue, Json }

trait ConvergentReplicatedDataTypeCounterView {
  def id: String
  def toJson: JsValue
  override def toString: String = Json.stringify(toJson)
}

trait ConvergentReplicatedDataType {
  def `type`: String
  //PN: I would name this to something else to avoid the back-ticking, which is also exposed in API
  def id: String
  def toJson: JsValue
  def view: ConvergentReplicatedDataTypeCounterView
  //PN: Why is this a CounterView? What is a CounterView?
  override def toString: String = Json.stringify(toJson)
}

trait ConvergentReplicatedDataTypeCounter extends ConvergentReplicatedDataType {
  def value: Int
}

trait ConvergentReplicatedDataTypeSet extends ConvergentReplicatedDataType {
  def value: immutable.Set[JsValue]
  //PN: Why JsValue and not a type parameter?
  def contains(element: JsValue): Boolean = value contains element
  def foreach(f: JsValue ⇒ Unit): Unit = value.toSeq foreach f
  def isEmpty: Boolean = value.isEmpty
  def size: Int = value.size
}
