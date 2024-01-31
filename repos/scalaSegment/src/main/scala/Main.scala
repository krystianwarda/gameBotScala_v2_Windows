package main.scala

//import MainApp.periodicFunctionActor
import akka.actor.{Actor, ActorRef, ActorSystem, Cancellable, Props}
import play.api.libs.json.Json.JsValueWrapper
import player.Player
import mouse.{Mouse, MouseMovementActor}
import play.api.libs.json._
import processing.JsonProcessorActor
import userUI.UIAppActor
import utils.{InitialJsonProcessorActor, MainActor, InitialRunActor, PeriodicFunctionActor}

import java.awt.Robot
import java.io.EOFException
import java.net.{ServerSocket, SocketException, SocketTimeoutException}
import java.nio.{ByteBuffer, ByteOrder}
import java.util.concurrent.TimeUnit
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.io.BufferedSource
import scala.util.{Random, Try}
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

case class SendJsonCommand(json: JsValue)





import userUI.SettingsUtils.UISettings

class ThirdProcessActor extends Actor {
  import context.dispatcher // Import the execution context for scheduling

  override def receive: Receive = {
    case MainApp.StartActors(settings) =>
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
  case class StartActors(settings: UISettings)
  case class JsonData(json: JsValue)
  val system = ActorSystem("MySystem")
  // Create a placeholder for MouseMovementActor
  val mouseMovementActorRef = system.deadLetters
  val initialJsonProcessorActorRef = system.actorOf(Props[InitialJsonProcessorActor], "initialJsonProcessor")
  val initialRunActorRef = system.actorOf(Props(new InitialRunActor(initialJsonProcessorActorRef)), "initialRunActor")

  // Create MainActor
  val mainActorRef = system.actorOf(Props[MainActor], "mainActor")

  // Create JsonProcessorActor with the placeholder reference
  val jsonProcessorActorRef = system.actorOf(Props(new JsonProcessorActor(mouseMovementActorRef)), "jsonProcessor")

  val periodicFunctionActorRef = system.actorOf(Props(classOf[PeriodicFunctionActor], jsonProcessorActorRef), "periodicFunctionActor")
  val thirdProcessActorRef = system.actorOf(Props[ThirdProcessActor], "thirdProcess")

  // UI AppActor setup
  val playerClassList: List[Player] = List(new Player("Player1"))
  val uiAppActorRef = system.actorOf(Props(new UIAppActor(playerClassList, jsonProcessorActorRef, periodicFunctionActorRef, thirdProcessActorRef, mainActorRef)), "uiAppActor")

  println("Press ENTER to exit...")
  scala.io.StdIn.readLine()

  system.terminate()
}
