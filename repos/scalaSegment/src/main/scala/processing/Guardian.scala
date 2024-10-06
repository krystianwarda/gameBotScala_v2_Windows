package processing

import main.scala.MainApp.alertSenderActorRef
import mouse.FakeAction
import play.api.libs.json.{JsObject, JsValue}
import userUI.SettingsUtils

import scala.collection.immutable.Seq
import play.api.libs.json._
import processing.CaveBot.Vec
import processing.Process.captureScreen

import java.lang.System.currentTimeMillis
//import processing.Process.{captureScreen, generateNoise}
import utils.{Credentials, SendDiscordAlert, SendSoundAlert}
case class IgnoredCreature(name: String, safe: Boolean)

object Guardian {

  def computeGuardianActions(
                              json: JsValue,
                              settings: SettingsUtils.UISettings,
                              currentState: ProcessorState
                            ): ((Seq[FakeAction], Seq[Log]), ProcessorState) = {

    println("Performing computeGuardianActions action.")
    val startTime = System.nanoTime()
    val currentTime = System.currentTimeMillis()
    var updatedState = currentState
    // Initialize actions, logs, and updatedState as vars to allow updates
    var actions: Seq[FakeAction] = Seq.empty
    var logs: Seq[Log] = Seq.empty

    // Collect state transformations
    val stateTransformations: List[ProcessorState => ProcessorState] = List()


    if (settings.guardianSettings.enabled) {

      val playerName = (json \ "characterInfo" \ "Name").as[String]
      val spyLevelInfoJson = (json \ "spyLevelInfo").as[JsObject]
      val ignoredCreatures = parseIgnoredCreatures(settings)
      val currentZLevel = (json \ "characterInfo" \ "PositionZ").as[Int]

      // Player detected
      val (newActions1, newState1) = triggerAlertIf(
        condition = AlertLogic.shouldTriggerAlert(updatedState.guardian.playerDetectedAlertTime, currentTime, 10000),
        currentState = updatedState,
        alertActions = state => {
          val newState = state.copy(
            guardian = state.guardian.copy(playerDetectedAlertTime = currentTime)
          )

          val alertSettings = settings.guardianSettings.playerOnScreenSettings.head

          var newActions = Seq.empty[FakeAction]

          if (alertSettings.playerOnScreenSound)
            GuardianActionExecutor.executeSoundAlert("Player on screen detected.")

          if (alertSettings.playerOnScreenMessage)
            newActions = newActions :+ GuardianActionExecutor.executeInGameMessage(s"Player detected!")

          // Return the new state and actions
          (newActions, newState)
        }
      )

      // Update actions and state
      actions = actions ++ newActions1
      updatedState = newState1

      // Check for player on screen
      AlertLogic.logicPlayerOnScreen(spyLevelInfoJson, currentZLevel, ignoredCreatures, playerName).foreach { detectedPlayerName =>
        if (AlertLogic.shouldTriggerAlert(updatedState.guardian.playerDetectedAlertTime, currentTime, 10000)) {
          val newState = updatedState.copy(
            guardian = updatedState.guardian.copy(playerDetectedAlertTime = currentTime)
          )

          val alertSettings = settings.guardianSettings.playerOnScreenSettings.head

          var newActions = actions

          if (alertSettings.playerOnScreenSound)
            GuardianActionExecutor.executeSoundAlert("Player on screen detected.")

          if (alertSettings.playerOnScreenMessage)
            newActions = newActions :+ GuardianActionExecutor.executeInGameMessage(s"Player $detectedPlayerName is on screen!")

          if (alertSettings.playerOnScreenDiscord)
            GuardianActionExecutor.executeDiscordAlert("Player on screen detected.", json)

          if (alertSettings.playerOnScreenLogout)
            newActions = newActions :+ GuardianActionExecutor.executeLogout()

          if (alertSettings.playerOnScreenPz) {
            val (pzActions, finalState) = GuardianActionExecutor.executePzMove(json, settings, newState, newActions)
            actions = pzActions
            updatedState = finalState
          } else {
            actions = newActions
            updatedState = newState
          }
        }
      }
    }

    println(s"time10: ${currentTime - updatedState.guardian.playerDetectedAlertTime}")
    val endTime = System.nanoTime()
    val duration = (endTime - startTime) / 1e9d
    println(f"[INFO] Processing computeGuardianActions took $duration%.6f seconds")


    // Apply all transformations at once
    updatedState = stateTransformations.foldLeft(currentState)((state, transform) => transform(state))

    ((actions, logs), updatedState)  // Return final actions and updated state
  }

