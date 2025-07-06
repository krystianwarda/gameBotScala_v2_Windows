package com.example

import org.apache.flink.streaming.api.scala.StreamExecutionEnvironment
import org.apache.flink.table.api.EnvironmentSettings
import org.apache.flink.table.api.bridge.scala.StreamTableEnvironment
import org.slf4j.LoggerFactory

object KafkaToIceberg {
  private val logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    try {
      logger.info("Starting Kafka to Iceberg Flink Table API Job")

      // --- Step 1: Initialize and validate configuration ---
      ConfigLoader.initialize()
      ConfigLoader.validate()
      logger.info("Configuration loaded and validated successfully")

      // --- Step 2: Create Table Environment ---
      logger.info("Creating Table Environment")
      val settings = EnvironmentSettings.newInstance().inStreamingMode().build()
      val tableEnv = StreamTableEnvironment.create(StreamExecutionEnvironment.getExecutionEnvironment, settings)

      // Configure Checkpointing for fault tolerance
      val checkpointInterval = ConfigLoader.Flink.Checkpointing.interval
      tableEnv.getConfig.getConfiguration.setLong("execution.checkpointing.interval", checkpointInterval)
      logger.info(s"Checkpointing configured: interval=${checkpointInterval}ms")

      // --- Step 3: Define Kafka Source Table with JSON Schema ---
      logger.info("Defining Kafka source table with JSON schema")
      // Define the schema for game bot events JSON messages
      val createKafkaSourceDDL = s"""
        CREATE TEMPORARY TABLE kafka_source (
           `screenInfo` STRING,
          `battleInfo` STRING,
          `textTabsInfo` STRING,
          `EqInfo` STRING,
          `containersInfo` STRING,
          `attackInfo` STRING,
          `areaInfo` STRING,
          `spyLevelInfo` STRING,
          `lastAttackedCreatureInfo` STRING,
          `lastKilledCreatures` STRING,
          `characterInfo` STRING,
          `focusedTabInfo` STRING,
          `proctime` AS PROCTIME() -- Add processing time column
        )  WITH (
          'connector' = 'kafka',
          'topic' = '${ConfigLoader.Kafka.topic}',
          'properties.bootstrap.servers' = '${ConfigLoader.Kafka.bootstrapServers}',
          'properties.group.id' = '${ConfigLoader.Kafka.groupId}',
          'scan.startup.mode' = 'latest-offset',
          'value.format' = 'json',
          'properties.security.protocol' = '${ConfigLoader.Kafka.securityProtocol}',
          'properties.sasl.mechanism' = '${ConfigLoader.Kafka.saslMechanism}',
          'properties.sasl.jaas.config' = '${ConfigLoader.Kafka.getJaasConfig}'
        )
      """
      logger.info(s"Kafka source DDL: topic=${ConfigLoader.Kafka.topic}, bootstrap-servers=${ConfigLoader.Kafka.bootstrapServers}")

      // --- Step 4: Register Iceberg Catalog ---
      logger.info("Registering Iceberg catalog")
      // We use HadoopCatalog, which manages metadata directly on GCS file system without additional services
      val createCatalogDDL = s"""
        CREATE CATALOG ${ConfigLoader.Iceberg.catalogName}
        WITH (
          'type' = 'iceberg',
          'catalog-type'='${ConfigLoader.Iceberg.catalogType}',
          'warehouse' = '${ConfigLoader.Gcs.icebergWarehousePath}'
        )
      """
      logger.info(s"Iceberg catalog DDL: name=${ConfigLoader.Iceberg.catalogName}, warehouse=${ConfigLoader.Gcs.icebergWarehousePath}")

      // --- Step 5: Set Current Catalog and Database ---
      logger.info("Setting current catalog and database")
      tableEnv.executeSql(createKafkaSourceDDL)
      tableEnv.executeSql(createCatalogDDL)
      tableEnv.useCatalog(ConfigLoader.Iceberg.catalogName)
      tableEnv.executeSql(s"CREATE DATABASE IF NOT EXISTS ${ConfigLoader.Iceberg.database}")
      tableEnv.useDatabase(ConfigLoader.Iceberg.database)
      logger.info(s"Using catalog: ${ConfigLoader.Iceberg.catalogName}, database: ${ConfigLoader.Iceberg.database}")

      // --- Step 6: Create Target Iceberg Table (if not exists) ---
      logger.info("Creating Iceberg table")
      // This table is now managed by Iceberg. Its definition is persistent.
      // We assume Kafka messages are in JSON format and define corresponding fields.
      val createIcebergTableDDL = s"""
        CREATE TABLE IF NOT EXISTS ${ConfigLoader.Iceberg.table} (
          `screenInfo` STRING,
          `battleInfo` STRING,
          `textTabsInfo` STRING,
          `EqInfo` STRING,
          `containersInfo` STRING,
          `attackInfo` STRING,
          `areaInfo` STRING,
          `spyLevelInfo` STRING,
          `lastAttackedCreatureInfo` STRING,
          `lastKilledCreatures` STRING,
          `characterInfo` STRING,
          `focusedTabInfo` STRING,
          `processing_time` TIMESTAMP(3),
          `pt_date` DATE -- Explicitly add a physical partition column
        ) PARTITIONED BY (pt_date) -- Partition directly by physical partition column
        WITH (
          'format-version'='2'
        )
      """
      tableEnv.executeSql(createIcebergTableDDL)
      logger.info(s"Iceberg table created: ${ConfigLoader.Iceberg.table}")

      // --- Step 7: Execute INSERT INTO Statement to Start Data ETL ---
      logger.info("Executing INSERT INTO statement to start data ETL")
      val insertSql = s"""
        INSERT INTO ${ConfigLoader.Iceberg.table}
        SELECT
          screenInfo,
          battleInfo,
          textTabsInfo,
          EqInfo,
          containersInfo,
          attackInfo,
          areaInfo,
          spyLevelInfo,
          lastAttackedCreatureInfo,
          lastKilledCreatures,
          characterInfo,
          focusedTabInfo,
          proctime,
          CAST(proctime AS DATE) -- Convert processing time to date for partitioning
        FROM `default_catalog`.`default_database`.kafka_source
      """
      val tableResult = tableEnv.executeSql(insertSql)

      // Optional: Uncomment to test the source table
      // val tableResult = tableEnv.executeSql("select * FROM `default_catalog`.`default_database`.kafka_source")
      // tableResult.print()

      // Wait for job completion
      logger.info("Waiting for job completion")
      tableResult.await()

      logger.info(s"Job submitted successfully. Writing from Kafka topic '${ConfigLoader.Kafka.topic}' to Iceberg table '${ConfigLoader.Iceberg.catalogName}.${ConfigLoader.Iceberg.database}.${ConfigLoader.Iceberg.table}'.")

    } catch {
      case e: Exception =>
        logger.error("Failed to start Kafka to Iceberg job", e)
        throw e
    }
  }


}