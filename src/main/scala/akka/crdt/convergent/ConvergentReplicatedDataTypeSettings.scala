/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.crdt.convergent

import com.typesafe.config.Config

class ConvergentReplicatedDataTypeSettings(val config: Config, val name: String) {
  import config._

  // final val FeatureX: Double = {
  //   val x = getDouble("akka.crdt.feature-x")
  //   require(x > 0.0, "feature-x must be > 0")
  //   x
  // }
}
