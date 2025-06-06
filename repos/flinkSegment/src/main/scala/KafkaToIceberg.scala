import org.apache.flink.table.api.{EnvironmentSettings, TableEnvironment}

object KafkaToIceberg {

  def main(args: Array[String]): Unit = {
    println("[DEBUG] Starting KafkaToGCS job...")

    // 1) Create a streaming TableEnvironment
    println("[DEBUG] Creating TableEnvironment in streaming mode")
    val settings = EnvironmentSettings
      .newInstance()
      .inStreamingMode()
      .build()
    val tEnv = TableEnvironment.create(settings)

    // 2) Define Kafka source
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

    // 3) Define GCS FileSystem sink
    println("[DEBUG] Creating GCS output table `gcs_output`")
    tEnv.executeSql(
      """
      CREATE TABLE gcs_output (
        raw_json STRING
      ) WITH (
        'connector' = 'filesystem',
        'path'      = 'gs://gamebot-460320-iceberg/raw-output/',
        'format'    = 'json'
      )
      """
    )

    // 4) Stream insert
    println("[DEBUG] Inserting into gcs_output...")
    val result = tEnv.executeSql(
      """
      INSERT INTO gcs_output
      SELECT raw_json FROM kafka_input
      """
    )

    result.await() // Blocks the job to keep running

    println("[DEBUG] Job streaming execution completed.")
  }
}
