package keyboard

import cats.effect.{IO, Ref}
import java.awt.{Robot, Toolkit}
import java.awt.event.KeyEvent
import com.sun.jna.platform.win32.{User32, WinUser}
import com.sun.jna.platform.win32.WinUser._
import com.sun.jna.platform.win32.WinDef._
import scala.concurrent.duration._
import scala.util.Try

object KeyboardUtils {
  val user32 = User32.INSTANCE
  val robot = new Robot()

//  def pressControlAndArrows(robot: Robot, ctrlKey: Int, arrowKeys: List[String]): IO[Unit] = IO.blocking {
//    robot.keyPress(KeyEvent.VK_CONTROL)
//    arrowKeys.foreach { direction =>
//      val keyCode = keyCodeFromDirection(direction)
//      robot.keyPress(keyCode)
//      Thread.sleep(100)
//      robot.keyRelease(keyCode)
//    }
//    robot.keyRelease(KeyEvent.VK_CONTROL)
//  }

//  def pressButtons(robot: Robot, keys: Seq[String]): IO[Unit] = IO.blocking {
//    val keyCodes = keys.map(getKeyCodeForKey)
//    if (keyCodes.forall(isValidKeyCode)) {
//      keyCodes.foreach(robot.keyPress)
//      keyCodes.reverse.foreach(robot.keyRelease)
//    } else {
//      println(s"[ERROR] Invalid key(s): ${keys.mkString(", ")}")
//    }
//  }



//  def pressArrowKey(robot: Robot, direction: String): IO[Unit] = IO.blocking {
//    val numLockOn = Toolkit.getDefaultToolkit.getLockingKeyState(KeyEvent.VK_NUM_LOCK)
//    val (keyCode, immediateRelease) = direction match {
//      case "MoveUpLeft" => if (!numLockOn) (0x24, false) else (KeyEvent.VK_HOME, false)
//      case "MoveUpRight" => if (!numLockOn) (0x21, false) else (KeyEvent.VK_PAGE_UP, false)
//      case "MoveDownLeft" => if (!numLockOn) (0x23, false) else (KeyEvent.VK_END, false)
//      case "MoveDownRight" => if (!numLockOn) (0x22, false) else (KeyEvent.VK_PAGE_DOWN, false)
//      case _ => (keyCodeFromDirection(direction), immediateReleaseFromDirection(direction))
//    }
//
//    if (isValidKeyCode(keyCode)) {
//      if (direction.startsWith("Move") && !numLockOn) {
//        pressKeyUsingJNA(keyCode)
//      } else {
//        robot.keyPress(keyCode)
//        if (immediateRelease) {
//          robot.keyRelease(keyCode)
//        }
//      }
//    } else {
//      println(s"[ERROR] Invalid direction: $direction")
//    }
//  }

  def pressArrowKey(robot: Robot, direction: String): IO[Unit] = IO.blocking {
    val diagonalKeys = Set("MoveUpLeft", "MoveUpRight", "MoveDownLeft", "MoveDownRight")
    val isDiagonal = diagonalKeys.contains(direction)
    val useJNA = isDiagonal

    val keyCode = if (useJNA) {
      direction match {
        case "MoveUpLeft"    => 0x24
        case "MoveUpRight"   => 0x21
        case "MoveDownLeft"  => 0x23
        case "MoveDownRight" => 0x22
      }
    } else {
      keyCodeFromString(direction)
    }

    val immediateRelease = !isDiagonal

    if (useJNA) {
      pressKeyUsingJNA(keyCode)
    } else if (isValidKeyCode(keyCode)) {
      pressKeyUsingRobot(keyCode, immediateRelease)
    }
  }



  //    def pressArrowKey(robot: Robot, direction: String): IO[Unit] = IO.blocking {
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
////        if (!immediateRelease) {
////          scheduleKeyRelease(keyCode)
////        }
//        println(s"Key pressed and processed for $direction")
//      } else {
//        println(s"[ERROR] Invalid key code: $keyCode for direction: $direction")
//      }
//
//    }



