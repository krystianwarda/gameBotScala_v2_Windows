package utils
import akka.actor.{Actor, ActorRef}
import main.scala.MainApp
import play.api.libs.json.{JsValue, Json}
import play.api.libs.json.Json.JsValueWrapper
import main.scala.MainApp.StartActors

import java.io.{DataInputStream, DataOutputStream, EOFException, IOException}
import java.net.{InetAddress, Socket, SocketException, SocketTimeoutException}
import java.nio.{ByteBuffer, ByteOrder}
import java.util.concurrent.TimeUnit
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.util.{Random, Try}


class PeriodicFunctionActor(jsonProcessorActor: ActorRef) extends Actor {
  import context.dispatcher
  private var reconnecting = false
  val serverAddress = InetAddress.getByName("127.0.0.1")
  val port = 9997
  var socket: Option[Socket] = None
  var out: Option[DataOutputStream] = None
  var in: Option[DataInputStream] = None


  override def preStart(): Unit = {

  }

  override def receive: Receive = {
    case "startListening" =>
      startListening()
    case json: JsValue =>
      jsonProcessorActor ! MainApp.JsonData(json)
    case StartActors(settings) =>
      println("PeriodicFunctionActor received StartActors message.")
      if (socket.isEmpty || socket.exists(!_.isConnected)) {
        connectToServer()
      }
      startListening()
      initiateSendFunction("init")
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


  def sendJson(json: JsValue): Boolean = {
    if (socket.isEmpty || socket.exists(!_.isConnected)) {
      println("Socket is not connected. Attempting to reconnect.")
      connectToServer()
      return false
    }

    try {
      println("Sending JSON: " + json)
      out.flatMap { o =>
        val data = Json.stringify(json).getBytes("UTF-8")
        try {
          val lengthBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(data.length).array()
          o.write(lengthBytes)
          o.write(data)
          o.flush()
          println("JSON sent successfully.")
          Some(true)
        } catch {
          case e: IOException =>
            println(s"IOException during sending JSON: $e")
            e.printStackTrace()
            None
        }
      }.getOrElse(false)
    } catch {
      case e: SocketException =>
        println(s"SocketException when sending JSON: ${e.getMessage}")
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
        readJsonFromSocket() match {
          case Some(json) => self ! json
          case None => // Do nothing, effectively skipping this loop iteration
        }
      }
    }.recover {
      case e: Exception => println(s"Listening error: ${e.getMessage}")
    }
  }

  def readJsonFromSocket(): Option[JsValue] = Try {
    val socketInputStream = in.getOrElse(throw new IllegalStateException("Socket input stream not initialized"))
    val lengthBytes = new Array[Byte](4)
    if (socketInputStream.read(lengthBytes) == -1) {
      throw new IOException("End of stream reached")
    }
    val length = ByteBuffer.wrap(lengthBytes).order(ByteOrder.LITTLE_ENDIAN).getInt
    if (length > 0) {
      val data = new Array[Byte](length)
      socketInputStream.readFully(data)
      Json.parse(data)
    } else {
      throw new IOException("Received empty message")
    }
  }.toOption


  def initiateSendFunction(commandName: String): Unit = {
    val jsonCommand = Json.obj("__command" -> commandName)
    if (!sendJson(jsonCommand)) {
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