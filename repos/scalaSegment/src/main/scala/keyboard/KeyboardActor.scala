package keyboard

import akka.actor.TypedActor.context
import akka.actor.{Actor, Cancellable, Props}

import java.awt.{Robot, Toolkit}
import java.awt.event.KeyEvent
import scala.concurrent.duration._
import com.sun.jna.platform.win32.{User32, WinUser}
import com.sun.jna.platform.win32.WinUser._
import com.sun.jna.platform.win32.WinDef._


class KeyboardActor extends Actor {
  import context.dispatcher


  val robot = new Robot()
  val user32 = User32.INSTANCE
  var keyReleaseTasks: Map[Int, Cancellable] = Map()


  // Simulate typing a string and press Enter after typing

  override def receive: Receive = {


    case PressControlAndArrows(ctrlKey, arrowKeys) =>
      println("[DEBUG] Initiating CTRL and arrow keys press.")
      try {
        robot.keyPress(KeyEvent.VK_CONTROL) // Press the CTRL key
        arrowKeys.foreach { key =>
          val keyCode = keyCodeFromDirection(key) // Map direction to key code
          robot.keyPress(keyCode) // Press each arrow key
          robot.delay(100) // Delay for 100 milliseconds between key presses
          robot.keyRelease(keyCode) // Release each arrow key
        }
      } catch {
        case e: Exception =>
          println(s"[ERROR] Exception occurred while pressing keys: ${e.getMessage}")
          throw e
      } finally {
        robot.keyRelease(KeyEvent.VK_CONTROL) // Ensure CTRL key is released
        println("[DEBUG] CTRL and arrow keys sequence completed. Keys released.")
//        sender() ! KeyboardActionCompleted(KeyboardActionTypes.PressKey) // Notify completion
      }

    case TypeText(text) =>
      typeText(text)

    case PressButtons(key) =>
      println(s"[DEBUG] Initiating key press for: $key")
      val keyCode = getKeyCodeForKey(key)
      try {
        val immediateRelease = true // For simplicity, we'll always immediately release the key
        if (isValidKeyCode(keyCode)) {
          pressKey(keyCode)
          println(s"[DEBUG] Key $key pressed.")
        } else {
          println(s"[ERROR] Invalid key code: $key for key: $key")
        }
      } catch {
        case e: Exception =>
          println(s"[ERROR] Exception occurred while pressing key $key: ${e.getMessage}")
          throw e
      }
      sender() ! KeyboardActionCompleted(KeyboardActionTypes.PressKey)


    case PressArrowKey(direction) =>
      val numLockOn = Toolkit.getDefaultToolkit.getLockingKeyState(KeyEvent.VK_NUM_LOCK)
      val (keyCode, immediateRelease) = direction match {
        // Use JNA for diagonal movements when NumLock is off
        case "MoveUpLeft" => if (!numLockOn) (0x24, false) else (KeyEvent.VK_HOME, false) // VK_HOME
        case "MoveUpRight" => if (!numLockOn) (0x21, false) else (KeyEvent.VK_PAGE_UP, false) // VK_PAGE_UP
        case "MoveDownLeft" => if (!numLockOn) (0x23, false) else (KeyEvent.VK_END, false) // VK_END
        case "MoveDownRight" => if (!numLockOn) (0x22, false) else (KeyEvent.VK_PAGE_DOWN, false) // VK_PAGE_DOWN



        // Use Robot for all other keys
        case _ => (keyCodeFromDirection(direction), immediateReleaseFromDirection(direction))
      }
      println(s"[DEBUG] Preparing to press key: $keyCode for direction: $direction with immediate release: $immediateRelease")


      if (isValidKeyCode(keyCode)) {
        if (direction.startsWith("Move") && !numLockOn) {
          pressKeyUsingJNA(keyCode)
        } else {
          pressKeyUsingRobot(keyCode, immediateRelease)
        }
        if (!immediateRelease) {
          scheduleKeyRelease(keyCode)
        }
        println(s"Key pressed and processed for $direction")
      } else {
        println(s"[ERROR] Invalid key code: $keyCode for direction: $direction")
      }

      sender() ! KeyboardActionCompleted(KeyboardActionTypes.PressKey)


  }


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

