import org.apache.flink.streaming.api.scala.StreamExecutionEnvironment
import org.apache.flink.table.api.EnvironmentSettings
import org.apache.flink.table.api.bridge.scala.StreamTableEnvironment

object KafkaToIceberg {
  def main(args: Array[String]): Unit = {
    // 1) Flink environments
    val env = StreamExecutionEnvironment.getExecutionEnvironment
    val settings = EnvironmentSettings.newInstance().inStreamingMode().build()
    val tEnv = StreamTableEnvironment.create(env, settings)


    // 2) Register Kafka source table using SQL DDL
    tEnv.executeSql(
      """
        |CREATE TABLE kafka_input (
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
        |  focusedTabInfo STRING
        |) WITH (
        |  'connector' = 'kafka',
        |  'topic' = 'game-bot-events',
        |  'properties.bootstrap.servers' = 'kafka:9092',
        |  'properties.group.id' = 'flink-bot-iceberg',
        |  'scan.startup.mode' = 'earliest-offset',
        |  'format' = 'json',
        |  'json.ignore-parse-errors' = 'true'
        |)
        |""".stripMargin)

    // 3) Create the print sink
    tEnv.executeSql(
      """
        |CREATE TABLE bot_game_events (
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
        |  focusedTabInfo STRING
        |) WITH (
        |  'connector' = 'print'
        |)
        |""".stripMargin)

    // 4) Pipe from Kafka to print
    tEnv.executeSql(
      """
        |INSERT INTO bot_game_events
        |SELECT * FROM kafka_input
        |""".stripMargin)
  }
}
