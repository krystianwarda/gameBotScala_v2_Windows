package mouse
import akka.actor.{Actor, ActorRef, Props}
import main.scala.MainApp.mouseMovementActorRef

import scala.collection.mutable
import mouse.{ActionCompleted, MouseMoveCommand}
import processing.MouseAction
// Define action types and their priorities
// Within your mouse package or an appropriate location

case class ActionCompleted(actionType: ActionTypes.Value) // Redefined to include actionType
case class MouseMovementStatusUpdate(activeTaskCount: Int, mouseMovementsEnabled: Boolean)

// A map to hold action priorities (lower number = higher priority)
object ActionTypes extends Enumeration {
  val Heal, AttackMonster, Move, ShootRune, Fish = Value

  // A map to hold action priorities (lower number = higher priority)
  val actionPriorities: Map[Value, Int] = Map(
    Heal -> 1,
    AttackMonster -> 2,
    Move -> 3,
    ShootRune -> 4,
    Fish -> 5 // Assuming you want to add Fish with a priority
  )
}


// Actor to manage action states
class ActionStateManager extends Actor {
  // Map to track action states and their last execution time
  val actionStates: mutable.Map[ActionTypes.Value, (String, Long, Option[Long])] = mutable.Map().withDefaultValue(("free", 0L, None))
  // Initialize with mouse movements enabled status
  var mouseMovementsEnabled: Boolean = true // Assume default as true, adjust based on your application logic

  // Method to call when changing the state
  def updateMouseMovementStatus(): Unit = {
    // Assuming you have a way to count active tasks, replace `activeTaskCount` with your actual logic
    val activeTaskCount = actionStates.count(_._2._1 == "in progress")
    mouseMovementActorRef ! MouseMovementStatusUpdate(activeTaskCount, mouseMovementsEnabled)
  }

  override def receive: Receive = {
    case MouseMoveCommand(actions, mouseMovementsEnabled) =>
      //      println(s"Received MouseMoveCommand with actions: ${actions.length} and mouseMovementsEnabled: $mouseMovementsEnabled")
      val actionType = extractActionType(actions)
      val currentTime = System.currentTimeMillis()
      val (state, lastExecutionTime, _) = actionStates(actionType)
      //      println(s"Current state for actionType $actionType: $state")

      if (state == "free" && isPriorityMet(actionType)) {
        //        println(s"Action $actionType is set to 'in progress'")
        actionStates(actionType) = ("in progress", currentTime, calculateNextExecutionTime(actionType, currentTime))
        mouseMovementActorRef ! MouseMoveCommand(actions, mouseMovementsEnabled)
      }

    case ActionCompleted(actionType) =>
      //      println(s"ActionCompleted received for actionType: $actionType")
      val currentTime = System.currentTimeMillis()
      actionStates(actionType) = ("free", currentTime, None)
    //      println(s"Action $actionType is now free")
  }

  def extractActionType(actions: Seq[MouseAction]): ActionTypes.Value = {
    // Example logic to determine action type
    // This is highly simplified and should be replaced with your actual logic
    if (actions.exists(_.action == "heal")) ActionTypes.Heal
    else if (actions.exists(_.action == "attack")) ActionTypes.AttackMonster
    else ActionTypes.Move // Default to Move as a simple example
  }

  def handleActionCompleted(actionType: ActionTypes.Value): Unit = {
    val currentTime = System.currentTimeMillis()
    // Retrieve the existing throttle info to decide if it should be reset or maintained.
    val (_, _, existingThrottleInfo) = actionStates(actionType)

    // If you want to reset the throttle info after the action is completed, replace `existingThrottleInfo` with `None`.
    // If you want to keep the throttle as it was, just leave `existingThrottleInfo` as it is.
    actionStates(actionType) = ("free", currentTime, existingThrottleInfo)

    //    println(s"Action $actionType completed and is now free.")
    // Additional logic for post-action completion...
  }


  def calculateNextExecutionTime(actionType: ActionTypes.Value, currentTime: Long): Option[Long] = {
    // Define throttling logic per action type, e.g., 1 second for fishing
    actionType match {
      case ActionTypes.Fish => Some(currentTime + 1000) // Next execution time 1 second later
      case _ => None // No throttling for other actions
    }
  }

  def isPriorityMet(actionType: ActionTypes.Value): Boolean = {
    // Implement your logic to check if the action's priority allows it to proceed
    true
  }
}

object ActionStateManager {
  def props: Props = Props[ActionStateManager]
}