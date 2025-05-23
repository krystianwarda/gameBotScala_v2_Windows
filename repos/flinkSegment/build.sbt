import sbt._
import Keys._
import sbtassembly.AssemblyPlugin.autoImport._
import sbtassembly.{MergeStrategy, PathList}

// basic build settings
ThisBuild / scalaVersion := "2.12.17"
ThisBuild / version      := "0.1"

lazy val flinkVersion   = "1.17.2"
lazy val icebergVersion = "1.8.1"
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

  // Iceberg on Flink
  "org.apache.iceberg" % "iceberg-flink-runtime-1.18" % icebergVersion,
  "org.apache.iceberg" % "iceberg-core"              % icebergVersion,
  "org.apache.iceberg" % "iceberg-gcp"               % icebergVersion,

  // Hadoop filesystem support for IcebergCatalog
  "org.apache.hadoop" % "hadoop-common"      % hadoopVersion,
  "org.apache.hadoop" % "hadoop-auth"        % hadoopVersion,
  "org.apache.hadoop" % "hadoop-hdfs"        % hadoopVersion,
  "org.apache.hadoop" % "hadoop-hdfs-client" % hadoopVersion,

  // Google Cloud Storage connector for Hadoop
  "com.google.cloud.bigdataoss" % "gcs-connector"       % "hadoop2-2.2.0",
  "com.google.cloud"            % "google-cloud-storage" % "2.1.6",

  // Logging (if you need it)
  "commons-logging" % "commons-logging" % "1.2",

  "org.codehaus.woodstox" % "woodstox-core-asl" % "4.4.1"
)

// override Kafka client to match your Kafka version
dependencyOverrides += "org.apache.kafka" % "kafka-clients" % kafkaVersion

// Assembly (fat jar) settings
assembly / mainClass := Some("KafkaToIceberg")
assembly / assemblyJarName := "KafkaToIceberg.jar"
assembly / assemblyMergeStrategy := {
  case PathList("META-INF", "services", xs @ _*) => MergeStrategy.concat
  case PathList("META-INF", xs @ _*)             => MergeStrategy.discard
  case PathList("reference.conf")                => MergeStrategy.concat
  case x if x.endsWith("module-info.class")      => MergeStrategy.discard
  case _                                          => MergeStrategy.first
}

Compile / javacOptions ++= Seq("--release", "11")
Compile / scalacOptions ++= Seq("-target:jvm-11")