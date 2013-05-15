/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.crdt.convergent

import scala.util.Try
import scala.reflect.ClassTag
import scala.collection.immutable
import play.api.libs.json.Json.parse
import akka.event.LoggingAdapter
import org.iq80.leveldb.{ Logger, ReadOptions, WriteOptions, Options, CompressionType, WriteBatch, DB, DBFactory }
import org.iq80.leveldb.impl.Iq80DBFactory
import org.fusesource.leveldbjni.JniDBFactory
import org.fusesource.leveldbjni.JniDBFactory.{ asString, bytes }
import java.io.File

class LevelDbStorage(
  val nodename: String,
  val settings: ConvergentReplicatedDataTypeSettings,
  log: LoggingAdapter) extends Storage { storage ⇒

  final val path = settings.LevelDbStoragePath
  final val filenamePrefix = s"$path/$nodename"

  final val useFsync: Boolean = settings.LevelDbUseFsync
  final val verifyChecksums: Boolean = settings.LevelDbVerifyChecksums
  final val useNative: Boolean = settings.LevelDbUseNative
  final val cacheSize: Int = settings.LevelDbCacheSize

  private val levelDbReadOptions: ReadOptions = new ReadOptions().verifyChecksums(verifyChecksums)
  private val levelDbWriteOptions: WriteOptions = new WriteOptions().sync(useFsync)

  private val factory: DBFactory = if (useNative) JniDBFactory.factory else Iq80DBFactory.factory

  private val leveldbOptions: Options = {
    val options = new Options()
      .createIfMissing(true)
      .cacheSize(cacheSize)
      .logger(new Logger() {
        def log(message: String) = storage.log.debug(message)
      })
    if (useNative) options.compressionType(CompressionType.SNAPPY)
    else options.compressionType(CompressionType.NONE)
  }

  private def createDb(filename: String): DB = factory.open(new File(filename), leveldbOptions)

  private val gCountersFilename = s"${filenamePrefix}_g_counters"
  private val pnCountersFilename = s"${filenamePrefix}_pn_counters"
  private val gSetsFilename = s"${filenamePrefix}_g_sets"
  private val twoPhaseSetsFilename = s"${filenamePrefix}_2p_sets"

  private val gCounters: DB = createDb(gCountersFilename)
  private val pnCounters: DB = createDb(pnCountersFilename)
  private val gSets: DB = createDb(gSetsFilename)
  private val twoPhaseSets: DB = createDb(twoPhaseSetsFilename)

  private val databases: List[(String, DB)] =
    (gCountersFilename, gCounters) ::
      (pnCountersFilename, pnCounters) ::
      (gSetsFilename, gSets) ::
      (twoPhaseSetsFilename, twoPhaseSets) ::
      Nil

  def findById[T <: ConvergentReplicatedDataType: ClassTag](id: String): Try[T] = Try {
    val clazz = implicitly[ClassTag[T]].runtimeClass
    val crdt =
      if (classOf[GCounter].isAssignableFrom(clazz)) parse(asString(gCounters.get(bytes(id)))).as[GCounter]
      else if (classOf[PNCounter].isAssignableFrom(clazz)) parse(asString(pnCounters.get(bytes(id)))).as[PNCounter]
      else if (classOf[GSet].isAssignableFrom(clazz)) parse(asString(gSets.get(bytes(id)))).as[GSet]
      else if (classOf[TwoPhaseSet].isAssignableFrom(clazz)) parse(asString(twoPhaseSets.get(bytes(id)))).as[TwoPhaseSet]
      else throw new ClassCastException(s"Could create new CvRDT with id [$id] and type [$clazz]")
    crdt.asInstanceOf[T]
  }

  def store(counter: GCounter): Unit = gCounters.put(bytes(counter.id), bytes(counter.toString))
  def store(counter: PNCounter): Unit = pnCounters.put(bytes(counter.id), bytes(counter.toString))
  def store(set: GSet): Unit = gSets.put(bytes(set.id), bytes(set.toString))
  def store(set: TwoPhaseSet): Unit = twoPhaseSets.put(bytes(set.id), bytes(set.toString))

  /**
   * Store a batch.
   */
  def store[T <: ConvergentReplicatedDataType: ClassTag](crdts: immutable.Seq[T]): Unit = {
    val clazz = implicitly[ClassTag[T]].runtimeClass

    val db =
      if (classOf[GCounter].isAssignableFrom(clazz)) gCounters
      else if (classOf[PNCounter].isAssignableFrom(clazz)) pnCounters
      else if (classOf[GSet].isAssignableFrom(clazz)) gSets
      else if (classOf[TwoPhaseSet].isAssignableFrom(clazz)) twoPhaseSets
      else throw new ClassCastException(s"Could store CvRDT with type [$clazz]")

    val batch = db.createWriteBatch()
    try {
      db.write(batch, levelDbWriteOptions)
      crdts foreach { crdt ⇒
        batch put (bytes(crdt.id), bytes(crdt.toString))
      }
    } finally batch.close()
  }

  override def close(): Unit = databases foreach { case (_, db) ⇒ db.close() }

  override def destroy(): Unit = databases foreach {
    case (filename, _) ⇒
      factory.destroy(new File(filename), new Options)
  }
}
/*
TODO: Stuff to look into (from LevelDBJNI docs):
 
Getting approximate sizes.
 
    long[] sizes = db.getApproximateSizes(new Range(bytes("a"), bytes("k")), new Range(bytes("k"), bytes("z")));
    System.out.println("Size: "+sizes[0]+", "+sizes[1]);
 
Getting database status.
 
    String stats = db.getProperty("leveldb.stats");
    System.out.println(stats);
 
Repairing a database.

    Options options = new Options();
    factory.repair(new File("example"), options);

Using a memory pool to make native memory allocations more efficient:

    JniDBFactory.pushMemoryPool(1024 * 512);
    try {
        // .. work with the DB in here,
    } finally {
        JniDBFactory.popMemoryPool();
    }
 */
