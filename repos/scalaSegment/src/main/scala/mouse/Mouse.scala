package mouse

import java.awt.Robot
import java.awt.event.InputEvent
import java.awt.MouseInfo
import java.awt.event.KeyEvent

object Mouse {

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

  def mouseMoveSmooth(robotInstance: Robot, loc: Option[(Int, Int)]): Unit = {
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