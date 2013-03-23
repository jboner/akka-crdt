/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package com.typesafe.akka.crdt

import scala.collection.immutable

import play.api.libs.json._

/**
 * Implements a CRDT 'Add Set' also called a 'G-Set'. You cannot remove an element of a G-Set.
 */
case class AddSet[T](private[crdt] val state: Set[T] = Set.empty[T]) extends CRDTSet[T] {

  val `type`: String = "g-set"

  def +(element: T): AddSet[T] = AddSet(state + element)

  def merge(that: AddSet[T]): AddSet[T] = AddSet(that.state ++ this.state)

  def toSet: immutable.Set[T] = state
}

object AddSet {

  implicit object stringFormat extends Format[AddSet[String]] {
    def reads(json: JsValue): JsResult[AddSet[String]] = JsSuccess(AddSet(
      (json \ "state").as[Set[String]]
    ))

    def writes(set: AddSet[String]): JsValue = JsObject(Seq(
      "type" -> JsString(set.`type`),
      "state" -> Json.toJson(set.state)
    ))
  }

  implicit object jsValueFormat extends Format[AddSet[JsValue]] {
    def reads(json: JsValue): JsResult[AddSet[JsValue]] = JsSuccess(AddSet(
      (json \ "state").as[Set[JsValue]]
    ))

    def writes(set: AddSet[JsValue]): JsValue = JsObject(Seq(
      "type" -> JsString(set.`type`),
      "state" -> Json.toJson(set.state)
    ))
  }
}
