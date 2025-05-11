import sbtassembly.AssemblyPlugin.autoImport._
import sbtassembly.MergeStrategy
import sbtassembly.PathList

enablePlugins(AssemblyPlugin)

ThisBuild / scalaVersion := "2.12.17"
ThisBuild / version      := "0.1"

lazy val flinkVersion   = "1.17.2"
lazy val icebergVersion = "1.5.0"
lazy val hadoopVersion  = "3.3.1"
lazy val kafkaVersion   = "3.3.2"

resolvers ++= Seq(
  "Apache Releases" at "https://repository.apache.org/content/repositories/releases/",
  Resolver.mavenCentral
)

libraryDependencies ++= Seq(
  // Flink core & table APIs (provided by Flink runtime)
  "org.apache.flink" %% "flink-scala"                   % flinkVersion,
  "org.apache.flink" %% "flink-streaming-scala"         % flinkVersion,
  "org.apache.flink" %% "flink-table-api-scala-bridge"  % flinkVersion,
  "org.apache.flink" %  "flink-table-planner-loader" % flinkVersion,

//  "org.apache.flink" % "flink-connector-kafka" % flinkVersion,
//  "org.apache.kafka" % "kafka-clients" % kafkaVersion,
//  "org.apache.flink" % "flink-sql-connector-kafka" % flinkVersion,

  // Iceberg runtime (included)
  "org.apache.iceberg" % "iceberg-flink-runtime-1.17" % icebergVersion,
//  "org.apache.kafka" % "kafka-clients" % kafkaVersion force(),


// JSON parsing
  "com.typesafe.play" %% "play-json" % "2.9.2",

  // Hadoop (included)
  "org.apache.hadoop" % "hadoop-common" % hadoopVersion
)

dependencyOverrides += "org.apache.kafka" % "kafka-clients" % kafkaVersion

Compile / mainClass := Some("KafkaToIceberg")
assembly / assemblyJarName := "KafkaToIceberg.jar"

assembly / assemblyMergeStrategy := {
  case PathList("META-INF", "services", xs @ _*) => MergeStrategy.concat
  case PathList("META-INF", xs @ _*)             => MergeStrategy.discard
  case PathList("reference.conf")                => MergeStrategy.concat
  case x if x.endsWith("module-info.class")      => MergeStrategy.discard
  case _                                         => MergeStrategy.first
}
