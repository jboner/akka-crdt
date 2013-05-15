/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.crdt.convergent

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
case class PNCounter private (
  id: String,
  private[crdt] val increments: GCounter,
  private[crdt] val decrements: GCounter) extends ConvergentReplicatedDataTypeCounter {

  val `type`: String = "pn-counter"

  def value: Int = increments.value - decrements.value

  /**
   * Increment the PNCounter with the delta specified. If the value is negative then it will decrement instead of increment.
   */
  def +(node: String, delta: Int = 1): PNCounter = {
    if (delta < 0) this - (node, delta)
    else new PNCounter(id, increments + (node, delta), decrements)
  }

  /**
   * Decrements the PNCounter with the delta specified. Agnostic to sign (does Math.abs(delta)).
   */
  def -(node: String, delta: Int = 1): PNCounter =
    new PNCounter(id, increments, decrements + (node, Math.abs(delta)))

  def merge(that: PNCounter): PNCounter =
    new PNCounter(id, that.increments.merge(this.increments), that.decrements.merge(this.decrements))

  override def toString: String = Json.stringify(PNCounter.format.writes(this))
}

object PNCounter {

  def apply(): PNCounter = {
    apply(UUID.randomUUID.toString)
  }

  def apply(id: String): PNCounter = {
    new PNCounter(id, GCounter(id = id + "/inc"), GCounter(id = id + "/dec"))
  }

  implicit object format extends Format[PNCounter] {
    def reads(json: JsValue): JsResult[PNCounter] = JsSuccess(new PNCounter(
      (json \ "id").as[String],
      (json \ "increments").as[GCounter],
      (json \ "decrements").as[GCounter]))

    def writes(counter: PNCounter): JsValue = JsObject(Seq(
      "type" -> JsString(counter.`type`),
      "id" -> JsString(counter.id),
      "increments" -> Json.toJson(counter.increments),
      "decrements" -> Json.toJson(counter.decrements)))
  }
}
