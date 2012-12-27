name := "toy-redis-client"

version := "1.0"

scalaVersion := "2.10.0"

scalacOptions ++= Seq("-unchecked", "-deprecation", "-encoding", "utf8")

// Typesafe.
resolvers += "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/"

// Spray.
resolvers += "spray repo" at "http://repo.spray.io"

libraryDependencies += "com.typesafe.akka" %% "akka-actor" % "2.1.0"

libraryDependencies += "io.spray" % "spray-io" % "1.1-M7"