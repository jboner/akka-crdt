/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.crdt.convergent

import com.typesafe.config.Config

class ConvergentReplicatedDataTypeSettings(val config: Config, val name: String) {
  import config._

  // rest-server
  final val RestServerRun: Boolean = getBoolean("akka.crdt.rest-server.run")
  final val RestServerHostname: String = getString("akka.crdt.rest-server.hostname")
  final val RestServerPort: Int = getInt("akka.crdt.rest-server.port")

  final val StorageClass = {
    val classname = getString("akka.crdt.convergent.storage-class")
    if (classname.trim().isEmpty) "akka.crdt.convergent.LevelDbStorage"
    else classname
  }

  // leveldb
  final val LevelDbStoragePath: String = getString("akka.crdt.convergent.leveldb.storage-path")
  final val LevelDbUseFsync: Boolean = getBoolean("akka.crdt.convergent.leveldb.use-fsync")
  final val LevelDbVerifyChecksums: Boolean = getBoolean("akka.crdt.convergent.leveldb.verify-checksums")
  final val LevelDbUseNative: Boolean = getBoolean("akka.crdt.convergent.leveldb.use-native")
  final val LevelDbCacheSize: Int = {
    val size = getInt("akka.crdt.convergent.leveldb.cache-size")
    require(size >= 0, "akka.crdt.convergent.leveldb.cache-size can't be negative")
    size
  }
}