  def triggerAlertIf(
                      condition: Boolean,
                      currentState: ProcessorState,
                      alertActions: ProcessorState => (Seq[FakeAction], ProcessorState)
                    ): (Seq[FakeAction], ProcessorState) = {
    if (condition) alertActions(currentState)
    else (Seq.empty, currentState)
  }

  object AlertLogic {

    def shouldTriggerAlert(
                            lastAlertTime: Long,
                            currentTime: Long,
                            cooldownMillis: Long
                          ): Boolean = {
      (currentTime - lastAlertTime) >= cooldownMillis
    }

    def logicPlayerOnScreen(
                             spyLevelInfoJson: JsObject,
                             currentZLevel: Int,
                             ignoredPlayers: List[String],
                             playerName: String
                           ): Option[String] = {
      spyLevelInfoJson.fields.collectFirst {
        case (_, playerInfo) =>
          val player = playerInfo.as[JsObject]
          val name = (player \ "Name").as[String]
          val isPlayer = (player \ "IsPlayer").asOpt[Boolean].getOrElse(false)
          val positionZ = (player \ "PositionZ").as[Int]
          if (isPlayer && positionZ == currentZLevel && !ignoredPlayers.contains(name) && name != playerName)
            Some(name)
          else
            None
      }.flatten
    }

    def logicPlayerDetected(
                             spyLevelInfoJson: JsObject,
                             currentZLevel: Int,
                             ignoredPlayers: List[String],
                             playerName: String
                           ): Option[String] = {
      spyLevelInfoJson.fields.collectFirst {
        case (_, playerInfo) =>
          val player = playerInfo.as[JsObject]
          val name = (player \ "Name").as[String]
          val isPlayer = (player \ "IsPlayer").asOpt[Boolean].getOrElse(false)
          val positionZ = (player \ "PositionZ").as[Int]
          if (isPlayer && positionZ != currentZLevel && !ignoredPlayers.contains(name) && name != playerName)
            Some(name)
          else
            None
      }.flatten
    }

    def detectLowSupplies(
                           suppliesLeftMap: Map[String, Int]
                         ): Boolean = {
      suppliesLeftMap.values.exists(_ <= 1)
    }

  }

  object GuardianActionExecutor {

    def executeSoundAlert(message: String): Unit = {
      alertSenderActorRef ! SendSoundAlert(message)
    }

    def executeDiscordAlert(message: String, json: JsValue): Unit = {
      alertSenderActorRef ! SendDiscordAlert(message, Some(json))
    }

    def executeInGameMessage(text: String): FakeAction = {
      FakeAction("typeText", None, Some(KeyboardText(text)))
    }

    def executeLogout(): FakeAction = {
      val comboActionDetail = ComboKeyActions("Ctrl", Seq("L"))
      FakeAction("pressMultipleKeys", None, Some(comboActionDetail))
    }

    // Implement PZ move action if required
    def executePzMove(
                       json: JsValue,
                       settings: SettingsUtils.UISettings,
                       currentState: ProcessorState,
                       initialActions: Seq[FakeAction]
                     ): (Seq[FakeAction], ProcessorState) = {
      // Your existing implementation for moving to a safe zone
      // For demonstration, we'll return the initial actions and state
      (initialActions, currentState)
    }

  }


