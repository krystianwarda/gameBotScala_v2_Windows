import sbt._
import Keys._
import sbtassembly.AssemblyPlugin.autoImport._
import sbtassembly.{MergeStrategy, PathList}

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
  // Flink core & table (provided by the cluster)
  "org.apache.flink" %% "flink-scala"                  % flinkVersion % "provided",
  "org.apache.flink" %% "flink-streaming-scala"        % flinkVersion % "provided",
  "org.apache.flink" %% "flink-table-api-scala-bridge" % flinkVersion % "provided",
  "org.apache.flink" %  "flink-table-planner-loader"    % flinkVersion % "provided",

  // Flink Kafka connector & client
  "org.apache.flink" % "flink-connector-kafka" % flinkVersion,
  "org.apache.kafka" % "kafka-clients"         % kafkaVersion,

  // ✅ Iceberg for Flink (NO hive)
  "org.apache.iceberg" % "iceberg-flink-runtime-1.17" % icebergVersion exclude("org.apache.iceberg", "iceberg-hive-metastore"),
  "org.apache.iceberg" % "iceberg-core"              % icebergVersion,
  "org.apache.iceberg" % "iceberg-api"               % icebergVersion,


  // Hadoop support
  "org.apache.hadoop" % "hadoop-common"      % hadoopVersion,
  "org.apache.hadoop" % "hadoop-auth"        % hadoopVersion,
  "org.apache.hadoop" % "hadoop-hdfs"        % hadoopVersion,
  "org.apache.hadoop" % "hadoop-hdfs-client" % hadoopVersion,

  // GCS filesystem connector
  "com.google.cloud.bigdataoss" % "gcs-connector"        % "hadoop2-2.2.0",
  "com.google.cloud"            % "google-cloud-storage" % "2.1.6",

  // Logging
  "commons-logging" % "commons-logging"     % "1.2",
  "org.codehaus.woodstox" % "woodstox-core-asl" % "4.4.1",
  "org.apache.flink" % "flink-connector-files" % "1.17.2"
)

// Ensure Kafka version is aligned
dependencyOverrides += "org.apache.kafka" % "kafka-clients" % kafkaVersion

// Assembly settings
assembly / mainClass := Some("KafkaToIceberg")
assembly / assemblyJarName := "KafkaToIceberg.jar"
assembly / assemblyMergeStrategy := {
  case PathList("META-INF", "services", xs @ _*) => MergeStrategy.concat
  case PathList("META-INF", xs @ _*)             => MergeStrategy.discard
  case PathList("reference.conf")                => MergeStrategy.concat
  case x if x.endsWith("module-info.class")      => MergeStrategy.discard
  case _                                         => MergeStrategy.first
}

// JVM target version
Compile / javacOptions ++= Seq("--release", "11")
Compile / scalacOptions ++= Seq("-target:jvm-11")
excludeDependencies ++= Seq(
  ExclusionRule(organization = "org.apache.iceberg", name = "iceberg-hive"),
  ExclusionRule(organization = "org.apache.hadoop.hive")
)