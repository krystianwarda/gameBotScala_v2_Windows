// build.sbt
import sbt._
import Keys._
import sbtassembly.AssemblyPlugin.autoImport._

ThisBuild / scalaVersion := "2.12.17"
ThisBuild / version      := "0.1"
lazy val flinkVersion   = "1.15.4"
lazy val kafkaVersion   = "3.3.2"

libraryDependencies ++= Seq(
  // Flink core (cluster provides runtime)
  "org.apache.flink" %% "flink-scala"                  % flinkVersion,
  "org.apache.flink" %% "flink-streaming-scala"        % flinkVersion,
  "org.apache.flink" %% "flink-table-api-scala-bridge" % flinkVersion,

  // This is the Flink SQL Kafka connector – it's on Maven Central :contentReference[oaicite:1]{index=1}
  "org.apache.flink" % "flink-sql-connector-kafka" % flinkVersion,
  "org.apache.kafka"   % "kafka-clients" % kafkaVersion,

  // JSON support and filesystem connector
  "org.apache.flink" % "flink-connector-files" % flinkVersion,
  "org.apache.flink" % "flink-json"           % flinkVersion,
  "org.apache.flink" % "flink-shaded-jackson" % "2.12.1-13.0",
  "com.google.cloud.bigdataoss" % "gcs-connector" % "hadoop3-2.2.5"



)
dependencyOverrides += "org.apache.flink" % "flink-shaded-jackson" % "2.12.1-13.0"


// Tell sbt-assembly to relocate shaded classes properly into the fat JAR
assembly / assemblyShadeRules := Seq(
  ShadeRule.rename("com.fasterxml.jackson.**" -> "shade.com.fasterxml.jackson.@1").inAll
)

assembly / mainClass := Some("KafkaToIceberg")
assembly / assemblyJarName := "KafkaToIceberg.jar"
assembly / assemblyMergeStrategy := {
  case PathList("META-INF", "services", xs @ _*) => MergeStrategy.concat
  case PathList("reference.conf")                => MergeStrategy.concat
  case PathList("META-INF", xs @ _*)             => MergeStrategy.discard
  case x if x.endsWith("module-info.class")      => MergeStrategy.discard
  case _                                         => MergeStrategy.first
}

// Optional: exclude shady duplicates
assembly / assemblyOption := (assembly / assemblyOption).value.copy(includeScala = false)
