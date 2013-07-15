/**
 *  Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.crdt

import org.scalatest.{ BeforeAndAfterAll, WordSpec }
import org.scalatest.matchers.MustMatchers

import akka.remote.testkit.{MultiNodeSpec, MultiNodeSpecCallbacks}
import akka.testkit.ImplicitSender
import akka.contrib.pattern.{DistributedPubSubExtension, DistributedPubSubMediator}

import scala.concurrent.duration._

/**
 * Hooks up MultiNodeSpec with ScalaTest
 */
trait STMultiNodeSpec
  extends MultiNodeSpecCallbacks
  with WordSpec
  with MustMatchers
  with BeforeAndAfterAll
  with ImplicitSender { this: MultiNodeSpec ⇒

  override def beforeAll() = multiNodeSpecBeforeAll()
  override def afterAll() = multiNodeSpecAfterAll()
}
