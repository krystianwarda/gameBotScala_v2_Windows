//import MainApp.periodicFunctionActor
import akka.actor.{Actor, ActorRef, ActorSystem, Cancellable, Props}

import play.api.libs.json.Json.JsValueWrapper
import player.Player

import java.io.EOFException
import java.net.{ServerSocket, SocketException, SocketTimeoutException}
import java.nio.{ByteBuffer, ByteOrder}
import java.util.concurrent.TimeUnit
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.io.BufferedSource
import scala.util.Random
import scala.collection.immutable.Seq
import scala.concurrent.ExecutionContext.Implicits.global  // Import global ExecutionContext
// Other imports remain the same

// Define the new PeriodicFunctionActor
import akka.actor.Actor
import java.net.{Socket, InetAddress}
import java.io.{DataOutputStream, DataInputStream, IOException}
import play.api.libs.json._
import scala.concurrent.duration._
import akka.actor.Actor
import java.net.{Socket, InetAddress}
import java.io.{DataOutputStream, DataInputStream, IOException}
import play.api.libs.json._
import scala.concurrent.duration._


case class FunctionCall(functionName: String, arg1: Option[String] = None, arg2: Option[String] = None)
case class JsonData(json: JsValue)
case class SendJsonCommand(json: JsValue)

case class cpResult(message: String, additionalData: Option[JsValue] = None)

trait CommandProcessor {
  def execute(json: JsValue, player: Player, settings: UISettings): cpResult
}

class JsonProcessorActor extends Actor {
  // Immutable state
//  var player: Player = // Initialize with default or fetch from a message
//  var settings: UISettings = // Initialize with default or fetch from a message

  import context.dispatcher

  private var reconnecting = false
  val serverAddress = InetAddress.getByName("127.0.0.1")
  val port = 9997
  var socket: Option[Socket] = None
  var out: Option[DataOutputStream] = None
  var in: Option[DataInputStream] = None

  def receive: Receive = {
    case JsonData(json) =>
      println("JsonProcessorActor received JSON: " + json)
      processJson(json)
    // Handle other messages...
    case StartActors(settings) =>
    // existing logic for handling StartActors messages
    // ... other cases ...
  }


  private def processJson(json: JsValue): Unit = {
    // Example: Determining the action based on JSON data
    (json \ "__status").asOpt[String] match {
      case Some("ok") => handleOkStatus(json)
      case Some("error") => handleErrorStatus(json)
      case _ => println("Unknown status")
    }
  }

  private def handleErrorStatus(json: JsValue): Unit = {
    // Handle error status
  }
  private def findRats(battleInfo: JsValue): Seq[Long] = {
    battleInfo match {
      case obj: JsObject => obj.fields.flatMap {
        case (_, creature) =>
          val name = (creature \ "Name").asOpt[String]
          val id = (creature \ "Id").asOpt[Long]
          if (name.contains("Rat")) id else None
      }.toSeq
      case _ => Seq.empty
    }
  }

  private def handleOkStatus(json: JsValue): Unit = {
    // Example: Handling 'ok' status
    (json \ "battleInfo").asOpt[JsValue].foreach { battleInfo =>
      findRats(battleInfo).foreach(attackOnRat)
    }
  }
  class RatAttackProcessor extends CommandProcessor {
    override def execute(json: JsValue, player: Player, settings: UISettings): cpResult = {
      // Pure function logic here
      cpResult("Rat attacked")
    }
  }
  private def attackOnRat(ratId: Long): Unit = {
    val command = Json.obj("__command" -> "targetAttack", "ratId" -> ratId)
    sendJson(command) // Directly send the command to the TCP server
  }

