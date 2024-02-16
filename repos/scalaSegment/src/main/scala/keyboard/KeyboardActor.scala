package keyboard

import akka.actor.{Actor, Props}

import java.awt.Robot
import java.awt.event.KeyEvent
// Assuming ActionDetail is declared somewhere accessible
import mouse.{ActionDetail}

// Adjust KeyboardText to extend ActionDetail
case class KeyboardText(text: String) extends ActionDetail



class KeyboardActor extends Actor {
  val robot = new Robot()

  // Helper method to press and release a key
  private def pressKey(keyCode: Int): Unit = {
    robot.keyPress(keyCode)
    robot.keyRelease(keyCode)
  }

  // Simulate typing a string
  private def typeText(text: String): Unit = {
    text.foreach { char =>
      val keyCode = KeyEvent.getExtendedKeyCodeForChar(char)
      if (Character.isUpperCase(char) || char.isDigit || "`~!@#$%^&*()_+{}|:\"<>?".indexOf(char) > -1) {
        robot.keyPress(KeyEvent.VK_SHIFT)
      }
      pressKey(keyCode)
      if (Character.isUpperCase(char) || char.isDigit || "`~!@#$%^&*()_+{}|:\"<>?".indexOf(char) > -1) {
        robot.keyRelease(KeyEvent.VK_SHIFT)
      }
    }
  }

  // Handle function keys
  private def pressFunctionKey(key: String): Unit = {
    val keyCode = key match {
      case "F1" => KeyEvent.VK_F1
      case "F2" => KeyEvent.VK_F2
      // Add more cases as needed
      case _ => 0 // Default or error handling
    }
    if (keyCode != 0) pressKey(keyCode)
  }

  // In KeyboardActor
  def receive: Receive = {
    case TypeText(text) => typeText(text)
    case _ => println("Unhandled text type action")
  }
}

object KeyboardActor {
  def props: Props = Props[KeyboardActor]
}
