package main.scala

import akka.actor.{Actor, ActorRef, ActorSystem, Cancellable, Props}
import akka.util.ByteString
import cats.effect.{IO, IOApp, Ref}
import cats.effect.unsafe.implicits.global
import com.github.kwhat.jnativehook.GlobalScreen
import player.Player
import mouse.{GlobalMouseManager, MouseAction, MouseActionManager, MouseUtils}
import keyboard.{KeyboardAction, KeyboardActionManager}
import play.api.libs.json._
import processing.FunctionalJsonConsumer
import utils.SettingsUtils._
import userUI.{AutoHealBot, FishingBot, UIAppActor}
import utils.{AlertSenderActor, EscapeKeyHandler, EscapeKeyListener, FunctionExecutorActor, GameState, InitialJsonProcessorActor, InitialRunActor, KafkaConfigLoader, KafkaJsonPublisher, MouseJiggler, PeriodicFunctionActor, SettingsUtils}

import java.awt.Robot
import scala.concurrent.duration._
import scala.io.StdIn
import cats.effect.unsafe.IORuntime
import cats.effect.unsafe.IORuntime.{global => defaultRuntime}
import com.github.kwhat.jnativehook.keyboard.{NativeKeyAdapter, NativeKeyEvent}

import java.util.concurrent.CountDownLatch


case class FunctionCall(functionName: String, arg1: Option[String] = None, arg2: Option[String] = None)

case class SendJsonCommand(json: JsValue)

import com.typesafe.config.ConfigFactory





import utils.SettingsUtils.UISettings
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

object MouseManagerApp {
  def start(settingsRef: Ref[IO, UISettings], kafkaPublisher: KafkaJsonPublisher): Unit = {
    System.setProperty("java.awt.headless", "false")
    val robot = new Robot()
    val queueRef = Ref.unsafe[IO, List[MouseAction]](List.empty)
    val statusRef = Ref.unsafe[IO, String]("idle")
    val taskInProgressRef = Ref.unsafe[IO, Boolean](false)
    val wasInProgressRef = Ref.unsafe[IO, Boolean](false)

    val manager = new MouseActionManager(
      robot,
      queueRef,
      statusRef,
      taskInProgressRef,
      wasInProgressRef,
      settingsRef,
      kafkaPublisher
    )

    GlobalMouseManager.instance = Some(manager)

    println("✅ MouseActionManager instance set")

    manager.startProcessing.compile.drain.unsafeRunAndForget()
    println("✅ MouseActionManager started")
  }
}


object MainApp extends IOApp.Simple {


  val config = ConfigFactory.parseString("""
    akka {
      log-dead-letters = off
      log-dead-letters-during-shutdown = off

      actor {
        default-dispatcher {
          type = "Dispatcher"
          executor = "fork-join-executor"
          fork-join-executor {
            parallelism-min = 21228991
            parallelism-factor = 2.0
            parallelism-max = 10
          }
        }
      }
    }
  """)

  implicit val ioRuntime: IORuntime = defaultRuntime

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

  def createMouseActionManager(
                                robot: Robot,
                                settingsRef: Ref[IO, UISettings],
                                kafkaPublisher: KafkaJsonPublisher
                              ): IO[MouseActionManager] = for {
    queueRef <- Ref.of[IO, List[MouseAction]](List.empty)
    statusRef <- Ref.of[IO, String]("idle")
    taskInProgressRef <- Ref.of[IO, Boolean](false)
    wasInProgressRef <- Ref.of[IO, Boolean](false)
  } yield new MouseActionManager(
    robot,
    queueRef,
    statusRef,
    taskInProgressRef,
    wasInProgressRef,
    settingsRef,
    kafkaPublisher
  )

  def createKeyboardActionManager(
                                   robot: Robot,
                                   settingsRef: Ref[IO, UISettings],
                                   kafkaPublisher: KafkaJsonPublisher
                                 ): IO[KeyboardActionManager] = for {
    queueRef <- Ref.of[IO, List[KeyboardAction]](List.empty)
    statusRef <- Ref.of[IO, String]("idle")
    taskInProgressRef <- Ref.of[IO, Boolean](false)
  } yield new KeyboardActionManager(
    robot,
    queueRef,
    statusRef,
    taskInProgressRef,
    settingsRef,
    kafkaPublisher
  )




