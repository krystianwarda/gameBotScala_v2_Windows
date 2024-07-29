package processing

import main.scala.MainApp.alertSenderActorRef
import mouse.FakeAction
import play.api.libs.json.{JsObject, JsValue}
import userUI.SettingsUtils

import scala.collection.immutable.Seq
import play.api.libs.json._
import processing.Process.{captureScreen, generateNoise}
import utils.{Credentials, SendDiscordAlert}


object ProtectionZone {
  def computeProtectionZoneActions(json: JsValue, settings: SettingsUtils.UISettings, currentState: ProcessorState): ((Seq[FakeAction], Seq[Log]), ProcessorState) = {
    println("Performing computeProtectionZoneActions action.")
    val startTime = System.nanoTime()
    val currentTime = System.currentTimeMillis()
    var actions: Seq[FakeAction] = Seq()
    var logs: Seq[Log] = Seq()
    var updatedState = currentState

    if (settings.protectionZoneSettings.enabled) {
      val playerName = (json \ "characterInfo" \ "Name").as[String]
      // Extract the "spyLevelInfo" object from the JSON
      val spyLevelInfoJson = (json \ "spyLevelInfo").as[JsObject]

      // Iterate over each key in spyLevelInfoJson to find players with "IsPlayer" set to true and name not equal to playerName
      spyLevelInfoJson.fields.foreach {
        case (_, playerInfo) =>
          val player = playerInfo.as[JsObject]
          val isPlayer = (player \ "IsPlayer").as[Boolean]
          val playerZLevel = (player \ "PositionZ").as[Int]
          val currentName = (player \ "Name").as[String]

          if (isPlayer && currentName != playerName) {
            val currentZLevel = (json \ "characterInfo" \ "PositionZ").as[Int]
            if (playerZLevel == currentZLevel) {
              println(s"player on the screen: $currentName")
              if (currentTime - updatedState.playerDetectedAlertTime > 10000) { // More than 5000 milliseconds
                generateNoise("Player on the screen.")
//                val screenshot = captureScreen()
//                println(s"Sending message to AlertActor")
//                alertSenderActorRef ! SendDiscordAlert("Player on the screen.", Some(screenshot), Some(json))
                updatedState = updatedState.copy(playerDetectedAlertTime = currentTime)
              }
            }
//            else {
//              println(s"player detected: $currentName")
//              if (currentTime - updatedState.playerDetectedAlertTime > 5000) { // More than 5000 milliseconds
//                generateNoise("Player detected.")
//                updatedState = updatedState.copy(playerDetectedAlertTime = currentTime)
//              }
//            }
          }
      }



//      if (settings.protectionZoneSettings.escapeToProtectionZone) {
//        val ignoredCreaturesList = settings.protectionZoneSettings.ignoredCreatures
//        // Print elements of the ignoredCreaturesList
//        println(s"Ignored Creatures List: ${ignoredCreaturesList.mkString(", ")}")
//


//        // Initialize a list to keep track of all creatures that are not NPCs and not ignored
//        var detectedCreatures: Seq[String] = Seq()
//
//        // Iterate through each creature in the spyLevelInfo
//        spyLevelInfoJson.fields.foreach { case (_, creatureInfo) =>
//          val isNpc = (creatureInfo \ "IsNpc").as[Boolean]
//          val name = (creatureInfo \ "Name").as[String]
//
//          // Check if the creature is not an NPC and not in the ignored list
//          if (!isNpc && !ignoredCreaturesList.contains(name) && !ignoredCreaturesList.contains(playerName)) {
//            detectedCreatures :+= name // Add the creature's name to the list
//          }
//        }
//
//        // Log all detected creatures that are not NPCs and not ignored
//        if (detectedCreatures.nonEmpty) {
////          println(s"Detected Creatures: ${detectedCreatures.mkString(", ")}")
//          logs = logs :+ Log("I have to hide!")
//
//
//        } else {
//          logs = logs :+ Log("I am safe")
//        }
//      }
    }
    val endTime = System.nanoTime()
    val duration = (endTime - startTime) / 1e9d
    println(f"[INFO] Processing computeProtectionZoneActions took $duration%.6f seconds")
    ((actions, logs), updatedState)
  }
}


