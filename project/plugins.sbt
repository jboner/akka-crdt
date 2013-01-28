resolvers += "sbt-idea-repo" at "http://mpeltonen.github.com/maven/"

addSbtPlugin("com.github.mpeltonen" % "sbt-idea" % "1.1.0")

addSbtPlugin("com.typesafe.sbt" % "sbt-osgi" % "0.4.0")

addSbtPlugin("com.mojolly.scalate" % "xsbt-scalate-generator" % "0.2.2")

addSbtPlugin("org.ensime" % "ensime-sbt-cmd" % "0.1.0")
