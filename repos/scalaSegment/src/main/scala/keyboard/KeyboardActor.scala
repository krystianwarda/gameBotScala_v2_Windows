package keyboard
import akka.actor.{Actor, Props, Cancellable}
import java.awt.Robot
import java.awt.event.KeyEvent
import scala.concurrent.duration._


class KeyboardActor extends Actor {
  import context.dispatcher

  val robot = new Robot()
  var keyReleaseTasks: Map[Int, Cancellable] = Map()

  // Cancel any existing release task and press the key
  private def pressKey(keyCode: Int, immediateRelease: Boolean = false): Unit = {
    robot.keyPress(keyCode)
    keyReleaseTasks.get(keyCode).foreach(_.cancel())
    keyReleaseTasks -= keyCode
    println(s"KeyboardActor: Key $keyCode pressed.")
    if (immediateRelease) {
      robot.keyRelease(keyCode)
      println(s"KeyboardActor: Key $keyCode released immediately.")
    }
  }

  // Schedule a key release with a delay
  private def scheduleKeyRelease(keyCode: Int, delay: FiniteDuration = 400.milliseconds): Unit = {
    keyReleaseTasks.get(keyCode).foreach(_.cancel())
    val task = context.system.scheduler.scheduleOnce(delay) {
      robot.keyRelease(keyCode)
      keyReleaseTasks -= keyCode
      println(s"KeyboardActor: Key $keyCode released after delay.")
    }
    keyReleaseTasks += (keyCode -> task)
    println(s"KeyboardActor: Release for key $keyCode scheduled.")
  }

  override def receive: Receive = {
    case PressArrowKey(direction) =>
      val (keyCode, immediateRelease) = direction match {
        case "ArrowUp" => (KeyEvent.VK_UP, false)
        case "ArrowDown" => (KeyEvent.VK_DOWN, false)
        case "ArrowLeft" => (KeyEvent.VK_LEFT, false)
        case "ArrowRight" => (KeyEvent.VK_RIGHT, false)
        case "ArrowUpSingle" => (KeyEvent.VK_UP, true)
        case "ArrowDownSingle" => (KeyEvent.VK_DOWN, true)
        case "ArrowLeftSingle" => (KeyEvent.VK_LEFT, true)
        case "ArrowRightSingle" => (KeyEvent.VK_RIGHT, true)
        case "F1" => (KeyEvent.VK_F1, true)
        case "F2" => (KeyEvent.VK_F2, true)
        case "F3" => (KeyEvent.VK_F3, true)
        case "F4" => (KeyEvent.VK_F4, true)
        case "F5" => (KeyEvent.VK_F5, true)
        case "F6" => (KeyEvent.VK_F6, true)
        case "F7" => (KeyEvent.VK_F7, true)
        case "F8" => (KeyEvent.VK_F8, true)
        case "F9" => (KeyEvent.VK_F9, true)
        case "F10" => (KeyEvent.VK_F10, true)
        case "F11" => (KeyEvent.VK_F11, true)
        case "F12" => (KeyEvent.VK_F12, true)
        case _ =>
          println("KeyboardActor: Invalid direction received.")
          (-1, false)
      }

      if (keyCode != -1) {
        // Press the key, with immediate release if specified
        pressKey(keyCode, immediateRelease)
        // Schedule the release of the key only if not immediately released
        if (!immediateRelease) {
          scheduleKeyRelease(keyCode)
        }
      }
      sender() ! KeyboardActionCompleted(KeyboardActionTypes.PressKey)
  }
}

object KeyboardActor {
  def props: Props = Props[KeyboardActor]
  case class PressArrowKey(direction: String)
  case object KeyboardActionCompleted
  object KeyboardActionTypes {
    val PressKey = "PressKey"
  }
}
