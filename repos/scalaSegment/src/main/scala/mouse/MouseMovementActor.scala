package mouse

import akka.actor.{Actor, ActorRef}
import play.api.libs.json.{Json, Writes}
import mouse.ActionTypes
import java.awt.Robot
import java.awt.event.InputEvent


case class MouseAction(x: Int, y: Int, action: String)
case class MouseMoveCommand(actions: Seq[MouseAction], mouseMovementsEnabled: Boolean)
case class MouseMovementSettings(x: Int, y: Int, action: String)

object MouseMovementSettings {
  // Using macro to automatically provide Writes implementation
  implicit val writes: Writes[MouseMovementSettings] = Json.writes[MouseMovementSettings]
}

class MouseMovementActor(actionStateManager: ActorRef) extends Actor {
  val robotInstance = new Robot()

  def performMouseAction(mouseAction: MouseAction): Unit = {
    println(s"Performing mouse action: ${mouseAction.action} at (${mouseAction.x}, ${mouseAction.y})")
    mouseAction.action match {
      case "move" => Mouse.mouseMoveSmooth(robotInstance, Some((mouseAction.x, mouseAction.y)))
      case "pressLeft" => robotInstance.mousePress(InputEvent.BUTTON1_DOWN_MASK)
      case "releaseLeft" => robotInstance.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
      case _ => println(s"Invalid mouse action: ${mouseAction.action}")
    }
  }

  def receive: Receive = {
    case MouseMoveCommand(actions, mouseMovementsEnabled) =>
      println(s"MouseMovementActor received command with enabled: $mouseMovementsEnabled")
      if (mouseMovementsEnabled) {
        actions.foreach { action =>
          println(s"Executing action: $action")
          performMouseAction(action)
        }
        actionStateManager ! ActionCompleted(ActionTypes.Move) // Example action type
        println("MouseMovementActor: Completed actions.")
      } else {
        println("Mouse movements are disabled. Ignoring the command.")
      }
  }
}

