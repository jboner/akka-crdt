/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package com.typesafe.akka.crdt.commutative

import play.api.libs.json._

import java.util.UUID

/**
 * Implements a ConvergentReplicatedDataType 'Increment/Decrement Counter' also called a 'PN-Counter'.
 *
 * PN-Counters allow the counter to be incremented by tracking the
 * increments (P) separate from the decrements (N). Both P and N are represented
 * as internal G-Counters. Merge is handled by merging the internal P and N
 * counters. The value of the counter is the value of the P counter minus
 * the value of the N counter.
 */
case class IncrementingDecrementingCounter private (
  id: String,
  private[crdt] val increments: IncrementingCounter,
  private[crdt] val decrements: IncrementingCounter) extends ConvergentReplicatedDataTypeCounter {

  val `type`: String = "pn-counter"

  def value: Int = increments.value - decrements.value

  def +(node: String, delta: Int = 1): IncrementingDecrementingCounter = {
    if (delta < 0) this - (node, delta)
    else new IncrementingDecrementingCounter(id, increments + (node, delta), decrements)
  }

  def -(node: String, delta: Int = 1): IncrementingDecrementingCounter =
    new IncrementingDecrementingCounter(id, increments, decrements + (node, Math.abs(delta)))

  def merge(that: IncrementingDecrementingCounter): IncrementingDecrementingCounter =
    new IncrementingDecrementingCounter(id, that.increments.merge(this.increments), that.decrements.merge(this.decrements))
}

object IncrementingDecrementingCounter {

  def apply(): IncrementingDecrementingCounter = {
    apply(UUID.randomUUID.toString)
  }

  def apply(id: String): IncrementingDecrementingCounter = {
    new IncrementingDecrementingCounter(id, IncrementingCounter(id = id + "/inc"), IncrementingCounter(id = id + "/dec"))
  }

  implicit object format extends Format[IncrementingDecrementingCounter] {
    def reads(json: JsValue): JsResult[IncrementingDecrementingCounter] = JsSuccess(new IncrementingDecrementingCounter(
      (json \ "id").as[String],
      (json \ "increments").as[IncrementingCounter],
      (json \ "decrements").as[IncrementingCounter]
    ))

    def writes(counter: IncrementingDecrementingCounter): JsValue = JsObject(Seq(
      "type" -> JsString(counter.`type`),
      "id" -> JsString(counter.id),
      "increments" -> Json.toJson(counter.increments),
      "decrements" -> Json.toJson(counter.decrements)
    ))
  }
}
