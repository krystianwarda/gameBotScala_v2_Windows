package utils
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord, Callback, RecordMetadata}
import play.api.libs.json.JsValue
import java.util.Properties
import scala.util.{Try, Success, Failure}

class KafkaJsonPublisher(bootstrapServers: String, topic: String) {

  private lazy val producerOpt: Option[KafkaProducer[String, String]] = {
    val props = new Properties()
    props.put("bootstrap.servers", bootstrapServers)
    props.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer")
    props.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer")
    props.put("acks", "1")
    props.put("retries", "3")
    props.put("linger.ms", "5")
    props.put("max.block.ms", "5000")


    Try(new KafkaProducer[String, String](props)) match {
      case Success(producer) =>
        println("Kafka producer initialized.")
        Some(producer)
      case Failure(ex) =>
        println(s"[WARN] Kafka unavailable: ${ex.getMessage}")
        None
    }
  }

  def send(json: JsValue): Unit = {
    producerOpt.foreach { producer =>
      val jsonStr = json.toString()
      val record = new ProducerRecord[String, String](topic, jsonStr)

      producer.send(record, new Callback {
        override def onCompletion(metadata: RecordMetadata, exception: Exception): Unit = {
          if (exception != null)
            println(s"[Kafka ERROR] ${exception.getMessage}")
          else
            println(s"[Kafka OK] offset=${metadata.offset()}, partition=${metadata.partition()}")
        }
      })
    }
  }

  def close(): Unit = {
    producerOpt.foreach(_.close())
  }
}
