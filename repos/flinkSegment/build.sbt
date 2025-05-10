import sbtassembly.AssemblyPlugin.autoImport._
import sbtassembly.MergeStrategy
import sbtassembly.PathList

enablePlugins(AssemblyPlugin)

ThisBuild / scalaVersion := "2.12.17"
ThisBuild / version      := "0.1"

lazy val flinkVersion   = "1.17.2"
lazy val icebergVersion = "1.5.0"
lazy val hadoopVersion  = "3.3.1" // Use the version compatible with your environment

resolvers ++= Seq(
  "Apache Releases" at "https://repository.apache.org/content/repositories/releases/",
  Resolver.mavenCentral
)

libraryDependencies ++= Seq(
  // — Flink core & table APIs, provided by the cluster at runtime —
  "org.apache.flink" %% "flink-scala"                   % flinkVersion % "provided",
  "org.apache.flink" %% "flink-streaming-scala"         % flinkVersion % "provided",
  "org.apache.flink" %% "flink-table-api-scala-bridge"  % flinkVersion % "provided",
  // — the Blink planner (only one planner needed) —
  "org.apache.flink" %% "flink-table-planner"           % flinkVersion % "provided",
  // — Kafka connector (you can also mark this provided if you install it on the cluster) —
  "org.apache.flink"  % "flink-connector-kafka"         % flinkVersion % "provided",
  "org.apache.flink"  % "flink-json"                    % flinkVersion % "provided",

  // — Iceberg runtime: we bundle this —
  "org.apache.iceberg" % "iceberg-flink-runtime-1.17"   % icebergVersion,

  // — JSON parser —
  "com.typesafe.play" %% "play-json"                   % "2.9.2",

  // — Hadoop Common (for FileSystem and Configuration) —
  "org.apache.hadoop" % "hadoop-common" % hadoopVersion
)

Compile / mainClass := Some("KafkaToIceberg")
assembly / assemblyJarName := "KafkaToIceberg.jar"

// Merge only service‐loader files; drop everything else under META-INF
assembly / assemblyMergeStrategy := {
  case PathList("META-INF", "services", xs @ _*) => MergeStrategy.concat
  case PathList("META-INF", xs @ _*)             => MergeStrategy.discard
  case PathList("reference.conf")                => MergeStrategy.concat
  case _                                         => MergeStrategy.first
}
