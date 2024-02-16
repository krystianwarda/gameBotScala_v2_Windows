package mouse

import akka.actor.{Actor, ActorRef}
import play.api.libs.json.{JsValue, Json, Writes}
import mouse.ActionTypes
import processing.{ActionDetail, JsonActionDetails, KeyboardText, MouseAction, MouseActions}

import java.awt.Robot
import java.awt.event.{InputEvent, KeyEvent}

case class TextCommand(text: String)

// MouseAction to represent a single mouse action (move, click, etc.)

object TextCommand {
  implicit val writes: Writes[TextCommand] = Json.writes[TextCommand]
}



// Item information
case class ItemInfo(id: Int, subType: Option[Int])

object ItemInfo {
  implicit val writes: Writes[ItemInfo] = Json.writes[ItemInfo]
}

// Action class that can include either mouse actions, keyboard text, or other command types
//case class FakeAction(command: String, itemInfo: Option[ItemInfo], actionDetail: Option[ActionDetail])
//case class FakeAction(command: String, itemInfo: Option[ItemInfo], actionDetail: Option[JsValue])
case class FakeAction(command: String, itemInfo: Option[ItemInfo], actionDetail: Option[ActionDetail])

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
      case "pressRight" => robotInstance.mousePress(InputEvent.BUTTON3_DOWN_MASK) // Add this line for right-click press
      case "releaseRight" => robotInstance.mouseRelease(InputEvent.BUTTON3_DOWN_MASK) // Add this line for right-click release
      case _ => println(s"Invalid mouse action: ${mouseAction.action}")
    }
  }

  // This method simulates typing a string using the Robot class.
  private def typeString(text: String): Unit = {
    text.foreach { char =>
      val keyCode = KeyEvent.getExtendedKeyCodeForChar(char)
      val isUpperCase = Character.isUpperCase(char) || "`~!@#$%^&*()_+{}|:\"<>?".indexOf(char) > -1

      if (isUpperCase) {
        robotInstance.keyPress(KeyEvent.VK_SHIFT)
      }

      robotInstance.keyPress(keyCode)
      robotInstance.keyRelease(keyCode)

      if (isUpperCase) {
        robotInstance.keyRelease(KeyEvent.VK_SHIFT)
      }

      Thread.sleep(50) // Small delay to simulate typing
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

