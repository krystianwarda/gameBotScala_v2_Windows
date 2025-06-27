lazy val scalaV        = "2.12.17"
lazy val flinkVersion  = "1.15.4"
lazy val icebergVersion = "1.4.3"

ThisBuild / scalaVersion := scalaV
ThisBuild / version      := "0.1"

libraryDependencies ++= Seq(
  // — your existing Flink, Kafka, GCS connectors —
  "org.apache.flink" %% "flink-scala"                   % flinkVersion,
  "org.apache.flink" %% "flink-streaming-scala"         % flinkVersion,
  "org.apache.flink" %  "flink-connector-kafka"         % flinkVersion,
  "org.apache.flink" %  "flink-connector-files"         % flinkVersion,
  "org.apache.flink" %  "flink-json"                    % flinkVersion,

  "org.apache.flink" %  "flink-table-api-java-bridge" % flinkVersion % "provided",
  "org.apache.flink" %  "flink-table-planner-loader"   % flinkVersion % "provided",

  "org.apache.kafka"  %  "kafka-clients"                % "3.3.2",
  "com.google.cloud.bigdataoss" % "gcs-connector"       % "hadoop3-2.2.5",

  // — Iceberg Flink runtime for Flink 1.15 —
  "org.apache.iceberg" % "iceberg-flink-1.15" % icebergVersion,
  // for Parquet file format support (optional: or ORC, AVRO, etc.)
  "org.apache.iceberg" % "iceberg-parquet"   % icebergVersion
)

// Assembly settings
assembly / mainClass           := Some("KafkaToIceberg")
assembly / assemblyJarName     := "KafkaToIceberg.jar"
assembly / assemblyMergeStrategy := {
  case PathList("META-INF", "services", xs @ _*) => MergeStrategy.concat
  case PathList("reference.conf")                => MergeStrategy.concat
  case PathList("META-INF", xs @ _*)             => MergeStrategy.discard
  case x if x.endsWith("module-info.class")      => MergeStrategy.discard
  case _                                         => MergeStrategy.first
}
