import sbt._

object Dependencies {
  val AkkaVersion = "2.2.1"

  val akkaActor         = "com.typesafe.akka"         %% "akka-actor"               % AkkaVersion    % "compile"
  val akkaCluster       = "com.typesafe.akka"         %% "akka-cluster"             % AkkaVersion    % "compile"
  val playJson          = "play"                      %% "play-json"                % "2.2-SNAPSHOT" % "compile"
  val levelDbNative     = "org.fusesource.leveldbjni" % "leveldbjni-all"            % "1.6.1"        % "compile"
  val levelDbJava       = "org.iq80.leveldb"          % "leveldb"                   % "0.5"          % "compile"
  val unfiltered        = "net.databinder"            %% "unfiltered-netty-server"  % "0.6.8"        % "compile"
  val dispatch          = "net.databinder.dispatch"   %% "dispatch-core"            % "0.10.0"       % "compile"
  val scalaTest         = "org.scalatest"             %% "scalatest"                % "1.9.1"        % "test"
  val akkaMultiNodeTest = "com.typesafe.akka"         %% "akka-remote-tests"        % AkkaVersion    % "test"

  val runtime = List(akkaActor, akkaCluster, playJson, unfiltered, dispatch, levelDbNative, levelDbJava)
  val test    = List(scalaTest, akkaMultiNodeTest)
}
