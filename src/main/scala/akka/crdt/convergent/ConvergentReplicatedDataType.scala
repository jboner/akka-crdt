/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.crdt.convergent

import scala.collection.immutable
import play.api.libs.json.{ JsValue, Json }
import akka.actor.ActorSystem

object ConvergentReplicatedDataType {
  trait View {
    def id: String
    def toJson: JsValue
    override def toString: String = Json.stringify(toJson)
  }

  trait Counter extends ConvergentReplicatedDataType {
    def value: Int
  }

  trait Set extends ConvergentReplicatedDataType {
    def value: immutable.Set[JsValue]
    def contains(element: JsValue): Boolean = value contains element
    def foreach(f: JsValue ⇒ Unit): Unit = value.toSeq foreach f
    def isEmpty: Boolean = value.isEmpty
    def size: Int = value.size
  }
}

trait ConvergentReplicatedDataType {
  def dataType: String
  def id: String
  def toJson: JsValue
  def view: ConvergentReplicatedDataType.View
  def store(implicit system: ActorSystem): Unit
  override def toString: String = Json.stringify(toJson)
}
