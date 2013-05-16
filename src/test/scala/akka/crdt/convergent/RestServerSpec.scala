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

import com.typesafe.config.ConfigFactory

import scala.concurrent.Await
import scala.concurrent.duration._

class RestServerSpec extends WordSpec with MustMatchers with BeforeAndAfterAll {

  val system = ActorSystem("RestServerSpec", ConfigFactory.parseString("""
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
			  port     = 9000
			}
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

    def newURL = host("127.0.0.1", 9000)

    "serve unfiltered text" in {
      val result = Await.result(Http(newURL / "ping" OK as.String), timeout).trim()
      result must be("pong")
    }

    // =================================================================
    // g-counter
    // =================================================================

    "be able to create a new g-counter with a specific id" in {
      val result = Await.result(Http(newURL / "g-counter" / "jonas" OK as.String), timeout).trim()
      result must be("""{"type":"counter","id":"jonas","value":0}""")
    }

    "be able to create a new g-counter with a random id" in {
      val result = Await.result(Http(newURL / "g-counter" OK as.String), timeout).trim()
      result.startsWith("""{"type":"counter"""") must be(true)
    }

    "be able to increment a g-counter" in {
      val response = Http((newURL / "g-counter" / "jonas").POST <<? Map("node" -> "node1", "delta" -> "1") <:< Map("Content-type" -> "application/text") OK as.String)
      val result = Await.result(response, timeout).trim()
      result must be("""{"type":"counter","id":"jonas","value":1}""")
    }

    "be able to update a g-counter JSON body" in {
      val json = """{"type":"g-counter","id":"jonas","state":{"node1":2}}"""
      val response = Http((newURL / "g-counter" / "jonas") << json <:< Map("Content-type" -> "application/json") OK as.String)
      val result = Await.result(response, timeout).trim()
      result must be("""{"type":"counter","id":"jonas","value":2}""")
    }

    "be get an error response if invalid g-counter JSON body is POSTed" ignore {
      val json = """{"type":"g-counter","id":"jonas","state":{"node1":2}""" // not well-formed
      val response = Http((newURL / "g-counter" / "jonas") << json <:< Map("Content-type" -> "application/json") OK as.String)
      val result = Await.result(response, timeout).trim()
      result must be("""{"type":"counter","id":"jonas","value":2}""")
    }

    // =================================================================
    // pn-counter
    // =================================================================

    "be able to create a new pn-counter with a specific id" in {
      val result = Await.result(Http(newURL / "pn-counter" / "users1" OK as.String), timeout).trim()
      result must be("""{"type":"counter","id":"users1","value":0}""")
    }

    "be able to create a new pn-counter with a random id" in {
      val result = Await.result(Http(newURL / "pn-counter" OK as.String), timeout).trim()
      result.startsWith("""{"type":"counter"""") must be(true)
    }

    "be able to increment a pn-counter" in {
      val response = Http((newURL / "pn-counter" / "users2").POST <<? Map("node" -> "node1", "delta" -> "1") <:< Map("Content-type" -> "application/text") OK as.String)
      val result = Await.result(response, timeout).trim()
      result must be("""{"type":"counter","id":"users2","value":1}""")
    }

    "be able to decrement a pn-counter" in {
      val response = Http((newURL / "pn-counter" / "users3").POST <<? Map("node" -> "node3", "delta" -> "-1") <:< Map("Content-type" -> "application/text") OK as.String)
      val result = Await.result(response, timeout).trim()
      result must be("""{"type":"counter","id":"users3","value":-1}""")
    }

    "be able to update a pn-counter JSON body" in {
      val json = """{"type":"pn-counter","id":"users4","increments":{"type":"g-counter","id":"users4/inc","state":{"node1":2, "node2":1}},"decrements":{"type":"g-counter","id":"users4/dec","state":{"node1":1}}}"""
      val response = Http((newURL / "pn-counter" / "users4") << json <:< Map("Content-type" -> "application/json") OK as.String)
      val result = Await.result(response, timeout).trim()
      result must be("""{"type":"counter","id":"users4","value":2}""")
    }

    "be get an error response if invalid pn-counter JSON body is POSTed" ignore {
      val json = """{"type":"pn-counter","id":"users4","increments":{"type":"g-counter","id":"users4/inc","state":{"node1":2, "node2":1}},"decrements":{"type":"g-counter","id":"users4/dec","state":{"node1"=1}}}"""
      val response = Http((newURL / "pn-counter" / "users4") << json <:< Map("Content-type" -> "application/json") OK as.String)
      val result = Await.result(response, timeout).trim()
      result must be("""{"type":"counter","id":"users4","value":2}""")
    }

  }
}
