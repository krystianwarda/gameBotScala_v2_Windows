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
  private def pressKey(keyCode: Int): Unit = {
    robot.keyPress(keyCode)
    keyReleaseTasks.get(keyCode).foreach(_.cancel())
    keyReleaseTasks -= keyCode
    println(s"KeyboardActor: Key $keyCode pressed.")
  }

  // Schedule a key release with a delay
  private def scheduleKeyRelease(keyCode: Int, delay: FiniteDuration = 550.milliseconds): Unit = {
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
      val keyCode = direction match {
        case "ArrowUp" => KeyEvent.VK_UP
        case "ArrowDown" => KeyEvent.VK_DOWN
        case "ArrowLeft" => KeyEvent.VK_LEFT
        case "ArrowRight" => KeyEvent.VK_RIGHT
        case _ =>
          println("KeyboardActor: Invalid direction received.")
          -1
      }

      if (keyCode != -1) {
        // Press the key immediately to ensure responsiveness
        pressKey(keyCode)
        // Schedule the release of the key allowing it to be held
        scheduleKeyRelease(keyCode)
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
