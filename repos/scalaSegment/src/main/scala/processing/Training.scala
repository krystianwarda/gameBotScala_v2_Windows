package processing

import mouse.FakeAction
import play.api.libs.json.{JsObject, JsValue, Json}
import processing.Process.{findBackpackPosition, isJsonNotEmpty}
import userUI.SettingsUtils


object Training {

  def computeTrainingActions(json: JsValue, settings: SettingsUtils.UISettings): (Seq[FakeAction], Seq[Log]) = {
    var actions: Seq[FakeAction] = Seq()
    var logs: Seq[Log] = Seq()
    logs = logs :+ Log("computeTrainingActions activated.")

    if (settings.trainingSettings.enabled) {
      // Safely extracting "attackInfo" as a JsObject using .asOpt
      val attackInfoOpt: Option[JsObject] = (json \ "attackInfo").asOpt[JsObject]

      attackInfoOpt match {
        case Some(attackInfoJson) =>
          val attackInfoString: String = Json.stringify(attackInfoJson)
          logs = logs :+ Log(attackInfoString)
//          logs = logs :+ Log("Monster is being attacked.")

          val slot6Position = (json \ "screenInfo" \ "inventoryPanelLoc" \ "inventoryWindow" \ "contentsPanel" \ "slot6").as[JsObject]
          val slot6X = (slot6Position \ "x").as[Int]
          val slot6Y = (slot6Position \ "y").as[Int]

          val monsterPositionOpt = (attackInfoJson \ "Position").asOpt[JsObject]
          monsterPositionOpt.foreach { monsterPosition =>
            val monsterXPosition = (monsterPosition \ "x").asOpt[Int]
            val monsterYPosition = (monsterPosition \ "y").asOpt[Int]
            val monsterZPosition = (monsterPosition \ "z").asOpt[Int]
            val monsterHealth = (attackInfoJson \ "HealthPercent").asOpt[Int]
//            logs = logs :+ Log(s"Extracted monster health: $monsterHealth")

            val monsterPositionString = for {
              x <- monsterXPosition
              y <- monsterYPosition
              z <- monsterZPosition
            } yield s"$x$y${z.formatted("%02d")}" // Formats z to always have two digits

//            logs = logs :+ Log(s"monsterPositionString: $monsterPositionString")
            monsterPositionString.foreach { posString =>
              val monsterTileItemsIds = (json \ "areaInfo" \ "tiles" \ posString \ "items").asOpt[JsObject].map(_.values.map(_.as[Int]).toList)
              val currentFightMode = (json \ "characterInfo" \ "FightMode").as[Int]
//              logs = logs :+ Log(s"monsterTileItemsIds: $monsterTileItemsIds")
//              logs = logs :+ Log(s"doNotKillTarget setting: ${settings.trainingSettings.doNotKillTarget}")
              monsterTileItemsIds.foreach { ids =>

                if (ids.contains(2889) && currentFightMode == 1) {
//                  logs = logs :+ Log("Switching to defence fight mode.")
                  if (settings.mouseMovements) {
                    val slot19Position = (json \ "screenInfo" \ "inventoryPanelLoc" \ "inventoryWindow" \ "contentsPanel" \ "slot19").as[JsObject]
                    val slot19X = (slot19Position \ "x").as[Int]
                    val slot19Y = (slot19Position \ "y").as[Int]
                    val actionsSeq = Seq(
                      MouseAction(slot19X, slot19Y, "move"),
                      MouseAction(slot19X, slot19Y, "pressLeft"),
                      MouseAction(slot19X, slot19Y, "releaseLeft"),
                    )
                    logs = logs :+ Log("Move mouse to switch to defence fight mode.")
                    actions = actions :+ FakeAction("useMouse", None, Some(MouseActions(actionsSeq)))
                  } else {
                    val attackMode: JsValue = Json.obj(
                      "id" -> 3,
                    )
                    logs = logs :+ Log("Sent function to switch to defence fight mode.")
                    actions = actions :+ FakeAction("switchAttackModeFunction", None, Some(JsonActionDetails(attackMode)))
                  }
                }
                if (settings.trainingSettings.doNotKillTarget && monsterHealth.exists(_ < 50)) {
                  logs = logs :+ Log("Monster is dying, stop attack.")

                }

                if (settings.trainingSettings.switchAttackModeToEnsureDamage && !ids.contains(2889)) {

//                  logs = logs :+ Log("No hit on monster last 45 seconds. Need to attack with dmg.")

                  if (currentFightMode == 2 || currentFightMode == 3) {

                    if (settings.mouseMovements) {
//                      logs = logs :+ Log("Changing attack mode with mouse.")
                      val slot15Position = (json \ "screenInfo" \ "inventoryPanelLoc" \ "inventoryWindow" \ "contentsPanel" \ "slot15").as[JsObject]
                      val slot15X = (slot15Position \ "x").as[Int]
                      val slot15Y = (slot15Position \ "y").as[Int]
                      val actionsSeq = Seq(
                        MouseAction(slot15X, slot15Y, "move"),
                        MouseAction(slot15X, slot15Y, "pressLeft"),
                        MouseAction(slot15X, slot15Y, "releaseLeft"),
                      )
                      logs = logs :+ Log("Move mouse to switch to offensive fight mode.")
                      actions = actions :+ FakeAction("useMouse", None, Some(MouseActions(actionsSeq)))

                    } else {
                      val attackMode: JsValue = Json.obj(
                        "id" -> 1,
                      )
                      logs = logs :+ Log("Sent function to switch to offensive fight mode.")
                      actions = actions :+ FakeAction("switchAttackModeFunction", None, Some(JsonActionDetails(attackMode)))

                    }
                  } else {
                    logs = logs :+ Log("Fight mode already on offensive.")
                  }
                }

                if (settings.trainingSettings.switchWeaponToEnsureDamage && !ids.contains(2889)) {
                  logs = logs :+ Log("No hit on monster last 45 seconds. Need to attack with dmg.")
                  if (settings.mouseMovements) {
                    logs = logs :+ Log("Changing weapon with mouse.")
//
//                    val backpackPositionResult: Option[(FakeAction, Log)] = findBackpackPosition(json).map { backpackSlotForBlank =>
//                      val endX = (backpackSlotForBlank \ "x").as[Int]
//                      val endY = (backpackSlotForBlank \ "y").as[Int]
//                    }
//
//                    val actionsSeq = Seq(
//                      MouseAction(endX, endY, "move"),
//                      MouseAction(endX, endY, "pressLeft"),
//                      MouseAction(slot6X, slot6Y, "move"),
//                      MouseAction(slot6X, slot6Y, "releaseLeft"),
//                    )
//                    actions = actions :+ FakeAction("useMouse", Some(ItemInfo(3147, Option(1))), Some(MouseActions(actionsSeq)))

                  } else {
                    logs = logs :+ Log("Changing weapon with function.")
                  }
                }
              }
            }
          }

        case None =>
          logs = logs :+ Log("No monster being attacked.")
      }
    }

    (actions, logs)
  }
}
// 2889 // krew pelna
// 2890 // krew po czasie
// 45 sekund
