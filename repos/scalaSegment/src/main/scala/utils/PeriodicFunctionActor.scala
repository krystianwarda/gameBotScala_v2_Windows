package utils
import akka.actor.{Actor, ActorRef}
import akka.util.ByteString
import cats.effect.{IO, Ref}
import main.scala.MainApp
import play.api.libs.json.{JsArray, JsBoolean, JsNull, JsObject, JsValue, Json}
import play.api.libs.json.Json.JsValueWrapper
import main.scala.MainApp.StartActors
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerConfig}
import org.apache.kafka.common.serialization.StringSerializer
import java.io.{DataInputStream, DataOutputStream, EOFException, IOException}
import java.net.{InetAddress, Socket, SocketException, SocketTimeoutException}
import java.nio.{ByteBuffer, ByteOrder}
import java.util.concurrent.TimeUnit
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.util.control.NonFatal
import scala.util.{Random, Try}
import play.api.libs.json._
import processing.FunctionalJsonConsumer
import java.util.Properties
import scala.collection.mutable
import cats.effect.unsafe.implicits.global
import org.apache.kafka.clients.producer.RecordMetadata


case class StartSpecificPeriodicFunction(functionName: String)

case class KafkaConfig(
                        bootstrap_servers: String,
                        group_id: String,
                        kafka_api_key: String,
                        kafka_api_secret: String,
                        kafka_cluster_id: String,
                        rest_endpoint: String,
                        topic: String,
                        actionTopic: String
                      )

object KafkaConfigLoader {
  def loadFromFile(path: String): KafkaConfig = {
    val source = scala.io.Source.fromFile(path)
    try {
      val content = source.getLines().mkString
      val json = Json.parse(content)
      KafkaConfig(
        (json \ "bootstrap_servers").as[String],
        (json \ "group_id").as[String],
        (json \ "kafka_api_key").as[String],
        (json \ "kafka_api_secret").as[String],
        (json \ "kafka_cluster_id").as[String],
        (json \ "rest_endpoint").as[String],
        (json \ "topic").as[String],
        (json \ "actionTopic").as[String]
      )
    } finally {
      source.close()
    }
  }
}



