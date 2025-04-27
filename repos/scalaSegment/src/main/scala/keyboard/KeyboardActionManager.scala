package keyboard

import cats.effect.{IO, Ref}
import cats.implicits.{catsSyntaxNestedFoldable, toFoldableOps}
import fs2.Stream
import mouse.MouseAction

import java.awt.Robot
import java.awt.event.KeyEvent
import scala.concurrent.duration._

sealed trait KeyboardAction {
  def priority: Int
}

case class PressKey(keyCode: Int) extends KeyboardAction {
  val priority = 1
}

object PressKey {
  def fromKeyString(key: String): PressKey = {
    val keyCode = KeyboardUtils.getKeyCodeForKey(key)
    PressKey(keyCode)
  }
}

case class TextType(text: String) extends KeyboardAction {
  val priority = 2
}


class KeyboardActionManager(
                             robot: Robot,
                             queueRef: Ref[IO, List[KeyboardAction]],
                             statusRef: Ref[IO, String],
                             taskInProgressRef: Ref[IO, Boolean]
                           ) {

  def enqueueBatches(batches: List[(String, List[KeyboardAction])]): IO[Unit] =
    batches.traverse_ { case (taskName, actions) =>
      IO.println(s"[$taskName] Enqueueing ${actions.size} keyboard actions") *>
        actions.traverse_( { action =>
          IO.println(s"[$taskName] ➡ $action") *> enqueue(action)
        })
    }

  def enqueue(action: KeyboardAction): IO[Unit] =
    for {
      _ <- IO.println(s"Enqueuing keyboard action: $action")
      _ <- queueRef.update(_ :+ action)
    } yield ()

  def enqueueTask(actions: List[KeyboardAction], allowOverlap: Boolean = false): IO[Unit] =
    for {
      inProgress <- taskInProgressRef.get
      _ <- if (inProgress && !allowOverlap) IO.println("🟡 Task already in progress, skipping...")
      else actions.traverse_(enqueue)
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
      if (keyCode == KeyEvent.VK_UNDEFINED) IO.println(s"Cannot type char: $char")
      else pressAndReleaseKey(keyCode)
    }.sequence_ *> pressAndReleaseKey(KeyEvent.VK_ENTER)


  private def executeAction(action: KeyboardAction): IO[Unit] = action match {
    case PressKey(code) =>
      pressAndReleaseKey(code) *> IO.sleep(50.millis)
    case TextType(text) =>
      typeString(text)
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