/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package com.typesafe.akka.crdt

/**
 * Implements a CRDT 'Growing Counter' also called a 'G-Counter'.
 *
 * A G-Counter is a grow-only counter (inspired by vector clocks) in
 * which only increment and merge are possible. Incrementing the counter
 * adds 1 to the count for the current actor. Divergent histories are
 * resolved by taking the maximum count for each actor (like a vector
 * clock merge).  The value of the counter is the sum of all actor counts.
 */
case class IncrementingCounter(private[crdt] val state: Map[String, Int] = Map.empty[String, Int]) extends CRDT {

  def value: Int = state.values.sum

  def +(node: String, delta: Int = 1): IncrementingCounter = {
    if (delta < 0) throw new IllegalArgumentException("Can't decrement a IncrementingCounter")
    if (state.contains(node)) IncrementingCounter(state + (node -> (state(node) + delta)))
    else IncrementingCounter(state + (node -> delta))
  }

  def merge(that: IncrementingCounter): IncrementingCounter = {
    that.state.foldLeft(this) { (acc, record) => acc + (record._1, record._2) }
  }
}