  private def isValidKeyCode(keyCode: Int): Boolean = {
    keyCode >= KeyEvent.VK_0 && keyCode <= KeyEvent.VK_Z || // For alphanumeric keys
      keyCode >= KeyEvent.VK_NUMPAD0 && keyCode <= KeyEvent.VK_NUMPAD9 || // For numpad keys
      keyCode >= KeyEvent.VK_F1 && keyCode <= KeyEvent.VK_F12 || // For function keys
      keyCode >= KeyEvent.VK_LEFT && keyCode <= KeyEvent.VK_DOWN || // For arrow keys
      keyCode == KeyEvent.VK_CONTROL || keyCode == KeyEvent.VK_ALT || keyCode == KeyEvent.VK_SHIFT || // Modifier keys
      keyCode == KeyEvent.VK_TAB || keyCode == KeyEvent.VK_ENTER || keyCode == KeyEvent.VK_SPACE || keyCode == KeyEvent.VK_ESCAPE // Other special keys
  }


  private def scheduleKeyRelease(keyCode: Int, delay: FiniteDuration = 400.milliseconds): Unit = {
    keyReleaseTasks.get(keyCode).foreach(task => {
      task.cancel()
      println(s"[DEBUG] Previously scheduled key release for $keyCode cancelled.")
    })
    val task = context.system.scheduler.scheduleOnce(delay) {
      println(s"[DEBUG] Executing scheduled key release for $keyCode.")
      robot.keyRelease(keyCode)
      println(s"[DEBUG] Key $keyCode released after delay.")
    }
    keyReleaseTasks += (keyCode -> task)
    println(s"[DEBUG] Key release for $keyCode scheduled with a delay of $delay.")
  }


  //  private def scheduleKeyRelease(keyCode: Int, delay: FiniteDuration = 400.milliseconds): Unit = {
//    keyReleaseTasks.get(keyCode).foreach(_.cancel())
//    val task = context.system.scheduler.scheduleOnce(delay) {
//      robot.keyRelease(keyCode)
//      keyReleaseTasks -= keyCode
//      println(s"KeyboardActor: Key $keyCode released after delay.")
//    }
//    keyReleaseTasks += (keyCode -> task)
//    println(s"KeyboardActor: Release for key $keyCode scheduled.")
//  }

  private def pressKeyUsingRobot(keyCode: Int, immediateRelease: Boolean): Unit = {
    robot.keyPress(keyCode)
    keyReleaseTasks.get(keyCode).foreach(_.cancel())
    keyReleaseTasks -= keyCode
    println(s"KeyboardActor: Key $keyCode pressed.")
    if (immediateRelease) {
      robot.keyRelease(keyCode)
      println(s"KeyboardActor: Key $keyCode released immediately.")
    }
  }

  private def pressKeyUsingJNA(keyCode: Int): Unit = {
    val input = new INPUT()
    input.`type` = new DWORD(INPUT.INPUT_KEYBOARD)
    input.input.setType("ki")
    input.input.ki.wVk = new WORD(keyCode.toShort)
    input.input.ki.dwFlags = new DWORD(0)

    user32.SendInput(new DWORD(1), Array(input), input.size)

    Thread.sleep(100)

    val KEYEVENTF_KEYUP = 0x0002
    input.input.ki.dwFlags = new DWORD(KEYEVENTF_KEYUP)
    user32.SendInput(new DWORD(1), Array(input), input.size)
    println(s"KeyboardActor: Key $keyCode pressed and released using JNA.")
  }

  private def setNumLock(desiredState: Boolean): Unit = {
    val currentState = Toolkit.getDefaultToolkit.getLockingKeyState(KeyEvent.VK_NUM_LOCK)
    if (currentState != desiredState) {
      robot.keyPress(KeyEvent.VK_NUM_LOCK)
      robot.keyRelease(KeyEvent.VK_NUM_LOCK)
      println(s"NumLock set to $desiredState")
    }
  }


