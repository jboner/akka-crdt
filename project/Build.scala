//
// Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
//

import java.io.File

import sbt._
import Keys._

object BuildSettings {
  val buildOrganization = "com.typesafe.akka"
  val buildVersion      = "0.1-SNAPSHOT"
  val buildScalaVersion = "2.10.0"

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
  val AkkaVersion         = "2.1.1"
  val EventSourcedVersion = "0.5-SNAPSHOT"
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
  lazy val scalaTest         = "org.scalatest"     %% "scalatest"                  % "1.9"               % "test"
}

object ExampleBuild extends Build {
  import BuildSettings._
  import Resolvers._
  import Dependencies._

  lazy val akkaCRDT = Project (
    "akka-crdt",
    file("."),
    settings = buildSettings ++ Seq (
      resolvers            := Seq (typesafeReleasesRepo, typesafeSnapshotsRepo, eligosourceReleasesRepo, eligosourceSnapshotsRepo, temporary),
      libraryDependencies ++= Seq (akkaActor, akkaCluster, playJson, eventSourced, eventSourcedInMem),
      libraryDependencies ++= Seq (scalaTest)
    )
  )
}

