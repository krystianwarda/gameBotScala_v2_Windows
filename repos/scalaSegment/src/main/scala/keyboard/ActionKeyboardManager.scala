package keyboard
import akka.actor.{Actor, ActorRef, Props}
import processing.PushTheButton
import scala.collection.mutable

// Assuming TypeText is already correctly defined
case class TypeText(text: String)

case class KeyboardActionCompleted(actionType: KeyboardActionTypes.Value)

// Definition for PressArrowKey (if not already defined)
case class PressArrowKey(key: String)

object KeyboardActionTypes extends Enumeration {
  val TypeText, PressKey, OtherAction = Value // Added PressKey for clarity
}

class ActionKeyboardManager(keyboardActorRef: ActorRef) extends Actor {
  val actionStates: mutable.Map[KeyboardActionTypes.Value, (String, Long)] = mutable.Map().withDefaultValue(("free", 0L))

  override def receive: Receive = {
    case PushTheButton(key) =>
      // Assuming PushTheButton implies a PressKey action
      processKeyAction(KeyboardActionTypes.PressKey, key)

    case textCommand: TypeText =>
      processTextAction(textCommand)

    case KeyboardActionCompleted(actionType) =>
      // Do not set PressKey actions to "free" to keep them always ready
      if (actionType != KeyboardActionTypes.PressKey) {
        println(s"Action $actionType completed, setting state to free.")
        actionStates.update(actionType, ("free", System.currentTimeMillis()))
      }

    case _ => println("ActionKeyboardManager: Unhandled keyboard action")
  }

  def processKeyAction(actionType: KeyboardActionTypes.Value, key: String): Unit = {
    // Always treat PressKey actions as "free"
    if (actionType == KeyboardActionTypes.PressKey || (actionStates(actionType)._1 == "free" && isPriorityMet(actionType))) {
      println(s"ActionKeyboardManager: Processing key action for $key, treating as always free.")
      keyboardActorRef ! PressArrowKey(key)
      // Note: We do not update the state to "in progress" for PressKey actions to keep them always ready
    } else {
      println("Skipping non-PressKey action due to state or priority")
    }
  }

  def processTextAction(textCommand: TypeText): Unit = {
    val actionType = KeyboardActionTypes.TypeText
    val (state, _) = actionStates(actionType)
    if (state == "free" && isPriorityMet(actionType)) {
      println("ActionKeyboardManager: Processing TypeText command")
      actionStates(actionType) = ("in progress", System.currentTimeMillis())
      keyboardActorRef ! textCommand
    } else {
      println("Skipping TypeText command due to state or priority")
    }
  }

  def isPriorityMet(actionType: KeyboardActionTypes.Value): Boolean = {
    // Placeholder for actual priority logic; adjust as needed
    true
  }
}

object ActionKeyboardManager {
  def props(keyboardActorRef: ActorRef): Props = Props(new ActionKeyboardManager(keyboardActorRef))
}
