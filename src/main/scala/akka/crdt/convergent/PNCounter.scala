/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.crdt.convergent

import play.api.libs.json._

import java.util.UUID

/**
 * Implements a snapshot view of the PNCounter.
 */
case class PNCounterView(id: String, value: Int) extends ConvergentReplicatedDataTypeCounterView {
  def toJson: JsValue = PNCounterView.Format.writes(this)
}

object PNCounterView {
  implicit object Format extends Format[PNCounterView] {
    def reads(json: JsValue): JsResult[PNCounterView] = JsSuccess(PNCounterView(
      (json \ "id").as[String],
      (json \ "value").as[Int]))

    def writes(counter: PNCounterView): JsValue = JsObject(Seq(
      "type" -> JsString("counter"),
      "id" -> JsString(counter.id),
      "value" -> Json.toJson(counter.value)))
  }
}

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

  val crdtType: String = "pn-counter"

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
    //PN: def :-
    new PNCounter(id, increments, decrements + (node, Math.abs(delta)))

  def merge(that: PNCounter): PNCounter =
    new PNCounter(id, that.increments.merge(this.increments), that.decrements.merge(this.decrements))

  def view: ConvergentReplicatedDataTypeCounterView = PNCounterView(id, value)

  override def toJson: JsValue = PNCounter.Format.writes(this)
}

object PNCounter {

  def apply(): PNCounter = {
    apply(UUID.randomUUID.toString)
  }

  def apply(id: String): PNCounter = {
    new PNCounter(id, GCounter(id = id + "/inc"), GCounter(id = id + "/dec"))
  }

  implicit object Format extends Format[PNCounter] {
    def reads(json: JsValue): JsResult[PNCounter] = JsSuccess(new PNCounter(
      (json \ "id").as[String],
      (json \ "increments").as[GCounter],
      (json \ "decrements").as[GCounter]))

    def writes(counter: PNCounter): JsValue = JsObject(Seq(
      "type" -> JsString(counter.crdtType),
      "id" -> JsString(counter.id),
      "increments" -> Json.toJson(counter.increments),
      "decrements" -> Json.toJson(counter.decrements)))
  }
}
