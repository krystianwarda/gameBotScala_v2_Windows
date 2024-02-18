package mouse

import java.awt.Robot
import java.awt.event.InputEvent
import java.awt.MouseInfo
import java.awt.event.KeyEvent
import scala.math.random
import scala.util.Random

object Mouse {
  val random = new Random()

  def mousePress(robot: Robot, buttons: Int): Unit = {
    robot.mousePress(buttons)
  }

  def mouseRelease(robot: Robot, buttons: Int): Unit = {
    robot.mouseRelease(buttons)
  }
  def leftClick(robotInstance: Robot, loc: Option[(Int, Int)]): Unit = {
    loc.foreach { case (x, y) =>
      mouseMoveSmooth(robotInstance, Some(x, y))
      robotInstance.mousePress(InputEvent.BUTTON1_DOWN_MASK)
      robotInstance.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
    }
  }

  def rightClick(robotInstance: Robot, loc: Option[(Int, Int)]): Unit = {
    loc.foreach { case (x, y) =>
      mouseMoveSmooth(robotInstance, Some(x, y))
      robotInstance.mousePress(InputEvent.BUTTON3_DOWN_MASK)
      robotInstance.mouseRelease(InputEvent.BUTTON3_DOWN_MASK)
    }
  }

  def shiftClick(robotInstance: Robot, loc: Option[(Int, Int)]): Unit = {
    loc.foreach { case (x, y) =>
      robotInstance.mouseMove(x, y)
      robotInstance.keyPress(KeyEvent.VK_SHIFT)
      Thread.sleep(300)
      robotInstance.mousePress(InputEvent.BUTTON1_DOWN_MASK)
      robotInstance.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
      Thread.sleep(300)
      robotInstance.keyRelease(KeyEvent.VK_SHIFT)
    }
  }

  def mouseMove(robotInstance: Robot, loc: Option[(Int, Int)]): Unit = {
    val (x, y) = loc.get
    robotInstance.mouseMove(x, y)
  }

  def dragUseOnChar(robotInstance: Robot, loc1: Option[(Int, Int)], loc2: Option[(Int, Int)]): Unit = {
    mouseMoveSmooth(robotInstance, loc1)
    robotInstance.mousePress(InputEvent.BUTTON3_DOWN_MASK) // Press right click
    robotInstance.mouseRelease(InputEvent.BUTTON3_DOWN_MASK) // Release right click

    mouseMoveSmooth(robotInstance, loc2)
    robotInstance.mousePress(InputEvent.BUTTON1_DOWN_MASK) // Press left click
    robotInstance.mouseRelease(InputEvent.BUTTON1_DOWN_MASK) // Release left click
  }

  def mouseMoveSmoothOld(robotInstance: Robot, loc: Option[(Int, Int)]): Unit = {
    loc.foreach { case (x, y) =>
      val currentLoc = MouseInfo.getPointerInfo.getLocation
      val xStep = (x - currentLoc.getX).toInt / 10
      val yStep = (y - currentLoc.getY).toInt / 10
      for (i <- 1 to 10) {
        robotInstance.mouseMove(currentLoc.getX.toInt + i * xStep, currentLoc.getY.toInt + i * yStep)
        Thread.sleep(50)
      }
    }
  }

//  def mouseMoveSmooth(robotInstance: Robot, loc: Option[(Int, Int)], simulateHumanBehavior: Boolean = false): Unit = {
//    loc.foreach { case (x, y) =>
//      val currentLoc = MouseInfo.getPointerInfo.getLocation
//      val xStep = (x - currentLoc.getX).toInt / 10
//      val yStep = (y - currentLoc.getY).toInt / 10
//      for (i <- 1 to 10) {
//        robotInstance.mouseMove(currentLoc.getX.toInt + i * xStep, currentLoc.getY.toInt + i * yStep)
//        Thread.sleep(50)
//      }
//    }
//  }

//  def mouseMoveSmooth(robotInstance: Robot, loc: Option[(Int, Int)], simulateHumanBehavior: Boolean = false): Unit = {
//    loc.foreach { case (targetX, targetY) =>
//      println(s"Starting smooth mouse movement to target: ($targetX, $targetY)")
//
//      val currentLoc = MouseInfo.getPointerInfo.getLocation
//      println(s"Current mouse position: (${currentLoc.x}, ${currentLoc.y})")
//
//      val steps = if (simulateHumanBehavior) Math.max(20, (Math.hypot(targetX - currentLoc.x, targetY - currentLoc.y) / 5).toInt) else 10
//      for (i <- 1 to steps) {
//        val intermediateX = currentLoc.x + (targetX - currentLoc.x) * i / steps
//        val intermediateY = currentLoc.y + (targetY - currentLoc.y) * i / steps
//
//        println(s"Moving to intermediate position: ($intermediateX, $intermediateY) step $i of $steps")
//        robotInstance.mouseMove(intermediateX.toInt, intermediateY.toInt)
//        Thread.sleep(if (simulateHumanBehavior) 50 + random.nextInt(50) else 50)
//      }
//
//      println(s"Completed smooth mouse movement to: ($targetX, $targetY)")
//    }
//  }

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


