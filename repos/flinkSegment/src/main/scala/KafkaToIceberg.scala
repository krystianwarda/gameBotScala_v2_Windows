import org.apache.flink.streaming.api.scala.{StreamExecutionEnvironment, _}
import org.apache.flink.table.api.EnvironmentSettings
import org.apache.flink.table.api.bridge.scala.StreamTableEnvironment
import org.apache.flink.api.common.serialization.SimpleStringSchema
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaConsumer
import play.api.libs.json._
import scala.util.Try
import java.util.Properties
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.FileSystem


// Your event case class + JSON reader
case class BotGameEvent(
                         screenInfo: String,
                         battleInfo: String,
                         textTabsInfo: String,
                         EqInfo: String,
                         containersInfo: String,
                         attackInfo: String,
                         areaInfo: String,
                         spyLevelInfo: String,
                         lastAttackedCreatureInfo: String,
                         lastKilledCreatures: String,
                         characterInfo: String,
                         focusedTabInfo: String
                       )
object BotGameEvent {
  implicit val reads: Reads[BotGameEvent] = Json.reads[BotGameEvent]
}

object KafkaToIceberg {
  def main(args: Array[String]): Unit = {
    // 1) set up Flink streaming & table environments
    val env = StreamExecutionEnvironment.getExecutionEnvironment
    val settings = EnvironmentSettings.newInstance().inStreamingMode().build()
    val tEnv = StreamTableEnvironment.create(env, settings)

    // Set region explicitly for Hadoop
    val hadoopConf = new Configuration()
    hadoopConf.set("fs.s3a.access.key", "minio")
    hadoopConf.set("fs.s3a.secret.key", "minio123")
    hadoopConf.set("fs.s3a.endpoint", "http://minio:9000")
    hadoopConf.set("fs.s3a.path.style.access", "true")
    hadoopConf.set("fs.s3a.region", "us-east-1")
    hadoopConf.set("fs.s3a.endpoint.region", "us-east-1")
    hadoopConf.set("fs.s3a.aws.credentials.provider", "org.apache.hadoop.fs.s3a.SimpleAWSCredentialsProvider")

    tEnv.executeSql(
      s"""
         |CREATE CATALOG iceberg WITH (
         |  'type' = 'iceberg',
         |  'catalog-type' = 'hadoop',
         |  'warehouse' = 's3a://iceberg/warehouse',
         |  'io-impl' = 'org.apache.iceberg.aws.s3.S3FileIO',
         |  's3.endpoint' = 'http://minio:9000',
         |  's3.access-key-id' = 'minio',
         |  's3.secret-access-key' = 'minio123',
         |  's3.path-style-access' = 'true',
         |  's3.region' = 'us-east-1'
         |)
         |""".stripMargin)




    tEnv.useCatalog("iceberg")
    // now you can safely do
    tEnv.useDatabase("default")

    // 3) define the table in the botdb namespace
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
        |)
        |PARTITIONED BY (characterInfo)
        |WITH (
        |  'format-version'='2',
        |  'write.format.default'='parquet'
        |)
      """.stripMargin)

    // 5) build your DataStream[BotGameEvent] from Kafka
    val kafkaProps = new Properties()
    kafkaProps.setProperty("bootstrap.servers", "kafka:9092")
    kafkaProps.setProperty("group.id", "flink-bot-iceberg")
    kafkaProps.setProperty("auto.offset.reset", "earliest")

    val consumer = new FlinkKafkaConsumer[String](
      "game-bot-events",
      new SimpleStringSchema(),
      kafkaProps
    )
    val rawStream: DataStream[String] = env.addSource(consumer)
    val parsed: DataStream[BotGameEvent] = rawStream.flatMap { jsonStr =>
      Try(Json.parse(jsonStr)).toOption.flatMap(_.validate[BotGameEvent].asOpt)
    }

    // 6) register as a view and insert into Iceberg
    val botEventTable = tEnv.fromDataStream(parsed)
    tEnv.createTemporaryView("bot_game_events_view", botEventTable)

    tEnv.executeSql(
      """
        |INSERT INTO bot_game_events
        |SELECT *
        |FROM bot_game_events_view
      """.stripMargin)

    // 7) execute the Flink job
    env.execute("Kafka → Iceberg BotGameEvent")
  }
}
