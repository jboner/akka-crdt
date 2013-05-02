/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.crdt.convergent

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import play.api.libs.json.Json._

class TwoPhaseSetSpec extends WordSpec with MustMatchers {
  val user1 = parse("""{"username":"john","password":"coltrane"}""")
  val user2 = parse("""{"username":"sonny","password":"rollins"}""")
  val user3 = parse("""{"username":"charlie","password":"parker"}""")
  val user4 = parse("""{"username":"charles","password":"mingus"}""")

  "An TwoPhaseSet" must {

    "be able to add user" in {
      val c1 = TwoPhaseSet(id = "users")

      val c2 = c1 + user1
      val c3 = c2 + user2

      val c4 = c3 + user4
      val c5 = c4 + user3

      c5.value must contain (user1)
      c5.value must contain (user2)
      c5.value must contain (user3)
      c5.value must contain (user4)
    }

    "be able to remove added user" in {
      val c1 = TwoPhaseSet(id = "users")

      val c2 = c1 + user1
      val c3 = c2 + user2

      val c4 = c3 - user2
      val c5 = c4 - user1

      c5.value must not contain (user1)
      c5.value must not contain (user2)
    }


    "be throw exception if attempt to remove element that is not part of the set" in {
      val c1 = TwoPhaseSet(id = "users")

      val c2 = c1 + user1
      val c3 = c2 + user2

      intercept[IllegalStateException] { c3 - user3 }
    }

    "be throw exception if attempt to add an element previously removed from set" in {
      val c1 = TwoPhaseSet(id = "users")

      val c2 = c1 + user1
      val c3 = c2 - user1

      c3.value must not contain (user1)

      intercept[IllegalStateException] { c3 + user1 }
    }

    "be able to have its user set correctly merged with another TwoPhaseSet with unique user sets" in {
      // set 1
      val c11 = TwoPhaseSet(id = "users")

      val c12 = c11 + user1
      val c13 = c12 + user2

      c13.value must contain (user1)
      c13.value must contain (user2)

      // set 2
      val c21 = TwoPhaseSet(id = "users")

      val c22 = c21 + user3
      val c23 = c22 + user4
      val c24 = c23 - user3

      c24.value must not contain (user3)
      c24.value must contain (user4)

      // merge both ways
      val merged1 = c13 merge c24
      merged1.value must contain (user1)
      merged1.value must contain (user2)
      merged1.value must not contain (user3)
      merged1.value must contain (user4)

      val merged2 = c24 merge c13
      merged2.value must contain (user1)
      merged2.value must contain (user2)
      merged2.value must not contain (user3)
      merged2.value must contain (user4)
    }

    "be able to have its user set correctly merged with another TwoPhaseSet with overlapping user sets" in {
      // set 1
      val c10 = TwoPhaseSet(id = "users")

      val c11 = c10 + user1
      val c12 = c11 + user2
      val c13 = c12 - user1

      c13.value must not contain (user1)
      c13.value must contain (user2)

      // set 2
      val c20 = TwoPhaseSet(id = "users")

      val c21 = c20 + user1
      val c22 = c21 + user3
      val c23 = c22 + user4

      c23.value must contain (user1)
      c23.value must contain (user3)
      c23.value must contain (user4)

      // merge both ways
      val merged1 = c13 merge c23
      merged1.value must not contain (user1)
      merged1.value must contain (user2)
      merged1.value must contain (user3)
      merged1.value must contain (user4)

      val merged2 = c23 merge c13
      merged2.value must not contain (user1)
      merged2.value must contain (user2)
      merged2.value must contain (user3)
      merged2.value must contain (user4)
    }

    "be able to serialize itself to JSON" in {
      val c1 = TwoPhaseSet(id = "users")

      stringify(toJson(c1)) must be("""{"type":"2p-set","id":"users","increments":{"type":"g-set","id":"users/inc","state":[]},"decrements":{"type":"g-set","id":"users/dec","state":[]}}""")

      val c2 = c1 + user1
      val c3 = c2 + user2

      val c4 = c3 - user2
      val c5 = c4 + user3

      stringify(toJson(c5)) must be("""{"type":"2p-set","id":"users","increments":{"type":"g-set","id":"users/inc","state":[{"username":"john","password":"coltrane"},{"username":"sonny","password":"rollins"},{"username":"charlie","password":"parker"}]},"decrements":{"type":"g-set","id":"users/dec","state":[{"username":"sonny","password":"rollins"}]}}""")
    }

    "be able to serialize itself from JSON" in {
      val json = parse("""{"type":"2p-set","id":"users","increments":{"type":"g-set","id":"users/inc","state":[{"username":"john","password":"coltrane"},{"username":"sonny","password":"rollins"},{"username":"charlie","password":"parker"}]},"decrements":{"type":"g-set","id":"users/dec","state":[{"username":"sonny","password":"rollins"}]}}""")
      val c1 = json.as[TwoPhaseSet]

      c1.value must contain (user1)
      c1.value must not contain (user2)
      c1.value must contain (user3)
    }
  }
}
