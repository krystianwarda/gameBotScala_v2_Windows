package com.example

import com.typesafe.config.{Config, ConfigFactory}
import org.slf4j.LoggerFactory

/**
 * Configuration loader utility for Flink GCP Scala project
 *
 * This class provides a centralized way to load and access configuration
 * parameters from the application.conf file. It uses Typesafe Config library
 * for configuration management.
 */
object ConfigLoader {
  private val logger = LoggerFactory.getLogger(getClass)

  // Load configuration from application.conf
  private val config: Config = ConfigFactory.load("application.conf")

  // Kafka Configuration
  object Kafka {
    val bootstrapServers: String = config.getString("kafka.bootstrap-servers")
    val topic: String = config.getString("kafka.topic")
    val groupId: String = config.getString("kafka.group-id")
    val apiKey: String = config.getString("kafka.api-key")
    val apiSecret: String = config.getString("kafka.api-secret")
    val securityProtocol: String = config.getString("kafka.security.protocol")
    val saslMechanism: String = config.getString("kafka.security.mechanism")

    // Generate JAAS config string
    def getJaasConfig: String = {
      String.format("org.apache.kafka.common.security.plain.PlainLoginModule required username=\"%s\" password=\"%s\";", apiKey, apiSecret)
    }
  }

  // GCS Configuration
  object Gcs {
    val bucket: String = config.getString("gcs.bucket")
    val datastreamOutputPath: String = config.getString("gcs.paths.datastream-output")
    val tableApiOutputPath: String = config.getString("gcs.paths.table-api-output")
    val icebergWarehousePath: String = config.getString("gcs.paths.iceberg-warehouse")
    val jarBucket: String = config.getString("gcs.paths.jar-bucket")
  }

  // Iceberg Configuration
  object Iceberg {
    val catalogName: String = config.getString("iceberg.catalog.name")
    val catalogType: String = config.getString("iceberg.catalog.type")
    val database: String = config.getString("iceberg.database")
    val table: String = config.getString("iceberg.table")
  }

  // Flink Configuration
  object Flink {
    object Checkpointing {
      val interval: Long = config.getLong("flink.checkpointing.interval")
      val mode: String = config.getString("flink.checkpointing.mode")
      val minPauseBetweenCheckpoints: Long = config.getLong("flink.checkpointing.min-pause-between-checkpoints")
      val timeout: Long = config.getLong("flink.checkpointing.timeout")
      val maxConcurrentCheckpoints: Int = config.getInt("flink.checkpointing.max-concurrent-checkpoints")
    }

    object FileSink {
      val rolloverInterval: String = config.getString("flink.file-sink.rolling-policy.rollover-interval")
      val inactivityInterval: String = config.getString("flink.file-sink.rolling-policy.inactivity-interval")
      val maxPartSize: String = config.getString("flink.file-sink.rolling-policy.max-part-size")
      val partPrefix: String = config.getString("flink.file-sink.output-config.part-prefix")
      val partSuffix: String = config.getString("flink.file-sink.output-config.part-suffix")
    }

    object TableApi {
      val rolloverInterval: String = config.getString("flink.table-api.rolling-policy.rollover-interval")
      val inactivityInterval: String = config.getString("flink.table-api.rolling-policy.inactivity-interval")
      val fileSize: String = config.getString("flink.table-api.rolling-policy.file-size")
    }
  }

  // Logging Configuration
  object Logging {
    val level: String = config.getString("logging.level")
    val pattern: String = config.getString("logging.pattern")
    val fileEnabled: Boolean = config.getBoolean("logging.file.enabled")
    val filePath: String = config.getString("logging.file.path")
    val maxSize: String = config.getString("logging.file.max-size")
    val maxFiles: Int = config.getInt("logging.file.max-files")
  }

  /**
   * Initialize configuration and log startup information
   */
  def initialize(): Unit = {
    logger.info("Initializing Flink GCP Scala Configuration")
    logger.info(s"Kafka Bootstrap Servers: ${Kafka.bootstrapServers}")
    logger.info(s"Kafka Topic: ${Kafka.topic}")
    logger.info(s"GCS Bucket: ${Gcs.bucket}")
    logger.info(s"Iceberg Catalog: ${Iceberg.catalogName}")
    logger.info(s"Flink Checkpointing Interval: ${Flink.Checkpointing.interval}ms")
    logger.info("Configuration loaded successfully")
  }

  /**
   * Validate required configuration parameters
   */
  def validate(): Unit = {
    logger.info("Validating configuration parameters...")

    // Validate Kafka configuration
    require(Kafka.bootstrapServers.nonEmpty, "Kafka bootstrap servers cannot be empty")
    require(Kafka.topic.nonEmpty, "Kafka topic cannot be empty")
    require(Kafka.apiKey.nonEmpty, "Kafka API key cannot be empty")
    require(Kafka.apiSecret.nonEmpty, "Kafka API secret cannot be empty")

    // Validate GCS configuration
    require(Gcs.bucket.nonEmpty, "GCS bucket cannot be empty")
    require(Gcs.datastreamOutputPath.nonEmpty, "GCS datastream output path cannot be empty")
    require(Gcs.tableApiOutputPath.nonEmpty, "GCS table API output path cannot be empty")
    require(Gcs.icebergWarehousePath.nonEmpty, "GCS iceberg warehouse path cannot be empty")

    // Validate Iceberg configuration
    require(Iceberg.catalogName.nonEmpty, "Iceberg catalog name cannot be empty")
    require(Iceberg.database.nonEmpty, "Iceberg database cannot be empty")
    require(Iceberg.table.nonEmpty, "Iceberg table cannot be empty")

    logger.info("Configuration validation completed successfully")
  }
}