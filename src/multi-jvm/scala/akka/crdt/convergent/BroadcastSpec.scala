/**
 *  Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.crdt.convergent

import akka.remote.testkit.MultiNodeConfig

import akka.crdt._

import akka.remote.testkit.MultiNodeSpec
import akka.testkit.ImplicitSender
import akka.remote.testconductor.RoleName
import akka.actor._
import akka.cluster._

import com.typesafe.config.ConfigFactory

object BroadcastSpecConfig extends MultiNodeConfig {
  val node1 = role("node1")
  val node2 = role("node2")
  val node3 = role("node3")

  commonConfig(ConfigFactory.parseString("""
    akka.actor.provider = akka.cluster.ClusterActorRefProvider
    akka.cluster.auto-join = off
    akka.cluster.auto-down = on
    akka.loggers = ["akka.testkit.TestEventListener"]
    akka.loglevel = INFO
    akka.remote.log-remote-lifecycle-events = off
  """))
}

class BroadcastSpecSpecMultiJvmNode1 extends BroadcastSpec
class BroadcastSpecSpecMultiJvmNode2 extends BroadcastSpec
class BroadcastSpecSpecMultiJvmNode3 extends BroadcastSpec

class BroadcastSpec extends MultiNodeSpec(BroadcastSpecConfig) with ScalaTestMultiNodeSpec with ImplicitSender {

  import BroadcastSpecConfig._

  implicit def roleNameToAddress(role: RoleName): Address = testConductor.getAddressFor(role).await

  def initialParticipants = 3//roles.size

  "A ConvergentReplicatedDataTypeStorage" must {

    "broadcast all CvRDT changes to all cluster nodes" in {
      val cluster = Cluster(system)
      val crdt = ConvergentReplicatedDataTypeStorage(system)

      println("-----------" + crdt.findById[GCounter]("hello"))

      runOn(node1) {
        cluster join node1
      }
      enterBarrier("node1-started")

      runOn(node2) {
        cluster join node1
      }
      enterBarrier("node2-started")

      runOn(node3) {
        cluster join node1
      }
      enterBarrier("node3-started")

      Thread.sleep(5000)

      runOn(node1) {
        crdt.store(GCounter())
      }
      runOn(node2) {
        crdt.store(GSet())
      }

      Thread.sleep(5000)

      enterBarrier("finished")
    }
  }
}
