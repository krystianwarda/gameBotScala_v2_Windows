package utils

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import com.github.kwhat.jnativehook.GlobalScreen
import com.github.kwhat.jnativehook.keyboard.{NativeKeyEvent, NativeKeyListener}
//import java.util.logging.{Level, Logger}



// Messages for the actor
sealed trait KeyEvent
case object EscapePressed extends KeyEvent
case object ResetCounter extends KeyEvent

// Actor for tracking Escape key presses
class EscapeKeyListener extends Actor {
  var escapeCount = 0

  def receive: Receive = {
    case EscapePressed =>
      escapeCount += 1
      if (escapeCount >= 3) {
        println("Escape key pressed 3 times! Stopping bot...")
        System.exit(0)
      }
    case ResetCounter =>
      println("reset counter")
      escapeCount = 0
  }
}

// JNativeHook Key Listener
class EscapeKeyHandler(actor: ActorRef) extends NativeKeyListener {
  override def nativeKeyPressed(e: NativeKeyEvent): Unit = {
    if (e.getKeyCode == NativeKeyEvent.VC_ESCAPE) {
      actor ! EscapePressed
    }
  }
  override def nativeKeyReleased(e: NativeKeyEvent): Unit = {}
  override def nativeKeyTyped(e: NativeKeyEvent): Unit = {}
}

//// Main application
//object EscapeKeyListener extends App {
//  // Disable JNativeHook logging
//  val logger = Logger.getLogger(classOf[GlobalScreen].getPackage.getName)
//  logger.setLevel(Level.OFF)
//
//  // Akka Actor System
//  val system = ActorSystem("EscapeListenerSystem")
//  val escapeActor = system.actorOf(Props[EscapeKeyActor], "EscapeKeyActor")
//
//  // Register global key listener
//  GlobalScreen.registerNativeHook()
//  GlobalScreen.addNativeKeyListener(new EscapeKeyHandler(escapeActor))
//
//  println("Listening for Escape key presses...")
//}
