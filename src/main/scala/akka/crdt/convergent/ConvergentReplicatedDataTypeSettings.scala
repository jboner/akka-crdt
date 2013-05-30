/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.crdt.convergent

import com.typesafe.config.Config
import scala.concurrent.duration.Duration
import scala.concurrent.duration._
import java.util.concurrent.TimeUnit

class ConvergentReplicatedDataTypeSettings(val config: Config, val name: String) {
  import config._

  // rest-server
  val RestServerRun: Boolean = getBoolean("akka.crdt.rest-server.run")
  val RestServerHostname: String = getString("akka.crdt.rest-server.hostname")
  val RestServerPort: Int = getInt("akka.crdt.rest-server.port")

  val BatchingWindow: FiniteDuration = Duration(getMilliseconds("akka.crdt.convergent.batching-window"), TimeUnit.MILLISECONDS)

  val StorageClass = {
    val classname = getString("akka.crdt.convergent.storage-class")
    if (classname.trim().isEmpty) "akka.crdt.convergent.LevelDbStorage"
    else classname
  }

  // leveldb
  val LevelDbStoragePath: String = getString("akka.crdt.convergent.leveldb.storage-path")
  val LevelDbUseFsync: Boolean = getBoolean("akka.crdt.convergent.leveldb.use-fsync")
  val LevelDbVerifyChecksums: Boolean = getBoolean("akka.crdt.convergent.leveldb.verify-checksums")
  val LevelDbUseNative: Boolean = getBoolean("akka.crdt.convergent.leveldb.use-native")
  val LevelDbCacheSize: Int = {
    val size = getInt("akka.crdt.convergent.leveldb.cache-size")
    require(size >= 0, "akka.crdt.convergent.leveldb.cache-size can't be negative")
    size
  }
}
