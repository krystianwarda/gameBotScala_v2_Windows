package mouse
import akka.actor.{Actor, ActorRef, Props}
import main.scala.MainApp.mouseMovementActorRef

import scala.collection.mutable
import mouse.{ActionCompleted, MouseMoveCommand}
// Define action types and their priorities
// Within your mouse package or an appropriate location

case class ActionCompleted(actionType: ActionTypes.Value) // Redefined to include actionType


// A map to hold action priorities (lower number = higher priority)
object ActionTypes extends Enumeration {
  val Heal, AttackMonster, Move, ShootRune = Value

  // A map to hold action priorities (lower number = higher priority)
  val actionPriorities: Map[Value, Int] = Map(
    Heal -> 1,
    AttackMonster -> 2,
    Move -> 3,
    ShootRune -> 4
  )
}


// Actor to manage action states
class ActionStateManager extends Actor {
  // Map to track action states and their last execution time
  val actionStates: mutable.Map[ActionTypes.Value, (String, Long)] = mutable.Map().withDefaultValue(("free", 0L))

  def receive: Receive = {

    case MouseMoveCommand(actions, mouseMovementsEnabled) =>
      println(s"ActionStateManager received MouseMoveCommand: $actions, mouseMovementsEnabled: $mouseMovementsEnabled")
      val actionType = extractActionType(actions) // Implement this based on your logic
      val currentTime = System.currentTimeMillis()
      val (state, lastExecTime) = actionStates(actionType)

      if (state == "free" && isPriorityMet(actionType)) {
        actionStates(actionType) = ("in progress", currentTime)
        mouseMovementActorRef ! MouseMoveCommand(actions, true) // Directly send to MouseMovementActor
        println(s"ActionStateManager forwarded MouseMoveCommand to MouseMovementActor")
      }
      println(s"ActionStateManager processing command for actionType: $actionType, currentState: $state")

    case ActionCompleted(actionType) =>
      handleActionCompleted(actionType)
      val currentTime = System.currentTimeMillis()
      println(s"Action $actionType completed at $currentTime.")

  }

  def extractActionType(actions: Seq[MouseAction]): ActionTypes.Value = {
    // Example logic to determine action type
    // This is highly simplified and should be replaced with your actual logic
    if (actions.exists(_.action == "heal")) ActionTypes.Heal
    else if (actions.exists(_.action == "attack")) ActionTypes.AttackMonster
    else ActionTypes.Move // Default to Move as a simple example
  }

  def handleActionCompleted(actionType: ActionTypes.Value): Unit = {
    actionStates(actionType) = ("free", System.currentTimeMillis())
    println(s"Action $actionType completed and is now free.")
    // Here you can add any logic that should be triggered after an action is completed.
    // For example, checking if there are queued actions that were waiting for this one to complete.
    // checkForQueuedActions(actionType)
  }


  def isPriorityMet(actionType: ActionTypes.Value): Boolean = {
    // Implement your logic to check if the action's priority allows it to proceed
    true
  }
}

object ActionStateManager {
  def props: Props = Props[ActionStateManager]
}
