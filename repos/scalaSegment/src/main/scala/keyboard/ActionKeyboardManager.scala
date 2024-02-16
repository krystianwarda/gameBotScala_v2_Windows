package keyboard

import akka.actor.{Actor, ActorRef, Props}

case class TypeText(text: String) // Assuming this is recognized across your packages

class ActionKeyboardManager(keyboardActorRef: ActorRef) extends Actor {
  def receive: Receive = {
    case TypeText(text) => keyboardActorRef ! TypeText(text)
    case _ => println("Unhandled keyboard action")
  }
}

object ActionKeyboardManager {
  def props(keyboardActorRef: ActorRef): Props = Props(new ActionKeyboardManager(keyboardActorRef))
}

