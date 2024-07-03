package utils

import akka.actor.Actor

import java.io.{DataInputStream, DataOutputStream, IOException}
import java.net.{InetAddress, Socket}
import play.api.libs.json.{JsValue, Json}

import java.nio.{ByteBuffer, ByteOrder}

case class ExecuteFunction(funcIdentifier: String)

class FunctionExecutorActor extends Actor {
  println("FunctionExecutorActor created.")
  val serverAddress = InetAddress.getByName("127.0.0.1")
  val port = 9997

  var socket: Option[Socket] = None
  var out: Option[DataOutputStream] = None
  var in: Option[DataInputStream] = None

  override def preStart(): Unit = {
    connectToServer()
  }

  def receive: Receive = {
    case ExecuteFunction(funcIdentifier) =>
      println(s"Received ExecuteFunction request: $funcIdentifier")
      val jsonCommand = Json.obj("__command" -> funcIdentifier).toString()
      if (sendJson(jsonCommand)) {
        receiveJsonResponse().foreach(json => sender() ! json)
      } else {
        println(s"Failed to execute function: $funcIdentifier")
        sender() ! Json.obj("error" -> "Function execution failed or no response.")
      }
  }

  private def connectToServer(): Unit = {
    try {
      println("Connecting to server...")
      val newSocket = new Socket(serverAddress, port)
      socket = Some(newSocket)
      out = Some(new DataOutputStream(newSocket.getOutputStream))
      in = Some(new DataInputStream(newSocket.getInputStream))
      println("Connection established.")
    } catch {
      case e: IOException =>
        println(s"Connection failed: $e")
        e.printStackTrace()
    }
  }

  def sendJson(jsonCommand: String): Boolean = {
    out.flatMap { dataOutputStream =>
      try {
        println(s"Sending JSON command: $jsonCommand")
        val data = jsonCommand.getBytes("UTF-8")
        val lengthBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(data.length).array()
        dataOutputStream.write(lengthBytes)
        dataOutputStream.write(data)
        dataOutputStream.flush()
        println("JSON command sent successfully.")
        Some(true)
      } catch {
        case e: IOException =>
          println(s"IOException during sending JSON command: $e")
          e.printStackTrace()
          None
      }
    }.getOrElse(false)
  }

  private def receiveJsonResponse(): Option[JsValue] = {
    in.flatMap { dataInputStream =>
      try {
        val lengthBytes = new Array[Byte](4)
        dataInputStream.readFully(lengthBytes)
        val length = ByteBuffer.wrap(lengthBytes).order(ByteOrder.LITTLE_ENDIAN).getInt
        if (length > 0) {
          val data = new Array[Byte](length)
          dataInputStream.readFully(data)
          println("JSON response received.")
          Some(Json.parse(data))
        } else {
          println("Received empty response.")
          None
        }
      } catch {
        case e: IOException =>
          println(s"Failed to receive JSON response: $e")
          e.printStackTrace()
          None
      }
    }
  }

  override def postStop(): Unit = {
    println("FunctionExecutorActor stopping...")
    try {
      socket.foreach(_.close())
      out.foreach(_.close())
      in.foreach(_.close())
    } catch {
      case e: IOException => println(s"Failed to close connections: $e")
    }
  }
}