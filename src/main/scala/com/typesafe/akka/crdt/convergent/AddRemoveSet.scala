/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package com.typesafe.akka.crdt.commutative

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
case class AddRemoveSet(
  id: String,
  private[crdt] val increments: AddSet,
  private[crdt] val decrements: AddSet) extends ConvergentReplicatedDataTypeSet {

  val `type`: String = "2p-set"

  def +(element: JsValue): AddRemoveSet = {
    if ((increments contains element) && (decrements contains element)) throw new IllegalStateException(s"Can not add $element - already removed from set") // was previously removed
    else AddRemoveSet(id, increments + element, decrements)
  }

  def -(element: JsValue): AddRemoveSet = {
    if (increments contains element) AddRemoveSet(id, increments, decrements + element)
    else throw new IllegalStateException(s"Can not remove $element - not in set")
  }

  def merge(that: AddRemoveSet): AddRemoveSet =
    AddRemoveSet(id, that.increments.merge(this.increments), that.decrements.merge(this.decrements))

  def toSet: immutable.Set[JsValue] = increments.toSet -- decrements.toSet
}

object AddRemoveSet {
  def apply(): AddRemoveSet = {
    apply(UUID.randomUUID.toString)
  }

  def apply[T](id: String): AddRemoveSet = {
    new AddRemoveSet(id, AddSet(id = id + "/inc"), AddSet(id = id + "/dec"))
  }

  implicit object jsValueFormat extends Format[AddRemoveSet] {
    def reads(json: JsValue): JsResult[AddRemoveSet] = JsSuccess(AddRemoveSet(
      (json \ "id").as[String],
      (json \ "increments").as[AddSet],
      (json \ "decrements").as[AddSet]
    ))

    def writes(set: AddRemoveSet): JsValue = JsObject(Seq(
      "type" -> JsString(set.`type`),
      "id" -> JsString(set.id),
      "increments" -> Json.toJson(set.increments),
      "decrements" -> Json.toJson(set.decrements)
    ))
  }
}
