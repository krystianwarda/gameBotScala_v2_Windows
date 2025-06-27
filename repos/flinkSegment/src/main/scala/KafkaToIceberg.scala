import org.apache.flink.configuration.Configuration
import org.apache.flink.streaming.api.CheckpointingMode
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment

object KafkaToIceberg {

  def main(args: Array[String]): Unit = {

    // Replace XXXX with your configuration
    val kafkaApiKey = "IDF6HNFBXSDMN4GT"
    val kafkaApiSecret = "LUfcrMNAzN7lBtslr+TfsP1QXA+x9E33zKPVZMqzR8kyvx1rXUjasty8RYnnWdxD"
    val bootstrapServers = "pkc-619z3.us-east1.gcp.confluent.cloud:9092"
    val jaasConfig = s"""org.apache.kafka.common.security.plain.PlainLoginModule required username="$kafkaApiKey" password="$kafkaApiSecret";"""
    val gcsBucketName = "gamebot-460320-iceberg"

    val flinkConfig = new Configuration()
    flinkConfig.setBoolean("fs.gs.impl.disable.cache", true)
    flinkConfig.setBoolean("fs.gs.implicit.dir.repair.enable", true)

    val env = StreamExecutionEnvironment.getExecutionEnvironment(flinkConfig)
    env.enableCheckpointing(60000)

    // checkpoint conf
    val checkpointConfig = env.getCheckpointConfig
    checkpointConfig.setCheckpointingMode(CheckpointingMode.EXACTLY_ONCE)
    checkpointConfig.setCheckpointTimeout(60000)
    checkpointConfig.setMinPauseBetweenCheckpoints(500)
    checkpointConfig.setMaxConcurrentCheckpoints(1)

    // set to streaming mode
    val tableEnv = StreamTableEnvironment.create(env)
    tableEnv.getConfig.set("execution.runtime-mode", "streaming")

    // Create Kafka Source Table
    val createKafkaSourceSql =
      s"""
         |CREATE TABLE IF NOT EXISTS kafka_source (
         |  screenInfo STRING,
         |  battleInfo STRING,
         |  textTabsInfo STRING,
         |  EqInfo STRING,
         |  containersInfo STRING,
         |  attackInfo STRING,
         |  areaInfo STRING,
         |  spyLevelInfo STRING,
         |  lastAttackedCreatureInfo STRING,
         |  lastKilledCreatures STRING,
         |  characterInfo STRING,
         |  focusedTabInfo STRING,
         |  processing_time AS PROCTIME()
         |) WITH (
         |  'connector' = 'kafka',
         |  'topic' = 'game-bot-events',
         |  'properties.bootstrap.servers' = '$bootstrapServers',
         |  'properties.group.id' = 'sql-test-group-final',
         |  'scan.startup.mode' = 'latest-offset',
         |  'value.format' = 'json',
         |  'properties.security.protocol' = 'SASL_SSL',
         |  'properties.sasl.mechanism' = 'PLAIN',
         |  'properties.sasl.jaas.config' = '$jaasConfig'
         |)
         |""".stripMargin

    tableEnv.executeSql(createKafkaSourceSql)

    // Create catalog
    val createCatalogSql =
      s"""
         |CREATE CATALOG iceberg_catalog WITH (
         |  'type'='iceberg',
         |  'catalog-type'='hadoop',
         |  'warehouse'='gs://$gcsBucketName/warehouse'
         |)
         |""".stripMargin

    tableEnv.executeSql(createCatalogSql)
    tableEnv.executeSql("USE CATALOG iceberg_catalog")
    tableEnv.executeSql("CREATE DATABASE IF NOT EXISTS game")
    tableEnv.executeSql("USE game")

    // Create table
    val createIcebergTableSql =
      """
        |CREATE TABLE IF NOT EXISTS game_events (
        |  screenInfo STRING,
        |  battleInfo STRING,
        |  textTabsInfo STRING,
        |  eqInfo STRING,
        |  containersInfo STRING,
        |  attackInfo STRING,
        |  areaInfo STRING,
        |  spyLevelInfo STRING,
        |  lastAttackedCreatureInfo STRING,
        |  lastKilledCreatures STRING,
        |  characterInfo STRING,
        |  focusedTabInfo STRING,
        |  processing_time TIMESTAMP_LTZ(3),
        |  dt STRING
        |) PARTITIONED BY (dt)
        |""".stripMargin

    tableEnv.executeSql(createIcebergTableSql)

    // insert data
    val insertSql =
      """
        |INSERT INTO game_events
        |SELECT
        |  screenInfo,
        |  battleInfo,
        |  textTabsInfo,
        |  EqInfo AS eqInfo,
        |  containersInfo,
        |  attackInfo,
        |  areaInfo,
        |  spyLevelInfo,
        |  lastAttackedCreatureInfo,
        |  lastKilledCreatures,
        |  characterInfo,
        |  focusedTabInfo,
        |  processing_time,
        |  DATE_FORMAT(CAST(processing_time AS TIMESTAMP), 'yyyy-MM-dd') as dt
        |FROM default_catalog.default_database.kafka_source
        |""".stripMargin

    val tableResult = tableEnv.executeSql(insertSql)

    tableResult.await()

    println("Flink job submitted. Waiting for data from Kafka and writing to Iceberg...")
  }
}