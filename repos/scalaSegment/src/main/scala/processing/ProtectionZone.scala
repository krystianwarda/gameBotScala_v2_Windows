package processing

import mouse.FakeAction
import play.api.libs.json.{JsObject, JsValue}
import userUI.SettingsUtils

import scala.collection.immutable.Seq

import play.api.libs.json._

object ProtectionZone {
  def computeProtectionZoneActions(json: JsValue, settings: SettingsUtils.UISettings): (Seq[FakeAction], Seq[Log]) = {
    var actions: Seq[FakeAction] = Seq()
    var logs: Seq[Log] = Seq()

    if (settings.protectionZoneSettings.enabled) {
      val playerName = (json \ "characterInfo" \ "Name").as[String]
      if (settings.protectionZoneSettings.escapeToProtectionZone) {
        val ignoredCreaturesList = settings.protectionZoneSettings.ignoredCreatures
        // Print elements of the ignoredCreaturesList
//        println(s"Ignored Creatures List: ${ignoredCreaturesList.mkString(", ")}")

        // Extract the "spyLevelInfo" object from the JSON
        val creatures = (json \ "spyLevelInfo").as[JsObject]

        // Initialize a list to keep track of all creatures that are not NPCs and not ignored
        var detectedCreatures: Seq[String] = Seq()

        // Iterate through each creature in the spyLevelInfo
        creatures.fields.foreach { case (_, creatureInfo) =>
          val isNpc = (creatureInfo \ "IsNpc").as[Boolean]
          val name = (creatureInfo \ "Name").as[String]

          // Check if the creature is not an NPC and not in the ignored list
          if (!isNpc && !ignoredCreaturesList.contains(name) && !ignoredCreaturesList.contains(playerName)) {
            detectedCreatures :+= name // Add the creature's name to the list
          }
        }

        // Log all detected creatures that are not NPCs and not ignored
        if (detectedCreatures.nonEmpty) {
//          println(s"Detected Creatures: ${detectedCreatures.mkString(", ")}")
          logs = logs :+ Log("I have to hide!")


        } else {
          logs = logs :+ Log("I am safe")
        }
      }
    }

    (actions, logs)
  }
}
