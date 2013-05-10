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

import play.api.libs.json.Json._
import play.api.libs.json._

object TwoPhaseSetClusterSpecConfig extends MultiNodeConfig {
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

class TwoPhaseSetClusterSpecMultiJvmNode1 extends TwoPhaseSetClusterSpec
class TwoPhaseSetClusterSpecMultiJvmNode2 extends TwoPhaseSetClusterSpec
class TwoPhaseSetClusterSpecMultiJvmNode3 extends TwoPhaseSetClusterSpec

class TwoPhaseSetClusterSpec extends MultiNodeSpec(TwoPhaseSetClusterSpecConfig) with STMultiNodeSpec {

  import TwoPhaseSetClusterSpecConfig._

  implicit def roleNameToAddress(role: RoleName): Address = testConductor.getAddressFor(role).await

  def initialParticipants = roles.size

  "A ConvergentReplicatedDataTypeDatabase" must {

    "Make sure that a TwoPhaseSet, used by multiple nodes, eventually converge to a consistent value" in {
      val cluster = Cluster(system)
      val storage = ConvergentReplicatedDataTypeDatabase(system)

      runOn(node1) { cluster join node1 }
      runOn(node2) { cluster join node1 }
      runOn(node3) { cluster join node1 }

      awaitConnectedSubscribers(initialParticipants)
      enterBarrier("pubsub-fully-connected")

      // create CRDT on node1
      runOn(node1) {
        storage.getOrCreate[TwoPhaseSet]("users").get.value.size must be(0)
      }
      enterBarrier("stored 2p-set on node1")

      // find CRDT by id on the other nodes
      runOn(node2, node3) {
        awaitAssert(storage.getOrCreate[TwoPhaseSet]("users").get) // wait until it does not throw exception
      }
      enterBarrier("2p-set exists on all nodes")

      val coltrane = """{"username":"john","password":"coltrane"}"""
      val rollins = """{"username":"sonny","password":"rollins"}"""
      val parker = """{"username":"charlie","password":"parker"}"""

      // let each node update the set
      runOn(node1) {
        storage.getOrCreate[TwoPhaseSet]("users") map (_ + parse(coltrane)) foreach (storage.update(_))
      }
      runOn(node2) {
        storage.getOrCreate[TwoPhaseSet]("users") map (_ + parse(rollins)) foreach (storage.update(_))
      }
      runOn(node3) {
        storage.getOrCreate[TwoPhaseSet]("users") map (_ + parse(parker)) foreach (storage.update(_))
      }
      enterBarrier("all-nodes-have-added-an-item")

      // make sure each node sees the converged set with all the users
      runOn(node1, node2, node3) {
        awaitCond(storage.getOrCreate[TwoPhaseSet]("users").get.value.size == 3, 10 seconds)
        storage.getOrCreate[TwoPhaseSet]("users") match {
          case Success(set) =>
            val usersAsStrings = set.value.map(stringify(_))
            usersAsStrings.contains(coltrane) must be(true)
            usersAsStrings.contains(rollins) must be(true)
            usersAsStrings.contains(parker) must be(true)

          case Failure(error) =>
            fail(error)
        }
      }

      // remove one of the items
      runOn(node3) {
        storage.getOrCreate[TwoPhaseSet]("users") map (_ - parse(coltrane)) foreach (storage.update(_))
      }
      enterBarrier("one-node-have-deleted-an-item")

      // make sure each node sees the converged set with all the users
      runOn(node1, node2, node3) {
        awaitCond(storage.getOrCreate[TwoPhaseSet]("users").get.value.size == 2, 10 seconds)
        storage.getOrCreate[TwoPhaseSet]("users") match {
          case Success(set) =>
            val usersAsStrings = set.value.map(stringify(_))
            usersAsStrings.contains(coltrane) must be(false)
            usersAsStrings.contains(rollins) must be(true)
            usersAsStrings.contains(parker) must be(true)

          case Failure(error) =>
            fail(error)
        }
      }

      enterBarrier("verified-set-on-all-nodes")
    }
  }
}
