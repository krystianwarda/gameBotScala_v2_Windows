package mouse

import cats.effect.{IO, IOApp, Ref}
import fs2.Stream
import java.awt.{MouseInfo, Robot}
import java.awt.event.InputEvent
import scala.concurrent.duration._
import scala.util.Random

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

// Mouse Manager Class (Handles Task Execution)
class MouseActionManager(robot: Robot, queueRef: Ref[IO, List[MouseAction]], statusRef: Ref[IO, String]) {

  private def sortQueue(queue: List[MouseAction]): List[MouseAction] =
    queue.sortBy(_.priority)

  def enqueue(action: MouseAction): IO[Unit] =
    queueRef.update(queue => sortQueue(queue :+ action)) *> statusRef.set("busy")

  private def processNext: IO[Unit] =
    for {
      queue <- queueRef.get
      _ <- queue match {
        case head :: tail =>
          executeAction(head) *> queueRef.set(tail) *> IO.sleep(50.millis) *> processNext
        case Nil =>
          IO.sleep(2.seconds) *> statusRef.set("idle")
      }
    } yield ()

  private def executeAction(action: MouseAction): IO[Unit] = action match {
    case MoveMouse(x, y)         => moveMouse(x, y)
    case LeftButtonPress(x, y)   => moveTo(x, y) *> IO(robot.mousePress(InputEvent.BUTTON1_DOWN_MASK))
    case LeftButtonRelease(x, y) => moveTo(x, y) *> IO(robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK))
    case RightButtonPress(x, y)  => moveTo(x, y) *> IO(robot.mousePress(InputEvent.BUTTON3_DOWN_MASK))
    case RightButtonRelease(x, y)=> moveTo(x, y) *> IO(robot.mouseRelease(InputEvent.BUTTON3_DOWN_MASK))
    case DragMouse(x, y)         => IO(robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)) *> moveMouse(x, y) *> IO(robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK))
    case CrosshairMove(x, y)     => IO(robot.mousePress(InputEvent.BUTTON3_DOWN_MASK)) *> moveMouse(x, y) *> IO(robot.mouseRelease(InputEvent.BUTTON3_DOWN_MASK))
  }

  private def moveTo(x: Int, y: Int): IO[Unit] = IO(robot.mouseMove(x, y))

  private def moveMouse(targetX: Int, targetY: Int): IO[Unit] = IO {
    val startX = MouseInfo.getPointerInfo.getLocation.getX.toInt
    val startY = MouseInfo.getPointerInfo.getLocation.getY.toInt
    val steps = 100
    val random = new Random()
    val overshootFactor = if (random.nextDouble() < 0.15) 0.05 else 0.0
    val overshootX = targetX + ((targetX - startX) * overshootFactor).toInt
    val overshootY = targetY + ((targetY - startY) * overshootFactor).toInt

    for (i <- 1 to steps) {
      val progress = i.toDouble / steps
      val newX = startX + ((overshootX - startX) * progress).toInt
      val newY = startY + ((overshootY - startY) * progress).toInt
      robot.mouseMove(newX, newY)
      Thread.sleep((5 + (20 * (1 - progress))).toInt)
    }

    if (random.nextDouble() < 0.10) {
      val hoverStart = System.currentTimeMillis()
      while (System.currentTimeMillis() - hoverStart < 1000) {
        val hoverX = targetX + random.between(-5, 5)
        val hoverY = targetY + random.between(-5, 5)
        robot.mouseMove(hoverX, hoverY)
        Thread.sleep(50)
      }
    }

    robot.mouseMove(targetX, targetY)
  }

  def startProcessing: Stream[IO, Unit] =
    Stream.repeatEval(processNext).metered(100.millis)
}

object MainApp extends IOApp.Simple {
  override def run: IO[Unit] =
    for {
      queueRef <- Ref.of[IO, List[MouseAction]](List.empty)
      statusRef <- Ref.of[IO, String]("idle")
      robot <- IO(new Robot())
      manager = new MouseActionManager(robot, queueRef, statusRef)
      _ <- IO { GlobalMouseManager.instance = Some(manager) }

      arrowsX = 800
      arrowsY = 500

      _ <- manager.enqueue(MoveMouse(arrowsX, arrowsY))
      _ <- manager.enqueue(RightButtonPress(arrowsX, arrowsY))
      _ <- manager.enqueue(RightButtonRelease(arrowsX, arrowsY))
      _ <- manager.enqueue(MoveMouse(arrowsX, arrowsY))
      _ <- manager.enqueue(LeftButtonRelease(arrowsX, arrowsY))
      _ <- manager.enqueue(LeftButtonPress(arrowsX, arrowsY))

      _ <- IO(manager.startProcessing.compile.drain.unsafeRunAndForget())

    } yield ()
}
