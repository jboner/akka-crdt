/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package com.typesafe.akka.crdt

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers

import play.api.libs.json.Json._

class IncrementingCounterSpec extends WordSpec with MustMatchers {
  val node1 = "node1"
  val node2 = "node2"

  "A IncrementingCounter" must {

    "be able to increment each node's record by one" in {
      val c1 = IncrementingCounter()

      val c2 = c1 + node1
      val c3 = c2 + node1

      val c4 = c3 + node2
      val c5 = c4 + node2
      val c6 = c5 + node2

      c6.state(node1) must be(2)
      c6.state(node2) must be(3)
    }

    "be able to increment each node's record by arbitrary delta" in {
      val c1 = IncrementingCounter()

      val c2 = c1 + (node1, 3)
      val c3 = c2 + (node1, 4)

      val c4 = c3 + (node2, 2)
      val c5 = c4 + (node2, 7)
      val c6 = c5 + node2

      c6.state(node1) must be(7)
      c6.state(node2) must be(10)
    }

    "be able to summarize the history to the correct aggregated value" in {
      val c1 = IncrementingCounter()

      val c2 = c1 + (node1, 3)
      val c3 = c2 + (node1, 4)

      val c4 = c3 + (node2, 2)
      val c5 = c4 + (node2, 7)
      val c6 = c5 + node2

      c6.state(node1) must be(7)
      c6.state(node2) must be(10)

      c6.value must be(17)
    }

    "be able to have its history correctly merged with another IncrementingCounter" in {
      // counter 1
      val c11 = IncrementingCounter()
      val c12 = c11 + (node1, 3)
      val c13 = c12 + (node1, 4)
      val c14 = c13 + (node2, 2)
      val c15 = c14 + (node2, 7)
      val c16 = c15 + node2

      c16.state(node1) must be(7)
      c16.state(node2) must be(10)

      // counter 1
      val c21 = IncrementingCounter()
      val c22 = c21 + (node1, 2)
      val c23 = c22 + (node1, 2)
      val c24 = c23 + (node2, 3)
      val c25 = c24 + (node2, 2)
      val c26 = c25 + node2

      c26.state(node1) must be(4)
      c26.state(node2) must be(6)

      // merge both ways
      val merged1 = c16 merge c26
      merged1.state(node1) must be(11)
      merged1.state(node2) must be(16)

      val merged2 = c26 merge c16
      merged2.state(node1) must be(11)
      merged2.state(node2) must be(16)
    }

    "be able to serialize itself to JSON" in {
      val c1 = IncrementingCounter()

      stringify(toJson(c1)) must be("""{"type":"g-counter","state":{}}""")

      val c2 = c1 + node1
      val c3 = c2 + node1

      val c4 = c3 + node2
      val c5 = c4 + node2
      val c6 = c5 + node2

      c6.state(node1) must be(2)
      c6.state(node2) must be(3)

      stringify(toJson(c6)) must be("""{"type":"g-counter","state":{"node1":2,"node2":3}}""")
    }

    "be able to serialize itself from JSON" in {
      val json = parse("""{"type":"g-counter","state":{"node1":2,"node2":3}}""")
      val c1 = json.as[IncrementingCounter]

      c1.state(node1) must be(2)
      c1.state(node2) must be(3)
    }
  }
}
