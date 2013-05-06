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
  val eligosourceReleasesRepo  = "Eligosource Releases Repo"  at "http://repo.eligotech.com/nexus/content/repositories/eligosource-releases/"
  val eligosourceSnapshotsRepo = "Eligosource Snapshots Repo" at "http://repo.eligotech.com/nexus/content/repositories/eligosource-snapshots/"
  val sonatypeSnapshots        = "Sonatype Snapshots Repo"    at "https://oss.sonatype.org/content/groups/public"
  val playJsonSnapshots        = "Temporary Play JSON repo"   at "https://github.com/mandubian/mandubian-mvn/raw/master/snapshots/"
}

object Versions {
  val AkkaVersion         = "2.2-M3"
  val EventSourcedVersion = "0.5-M2"
}

object Dependencies {
  import Versions._
  lazy val akkaActor   = "com.typesafe.akka" 			 %% "akka-actor"                % AkkaVersion    % "compile"
  lazy val akkaCluster = "com.typesafe.akka"       %% "akka-cluster-experimental" % AkkaVersion    % "compile"
  lazy val akkaContrib = "com.typesafe.akka" 			 %% "akka-contrib"              % AkkaVersion    % "compile"
  lazy val playJson    = "play"              			 %% "play-json"                 % "2.2-SNAPSHOT" % "compile"
  lazy val unfiltered  = "net.databinder"          %% "unfiltered-netty-server"   % "0.6.8"        % "compile"
  lazy val dispatch    = "net.databinder.dispatch" %% "dispatch-core"             % "0.10.0"       % "compile"
  lazy val bytecask    = "com.github.bytecask"     %% "bytecask"                  % "1.0-SNAPSHOT" % "compile"

  // lazy val eventSourced      = "org.eligosource"   %% "eventsourced-core"          % EventSourcedVersion % "compile"
  // lazy val eventSourcedInMem = "org.eligosource"   %% "eventsourced-journal-inmem" % EventSourcedVersion % "compile"

  lazy val scalaTest         = "org.scalatest"     %% "scalatest"         % "1.9.1"     % "test"
  lazy val akkaMultiNodeTest = "com.typesafe.akka" %% "akka-remote-tests" % AkkaVersion % "test"
}

object ExampleBuild extends Build {
  import BuildSettings._
  import Resolvers._
  import Dependencies._

  lazy val akkaCRDT = Project (
    "akka-crdt",
    file("."),
    settings = buildSettings ++ multiJvmSettings ++ Seq (
      resolvers            := Seq (playJsonSnapshots, sonatypeSnapshots),
      libraryDependencies ++= Seq (akkaActor, akkaCluster, akkaContrib, playJson, unfiltered, dispatch, bytecask),
      libraryDependencies ++= Seq (scalaTest, akkaMultiNodeTest)
    )
  ) configs(MultiJvm)

  lazy val multiJvmSettings = SbtMultiJvm.multiJvmSettings ++ Seq(
    compile in MultiJvm <<= (compile in MultiJvm) triggeredBy (compile in Test), // make sure that MultiJvm test are compiled by the default test compilation
    parallelExecution in Test := false,                                          // disable parallel tests
    executeTests in Test <<=                                                     // make sure that MultiJvm tests are executed by the default test target
      ((executeTests in Test), (executeTests in MultiJvm)) map {
        case ((_, testResults), (_, multiJvmResults))  =>
          val results = testResults ++ multiJvmResults
          (Tests.overall(results.values), results)
    }
  )
}
