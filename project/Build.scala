//
// Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
//

import java.io.File

import sbt._
import Keys._
import com.typesafe.sbt.SbtMultiJvm
import com.typesafe.sbt.SbtMultiJvm.MultiJvmKeys.{ MultiJvm }
import com.typesafe.sbt.SbtScalariform
import com.typesafe.sbt.SbtScalariform.ScalariformKeys

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
  val sonatypeSnapshots = "Sonatype Snapshots Repo"  at "https://oss.sonatype.org/content/groups/public"
  val playJsonSnapshots = "Temporary Play JSON repo" at "https://github.com/mandubian/mandubian-mvn/raw/master/snapshots/"
}

object Versions {
  val AkkaVersion = "2.2.0"
}

object Dependencies {
  import Versions._
  lazy val akkaActor     		 = "com.typesafe.akka" 				 %% "akka-actor"                % AkkaVersion    % "compile"
  lazy val akkaCluster   		 = "com.typesafe.akka"  			 %% "akka-cluster"              % AkkaVersion    % "compile"
  lazy val akkaContrib   		 = "com.typesafe.akka" 			   %% "akka-contrib"              % AkkaVersion    % "compile"
  lazy val playJson      		 = "play"              			   %% "play-json"                 % "2.2-SNAPSHOT" % "compile"
  lazy val levelDbNative 		 = "org.fusesource.leveldbjni" % "leveldbjni-all"             % "1.6.1"        % "compile"
  lazy val levelDbJava   		 = "org.iq80.leveldb"          % "leveldb"                    % "0.5"          % "compile"
  lazy val unfiltered    		 = "net.databinder"            %% "unfiltered-netty-server"   % "0.6.8"        % "compile"
  lazy val dispatch          = "net.databinder.dispatch"   %% "dispatch-core"             % "0.10.0"       % "compile"
  lazy val scalaTest         = "org.scalatest"     				 %% "scalatest"         				% "1.9.1"     	 % "test"
  lazy val akkaMultiNodeTest = "com.typesafe.akka" 				 %% "akka-remote-tests" 				% AkkaVersion    % "test"
}

object CRDTBuild extends Build {
  import BuildSettings._
  import Resolvers._
  import Dependencies._

  lazy val akkaCRDT = Project (
    "akka-crdt",
    file("."),
    settings = buildSettings ++ multiJvmSettings ++ formatSettings ++ Seq (
      resolvers            := Seq (playJsonSnapshots, sonatypeSnapshots),
      libraryDependencies ++= Seq (akkaActor, akkaCluster, akkaContrib, playJson, unfiltered, dispatch, levelDbNative, levelDbJava),
      libraryDependencies ++= Seq (scalaTest, akkaMultiNodeTest)
    )
  ) configs(MultiJvm)

  lazy val multiJvmSettings = SbtMultiJvm.multiJvmSettings ++ Seq(
    compile in MultiJvm <<= (compile in MultiJvm) triggeredBy (compile in Test), // make sure that MultiJvm test are compiled by the default test compilation
    parallelExecution in Test := false,                                          // disable parallel tests
    executeTests in Test <<=                                                     // make sure that MultiJvm tests are executed by the default test target
      ((executeTests in Test), (executeTests in MultiJvm)) map {
        case ((_, testResults), (_, multiJvmResults)) =>
          val results = testResults ++ multiJvmResults
          (Tests.overall(results.values), results)
    }
  )

  lazy val formatSettings = SbtScalariform.scalariformSettings ++ Seq(
    ScalariformKeys.preferences in Compile := formattingPreferences,
    ScalariformKeys.preferences in Test    := formattingPreferences
  )

  def formattingPreferences = {
    import scalariform.formatter.preferences._
    FormattingPreferences()
	    .setPreference(RewriteArrowSymbols, true)
	    .setPreference(AlignParameters, true)
	    .setPreference(AlignSingleLineCaseStatements, true)
  }
}
