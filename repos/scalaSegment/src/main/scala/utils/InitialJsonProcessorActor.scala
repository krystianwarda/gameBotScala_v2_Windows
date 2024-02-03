package utils

import akka.actor.Actor
import akka.actor.Actor.Receive
import main.scala.MainApp
import play.api.libs.json.JsValue
import player.Player


class InitialJsonProcessorActor extends Actor {
  override def receive: Receive = {
    case MainApp.JsonData(json) =>
      println(s"Innitial Json received.")
      println(s"InitialJsonProcessorActor received JSON: ${json}")

      val name = (json \ "characterInfo" \ "Name").as[String]

      // Create a new Player object with the extracted name
      val player = new Player(name)

      println(s"InitialJsonProcessorActor created Player: ${player.characterName}")

    case other =>
      println(s"InitialJsonProcessorActor received an unrecognized message of type ${other.getClass.getName}: $other")
  }
}




