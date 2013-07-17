/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.crdt.convergent

import ConvergentReplicatedDataType._
import scala.collection.immutable
import play.api.libs.json._
import java.util.UUID
import akka.actor.ActorSystem

/**
 * Implements a ConvergentReplicatedDataType 'Add Set' also called a 'G-Set'. You cannot remove an element of a G-Set.
 */
case class GSet(
  id: String = UUID.randomUUID.toString,
  private[crdt] val state: immutable.Set[JsValue] = immutable.Set.empty[JsValue]) extends Set {

  override val dataType: String = GSet.dataType

  def +(element: JsValue): GSet = GSet(id, state + element)

  def merge(that: GSet): GSet = GSet(id, that.state ++ this.state)

  def value: immutable.Set[JsValue] = state

  def view: View = GSetView(id, value)

  def store(implicit system: ActorSystem): this.type = {
    ConvergentReplicatedDataTypeDatabase(system).update(this)
    this
  }

  override def toJson: JsValue = GSet.Format.writes(this)
}

object GSet {
  val dataType: String = "g-set"

  implicit object Format extends Format[GSet] {
    def reads(json: JsValue): JsResult[GSet] = JsSuccess(GSet(
      (json \ "id").as[String],
      (json \ "state").as[immutable.Set[JsValue]]))

    def writes(set: GSet): JsValue = JsObject(Seq(
      "type" -> JsString(set.dataType),
      "id" -> JsString(set.id),
      "state" -> Json.toJson(set.state)))
  }
}

/**
 * Implements a snapshot view of the GSet.
 */
case class GSetView(id: String, value: immutable.Set[JsValue]) extends View {
  def toJson: JsValue = GSetView.Format.writes(this)
}

object GSetView {
  implicit object Format extends Format[GSetView] {
    def reads(json: JsValue): JsResult[GSetView] = JsSuccess(GSetView(
      (json \ "id").as[String],
      (json \ "value").as[immutable.Set[JsValue]]))

    def writes(set: GSetView): JsValue = JsObject(Seq(
      "type" -> JsString("set"),
      "id" -> JsString(set.id),
      "value" -> Json.toJson(set.value)))
  }
}

