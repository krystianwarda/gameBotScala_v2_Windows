package userUI
// SwingActor.scala
//package src.main.scala.userUI/*/
//import actors.JsonProcessorActor
import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import play.api.libs.json.Json

import scala.swing._
import scala.swing.event._
import java.awt.event._
import player.Player
import processing.InitializeProcessor
import main.scala.MainApp.jsonProcessorActorRef
import userUI.{AutoHealBot, CaveBot, ProtectionZoneBot, RuneMaker, SettingsUtils, SwingApp, TrainerBot}
import utils.StartMouseMovementActor

import java.awt.Dimension
import scala.concurrent.ExecutionContext.Implicits.global
import javax.swing.{ImageIcon, JLabel, JPanel}
import java.awt.{GridBagConstraints, GridBagLayout, Insets}
import scala.runtime.BoxesRunTime.add
import scala.swing.GridBagPanel.Fill
import scala.swing.ListView.Renderer


class UIAppActor(playerClassList: List[Player],
                 jsonProcessorActorRef: ActorRef,
                 periodicFunctionActorRef: ActorRef,
                 thirdProcessActorRef: ActorRef,
                 mainActorRef: ActorRef) extends Actor {


  // Store the current player. For simplicity, let's use the first player from the list.
  // You may want to update this based on your application's logic.
  private val currentPlayer: Option[Player] = playerClassList.headOption

  Swing.onEDT {
    new SwingApp(
      playerClassList,
      self, // the UIAppActor itself
      jsonProcessorActorRef,
      periodicFunctionActorRef,
      thirdProcessActorRef,
      mainActorRef // add the MainActor reference here
    ).visible = true
  }


  def receive: Receive = {
    case StartActors(settings) =>
      // Forward settings to JsonProcessorActor
      // Ensure currentPlayer is not None before sending the message
      currentPlayer.foreach { player =>
        jsonProcessorActorRef ! InitializeProcessor(player, settings)
      }
      // Tell MainActor to start MouseMovementActor if necessary
      if (settings.mouseMovements) {
        mainActorRef ! StartMouseMovementActor
      }
      // Use the settings to start or configure other actors
      println(s"Received settings: $settings")
    // Additional logic for handling StartActors message
  }

  // ... other methods ...
}




