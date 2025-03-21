package main.scala

import akka.actor.{Actor, ActorRef, ActorSystem, Cancellable, Props}
import akka.util.ByteString
import cats.effect.kernel.Ref
import cats.effect.{IO, IOApp}
import cats.effect.unsafe.implicits.global
import com.github.kwhat.jnativehook.GlobalScreen
import player.Player
import mouse.{ActionMouseManager, GlobalMouseManager, Mouse, MouseAction, MouseActionManager, MouseManagerApp, MouseMovementActor}
import keyboard.{ActionKeyboardManager, AutoResponderManager, KeyboardActor}
import play.api.libs.json._
import processing.JsonProcessorActor
import userUI.UIAppActor
import utils.{AlertSenderActor, EscapeKeyHandler, EscapeKeyListener, FunctionExecutorActor, InitialJsonProcessorActor, InitialRunActor, MainActor, MouseJiggler, PeriodicFunctionActor}

import java.awt.Robot
import scala.concurrent.duration._
import scala.io.StdIn



case class FunctionCall(functionName: String, arg1: Option[String] = None, arg2: Option[String] = None)

case class SendJsonCommand(json: JsValue)





import userUI.SettingsUtils.UISettings

class ThirdProcessActor extends Actor {
  import context.dispatcher

  override def receive: Receive = {
    case MainApp.StartActors(settings) =>
      println("ThirdProcessActor received StartActors message.")
      context.system.scheduler.scheduleWithFixedDelay(
        initialDelay = 1.second,
        delay = 5.seconds,
        receiver = self,
        message = "Initiate"
      )
    case "Initiate" =>
      initiateFunction()
  }

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

import cats.effect.unsafe.implicits.global

object MainApp extends App {
  val system = ActorSystem("MySystem")


  MouseJiggler.start()

  MouseManagerApp.start() // ✅ Now all logic is handled separately



  // Define case classes and objects as before
  val playerClassList: List[Player] = List(new Player("Player1"))
  case class StartActors(settings: UISettings)
  case class UpdateSettings(settings: UISettings)
  case class UpdatePauseStatus(isPaused: Boolean)
  case class JsonData(json: JsValue)
  case class BinaryData(data: ByteString)


  val keyboardActorRef: ActorRef = system.actorOf(Props[KeyboardActor], "keyboardActor")
  val actionStateManagerRef: ActorRef = system.actorOf(Props[ActionMouseManager], "actionStateManager")
  val actionKeyboardManagerRef: ActorRef = system.actorOf(Props(new ActionKeyboardManager(keyboardActorRef)), "actionKeyboardManager")
  val alertSenderActorRef: ActorRef = system.actorOf(Props[AlertSenderActor], "alertSender")

  lazy val mouseMovementActorRef: ActorRef = system.actorOf(Props(new MouseMovementActor(actionStateManagerRef, jsonProcessorActorRef)), "mouseMovementActor")
  val autoResponderManagerRef: ActorRef = system.actorOf(AutoResponderManager.props(keyboardActorRef, jsonProcessorActorRef), "autoResponderManager")
  lazy val jsonProcessorActorRef: ActorRef = system.actorOf(Props(new JsonProcessorActor(mouseMovementActorRef, actionStateManagerRef, actionKeyboardManagerRef)), "jsonProcessor")

  val keyListenerActorRef: ActorRef = system.actorOf(Props[EscapeKeyListener], "EscapeKeyListener")

  val functionExecutorActorRef: ActorRef = system.actorOf(Props[FunctionExecutorActor], "functionExecutorActor")
  val initialJsonProcessorActorRef: ActorRef = system.actorOf(Props[InitialJsonProcessorActor], "initialJsonProcessor")
  val initialRunActorRef: ActorRef = system.actorOf(Props(new InitialRunActor(initialJsonProcessorActorRef)), "initialRunActor")
  val mainActorRef: ActorRef = system.actorOf(Props[MainActor], "mainActor")
  val periodicFunctionActorRef: ActorRef = system.actorOf(Props(classOf[PeriodicFunctionActor], jsonProcessorActorRef), "periodicFunctionActor")
  val thirdProcessActorRef: ActorRef = system.actorOf(Props[ThirdProcessActor], "thirdProcess")
  val uiAppActorRef: ActorRef = system.actorOf(Props(new UIAppActor(playerClassList, jsonProcessorActorRef, periodicFunctionActorRef, thirdProcessActorRef, mainActorRef)), "uiAppActor")


  // Register global key listener
  GlobalScreen.registerNativeHook()
  GlobalScreen.addNativeKeyListener(new EscapeKeyHandler(keyListenerActorRef))

  println("Press ENTER to exit...")
  scala.io.StdIn.readLine()

  system.terminate()
}



//object MainApp extends App {
//
//  val system = ActorSystem("MySystem")
//
//  // Define case classes and objects as before
//
//  val playerClassList: List[Player] = List(new Player("Player1"))
//  case class StartActors(settings: UISettings)
//  case class JsonData(json: JsValue)
//
//  lazy val jsonProcessorActorRef = system.actorOf(Props(new JsonProcessorActor(mouseMovementActorRef, actionStateManagerRef, actionKeyboardManagerRef)), "jsonProcessor")
//
//  // Create the ActionStateManager without any parameters
//  val actionStateManagerRef = system.actorOf(Props[ActionStateManager], "actionStateManager")
//
//  // Create the MouseMovementActor, passing the ActionStateManager reference
//  val mouseMovementActorRef = system.actorOf(Props(new MouseMovementActor(actionStateManagerRef, jsonProcessorActorRef)), "mouseMovementActor")
//
//  // Create the KeyboardActor
//  val keyboardActorRef = system.actorOf(Props[KeyboardActor], "keyboardActor")
//
//  val autoResponderManagerRef = system.actorOf(AutoResponderManager.props(keyboardActorRef), "autoResponderManager")
//
//  // Create the ActionKeyboardManager, passing the KeyboardActor reference
//  val actionKeyboardManagerRef = system.actorOf(Props(new ActionKeyboardManager(keyboardActorRef)), "actionKeyboardManager")
//
//  // Update JsonProcessorActor creation to include the ActionKeyboardManager reference
////  val jsonProcessorActorRef = system.actorOf(Props(new JsonProcessorActor(mouseMovementActorRef, actionStateManagerRef, actionKeyboardManagerRef)), "jsonProcessor")
//  val functionExecutorActorRef = system.actorOf(Props[FunctionExecutorActor], "functionExecutorActor")
//
//  // Continue with the creation of other actors as before
//  val initialJsonProcessorActorRef = system.actorOf(Props[InitialJsonProcessorActor], "initialJsonProcessor")
//  val initialRunActorRef = system.actorOf(Props(new InitialRunActor(initialJsonProcessorActorRef)), "initialRunActor")
//  val mainActorRef = system.actorOf(Props[MainActor], "mainActor")
//  val periodicFunctionActorRef = system.actorOf(Props(classOf[PeriodicFunctionActor], jsonProcessorActorRef), "periodicFunctionActor")
//  val thirdProcessActorRef = system.actorOf(Props[ThirdProcessActor], "thirdProcess")
//  val uiAppActorRef = system.actorOf(Props(new UIAppActor(playerClassList, jsonProcessorActorRef, periodicFunctionActorRef, thirdProcessActorRef, mainActorRef)), "uiAppActor")
//
//  println("Press ENTER to exit...")
//  scala.io.StdIn.readLine()
//
//  system.terminate()
//}



