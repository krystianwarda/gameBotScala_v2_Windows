package mouse

import java.awt.Robot
import java.awt.event.InputEvent
import java.awt.MouseInfo
import java.awt.event.KeyEvent
import scala.math.random
import scala.util.Random

object MouseUtils {
  val random = new Random()

  def mousePress(robot: Robot, buttons: Int): Unit = {
    robot.mousePress(buttons)
  }

  def mouseRelease(robot: Robot, buttons: Int): Unit = {
    robot.mouseRelease(buttons)
  }


  def mouseMove(robotInstance: Robot, loc: Option[(Int, Int)]): Unit = {
    val (x, y) = loc.get
    robotInstance.mouseMove(x, y)
  }



  import java.awt.Robot
  import java.awt.MouseInfo
  import scala.util.Random

  def mouseMoveSmooth(robotInstance: Robot, loc: Option[(Int, Int)], simulateHumanBehavior: Boolean = false): Unit = {
    loc.foreach { case (targetX, targetY) =>
      val random = new Random()
      val currentLoc = MouseInfo.getPointerInfo.getLocation
      val startX = currentLoc.x
      val startY = currentLoc.y
      val distance = Math.hypot(targetX - startX, targetY - startY).toInt

      val steps = if (simulateHumanBehavior) Math.max(40, distance / 20) else 25 // Increased steps for smoother, faster transition
      val arcDirection = if (targetX > startX) 1 else -1 // Determine arc direction based on target position relative to start
      val baseArcFactor = if (simulateHumanBehavior) 0.05 * distance else 0 // Base arc factor for human-like behavior
      val arcVariability = random.nextDouble() * 0.015 - 0.0020 // Random variability in arc size, slightly reduced

      for (i <- 1 to steps) {
        val progress = i.toDouble / steps
        val arcFactor = (baseArcFactor + arcVariability * distance) * arcDirection // Apply arc direction
        val arcAdjustment = Math.sin(progress * Math.PI) * arcFactor
        val intermediateX = startX + ((targetX - startX + arcAdjustment * Math.cos(progress * Math.PI)) * progress).toInt
        val intermediateY = startY + ((targetY - startY) * progress).toInt

        robotInstance.mouseMove(intermediateX, intermediateY)
        // Adjust sleep time to be generally faster but slow down significantly towards the end
        val sleepTime = if (simulateHumanBehavior) (15 - 10 * progress).toInt.max(5) else 20
        Thread.sleep(sleepTime.toLong)
      }

      if (simulateHumanBehavior && distance > 150) {
        // Adjusted overshoot to be slightly more controlled
        val overshootX = targetX + random.between(-8, 8)
        val overshootY = targetY + random.between(-8, 8)
        robotInstance.mouseMove(overshootX, overshootY)
        Thread.sleep(75 + random.nextInt(50))
        robotInstance.mouseMove(targetX, targetY) // Final adjustment to ensure accuracy
      }
    }
  }




}