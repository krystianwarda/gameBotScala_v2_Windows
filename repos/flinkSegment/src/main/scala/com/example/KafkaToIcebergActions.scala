// scala
package com.example

import org.apache.flink.streaming.api.scala.StreamExecutionEnvironment
import org.apache.flink.table.api.EnvironmentSettings
import org.apache.flink.table.api.bridge.scala.StreamTableEnvironment
import org.slf4j.LoggerFactory

object KafkaToIcebergActions {
  private val logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    try {
      logger.info("Starting Kafka to Iceberg Actions Flink Table API Job")

      ConfigLoader.initialize()
      ConfigLoader.validate()
      logger.info("Configuration loaded and validated successfully")

      val env = StreamExecutionEnvironment.getExecutionEnvironment
      env.setParallelism(1)

      val settings = EnvironmentSettings.newInstance().inStreamingMode().build()
      val tableEnv = StreamTableEnvironment.create(env, settings)

      val checkpointInterval = ConfigLoader.Flink.Checkpointing.interval
      env.enableCheckpointing(checkpointInterval)

      val conf = tableEnv.getConfig.getConfiguration
      conf.setLong("execution.checkpointing.interval", checkpointInterval)
      conf.setString("execution.checkpointing.mode", ConfigLoader.Flink.Checkpointing.mode)
      conf.setString("table.local-time-zone", "UTC")
      logger.info(s"Checkpointing configured: interval=${checkpointInterval}ms, mode=${ConfigLoader.Flink.Checkpointing.mode}")

      val actionsGroupId = s"${ConfigLoader.Kafka.groupId}-actions-${System.currentTimeMillis()}"

      val createKafkaActionSourceDDL =
        s"""
           |CREATE TEMPORARY TABLE IF NOT EXISTS kafka_actions_source (
           |  `device` STRING,
           |  `action` STRING,
           |  `x` STRING,
           |  `y` STRING,
           |  `metaGeneratedTimestamp` STRING,
           |  `metaGeneratedDate` STRING,
           |  `metaGeneratedId` STRING
           |) WITH (
           |  'connector' = 'kafka',
           |  'topic' = '${ConfigLoader.Kafka.actionsTopic}',
           |  'properties.bootstrap.servers' = '${ConfigLoader.Kafka.bootstrapServers}',
           |  'properties.group.id' = '${actionsGroupId}',
           |  'scan.startup.mode' = 'earliest-offset',
           |  'properties.auto.offset.reset' = 'earliest',
           |  'value.format' = 'json',
           |  'value.json.fail-on-missing-field' = 'false',
           |  'value.json.ignore-parse-errors' = 'true',
           |  'properties.security.protocol' = '${ConfigLoader.Kafka.securityProtocol}',
           |  'properties.sasl.mechanism' = '${ConfigLoader.Kafka.saslMechanism}',
           |  'properties.sasl.jaas.config' = '${ConfigLoader.Kafka.getJaasConfig}',
           |  'properties.ssl.endpoint.identification.algorithm' = 'https',
           |  'properties.client.dns.lookup' = 'use_all_dns_ips'
           |)
           |""".stripMargin
      tableEnv.executeSql(createKafkaActionSourceDDL)

      val catalogProps = {
        val props = collection.mutable.ListBuffer[String](
          "'type' = 'iceberg'",
          s"'catalog-type' = '${ConfigLoader.Iceberg.catalogType}'",
          s"'warehouse' = '${ConfigLoader.Iceberg.warehouse}'"
        )
        props.mkString(",\n  ")
      }

      val createCatalogDDL =
        s"""
           |CREATE CATALOG ${ConfigLoader.Iceberg.catalogName} WITH (
           |  ${catalogProps}
           |)
           |""".stripMargin
      tableEnv.executeSql(createCatalogDDL)
      tableEnv.useCatalog(ConfigLoader.Iceberg.catalogName)
      tableEnv.executeSql(s"CREATE DATABASE IF NOT EXISTS ${ConfigLoader.Iceberg.database}")
      tableEnv.useDatabase(ConfigLoader.Iceberg.database)

      // Recreate sink with all STRING columns and no partitioning
//      tableEnv.executeSql(
//        s"DROP TABLE IF EXISTS ${ConfigLoader.Iceberg.catalogName}.${ConfigLoader.Iceberg.database}.${ConfigLoader.Iceberg.actionTable}"
//      )

      // scala
      val createIcebergActionsTableDDL =
        s"""
           |CREATE TABLE IF NOT EXISTS ${ConfigLoader.Iceberg.catalogName}.${ConfigLoader.Iceberg.database}.${ConfigLoader.Iceberg.actionTable} (
           |  `device` STRING,
           |  `action` STRING,
           |  `x` STRING,
           |  `y` STRING,
           |  `metaGeneratedTimestamp` STRING,
           |  `metaGeneratedDate` STRING,
           |  `metaGeneratedId` STRING
           |) WITH (
           |  'format-version' = '2',
           |  'write.target-file-size-bytes' = '67108864'
           |)
           |""".stripMargin


      tableEnv.executeSql(createIcebergActionsTableDDL)

      // No CAST, no functions, plain pass-through
      val insertActionsSql =
        s"""
           |INSERT INTO ${ConfigLoader.Iceberg.catalogName}.${ConfigLoader.Iceberg.database}.${ConfigLoader.Iceberg.actionTable}
           |SELECT device, action, x, y, metaGeneratedTimestamp, metaGeneratedDate, metaGeneratedId
           |FROM `default_catalog`.`default_database`.kafka_actions_source
           |""".stripMargin

      val tableResult = tableEnv.executeSql(insertActionsSql)
      tableResult.await()

      logger.info(s"Actions job submitted: Kafka `${ConfigLoader.Kafka.actionsTopic}` -> Iceberg `${ConfigLoader.Iceberg.catalogName}.${ConfigLoader.Iceberg.database}.${ConfigLoader.Iceberg.actionTable}`")
    } catch {
      case e: Exception =>
        logger.error("Failed to start Kafka to Iceberg Actions job", e)
        throw e
    }
  }
}