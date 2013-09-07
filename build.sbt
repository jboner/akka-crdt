// ======== imports ========
import Resolvers._
import Dependencies._
import com.typesafe.sbt.SbtMultiJvm
import com.typesafe.sbt.SbtMultiJvm.MultiJvmKeys.MultiJvm
import scalariform.formatter.preferences._

// ======== settings ========
organization := "com.typesafe.akka"

name := "akka-crdt"

version := "1.0-SNAPSHOT"

scalaVersion := "2.10.2"

resolvers += "Sonatype Snapshots Repo"  at "https://oss.sonatype.org/content/groups/public"

resolvers += "Temporary Play JSON repo" at "https://github.com/mandubian/mandubian-mvn/raw/master/snapshots/"

libraryDependencies ++= Dependencies.runtime

libraryDependencies ++= Dependencies.test

scalacOptions ++= Seq(
  "-unchecked",
  "-deprecation",
  "-Xlint",
  "-language:_",
  "-target:jvm-1.6",
  "-encoding", "UTF-8"
)

// ======== scalariform ========
scalariformSettings

ScalariformKeys.preferences := ScalariformKeys.preferences.value
  .setPreference(RewriteArrowSymbols, true)
  .setPreference(AlignParameters, true)
  .setPreference(AlignSingleLineCaseStatements, true)


// ======== multi-jvm plugin ========
lazy val akka_crdt = Project (
  "akka-crdt",
  file("."),
  settings = Defaults.defaultSettings ++ multiJvmSettings,
  configurations = Configurations.default :+ MultiJvm
)

lazy val multiJvmSettings = SbtMultiJvm.multiJvmSettings ++ Seq(
  compile in MultiJvm <<= (compile in MultiJvm) triggeredBy (compile in Test), // make sure that MultiJvm test are compiled by the default test compilation
  parallelExecution in Test := false,                                          // disable parallel tests
  executeTests in Test <<=
    ((executeTests in Test), (executeTests in MultiJvm)) map {
      case ((testResults), (multiJvmResults)) =>
        val overall =
          if (testResults.overall.id < multiJvmResults.overall.id) multiJvmResults.overall
          else testResults.overall
        Tests.Output(overall,
          testResults.events ++ multiJvmResults.events,
          testResults.summaries ++ multiJvmResults.summaries)
   }
)