  private def pressKeyUsingRobot(keyCode: Int, immediateRelease: Boolean): Unit = {
    robot.keyPress(keyCode)
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

  def typeText(robot: Robot, text: String): IO[Unit] = IO.blocking {
    println(s"Typing text: $text")
    text.foreach { char =>
      val keyCode = KeyEvent.getExtendedKeyCodeForChar(char)
      if (keyCode == KeyEvent.VK_UNDEFINED) {
        println(s"[ERROR] Cannot type char: $char")
      } else {
        val shiftRequired = Character.isUpperCase(char) || "~!@#$%^&*()_+{}|:\"<>?".contains(char)
        if (shiftRequired) robot.keyPress(KeyEvent.VK_SHIFT)
        robot.keyPress(keyCode)
        robot.keyRelease(keyCode)
        if (shiftRequired) robot.keyRelease(KeyEvent.VK_SHIFT)
      }
    }
    robot.keyPress(KeyEvent.VK_ENTER)
    robot.keyRelease(KeyEvent.VK_ENTER)
  }
  def isValidKeyCode(keyCode: Int): Boolean = {
    keyCode >= KeyEvent.VK_0 && keyCode <= KeyEvent.VK_Z ||
      keyCode >= KeyEvent.VK_NUMPAD0 && keyCode <= KeyEvent.VK_NUMPAD9 ||
      keyCode >= KeyEvent.VK_F1 && keyCode <= KeyEvent.VK_F12 ||
      keyCode >= KeyEvent.VK_LEFT && keyCode <= KeyEvent.VK_DOWN ||
      keyCode == KeyEvent.VK_PAGE_UP || keyCode == KeyEvent.VK_PAGE_DOWN ||
      keyCode == KeyEvent.VK_HOME || keyCode == KeyEvent.VK_END ||
      keyCode == KeyEvent.VK_CONTROL || keyCode == KeyEvent.VK_ALT || keyCode == KeyEvent.VK_SHIFT ||
      keyCode == KeyEvent.VK_TAB || keyCode == KeyEvent.VK_ENTER || keyCode == KeyEvent.VK_SPACE || keyCode == KeyEvent.VK_ESCAPE
  }


  def isDiagonalKey(keyCode: Int): Boolean =
    keyCode == KeyEvent.VK_HOME ||
      keyCode == KeyEvent.VK_END ||
      keyCode == KeyEvent.VK_PAGE_UP ||
      keyCode == KeyEvent.VK_PAGE_DOWN

  def fromHotkeyString(str: String): Int = {
    val upper = str.trim.toUpperCase
    val fieldOpt = try {
      Some(classOf[KeyEvent].getField(s"VK_$upper"))
    } catch {
      case _: NoSuchFieldException => None
    }

    fieldOpt.map(_.getInt(null)).getOrElse {
      println(s"[WARN] Unknown hotkey string: $str")
      -1
    }
  }
  def keyCodeFromString(key: String): Int = {
    val numLockOn = Toolkit.getDefaultToolkit.getLockingKeyState(KeyEvent.VK_NUM_LOCK)

    key match {
      case "MoveUp" => KeyEvent.VK_UP
      case "MoveDown" => KeyEvent.VK_DOWN
      case "MoveLeft" => KeyEvent.VK_LEFT
      case "MoveRight" => KeyEvent.VK_RIGHT
      case "MoveUpLeft" => if (!numLockOn) 0x24 else KeyEvent.VK_HOME
      case "MoveUpRight" => if (!numLockOn) 0x21 else KeyEvent.VK_PAGE_UP
      case "MoveDownLeft" => if (!numLockOn) 0x23 else KeyEvent.VK_END
      case "MoveDownRight" => if (!numLockOn) 0x22 else KeyEvent.VK_PAGE_DOWN

      // Extended key support
      case "Ctrl" | "Control" => KeyEvent.VK_CONTROL
      case "Shift" => KeyEvent.VK_SHIFT
      case "Alt" => KeyEvent.VK_ALT
      case "Enter" => KeyEvent.VK_ENTER
      case "Space" => KeyEvent.VK_SPACE
      case "Esc" | "Escape" => KeyEvent.VK_ESCAPE
      case "Tab" => KeyEvent.VK_TAB
      case "Backspace" => KeyEvent.VK_BACK_SPACE
      case "Delete" => KeyEvent.VK_DELETE
      case "CapsLock" => KeyEvent.VK_CAPS_LOCK

      // Function keys
      case f if f.matches("F\\d{1,2}") =>
        val n = f.drop(1).toInt
        if (n >= 1 && n <= 12) KeyEvent.VK_F1 + (n - 1) else -1

      // Alphabet and other general keys
      case char if char.length == 1 =>
        KeyboardUtils.getKeyCodeForKey(char)

      case _ => -1
    }
  }


  def keyCodeToDirection(keyCode: Int): String = keyCode match {
    case KeyEvent.VK_UP         => "MoveUp"
    case KeyEvent.VK_DOWN       => "MoveDown"
    case KeyEvent.VK_LEFT       => "MoveLeft"
    case KeyEvent.VK_RIGHT      => "MoveRight"
    case KeyEvent.VK_HOME       => "MoveUpLeft"
    case KeyEvent.VK_PAGE_UP    => "MoveUpRight"
    case KeyEvent.VK_END        => "MoveDownLeft"
    case KeyEvent.VK_PAGE_DOWN  => "MoveDownRight"
    case _                      => s"Unknown($keyCode)"
  }


  private def immediateReleaseFromDirection(direction: String): Boolean =
    direction.contains("Single")

  // Lookup common keys, including all function keys and fallback
  def getKeyCodeForKey(key: String): Int = key.toUpperCase match {
    case "TAB"    => KeyEvent.VK_TAB
    case "CTRL"   => KeyEvent.VK_CONTROL
    case "ALT"    => KeyEvent.VK_ALT
    case "SHIFT"  => KeyEvent.VK_SHIFT
    case "ENTER"  => KeyEvent.VK_ENTER
    case "SPACE"  => KeyEvent.VK_SPACE
    // F1–F12
    case "F1"     => KeyEvent.VK_F1
    case "F2"     => KeyEvent.VK_F2
    case "F3"     => KeyEvent.VK_F3
    case "F4"     => KeyEvent.VK_F4
    case "F5"     => KeyEvent.VK_F5
    case "F6"     => KeyEvent.VK_F6
    case "F7"     => KeyEvent.VK_F7
    case "F8"     => KeyEvent.VK_F8
    case "F9"     => KeyEvent.VK_F9
    case "F10"    => KeyEvent.VK_F10
    case "F11"    => KeyEvent.VK_F11
    case "F12"    => KeyEvent.VK_F12
    // Single character fallback
    case s if s.length == 1 =>
      val code = KeyEvent.getExtendedKeyCodeForChar(s.charAt(0))
      if (code == KeyEvent.VK_UNDEFINED) KeyEvent.VK_UNDEFINED else code
    case other =>
      println(s"[WARNING] Unrecognized key '$other', defaulting to VK_UNDEFINED")
      KeyEvent.VK_UNDEFINED
  }

}
