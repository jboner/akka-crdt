/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package com.typesafe.akka.crdt.convergent

import scala.collection.immutable

import play.api.libs.json._

import java.util.UUID

/**
 * Implements a ConvergentReplicatedDataType 'Add Set' also called a 'G-Set'. You cannot remove an element of a G-Set.
 */
case class AddSet(
  id: String = UUID.randomUUID.toString,
  private[crdt] val state: Set[JsValue] = Set.empty[JsValue]) extends ConvergentReplicatedDataTypeSet {

  val `type`: String = "g-set"

  def +(element: JsValue): AddSet = AddSet(id, state + element)

  def merge(that: AddSet): AddSet = AddSet(id, that.state ++ this.state)

  def toSet: immutable.Set[JsValue] = state

  override def toString: String = Json.stringify(AddSet.format.writes(this))
}

object AddSet {

  implicit object format extends Format[AddSet] {
    def reads(json: JsValue): JsResult[AddSet] = JsSuccess(AddSet(
      (json \ "id").as[String],
      (json \ "state").as[Set[JsValue]]
    ))

    def writes(set: AddSet): JsValue = JsObject(Seq(
      "type"  -> JsString(set.`type`),
      "id"    -> JsString(set.id),
      "state" -> Json.toJson(set.state)
    ))
  }
}
