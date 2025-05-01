//package utils
//
//import akka.actor.{Actor, ActorRef, Props}
//import mouse.MouseMovementActor
//
//class MainActor extends Actor {
//  var mouseMovementActorRef: Option[ActorRef] = None
//
//  def receive: Receive = {
//    case StartMouseMovementActor =>
////      if (mouseMovementActorRef.isEmpty) {
////        mouseMovementActorRef = Some(context.system.actorOf(Props[MouseMovementActor], "mouseMovementActor"))
////        println("MouseMovementActor activated.")
////      }
//  }
//}
//
//// Message to trigger the start of MouseMovementActor
//case object StartMouseMovementActor
//
