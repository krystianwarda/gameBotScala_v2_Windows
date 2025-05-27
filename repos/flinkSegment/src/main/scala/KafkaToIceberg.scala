import org.apache.flink.streaming.api.scala.StreamExecutionEnvironment
import org.apache.flink.streaming.api.CheckpointingMode
import org.apache.flink.table.api.EnvironmentSettings
import org.apache.flink.table.api.bridge.scala.StreamTableEnvironment

object KafkaToIceberg {
  def main(args: Array[String]): Unit = {
    println("[DEBUG] Starting KafkaToIceberg job...")

    // GCP bucket and path
    val outputPath = "gs://gamebot-460320-iceberg/iceberg-warehouse/appdb/mytable/"
    println(s"[DEBUG] Output path set to: $outputPath")

    // 1) Streaming environment with 15s checkpointing
    println("[DEBUG] Setting up StreamExecutionEnvironment with 15s checkpointing")
    val env = StreamExecutionEnvironment.getExecutionEnvironment
    env.enableCheckpointing(15000, CheckpointingMode.EXACTLY_ONCE)
    env.getCheckpointConfig.setMinPauseBetweenCheckpoints(10000)

    // 2) Table environment in streaming mode
    println("[DEBUG] Creating StreamTableEnvironment in streaming mode")
    val settings = EnvironmentSettings.newInstance().inStreamingMode().build()
    val tEnv = StreamTableEnvironment.create(env, settings)
    tEnv.getConfig.getConfiguration.setString("execution.checkpointing.interval", "15s")

    // 3) Define Kafka source table
    println("[DEBUG] Executing DDL for kafka_input table")
    val createKafkaDdl =
      """
      CREATE TABLE kafka_input (
        raw_json STRING
      ) WITH (
        'connector'                    = 'kafka',
        'topic'                        = 'game-bot-events',
        'properties.bootstrap.servers' = 'kafka:9092',
        'properties.group.id'          = 'flink-raw-test',
        'scan.startup.mode'            = 'earliest-offset',
        'format'                       = 'raw'
      )
      """
    tEnv.executeSql(createKafkaDdl)
    println("[DEBUG] kafka_input table created")

    // 4) Create a view to parse top-level JSON keys via JSON_QUERY
    println("[DEBUG] Creating view kafka_parsed")
    val createParsedView =
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
    tEnv.executeSql(createParsedView)
    println("[DEBUG] kafka_parsed view created")

    // 5) Define filesystem sink with 15s rollovers
    println("[DEBUG] Executing DDL for gcs_sink table")
    val createGcsSinkDdl = s"""
      CREATE TABLE gcs_sink (
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
      ) WITH (
        'connector'                         = 'filesystem',
        'path'                              = '$outputPath',
        'format'                            = 'json',
        'sink.rolling-policy.check-interval'  = '15s',
        'sink.rolling-policy.rollover-interval' = '15s',
        'sink.rolling-policy.file-size'       = '128mb'
      )
      """
    tEnv.executeSql(createGcsSinkDdl)
    println("[DEBUG] gcs_sink table created")

    // 6) Submit the continuous INSERT into GCS
    println("[DEBUG] Submitting INSERT INTO gcs_sink SELECT * FROM kafka_parsed")
    val result = tEnv.executeSql("INSERT INTO gcs_sink SELECT * FROM kafka_parsed")
    println("[DEBUG] Insert job submitted, awaiting result...")
    val jobClient = result.getJobClient.orElseThrow(() => new RuntimeException("JobClient not available"))
    val jobResult = jobClient.getJobExecutionResult().get()
    println(s"[DEBUG] Job finished with result: $jobResult")
  }
}
