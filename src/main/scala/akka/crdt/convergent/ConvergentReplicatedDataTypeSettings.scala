/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.crdt.convergent

import akka.util.Helpers.Requiring
import com.typesafe.config.Config
import scala.concurrent.duration.Duration
import scala.concurrent.duration._
import java.util.concurrent.TimeUnit

class ConvergentReplicatedDataTypeSettings(val config: Config, val name: String) {
  val c = config.getConfig("akka.crdt")
  import c._

  // rest-server
  val RestServerRun: Boolean = getBoolean("rest-server.run")
  val RestServerHostname: String = getString("rest-server.hostname") requiring (_ != "", "akka.crdt.rest-server.hostname can't be empty string")
  val RestServerPort: Int = getInt("rest-server.port")

  val BatchingWindow: FiniteDuration = Duration(getMilliseconds("convergent.batching-window"), TimeUnit.MILLISECONDS)
  val ChangeSetResubmissionInterval: FiniteDuration = Duration(getMilliseconds("convergent.change-set-resubmission-interval"), TimeUnit.MILLISECONDS)

  val StorageClass = {
    val classname = getString("convergent.storage-class").trim()
    if (classname != "") classOf[akka.crdt.convergent.LevelDbStorage].getName
    else classname
  }

  // leveldb
  val LevelDbStoragePath: String = getString("convergent.leveldb.storage-path") requiring (_ != "", "akka.crdt.convergent.leveldb.storage-path can't be empty string")
  val LevelDbDestroyOnShutdown: Boolean = getBoolean("convergent.leveldb.destroy-on-shutdown")
  val LevelDbUseFsync: Boolean = getBoolean("convergent.leveldb.use-fsync")
  val LevelDbVerifyChecksums: Boolean = getBoolean("convergent.leveldb.verify-checksums")
  val LevelDbUseNative: Boolean = getBoolean("convergent.leveldb.use-native")
  val LevelDbCacheSize: Int = getInt("convergent.leveldb.cache-size") requiring (_ >= 0, "akka.crdt.convergent.leveldb.cache-size can't be negative")
}
