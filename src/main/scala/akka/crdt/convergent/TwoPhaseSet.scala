/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.crdt.convergent

import scala.collection.immutable

import play.api.libs.json._

import java.util.UUID

/**
 * Implements a ConvergentReplicatedDataType 'Two Phase Set' also called a '2P-Set'.
 *
 * 2-phase sets consist of two G-Sets: one for adding and one for removing.
 * An element can only be added once and only removed once, and elements can
 * only be removed if they are present in the set. Removes naturally take
 * precedence over adds.
 */
case class TwoPhaseSet(
  id: String,
  private[crdt] val increments: GSet,
  private[crdt] val decrements: GSet) extends ConvergentReplicatedDataTypeSet {

  val `type`: String = "2p-set"

  def +(element: JsValue): TwoPhaseSet = {
    if ((increments contains element) && (decrements contains element)) throw new IllegalStateException(s"Can not add $element - already removed from set") // was previously removed
    else TwoPhaseSet(id, increments + element, decrements)
  }

  def -(element: JsValue): TwoPhaseSet = {
    if (increments contains element) TwoPhaseSet(id, increments, decrements + element)
    else throw new IllegalStateException(s"Can not remove $element - not in set")
  }

  def merge(that: TwoPhaseSet): TwoPhaseSet =
    TwoPhaseSet(id, that.increments.merge(this.increments), that.decrements.merge(this.decrements))

  def value: immutable.Set[JsValue] = increments.value -- decrements.value

  override def toString: String = Json.stringify(TwoPhaseSet.format.writes(this))
}

object TwoPhaseSet {
  def apply(): TwoPhaseSet = {
    apply(UUID.randomUUID.toString)
  }

  def apply[T](id: String): TwoPhaseSet = {
    new TwoPhaseSet(id, GSet(id = id + "/inc"), GSet(id = id + "/dec"))
  }

  implicit object format extends Format[TwoPhaseSet] {
    def reads(json: JsValue): JsResult[TwoPhaseSet] = JsSuccess(TwoPhaseSet(
      (json \ "id").as[String],
      (json \ "increments").as[GSet],
      (json \ "decrements").as[GSet]
    ))

    def writes(set: TwoPhaseSet): JsValue = JsObject(Seq(
      "type"       -> JsString(set.`type`),
      "id"         -> JsString(set.id),
      "increments" -> Json.toJson(set.increments),
      "decrements" -> Json.toJson(set.decrements)
    ))
  }
}