class PeriodicFunctionActor(
                             jsonProcessorActor: ActorRef,
                             jsonConsumer: FunctionalJsonConsumer
                           ) extends Actor {
  import context.dispatcher
  private var reconnecting = false
  val serverAddress = InetAddress.getByName("127.0.0.1")
  val port = 9997
  var socket: Option[Socket] = None
  var out: Option[DataOutputStream] = None
  var in: Option[DataInputStream] = None
//  private var  latestJson: Option[JsValue] = None
  private val latestJsonRef: Ref[IO, Option[JsValue]] = Ref.unsafe(None)





  override def preStart(): Unit = {

  }

  override def receive: Receive = {
    case "startListening" =>
      startListening()


    case json: JsValue =>
      latestJsonRef.set(Some(json)).unsafeRunAndForget()
      jsonConsumer.process(json).unsafeRunAndForget()


    case "fetchLatestJson" =>
      val originalSender = sender()
      // Retrieve JSON functionally using Ref
      latestJsonRef.get.map {
        case Some(json) => originalSender ! json
        case None => originalSender ! Json.obj("error" -> "No JSON data available")
      }.unsafeRunAndForget()

    case StartActors(settings) =>
      println("PeriodicFunctionActor received StartActors message.")
      if (socket.isEmpty || socket.exists(!_.isConnected)) {
        connectToServer()
      }
      startListening()
      initiateSendFunction("periodicEvent")
      println("Listening for keys started!")
//      startListeningForKeys()


    case _ => println("PeriodicFunctionActor received an unhandled message type.")

  }


  def connectToServer(): Unit = {

    try {
      println("Connecting to server...")
      val s = new Socket(serverAddress, port)
      socket = Some(s)
      s.setSoTimeout(5000) // Set read timeout to 5000 milliseconds
      out = Some(new DataOutputStream(s.getOutputStream))
      in = Some(new DataInputStream(s.getInputStream))
      println("Connected to server")
    } catch {
      case e: IOException => println(s"Failed to connect to server: $e")
    }
  }



  def sendJson(functionName: String): Boolean = {
    // This command is manually constructed to match your desired byte format
    val commandString = "__command\r\u0000\u0000\u0000periodicEvent"
    val commandPrefix = Array[Byte](0x89.toByte) // Prefix byte
    val command = commandPrefix ++ commandString.getBytes("UTF-8")

    if (socket.isEmpty || socket.exists(!_.isConnected)) {
      println("Socket is not connected. Attempting to reconnect.")
      connectToServer()
      return false
    }

    try {
      println(s"Sending binary data: ${command.map("%02X" format _).mkString(" ")}")
      out.foreach { outputStream =>
        val lengthBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(command.length).array()
        outputStream.write(lengthBytes)
        outputStream.write(command)
        outputStream.flush()
        println("Binary data sent successfully.")
      }
      true
    } catch {
      case e: IOException =>
        println(s"IOException during sending binary data: ${e.getMessage}")
        e.printStackTrace()
        false
      case e: SocketException =>
        println(s"SocketException when sending binary data: ${e.getMessage}")
        connectToServer()
        false
    }
  }

  def receiveJson(): Option[JsValue] = {
    println("Received a JSON...")
    try {
      in.flatMap { i =>
        val lengthBytes = new Array[Byte](4)
        if (i.read(lengthBytes) == -1) {
          throw new EOFException("End of stream reached")
        }
        val length = ByteBuffer.wrap(lengthBytes).order(ByteOrder.LITTLE_ENDIAN).getInt
        if (length > 0) {
          val data = ByteBuffer.allocate(length) // Use ByteBuffer for more efficient memory management
          i.readFully(data.array()) // Read the data into the buffer
          Some(Json.parse(data.array()))
        } else {
          None
        }
      }
    } catch {
      case _: EOFException =>
        println("End of stream reached, connection closed by server.")
        None
      case _: SocketTimeoutException =>
        println("Read timeout exceeded, continuing to listen...")
        None
      case e: IOException =>
        println(s"Failed to receive JSON: $e")
        // reconnectToServer()
        None
    }
  }

  def startListening(): Unit = {
    Future {
      while (socket.exists(_.isConnected)) {
        readFromSocket() match {
          case Some(data) =>
//            println(s"Received: ${data.utf8String}") // Print the raw data as a UTF-8 string
            processData(data) // Process the data, possibly JSON
          case None => // Do nothing, effectively skipping this loop iteration
        }
      }
    }.recover {
      case e: Exception => println(s"Listening error: ${e.getMessage}")
    }
  }

  def deserialize(data: Array[Byte]): JsValue = {
    val obj = mutable.Map[String, JsValue]()
    val buffer = ByteBuffer.wrap(data)
    buffer.order(ByteOrder.LITTLE_ENDIAN)

    while (buffer.hasRemaining) {
      if (buffer.remaining() < 1) return Json.toJson(obj) // Early exit if data is insufficient

      val tagH = buffer.get() & 0xFF
      val tagT = tagH >> 5
      val tagNL = tagH & 31

      if (buffer.remaining() < tagNL) return Json.toJson(obj) // Check data sufficiency for key

      val keyBytes = new Array[Byte](tagNL)
      buffer.get(keyBytes)
      val key = new String(keyBytes, java.nio.charset.StandardCharsets.UTF_8)

      val value = tagT match {
        case 1 => JsBoolean(buffer.get() != 0)
        case 2 => if (buffer.remaining() < 4) JsNull else JsNumber(buffer.getInt())
        case 3 => if (buffer.remaining() < 8) JsNull else JsNumber(buffer.getDouble())
        case 4 =>
          if (buffer.remaining() < 4) JsNull else {
            val length = buffer.getInt()
            if (buffer.remaining() < length) JsNull else {
              val strBytes = new Array[Byte](length)
              buffer.get(strBytes)
              JsString(new String(strBytes, java.nio.charset.StandardCharsets.UTF_8))
            }
          }
        case 5 =>
          if (buffer.remaining() < 4) JsNull else {
            val length = buffer.getInt()
            if (buffer.remaining() < length) JsNull else {
              val nestedData = new Array[Byte](length)
              buffer.get(nestedData)
              deserialize(nestedData)
            }
          }
        case _ =>
//          println(s"Unhandled tag type $tagT at buffer position ${buffer.position()}")
          JsString("Error: Unhandled tag type")
      }

      if (key.nonEmpty && value != null) obj(key) = value
    }

    // Adjusted to remove 'root' and use a direct key if present
    obj.get("root").flatMap(_.asOpt[JsObject]).getOrElse(JsObject(obj.toSeq))
  }

  // Implicit Writes for handling Any type
  implicit val anyWrites: Writes[Any] = new Writes[Any] {
    def writes(o: Any): JsValue = o match {
      case n: Int => JsNumber(n)
      case s: String => JsString(s)
      case b: Boolean => JsBoolean(b)
      case d: Double => JsNumber(d)
      case map: Map[_, _]@unchecked => Json.toJson(map.asInstanceOf[Map[String, Any]])
      case other => JsString(other.toString)
    }
  }

  // Custom Writes for handling Map[String, Any] to ensure type safety and proper JSON structure
  implicit val mapWrites: Writes[Map[String, Any]] = new Writes[Map[String, Any]] {
    def writes(map: Map[String, Any]): JsValue = JsObject(map.map { case (key, value) =>
      key -> Json.toJson(value)(anyWrites)
    }.toSeq)
  }




  def processData(data: ByteString): Unit = {
//    println(s"Received raw data: ${data.utf8String}")
    try {
      val decodedData = deserialize(data.toArray)
//      println(s"Deserialized data: $decodedData")
      self ! decodedData // Send deserialized data to self
    } catch {
      case e: Exception =>
        println(s"Error processing data: ${e.getMessage}")
    }
  }

  def readFromSocket(): Option[ByteString] = Try {
    val socketInputStream = in.getOrElse(throw new IllegalStateException("Socket input stream not initialized"))
    val lengthBytes = new Array[Byte](4)
    if (socketInputStream.read(lengthBytes) == -1) {
      throw new IOException("End of stream reached")
    }
    val length = ByteBuffer.wrap(lengthBytes).order(ByteOrder.LITTLE_ENDIAN).getInt
    if (length > 0) {
      val data = new Array[Byte](length)
      var bytesRead = 0
      var bytesToRead = length
      while (bytesToRead > 0) {
        val result = socketInputStream.read(data, bytesRead, bytesToRead)
        if (result == -1) throw new IOException("End of stream reached while reading data")
        bytesRead += result
        bytesToRead -= result
      }
      ByteString(data) // Return raw binary data
    } else {
      throw new IOException("Received empty message")
    }
  }.toOption



  def initiateSendFunction(commandName: String): Unit = {
    println(s"commandName: $commandName")
    val jsonCommand = Json.obj("__command" -> commandName)
    if (!sendJson(commandName)) {
      println("Failed to send JSON, attempting to reconnect for the next task...")
      reconnectToServer()
    }
  }



  def reconnectToServer(): Unit = {
    reconnecting = true

    // Close existing socket if it's open
    if (socket.exists(!_.isClosed)) {
      try {
        out.foreach(_.close())
        in.foreach(_.close())
        socket.foreach(_.close())
      } catch {
        case e: IOException => println(s"Failed to close existing connection: $e")
      }
    }

    // Implement a back-off strategy to wait before reconnecting
    val backoffTime = Random.nextInt(5000) + 5000 // Random delay between 5 to 10 seconds
    println(s"Scheduling reconnection after $backoffTime milliseconds...")
    context.system.scheduler.scheduleOnce(Duration(backoffTime, TimeUnit.MILLISECONDS)) {
      connectToServer()
      reconnecting = false
    }
  }



  override def postStop(): Unit = {
    println("PeriodicFunctionActor stopping...")
  }
}