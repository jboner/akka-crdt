resolvers += "sbt-idea-repo" at "http://mpeltonen.github.com/maven/"

addSbtPlugin("com.github.mpeltonen" % "sbt-idea" % "1.1.0")

addSbtPlugin("org.ensime" % "ensime-sbt-cmd" % "0.1.0")

addSbtPlugin("com.orrsella" % "sbt-sublime" % "1.0.3")

addSbtPlugin("com.typesafe.sbteclipse" % "sbteclipse-plugin" % "2.2.0")

addSbtPlugin("com.typesafe.sbt" % "sbt-multi-jvm" % "0.3.5")
