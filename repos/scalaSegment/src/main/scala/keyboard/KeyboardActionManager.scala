package keyboard

import akka.actor.ActorSystem.Settings
import cats.effect.{IO, Ref}
import cats.implicits.{catsSyntaxNestedFoldable, toFoldableOps}
import fs2.Stream
import keyboard.KeyboardUtils.{keyCodeFromString, pressArrowKey}
import mouse.MouseAction
import play.api.libs.json.Json
import utils.KafkaJsonPublisher
import utils.SettingsUtils.UISettings

import java.awt.Robot
import java.awt.event.KeyEvent
import scala.concurrent.duration._
import play.api.libs.json.{JsArray, JsObject, JsValue}
import java.util.UUID

sealed trait KeyboardAction {
  def priority: Int
  def actionMetaId: String
}

final case class TextType(text: String, actionMetaId: String) extends KeyboardAction {
  val priority = 3
}

final case class GeneralKey(code: Int, actionMetaId: String) extends KeyboardAction {
  val priority = 2
}

final case class DirectionalKey(direction: String, actionMetaId: String) extends KeyboardAction {
  val priority = 1
}

final case class PressCtrl(actionMetaId: String) extends KeyboardAction {
  val priority = 0
}

final case class ReleaseCtrl(actionMetaId: String) extends KeyboardAction {
  val priority = 0
}

final case class PressShift(actionMetaId: String) extends KeyboardAction {
  val priority = 0
}

final case class ReleaseShift(actionMetaId: String) extends KeyboardAction {
  val priority = 0
}

final case class HoldShiftFor(duration: FiniteDuration, actionMetaId: String) extends KeyboardAction {
  val priority = 1
}

final case class HoldCtrlFor(duration: FiniteDuration, actionMetaId: String) extends KeyboardAction {
  val priority = 1
}

class KeyboardActionManager(
                             robot: Robot,
                             queueRef: Ref[IO, List[KeyboardAction]],
                             statusRef: Ref[IO, String],
                             taskInProgressRef: Ref[IO, Boolean],
                             settingsRef: Ref[IO, UISettings],
                             kafkaPublisher: KafkaJsonPublisher
                           ) {

//  def fromString(input: String): KeyboardAction = {
//    val directionKeys = Set(
//      "MoveUp", "MoveDown", "MoveLeft", "MoveRight",
//      "MoveUpLeft", "MoveUpRight", "MoveDownLeft", "MoveDownRight"
//    )
//
//    if (directionKeys.contains(input)) {
//      DirectionalKey(input)
//    } else if (input.length == 1 || input.matches("^[a-zA-Z0-9]+$")) {
//      TextType(input)
//    } else {
//      val code = keyCodeFromString(input)
//      if (code != -1) GeneralKey(code) else TextType(input)
//    }
//  }



  def enqueueBatches(batches: List[(String, List[KeyboardAction])]): IO[Unit] =
    batches.traverse_ { case (taskName, actions) =>
      IO.println(s"[$taskName] Enqueueing ${actions.size} keyboard actions") *>
        actions.traverse_({ action =>
          IO.println(s"[$taskName] ➡ $action") *> enqueue(action)
        })
    }

  def enqueue(action: KeyboardAction): IO[Unit] =
    for {
      _ <- queueRef.update(_ :+ action)
      _ <- sendActionToKafka(action)
    } yield ()

  def enqueueTask(actions: List[KeyboardAction], allowOverlap: Boolean = false): IO[Unit] =
    for {
      inProgress <- taskInProgressRef.get
      _ <- if (inProgress && !allowOverlap) IO.unit else actions.traverse_(enqueue)
    } yield ()

  private def pressAndReleaseKey(keyCode: Int): IO[Unit] =
    IO.blocking {
      robot.keyPress(keyCode)
      Thread.sleep(50)
      robot.keyRelease(keyCode)
    }

  private def typeString(text: String): IO[Unit] =
    text.toList.map { char =>
      val keyCode = KeyEvent.getExtendedKeyCodeForChar(char.toInt)
      if (keyCode == KeyEvent.VK_UNDEFINED) IO.unit else pressAndReleaseKey(keyCode)
    }.sequence_ *> pressAndReleaseKey(KeyEvent.VK_ENTER)



  private def executeAction(action: KeyboardAction): IO[Unit] = action match {
    case DirectionalKey(direction, _) =>
      println(s"DirectionalKey - pressing $direction")
      pressArrowKey(robot, direction) *> IO.sleep(50.millis)

    case GeneralKey(code, _) =>
      println(s"GeneralKey - pressing $code")
      pressAndReleaseKey(code) *> IO.sleep(50.millis)

    case TextType(text, _) =>
      println(s"TextType - typing $text")
      typeString(text)

    case PressCtrl(_) =>
      println("Pressing Ctrl key")
      IO(robot.keyPress(KeyEvent.VK_CONTROL)) *> IO.sleep(20.millis)

    case ReleaseCtrl(_) =>
      println("Releasing Ctrl key")
      IO(robot.keyRelease(KeyEvent.VK_CONTROL)) *> IO.sleep(20.millis)

    case HoldCtrlFor(duration, _) =>
      println(s"Holding Ctrl for $duration")
      IO(robot.keyPress(KeyEvent.VK_CONTROL)) *> IO.sleep(duration) *> IO(robot.keyRelease(KeyEvent.VK_CONTROL))


  }




  def startProcessing: Stream[IO, Unit] =
    Stream.awakeEvery[IO](10.millis).evalMap { _ =>
      queueRef.modify {
        case head :: tail => (tail, Some(head))
        case Nil          => (Nil, None)
      }.flatMap {
        case Some(action) =>
          for {
            _         <- taskInProgressRef.set(true)
            _         <- executeAction(action)
            remaining <- queueRef.get
            _         <- if (remaining.isEmpty) taskInProgressRef.set(false) else IO.unit
          } yield ()
        case None =>
          taskInProgressRef.set(false)
      }
    }

  private def sendActionToKafka(action: KeyboardAction): IO[Unit] = {
    settingsRef.get.flatMap { settings =>
      val (actionType, metaId) = action match {
        case TextType(_, meta)            => ("type", meta)
        case GeneralKey(_, meta)          => ("key_press", meta)
        case DirectionalKey(_, meta)      => ("directional", meta)
        case PressCtrl(meta)              => ("ctrl_press", meta)
        case ReleaseCtrl(meta)            => ("ctrl_release", meta)
        case HoldCtrlFor(_, meta)         => ("ctrl_hold", meta)
      }

      val json = Json.obj(
        "device" -> "keyboard",
        "action" -> actionType,
        "x" -> "0",
        "y" -> "0",
        "metaGeneratedTimestamp" -> java.time.Instant.now().toString,
        "metaGeneratedDate" -> java.time.Instant.now().toString.take(10),
        "metaGeneratedId" -> metaId
      )

      val debugIO =
        if (settings.debugMode) IO {
          println(s"[KeyboardAction] Top-level keys: ${json.as[play.api.libs.json.JsObject].keys.mkString(", ")}")
          println(s"[KeyboardAction] JSON: $json")
          println(s"[KeyboardAction] shareDataMode=${settings.shareDataMode}")
        } else IO.unit

      val sendIO =
        if (settings.shareDataMode) IO {
          kafkaPublisher.sendActionData(json)
        } else IO.unit

      debugIO *> sendIO
    }
  }

}