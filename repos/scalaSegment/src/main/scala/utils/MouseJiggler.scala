// Scala
package utils

import java.awt.Robot
import java.util.concurrent.ThreadLocalRandom
import scala.concurrent.ExecutionContext
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.math._

object MouseJiggler {
  System.setProperty("java.awt.headless", "false")
  // Lazy to avoid build-time instantiation
  private lazy val robot: Robot = new Robot()
  implicit val ec: ExecutionContext = ExecutionContext.global

  val movementDistance: Int = 500
  val movementVariation: Int = 100
  val arcStrengthFactor: Double = 0.05
  val steps: Int = 100
  val baseSpeedFactor: Double = 20.0
  val overshootFactor: Double = 0.05
  val overshootChance: Double = 0.15
  val hoverChance: Double = 0.10
  val hoverDuration: Int = 1000
  val hoverRange: Int = 5

  private val mouseStatus = new AtomicReference[String]("idle")
  private val running     = new AtomicBoolean(false)
  private val workerRef   = new AtomicReference[Thread](null)

  private def rng = ThreadLocalRandom.current()

  def easingStepSize(progress: Double, totalDistance: Double): Double = {
    val easeOutExpo = 1 - pow(1 - progress, 4)
    baseSpeedFactor * easeOutExpo * (totalDistance / steps)
  }

  def determineTarget(): (Int, Int) = {
    val start = java.awt.MouseInfo.getPointerInfo.getLocation
    val startX = start.getX.toInt
    val startY = start.getY.toInt

    val distance = movementDistance + rng.nextInt(-movementVariation, movementVariation + 1)
    val angle = rng.nextDouble() * 2 * Pi

    val targetX = (startX + cos(angle) * distance).toInt
    val targetY = (startY + sin(angle) * distance).toInt
    (targetX, targetY)
  }

  def determineOvershoot(targetX: Int, targetY: Int, startX: Int, startY: Int): (Int, Int) = {
    val overshootX = targetX + ((targetX - startX) * overshootFactor).toInt
    val overshootY = targetY + ((targetY - startY) * overshootFactor).toInt
    (overshootX, overshootY)
  }

  def calculateSlightArc(startX: Int, startY: Int, targetX: Int, targetY: Int, progress: Double): (Int, Int) = {
    val linearX = startX + ((targetX - startX) * progress).toInt
    val linearY = startY + ((targetY - startY) * progress).toInt
    val deltaX = targetX - startX
    val deltaY = targetY - startY
    val arcAmount = sqrt(deltaX * deltaX + deltaY * deltaY) * arcStrengthFactor
    val perpendicularX = -deltaY
    val perpendicularY = deltaX
    val norm = sqrt(perpendicularX * perpendicularX + perpendicularY * perpendicularY)
    val arcX = linearX + (perpendicularX / norm * arcAmount).toInt
    val arcY = linearY + (perpendicularY / norm * arcAmount).toInt
    (arcX, arcY)
  }

  def moveMouseTo(targetX: Int, targetY: Int): Unit = {
    val start = java.awt.MouseInfo.getPointerInfo.getLocation
    val startX = start.getX.toInt
    val startY = start.getY.toInt
    val totalDistance = sqrt(pow(targetX - startX, 2) + pow(targetY - startY, 2))
    val shouldOvershoot = rng.nextDouble() < overshootChance
    val (overshootX, overshootY) =
      if (shouldOvershoot) determineOvershoot(targetX, targetY, startX, startY) else (targetX, targetY)

    println(s"Moving mouse from ($startX,$startY) to ($targetX,$targetY)${if (shouldOvershoot) s" via overshoot ($overshootX,$overshootY)" else ""}")

    var prevX = startX.toDouble
    var prevY = startY.toDouble

    (1 to steps / 2).foreach { step =>
      val progress = step.toDouble / (steps / 2)
      val stepSize = easingStepSize(progress, totalDistance)
      val (arcX, arcY) = calculateSlightArc(startX, startY, overshootX, overshootY, progress)
      val newX = prevX + (arcX - prevX) * stepSize / totalDistance
      val newY = prevY + (arcY - prevY) * stepSize / totalDistance
      robot.mouseMove(newX.toInt, newY.toInt)
      prevX = newX; prevY = newY
      Thread.sleep(5)
    }

    (1 to steps / 2).foreach { step =>
      val progress = step.toDouble / (steps / 2)
      val stepSize = easingStepSize(1 - progress, totalDistance)
      val (arcX, arcY) = calculateSlightArc(overshootX, overshootY, targetX, targetY, progress)
      val newX = prevX + (arcX - prevX) * stepSize / totalDistance
      val newY = prevY + (arcY - prevY) * stepSize / totalDistance
      robot.mouseMove(newX.toInt, newY.toInt)
      prevX = newX; prevY = newY
      Thread.sleep(5)
    }

    if (rng.nextDouble() < hoverChance) {
      println(s"Hovering over ($targetX,$targetY)")
      val endAt = System.currentTimeMillis() + hoverDuration
      while (System.currentTimeMillis() < endAt) {
        val hoverX = targetX + rng.nextInt(-hoverRange, hoverRange + 1)
        val hoverY = targetY + rng.nextInt(-hoverRange, hoverRange + 1)
        robot.mouseMove(hoverX, hoverY)
        Thread.sleep(50)
      }
    }
  }

  private def moveLoop(): Unit = {
    try {
      while (running.get()) {
        if (mouseStatus.get() == "idle2") {
          val (tx, ty) = determineTarget()
          moveMouseTo(tx, ty)
        }
        Thread.sleep(5000)
      }
    } catch {
      case _: InterruptedException => ()
    }
  }

  def start(): Unit = {
    if (running.compareAndSet(false, true)) {
      val thread = new Thread(() => moveLoop(), "MouseJiggler")
      thread.setDaemon(true)
      workerRef.set(thread)
      thread.start()
    }
  }

  def stop(): Unit = {
    if (running.compareAndSet(true, false)) {
      val t = workerRef.getAndSet(null)
      if (t != null) t.interrupt()
    }
  }
}