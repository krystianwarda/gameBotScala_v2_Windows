ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "2.12.18"
ThisBuild / organization := "com.example"

val flinkVersion = "1.17.0"
val icebergVersion = "1.5.1"

lazy val root = (project in file("."))
  .settings(
    name := "flink-gcp-scala",

    // ✅ Define the main class
    assembly / mainClass := Some("com.example.KafkaToIceberg"),

    libraryDependencies ++= Seq(
      "org.apache.flink" %% "flink-scala" % flinkVersion % "provided",
      "org.apache.flink" %% "flink-streaming-scala" % flinkVersion % "provided",
      "org.apache.flink" % "flink-clients" % flinkVersion % "provided",

      "org.apache.flink" %% "flink-table-api-scala-bridge" % flinkVersion % "compile",
      "org.apache.flink" % "flink-sql-connector-kafka" % flinkVersion % "compile",
      "org.apache.flink" % "flink-table-planner-loader" % flinkVersion % "runtime",

      "org.apache.iceberg" % "iceberg-flink-runtime-1.17" % icebergVersion % "compile",
      "org.apache.flink" % "flink-connector-files" % "1.15.4" % "provided",

      "com.typesafe" % "config" % "1.4.2" % "compile",

      "org.apache.logging.log4j" % "log4j-api" % "2.20.0" % "runtime",
      "org.apache.logging.log4j" % "log4j-core" % "2.20.0" % "runtime",
      "org.apache.logging.log4j" % "log4j-slf4j-impl" % "2.20.0" % "runtime"
    ),

    assembly / assemblyJarName := s"${name.value}-assembly-${version.value}.jar",

    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "services", _*) => MergeStrategy.concat
      case PathList("META-INF", ps @ _*) if ps.lastOption.exists(_.endsWith(".SF")) => MergeStrategy.discard
      case PathList("META-INF", ps @ _*) if ps.lastOption.exists(_.endsWith(".DSA")) => MergeStrategy.discard
      case PathList("META-INF", ps @ _*) if ps.lastOption.exists(_.endsWith(".RSA")) => MergeStrategy.discard
      case "reference.conf" | "application.conf" => MergeStrategy.concat
      case _ => MergeStrategy.first
    }
  )
