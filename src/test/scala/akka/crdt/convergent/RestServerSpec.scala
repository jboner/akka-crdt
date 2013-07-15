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

class RestServerSpec extends WordSpec with MustMatchers with BeforeAndAfterAll {

  val system = ActorSystem("RestServerSpec", ConfigFactory.parseString("""
		akka {
			actor.provider = akka.cluster.ClusterActorRefProvider
			loglevel = DEBUG
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
		"""))

  val storage = ConvergentReplicatedDataTypeDatabase(system)
  val timeout = 5 seconds

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
      val result = Await.result(Http(newURL / "ping" OK as.String), timeout).trim()
      result must be("pong")
    }

    // =================================================================
    // g-counter
    // =================================================================

    "be able to create a new g-counter with a random id" in {
      val result = Await.result(Http((newURL / "g-counter").PUT), timeout).getResponseBody().trim()
      result.startsWith("Successfully created g-counter") must be(true)
    }

    "be able to create a new g-counter with a specific id" in {
      val result = Await.result(Http((newURL / "g-counter" / "jonas").PUT), timeout).getResponseBody().trim()
      result must be("Successfully created g-counter with id = 'jonas'")
    }

    "be able to find a new g-counter with a specific id" in {
      val result = Await.result(Http(newURL / "g-counter" / "jonas"), timeout).getResponseBody().trim()
      result must be("""{"type":"counter","id":"jonas","value":0}""")
    }

    "be able to increment a g-counter" in {
      val response = Http((newURL / "g-counter" / "jonas").POST <<? Map("node" -> "node1", "delta" -> "1") <:< Map("Content-type" -> "application/text") OK as.String)
      val result = Await.result(response, timeout).trim()
      result must be("""{"type":"counter","id":"jonas","value":1}""")
    }

    // =================================================================
    // pn-counter
    // =================================================================

    "be able to create a new pn-counter with a random id" in {
      val result = Await.result(Http((newURL / "pn-counter").PUT), timeout).getResponseBody().trim()
      result.startsWith("Successfully created pn-counter") must be(true)
    }

    "be able to create a new pn-counter with a specific id" in {
      val result = Await.result(Http((newURL / "pn-counter" / "users1").PUT), timeout).getResponseBody().trim()
      result must be("Successfully created pn-counter with id = 'users1'")
    }

    "be able to find a new pn-counter with a specific id" in {
      val result = Await.result(Http(newURL / "pn-counter" / "users1"), timeout).getResponseBody().trim()
      result must be("""{"type":"counter","id":"users1","value":0}""")
    }

    "be able to increment a pn-counter" in {
      val response = Http((newURL / "pn-counter" / "users1").POST <<? Map("node" -> "node1", "delta" -> "1") <:< Map("Content-type" -> "application/text") OK as.String)
      val result = Await.result(response, timeout).trim()
      result must be("""{"type":"counter","id":"users1","value":1}""")
    }

    "be able to decrement a pn-counter" in {
      val response = Http((newURL / "pn-counter" / "users1").POST <<? Map("node" -> "node3", "delta" -> "-1") <:< Map("Content-type" -> "application/text") OK as.String)
      val result = Await.result(response, timeout).trim()
      result must be("""{"type":"counter","id":"users1","value":0}""")
    }

    // =================================================================
    // g-set
    // =================================================================

    "be able to create a new g-set with a random id" in {
      val result = Await.result(Http((newURL / "g-set").PUT), timeout).getResponseBody().trim()
      result.startsWith("Successfully created g-set") must be(true)
    }

    "be able to create a new g-set with a specific id" in {
      val result = Await.result(Http((newURL / "g-set" / "users1").PUT), timeout).getResponseBody().trim()
      result must be("Successfully created g-set with id = 'users1'")
    }

    "be able to find a new g-set with a specific id" in {
      val result = Await.result(Http(newURL / "g-set" / "users1"), timeout).getResponseBody().trim()
      result must be("""{"type":"set","id":"users1","value":[]}""")
    }

    "be able to add a JSON value to a g-set" in {
      val userValue = """{"username":"john","password":"coltrane"}"""
      val response = Http((newURL / "g-set" / "users1" / "add") << userValue <:< Map("Content-type" -> "application/json") OK as.String)
      val result = Await.result(response, timeout).trim()
      result must be("""{"type":"set","id":"users1","value":[{"username":"john","password":"coltrane"}]}""")
    }

    "be able to add an invalid JSON value to a g-set" in {
      val userValue = """{"username":"john","password":"coltrane}"""
      val response = Http((newURL / "g-set" / "users3" / "add") << userValue <:< Map("Content-type" -> "application/json"))
      val result = Await.result(response, timeout)
      result.getResponseBody() must startWith("org.codehaus.jackson.JsonParseException: Unexpected end-of-input: was expecting closing quote for a string value")
      result.getStatusCode() must be(400)
      result.getStatusText() must be("Bad Request")
    }

    // =================================================================
    // 2p-set
    // =================================================================

    "be able to create a new 2p-set with a random id" in {
      val result = Await.result(Http((newURL / "2p-set").PUT), timeout).getResponseBody().trim()
      result.startsWith("Successfully created 2p-set") must be(true)
    }

    "be able to create a new 2p-set with a specific id" in {
      val result = Await.result(Http((newURL / "2p-set" / "users1").PUT), timeout).getResponseBody().trim()
      result must be("Successfully created 2p-set with id = 'users1'")
    }

    "be able to find a new 2p-set with a specific id" in {
      val result = Await.result(Http(newURL / "2p-set" / "users1"), timeout).getResponseBody().trim()
      result must be("""{"type":"set","id":"users1","value":[]}""")
    }

    "be able to add a JSON value to a 2p-set" in {
      val userValue = """{"username":"john","password":"coltrane"}"""
      val response = Http((newURL / "2p-set" / "users1" / "add") << userValue <:< Map("Content-type" -> "application/json") OK as.String)
      val result = Await.result(response, timeout).trim()
      result must be("""{"type":"set","id":"users1","value":[{"username":"john","password":"coltrane"}]}""")
    }

    "be able to remove a JSON value from a 2p-set" in {
      val userValue = """{"username":"john","password":"coltrane"}"""
      val response2 = Http((newURL / "2p-set" / "users1" / "remove") << userValue <:< Map("Content-type" -> "application/json") OK as.String)
      val result2 = Await.result(response2, timeout).trim()
      result2 must be("""{"type":"set","id":"users1","value":[]}""")
    }

    "be able to add an invalid JSON value to a 2p-set" in {
      val userValue = """{"username":"john","password":"coltrane}"""
      val response = Http((newURL / "2p-set" / "users1" / "add") << userValue <:< Map("Content-type" -> "application/json"))
      val result = Await.result(response, timeout)
      result.getResponseBody() must startWith("org.codehaus.jackson.JsonParseException: Unexpected end-of-input: was expecting closing quote for a string value")
      result.getStatusCode() must be(400)
      result.getStatusText() must be("Bad Request")
    }
  }
}
