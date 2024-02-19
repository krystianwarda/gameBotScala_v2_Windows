package keyboard

import akka.actor.{Actor, ActorRef, Props}
import scala.collection.mutable

// Assuming TypeText is correctly defined as a case class elsewhere in your code
 case class TypeText(text: String)

object KeyboardActionTypes extends Enumeration {
  val TypeText, OtherAction = Value
  val actionPriorities: Map[Value, Int] = Map(
    TypeText -> 1,
    OtherAction -> 2
  )
}

case class KeyboardActionCompleted(actionType: KeyboardActionTypes.Value)

class ActionKeyboardManager(keyboardActorRef: ActorRef) extends Actor {
  val actionStates: mutable.Map[KeyboardActionTypes.Value, (String, Long)] = mutable.Map().withDefaultValue(("free", 0L))

  override def receive: Receive = {
    case textCommand: TypeText =>
      val actionType = KeyboardActionTypes.TypeText
      val currentTime = System.currentTimeMillis()
      val (state, _) = actionStates(actionType)

      println(s"Current state for $actionType: $state") // Debug

      if (state == "free" && isPriorityMet(actionType)) {
        actionStates(actionType) = ("in progress", currentTime)
        println(s"ActionKeyboardManager: Processing TypeText command")
        keyboardActorRef ! textCommand
      } else {
        println(s"ActionKeyboardManager: Skipping TypeText command due to state or priority, current state: $state")
      }

    case KeyboardActionCompleted(actionType) =>
      println(s"Action $actionType completed, setting state to free.") // Debug
      actionStates(actionType) = ("free", System.currentTimeMillis())

    case _ => println("ActionKeyboardManager: Unhandled keyboard action")
  }

  def isPriorityMet(actionType: KeyboardActionTypes.Value): Boolean = {
    // Placeholder for actual priority logic
    true
  }
}

object ActionKeyboardManager {
  def props(keyboardActorRef: ActorRef): Props = Props(new ActionKeyboardManager(keyboardActorRef))
}
