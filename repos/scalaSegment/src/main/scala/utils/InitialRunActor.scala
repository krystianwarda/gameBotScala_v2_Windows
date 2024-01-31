package utils
import akka.actor.{Actor, ActorRef}
import play.api.libs.json.{JsValue, Json}

import java.net.{InetAddress, Socket, SocketException, SocketTimeoutException}
import java.io.{DataInputStream, DataOutputStream, EOFException, IOException}
import java.nio.{ByteBuffer, ByteOrder}
import java.util.concurrent.TimeUnit
import scala.concurrent.Future
import scala.concurrent.Future.never.recover
import scala.concurrent.duration.Duration
import scala.util.{Random, Try}
import scala.util.control.NonFatal

class InitialRunActor(initialJsonProcessorActor: ActorRef) extends Actor {
  import context.dispatcher
  private var reconnecting = false
  private val maxReconnectAttempts = 5 // Limit reconnection attempts
  private var reconnectAttempts = 0
  val serverAddress = InetAddress.getByName("127.0.0.1")
  val port = 9997
  var socket: Option[Socket] = None
  var out: Option[DataOutputStream] = None
  var in: Option[DataInputStream] = None


  override def preStart(): Unit = {
    // Execute your code here immediately upon actor creation
//    connectToServer()
//    startListening()
//    val command = Json.obj("__command" -> "innitialGetGameData")
//    sendJson(command) // Directly send the command to the TCP server
  }

  override def receive: Receive = {
    case anyMessage =>
      //
  }


  def connectToServer(): Unit = {
    Try {
      println("Initial temporary connecting to server...")
      val s = new Socket(serverAddress, port)
      s.setSoTimeout(5000)
      socket = Some(s)
      out = Some(new DataOutputStream(s.getOutputStream))
      in = Some(new DataInputStream(s.getInputStream))
      println("Temporary connected to server")
      reconnectAttempts = 0 // Reset the counter on successful connection
    } recover {
      case e: IOException =>
        handleConnectionFailure(e)
    }
  }

  private def handleConnectionFailure(e: IOException): Unit = {
    println(s"Failed to connect to server: $e")
    if (reconnectAttempts < maxReconnectAttempts) {
      scheduleReconnection()
      reconnectAttempts += 1
    } else {
      println("Maximum reconnection attempts reached. Stopping.")
    }
  }

  def scheduleReconnection(): Unit = {
    if (!reconnecting) {
      reconnecting = true
      // Implement a back-off strategy to wait before reconnecting
      val backoffTime = Random.nextInt(5000) + 1000 // Random delay between 1 to 5 seconds
      println(s"Scheduling reconnection after $backoffTime milliseconds...")
      context.system.scheduler.scheduleOnce(Duration(backoffTime, TimeUnit.MILLISECONDS)) {
        connectToServer()
        reconnecting = false
      }
    }
  }



  def sendJson(json: JsValue): Boolean = {
    // Improved socket check and reconnection logic
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
    try {
      in.flatMap { i =>
        val lengthBytes = new Array[Byte](4)
        if (i.read(lengthBytes) == -1) {
          throw new EOFException("End of stream reached")
        }
        val length = ByteBuffer.wrap(lengthBytes).order(ByteOrder.LITTLE_ENDIAN).getInt

        if (length > 0) {
          println(s"Receiving JSON of length: $length")
          val data = new Array[Byte](length)
          var totalRead = 0
          while (totalRead < length) {
            val read = i.read(data, totalRead, length - totalRead)
            if (read == -1) throw new EOFException("End of stream reached")
            totalRead += read
          }
          println("JSON data received successfully.")
          val json = Json.parse(data)
          Some(json)
        } else {
          None
        }
      }
    } catch {
      case _: EOFException =>
        println("End of stream reached, connection closed by server.")
        reconnectToServer()
        None
      case _: SocketTimeoutException =>
        println("Read timeout exceeded, continuing to listen...")
        None
      case e: IOException =>
        println(s"Failed to receive JSON: $e")
        reconnectToServer()
        None
    }
  }

  def startListening(): Unit = {
    Future {
      var listening = true
      while (listening && socket.exists(_.isConnected)) {
        receiveJson() match {
          case Some(json) =>
            initialJsonProcessorActor ! JsonData(json)
            closeConnection()
            listening = false
          case None =>
            if (socket.isEmpty || !socket.get.isConnected) {
              listening = false
            }
        }
      }
    } recover {
      case e: Throwable =>
        println(s"Error in startListening: ${e.getMessage}")
    }
  }

  def closeConnection(): Unit = {
    println("Closing connection to server...")
    try {
      out.foreach(_.close())
      in.foreach(_.close())
      socket.foreach(_.close())
      println("Connection closed")
    } catch {
      case e: IOException => println(s"Failed to close resources: $e")
    }
  }

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
    val backoffTime = Random.nextInt(2000) + 1000 // Random delay between 5 to 10 seconds
    println(s"Scheduling reconnection after $backoffTime milliseconds...")
    context.system.scheduler.scheduleOnce(Duration(backoffTime, TimeUnit.MILLISECONDS)) {
      connectToServer()
      reconnecting = false
    }
  }

  override def postStop(): Unit = {
    println("PeriodicFunctionActor stopping...")
    try {
      out.foreach(_.close())
      in.foreach(_.close())
      socket.foreach(_.close())
      println("Resources closed")
    } catch {
      case e: IOException => println(s"Failed to close resources: $e")
    }
  }
}
