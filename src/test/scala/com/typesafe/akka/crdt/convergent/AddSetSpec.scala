/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package com.typesafe.akka.crdt.convergent

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers

import play.api.libs.json.Json._

class AddSetSpec extends WordSpec with MustMatchers {
  val user1 = parse("""{"username":"john","password":"coltrane"}""")
  val user2 = parse("""{"username":"sonny","password":"rollins"}""")
  val user3 = parse("""{"username":"charlie","password":"parker"}""")
  val user4 = parse("""{"username":"charles","password":"mingus"}""")

  "An AddSet" must {

    "be able to add user" in {
      val c1 = AddSet(id = "users")

      val c2 = c1 + user1
      val c3 = c2 + user2

      val c4 = c3 + user4
      val c5 = c4 + user3

      c5.toSet must contain (user1)
      c5.toSet must contain (user2)
      c5.toSet must contain (user3)
      c5.toSet must contain (user4)
    }

    "be able to have its user set correctly merged with another AddSet with unique user sets" in {
      // set 1
      val c11 = AddSet(id = "users")

      val c12 = c11 + user1
      val c13 = c12 + user2

      c13.toSet must contain (user1)
      c13.toSet must contain (user2)

      // set 2
      val c21 = AddSet(id = "users")

      val c22 = c21 + user3
      val c23 = c22 + user4

      c23.toSet must contain (user3)
      c23.toSet must contain (user4)

      // merge both ways
      val merged1 = c13 merge c23
      merged1.toSet must contain (user1)
      merged1.toSet must contain (user2)
      merged1.toSet must contain (user3)
      merged1.toSet must contain (user4)

      val merged2 = c23 merge c13
      merged2.toSet must contain (user1)
      merged2.toSet must contain (user2)
      merged2.toSet must contain (user3)
      merged2.toSet must contain (user4)
    }

    "be able to have its user set correctly merged with another AddSet with overlapping user sets" in {
      // set 1
      val c10 = AddSet(id = "users")

      val c11 = c10 + user1
      val c12 = c11 + user2
      val c13 = c12 + user3

      c13.toSet must contain (user1)
      c13.toSet must contain (user2)
      c13.toSet must contain (user3)

      // set 2
      val c20 = AddSet(id = "users")

      val c21 = c20 + user2
      val c22 = c21 + user3
      val c23 = c22 + user4

      c23.toSet must contain (user2)
      c23.toSet must contain (user3)
      c23.toSet must contain (user4)

      // merge both ways
      val merged1 = c13 merge c23
      merged1.toSet must contain (user1)
      merged1.toSet must contain (user2)
      merged1.toSet must contain (user3)
      merged1.toSet must contain (user4)

      val merged2 = c23 merge c13
      merged2.toSet must contain (user1)
      merged2.toSet must contain (user2)
      merged2.toSet must contain (user3)
      merged2.toSet must contain (user4)
    }

    "be able to serialize itself to JSON" in {
      val c1 = AddSet(id = "users")

      stringify(toJson(c1)) must be("""{"type":"g-set","id":"users","state":[]}""")

      val c2 = c1 + user1
      val c3 = c2 + user2

      val c4 = c3 + user4
      val c5 = c4 + user3

      stringify(toJson(c5)) must be("""{"type":"g-set","id":"users","state":[{"username":"john","password":"coltrane"},{"username":"sonny","password":"rollins"},{"username":"charles","password":"mingus"},{"username":"charlie","password":"parker"}]}""")
    }

    "be able to serialize itself from JSON" in {
      val json = parse("""{"type":"g-set","id":"users","state":[{"username":"john","password":"coltrane"},{"username":"sonny","password":"rollins"},{"username":"charles","password":"mingus"},{"username":"charlie","password":"parker"}]}""")
      val c1 = json.as[AddSet]

      c1.toSet must contain (user1)
      c1.toSet must contain (user2)
      c1.toSet must contain (user3)
      c1.toSet must contain (user4)
    }
  }
}
