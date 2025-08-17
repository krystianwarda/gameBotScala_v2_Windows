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
                          taskInProgressRef: Ref[IO, Boolean],
                          wasInProgressRef: Ref[IO, Boolean]   // <-- add this
                        ) {

  private def sortQueue(queue: List[MouseAction]): List[MouseAction] =
    queue.sortBy(_.priority)

  def enqueueBatches(batches: List[(String, List[MouseAction])]): IO[Unit] =
    batches.traverse_ { case (taskName, actions) =>
      IO.println(s"[$taskName] Enqueueing ${actions.size} mouse actions") *>
        actions.traverse_( { action =>
          IO.println(s"[$taskName] ➡ $action") *> enqueue(action)
        })
    }

  def enqueue(action: MouseAction): IO[Unit] =
    for {
      _ <- IO(println(s"Enqueuing: $action"))
      _ <- queueRef.update(_ :+ action)
    } yield ()

  private def executeAction(action: MouseAction): IO[Unit] = for {
    _ <- IO(println(s"[EXECUTE] $action"))

    _ <- action match {
      case MoveMouse(x, y) =>
        IO(println(s"🔧 Actually calling robot.mouseMove($x, $y)")) *>
          moveMouse(x, y) *> IO.sleep(40.millis)

      case LeftButtonPress(_, _) =>
        IO(robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)) *> IO.sleep(100.millis)

      case LeftButtonRelease(_, _) =>
        IO(robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)) *> IO.sleep(100.millis)

      case RightButtonPress(_, _) =>
        IO(robot.mousePress(InputEvent.BUTTON3_DOWN_MASK)) *> IO.sleep(100.millis)

      case RightButtonRelease(_, _) =>
        IO(robot.mouseRelease(InputEvent.BUTTON3_DOWN_MASK)) *> IO.sleep(100.millis)

      case DragMouse(x, y) =>
        IO(robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)) *> moveMouse(x, y) *> IO(robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK))

      case CrosshairMove(x, y) =>
        IO(robot.mousePress(InputEvent.BUTTON3_DOWN_MASK)) *> moveMouse(x, y) *> IO(robot.mouseRelease(InputEvent.BUTTON3_DOWN_MASK))
    }
  } yield ()


  def enqueueTask(actions: List[MouseAction], allowOverlap: Boolean = false): IO[Unit] = {
    for {
      inProgress <- taskInProgressRef.get
      _ <- if (inProgress && !allowOverlap)
        IO(println("🟡 Task already in progress, skipping..."))
      else actions.traverse_(enqueue)
    } yield ()
  }


  def generateIntermediatePoints(startX: Int, startY: Int, endX: Int, endY: Int): List[(Int, Int)] = {
    val numPoints = 4 + Random.nextInt(3) // 4–6 breakpoints

    val dx = endX - startX
    val dy = endY - startY

    val perpX = -dy
    val perpY = dx
    val length = math.sqrt(perpX * perpX + perpY * perpY)
    val normX = perpX / length
    val normY = perpY / length

    val arcDirection = if (Random.nextBoolean()) 1 else -1

    (1 to numPoints).map { i =>
      val t = i.toDouble / (numPoints + 1)
      val baseX = startX + (dx * t).toInt
      val baseY = startY + (dy * t).toInt

      val baseOffset = 5 + Random.nextInt(10)        // original 5–14 px
      val multiplier = 1.0 + Random.nextDouble() * 0.8 // 1.0–1.5
      val offset = (baseOffset * multiplier).toInt   // 5–21 px approx.

      val offsetX = (normX * offset * arcDirection).toInt
      val offsetY = (normY * offset * arcDirection).toInt

      (baseX + offsetX, baseY + offsetY)
    }.toList
  }


  def moveSegment(startX: Int, startY: Int, targetX: Int, targetY: Int): IO[Unit] = {
    val steps = 60
    val deltaX = targetX - startX
    val deltaY = targetY - startY

    def easing(progress: Double): Double = 1 - math.pow(1 - progress, 4)

    (1 to steps).toList.traverse_ { i =>
      val progress = i.toDouble / steps
      val eased = easing(progress)

      val newX = startX + (deltaX * eased).toInt
      val newY = startY + (deltaY * eased).toInt

      IO.blocking {
        val current = MouseInfo.getPointerInfo.getLocation
        val correctedX = (current.getX + newX) / 2
        val correctedY = (current.getY + newY) / 2
        robot.mouseMove(correctedX.toInt, correctedY.toInt)
      } *> IO.sleep(2.millis) // More fluid6
    }
  }

  def moveMouse(targetX: Int, targetY: Int): IO[Unit] = {
    IO.blocking(MouseInfo.getPointerInfo.getLocation).flatMap { pos =>
      val startX = pos.getX.toInt
      val startY = pos.getY.toInt

      val intermediatePoints = generateIntermediatePoints(startX, startY, targetX, targetY)
      var path = List((startX, startY)) ++ intermediatePoints

      // 30% chance to overshoot
      if (Random.nextDouble() < 0.3) {
        val overshootLength = 20 + Random.nextInt(30) // 20–50 px


        val dx = targetX - startX
        val dy = targetY - startY
        val length = math.sqrt(dx * dx + dy * dy)
        val overshootX = targetX + ((dx / length) * overshootLength).toInt
        val overshootY = targetY + ((dy / length) * overshootLength).toInt

        path :+= (overshootX, overshootY) // go slightly past target
      }

      path :+= (targetX, targetY) // always end at the actual target

      val totalSteps = 20 // Faster movement

      val interpolatedPath = (1 to totalSteps).map { i =>
        val t = i.toDouble / totalSteps

        // Slow down only at the final 10%
        val easedT =
          if (t < 0.9) t * 1.15
          else 0.9 + 0.1 * (1 - math.pow(1 - ((t - 0.9) / 0.1), 2))

        val segment = (easedT * (path.length - 1)).toInt
        val localT = (easedT * (path.length - 1)) % 1

        val (fromX, fromY) = path(segment)
        val (toX, toY) = path(math.min(segment + 1, path.length - 1))

        val x = fromX + ((toX - fromX) * localT).toInt
        val y = fromY + ((toY - fromY) * localT).toInt
        (x, y)
      }

      interpolatedPath.toList.traverse_ { case (x, y) =>
        IO(robot.mouseMove(x, y)) *> IO.sleep(15.millis)
      }
    }
  }

  def startProcessing: Stream[IO, Unit] = {
    println("💥 Stream started!")
    Stream.awakeEvery[IO](10.millis).evalMap { _ =>
      queueRef.modify {
        case head :: tail => (tail, Some(head))
        case Nil          => (Nil, None)
      }.flatMap {
        case Some(action) =>
          for {
            _ <- IO(println(s"[EXECUTE] $action"))
//            _ <- IO(println(s"🎯 Executing: $action"))
            _ <- wasInProgressRef.set(true)
            _ <- taskInProgressRef.set(true)
            _ <- executeAction(action)
            _ <- IO.sleep(10.millis)
            remaining <- queueRef.get
            _ <- if (remaining.isEmpty) {
              IO(println("✅ Queue empty. Resetting taskInProgress.")) *>
                taskInProgressRef.set(false)
            } else IO.unit
          } yield ()

        case None =>
          for {
            wasInProgress <- wasInProgressRef.get
            _ <- if (wasInProgress) {
              IO(println("🔵 Queue empty, resetting taskInProgress")) *>
                taskInProgressRef.set(false) *>
                wasInProgressRef.set(false)
            } else IO.unit
          } yield ()
      }
    }
  }

}

object MouseManagerApp {
  def start(): Unit = {
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
      wasInProgressRef
    )

    GlobalMouseManager.instance = Some(manager) // ✅ Set the instance first

    println("✅ MouseActionManager instance set")

    manager.startProcessing.compile.drain.unsafeRunAndForget() // ✅ Only this one
    println("✅ MouseActionManager started")
  }
}
