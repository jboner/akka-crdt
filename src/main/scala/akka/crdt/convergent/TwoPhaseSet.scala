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
  private[crdt] val adds: GSet,
  private[crdt] val removes: GSet) extends ConvergentReplicatedDataTypeSet {

  val `type`: String = "2p-set"

  def +(element: JsValue): TwoPhaseSet = {
    if ((adds contains element) && (removes contains element)) throw new IllegalStateException(s"Can not add $element - already removed from set") // was previously removed
    else TwoPhaseSet(id, adds + element, removes)
  }

  def -(element: JsValue): TwoPhaseSet = {
    if (adds contains element) TwoPhaseSet(id, adds, removes + element)
    else throw new IllegalStateException(s"Can not remove $element - not in set")
  }

  def merge(that: TwoPhaseSet): TwoPhaseSet =
    TwoPhaseSet(id, that.adds.merge(this.adds), that.removes.merge(this.removes))

  def value: immutable.Set[JsValue] = adds.value -- removes.value

  override def toString: String = Json.stringify(TwoPhaseSet.format.writes(this))
}

object TwoPhaseSet {
  def apply(): TwoPhaseSet = {
    apply(UUID.randomUUID.toString)
  }

  def apply[T](id: String): TwoPhaseSet = {
    new TwoPhaseSet(id, GSet(id = id + "/adds"), GSet(id = id + "/removes"))
  }

  implicit object format extends Format[TwoPhaseSet] {
    def reads(json: JsValue): JsResult[TwoPhaseSet] = JsSuccess(TwoPhaseSet(
      (json \ "id").as[String],
      (json \ "adds").as[GSet],
      (json \ "removes").as[GSet]
    ))

    def writes(set: TwoPhaseSet): JsValue = JsObject(Seq(
      "type"    -> JsString(set.`type`),
      "id"      -> JsString(set.id),
      "adds"    -> Json.toJson(set.adds),
      "removes" -> Json.toJson(set.removes)
    ))
  }
}
