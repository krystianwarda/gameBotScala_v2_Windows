import sbtassembly.AssemblyPlugin.autoImport._
import sbtassembly.PathList

ThisBuild / organization := "ch.epfl.scala"
ThisBuild / scalaVersion := "2.13.12"
ThisBuild / version      := "1.0.0"

lazy val root = (project in file("."))
  .settings(
    name := "game-bot",
    Compile / mainClass := Some("main.scala.MainApp"),
    libraryDependencies ++= Seq(
      "com.typesafe.play" %% "play-json" % "2.10.3",
      "com.typesafe.akka" %% "akka-actor" % "2.6.20",
      "com.typesafe.akka" %% "akka-stream" % "2.6.20",
      "com.typesafe.akka" %% "akka-http" % "10.2.10",
      "org.scala-lang.modules" %% "scala-swing" % "3.0.0",
      "org.typelevel" %% "cats-effect" % "3.5.4",
      "co.fs2" %% "fs2-core" % "3.9.2",
      "co.fs2" %% "fs2-io" % "3.9.2",
      "com.github.kwhat" % "jnativehook" % "2.2.2",
      "net.java.dev.jna" % "jna" % "5.8.0",
      "net.java.dev.jna" % "jna-platform" % "5.8.0",
      "com.sun.mail" % "javax.mail" % "1.6.2",
      "com.softwaremill.sttp.client3" %% "core" % "3.9.7",
      "com.softwaremill.sttp.client3" %% "circe" % "3.9.7",
      "com.softwaremill.sttp.client3" %% "async-http-client-backend-future" % "3.9.7",
      "org.asynchttpclient" % "async-http-client" % "2.12.3",
      "net.sf.sociaal" % "freetts" % "1.2.2",
      "org.apache.kafka" % "kafka-clients" % "3.5.1",
      "org.slf4j" % "slf4j-simple" % "2.0.13",
      "org.scalatest" %% "scalatest" % "3.2.9" % Test
    ),
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", xs @ _*) => MergeStrategy.discard
      case _                             => MergeStrategy.first
    }
  )


// build.sbt (Variant A)
//import NativeImagePlugin.autoImport.*
//
//import scala.collection.immutable.Seq
//
//ThisBuild / organization := "ch.epfl.scala"
//ThisBuild / scalaVersion := "2.13.12"
//ThisBuild / version      := "1.0.0"
//
//lazy val root = (project in file("."))
//  .enablePlugins(NativeImagePlugin)
//  .settings(
//    name := "game-bot",
//    Compile / mainClass := Some("main.scala.MainApp"),
//    libraryDependencies ++= Seq(
//      "com.typesafe.play" %% "play-json" % "2.10.3",
//      "com.typesafe.akka" %% "akka-actor" % "2.6.20",
//      "com.typesafe.akka" %% "akka-stream" % "2.6.20",
//      "com.typesafe.akka" %% "akka-http" % "10.2.10",
//      "org.scala-lang.modules" %% "scala-swing" % "3.0.0",
//      "org.typelevel" %% "cats-effect" % "3.5.4",
//      "co.fs2" %% "fs2-core" % "3.9.2",
//      "co.fs2" %% "fs2-io" % "3.9.2",
//      "com.github.kwhat" % "jnativehook" % "2.2.2",
//      "net.java.dev.jna" % "jna" % "5.8.0",
//      "net.java.dev.jna" % "jna-platform" % "5.8.0",
//      "com.sun.mail" % "javax.mail" % "1.6.2",
//      "com.softwaremill.sttp.client3" %% "core" % "3.9.7",
//      "com.softwaremill.sttp.client3" %% "circe" % "3.9.7",
//      "com.softwaremill.sttp.client3" %% "async-http-client-backend-future" % "3.9.7",
//      "org.asynchttpclient" % "async-http-client" % "2.12.3",
//      "net.sf.sociaal" % "freetts" % "1.2.2",
//      "org.apache.kafka" % "kafka-clients" % "3.5.1",
//      "org.slf4j" % "slf4j-simple" % "2.0.13",
//      "org.scalatest" %% "scalatest" % "3.2.9" % Test
//    ),
//    Compile / resourceDirectories += baseDirectory.value / "graal-config",
//
//
//
////    nativeImageJvm := "graalvm-java21",
////    nativeImageVersion := "24.0.2",
//
//    nativeImageOptions ++= Seq(
//      "--no-fallback",
//      "-H:+ReportExceptionStackTraces",
//      "--enable-http",
//      "--enable-https",
//      "--install-exit-handlers",
////      "--enable-awt",
////      "--enable-swing",
//      "-Djava.awt.headless=false",
//      "--initialize-at-run-time=java.awt,java.awt.event,java.awt.AWTEvent,java.awt.Toolkit,java.awt.Robot,java.awt.Insets",
//      "--initialize-at-build-time=org.slf4j,org.slf4j.LoggerFactory,org.slf4j.impl.StaticLoggerBinder"
//    ),
////    nativeImageOptions ++= {
////      if (sys.props.get("native.mode").contains("dev")) Seq("--report-unsupported-elements-at-runtime")
////      else Seq("-O3")
////    }
//
//  )