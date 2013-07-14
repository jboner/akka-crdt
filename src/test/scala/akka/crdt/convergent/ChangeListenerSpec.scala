/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.crdt.convergent

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import org.scalatest.BeforeAndAfter
import akka.actor.ActorSystem
import akka.actor.Props
import akka.actor.Actor
import akka.actor.ActorRef
import akka.testkit.ImplicitSender
import akka.testkit.TestKit
import scala.concurrent.duration._
import com.typesafe.config.ConfigFactory
import play.api.libs.json.Json._
import scala.concurrent.Await
import scala.concurrent.duration._

class ChangeListenerSpec
  extends TestKit(ActorSystem("ChangeListenerSpec", ConfigFactory.parseString("""
		akka {
  		crdt.convergent.leveldb.destroy-on-shutdown = on
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
		"""))) with WordSpec with MustMatchers with BeforeAndAfter with ImplicitSender {

  case object Kick

  val storage = ConvergentReplicatedDataTypeDatabase(system)

  val duration = 10 seconds

  val listener = system.actorOf(Props(new Actor {
    override def preStart(): Unit =
      context.system.eventStream.subscribe(self, classOf[ConvergentReplicatedDataType])
    override def postStop(): Unit =
      context.system.eventStream.unsubscribe(self)

    var testActor: ActorRef = _

    def receive = {
      case Kick                            ⇒ testActor = sender
      case e: ConvergentReplicatedDataType ⇒ testActor ! e
    }
  }))

  after {
    system.shutdown()
  }

  "A subscriber to the event stream" must {
    "receive events about (all types of) CvRDT changes" in {
      listener ! Kick // to set the sender

      // g-counter
      val gc1 = Await.result(storage.create[GCounter]("jonas"), duration)
      expectMsgType[GCounter](10 seconds)

      val gc2 = gc1 + "node1"
      storage.update(gc2)
      expectMsgType[GCounter](10 seconds)

      // pn-counter
      val pnc1 = Await.result(storage.create[PNCounter]("jonas"), duration)
      expectMsgType[PNCounter](10 seconds)

      val pnc2 = pnc1 + "node1"
      storage.update(pnc2)
      expectMsgType[PNCounter](10 seconds)

      val pnc3 = pnc2 - "node1"
      storage.update(pnc3)
      expectMsgType[PNCounter](10 seconds)

      // g-set
      val gs1 = Await.result(storage.create[GSet]("jonas"), duration)
      expectMsgType[GSet](10 seconds)

      val gs2 = gs1 + parse("""{"name":"jonas"}""")
      storage.update(gs2)
      expectMsgType[GSet](10 seconds)

      // 2p-set
      val tps1 = Await.result(storage.create[TwoPhaseSet]("jonas"), duration)
      expectMsgType[TwoPhaseSet](10 seconds)

      val tps2 = tps1 + parse("""{"name":"jonas"}""")
      storage.update(tps2)
      expectMsgType[TwoPhaseSet](10 seconds)
    }
  }
}
