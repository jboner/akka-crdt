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

object GCounterClusterSpecConfig extends MultiNodeConfig {
  val node1 = role("node1")
  val node2 = role("node2")
  val node3 = role("node3")

  commonConfig(ConfigFactory.parseString("""
    akka.crdt.convergent.leveldb.destroy-on-shutdown = on
    akka.actor.provider = akka.cluster.ClusterActorRefProvider
    akka.cluster.auto-join = off
    akka.cluster.auto-down = on
    akka.loggers = ["akka.testkit.TestEventListener"]
    akka.loglevel = DEBUG
    akka.remote.log-remote-lifecycle-events = off"""))
}

class GCounterClusterSpecMultiJvmNode1 extends GCounterClusterSpec
class GCounterClusterSpecMultiJvmNode2 extends GCounterClusterSpec
class GCounterClusterSpecMultiJvmNode3 extends GCounterClusterSpec

class GCounterClusterSpec extends MultiNodeSpec(GCounterClusterSpecConfig) with STMultiNodeSpec {

  import GCounterClusterSpecConfig._

  implicit def roleNameToAddress(role: RoleName): Address = testConductor.getAddressFor(role).await

  def initialParticipants = roles.size

  "A ConvergentReplicatedDataTypeDatabase" must {

    "Make sure that a GCounter, used by multiple nodes, eventually converge to a consistent value" in {
      val cluster = Cluster(system)
      val storage = ConvergentReplicatedDataTypeDatabase(system)

      implicit val ec = system.dispatcher
      val duration = 10 seconds

      runOn(node1) { cluster join node1 }
      runOn(node2) { cluster join node1 }
      runOn(node3) { cluster join node1 }

      Thread.sleep(5000)

      // create CRDT on node1
      runOn(node1) {
        Await.result(storage.create[GCounter]("jonas"), duration).value must be(0)
      }
      enterBarrier("stored g-counter on node1")

      // find CRDT by id on the other nodes
      runOn(node1, node2, node3) {
        awaitAssert(Await.result(storage.findById[GCounter]("jonas"), duration)) // wait until it does not throw exception
      }
      enterBarrier("g-counter exists on all nodes")

      // let each node update the counter with 1
      runOn(node1) {
        storage.findById[GCounter]("jonas") map (_ + node1.name) foreach (storage.update(_))
      }
      runOn(node2) {
        storage.findById[GCounter]("jonas") map (_ + node2.name) foreach (storage.update(_))
      }
      runOn(node3) {
        storage.findById[GCounter]("jonas") map (_ + node3.name) foreach (storage.update(_))
      }
      enterBarrier("updated-counter-on-all-nodes")

      // make sure each node sees the converged counter value of 3
      runOn(node1, node2, node3) {
        awaitCond(Await.result(storage.findById[GCounter]("jonas"), duration).value == 3, 10 seconds)
      }

      enterBarrier("verified-counter-on-all-nodes")
      
      storage.shutdown()
      enterBarrier("after-shutdown")
    }
  }
}
