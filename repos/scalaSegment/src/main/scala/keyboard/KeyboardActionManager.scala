package keyboard

import cats.effect.{IO, Ref}
import cats.implicits.{catsSyntaxNestedFoldable, toFoldableOps}
import fs2.Stream
import keyboard.KeyboardUtils.{keyCodeFromString, pressArrowKey}
import mouse.MouseAction

import java.awt.Robot
import java.awt.event.KeyEvent
import scala.concurrent.duration._

sealed trait KeyboardAction {
  def priority: Int
}



case class TextType(text: String) extends KeyboardAction {
  val priority = 3
}
case class GeneralKey(code: Int) extends KeyboardAction {
  val priority = 2
}
case class DirectionalKey(direction: String) extends KeyboardAction {
  val priority = 1
}

case object PressCtrl extends KeyboardAction {
  val priority = 0
}
case object ReleaseCtrl extends KeyboardAction {
  val priority = 0
}

case class HoldCtrlFor(duration: FiniteDuration) extends KeyboardAction {
  val priority = 1
}


class KeyboardActionManager(
                             robot: Robot,
                             queueRef: Ref[IO, List[KeyboardAction]],
                             statusRef: Ref[IO, String],
                             taskInProgressRef: Ref[IO, Boolean]
                           ) {

  def fromString(input: String): KeyboardAction = {
    val directionKeys = Set(
      "MoveUp", "MoveDown", "MoveLeft", "MoveRight",
      "MoveUpLeft", "MoveUpRight", "MoveDownLeft", "MoveDownRight"
    )

    if (directionKeys.contains(input)) {
      DirectionalKey(input)
    } else if (input.length == 1 || input.matches("^[a-zA-Z0-9]+$")) {
      TextType(input)
    } else {
      val code = keyCodeFromString(input)
      if (code != -1) GeneralKey(code) else TextType(input)
    }
  }

  def enqueueBatches(batches: List[(String, List[KeyboardAction])]): IO[Unit] =
    batches.traverse_ { case (taskName, actions) =>
      IO.println(s"[$taskName] Enqueueing ${actions.size} keyboard actions") *>
        actions.traverse_( { action =>
          IO.println(s"[$taskName] ➡ $action") *> enqueue(action)
        })
    }

  def enqueue(action: KeyboardAction): IO[Unit] =
    queueRef.update(_ :+ action)

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
    case DirectionalKey(direction) =>
      println(s"DirectionalKey - pressing $direction")
      pressArrowKey(robot, direction) *> IO.sleep(50.millis)

    case GeneralKey(code) =>
      println(s"GeneralKey - pressing $code")
      pressAndReleaseKey(code) *> IO.sleep(50.millis)

    case TextType(text) =>
      println(s"TextType - typing $text")
      typeString(text)

    case PressCtrl =>
      println("Pressing Ctrl key")
      IO(robot.keyPress(KeyEvent.VK_CONTROL)) *> IO.sleep(20.millis)

    case ReleaseCtrl =>
      println("Releasing Ctrl key")
      IO(robot.keyRelease(KeyEvent.VK_CONTROL)) *> IO.sleep(20.millis)

    case HoldCtrlFor(duration) =>
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
}