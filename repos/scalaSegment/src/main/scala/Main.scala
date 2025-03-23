package main.scala

import akka.actor.{Actor, ActorRef, ActorSystem, Cancellable, Props}
import akka.util.ByteString
import cats.effect.{IO, IOApp, Ref}
import cats.effect.unsafe.implicits.global
import com.github.kwhat.jnativehook.GlobalScreen
import player.Player
import mouse.{ActionMouseManager, GlobalMouseManager, Mouse, MouseAction, MouseActionManager, MouseManagerApp, MouseMovementActor}
import keyboard.{ActionKeyboardManager, AutoResponderManager, KeyboardActor}
import play.api.libs.json._
import processing.{FunctionalJsonConsumer, GameState, JsonProcessorActor}
import userUI.SettingsUtils._
import userUI.{FishingBot, UIAppActor}
import utils.{AlertSenderActor, EscapeKeyHandler, EscapeKeyListener, FunctionExecutorActor, InitialJsonProcessorActor, InitialRunActor, MainActor, MouseJiggler, PeriodicFunctionActor}

import java.awt.Robot
import scala.concurrent.duration._
import scala.io.StdIn



case class FunctionCall(functionName: String, arg1: Option[String] = None, arg2: Option[String] = None)

case class SendJsonCommand(json: JsValue)





import userUI.SettingsUtils.UISettings
//
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
//
//object MainApp extends App {
//  val system = ActorSystem("MySystem")
//
//
//  MouseJiggler.start()
//
//  MouseManagerApp.start()
//
//  // Default values
//  val defaultState = GameState()
//  val defaultSettings = UserSettings()
//
//  // Program setup
//  val jsonConsumerIO: IO[FunctionalJsonConsumer] = for {
//    stateRef <- Ref.of[IO, GameState](defaultState)
//    settingsRef <- Ref.of[IO, UserSettings](defaultSettings)
//    mouseManager = new MouseActionManager(new java.awt.Robot(), MouseManagerApp.queueRef, MouseManagerApp.statusRef, MouseManagerApp.taskInProgressRef)
//    consumer = new FunctionalJsonConsumer(stateRef, settingsRef, mouseManager)
//  } yield consumer
//
//  // Define case classes and objects as before
//  val playerClassList: List[Player] = List(new Player("Player1"))
//  case class StartActors(settings: UISettings)
//  case class UpdateSettings(settings: UISettings)
//  case class UpdatePauseStatus(isPaused: Boolean)
//  case class JsonData(json: JsValue)
//  case class BinaryData(data: ByteString)
//
//
//  val keyboardActorRef: ActorRef = system.actorOf(Props[KeyboardActor], "keyboardActor")
//  val actionStateManagerRef: ActorRef = system.actorOf(Props[ActionMouseManager], "actionStateManager")
//  val actionKeyboardManagerRef: ActorRef = system.actorOf(Props(new ActionKeyboardManager(keyboardActorRef)), "actionKeyboardManager")
//  val alertSenderActorRef: ActorRef = system.actorOf(Props[AlertSenderActor], "alertSender")
//
//  lazy val mouseMovementActorRef: ActorRef = system.actorOf(Props(new MouseMovementActor(actionStateManagerRef, jsonProcessorActorRef)), "mouseMovementActor")
//  val autoResponderManagerRef: ActorRef = system.actorOf(AutoResponderManager.props(keyboardActorRef, jsonProcessorActorRef), "autoResponderManager")
//  lazy val jsonProcessorActorRef: ActorRef = system.actorOf(Props(new JsonProcessorActor(mouseMovementActorRef, actionStateManagerRef, actionKeyboardManagerRef)), "jsonProcessor")
//
//  val keyListenerActorRef: ActorRef = system.actorOf(Props[EscapeKeyListener], "EscapeKeyListener")
//
//  val functionExecutorActorRef: ActorRef = system.actorOf(Props[FunctionExecutorActor], "functionExecutorActor")
//  val initialJsonProcessorActorRef: ActorRef = system.actorOf(Props[InitialJsonProcessorActor], "initialJsonProcessor")
//  val initialRunActorRef: ActorRef = system.actorOf(Props(new InitialRunActor(initialJsonProcessorActorRef)), "initialRunActor")
//  val mainActorRef: ActorRef = system.actorOf(Props[MainActor], "mainActor")
//  val periodicFunctionActorRef: ActorRef = system.actorOf(Props(classOf[PeriodicFunctionActor], jsonProcessorActorRef), "periodicFunctionActor")
//  val thirdProcessActorRef: ActorRef = system.actorOf(Props[ThirdProcessActor], "thirdProcess")
//  val uiAppActorRef: ActorRef = system.actorOf(Props(new UIAppActor(playerClassList, jsonProcessorActorRef, periodicFunctionActorRef, thirdProcessActorRef, mainActorRef)), "uiAppActor")
//
//
//  // Register global key listener
//  GlobalScreen.registerNativeHook()
//  GlobalScreen.addNativeKeyListener(new EscapeKeyHandler(keyListenerActorRef))
//
//  println("Press ENTER to exit...")
//  scala.io.StdIn.readLine()
//
//  system.terminate()
//}




object MainApp extends IOApp.Simple {


