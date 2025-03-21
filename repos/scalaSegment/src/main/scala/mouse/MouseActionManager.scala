package mouse

import cats.effect.{IO, IOApp, Ref}
import fs2.Stream
import cats.effect.unsafe.implicits.global
import cats.syntax.all._

import java.awt.{MouseInfo, Robot}
import java.awt.event.InputEvent
import scala.concurrent.duration._
import scala.util.Random
import cats.syntax.all._

import scala.math.{pow, sqrt}

// Mouse Actions with Priorities
sealed trait MouseAction {
  def priority: Int
  def x: Int
  def y: Int
}

case class MoveMouse(x: Int, y: Int) extends MouseAction { val priority = 3 }
case class LeftButtonPress(x: Int, y: Int) extends MouseAction { val priority = 2 }
case class LeftButtonRelease(x: Int, y: Int) extends MouseAction { val priority = 2 }
case class RightButtonPress(x: Int, y: Int) extends MouseAction { val priority = 2 }
case class RightButtonRelease(x: Int, y: Int) extends MouseAction { val priority = 2 }
case class DragMouse(x: Int, y: Int) extends MouseAction { val priority = 1 }
case class CrosshairMove(x: Int, y: Int) extends MouseAction { val priority = 1 }

// Global mouse manager reference (will be initialized in MainApp)
object GlobalMouseManager {
  var instance: Option[MouseActionManager] = None
}

class MouseActionManager(
                          robot: Robot,
                          queueRef: Ref[IO, List[MouseAction]],
                          statusRef: Ref[IO, String],
                          posRef: Ref[IO, (Int, Int)],
                          taskInProgressRef: Ref[IO, Boolean]
                        ) {

  private def sortQueue(queue: List[MouseAction]): List[MouseAction] =
    queue.sortBy(_.priority)

  def enqueue(action: MouseAction): IO[Unit] =
    for {
      _ <- IO(println(s"Enqueuing: $action"))
      _ <- queueRef.update(_ :+ action)
    } yield ()

  private def executeAction(action: MouseAction): IO[Unit] = for {
    _ <- IO(println(s"[EXECUTE] $action"))

    _ <- action match {
      case MoveMouse(x, y) =>
        moveMouse(x, y) *> IO.sleep(40.millis) *>
          queueRef.get.map(_.isEmpty).flatMap { isEmpty =>
            if (isEmpty) {
              IO(println("✅ Task completed.")) *> taskInProgressRef.set(false)
            } else IO.unit
          }

      case LeftButtonPress(_, _) =>
        IO(robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)) *> IO.sleep(10.millis)

      case LeftButtonRelease(_, _) =>
        IO(robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)) *> IO.sleep(10.millis)

      case RightButtonPress(_, _) =>
        IO(robot.mousePress(InputEvent.BUTTON3_DOWN_MASK)) *> IO.sleep(10.millis)

      case RightButtonRelease(_, _) =>
        IO(robot.mouseRelease(InputEvent.BUTTON3_DOWN_MASK)) *> IO.sleep(10.millis)

      case DragMouse(x, y) =>
        IO(robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)) *> moveMouse(x, y) *> IO(robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK))

      case CrosshairMove(x, y) =>
        IO(robot.mousePress(InputEvent.BUTTON3_DOWN_MASK)) *> moveMouse(x, y) *> IO(robot.mouseRelease(InputEvent.BUTTON3_DOWN_MASK))
    }
  } yield ()


  def enqueueTask(actions: List[MouseAction], allowOverlap: Boolean = false): IO[Unit] = {
    for {
      inProgress <- taskInProgressRef.get
      _ <- if (inProgress && !allowOverlap) IO(println("🟡 Task already in progress, skipping..."))
      else {
        taskInProgressRef.set(true) *>
          actions.traverse_(enqueue)
      }
    } yield ()
  }


  private def moveMouse(targetX: Int, targetY: Int): IO[Unit] = {
    val steps = 100

    posRef.get.flatMap { case (startX, startY) =>
      val deltaX = targetX - startX
      val deltaY = targetY - startY

      def easing(progress: Double): Double =
        1 - Math.pow(1 - progress, 4)

      def moveStep(step: Int): IO[Unit] = {
        val progress = step.toDouble / steps
        val eased = easing(progress)

        val newX = startX + (deltaX * eased).toInt
        val newY = startY + (deltaY * eased).toInt

        IO(robot.mouseMove(newX, newY)) *>
          posRef.set((newX, newY)) *>
          IO.sleep(5.millis)
      }

      (1 to steps).toList.traverse_(moveStep)
    }
  }

  def startProcessing: Stream[IO, Unit] = {
    Stream.awakeEvery[IO](10.millis).evalMap { _ =>
      queueRef.modify {
        case head :: tail => (tail, Some(head))
        case Nil          => (Nil, None)
      }.flatMap {
        case Some(action) =>
          for {
            _ <- executeAction(action)
            _ <- IO.sleep(10.millis)
            remaining <- queueRef.get
            _ <- if (remaining.isEmpty) {
              IO(println("✅ Task completed.")) *> taskInProgressRef.set(false)
            } else IO.unit
          } yield ()
        case None =>
          IO.unit
      }
    }
  }

}


object MouseManagerApp {
  def start(): Unit = {
    val robot = new Robot()
    val queueRef = Ref.unsafe[IO, List[MouseAction]](List.empty)
    val statusRef = Ref.unsafe[IO, String]("idle")

    // Start pos from real cursor
    val pos = MouseInfo.getPointerInfo.getLocation
    val posRef = Ref.unsafe[IO, (Int, Int)]((pos.getX.toInt, pos.getY.toInt))

    val taskInProgressRef = Ref.unsafe[IO, Boolean](false)

    val manager = new MouseActionManager(robot, queueRef, statusRef, posRef, taskInProgressRef)

    GlobalMouseManager.instance = Some(manager)

    manager.startProcessing.compile.drain.unsafeRunAndForget()

    println("✅ MouseActionManager started")
  }
}


// ✅ Updated: MainApp now correctly starts MouseManagerApp and enqueues actions
object MainApp extends IOApp.Simple {
  override def run: IO[Unit] =
    for {
      _ <- IO(MouseManagerApp.start()) // ✅ Start the mouse manager

      _ <- IO.sleep(1.second) // Wait a moment before enqueueing actions
      _ <- IO(println("Enqueueing actions..."))

      _ <- IO(GlobalMouseManager.instance.foreach { manager =>
        manager.enqueue(MoveMouse(800, 500)).unsafeRunAndForget()
        manager.enqueue(RightButtonPress(800, 500)).unsafeRunAndForget()
        manager.enqueue(RightButtonRelease(800, 500)).unsafeRunAndForget()
        manager.enqueue(LeftButtonPress(800, 500)).unsafeRunAndForget()
        manager.enqueue(LeftButtonRelease(800, 500)).unsafeRunAndForget()
      })

      _ <- IO.sleep(5.seconds) // Let the actions process before exit
    } yield ()
}