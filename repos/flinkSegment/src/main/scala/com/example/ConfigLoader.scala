// scala
package com.example

import org.slf4j.LoggerFactory

object ConfigLoader {
  private val logger = LoggerFactory.getLogger(getClass)

  object Kafka {
    val bootstrapServers: String = "pkc-z1o60.europe-west1.gcp.confluent.cloud:9092"
    val topic: String = "game-bot-events"
    val actionsTopic: String = "game-bot-actions"
    val groupId: String = "flink-gcp-scala-group"

    // Use your real credentials
    val apiKey: String = "MK6M4LV23QAJUFFO"
    val apiSecret: String = "cfltiOHosg9Q+bgInOggnC4GX+kfYvpIC+0XxVXxutBuqNdDfiTg82M4DTXckvhg"

    val securityProtocol: String = "SASL_SSL"
    val saslMechanism: String = "PLAIN"

    def getJaasConfig: String =
      s"""org.apache.kafka.common.security.plain.PlainLoginModule required username="${apiKey}" password="${apiSecret}";"""
  }

  object Gcs {
    val bucket: String = "flink_gcs_bucket"
    val datastreamOutputPath: String = "gs://flink_iceberg_data_bucket/flink-scala-output/"
    val tableApiOutputPath: String = "gs://flink_iceberg_data_bucket/flink-table-api-output/"
    val icebergWarehousePath: String = "gs://flink_iceberg_data_bucket/iceberg_warehouse_8"
    val jarBucket: String = "gs://gamebot-469621-flink-jar-bucket/"
  }

  object Iceberg {
    // Use Hadoop catalog on GCS
    val catalogName: String = "hadoop_catalog"
    val catalogType: String = "hadoop"

    val database: String = "gcs_db"
    val table: String = "game_events"
    val actionTable: String = "gamebot_actions"

    // Not used in Hadoop catalog, kept for completeness
    val restUri: String = ""
    val gcpProject: String = "gamebot-469621"

    // GCS warehouse location
    val warehouse: String = "gs://flink_iceberg_data_bucket/iceberg_warehouse_8"

    // Use Iceberg's native GCS FileIO (no Hadoop GCS connector needed)
//    val ioimpl: String = "org.apache.iceberg.gcp.gcs.GCSFileIO"
  }

  object Flink {
    object Checkpointing {
      val interval: Long = 10000L
      val mode: String = "EXACTLY_ONCE"
      val minPauseBetweenCheckpoints: Long = 500L
      val timeout: Long = 600000L
      val maxConcurrentCheckpoints: Int = 1
    }
    object FileSink {
      val rolloverInterval: String = "30s"
      val inactivityInterval: String = "15s"
      val maxPartSize: String = "512KB"
      val partPrefix: String = "data"
      val partSuffix: String = ".txt"
    }
    object TableApi {
      val rolloverInterval: String = "5min"
      val inactivityInterval: String = "2min"
      val fileSize: String = "1MB"
    }
  }

  object Logging {
    val level: String = "INFO"
    val pattern: String = "%d{yyyy-MM-dd HH:mm:ss} [%thread] %-5level %logger{36} - %msg%n"
    val fileEnabled: Boolean = true
    val filePath: String = "logs/flink-gcp-scala.log"
    val maxSize: String = "100MB"
    val maxFiles: Int = 10
  }

  def initialize(): Unit = {
    logger.info("Initializing hardcoded configuration")
    logger.info(s"Kafka Bootstrap Servers: ${Kafka.bootstrapServers}")
    logger.info(s"Kafka Topic: ${Kafka.topic}")
    logger.info(s"Kafka Actions Topic: ${Kafka.actionsTopic}")
    logger.info(s"GCS Warehouse: ${Iceberg.warehouse}")
    logger.info(s"Iceberg Catalog: ${Iceberg.catalogName} (type=${Iceberg.catalogType})")
    logger.info(s"Flink Checkpointing Interval: ${Flink.Checkpointing.interval}ms")
    logger.info("Configuration initialized")
  }

  def validate(): Unit = {
    logger.info("Validating configuration parameters...")

    // Kafka
    require(Kafka.bootstrapServers.nonEmpty, "Kafka bootstrap servers cannot be empty")
    require(Kafka.topic.nonEmpty, "Kafka topic cannot be empty")
    require(Kafka.actionsTopic.nonEmpty, "Kafka actions topic cannot be empty")
    require(Kafka.apiKey.nonEmpty, "Kafka API key cannot be empty")
    require(Kafka.apiSecret.nonEmpty, "Kafka API secret cannot be empty")

    // Iceberg
    require(Iceberg.catalogName.nonEmpty, "Iceberg catalog name cannot be empty")
    require(Iceberg.catalogType.equalsIgnoreCase("hadoop"), "Iceberg catalog type must be 'hadoop'")
    require(Iceberg.database.nonEmpty, "Iceberg database cannot be empty")
    require(Iceberg.table.nonEmpty, "Iceberg table cannot be empty")
    require(Iceberg.warehouse.nonEmpty, "Iceberg warehouse cannot be empty")

    logger.info("Configuration validation completed successfully")
  }
}