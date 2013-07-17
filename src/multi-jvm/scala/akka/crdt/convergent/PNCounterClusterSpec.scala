/**
 *  Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.crdt.convergent

import akka.remote.testkit.MultiNodeConfig
import akka.crdt._
import akka.remote.testkit.MultiNodeSpec
import akka.remote.testconductor.RoleName
import akka.actor._
import akka.cluster._
import scala.util._
import scala.concurrent.duration._
import com.typesafe.config.ConfigFactory
import scala.concurrent.Await
import akka.util.Timeout

object PNCounterClusterSpecConfig extends MultiNodeConfig {
  val node1 = role("node1")
  val node2 = role("node2")
  val node3 = role("node3")

  commonConfig(ConfigFactory.parseString("""
    akka.crdt.convergent.leveldb.destroy-on-shutdown = on
    akka.convergent.batching-window = 10ms
    akka.actor.provider = akka.cluster.ClusterActorRefProvider
    akka.cluster.auto-join = off
    akka.cluster.auto-down = on
    akka.loggers = ["akka.testkit.TestEventListener"]
    akka.loglevel = INFO
    akka.remote.log-remote-lifecycle-events = off
  """))
}

class PNCounterClusterSpecMultiJvmNode1 extends PNCounterClusterSpec
class PNCounterClusterSpecMultiJvmNode2 extends PNCounterClusterSpec
class PNCounterClusterSpecMultiJvmNode3 extends PNCounterClusterSpec

class PNCounterClusterSpec extends MultiNodeSpec(PNCounterClusterSpecConfig) with STMultiNodeSpec {

  import PNCounterClusterSpecConfig._

  implicit def roleNameToAddress(role: RoleName): Address = testConductor.getAddressFor(role).await
  implicit val sys: ActorSystem = system

  def initialParticipants = roles.size

  "A ConvergentReplicatedDataTypeDatabase" must {

    "Make sure that a PNCounter, used by multiple nodes, eventually converge to a consistent value" in {
      val cluster = Cluster(system)
      val db = ConvergentReplicatedDataTypeDatabase(system)

      implicit val ec = system.dispatcher
      val duration = 10 seconds

      runOn(node1) { cluster join node1 }
      runOn(node2) { cluster join node1 }
      runOn(node3) { cluster join node1 }

      Thread.sleep(5000)
      
      // create CRDT on node1
      runOn(node1) {
        db.create[PNCounter]("jonas").value must be(0)
      }
      enterBarrier("stored pn-counter on node1")

      // find CRDT by id on the other nodes
      runOn(node2, node3) {
        awaitAssert(Await.result(db.findById[PNCounter]("jonas"), duration)) // wait until it does not throw exception
      }
      enterBarrier("pn-counter exists on all nodes")

      // let each node update the counter (incrementing or decrementing)
      runOn(node1) {
        db.findById[PNCounter]("jonas") map (_ + (node1.name, 2)) foreach (db.update(_))
      }
      runOn(node2) {
        db.findById[PNCounter]("jonas") map (_ - node2.name) foreach (db.update(_))
      }
      runOn(node3) {
        db.findById[PNCounter]("jonas") map (_ + node3.name) foreach (db.update(_))
        db.findById[PNCounter]("jonas") map (_ - node3.name) foreach (db.update(_))
      }
      enterBarrier("updated-counter-on-all-nodes")

      // make sure each node sees the converged counter value of 3
      runOn(node1, node2, node3) {
        awaitCond(Await.result(db.findById[PNCounter]("jonas"), 100 millis).value == 1, 10 seconds)
      }

      enterBarrier("verified-counter-on-all-nodes")
      
      db.shutdown()
      enterBarrier("after-shutdown")
    }
  }
}