  private def parseIgnoredCreatures(settings: SettingsUtils.UISettings): List[String] = {
    settings.guardianSettings.ignoredCreatures.map { entry =>
      entry.split(",")(0).split(":")(1).trim
    }
  }




//  private def triggerPlayerOnScreenActions(
//                                            player: JsObject,
//                                            settings: SettingsUtils.UISettings,
//                                            currentTime: Long,
//                                            json: JsValue,
//                                            initialState: ProcessorState
//                                          ): (Seq[FakeAction], ProcessorState) = {
//    var updatedState = initialState
//    println(s"time2: ${currentTime - updatedState.guardian.playerDetectedAlertTime}")
//    if (currentTime - updatedState.guardian.playerDetectedAlertTime > 15000) {
//
//      updatedState = updatedState.copy(playerDetectedAlertTime = currentTime)
//
//
//      println(s"time3: ${currentTime - updatedState.guardian.playerDetectedAlertTime}")
//      val currentName = (player \ "Name").as[String]
//      var actions = Seq[FakeAction]()
//
//      if (settings.guardianSettings.playerOnScreenSettings.head.playerOnScreenSound) {
//        triggerSoundAlert("Player on the screen.")
//      }
//      if (settings.guardianSettings.playerOnScreenSettings.head.playerOnScreenMessage) {
//        actions = actions :+ FakeAction("typeText", None, Some(KeyboardText(currentName)))
//      }
//      if (settings.guardianSettings.playerOnScreenSettings.head.playerOnScreenDiscord) {
//        triggerDiscordAlert("Player on the screen.", json)
//      }
//      if (settings.guardianSettings.playerOnScreenSettings.head.playerOnScreenLogout) {
//        actions = triggerLogoutAlert(settings, json, actions)
//      }
//      if (settings.guardianSettings.playerOnScreenSettings.head.playerOnScreenPz) {
//        val resultTriggerPzAlert = triggerPzAlert(json, settings, updatedState, actions)
//        actions = resultTriggerPzAlert._1
//        (actions, updatedState)
//      } else {
//        (actions, updatedState)
//      }
//    } else {
//      (Seq.empty, updatedState)
//    }
//  }

  private def isPlayer(player: JsObject): Boolean = {
    (player \ "IsPlayer").asOpt[Boolean].getOrElse(false)
  }

  private def isPlayerOnScreen(player: JsObject, currentZLevel: Int): Boolean = {
    val playerZLevel = (player \ "PositionZ").as[Int]
    playerZLevel == currentZLevel
  }

  private def isPlayerDetected(player: JsObject, currentZLevel: Int): Boolean = {
    val playerZLevel = (player \ "PositionZ").as[Int]
    playerZLevel != currentZLevel
  }

  private def isIgnoredPlayer(player: JsObject, ignoredCreatures: List[String], playerName: String): Boolean = {
    val currentName = (player \ "Name").as[String]
    ignoredCreatures.contains(currentName) || currentName == playerName
  }



//  private def triggerPlayerDetectedActions(
//                                            player: JsObject,
//                                            settings: SettingsUtils.UISettings,
//                                            currentTime: Long,
//                                            json: JsValue,
//                                            initialState: ProcessorState
//                                          ): (Seq[FakeAction], ProcessorState) = {
//    var updatedState = initialState
//    if (currentTime - updatedState.playerDetectedAlertTime > 15000) {
//      val currentName = (player \ "Name").as[String]
//      var actions: Seq[FakeAction] = Seq.empty
//      updatedState = updatedState.copy(playerDetectedAlertTime = currentTime)
//
//      if (settings.guardianSettings.playerDetectedSettings.head.playerDetectedSound) {
//        triggerSoundAlert("Player detected.")
//      }
//      if (settings.guardianSettings.playerDetectedSettings.head.playerDetectedMessage) {
//        actions = actions :+ FakeAction("typeText", None, Some(KeyboardText(currentName)))
//      }
//      if (settings.guardianSettings.playerDetectedSettings.head.playerDetectedDiscord) {
//        triggerDiscordAlert("Player detected.", json)
//      }
//      if (settings.guardianSettings.playerDetectedSettings.head.playerDetectedLogout) {
//        actions = triggerLogoutAlert(settings, json, actions)
//      }
//      if (settings.guardianSettings.playerDetectedSettings.head.playerDetectedPz) {
//        val resultTriggerPzAlert = triggerPzAlert(json, settings, updatedState, actions)
//        actions = resultTriggerPzAlert._1
//      }
//      (actions, updatedState)
//    } else {
//      (Seq.empty, updatedState)
//    }
//  }

//  private def triggerLowSuppliesActions(
//                                         settings: SettingsUtils.UISettings,
//                                         json: JsValue,
//                                         state: ProcessorState
//                                       ): (Seq[FakeAction], ProcessorState) = {
//    var actions = Seq[FakeAction]()
//
//    if (settings.guardianSettings.lowCapSettings.head.lowCapSound) {
//      triggerSoundAlert("Low supplies detected.")
//    }
//    if (settings.guardianSettings.lowCapSettings.head.lowCapMessage) {
//      actions = actions :+ FakeAction("typeText", None, Some(KeyboardText("Low supplies detected!")))
//    }
//    if (settings.guardianSettings.lowCapSettings.head.lowCapDiscord) {
//      triggerDiscordAlert("Low supplies detected!", json)
//    }
//    if (settings.guardianSettings.lowCapSettings.head.lowCapLogout) {
//      actions = triggerLogoutAlert(settings, json, actions)
//    }
//    if (settings.guardianSettings.lowCapSettings.head.lowCapPz) {
//      val resultTriggerPzAlert = triggerPzAlert(json, settings, state, actions)
//      (resultTriggerPzAlert._1, resultTriggerPzAlert._2)
//    } else {
//      (actions, state)
//    }
//  }


