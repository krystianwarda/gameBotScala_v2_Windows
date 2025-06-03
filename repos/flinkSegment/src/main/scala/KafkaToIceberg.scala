import org.apache.flink.table.api.{EnvironmentSettings, TableEnvironment}

object KafkaToIceberg {

  def main(args: Array[String]): Unit = {
    println("[DEBUG] Starting KafkaToIceberg job...")

    // 1) Create a streaming TableEnvironment
    println("[DEBUG] Creating TableEnvironment in streaming mode")
    val settings = EnvironmentSettings
      .newInstance()
      .inStreamingMode()
      .build()
    val tEnv = TableEnvironment.create(settings)

    // 2) Define Kafka source in the default catalog
    println("[DEBUG] Creating Kafka source table `kafka_input`")
    tEnv.executeSql(
      s"""
      CREATE TABLE kafka_input (
        raw_json STRING
      ) WITH (
        'connector'                    = 'kafka',
        'topic'                        = 'game-bot-events',
        'properties.bootstrap.servers' = 'pkc-z1o60.europe-west1.gcp.confluent.cloud:9092',
        'properties.group.id'          = 'flink-raw-test',
        'scan.startup.mode'            = 'earliest-offset',
        'format'                       = 'json',
        'properties.security.protocol' = 'SASL_SSL',
        'properties.sasl.mechanism'    = 'PLAIN',
        'properties.sasl.jaas.config'  = 'org.apache.kafka.common.security.plain.PlainLoginModule required username="PSAH6XQLCFJU6P4J" password="/ZTbUorF0KSw67bDMhjNiQDaatB3xuiF9JjPe7qdBbgoEUEjaSU++IoVB2CTcbCe";'
      )
      """
    )

    // 3) Create a parsed view
    println("[DEBUG] Creating `kafka_parsed`")
    tEnv.executeSql(
      """
      CREATE VIEW kafka_parsed AS
      SELECT
        JSON_QUERY(raw_json, '$.screenInfo')               AS screenInfo,
        JSON_QUERY(raw_json, '$.battleInfo')               AS battleInfo,
        JSON_QUERY(raw_json, '$.textTabsInfo')             AS textTabsInfo,
        JSON_QUERY(raw_json, '$.EqInfo')                   AS EqInfo,
        JSON_QUERY(raw_json, '$.containersInfo')           AS containersInfo,
        JSON_QUERY(raw_json, '$.attackInfo')               AS attackInfo,
        JSON_QUERY(raw_json, '$.areaInfo')                 AS areaInfo,
        JSON_QUERY(raw_json, '$.spyLevelInfo')             AS spyLevelInfo,
        JSON_QUERY(raw_json, '$.lastAttackedCreatureInfo') AS lastAttackedCreatureInfo,
        JSON_QUERY(raw_json, '$.lastKilledCreatures')      AS lastKilledCreatures,
        JSON_QUERY(raw_json, '$.characterInfo')            AS characterInfo,
        JSON_QUERY(raw_json, '$.focusedTabInfo')           AS focusedTabInfo
      FROM kafka_input
      """
    )

    // 4) Register Iceberg HadoopCatalog
    println("[DEBUG] Registering Iceberg Hadoop catalog `iceberg_cat`")
    tEnv.executeSql(
      """
        CREATE CATALOG iceberg_cat WITH (
          'type' = 'iceberg',
          'catalog-impl' = 'org.apache.iceberg.hadoop.HadoopCatalog',
          'warehouse' = 'gs://gamebot-460320-iceberg/iceberg-warehouse',
          'property-version' = '1'
        )
      """
    )

    // ✅ Switch to the Iceberg catalog
    tEnv.executeSql("USE CATALOG iceberg_cat")

    // 5) Create Iceberg sink table
    println("[DEBUG] Ensuring Iceberg sink table `mytable` exists")
    tEnv.executeSql(
      """
      CREATE TABLE mytable (
        screenInfo STRING,
        battleInfo STRING,
        textTabsInfo STRING,
        EqInfo STRING,
        containersInfo STRING,
        attackInfo STRING,
        areaInfo STRING,
        spyLevelInfo STRING,
        lastAttackedCreatureInfo STRING,
        lastKilledCreatures STRING,
        characterInfo STRING,
        focusedTabInfo STRING
      )
      """
    )

    // 6) Perform streaming insert with .await()
    println("[DEBUG] Inserting into iceberg_cat.default.mytable...")
    val result = tEnv.executeSql(
      """
      INSERT INTO mytable
      SELECT * FROM default_catalog.default_database.kafka_parsed
      """
    )

    result.await() // ✅ This blocks the streaming job properly

    println("[DEBUG] Job streaming execution completed.")
  }
}
