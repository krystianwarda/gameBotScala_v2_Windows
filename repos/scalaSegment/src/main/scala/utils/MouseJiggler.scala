package utils

import java.awt.Robot
import scala.util.Random
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext

object MouseJiggler {

  val robot: Robot = new Robot()
  implicit val ec: ExecutionContext = ExecutionContext.global

  def jiggleMouse(): Unit = {
    while (true) {
      val mouseX = java.awt.MouseInfo.getPointerInfo.getLocation.getX.toInt
      val mouseY = java.awt.MouseInfo.getPointerInfo.getLocation.getY.toInt

      val deltaX = Random.between(-5, 6) // Move between -5 to +5 pixels randomly
      val deltaY = Random.between(-5, 6)

      robot.mouseMove(mouseX + deltaX, mouseY + deltaY)

      Thread.sleep(1000) // Sleep for 1 second before next move
    }
  }

  def start(): Unit = {
    val thread = new Thread(() => jiggleMouse())
    thread.setDaemon(true) // Daemon thread so it stops when the app exits
    thread.start()
  }
}
