/**
 *  Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.crdt.convergent

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import org.scalatest.BeforeAndAfter

import akka.testkit.TestKit
import akka.actor.ActorSystem

import unfiltered.netty._

import com.typesafe.config.ConfigFactory

import scala.concurrent.Await
import scala.concurrent.duration._

class RestServerSpec extends WordSpec with MustMatchers with BeforeAndAfter {
	val system = ActorSystem("RestServerSpec", ConfigFactory.parseString("""
		akka {
			actor.provider = akka.cluster.ClusterActorRefProvider
			loglevel = INFO
			loggers = ["akka.testkit.TestEventListener"]
			remote {
				enabled-transports = ["akka.remote.netty.tcp"]
				netty.tcp {
      		hostname = "127.0.0.1"
      		port = 0
				}
				log-remote-lifecycle-events = off
			}
		}
		"""))
  
  val storage = ConvergentReplicatedDataTypeStorage(system)
  
  val http = Http(9000, "127.0.0.1")
  	.handler(new GCounterPlan(storage))
    .start()
  
  val timeout = 5 seconds
  
  after {
		http.stop()
		system.shutdown()
	}
	
  "A CRDT REST server" must {
  	import dispatch._, Defaults._
  	val url = host("127.0.0.1", 9000)
  	
    "serve unfiltered text" ignore {
      val response = Await.result(Http(url / "ping"), timeout)
      response.getResponseBody().trim() must be("Pong")
    }
  }
}
