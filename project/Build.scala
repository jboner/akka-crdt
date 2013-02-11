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
  val typesafeRepo             = "Typesafe Repo"              at "http://repo.typesafe.com/typesafe/releases/"
  val eligosourceReleasesRepo  = "Eligosource Releases Repo"  at "http://repo.eligotech.com/nexus/content/repositories/eligosource-releases/"
  val eligosourceSnapshotsRepo = "Eligosource Snapshots Repo" at "http://repo.eligotech.com/nexus/content/repositories/eligosource-snapshots/"
}

object Versions {
  val AkkaVersion         = "2.1.0"
  val EventSourcedVersion = "0.5-SNAPSHOT"
}

object Dependencies {
  import Versions._
  lazy val akkaactor    = "com.typesafe.akka" %% "akka-actor"                % AkkaVersion         % "compile"
  lazy val akkacluster  = "com.typesafe.akka" %% "akka-cluster-experimental" % AkkaVersion         % "compile"
  lazy val eventsourced = "org.eligosource"   %% "eventsourced"              % EventSourcedVersion % "compile"
  lazy val scalatest    = "org.scalatest"     %% "scalatest"                 % "1.9"               % "test"
}

object ExampleBuild extends Build {
  import BuildSettings._
  import Resolvers._
  import Dependencies._

  lazy val akkaCRDT = Project (
    "akka-crdt",
    file("."),
    settings = buildSettings ++ Seq (
      resolvers            := Seq (typesafeRepo, eligosourceReleasesRepo, eligosourceSnapshotsRepo),
      libraryDependencies ++= Seq (akkaactor, akkacluster, eventsourced),
      libraryDependencies ++= Seq (scalatest)
    )
  )
}

