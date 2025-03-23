package userUI
// SwingActor.scala
//package src.main.scala.userUI/*/
//import actors.JsonProcessorActor
import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import cats.effect.{IO, Ref}
import main.scala.MainApp
import play.api.libs.json.Json

import scala.swing._
import scala.swing.event._
import java.awt.event._
import player.Player
import processing.InitializeProcessor
import main.scala.MainApp.jsonProcessorActorRef
import userUI.SettingsUtils.UISettings
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
                 mainActorRef: ActorRef,
                 settingsRef: Ref[IO, UISettings]) extends Actor {

  override def preStart(): Unit = {
    initializeUI() // Call initialization when actor starts
  }

  def receive: Receive = {
    case _ =>
      println("UIAppActor received a message.")
    // Handle other messages as needed
  }

  private def initializeUI(): Unit = {
    Swing.onEDT {
      new SwingApp(
        playerClassList,
        self, // the UIAppActor itself
        jsonProcessorActorRef,
        periodicFunctionActorRef,
        thirdProcessActorRef,
        mainActorRef,
        settingsRef
      ).visible = true
    }
    println("UIAppActor UI initialized.")
  }
}


