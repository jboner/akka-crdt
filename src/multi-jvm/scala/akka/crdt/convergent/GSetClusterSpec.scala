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
import scala.concurrent.Await

object GSetClusterSpecConfig extends MultiNodeConfig {
  val node1 = role("node1")
  val node2 = role("node2")
  val node3 = role("node3")

  commonConfig(ConfigFactory.parseString("""
    akka.crdt.convergent.leveldb.destroy-on-shutdown = on
    akka.actor.provider = akka.cluster.ClusterActorRefProvider
    akka.cluster.auto-join = off
    akka.cluster.auto-down = on
    akka.loggers = ["akka.testkit.TestEventListener"]
    akka.loglevel = INFO
    akka.remote.log-remote-lifecycle-events = off
  """))
}

class GSetClusterSpecMultiJvmNode1 extends GSetClusterSpec
class GSetClusterSpecMultiJvmNode2 extends GSetClusterSpec
class GSetClusterSpecMultiJvmNode3 extends GSetClusterSpec

class GSetClusterSpec extends MultiNodeSpec(GSetClusterSpecConfig) with STMultiNodeSpec {

  import GSetClusterSpecConfig._

  implicit def roleNameToAddress(role: RoleName): Address = testConductor.getAddressFor(role).await
  implicit val sys: ActorSystem = system

  def initialParticipants = roles.size

  "A ConvergentReplicatedDataTypeDatabase" must {

    "Make sure that a GSet, used by multiple nodes, eventually converge to a consistent value" in {
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
        db.create[GSet]("users").size must be(0)
      }
      enterBarrier("stored g-set on node1")

      // find CRDT by id on the other nodes
      runOn(node2, node3) {
        awaitAssert(Await.result(db.findById[GSet]("users"), duration)) // wait until it does not throw exception
      }
      enterBarrier("g-set exists on all nodes")

      val coltrane = """{"username":"john","password":"coltrane"}"""
      val rollins = """{"username":"sonny","password":"rollins"}"""
      val parker = """{"username":"charlie","password":"parker"}"""

      // let each node update the set
      runOn(node1) {
        db.findById[GSet]("users") map (_ + parse(coltrane)) foreach (db.update(_))
      }
      runOn(node2) {
        db.findById[GSet]("users") map (_ + parse(rollins)) foreach (db.update(_))
      }
      runOn(node3) {
        db.findById[GSet]("users") map (_ + parse(parker)) foreach (db.update(_))
        db.findById[GSet]("users") map (_ + parse(rollins)) foreach (db.update(_)) // try to add the same element concurrently
      }
      enterBarrier("updated-set-on-all-nodes")

      // make sure each node sees the converged set with all the users
      runOn(node1, node2, node3) {
        awaitCond(Await.result(db.findById[GSet]("users"), duration).value.size == 3, 10 seconds)
        db.findById[GSet]("users") foreach { set =>
          set.id must be("users")
          set.dataType must be("g-set")
          val usersAsStrings = set.value.map(stringify(_))
          usersAsStrings.contains(coltrane) must be(true)
          usersAsStrings.contains(rollins) must be(true)
          usersAsStrings.contains(parker) must be(true)
        }
      }

      enterBarrier("verified-set-on-all-nodes")

      db.shutdown()
      enterBarrier("after-shutdown")
    }
  }
}
