import org.apache.flink.streaming.api.CheckpointingMode
import org.apache.flink.streaming.api.scala._
import org.apache.flink.table.api.EnvironmentSettings
import org.apache.flink.table.api.bridge.scala.StreamTableEnvironment
import java.util.UUID

object KafkaToIceberg {
  def main(args: Array[String]): Unit = {
    val env = StreamExecutionEnvironment.getExecutionEnvironment
    env.enableCheckpointing(60000, CheckpointingMode.EXACTLY_ONCE)
    env.getCheckpointConfig.setMinPauseBetweenCheckpoints(10000)

    val settings = EnvironmentSettings.newInstance().inStreamingMode().build()
    val tEnv = StreamTableEnvironment.create(env, settings)
    tEnv.getConfig.getConfiguration.setString("execution.checkpointing.interval", "60s")

    val randomGroup = "flink-debug-" + UUID.randomUUID().toString

    println(">> Creating Kafka source table...")
    tEnv.executeSql(s"""
      CREATE TABLE kafka_source (
        raw_bytes BYTES
      ) WITH (
        'connector'                    = 'kafka',
        'topic'                        = 'game-bot-events',
        'properties.bootstrap.servers' = 'pkc-z1o60.europe-west1.gcp.confluent.cloud:9092',
        'properties.group.id'          = '$randomGroup',
        'scan.startup.mode'            = 'earliest-offset',
        'format'                       = 'raw',
        'properties.security.protocol' = 'SASL_SSL',
        'properties.sasl.mechanism'    = 'PLAIN',
        'properties.sasl.jaas.config'  = 'org.apache.kafka.common.security.plain.PlainLoginModule required username="ONCVZD5AX7IMSTOK" password="jBMXKSCE8VJZSzg4cRZWyc7V44zZC8QkSfegp62pY9MuLW6suhZoITom1GlOGDug";'
      )
    """)

    println(">> Creating GCS JSON sink...")
    tEnv.executeSql(
      """
      CREATE TABLE gcs_sink (
        raw_json STRING
      ) WITH (
        'connector'                          = 'filesystem',
        'path'                               = 'gs://gamebot-460320-iceberg/raw-output/',
        'format'                             = 'raw',
        'sink.rolling-policy.file-size'      = '100KB',
        'sink.rolling-policy.rollover-interval' = '10s',
        'sink.rolling-policy.check-interval' = '5s'
      )
      """
    )


    println(">> Inserting into GCS sink (raw string from Kafka)...")
    tEnv.executeSql(
      """
      INSERT INTO gcs_sink
      SELECT CAST(raw_bytes AS STRING) AS raw_json
      FROM kafka_source
      """
    )
  }
}