  override def run: IO[Unit] = {

    var escListener: NativeKeyAdapter = null
    System.setProperty("java.awt.headless", "false")

    def cleanup(system: ActorSystem,
                mouseFiber: cats.effect.Fiber[IO, Throwable, Unit],
                keyFiber: cats.effect.Fiber[IO, Throwable, Unit]): IO[Unit] =
      IO.delay {
        try if (escListener != null) GlobalScreen.removeNativeKeyListener(escListener) catch { case _: Throwable => () }
        try GlobalScreen.unregisterNativeHook() catch { case _: Throwable => () }
      } *>
        mouseFiber.cancel.attempt.void *>
        keyFiber.cancel.attempt.void *>
        IO(MouseJiggler.stop()).attempt.void *>
        IO { GlobalMouseManager.instance = None } *>
        IO.fromFuture(IO(system.terminate())).void

    for {
      settingsRef <- Ref.of[IO, UISettings](SettingsUtils.defaultUISettings)

      val kafkaConfig = KafkaConfigLoader.loadFromFile("C:\\kafka-creds.json")
      val kafkaPublisher = new KafkaJsonPublisher(
        bootstrapServers = kafkaConfig.bootstrap_servers,
        gameDataTopic = kafkaConfig.topic,
        actionTopic = kafkaConfig.actionTopic,
        username = kafkaConfig.kafka_api_key,
        password = kafkaConfig.kafka_api_secret
      )

      _ <- IO(MouseJiggler.start())
      robot <- IO(new Robot())
      mouseManager <- createMouseActionManager(robot, settingsRef, kafkaPublisher)
      keyboardManager <- createKeyboardActionManager(robot, settingsRef, kafkaPublisher)

      _ <- IO {
        GlobalMouseManager.instance = Some(mouseManager)
        println("✅ MouseActionManager instance set")
      }

      mouseFiber <- mouseManager.startProcessing.compile.drain.start
      keyFiber <- keyboardManager.startProcessing.compile.drain.start
      system <- IO(ActorSystem("MySystem", config))

      // Initialize your actors as before, but DO NOT terminate the system here
      _ <- IO {
        alertSenderActorRef = system.actorOf(Props[AlertSenderActor], "alertSender")
        keyListenerActorRef = system.actorOf(Props[EscapeKeyListener], "EscapeKeyListener")
        functionExecutorActorRef = system.actorOf(Props[FunctionExecutorActor], "functionExecutorActor")
        initialJsonProcessorActorRef = system.actorOf(Props[InitialJsonProcessorActor], "initialJsonProcessor")
        initialRunActorRef = system.actorOf(Props(new InitialRunActor(initialJsonProcessorActorRef)), "initialRunActor")


        periodicFunctionActorRef = system.actorOf(Props(new PeriodicFunctionActor(
          jsonProcessorActorRef,
          new FunctionalJsonConsumer(
            Ref.unsafe[IO, GameState](GameState()),
            settingsRef,
            mouseManager,
            keyboardManager,
            kafkaPublisher
          )
        )), "periodicFunctionActor")

        thirdProcessActorRef = system.actorOf(Props[ThirdProcessActor], "thirdProcess")
        uiAppActorRef = system.actorOf(Props(new UIAppActor(
          playerClassList,
          jsonProcessorActorRef,
          periodicFunctionActorRef,
          thirdProcessActorRef,
          mainActorRef,
          settingsRef // same shared ref
        )), "uiAppActor")
      }

      // Register native hook and add listeners
      _ <- IO {
        GlobalScreen.registerNativeHook()
        GlobalScreen.addNativeKeyListener(new EscapeKeyHandler(keyListenerActorRef))
      }

      // Latch to keep the app alive and allow clean shutdown on ESC
      latch <- IO(new CountDownLatch(1))
      _ <- IO {
//        GlobalScreen.addNativeKeyListener(new EscapeKeyHandler(keyListenerActorRef))
//        GlobalScreen.addNativeKeyListener(escListener)
        println("Press ESC to exit...")
      }

      // ensure cleanup runs and then exit to stop AWT non-daemon threads
      _ <- IO.blocking(latch.await())
        .guarantee(cleanup(system, mouseFiber, keyFiber) *> IO.sleep(200.millis) *> IO(System.exit(0)))




    } yield ()
  }

}

