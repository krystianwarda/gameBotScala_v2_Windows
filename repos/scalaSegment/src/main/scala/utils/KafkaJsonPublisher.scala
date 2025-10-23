package utils

import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord, Callback, RecordMetadata}
import play.api.libs.json.JsValue
import java.util.Properties
import scala.util.{Try, Success, Failure}
import scala.io.Source
import play.api.libs.json.Json

class KafkaJsonPublisher(
                          bootstrapServers: String,
                          gameDataTopic: String,
                          actionTopic: String,
                          username: String,
                          password: String
                        ) {

  private lazy val partitionNumber: Int = {
    Try {
      val source = Source.fromFile("C:\\machine_info.txt")
      val content = source.mkString
      source.close()
      val json = Json.parse(content)
      (json \ "machine_number").as[Int]
    } match {
      case Success(num) if num >= 0 && num <= 3 =>
        println(s"Using partition $num from machine_info.txt")
        num
      case Success(num) =>
        println(s"[WARN] Invalid partition number $num in machine_info.txt, using 0")
        0
      case Failure(ex) =>
        println(s"[WARN] Failed to read partition from machine_info.txt: ${ex.getMessage}, using 0")
        0
    }
  }

  private lazy val producerOpt: Option[KafkaProducer[String, String]] = {
    val props = new Properties()
    props.put("bootstrap.servers", bootstrapServers)
    props.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer")
    props.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer")
    props.put("acks", "1")
    props.put("retries", "3")
    props.put("linger.ms", "5")
    props.put("max.block.ms", "5000")

    // Memory & batching
    props.put("buffer.memory", (256L * 1024 * 1024).toString) // 256 MB
    props.put("batch.size", (128 * 1024).toString)            // 128 KB
    props.put("linger.ms", "10")                               // allow small batching
    props.put("compression.type", "lz4")                       // or "zstd"

    // Backpressure/timeout
    props.put("max.block.ms", "20000")                         // tolerate bursts longer than 5s
    props.put("max.in.flight.requests.per.connection", "3")
    props.put("delivery.timeout.ms", "120000")
    props.put("request.timeout.ms", "30000")

    // ✅ SASL_SSL Auth for Confluent Cloud
    props.put("security.protocol", "SASL_SSL")
    props.put("sasl.mechanism", "PLAIN")
    props.put(
      "sasl.jaas.config",
      s"""org.apache.kafka.common.security.plain.PlainLoginModule required username="$username" password="$password";"""
    )

    Try(new KafkaProducer[String, String](props)) match {
      case Success(producer) =>
        println("Kafka producer initialized.")
        Some(producer)
      case Failure(ex) =>
        println(s"[WARN] Kafka unavailable: ${ex.getMessage}")
        None
    }
  }

  def sendGameData(json: JsValue): Unit = {
    sendToTopic(json, gameDataTopic)
  }

  def sendActionData(json: JsValue): Unit = {
    sendToTopic(json, actionTopic)
  }



  private def sendToTopic(json: JsValue, topic: String): Unit = {
    producerOpt.foreach { producer =>
      val jsonStr = json.toString()
      val record = new ProducerRecord[String, String](topic, partitionNumber, null, jsonStr)

      producer.send(record, new Callback {
        override def onCompletion(metadata: RecordMetadata, exception: Exception): Unit = {
          if (exception != null)
            println(s"[Kafka ERROR] topic=$topic ${exception.getMessage}")
          else
            println(s"[Kafka OK] topic=$topic offset=${metadata.offset()}, partition=${metadata.partition()}")
        }
      })
    }
  }

  def close(): Unit = {
    producerOpt.foreach(_.close())
  }



}
