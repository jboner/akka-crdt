/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.crdt.convergent

import scala.collection.immutable

import play.api.libs.json._

import java.util.UUID

/**
 * Implements a snapshot view of the GSet.
 */
case class GSetView(id: String, value: Set[JsValue]) extends ConvergentReplicatedDataTypeCounterView {
  def toJson: JsValue = GSetView.format.writes(this)
}

object GSetView {
  implicit object format extends Format[GSetView] {
    def reads(json: JsValue): JsResult[GSetView] = JsSuccess(GSetView(
      (json \ "id").as[String],
      (json \ "value").as[Set[JsValue]]))

    def writes(set: GSetView): JsValue = JsObject(Seq(
      "type" -> JsString("set"),
      "id" -> JsString(set.id),
      "value" -> Json.toJson(set.value)))
  }
}

/**
 * Implements a ConvergentReplicatedDataType 'Add Set' also called a 'G-Set'. You cannot remove an element of a G-Set.
 */
case class GSet(
  id: String = UUID.randomUUID.toString,
  private[crdt] val state: Set[JsValue] = Set.empty[JsValue]) extends ConvergentReplicatedDataTypeSet {

  val `type`: String = "g-set"

  def +(element: JsValue): GSet = GSet(id, state + element)

  def merge(that: GSet): GSet = GSet(id, that.state ++ this.state)

  def value: immutable.Set[JsValue] = state

  def view: ConvergentReplicatedDataTypeCounterView = GSetView(id, value)

  override def toJson: JsValue = GSet.format.writes(this)
}

object GSet {
  implicit object format extends Format[GSet] {
    def reads(json: JsValue): JsResult[GSet] = JsSuccess(GSet(
      (json \ "id").as[String],
      (json \ "state").as[Set[JsValue]]))

    def writes(set: GSet): JsValue = JsObject(Seq(
      "type" -> JsString(set.`type`),
      "id" -> JsString(set.id),
      "state" -> Json.toJson(set.state)))
  }
}
