import org.apache.flink.streaming.api.scala.StreamExecutionEnvironment
import org.apache.flink.streaming.api.CheckpointingMode
import org.apache.flink.table.api.EnvironmentSettings
import org.apache.flink.table.api.bridge.scala.StreamTableEnvironment

object KafkaToIceberg {
  def main(args: Array[String]): Unit = {
    // 1) Create the streaming environment and enable checkpointing
    val env = StreamExecutionEnvironment.getExecutionEnvironment
    env.enableCheckpointing(30000, CheckpointingMode.EXACTLY_ONCE)
    env.getCheckpointConfig.setMinPauseBetweenCheckpoints(10000)

    // 2) Create the TableEnvironment in streaming mode
    val settings = EnvironmentSettings.newInstance()
      .inStreamingMode()
      .build()
    val tEnv = StreamTableEnvironment.create(env, settings)

    // 3) (Optional) also set Table API checkpointing interval
    tEnv.getConfig.getConfiguration
      .setString("execution.checkpointing.interval", "30s")

    // 4) Define the Kafka source table by extracting JSON fields
    val createKafkaDdl =
      """
      CREATE TABLE kafka_input (
        `user` STRING,
        action STRING
      ) WITH (
        'connector' = 'kafka',
        'topic' = 'game-bot-events',
        'properties.bootstrap.servers' = 'kafka:9092',
        'scan.startup.mode' = 'earliest-offset',
        'format' = 'json',
        'json.fail-on-missing-field' = 'false',
        'json.ignore-parse-errors' = 'true'
      )
      """
    tEnv.executeSql(createKafkaDdl)

    // 5) Define the GCS sink table to store the parsed JSON objects
    val createGcsSinkDdl =
      """
      CREATE TABLE gcs_sink (
        `user` STRING,
        action STRING
      ) WITH (
        'connector' = 'filesystem',
        'path' = 'gs://gamebot-460320-iceberg/raw-json-output/',
        'format' = 'json',
        'sink.rolling-policy.check-interval' = '1min',
        'sink.rolling-policy.rollover-interval' = '1min',
        'sink.rolling-policy.file-size' = '128mb'
      )
      """
    tEnv.executeSql(createGcsSinkDdl)

    // 6) Submit the continuous INSERT job and wait indefinitely
    val insertSql = "INSERT INTO gcs_sink SELECT `user`, action FROM kafka_input"
    val tableResult = tEnv.executeSql(insertSql)
    val jobClient = tableResult.getJobClient
      .orElseThrow(() => new RuntimeException("JobClient not available"))
    jobClient.getJobExecutionResult().get()
  }
}