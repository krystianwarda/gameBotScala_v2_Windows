import akka.actor.{Actor, ActorSystem, Cancellable, Props}
import play.api.libs.json.Json.JsValueWrapper

import java.io.EOFException
import java.net.SocketTimeoutException
import java.nio.{ByteBuffer, ByteOrder}
import java.util.concurrent.TimeUnit
import scala.concurrent.duration._
import scala.util.Random

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

class PeriodicFunctionActor extends Actor {
  import context.dispatcher
  private var reconnecting = false
  val serverAddress = InetAddress.getByName("127.0.0.1")
  val port = 9997
  var socket: Option[Socket] = None
  var out: Option[DataOutputStream] = None
  var in: Option[DataInputStream] = None

  override def preStart(): Unit = {
    println("PeriodicFunctionActor starting...")
    connectToServer() // Establish the connection on actor startup
    context.system.scheduler.scheduleWithFixedDelay(
      initialDelay = 0.seconds,
      delay = 5.seconds,
      receiver = self,
      message = "Initiate"
    )
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
    println("Sending JSON: " + json)
    out.flatMap { o =>
      val data = Json.stringify(json).getBytes("UTF-8")
      try {
        val lengthBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(data.length).array()
        o.write(lengthBytes)
        o.write(data)
        o.flush()
        println("JSON sent successfully.")
        Some(true) // Indicate success
      } catch {
        case e: IOException =>
          println(s"Failed to send JSON: $e")
          None // Indicate failure
      }
    }.getOrElse(false) // Return false if 'out' is not available
  }


  def receiveJson(): Option[JsValue] = {
    in.flatMap { i =>
      try {
        val lengthBytes = new Array[Byte](4)
        i.readFully(lengthBytes)
        val length = ByteBuffer.wrap(lengthBytes).order(ByteOrder.LITTLE_ENDIAN).getInt
        if (length > 0) {
          val data = new Array[Byte](length)
          i.readFully(data)
          Some(Json.parse(data))
        } else {
          None
        }
      } catch {
        case _: EOFException =>
          println("End of stream reached, connection closed by server.")
          None
        case e: IOException =>
          println(s"Failed to receive JSON: $e")
          None
      }
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

  def initiateSendFunction(functionName: String, arg1: Option[String] = None, arg2: Option[String] = None): Unit = {
    if (socket.exists(_.isClosed)) {
      if (!reconnecting) {
        println("Socket is closed, reconnecting before sending data...")
        reconnectToServer()
      }
    } else {
      sendData(functionName, arg1, arg2)
    }
  }

//  def initiateSendAndReceiveFunction(functionName: String, eventData: Option[JsValue] = None): Unit = {
//    if (socket.exists(_.isClosed)) {
//      if (!reconnecting) {
//        println("Socket is closed, reconnecting before sending data...")
//        reconnectToServer()
//      }
//    } else {
//      sendAndReceiveData(functionName, eventData)
//    }
//  }


  def initiateSendAndReceiveFunction(): Unit = {
    println("Initiating function...")
    val command = Json.obj("__command" -> "test", "asd" -> 978)
    sendJson(command)
    val response = receiveJson()
    println("Function initiated. Response received: " + response)
  }

 /* // Updated initiateFunction to use the new sendAndReceiveData
  def initiateSendAndReceiveFunction(): Unit = {
    if (socket.exists(_.isClosed)) {
      if (!reconnecting) {
        println("Socket is closed, reconnecting before sending data...")
        reconnectToServer()
      }
    } else {
      // Example usage
      sendAndReceiveData("test", Map("asd" -> 978))
    }
  }*/

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



  /*  def sendAndReceiveData(commandName: String, arg1: Option[String] = None, arg2: Option[String] = None): Unit = {
    println("Initiating function...")
    val command = Json.obj("__command" -> "test", "asd" -> 978)
    if (sendJson(command)) {
      receiveResponses()
    } else {
      println("Failed to send JSON, attempting to reconnect for the next task...")
      reconnectToServer()
    }
  }*/

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

  def receive: Receive = {
    case "Initiate" => initiateSendAndReceiveFunction()
  }

/*  def receive: Receive = {
    case FunctionCall(functionName, arg1, arg2) =>
      initiateSendFunction(functionName, arg1, arg2)

    case "Initiate" =>
      val shouldSendAndReceive = true // Replace with actual condition or flag
      if (shouldSendAndReceive) {
        val eventData = Json.obj(
          "from" -> "ScalaApp",
          "data" -> Json.obj("asd" -> 978)
        )
        sendAndReceiveData("test", Map("event" -> eventData))
      } else {
        initiateSendFunction("sayFunction", Option("Hello"), Option("3"))
      }
  }*/

  /*  def receive: Receive = {
      case FunctionCall(functionName, arg1, arg2) => initiateSendFunction(functionName, arg1, arg2)
      case "Initiate" =>
        initiateSendFunction("getGameData")
    }*/

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

  // Define the function that will be called periodically
  def initiateFunction(): Unit = {
    println("TEMP PRINT") // Log the message to the console
    // Add any additional logic for the function here
  }

  override def preStart(): Unit = {
    // Schedule the `initiateFunction` to be called every 5 seconds
    context.system.scheduler.scheduleWithFixedDelay(
      initialDelay = 0.seconds,
      delay = 5.seconds,
      receiver = self,
      message = "Initiate"
    )
  }

  def receive: Receive = {
    case "Initiate" => initiateFunction() // Call the function when the message is received
  }
}

object MainApp extends App {
  val system = ActorSystem("MySystem")

  // Starting actors...
  val periodicFunctionActor = system.actorOf(Props[PeriodicFunctionActor], "periodicFunctionActor")
  val thirdProcessActor = system.actorOf(Props[ThirdProcessActor], "thirdProcess")

  // Keep the application running until it receives an exit command
  println("Press ENTER to exit...")
  scala.io.StdIn.readLine()

  // Shutdown the actor system gracefully
  system.terminate()
}

