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
  private val user32 = User32.INSTANCE

  def pressControlAndArrows(robot: Robot, ctrlKey: Int, arrowKeys: List[String]): IO[Unit] = IO.blocking {
    robot.keyPress(KeyEvent.VK_CONTROL)
    arrowKeys.foreach { direction =>
      val keyCode = keyCodeFromDirection(direction)
      robot.keyPress(keyCode)
      Thread.sleep(100)
      robot.keyRelease(keyCode)
    }
    robot.keyRelease(KeyEvent.VK_CONTROL)
  }

  def pressButtons(robot: Robot, keys: Seq[String]): IO[Unit] = IO.blocking {
    val keyCodes = keys.map(getKeyCodeForKey)
    if (keyCodes.forall(isValidKeyCode)) {
      keyCodes.foreach(robot.keyPress)
      keyCodes.reverse.foreach(robot.keyRelease)
    } else {
      println(s"[ERROR] Invalid key(s): ${keys.mkString(", ")}")
    }
  }

  def pressArrowKey(robot: Robot, direction: String): IO[Unit] = IO.blocking {
    val numLockOn = Toolkit.getDefaultToolkit.getLockingKeyState(KeyEvent.VK_NUM_LOCK)
    val (keyCode, immediateRelease) = direction match {
      case "MoveUpLeft" => if (!numLockOn) (0x24, false) else (KeyEvent.VK_HOME, false)
      case "MoveUpRight" => if (!numLockOn) (0x21, false) else (KeyEvent.VK_PAGE_UP, false)
      case "MoveDownLeft" => if (!numLockOn) (0x23, false) else (KeyEvent.VK_END, false)
      case "MoveDownRight" => if (!numLockOn) (0x22, false) else (KeyEvent.VK_PAGE_DOWN, false)
      case _ => (keyCodeFromDirection(direction), immediateReleaseFromDirection(direction))
    }

    if (isValidKeyCode(keyCode)) {
      if (direction.startsWith("Move") && !numLockOn) {
        pressKeyUsingJNA(keyCode)
      } else {
        robot.keyPress(keyCode)
        if (immediateRelease) {
          robot.keyRelease(keyCode)
        }
      }
    } else {
      println(s"[ERROR] Invalid direction: $direction")
    }
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

  private def isValidKeyCode(keyCode: Int): Boolean =
    (KeyEvent.VK_0 to KeyEvent.VK_Z).contains(keyCode) ||
      (KeyEvent.VK_NUMPAD0 to KeyEvent.VK_NUMPAD9).contains(keyCode) ||
      (KeyEvent.VK_F1 to KeyEvent.VK_F12).contains(keyCode) ||
      (KeyEvent.VK_LEFT to KeyEvent.VK_DOWN).contains(keyCode) ||
      Set(KeyEvent.VK_PAGE_UP, KeyEvent.VK_PAGE_DOWN, KeyEvent.VK_HOME, KeyEvent.VK_END,
        KeyEvent.VK_CONTROL, KeyEvent.VK_ALT, KeyEvent.VK_SHIFT, KeyEvent.VK_TAB,
        KeyEvent.VK_ENTER, KeyEvent.VK_SPACE, KeyEvent.VK_ESCAPE).contains(keyCode)

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


  def keyCodeFromDirection(direction: String): Int = direction match {
    case "MoveUp"    => KeyEvent.VK_UP
    case "MoveDown"  => KeyEvent.VK_DOWN
    case "MoveLeft"  => KeyEvent.VK_LEFT
    case "MoveRight" => KeyEvent.VK_RIGHT
    case "F1"        => KeyEvent.VK_F1
    case "F2"        => KeyEvent.VK_F2
    case "F3"        => KeyEvent.VK_F3
    case "F4"        => KeyEvent.VK_F4
    case "F5"        => KeyEvent.VK_F5
    case "F6"        => KeyEvent.VK_F6
    case "F7"        => KeyEvent.VK_F7
    case "F8"        => KeyEvent.VK_F8
    case "F9"        => KeyEvent.VK_F9
    case "F10"       => KeyEvent.VK_F10
    case "F11"       => KeyEvent.VK_F11
    case "F12"       => KeyEvent.VK_F12
    case _           => KeyEvent.VK_UNDEFINED
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

  private def pressKeyUsingJNA(keyCode: Int): Unit = {
    val input = new INPUT()
    input.`type` = new DWORD(INPUT.INPUT_KEYBOARD)
    input.input.setType("ki")
    input.input.ki.wVk = new WORD(keyCode.toShort)
    input.input.ki.dwFlags = new DWORD(0)
    user32.SendInput(new DWORD(1), Array(input), input.size)

    Thread.sleep(100)

    input.input.ki.dwFlags = new DWORD(0x0002) // KEYEVENTF_KEYUP
    user32.SendInput(new DWORD(1), Array(input), input.size)
  }
}
