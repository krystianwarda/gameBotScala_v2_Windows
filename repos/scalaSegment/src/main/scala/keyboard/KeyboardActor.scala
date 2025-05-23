//package keyboard
//
//import akka.actor.TypedActor.context
//import akka.actor.{Actor, Cancellable, Props}
//
//import java.awt.{Robot, Toolkit}
//import java.awt.event.KeyEvent
//import scala.concurrent.duration._
//import com.sun.jna.platform.win32.{User32, WinUser}
//import com.sun.jna.platform.win32.WinUser._
//import com.sun.jna.platform.win32.WinDef._
//import processing.PushTheButtons
//
//
//
//class KeyboardActor extends Actor {
//  import context.dispatcher
//
//
//  val robot = new Robot()
//  val user32 = User32.INSTANCE
//  var keyReleaseTasks: Map[Int, Cancellable] = Map()
//
//
//  // Simulate typing a string and press Enter after typing
//
//  override def receive: Receive = {
//
//
//    case PressControlAndArrows(ctrlKey, arrowKeys) =>
//      println("[DEBUG] Initiating CTRL and arrow keys press.")
//      try {
//        robot.keyPress(KeyEvent.VK_CONTROL) // Press the CTRL key
//        arrowKeys.foreach { key =>
//          val keyCode = keyCodeFromDirection(key) // Map direction to key code
//          robot.keyPress(keyCode) // Press each arrow key
//          robot.delay(100) // Delay for 100 milliseconds between key presses
//          robot.keyRelease(keyCode) // Release each arrow key
//        }
//      } catch {
//        case e: Exception =>
//          println(s"[ERROR] Exception occurred while pressing keys: ${e.getMessage}")
//          throw e
//      } finally {
//        robot.keyRelease(KeyEvent.VK_CONTROL) // Ensure CTRL key is released
//        println("[DEBUG] CTRL and arrow keys sequence completed. Keys released.")
////        sender() ! KeyboardActionCompleted(KeyboardActionTypes.PressKey) // Notify completion
//      }
//
//    case TypeText(text) =>
//      typeText(text)
//    case PressButtons(keys: Seq[String]) =>
//      println(s"[DEBUG] Initiating key press for: $keys")
//
//      // Convert each key in the sequence to its corresponding key code
//      val keyCodes: Seq[Int] = keys.map(getKeyCodeForKey)
//
//      try {
//        if (keyCodes.forall(isValidKeyCode)) { // Ensure all key codes are valid
//          // Press each key in sequence
//          keyCodes.foreach { keyCode =>
//            robot.keyPress(keyCode)
//            println(s"[DEBUG] Key code $keyCode pressed.")
//          }
//
//          // Release each key in reverse order
//          keyCodes.reverse.foreach { keyCode =>
//            robot.keyRelease(keyCode)
//            println(s"[DEBUG] Key code $keyCode released.")
//          }
//
//        } else {
//          println(s"[ERROR] Invalid key code for one or more keys: $keys")
//        }
//      } catch {
//        case e: Exception =>
//          println(s"[ERROR] Exception occurred while pressing keys $keys: ${e.getMessage}")
//          throw e
//      }
//
//      sender() ! KeyboardActionCompleted(KeyboardActionTypes.PressKey)
//
//
//    case PushTheButtons(keys: Seq[String]) =>
//      println(s"[DEBUG] Initiating key press for: $keys")
//
//      // Convert each key in the sequence to its corresponding key code
//      val keyCodes: Seq[Int] = keys.map(getKeyCodeForKey)
//
//      try {
//        // Check if all key codes are valid
//        if (keyCodes.forall(isValidKeyCode)) {
//
//          // Press each key in sequence
//          keyCodes.foreach { keyCode =>
//            robot.keyPress(keyCode)
//            println(s"[DEBUG] Key code $keyCode pressed.")
//          }
//
//          // Release each key in reverse order
//          keyCodes.reverse.foreach { keyCode =>
//            robot.keyRelease(keyCode)
//            println(s"[DEBUG] Key code $keyCode released.")
//          }
//
//        } else {
//          println(s"[ERROR] Invalid key code for one or more keys: $keys")
//        }
//      } catch {
//        case e: Exception =>
//          println(s"[ERROR] Exception occurred while pressing keys $keys: ${e.getMessage}")
//          throw e
//      }
//
//      sender() ! KeyboardActionCompleted(KeyboardActionTypes.PressKey)
//
//
//    case PressArrowKey(direction) =>
//      println(s"PressArrowKey dirrection: ${direction}")
//      val numLockOn = Toolkit.getDefaultToolkit.getLockingKeyState(KeyEvent.VK_NUM_LOCK)
//      val (keyCode, immediateRelease) = direction match {
//        // Use JNA for diagonal movements when NumLock is off
//        case "MoveUpLeft" => if (!numLockOn) (0x24, false) else (KeyEvent.VK_HOME, false) // VK_HOME
//        case "MoveUpRight" => if (!numLockOn) (0x21, false) else (KeyEvent.VK_PAGE_UP, false) // VK_PAGE_UP
//        case "MoveDownLeft" => if (!numLockOn) (0x23, false) else (KeyEvent.VK_END, false) // VK_END
//        case "MoveDownRight" => if (!numLockOn) (0x22, false) else (KeyEvent.VK_PAGE_DOWN, false) // VK_PAGE_DOWN
//
//        // Use Robot for all other keys
//        case _ => (keyCodeFromDirection(direction), immediateReleaseFromDirection(direction))
//      }
//      println(s"[DEBUG] Preparing to press key: $keyCode for direction: $direction with immediate release: $immediateRelease")
//
//      println(s"isValidKeyCode(keyCode): ${isValidKeyCode(keyCode)}")
//      if (isValidKeyCode(keyCode)) {
//        if (direction.startsWith("Move") && !numLockOn) {
//          println("Using pressKeyUsingJNA")
//          pressKeyUsingJNA(keyCode)
//        } else {
//          println("Using pressKeyUsingRobot")
//          pressKeyUsingRobot(keyCode, immediateRelease)
//        }
//        if (!immediateRelease) {
//          scheduleKeyRelease(keyCode)
//        }
//        println(s"Key pressed and processed for $direction")
//      } else {
//        println(s"[ERROR] Invalid key code: $keyCode for direction: $direction")
//      }
//
//      sender() ! KeyboardActionCompleted(KeyboardActionTypes.PressKey)
//
//
//  }
//
//
//  private def typeText(text: String): Unit = {
//    println(s"KeyboardActor: Typing text: $text") // Debug print
//    text.foreach { char =>
//      char match {
//        case '?' => // Special handling for question mark
//          robot.keyPress(KeyEvent.VK_SHIFT)
//          pressKey(KeyEvent.VK_SLASH)
//          robot.keyRelease(KeyEvent.VK_SHIFT)
//        case _ =>
//          val keyCode = KeyEvent.getExtendedKeyCodeForChar(char)
//          //          println(s"KeyboardActor: Typing char: $char with keyCode: $keyCode") // Debug print for each character
//          if (keyCode == KeyEvent.VK_UNDEFINED) {
//            println(s"KeyboardActor: Cannot type character: $char")
//          } else {
//            if (Character.isUpperCase(char) || char.isDigit || "`~!@#$%^&*()_+{}|:\"<>?".indexOf(char) > -1) {
//              robot.keyPress(KeyEvent.VK_SHIFT)
//            }
//            pressKey(keyCode)
//            if (Character.isUpperCase(char) || char.isDigit || "`~!@#$%^&*()_+{}|:\"<>?".indexOf(char) > -1) {
//              robot.keyRelease(KeyEvent.VK_SHIFT)
//            }
//          }
//      }
//    }
//    // Press Enter after typing the text
//    pressKey(KeyEvent.VK_ENTER)
//    println("KeyboardActor: Pressed Enter after typing") // Debug print
//  }
//
//  private def isValidKeyCode(keyCode: Int): Boolean = {
//    keyCode >= KeyEvent.VK_0 && keyCode <= KeyEvent.VK_Z || // For alphanumeric keys
//      keyCode >= KeyEvent.VK_NUMPAD0 && keyCode <= KeyEvent.VK_NUMPAD9 || // For numpad keys
//      keyCode >= KeyEvent.VK_F1 && keyCode <= KeyEvent.VK_F12 || // For function keys
//      keyCode >= KeyEvent.VK_LEFT && keyCode <= KeyEvent.VK_DOWN || // For arrow keys
//      keyCode == KeyEvent.VK_PAGE_UP || keyCode == KeyEvent.VK_PAGE_DOWN || // Page Up and Page Down keys
//      keyCode == KeyEvent.VK_HOME || keyCode == KeyEvent.VK_END || // Home and End keys
//      keyCode == KeyEvent.VK_CONTROL || keyCode == KeyEvent.VK_ALT || keyCode == KeyEvent.VK_SHIFT || // Modifier keys
//      keyCode == KeyEvent.VK_TAB || keyCode == KeyEvent.VK_ENTER || keyCode == KeyEvent.VK_SPACE || keyCode == KeyEvent.VK_ESCAPE // Special keys
//  }
//
//
//
//  private def scheduleKeyRelease(keyCode: Int, delay: FiniteDuration = 400.milliseconds): Unit = {
//    keyReleaseTasks.get(keyCode).foreach(task => {
//      task.cancel()
//      println(s"[DEBUG] Previously scheduled key release for $keyCode cancelled.")
//    })
//    val task = context.system.scheduler.scheduleOnce(delay) {
//      println(s"[DEBUG] Executing scheduled key release for $keyCode.")
//      robot.keyRelease(keyCode)
//      println(s"[DEBUG] Key $keyCode released after delay.")
//    }
//    keyReleaseTasks += (keyCode -> task)
//    println(s"[DEBUG] Key release for $keyCode scheduled with a delay of $delay.")
//  }
//
//
//  private def pressKeyUsingRobot(keyCode: Int, immediateRelease: Boolean): Unit = {
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
//  private def pressKeyUsingJNA(keyCode: Int): Unit = {
//    val input = new INPUT()
//    input.`type` = new DWORD(INPUT.INPUT_KEYBOARD)
//    input.input.setType("ki")
//    input.input.ki.wVk = new WORD(keyCode.toShort)
//    input.input.ki.dwFlags = new DWORD(0)
//
//    user32.SendInput(new DWORD(1), Array(input), input.size)
//
//    Thread.sleep(100)
//
//    val KEYEVENTF_KEYUP = 0x0002
//    input.input.ki.dwFlags = new DWORD(KEYEVENTF_KEYUP)
//    user32.SendInput(new DWORD(1), Array(input), input.size)
//    println(s"KeyboardActor: Key $keyCode pressed and released using JNA.")
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
//
//  // Helper method to press and release a key
//  private def pressKey(keyCode: Int): Unit = {
//    //    println(s"KeyboardActor: Pressing key with keyCode: $keyCode") // Debug print
//    robot.keyPress(keyCode)
//    robot.keyRelease(keyCode)
//    //    println(s"KeyboardActor: Released key with keyCode: $keyCode") // Debug print
//  }
//
//
//  def keyCodeFromDirection(direction: String): Int = {
//    // Example: translate direction to keyCode
//    direction match {
//      case "MoveUp" => KeyEvent.VK_UP
//      case "MoveDown" => KeyEvent.VK_DOWN
//      case "MoveLeft" => KeyEvent.VK_LEFT
//      case "MoveRight" => KeyEvent.VK_RIGHT
//      case "F1" => KeyEvent.VK_F1
//      case "F2" => KeyEvent.VK_F2
//      case "F3" => KeyEvent.VK_F3
//      case "F4" => KeyEvent.VK_F4
//      case "F5" => KeyEvent.VK_F5
//      case "F6" => KeyEvent.VK_F6
//      case "F7" => KeyEvent.VK_F7
//      case "F8" => KeyEvent.VK_F8
//      case "F9" => KeyEvent.VK_F9
//      case "F10" => KeyEvent.VK_F10
//      case "F11" => KeyEvent.VK_F11
//      case "F12" => KeyEvent.VK_F12
//      case "Ctrl" => KeyEvent.VK_CONTROL
//      case "L" => KeyEvent.VK_L
//      case _ => -1
//    }
//  }
//
//
//  private def getKeyCodeForKey(key: String): Int = {
//    key.toUpperCase match {
//      case "TAB" => KeyEvent.VK_TAB
//      case "CTRL" => KeyEvent.VK_CONTROL
//      case "E" => KeyEvent.VK_E
//      case "O" => KeyEvent.VK_O
//      case "SHIFT" => KeyEvent.VK_SHIFT
//      case "ALT" => KeyEvent.VK_ALT
//      case "ENTER" => KeyEvent.VK_ENTER
//      // Add more key mappings as necessary
//      case _ => KeyEvent.VK_UNDEFINED
//    }
//  }
//
//
//
//  def immediateReleaseFromDirection(direction: String): Boolean = {
//    // Determine if key should be immediately released based on direction
//    direction.contains("Single")
//  }
//}
//
//object KeyboardActor {
//  def props: Props = Props[KeyboardActor]
//  case class PressArrowKey(direction: String)
//
//  case class PressButtons(key: String)
//
//  case object KeyboardActionCompleted
//  object KeyboardActionTypes {
//    val PressKey = "PressKey"
//  }
//
//
//
//}
