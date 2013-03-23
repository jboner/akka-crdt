/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package com.typesafe.akka.crdt

import play.api.libs.json._

/**
 * Implements a CRDT 'Growing Counter' also called a 'G-Counter'.
 *
 * A G-Counter is a increment-only counter (inspired by vector clocks) in
 * which only increment and merge are possible. Incrementing the counter
 * adds 1 to the count for the current actor. Divergent histories are
 * resolved by taking the maximum count for each actor (like a vector
 * clock merge). The value of the counter is the sum of all actor counts.
 */
case class IncrementingCounter(
  private[crdt] val state: Map[String, Int] = Map.empty[String, Int]) extends CRDT {

  val `type`: String = "g-counter"

  def value: Int = state.values.sum

  def +(node: String, delta: Int = 1): IncrementingCounter = {
    if (delta < 0) throw new IllegalArgumentException("Can't decrement a IncrementingCounter")
    if (state.contains(node)) copy(state = state + (node -> (state(node) + delta)))
    else copy(state = state + (node -> delta))
  }

  def merge(that: IncrementingCounter): IncrementingCounter = {
    that.state.foldLeft(this) { (acc, record) => acc + (record._1, record._2) }
  }
}

object IncrementingCounter {
  implicit object format extends Format[IncrementingCounter] {
    def reads(json: JsValue): JsResult[IncrementingCounter] = JsSuccess(IncrementingCounter(
      (json \ "state").as[Map[String, Int]]
    ))

    def writes(ic: IncrementingCounter): JsValue = JsObject(Seq(
      "type" -> JsString(ic.`type`),
      "state" -> Json.toJson(ic.state)
    ))
  }
}
