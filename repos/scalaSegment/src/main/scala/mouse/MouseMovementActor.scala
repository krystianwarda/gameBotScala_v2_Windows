package mouse

import akka.actor.Actor

import java.awt.Robot

class MouseMovementActor extends Actor {
  // Initialize Robot instance when the actor starts
  val robotInstance = new Robot()

  def performMouseAction(x: Int, y: Int, action: String): Unit = {
    action match {
      case "move" => Mouse.mouseMoveSmooth(robotInstance, Some((x, y)))
      case "leftClick" => {
        Mouse.mouseMoveSmooth(robotInstance, Some((x, y)))
        Mouse.leftClick(robotInstance, Some((x, y)))
      }
      case "rightClick" => {
        Mouse.mouseMoveSmooth(robotInstance, Some((x, y)))
        Mouse.rightClick(robotInstance, Some((x, y)))
      }
      case _ => println("Invalid mouse action")
    }
  }

  def receive: Receive = {
    case MouseMoveCommand(settings) =>
      // Use settings to perform the mouse action
      performMouseAction(settings.x, settings.y, settings.action)
  }
}

case class MouseMoveCommand(settings: MouseMovementSettings)
case class MouseMovementSettings(x: Int, y: Int, action: String)
