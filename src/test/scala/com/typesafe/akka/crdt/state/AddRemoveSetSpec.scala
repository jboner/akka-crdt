/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package com.typesafe.akka.crdt.state

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import play.api.libs.json.Json._

class AddRemoveSetSpec extends WordSpec with MustMatchers {
  val data1 = "data1"
  val data2 = "data2"
  val data3 = "data3"
  val data4 = "data4"

  "An AddRemoveSet" must {

    "be able to add data" in {
      val c1 = AddRemoveSet[String]()

      val c2 = c1 + data1
      val c3 = c2 + data2

      val c4 = c3 + data4
      val c5 = c4 + data3

      c5.toSet must contain (data1)
      c5.toSet must contain (data2)
      c5.toSet must contain (data3)
      c5.toSet must contain (data4)
    }

    "be able to remove added data" in {
      val c1 = AddRemoveSet[String]()

      val c2 = c1 + data1
      val c3 = c2 + data2

      val c4 = c3 - data2
      val c5 = c4 - data1

      c5.toSet must not contain (data1)
      c5.toSet must not contain (data2)
    }


    "be throw exception if attempt to remove element that is not part of the set" in {
      val c1 = AddRemoveSet[String]()

      val c2 = c1 + data1
      val c3 = c2 + data2

      intercept[IllegalStateException] { c3 - data3 }
    }

    "be throw exception if attempt to add an element previously removed from set" in {
      val c1 = AddRemoveSet[String]()

      val c2 = c1 + data1
      val c3 = c2 - data1

      c3.toSet must not contain (data1)

      intercept[IllegalStateException] { c3 + data1 }
    }

    "be able to have its data set correctly merged with another AddRemoveSet with unique data sets" in {
      // set 1
      val c11 = AddRemoveSet[String]()

      val c12 = c11 + data1
      val c13 = c12 + data2

      c13.toSet must contain (data1)
      c13.toSet must contain (data2)

      // set 2
      val c21 = AddRemoveSet[String]()

      val c22 = c21 + data3
      val c23 = c22 + data4
      val c24 = c23 - data3

      c24.toSet must not contain (data3)
      c24.toSet must contain (data4)

      // merge both ways
      val merged1 = c13 merge c24
      merged1.toSet must contain (data1)
      merged1.toSet must contain (data2)
      merged1.toSet must not contain (data3)
      merged1.toSet must contain (data4)

      val merged2 = c24 merge c13
      merged2.toSet must contain (data1)
      merged2.toSet must contain (data2)
      merged2.toSet must not contain (data3)
      merged2.toSet must contain (data4)
    }

    "be able to have its data set correctly merged with another AddRemoveSet with overlapping data sets" in {
      // set 1
      val c10 = AddRemoveSet[String]()

      val c11 = c10 + data1
      val c12 = c11 + data2
      val c13 = c12 - data1

      c13.toSet must not contain (data1)
      c13.toSet must contain (data2)

      // set 2
      val c20 = AddRemoveSet[String]()

      val c21 = c20 + data1
      val c22 = c21 + data3
      val c23 = c22 + data4

      c23.toSet must contain (data1)
      c23.toSet must contain (data3)
      c23.toSet must contain (data4)

      // merge both ways
      val merged1 = c13 merge c23
      merged1.toSet must not contain (data1)
      merged1.toSet must contain (data2)
      merged1.toSet must contain (data3)
      merged1.toSet must contain (data4)

      val merged2 = c23 merge c13
      merged2.toSet must not contain (data1)
      merged2.toSet must contain (data2)
      merged2.toSet must contain (data3)
      merged2.toSet must contain (data4)
    }

    "be able to serialize itself to JSON" in {
      val c1 = AddRemoveSet[String]()

      stringify(toJson(c1)) must be("""{"type":"2p-set","increments":{"type":"g-set","state":[]},"decrements":{"type":"g-set","state":[]}}""")

      val c2 = c1 + data1
      val c3 = c2 + data2

      val c4 = c3 - data2
      val c5 = c4 + data3

      stringify(toJson(c5)) must be("""{"type":"2p-set","increments":{"type":"g-set","state":["data1","data2","data3"]},"decrements":{"type":"g-set","state":["data2"]}}""")
    }

    "be able to serialize itself from JSON" in {
      val json = parse("""{"type":"2p-set","increments":{"type":"g-set","state":["data1","data2","data3"]},"decrements":{"type":"g-set","state":["data2"]}}""")
      val c1 = json.as[AddRemoveSet[String]]

      c1.toSet must contain (data1)
      c1.toSet must not contain (data2)
      c1.toSet must contain (data3)
    }
  }
}
