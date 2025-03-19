package utils

import java.awt.Robot
import scala.util.Random
import scala.concurrent.ExecutionContext
import java.util.concurrent.atomic.AtomicReference
import scala.math._

object MouseJiggler {

  val robot: Robot = new Robot()
  implicit val ec: ExecutionContext = ExecutionContext.global

  // Configuration - Adjustable Randomness & Movement Settings
  val movementDistance: Int = 500 // **Max distance from current position**
  val movementVariation: Int = 100 // **Random variation added to movement distance**
  val arcStrengthFactor: Double = 0.05 // **Subtle curvature**
  val steps: Int = 100  // **Increase steps for smoother movement**
  val baseSpeedFactor: Double = 20.0 // **Speed scaling factor**
  val overshootFactor: Double = 0.05 // **50% Smaller Overshoot than Before**
  val overshootChance: Double = 0.15 // **Only 15% of moves will overshoot**
  val hoverChance: Double = 0.10 // **10% chance to hover on target**
  val hoverDuration: Int = 1000 // **Hovering time in milliseconds**
  val hoverRange: Int = 5 // **How much the mouse wiggles during hovering**

  // State variable for mouse status
  private val mouseStatus = new AtomicReference[String]("idle")

  // **Adjust Step Size Instead of Delay for Smooth Motion**
  def easingStepSize(progress: Double, totalDistance: Double): Double = {
    val easeOutExpo = 1 - pow(1 - progress, 4) // **Sharper deceleration near the target**
    baseSpeedFactor * easeOutExpo * (totalDistance / steps) // Adjust step size dynamically
  }

  // **Determine a Target Position Based on the Mouse's Current Position**
  def determineTarget(): (Int, Int) = {
    val startX = java.awt.MouseInfo.getPointerInfo.getLocation.getX.toInt
    val startY = java.awt.MouseInfo.getPointerInfo.getLocation.getY.toInt

    val distance = movementDistance + Random.between(-movementVariation, movementVariation)
    val angle = Random.nextDouble() * 2 * Pi // Random direction

    val targetX = (startX + cos(angle) * distance).toInt
    val targetY = (startY + sin(angle) * distance).toInt

    (targetX, targetY)
  }

  // **Calculate an Overshoot Target (Only Used 15% of the Time)**
  def determineOvershoot(targetX: Int, targetY: Int, startX: Int, startY: Int): (Int, Int) = {
    val overshootX = targetX + ((targetX - startX) * overshootFactor).toInt
    val overshootY = targetY + ((targetY - startY) * overshootFactor).toInt
    (overshootX, overshootY)
  }

  // **Subtle Arc Calculation (Smoother Curvature)**
  def calculateSlightArc(startX: Int, startY: Int, targetX: Int, targetY: Int, progress: Double): (Int, Int) = {
    val linearX = startX + ((targetX - startX) * progress).toInt
    val linearY = startY + ((targetY - startY) * progress).toInt

    val deltaX = targetX - startX
    val deltaY = targetY - startY

    // **Arc deviation should be small (a fraction of movement distance)**
    val arcAmount = sqrt(deltaX * deltaX + deltaY * deltaY) * arcStrengthFactor

    // **Determine slight curvature direction (left or right)**
    val perpendicularX = -deltaY
    val perpendicularY = deltaX
    val arcX = linearX + (perpendicularX / sqrt(perpendicularX * perpendicularX + perpendicularY * perpendicularY) * arcAmount).toInt
    val arcY = linearY + (perpendicularY / sqrt(perpendicularX * perpendicularX + perpendicularY * perpendicularY) * arcAmount).toInt

    (arcX, arcY)
  }

  // **Move Mouse to Target with Overshoot (15% Chance)**
  def moveMouseTo(targetX: Int, targetY: Int): Unit = {
    val startX = java.awt.MouseInfo.getPointerInfo.getLocation.getX.toInt
    val startY = java.awt.MouseInfo.getPointerInfo.getLocation.getY.toInt
    val totalDistance = sqrt(pow(targetX - startX, 2) + pow(targetY - startY, 2))

    val shouldOvershoot = Random.nextDouble() < overshootChance
    val (overshootX, overshootY) = if (shouldOvershoot) determineOvershoot(targetX, targetY, startX, startY) else (targetX, targetY)

    println(s"Moving mouse from ($startX, $startY) to ($targetX, $targetY) ${if (shouldOvershoot) s"with overshoot to ($overshootX, $overshootY)" else ""}")

    var prevX = startX.toDouble
    var prevY = startY.toDouble

    // **Phase 1: Move to Overshoot Target (Fast)**
    (1 to steps / 2).foreach { step =>
      val progress = step.toDouble / (steps / 2)
      val stepSize = easingStepSize(progress, totalDistance)

      val (arcX, arcY) = calculateSlightArc(startX, startY, overshootX, overshootY, progress)

      val newX = prevX + (arcX - prevX) * stepSize / totalDistance
      val newY = prevY + (arcY - prevY) * stepSize / totalDistance

      robot.mouseMove(newX.toInt, newY.toInt)

      prevX = newX
      prevY = newY

      Thread.sleep(5)
    }

    // **Phase 2: Move Back to Actual Target (Slow)**
    (1 to steps / 2).foreach { step =>
      val progress = step.toDouble / (steps / 2)
      val stepSize = easingStepSize(1 - progress, totalDistance)

      val (arcX, arcY) = calculateSlightArc(overshootX, overshootY, targetX, targetY, progress)

      val newX = prevX + (arcX - prevX) * stepSize / totalDistance
      val newY = prevY + (arcY - prevY) * stepSize / totalDistance

      robot.mouseMove(newX.toInt, newY.toInt)

      prevX = newX
      prevY = newY

      Thread.sleep(5)
    }

    // **Hovering Effect (10% Chance)**
    if (Random.nextDouble() < hoverChance) {
      println(s"Hovering over ($targetX, $targetY)")
      val hoverStart = System.currentTimeMillis()
      while (System.currentTimeMillis() - hoverStart < hoverDuration) {
        val hoverX = targetX + Random.between(-hoverRange, hoverRange)
        val hoverY = targetY + Random.between(-hoverRange, hoverRange)
        robot.mouseMove(hoverX, hoverY)
        Thread.sleep(50)
      }
    }
  }

  // **Move Mouse Periodically (Uses Functional Movement)**
  def moveMouse(): Unit = {
    while (true) {
      if (mouseStatus.get() == "idle") {
        val (targetX, targetY) = determineTarget()
        moveMouseTo(targetX, targetY) // **Now defined correctly**
      }
      Thread.sleep(5000) // Delay before next move
    }
  }

  def start(): Unit = {
    val thread = new Thread(() => moveMouse())
    thread.setDaemon(true)
    thread.start()
  }
}
