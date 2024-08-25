package processing

import main.scala.MainApp.alertSenderActorRef
import mouse.FakeAction
import play.api.libs.json.{JsObject, JsValue}
import userUI.SettingsUtils

import scala.collection.immutable.Seq
import play.api.libs.json._
import processing.CaveBot.Vec
import processing.Process.captureScreen
//import processing.Process.{captureScreen, generateNoise}
import processing.TeamHunt.generateSubwaypointsToBlocker
import utils.{Credentials, SendDiscordAlert, SendSoundAlert}
case class IgnoredCreature(name: String, safe: Boolean)


object Guardian {
  def computeGuardianActions(json: JsValue, settings: SettingsUtils.UISettings, currentState: ProcessorState): ((Seq[FakeAction], Seq[Log]), ProcessorState) = {
    println("Performing computeGuardianActions action.")
    val startTime = System.nanoTime()
    val currentTime = System.currentTimeMillis()
    var actions: Seq[FakeAction] = Seq()
    var logs: Seq[Log] = Seq()
    var updatedState = currentState

    if (settings.guardianSettings.enabled) {
      val playerName = (json \ "characterInfo" \ "Name").as[String]
      // Extract the "spyLevelInfo" object from the JSON
      val spyLevelInfoJson = (json \ "spyLevelInfo").as[JsObject]
      // Extract names from the ignoredCreatures list by splitting the string on the ',' and ':' to get the "Name" part.
      val ignoredCreatures: List[String] = settings.guardianSettings.ignoredCreatures.map { entry =>
        entry.split(",")(0).split(":")(1).trim // Extracts the name part after "Name:"
      }


      // Iterate over each key in spyLevelInfoJson to find players with "IsPlayer" set to true and name not equal to playerName
      spyLevelInfoJson.fields.foreach {
        case (_, playerInfo) =>
          val player = playerInfo.as[JsObject]
          val isPlayer = (player \ "IsPlayer").as[Boolean]
          val playerZLevel = (player \ "PositionZ").as[Int]
          val currentName = (player \ "Name").as[String]

          // Check if the current player's name is in the ignoredCreatures list
          val isIgnored = ignoredCreatures.contains(currentName)

          if (isPlayer && !isIgnored) {
            println(s"Player detected: $currentName at Z level $playerZLevel")

            if (isPlayer && currentName != playerName && settings.guardianSettings.playerOnScreenSettings.head.playerOnScreenSound) {
              val currentZLevel = (json \ "characterInfo" \ "PositionZ").as[Int]
              if (playerZLevel == currentZLevel) {
                println(s"player on the screen: $currentName")
                if (currentTime - updatedState.playerDetectedAlertTime > 120000) { // More than 5000 milliseconds

//                  alertSenderActorRef ! SendSoundAlert("Player on the screen.")
                  updatedState = updatedState.copy(playerDetectedAlertTime = currentTime)
                  val screenshot = captureScreen()
                  println(s"Sending message to AlertActor")
                  alertSenderActorRef ! SendDiscordAlert("Player on the screen.", Some(screenshot), Some(json))
                }
              }
              else {
                println(s"player detected: $currentName")
                if (currentTime - updatedState.playerDetectedAlertTime > 120000) { // More than 5000 milliseconds
                  // Somewhere in your code, when a player is detected:
                  alertSenderActorRef ! SendSoundAlert("Player detected.")

                  if (settings.guardianSettings.playerDetectedSettings.head.playerDetectedDiscord) {
                    val screenshot = captureScreen()
                    alertSenderActorRef ! SendDiscordAlert("Player on the screen.", Some(screenshot), Some(json))

                  }

                  updatedState = updatedState.copy(playerDetectedAlertTime = currentTime)
                  if (updatedState.escapedToSafeZone == "not_set") {
                    val resultEscapeProtectionZone = escapeProtectionZone(json: JsValue, settings, updatedState, actions, logs)
                    actions = resultEscapeProtectionZone._1._1
                    logs = resultEscapeProtectionZone._1._2
                    updatedState = resultEscapeProtectionZone._2
                    updatedState = updatedState.copy(escapedToSafeZone = "escaped")
                  }


                }


              }
            }

          } else {
            // Optionally, handle ignored creatures here if needed
            println(s"Creature $currentName is ignored.")
          }

      }

      if (currentTime - updatedState.playerDetectedAlertTime > 10000) {
        updatedState = updatedState.copy(escapedToSafeZone = "not_set")
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
    println(f"[INFO] Processing computeGuardianActions took $duration%.6f seconds")
    ((actions, logs), updatedState)
  }



  def escapeProtectionZone(json: JsValue,
                           settings: SettingsUtils.UISettings,
                           currentState: ProcessorState,
                           initialActions: Seq[FakeAction],
                           intialLogs: Seq[Log],
                          ): ((Seq[FakeAction], Seq[Log]), ProcessorState) = {
    var actions: Seq[FakeAction] = initialActions
    var logs: Seq[Log] = intialLogs
    var updatedState = currentState

    println("Inside escapeProtectionZone")
    val tiles = (json \ "areaInfo" \ "tiles").as[Map[String, JsObject]]
    val xs = tiles.keys.map(_.substring(0, 5).trim.toInt)
    val ys = tiles.keys.map(_.substring(5, 10).trim.toInt)
    val gridBounds = (xs.min, ys.min, xs.max, ys.max) // Properly define gridBounds here

    val itemTargetOpt = findItemOnArea(json, 2985)
    println(s"itemTargetOpt: ${itemTargetOpt}")
    // Handle Option[Vec] using pattern matching
    itemTargetOpt match {
      case Some(target) =>
        println("Escaping")
        logs :+= Log("Escaping.")

        val actionsSeq = Seq(
          MouseAction(target.x, target.y, "move"),
          MouseAction(target.x, target.y, "pressLeft"),
          MouseAction(target.x, target.y, "releaseLeft")
        )
        actions = actions :+ FakeAction("useMouse", None, Some(MouseActions(actionsSeq)))
        updatedState = updatedState.copy(chasingBlockerLevelChangeTime = updatedState.currentTime)

      case None =>
        println("Item not found")
    }
    ((actions, logs), updatedState)
  }


  def findItemOnArea(json: JsValue, itemIdToFind: Int): Option[Vec] = {
    // Extract the tiles from the JSON
    val tiles = (json \ "areaInfo" \ "tiles").as[Map[String, JsObject]]

    // Collect the tile ID and index that contains the itemIdToFind
    val potentialTileIndexOpt = tiles.collectFirst {
      case (tileId, tileData) if {
        // Create a list of item IDs from the items on the tile
        val itemIds = (tileData \ "items").as[Map[String, JsObject]].values.map(itemData => (itemData \ "id").as[Int]).toList
        // Check if the specific item ID is found in the list
        itemIds.contains(itemIdToFind)
      } =>
        // Return the index of the tile
        (tileData \ "index").as[String]
    }

    println(s"potentialTileIndexOpt: ${potentialTileIndexOpt}")

    // If we found the index, proceed to get the corresponding screenX and screenY
    potentialTileIndexOpt.flatMap { index =>
      // Try to find the corresponding screen location using the index
      (json \ "screenInfo" \ "mapPanelLoc" \ index).asOpt[JsObject].map { screenData =>
        val x = (screenData \ "x").as[Int]
        val y = (screenData \ "y").as[Int]
        Vec(x, y)
      }
    }
  }

}


