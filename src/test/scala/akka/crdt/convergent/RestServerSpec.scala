/**
 *  Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.crdt.convergent

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import org.scalatest.BeforeAndAfterAll
import akka.testkit.TestKit
import akka.actor.ActorSystem
import unfiltered.netty._
import unfiltered.request.PUT
import scala.concurrent.Await
import scala.concurrent.duration._
import com.typesafe.config.ConfigFactory

class RestServerSpec extends TestKit(
  ActorSystem("RestServerSpec", ConfigFactory.parseString("""
		akka {
			actor.provider = akka.cluster.ClusterActorRefProvider
			loglevel = INFO
			loggers = ["akka.testkit.TestEventListener"]
			remote {
				enabled-transports = ["akka.remote.netty.tcp"]
				netty.tcp {
      		hostname = "127.0.0.1"
      		port     = 0
				}
				log-remote-lifecycle-events = off
			}
			crdt.rest-server {
			  run      = on
  			hostname = "127.0.0.1"
			  port     = 9009
			}
  		crdt.convergent.leveldb.destroy-on-shutdown  = on
		}
	"""))) with WordSpec with MustMatchers with BeforeAndAfterAll {

  val storage = ConvergentReplicatedDataTypeDatabase(system)
  val waitForResponse = 5 seconds
  val waitForReplication = 10 seconds

  override def afterAll() = {
    storage.shutdown()
    system.shutdown()
    dispatch.Http.shutdown()
  }

  "A CRDT REST server" must {
    import dispatch._, Defaults._

    def newURL = host("127.0.0.1", 9009) // TODO: should use the config values

    // =================================================================
    // ping/pong
    // =================================================================

    "serve unfiltered text" in {
      val result = Await.result(Http(newURL / "ping" OK as.String), waitForResponse).trim()
      result must be("pong")
    }

    // =================================================================
    // g-counter
    // =================================================================

    "be able to create a new g-counter with a random id" in {
      Await.result(Http((newURL / "g-counter").PUT), waitForResponse).getResponseBody().trim().startsWith("Successfully created g-counter") must be(true)
    }

    "be able to create a new g-counter with a specific id" in {
      Await.result(Http((newURL / "g-counter" / "jonas").PUT), waitForResponse).getResponseBody().trim() must be("Successfully created g-counter with id = 'jonas'")
    }

    "be able to find a new g-counter with a specific id" in {
      awaitCond(Await.result(Http(newURL / "g-counter" / "jonas"), waitForResponse).getResponseBody().trim() == """{"type":"counter","id":"jonas","value":0}""", waitForReplication)
    }

    "be able to increment a g-counter" in {
      Http((newURL / "g-counter" / "jonas").POST <<? Map("node" -> "node1", "delta" -> "1") <:< Map("Content-type" -> "application/text"))
      awaitCond(Await.result(Http(newURL / "g-counter" / "jonas"), waitForResponse).getResponseBody().trim() == """{"type":"counter","id":"jonas","value":1}""", waitForReplication)
    }

    // =================================================================
    // pn-counter
    // =================================================================

    "be able to create a new pn-counter with a random id" in {
      Await.result(Http((newURL / "pn-counter").PUT), waitForResponse).getResponseBody().trim().startsWith("Successfully created pn-counter") must be(true)
    }

    "be able to create a new pn-counter with a specific id" in {
      Await.result(Http((newURL / "pn-counter" / "users1").PUT), waitForResponse).getResponseBody().trim() must be("Successfully created pn-counter with id = 'users1'")
    }

    "be able to find a new pn-counter with a specific id" in {
      awaitCond(Await.result(Http(newURL / "pn-counter" / "users1"), waitForResponse).getResponseBody().trim() == """{"type":"counter","id":"users1","value":0}""", waitForReplication)
    }

    "be able to increment a pn-counter" in {
      Http((newURL / "pn-counter" / "users1").POST <<? Map("node" -> "node1", "delta" -> "1") <:< Map("Content-type" -> "application/text"))
      awaitCond(Await.result(Http(newURL / "pn-counter" / "users1"), waitForResponse).getResponseBody().trim() == """{"type":"counter","id":"users1","value":1}""", waitForReplication)
    }

    "be able to decrement a pn-counter" in {
      Http((newURL / "pn-counter" / "users1").POST <<? Map("node" -> "node3", "delta" -> "-1") <:< Map("Content-type" -> "application/text"))
      awaitCond(Await.result(Http(newURL / "pn-counter" / "users1"), waitForResponse).getResponseBody().trim() == """{"type":"counter","id":"users1","value":0}""", waitForReplication)
    }

    // =================================================================
    // g-set
    // =================================================================

    "be able to create a new g-set with a random id" in {
      Await.result(Http((newURL / "g-set").PUT), waitForResponse).getResponseBody().trim().startsWith("Successfully created g-set") must be(true)
    }

    "be able to create a new g-set with a specific id" in {
      Await.result(Http((newURL / "g-set" / "users1").PUT), waitForResponse).getResponseBody().trim() must be("Successfully created g-set with id = 'users1'")
    }

    "be able to find a new g-set with a specific id" in {
      awaitCond(Await.result(Http(newURL / "g-set" / "users1"), waitForResponse).getResponseBody().trim() == """{"type":"set","id":"users1","value":[]}""", waitForReplication)
    }

    "be able to add a JSON value to a g-set" in {
      Http((newURL / "g-set" / "users1" / "add") << """{"username":"john","password":"coltrane"}""" <:< Map("Content-type" -> "application/json"))
      awaitCond(Await.result(Http(newURL / "g-set" / "users1"), waitForResponse).getResponseBody.trim() == """{"type":"set","id":"users1","value":[{"username":"john","password":"coltrane"}]}""", waitForReplication)
    }

    "be able to add an invalid JSON value to a g-set" in {
      val result = Await.result(Http((newURL / "g-set" / "users3" / "add") << """{"username":"john","password":"coltrane}""" <:< Map("Content-type" -> "application/json")), waitForResponse)
      result.getResponseBody() must startWith("org.codehaus.jackson.JsonParseException: Unexpected end-of-input: was expecting closing quote for a string value")
      result.getStatusCode() must be(400)
      result.getStatusText() must be("Bad Request")
    }

    // =================================================================
    // 2p-set
    // =================================================================

    "be able to create a new 2p-set with a random id" in {
      Await.result(Http((newURL / "2p-set").PUT), waitForResponse).getResponseBody().trim().startsWith("Successfully created 2p-set") must be(true)
    }

    "be able to create a new 2p-set with a specific id" in {
      Await.result(Http((newURL / "2p-set" / "users1").PUT), waitForResponse).getResponseBody().trim() must be("Successfully created 2p-set with id = 'users1'")
    }

    "be able to find a new 2p-set with a specific id" in {
      awaitCond(Await.result(Http(newURL / "2p-set" / "users1"), waitForResponse).getResponseBody().trim() == """{"type":"set","id":"users1","value":[]}""", waitForReplication)
    }

    "be able to add a JSON value to a 2p-set" in {
      Http((newURL / "2p-set" / "users1" / "add") << """{"username":"john","password":"coltrane"}""" <:< Map("Content-type" -> "application/json"))
      awaitCond(Await.result(Http(newURL / "2p-set" / "users1"), waitForResponse).getResponseBody().trim() == """{"type":"set","id":"users1","value":[{"username":"john","password":"coltrane"}]}""", waitForReplication)
    }

    "be able to remove a JSON value from a 2p-set" in {
      Http((newURL / "2p-set" / "users1" / "remove") << """{"username":"john","password":"coltrane"}""" <:< Map("Content-type" -> "application/json"))
      awaitCond(Await.result(Http(newURL / "2p-set" / "users1"), waitForResponse).getResponseBody().trim() == """{"type":"set","id":"users1","value":[]}""", waitForReplication)
    }

    "be able to add an invalid JSON value to a 2p-set" in {
      val result = Await.result(Http((newURL / "2p-set" / "users1" / "add") << """{"username":"john","password":"coltrane}""" <:< Map("Content-type" -> "application/json")), waitForResponse)
      result.getResponseBody() must startWith("org.codehaus.jackson.JsonParseException: Unexpected end-of-input: was expecting closing quote for a string value")
      result.getStatusCode() must be(400)
      result.getStatusText() must be("Bad Request")
    }
  }
}