  // Helper method to press and release a key
  private def pressKey(keyCode: Int): Unit = {
    //    println(s"KeyboardActor: Pressing key with keyCode: $keyCode") // Debug print
    robot.keyPress(keyCode)
    robot.keyRelease(keyCode)
    //    println(s"KeyboardActor: Released key with keyCode: $keyCode") // Debug print
  }

  def keyCodeFromDirection(direction: String): Int = {
    // Example: translate direction to keyCode
    direction match {
      case "MoveUp" => KeyEvent.VK_UP
      case "MoveDown" => KeyEvent.VK_DOWN
      case "MoveLeft" => KeyEvent.VK_LEFT
      case "MoveRight" => KeyEvent.VK_RIGHT
      case "F1" => KeyEvent.VK_F1
      case "F2" => KeyEvent.VK_F2
      case "F3" => KeyEvent.VK_F3
      case "F4" => KeyEvent.VK_F4
      case "F5" => KeyEvent.VK_F5
      case "F6" => KeyEvent.VK_F6
      case "F7" => KeyEvent.VK_F7
      case "F8" => KeyEvent.VK_F8
      case "F9" => KeyEvent.VK_F9
      case "F10" => KeyEvent.VK_F10
      case "F11" => KeyEvent.VK_F11
      case "F12" => KeyEvent.VK_F12
      case "Ctrl" => KeyEvent.VK_CONTROL
      case "L" => KeyEvent.VK_L
      case _ => -1
    }
  }


  private def getKeyCodeForKey(key: String): Int = {
    key.toUpperCase match {
      case "TAB" => KeyEvent.VK_TAB

      // Add more key mappings as necessary
      case _ => KeyEvent.VK_UNDEFINED
    }
  }

  def immediateReleaseFromDirection(direction: String): Boolean = {
    // Determine if key should be immediately released based on direction
    direction.contains("Single")
  }
}

object KeyboardActor {
  def props: Props = Props[KeyboardActor]
  case class PressArrowKey(direction: String)

  case class PressButtons(key: String)

  case object KeyboardActionCompleted
  object KeyboardActionTypes {
    val PressKey = "PressKey"
  }



}



