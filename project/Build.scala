//
// Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
//

import java.io.File

import sbt._
import Keys._
import com.typesafe.sbt.SbtMultiJvm
import com.typesafe.sbt.SbtMultiJvm.MultiJvmKeys.{ MultiJvm }

object BuildSettings {
  val buildOrganization = "com.typesafe.akka"
  val buildVersion      = "0.1-SNAPSHOT"
  val buildScalaVersion = "2.10.1"

  val buildSettings = Defaults.defaultSettings ++ Seq (
    organization := buildOrganization,
    version      := buildVersion,
    scalaVersion := buildScalaVersion
  )
}

object Resolvers {
  val typesafeReleasesRepo     = "Typesafe Releases Repo"     at "http://repo.typesafe.com/typesafe/releases/"
  val typesafeSnapshotsRepo    = "Typesafe Snaphots Repo"     at "http://repo.typesafe.com/typesafe/snapshots/"
  val eligosourceReleasesRepo  = "Eligosource Releases Repo"  at "http://repo.eligotech.com/nexus/content/repositories/eligosource-releases/"
  val eligosourceSnapshotsRepo = "Eligosource Snapshots Repo" at "http://repo.eligotech.com/nexus/content/repositories/eligosource-snapshots/"
  val temporary                = "Temporary Play JSON repo"   at "https://github.com/mandubian/mandubian-mvn/raw/master/snapshots/"
}

object Versions {
  val AkkaVersion         = "2.1.2"
  val EventSourcedVersion = "0.5-M2"
}

object Dependencies {
  import Versions._
  lazy val akkaActor         = "com.typesafe.akka" %% "akka-actor"                 % AkkaVersion         % "compile"
  lazy val akkaCluster       = "com.typesafe.akka" %% "akka-cluster-experimental"  % AkkaVersion         % "compile"
  lazy val playJson          = "play"              %% "play-json"                  % "2.2-SNAPSHOT"      % "compile"
  lazy val eventSourced      = "org.eligosource"   %% "eventsourced-core"          % EventSourcedVersion % "compile"
  lazy val eventSourcedInMem = "org.eligosource"   %% "eventsourced-journal-inmem" % EventSourcedVersion % "compile"
  //lazy val eventSourcedJournalIO = "org.eligosource" %% "eventsourced-journal-journalio" % EventSourcedVersion % "compile"
  //lazy val eventSourcedLevelDB   = "org.eligosource" %% "eventsourced-journal-leveldb"   % EventSourcedVersion % "compile"

  lazy val scalaTest         = "org.scalatest"     %% "scalatest"                      % "1.9"               % "test"
  lazy val akkaMultiNodeTest = "com.typesafe.akka" %% "akka-remote-tests-experimental" % AkkaVersion         % "test"
}

object ExampleBuild extends Build {
  import BuildSettings._
  import Resolvers._
  import Dependencies._

  lazy val akkaCRDT = Project (
    "akka-crdt",
    file("."),
    settings = buildSettings ++ multiJvmSettings ++ Seq (
      resolvers            := Seq (typesafeReleasesRepo, typesafeSnapshotsRepo, eligosourceReleasesRepo, eligosourceSnapshotsRepo, temporary),
      libraryDependencies ++= Seq (akkaActor, akkaCluster, playJson, eventSourced, eventSourcedInMem),
      libraryDependencies ++= Seq (scalaTest, akkaMultiNodeTest)
    )
  ) configs(MultiJvm)

  lazy val multiJvmSettings = SbtMultiJvm.multiJvmSettings ++ Seq(
    // make sure that MultiJvm test are compiled by the default test compilation
    compile in MultiJvm <<= (compile in MultiJvm) triggeredBy (compile in Test),
    // disable parallel tests
    parallelExecution in Test := false,
    // make sure that MultiJvm tests are executed by the default test target
    executeTests in Test <<=
      ((executeTests in Test), (executeTests in MultiJvm)) map {
        case ((_, testResults), (_, multiJvmResults))  =>
          val results = testResults ++ multiJvmResults
          (Tests.overall(results.values), results)
    }
  )

}

