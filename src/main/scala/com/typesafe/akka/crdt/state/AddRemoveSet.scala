/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package com.typesafe.akka.crdt.state

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
case class AddRemoveSet[T](
  id: String,
  private[crdt] val increments: AddSet[T],
  private[crdt] val decrements: AddSet[T]) extends ConvergentReplicatedDataTypeSet[T] {

  val `type`: String = "2p-set"

  def +(element: T): AddRemoveSet[T] = {
    if ((increments contains element) && (decrements contains element)) throw new IllegalStateException(s"Can not add $element - already removed from set") // was previously removed
    else AddRemoveSet(id, increments + element, decrements)
  }

  def -(element: T): AddRemoveSet[T] = {
    if (increments contains element) AddRemoveSet(id, increments, decrements + element)
    else throw new IllegalStateException(s"Can not remove $element - not in set")
  }

  def merge(that: AddRemoveSet[T]): AddRemoveSet[T] =
    AddRemoveSet(id, that.increments.merge(this.increments), that.decrements.merge(this.decrements))

  def toSet: immutable.Set[T] = increments.toSet -- decrements.toSet
}

object AddRemoveSet {
  def apply[T](): AddRemoveSet[T] = {
    apply(UUID.randomUUID.toString)
  }

  def apply[T](id: String): AddRemoveSet[T] = {
    new AddRemoveSet(id, AddSet[T](id = id + "/inc"), AddSet[T](id = id + "/dec"))
  }

  implicit object stringFormat extends Format[AddRemoveSet[String]] {
    def reads(json: JsValue): JsResult[AddRemoveSet[String]] = JsSuccess(AddRemoveSet(
      (json \ "id").as[String],
      (json \ "increments").as[AddSet[String]],
      (json \ "decrements").as[AddSet[String]]
    ))

    def writes(set: AddRemoveSet[String]): JsValue = JsObject(Seq(
      "type" -> JsString(set.`type`),
      "id" -> JsString(set.id),
      "increments" -> Json.toJson(set.increments),
      "decrements" -> Json.toJson(set.decrements)
    ))
  }

  implicit object jsValueFormat extends Format[AddRemoveSet[JsValue]] {
    def reads(json: JsValue): JsResult[AddRemoveSet[JsValue]] = JsSuccess(AddRemoveSet(
      (json \ "id").as[String],
      (json \ "increments").as[AddSet[JsValue]],
      (json \ "decrements").as[AddSet[JsValue]]
    ))

    def writes(set: AddRemoveSet[JsValue]): JsValue = JsObject(Seq(
      "type" -> JsString(set.`type`),
      "id" -> JsString(set.id),
      "increments" -> Json.toJson(set.increments),
      "decrements" -> Json.toJson(set.decrements)
    ))
  }
}
