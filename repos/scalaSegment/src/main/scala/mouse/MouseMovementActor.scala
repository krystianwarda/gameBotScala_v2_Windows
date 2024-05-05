package mouse

import akka.actor.{Actor, ActorRef, Cancellable}
import play.api.libs.json.{JsValue, Json, Writes}
import mouse.ActionTypes
import processing.{ActionDetail, JsonActionDetails, KeyboardText, MouseAction, MouseActions}

import scala.concurrent.ExecutionContext.Implicits.global
import java.awt.{MouseInfo, Robot}
import java.awt.event.{InputEvent, KeyEvent}
import scala.concurrent.duration.DurationInt
import scala.math.random
import scala.util.Random

case class TextCommand(text: String)

// MouseAction to represent a single mouse action (move, click, etc.)

object TextCommand {
  implicit val writes: Writes[TextCommand] = Json.writes[TextCommand]
}



// Item information
case class ItemInfo(id: Int, subType: Option[Int])

object ItemInfo {
  implicit val writes: Writes[ItemInfo] = Json.writes[ItemInfo]
}

// Action class that can include either mouse actions, keyboard text, or other command types
//case class FakeAction(command: String, itemInfo: Option[ItemInfo], actionDetail: Option[ActionDetail])
//case class FakeAction(command: String, itemInfo: Option[ItemInfo], actionDetail: Option[JsValue])
case class FakeAction(command: String, itemInfo: Option[ItemInfo], actionDetail: Option[ActionDetail])

case class MouseMoveCommand(actions: Seq[MouseAction], mouseMovementsEnabled: Boolean)

case class MouseMovementSettings(x: Int, y: Int, action: String)

object MouseMovementSettings {
  // Using macro to automatically provide Writes implementation
  implicit val writes: Writes[MouseMovementSettings] = Json.writes[MouseMovementSettings]
}


class MouseMovementActor(actionStateManager: ActorRef) extends Actor {
  val robotInstance = new Robot()
//  val c = new Random()
  // Schedule idle mouse movement simulation every 5 to 10 seconds
  var idleMovementSchedule: Option[Cancellable] = None
  var activeTaskCount: Int = 0
  var mouseMovementsEnabled: Boolean = false

  override def preStart(): Unit = {
    println("MouseMovementActor initialized")
  }

  // Schedule idle mouse movement simulation every 5 to 10 seconds
  context.system.scheduler.scheduleWithFixedDelay(
    initialDelay = 5.seconds,
    delay = 10.seconds,
    receiver = self,
    message = "simulateIdleMovement"
  )(context.system.dispatcher)

  private def startIdleMovement(): Unit = {
    if (idleMovementSchedule.isEmpty && mouseMovementsEnabled && activeTaskCount == 0) {
      idleMovementSchedule = Some(context.system.scheduler.scheduleWithFixedDelay(
        initialDelay = 5.seconds,
        delay = 10.seconds,
        receiver = self,
        message = "simulateIdleMovement"
      )(context.system.dispatcher))
    }
  }

  private def stopIdleMovement(): Unit = {
    idleMovementSchedule.foreach(_.cancel())
    idleMovementSchedule = None
  }

  def simulateIdleMouseMovement(): Unit = {
    val random = new Random()
    val currentLoc = MouseInfo.getPointerInfo.getLocation
    val angle = random.nextDouble() * 2 * Math.PI // Random angle in radians
    val distance = random.nextInt(20) + 20 // Random distance between 20 and 40 pixels

    val targetX = (currentLoc.x + Math.cos(angle) * distance).toInt
    val targetY = (currentLoc.y + Math.sin(angle) * distance).toInt

    val steps = 20
    for (i <- 1 to steps) {
      if (activeTaskCount > 0) return // Stop if new tasks have arrived

      val progress = i.toDouble / steps
      val intermediateX = currentLoc.x + (targetX - currentLoc.x) * progress
      val intermediateY = currentLoc.y + (targetY - currentLoc.y) * progress

      val curveEffect = Math.sin(progress * Math.PI) * 5
      robotInstance.mouseMove((intermediateX + curveEffect).toInt, intermediateY.toInt)

      Thread.sleep(50) // Realism
    }
  }


  // This method simulates typing a string using the Robot class.
  def performMouseAction(mouseAction: MouseAction): Unit = {
    //    println(s"Performing mouse action: ${mouseAction.action} at (${mouseAction.x}, ${mouseAction.y})")
    mouseAction.action match {
      case "move" =>
        //        println(s"Moving mouse to (${mouseAction.x}, ${mouseAction.y})")
        Mouse.mouseMoveSmooth(robotInstance, Some((mouseAction.x, mouseAction.y)), simulateHumanBehavior = true)
      case "pressLeft" =>
        //        println("Pressing left mouse button")
        robotInstance.mousePress(InputEvent.BUTTON1_DOWN_MASK)
      case "releaseLeft" =>
        //        println("Releasing left mouse button")
        robotInstance.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
      case "pressRight" =>
        //        println("Pressing right mouse button")
        robotInstance.mousePress(InputEvent.BUTTON3_DOWN_MASK)
      case "releaseRight" =>
        robotInstance.mouseRelease(InputEvent.BUTTON3_DOWN_MASK)
      case "pressCtrl" =>
        robotInstance.keyPress(KeyEvent.VK_CONTROL)
      case "releaseCtrl" =>
        robotInstance.keyRelease(KeyEvent.VK_CONTROL)
      case _ =>
        println(s"Invalid mouse action: ${mouseAction.action}")
    }
    actionStateManager ! ActionCompleted(ActionTypes.Move) // Adjust ActionTypes.Move as necessary
  }

  override def receive: Receive = {
    case MouseMoveCommand(actions, movementsEnabled) =>
      stopIdleMovement() // Stop idle movements when a new command is received
      activeTaskCount += actions.length
      actions.foreach(performMouseAction)
      activeTaskCount -= actions.length
      startIdleMovement() // Restart idle movements if still appropriate


    case "simulateIdleMovement" =>
      if (mouseMovementsEnabled && activeTaskCount == 0) simulateIdleMouseMovement()

    case MouseMovementStatusUpdate(taskCount, movementsEnabled) =>
      activeTaskCount = taskCount
      mouseMovementsEnabled = movementsEnabled
      if (mouseMovementsEnabled && activeTaskCount == 0) startIdleMovement()
      else stopIdleMovement()
  }

  override def postStop(): Unit = {
    stopIdleMovement()
    println("MouseMovementActor stopped")
  }
}