//import akka.actor.{Actor, Cancellable, Props}
//
//import java.awt.{Robot, Toolkit}
//import java.awt.event.KeyEvent
//import scala.concurrent.duration._
//
//
//class KeyboardActor extends Actor {
//  import context.dispatcher
//
//  val robot = new Robot()
//  var keyReleaseTasks: Map[Int, Cancellable] = Map()
//
//  // Cancel any existing release task and press the key
//  private def pressKey(keyCode: Int, immediateRelease: Boolean = false): Unit = {
//    robot.keyPress(keyCode)
//    keyReleaseTasks.get(keyCode).foreach(_.cancel())
//    keyReleaseTasks -= keyCode
//    println(s"KeyboardActor: Key $keyCode pressed.")
//    if (immediateRelease) {
//      robot.keyRelease(keyCode)
//      println(s"KeyboardActor: Key $keyCode released immediately.")
//    }
//  }
//
//  // Schedule a key release with a delay
//  private def scheduleKeyRelease(keyCode: Int, delay: FiniteDuration = 400.milliseconds): Unit = {
//    keyReleaseTasks.get(keyCode).foreach(_.cancel())
//    val task = context.system.scheduler.scheduleOnce(delay) {
//      robot.keyRelease(keyCode)
//      keyReleaseTasks -= keyCode
//      println(s"KeyboardActor: Key $keyCode released after delay.")
//    }
//    keyReleaseTasks += (keyCode -> task)
//    println(s"KeyboardActor: Release for key $keyCode scheduled.")
//  }
//
//  private def setNumLock(desiredState: Boolean): Unit = {
//    val currentState = Toolkit.getDefaultToolkit.getLockingKeyState(KeyEvent.VK_NUM_LOCK)
//    if (currentState != desiredState) {
//      robot.keyPress(KeyEvent.VK_NUM_LOCK)
//      robot.keyRelease(KeyEvent.VK_NUM_LOCK)
//      println(s"NumLock set to $desiredState")
//    }
//  }
//
//  override def receive: Receive = {
//    case PressArrowKey(direction) =>
//
//      // Check if NumLock is on
//      val numLockOn = Toolkit.getDefaultToolkit.getLockingKeyState(KeyEvent.VK_NUM_LOCK)
//
//      val (keyCode, immediateRelease) = direction match {
//        case "MoveDownLeft" => if (numLockOn) (KeyEvent.VK_END, false) else (KeyEvent.VK_END, false) // Numeric 1: End
//        case "MoveDownRight" => if (numLockOn) (KeyEvent.VK_PAGE_DOWN, false) else (KeyEvent.VK_PAGE_DOWN, false) // Numeric 3: Page Down
//        case "MoveUpLeft" => if (numLockOn) (KeyEvent.VK_HOME, false) else (KeyEvent.VK_HOME, false) // Numeric 7: Home
//        case "MoveUpRight" => if (numLockOn) (KeyEvent.VK_PAGE_UP, false) else (KeyEvent.VK_PAGE_UP, false) // Numeric 9: Page Up
//
//
//        case "MoveDown" => (KeyEvent.VK_DOWN, false) // Numeric 2: Down (down)
//        case "MoveLeft" => (KeyEvent.VK_LEFT, false) // Numeric 4: Left (left)
//        case "MoveUp" => (KeyEvent.VK_UP, false) // Numeric 8: Up (up)
//        case "MoveRight" => (KeyEvent.VK_RIGHT, false) // Numeric 6: Right (right)
//
//
//        // Adding single movements for diagonal directions
//        case "MoveUpLeftSingle" => (KeyEvent.VK_HOME, true) // Home key with immediate release
//        case "MoveUpRightSingle" => (KeyEvent.VK_PAGE_UP, true) // Page Up key with immediate release
//        case "MoveDownLeftSingle" => (KeyEvent.VK_END, true) // End key with immediate release
//        case "MoveDownRightSingle" => (KeyEvent.VK_PAGE_DOWN, true) // Page Down key with immediate release
//
//
//        case "F1" => (KeyEvent.VK_F1, true)
//        case "F2" => (KeyEvent.VK_F2, true)
//        case "F3" => (KeyEvent.VK_F3, true)
//        case "F4" => (KeyEvent.VK_F4, true)
//        case "F5" => (KeyEvent.VK_F5, true)
//        case "F6" => (KeyEvent.VK_F6, true)
//        case "F7" => (KeyEvent.VK_F7, true)
//        case "F8" => (KeyEvent.VK_F8, true)
//        case "F9" => (KeyEvent.VK_F9, true)
//        case "F10" => (KeyEvent.VK_F10, true)
//        case "F11" => (KeyEvent.VK_F11, true)
//        case "F12" => (KeyEvent.VK_F12, true)
//        case _ =>
//          println("KeyboardActor: Invalid direction received.")
//          (-1, false)
//      }
//
//      println(s"Attempting to press key: $keyCode for direction: $direction with immediate release: $immediateRelease")
//      if (keyCode != -1) {
//        pressKey(keyCode, immediateRelease)
//        if (!immediateRelease) {
//          scheduleKeyRelease(keyCode)
//        }
//        println(s"Key pressed and processed for $direction")
//      } else {
//        println(s"Failed to find key mapping for $direction")
//      }
//      sender() ! KeyboardActionCompleted(KeyboardActionTypes.PressKey)
//  }
//}
//
//object KeyboardActor {
//  def props: Props = Props[KeyboardActor]
//  case class PressArrowKey(direction: String)
//  case object KeyboardActionCompleted
//  object KeyboardActionTypes {
//    val PressKey = "PressKey"
//  }
//}
