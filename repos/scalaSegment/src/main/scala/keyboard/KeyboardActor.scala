package keyboard

import akka.actor.{Actor, Props}
import java.awt.Robot
import java.awt.event.KeyEvent


class KeyboardActor extends Actor {
  val robot = new Robot()

  // Helper method to press and release a key

  // Helper method to press and release a key
  private def pressKey(keyCode: Int): Unit = {
    robot.keyPress(keyCode)
    robot.keyRelease(keyCode)
    // Debug logs can be uncommented for development or troubleshooting
    // println(s"KeyboardActor: Pressed and released key with keyCode: $keyCode")
  }

  // Simulate typing a string and press Enter after typing
  // Simulate typing a string
  private def typeText(text: String): Unit = {
    text.foreach { char =>
      val keyCode = KeyEvent.getExtendedKeyCodeForChar(char)
      if (keyCode == KeyEvent.VK_UNDEFINED) {
        println(s"KeyboardActor: Cannot type character: $char")
      } else {
        val isShiftNeeded = Character.isUpperCase(char) || "`~!@#$%^&*()_+{}|:\"<>?".indexOf(char) > -1
        if (isShiftNeeded) {
          robot.keyPress(KeyEvent.VK_SHIFT)
        }
        pressKey(keyCode)
        if (isShiftNeeded) {
          robot.keyRelease(KeyEvent.VK_SHIFT)
        }
      }
    }
    println("KeyboardActor: Typed text: " + text)
    // Optionally, press Enter after typing the text
    pressKey(KeyEvent.VK_ENTER)
  }


  // Handle function keys
  private def pressFunctionKey(key: String): Unit = {
    val keyCode = key match {
      case "F1" => KeyEvent.VK_F1
      case "F2" => KeyEvent.VK_F2
      // Add more cases as needed
      case _ => 0 // Default or error handling
    }
    println(s"KeyboardActor: Handling function key: $key with keyCode: $keyCode") // Debug print
    if (keyCode != 0) pressKey(keyCode)
  }


  override def receive: Receive = {
    case PressArrowKey(direction) =>
      val keyCode = direction match {
        case "ArrowUp" => KeyEvent.VK_UP
        case "ArrowDown" => KeyEvent.VK_DOWN
        case "ArrowLeft" => KeyEvent.VK_LEFT
        case "ArrowRight" => KeyEvent.VK_RIGHT
        case _ => 0 // Invalid key, handle appropriately
      }
      if (keyCode != 0) {
        pressKey(keyCode)
        println(s"KeyboardActor: Pressed and released key for $direction")
      }
      sender() ! KeyboardActionCompleted(KeyboardActionTypes.PressKey) // Notify completion

    case TypeText(text) =>
      typeText(text)
      sender() ! KeyboardActionCompleted(KeyboardActionTypes.TypeText) // Send completion notice if required

    case _ =>
      println("KeyboardActor: Unhandled action")
  }
}

object KeyboardActor {
  def props: Props = Props[KeyboardActor]
}