  def sendCommandToTcpServer(json: JsValue): Unit = {
    sendJson(json)
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

  override def preStart(): Unit = {
    super.preStart()
    connectToServer()
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
}

class PeriodicFunctionActor(jsonProcessorActor: ActorRef) extends Actor {
  import context.dispatcher
  private var reconnecting = false
  val serverAddress = InetAddress.getByName("127.0.0.1")
  val port = 9997
  var socket: Option[Socket] = None
  var out: Option[DataOutputStream] = None
  var in: Option[DataInputStream] = None

  override def receive: Receive = {
    case StartActors(settings) =>
      println("PeriodicFunctionActor received StartActors message.")
      if (socket.isEmpty || socket.exists(!_.isConnected)) {
        connectToServer()
      }
      startListening()
      initiateSendFunction("init")
  }
  override def preStart(): Unit = {
//    println("PeriodicFunctionActor starting...")
//    connectToServer()
//    initiateSendFunction("init") // Send 'init' command once on start
//    self ! "StartListening" // Start listening right away
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
    println("Recived a Json...")
    try {
      in.flatMap { i =>
        val lengthBytes = new Array[Byte](4)
        if (i.read(lengthBytes) == -1) {
          throw new EOFException("End of stream reached")
        }
        val length = ByteBuffer.wrap(lengthBytes).order(ByteOrder.LITTLE_ENDIAN).getInt
        if (length > 0) {
          val data = new Array[Byte](length)
          var totalRead = 0
          while (totalRead < length) {
            val read = i.read(data, totalRead, length - totalRead)
            if (read == -1) throw new EOFException("End of stream reached")
            totalRead += read
          }
          Some(Json.parse(data))
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
        println("No data received, continuing to listen...")
        None
      case e: IOException =>
        println(s"Failed to receive JSON: $e")
        reconnectToServer()
        None
    }
  }


  def receiveResponses(): Unit = {
    try {
      var continueReceiving = true
      while (continueReceiving) {
        val response = receiveJson()
        response match {
          case Some(json) => println("Received JSON: " + json)
          case None => continueReceiving = false
        }
      }
    } catch {
      case _: SocketTimeoutException => println("Read timed out, no more data.")
      case e: IOException =>
        println(s"Error receiving data: $e")
        reconnectToServer() // Reconnect if connection is lost
    }
  }


  def startListening(): Unit = {
    Future {
      while (true) {  // Continuous loop for listening
        receiveJson() match {
          case Some(json) => jsonProcessorActor ! JsonData(json)
          case None => // Keep listening
        }
      }
    }
  }

  def initiateSendFunction(commandName: String): Unit = {
    val jsonCommand = Json.obj("__command" -> commandName)
    if (!sendJson(jsonCommand)) {
      println("Failed to send JSON, attempting to reconnect for the next task...")
      reconnectToServer()
    }
  }

  def initiateSendAndReceiveFunction(): Unit = {
    try {
      println("Initiating function...")
      val command = Json.obj("__command" -> "init")
      sendJson(command)
      val response = receiveJson()
      response.foreach(json => jsonProcessorActor ! JsonData(json))
      println("Function initiated. Response received: " + response)
    } catch {
      case e: SocketException =>
        println(s"SocketException, reconnecting: ${e.getMessage}")
        connectToServer()
      // Optionally retry sending the command here
      case e: Exception =>
        println(s"Unexpected error: ${e.getMessage}")
    }
  }

  def sendData(commandName: String, arg1: Option[String] = None, arg2: Option[String] = None): Unit = {
    println("Initiating function...")

    val jsonCommand = (arg1, arg2) match {
      case (Some(a1), Some(a2)) => Json.obj("__command" -> commandName, "param1" -> a1, "param2" -> a2)
      case (Some(a1), None) => Json.obj("__command" -> commandName, "param1" -> a1)
      case _ => Json.obj("__command" -> commandName)
    }

    if (sendJson(jsonCommand)) {
      receiveResponses()
    } else {
      println("Failed to send JSON, attempting to reconnect for the next task...")
      reconnectToServer()
    }
  }

  // Updated sendAndReceiveData function
  def sendAndReceiveData(commandName: String, parameters: Map[String, JsValueWrapper]): Unit = {
    println("Initiating function...")
    val jsonCommand = Json.obj("__command" -> commandName) ++ Json.obj(parameters.toSeq: _*)
    if (sendJson(jsonCommand)) {
      receiveResponses()
    } else {
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



class ThirdProcessActor extends Actor {
  import context.dispatcher // Import the execution context for scheduling

  override def receive: Receive = {
    case StartActors(settings) =>
      println("ThirdProcessActor received StartActors message.")
      // Improved scheduling logic to avoid multiple schedules
      context.system.scheduler.scheduleWithFixedDelay(
        initialDelay = 1.second,
        delay = 5.seconds,
        receiver = self,
        message = "Initiate"
      )

    case "Initiate" =>
      initiateFunction()
  }

  // Define the function that will be called periodically
  def initiateFunction(): Unit = {
    println("TEMP PRINT")
    // Additional logic
  }

  override def preStart(): Unit = {
    // Schedule the `initiateFunction` to be called every 5 seconds
    context.system.scheduler.scheduleWithFixedDelay(
      initialDelay = 1.seconds,
      delay = 5.seconds,
      receiver = self,
      message = "Initiate"
    )
  }
}

object MainApp extends App {
  val system = ActorSystem("MySystem")

  // Keep references to actors, but don't start them immediately
  val jsonProcessorActorRef = system.actorOf(Props[JsonProcessorActor], "jsonProcessor")
  val periodicFunctionActorRef = system.actorOf(Props(classOf[PeriodicFunctionActor], jsonProcessorActorRef), "periodicFunctionActor")
  val thirdProcessActorRef = system.actorOf(Props[ThirdProcessActor], "thirdProcess")

  // UI AppActor setup
  val playerClassList: List[Player] = List(new Player("Player1"))
  val uiAppActorRef = system.actorOf(Props(new UIAppActor(playerClassList, jsonProcessorActorRef, periodicFunctionActorRef, thirdProcessActorRef)), "uiAppActor")

  println("Press ENTER to exit...")
  scala.io.StdIn.readLine()

  system.terminate()
}