  //  def mouseMoveSmooth(robotInstance: Robot, loc: Option[(Int, Int)], simulateHumanBehavior: Boolean = false): Unit = {
//    loc.foreach { case (targetX, targetY) =>
//      println(s"Smoothly moving mouse to ($targetX, $targetY) with human behavior: $simulateHumanBehavior")
//      val currentLoc = MouseInfo.getPointerInfo.getLocation
//      val startX = currentLoc.x
//      val startY = currentLoc.y
//      val distance = Math.hypot(targetX - startX, targetY - startY).toInt
//
//      val steps = if (simulateHumanBehavior) Math.max(20, distance / 50) else 10 // Increase steps for smoother movement
//      val arcFactor = if (simulateHumanBehavior) 0.2 else 0 // Arc factor for human-like movement
//
//      for (i <- 1 to steps) {
//        val progress = i.toDouble / steps
//        val intermediateX = startX + ((targetX - startX) * progress).toInt
//        val intermediateY = startY + ((targetY - startY) * progress).toInt
//        val arcYOffset = if (simulateHumanBehavior) (Math.sin(progress * Math.PI) * (distance * arcFactor)).toInt else 0
//
//        robotInstance.mouseMove(intermediateX, intermediateY + arcYOffset)
//        Thread.sleep(if (simulateHumanBehavior) 10 + random.nextInt(20) else 50) // Randomize delay for human-like behavior
//      }
//
//      // Simulate overshoot if applicable
//      // Simulate overshoot if applicable
//      if (simulateHumanBehavior && distance > 100) {
//        val overshootX = targetX + (if (random.nextBoolean()) 5 else -5)
//        val overshootY = targetY + (if (random.nextBoolean()) 5 else -5)
//        robotInstance.mouseMove(overshootX, overshootY)
//        Thread.sleep(100 + random.nextInt(100))
//        robotInstance.mouseMove(targetX, targetY) // Adjust to the final target position
//      }
//    }
//  }

  def calcLocOffset(location: Option[(Int, Int)], x: Int, y: Int): Option[(Int, Int)] = {
    location.map { case (lat, long) =>
      (lat + x, long + y)
    }
  }


  //  def mouseScroll(robotInstance: Robot, scrolls: Int): Unit = {
  //    robot.mouseWheel(scrolls)
  //    }

  //    def mouseDragFour(x1: Int, y1: Int, x2: Int, y2: Int): Unit = {
  //        robot.mouseMove(x1, y1)
  //        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
  //        Thread.sleep(300)
  //        robot.mouseMove(x2, y2)
  //        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
  //    }

  def mouseDrag(robotInstance: Robot, loc1: Option[(Int, Int)], loc2: Option[(Int, Int)]): Unit = {
    if (loc1.isDefined && loc2.isDefined) {
      val (x1, y1) = loc1.get
      val (x2, y2) = loc2.get
      mouseMoveSmooth(robotInstance, loc1)
      robotInstance.mousePress(InputEvent.BUTTON1_DOWN_MASK)
      Thread.sleep(600)
      mouseMoveSmooth(robotInstance, loc2)
      //            robot.mouseMove(x2, y2)
      robotInstance.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
    } else {
      println("Cannot drag mouse. One or both locations not found.")
    }
  }

  def mouseDragSmooth(robotInstance: Robot, loc1: Option[(Int, Int)], loc2: Option[(Int, Int)]): Unit = {
    if (loc1.isDefined && loc2.isDefined) {
      val (x1, y1) = loc1.get
      val (x2, y2) = loc2.get

      // Get current mouse position
      val currentMousePos = MouseInfo.getPointerInfo.getLocation

      // Calculate the delta x and delta y
      val deltaX = x2 - x1
      val deltaY = y2 - y1

      // Calculate the number of steps for the drag
      val steps = 50

      // Calculate the step size for the drag
      val stepSizeX = deltaX / steps
      val stepSizeY = deltaY / steps

      // Perform the drag in steps
      robotInstance.mouseMove(x1, y1)
      robotInstance.mousePress(InputEvent.BUTTON1_DOWN_MASK)
      Thread.sleep(600)
      for (i <- 1 to steps) {
        val x = (x1 + stepSizeX * i).toInt
        val y = (y1 + stepSizeY * i).toInt
        robotInstance.mouseMove(x, y)
        Thread.sleep(10)
      }
      robotInstance.mouseMove(x2, y2)
      robotInstance.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)

      // Move the mouse back to its original position
      robotInstance.mouseMove(currentMousePos.x.toInt, currentMousePos.y.toInt)
    } else {
      println("Cannot drag mouse. One or both locations not found.")
    }
  }


}