  // Example alert trigger: Discord alert
  def triggerDiscordAlert(alertMessage: String, json: JsValue): Unit = {
    alertSenderActorRef ! SendDiscordAlert(alertMessage, Some(json))
  }

  // Higher-order function to trigger an alert based on a condition
  def triggerAlertIf(condition: => Boolean, alertActions: => Unit): Unit = {
    if (condition) alertActions
  }

  // Example condition checker: Is the player on the same level as the current player?
  def isPlayerOnScreen(playerZLevel: Int, currentZLevel: Int): Boolean = {
    playerZLevel == currentZLevel
  }

  // Example condition checker: Is the player detected on a different level?
  def isPlayerDetected(playerZLevel: Int, currentZLevel: Int): Boolean = {
    playerZLevel != currentZLevel
  }

  // Example alert trigger: Sound alert
  def triggerSoundAlert(alertMessage: String): Unit = {
    alertSenderActorRef ! SendSoundAlert(alertMessage)
  }

  def triggerPzAlert(json: JsValue,
                     settings: SettingsUtils.UISettings,
                     currentState: ProcessorState,
                     initialActions: Seq[FakeAction],
                    ): (Seq[FakeAction], ProcessorState) = {

    val resultEscapeProtectionZone = escapeProtectionZone(json: JsValue, settings, currentState, initialActions)
    val actions = resultEscapeProtectionZone._1
    var updatedState = resultEscapeProtectionZone._2
    updatedState = updatedState.copy(escapedToSafeZone = "escaped")

    (actions, updatedState)
  }

  def triggerLogoutAlert(settings: SettingsUtils.UISettings, json: JsValue, actions: Seq[FakeAction]): Seq[FakeAction] = {
    var updatedActions = actions

    // Trigger Discord alert if enabled
    if (settings.guardianSettings.playerDetectedSettings.head.playerDetectedDiscord) {
      triggerDiscordAlert("Player detected.", json)
    }

    // Check if logout action is enabled
    if (settings.guardianSettings.playerDetectedSettings.head.playerDetectedLogout) {
      // Create the ComboKeyActions with Ctrl and L
      val comboActionDetail = ComboKeyActions("Ctrl", Seq("L"))

      // Create the FakeAction for pressing multiple keys
      val comboAction = FakeAction("pressMultipleKeys", None, Some(comboActionDetail))

      // Add this action to the actions sequence
      updatedActions = updatedActions :+ comboAction
    }

    updatedActions
  }


  def escapeProtectionZone(json: JsValue,
                           settings: SettingsUtils.UISettings,
                           currentState: ProcessorState,
                           initialActions: Seq[FakeAction],
                          ): (Seq[FakeAction],  ProcessorState) = {
    var actions: Seq[FakeAction] = initialActions
    var updatedState = currentState


    val tiles = (json \ "areaInfo" \ "tiles").as[Map[String, JsObject]]
    val xs = tiles.keys.map(_.substring(0, 5).trim.toInt)
    val ys = tiles.keys.map(_.substring(5, 10).trim.toInt)
    val gridBounds = (xs.min, ys.min, xs.max, ys.max) // Properly define gridBounds here

    val itemTargetOpt = findItemOnArea(json, 2985)

    // Handle Option[Vec] using pattern matching
    itemTargetOpt match {
      case Some(target) =>


        val actionsSeq = Seq(
          MouseAction(target.x, target.y, "move"),
          MouseAction(target.x, target.y, "pressLeft"),
          MouseAction(target.x, target.y, "releaseLeft")
        )
        actions = actions :+ FakeAction("useMouse", None, Some(MouseActions(actionsSeq)))
        updatedState = updatedState.copy(chasingBlockerLevelChangeTime = updatedState.currentTime)

      case None =>
//        println("Item not found")
    }
    (actions, updatedState)
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



