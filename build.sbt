organization := "org.http4s.akka"
name := "http4s-akka"
version := "0.1"

scalaVersion := "2.12.2"

libraryDependencies ++= Seq(
  "org.http4s" %% "http4s-blaze-server" % "0.15.12a",
  "io.circe" %% "circe-parser" % "0.8.0",
  "com.typesafe.akka" %% "akka-actor" % "2.5.1",
  "com.typesafe.play" %% "twirl-api" % "1.3.0"
)
