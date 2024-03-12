package keyboard
import akka.actor.{Actor, Props, Cancellable}
import java.awt.Robot
import java.awt.event.KeyEvent
import scala.concurrent.duration._

class KeyboardActor extends Actor {
  import context.dispatcher // Import the actor's execution context for scheduling

  val robot = new Robot()
  var lastKeyReleaseTask: Option[Cancellable] = None
  var lastPressedKeyCode: Option[Int] = None

  // Helper method to press a key without releasing it
  private def pressKeyWithoutRelease(keyCode: Int): Unit = {
    robot.keyPress(keyCode)
    lastPressedKeyCode = Some(keyCode)
    // Cancel any existing scheduled release task
    lastKeyReleaseTask.foreach(_.cancel())
    // Schedule a new release task
    lastKeyReleaseTask = Some(context.system.scheduler.scheduleOnce(500.milliseconds) {
      robot.keyRelease(keyCode)
      lastPressedKeyCode = None
    })
  }

  override def receive: Receive = {
    case PressArrowKey(direction) =>
      val keyCode = direction match {
        case "ArrowUp" => KeyEvent.VK_UP
        case "ArrowDown" => KeyEvent.VK_DOWN
        case "ArrowLeft" => KeyEvent.VK_LEFT
        case "ArrowRight" => KeyEvent.VK_RIGHT
        case _ => -1 // Signify invalid key
      }
      if (keyCode != -1) {
        // Check if it's the same key pressed again before releasing
        if (lastPressedKeyCode.contains(keyCode)) {
          // It's the same key, so just reset the release task
          lastKeyReleaseTask.foreach(_.cancel())
          lastKeyReleaseTask = Some(context.system.scheduler.scheduleOnce(800.milliseconds) {
            robot.keyRelease(keyCode)
            lastPressedKeyCode = None
          })
        } else {
          // It's a different key or the first press
          lastPressedKeyCode.foreach(robot.keyRelease) // Release any previously pressed key
          pressKeyWithoutRelease(keyCode) // Press the new key
        }
        println(s"KeyboardActor: Pressed key for $direction")
      }
      sender() ! KeyboardActionCompleted(KeyboardActionTypes.PressKey)

    // Add cases for TypeText and other actions as before
  }
}

object KeyboardActor {
  def props: Props = Props[KeyboardActor]
}