  // Exposed top-level ActorRefs
  var keyboardActorRef: ActorRef = _
  var actionStateManagerRef: ActorRef = _
  var actionKeyboardManagerRef: ActorRef = _
  var alertSenderActorRef: ActorRef = _
  var jsonProcessorActorRef: ActorRef = _
  var mouseMovementActorRef: ActorRef = _
  var autoResponderManagerRef: ActorRef = _
  var keyListenerActorRef: ActorRef = _
  var functionExecutorActorRef: ActorRef = _
  var initialJsonProcessorActorRef: ActorRef = _
  var initialRunActorRef: ActorRef = _
  var mainActorRef: ActorRef = _
  var periodicFunctionActorRef: ActorRef = _
  var thirdProcessActorRef: ActorRef = _
  var uiAppActorRef: ActorRef = _

  // Define case classes and objects as before
  val playerClassList: List[Player] = List(new Player("Player1"))
  case class StartActors(settings: UISettings)
  case class UpdateSettings(settings: UISettings)
  case class UpdatePauseStatus(isPaused: Boolean)
  case class JsonData(json: JsValue)
  case class BinaryData(data: ByteString)

  def createMouseActionManager(robot: Robot): IO[MouseActionManager] = for {
    queueRef <- Ref.of[IO, List[MouseAction]](List.empty)
    statusRef <- Ref.of[IO, String]("idle")
    taskInProgressRef <- Ref.of[IO, Boolean](false)
    wasInProgressRef <- Ref.of[IO, Boolean](false) // <-- Add this
  } yield new MouseActionManager(robot, queueRef, statusRef, taskInProgressRef, wasInProgressRef)


  override def run: IO[Unit] = for {
    _ <- IO(MouseJiggler.start())

    robot <- IO(new Robot()) // ✅ valid
    mouseManager <- createMouseActionManager(robot)
    stateRef <- Ref.of[IO, GameState](GameState())
    val defaultSettings = UISettings(
      healingSettings = HealingSettings(),
      runeMakingSettings = RuneMakingSettings(),
      hotkeysSettings = HotkeysSettings(),
      guardianSettings = GuardianSettings(),
      fishingSettings = FishingSettings(),
      autoResponderSettings = AutoResponderSettings(),
      trainingSettings = TrainingSettings(),
      mouseMovements = true,
      caveBotSettings = CaveBotSettings(),
      autoLootSettings = AutoLootSettings(),
      autoTargetSettings = AutoTargetSettings(),
      teamHuntSettings = TeamHuntSettings()
    )
    settingsRef <- Ref.of[IO, UISettings](defaultSettings)
    val fishingBot = new FishingBot(uiAppActorRef, jsonProcessorActorRef, settingsRef)

    jsonConsumer = new FunctionalJsonConsumer(stateRef, settingsRef, mouseManager)

    _ <- IO {
      GlobalMouseManager.instance = Some(mouseManager)
      println("✅ MouseActionManager instance set")
      mouseManager.startProcessing.compile.drain.unsafeRunAndForget()
      println("✅ MouseActionManager started")
    }

    // pass jsonConsumer to actor here
    _ <- IO {
      val system = ActorSystem("MySystem")




      keyboardActorRef = system.actorOf(Props[KeyboardActor], "keyboardActor")
      actionStateManagerRef = system.actorOf(Props[ActionMouseManager], "actionStateManager")
      actionKeyboardManagerRef = system.actorOf(Props(new ActionKeyboardManager(keyboardActorRef)), "actionKeyboardManager")
      alertSenderActorRef = system.actorOf(Props[AlertSenderActor], "alertSender")

      // Use lazy val in correct order
      jsonProcessorActorRef = system.actorOf(Props(new JsonProcessorActor(mouseMovementActorRef, actionStateManagerRef, actionKeyboardManagerRef)), "jsonProcessor")
      mouseMovementActorRef = system.actorOf(Props(new MouseMovementActor(actionStateManagerRef, jsonProcessorActorRef)), "mouseMovementActor")

      autoResponderManagerRef = system.actorOf(AutoResponderManager.props(keyboardActorRef, jsonProcessorActorRef), "autoResponderManager")
      keyListenerActorRef = system.actorOf(Props[EscapeKeyListener], "EscapeKeyListener")
      functionExecutorActorRef = system.actorOf(Props[FunctionExecutorActor], "functionExecutorActor")
      initialJsonProcessorActorRef = system.actorOf(Props[InitialJsonProcessorActor], "initialJsonProcessor")
      initialRunActorRef = system.actorOf(Props(new InitialRunActor(initialJsonProcessorActorRef)), "initialRunActor")
      mainActorRef = system.actorOf(Props[MainActor], "mainActor")
      periodicFunctionActorRef = system.actorOf(Props(new PeriodicFunctionActor(jsonProcessorActorRef, jsonConsumer)), "periodicFunctionActor")
      thirdProcessActorRef = system.actorOf(Props[ThirdProcessActor], "thirdProcess")
      uiAppActorRef = system.actorOf(Props(new UIAppActor(playerClassList, jsonProcessorActorRef, periodicFunctionActorRef, thirdProcessActorRef, mainActorRef, settingsRef)), "uiAppActor")

      // whatever else...
      println("Press ENTER to exit...")
      scala.io.StdIn.readLine()
      system.terminate()
    }
  } yield ()

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



