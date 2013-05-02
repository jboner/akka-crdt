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

import com.typesafe.config.ConfigFactory

object BroadcastClusterSpecConfig extends MultiNodeConfig {
  val node1 = role("node1")
  val node2 = role("node2")
  val node3 = role("node3")

  commonConfig(ConfigFactory.parseString("""
    akka.actor.provider = akka.cluster.ClusterActorRefProvider
    akka.cluster.auto-join = off
    akka.cluster.auto-down = on
    akka.loggers = ["akka.testkit.TestEventListener"]
    akka.loglevel = INFO
    akka.remote.log-remote-lifecycle-events = off"""))
}

class BroadcastClusterSpecMultiJvmNode1 extends BroadcastClusterSpec
class BroadcastClusterSpecMultiJvmNode2 extends BroadcastClusterSpec
class BroadcastClusterSpecMultiJvmNode3 extends BroadcastClusterSpec

class BroadcastClusterSpec extends MultiNodeSpec(BroadcastClusterSpecConfig) with STMultiNodeSpec {

  import BroadcastClusterSpecConfig._

  implicit def roleNameToAddress(role: RoleName): Address = testConductor.getAddressFor(role).await

  def initialParticipants = roles.size

  "A ConvergentReplicatedDataTypeStorage" must {

    "broadcast all CvRDT changes to all cluster nodes" in {
      val cluster = Cluster(system)
      val storage = ConvergentReplicatedDataTypeStorage(system)

      runOn(node1) { cluster join node1 }
      runOn(node2) { cluster join node1 }
      runOn(node3) { cluster join node1 }

      awaitConnectedSubscribers(initialParticipants)
      enterBarrier("pubsub-fully-connected")

      // create directly and then store using 'update'
      runOn(node1) {
        val gcounter = GCounter("jonas")
        storage.update(gcounter)
      }
      enterBarrier("stored g-counter on node1")

      // find by id on the other nodes
      runOn(node2, node3) {
        awaitAssert(storage.findById[GCounter]("jonas").get)
        storage.findById[GCounter]("jonas") match {
          case Success(counter) =>
            counter.id must be("jonas")
            counter.`type` must be("g-counter")
          case Failure(error) =>
            fail(error)
        }
      }
      enterBarrier("replicated g-counter from node1")

      // create in the storage and have it updated automatically
      runOn(node2) {
        val gcounter = storage.create[GCounter]("viktor")
      }
      enterBarrier("stored g-counter on node2")

      // find by id on the other nodes
      runOn(node1, node3) {
        awaitAssert(storage.findById[GCounter]("viktor").get)
        storage.findById[GCounter]("viktor") match {
          case Success(counter) =>
            counter.id must be("viktor")
            counter.`type` must be("g-counter")
          case Failure(error) =>
            fail(error)
        }
      }
      enterBarrier("replicated g-counter from node2")
    }
  }
}
