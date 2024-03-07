package keyboard

import akka.actor.{Actor, Props}
import java.awt.Robot
import java.awt.event.KeyEvent

class KeyboardActor extends Actor {
  val robot = new Robot()

  // Helper method to press and release a key
  private def pressKey(keyCode: Int): Unit = {
//    println(s"KeyboardActor: Pressing key with keyCode: $keyCode") // Debug print
    robot.keyPress(keyCode)
    robot.keyRelease(keyCode)
//    println(s"KeyboardActor: Released key with keyCode: $keyCode") // Debug print
  }

  // Simulate typing a string and press Enter after typing
  private def typeText(text: String): Unit = {
    println(s"KeyboardActor: Typing text: $text") // Debug print
    text.foreach { char =>
      char match {
        case '?' => // Special handling for question mark
          robot.keyPress(KeyEvent.VK_SHIFT)
          pressKey(KeyEvent.VK_SLASH)
          robot.keyRelease(KeyEvent.VK_SHIFT)
        case _ =>
          val keyCode = KeyEvent.getExtendedKeyCodeForChar(char)
//          println(s"KeyboardActor: Typing char: $char with keyCode: $keyCode") // Debug print for each character
          if (keyCode == KeyEvent.VK_UNDEFINED) {
            println(s"KeyboardActor: Cannot type character: $char")
          } else {
            if (Character.isUpperCase(char) || char.isDigit || "`~!@#$%^&*()_+{}|:\"<>?".indexOf(char) > -1) {
              robot.keyPress(KeyEvent.VK_SHIFT)
            }
            pressKey(keyCode)
            if (Character.isUpperCase(char) || char.isDigit || "`~!@#$%^&*()_+{}|:\"<>?".indexOf(char) > -1) {
              robot.keyRelease(KeyEvent.VK_SHIFT)
            }
          }
      }
    }
    // Press Enter after typing the text
    pressKey(KeyEvent.VK_ENTER)
    println("KeyboardActor: Pressed Enter after typing") // Debug print
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

  // In KeyboardActor
  def receive: Receive = {
    case TypeText(text) =>
//      println(s"KeyboardActor: Received TypeText with text: $text") // Debug print
      typeText(text)
    case _ => println("KeyboardActor: Unhandled text type action")
  }
}

object KeyboardActor {
  def props: Props = Props[KeyboardActor]
}
