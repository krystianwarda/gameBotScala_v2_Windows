package processing

import mouse.{FakeAction, ItemInfo}
import play.api.libs.json.{JsDefined, JsNumber, JsObject, JsValue, Json}
import userUI.SettingsUtils

import scala.collection.immutable.Seq
import scala.util.Random

object Fishing {


  def computeFishingActions(json: JsValue, settings: SettingsUtils.UISettings): (Seq[FakeAction], Seq[Log]) = {
    var actions: Seq[FakeAction] = Seq()
    var logs: Seq[Log] = Seq()

    if (settings.fishingSettings.enabled) {
      logs = logs :+ Log("I want a fish!")
      println("Selected tiles: " + settings.fishingSettings.selectedRectangles.mkString(", "))
      println(settings.fishingSettings.selectedRectangles.nonEmpty)
      if (settings.fishingSettings.selectedRectangles.nonEmpty) {

        if (settings.mouseMovements) {
          val randomTileId = scala.util.Random.shuffle(settings.fishingSettings.selectedRectangles.toList).head

          // Use the randomTileId to access the JSON structure
          val targetTileScreenX = (json \ "screenInfo" \ "mapPanelLoc" \ randomTileId \ "x").asOpt[Int].getOrElse(0)
          val targetTileScreenY = (json \ "screenInfo" \ "mapPanelLoc" \ randomTileId \ "y").asOpt[Int].getOrElse(0)
          val arrowsX = (json \ "screenInfo" \ "inventoryPanelLoc" \ "inventoryWindow" \ "contentsPanel" \ "slot10" \ "x").asOpt[Int].getOrElse(0)
          val arrowsY = (json \ "screenInfo" \ "inventoryPanelLoc" \ "inventoryWindow" \ "contentsPanel" \ "slot10" \ "y").asOpt[Int].getOrElse(0)


          println(s"Random Tile ID: $randomTileId, targetTileScreenX: $targetTileScreenX, targetTileScreenY: $targetTileScreenY, arrowsX: $arrowsX, arrowsY: $arrowsY")
          val actionsSeq = Seq(
            MouseAction(arrowsX, arrowsY, "move"),
            MouseAction(arrowsX, arrowsY, "pressRight"),
            MouseAction(arrowsX, arrowsY, "releaseRight"),
            MouseAction(targetTileScreenX, targetTileScreenY, "move"),
            MouseAction(targetTileScreenX, targetTileScreenY, "pressLeft"),
            MouseAction(targetTileScreenX, targetTileScreenY, "releaseLeft")
          )

          actions = actions :+ FakeAction("useMouse", Some(ItemInfo(2874, Option(7))), Some(MouseActions(actionsSeq)))
          logs = logs :+ Log(s"Using fishing rod with mouse")
        } else {
          println("Chosing the tiles from saved list - function")
          // to be finished when tiles symbols like '6x3' will be added in areaInfo
        }
      } else {
        println("Tiles has not been selected - searching for tiles.")
        (json \ "characterInfo").asOpt[JsObject].foreach { characterInfo =>
          val charX = (characterInfo \ "PositionX").asOpt[Int].getOrElse(0)
          val charY = (characterInfo \ "PositionY").asOpt[Int].getOrElse(0)
          val charZ = (characterInfo \ "PositionZ").asOpt[Int].getOrElse(0)

          val validTiles = (json \ "areaInfo" \ "tiles").asOpt[JsObject].toSeq.flatMap { tiles =>
            tiles.fields.flatMap {
              case (tileId, tileValues) =>
                val tileX = tileId.substring(0, 5).toInt
                val tileY = tileId.substring(5, 10).toInt
                val tileZ = tileId.substring(10, 12).toInt

                val isValidTileId = tileValues \ "items" match {
                  case JsDefined(items) => items.as[JsObject].values.exists {
                    case JsNumber(n) => List(618, 619, 620, 621).contains(n.toInt)
                    case _ => false
                  }
                  case _ => false
                }


                val deltaX = tileX - charX
                val deltaY = Math.abs(tileY - charY)
                //              logs = logs :+ Log(s"Tile : $tileX, $tileY, $tileZ")
                //              logs = logs :+ Log(s"Player : $charX, $charY")
                //              logs = logs :+ Log(s"Delta : $deltaX, $deltaY, $isValidTileId")
                if (isValidTileId && deltaX >= 4 && deltaX <= 6 && deltaY <= 4) Some(tileId) else None
            }
          }
          //        println(s"Valid Tiles: ${validTiles.mkString(", ")}")
          Random.shuffle(validTiles).headOption.foreach { tileId =>

            val tileX = tileId.substring(0, 5).toInt
            val tileY = tileId.substring(5, 10).toInt
            val tileZ = tileId.substring(10).toInt // Adjusted to directly use remainder of the string for Z coordinate

            //          logs :+= Log(s"Selected Tile for Fishing: X=$tileX, Y=$tileY, Z=$tileZ")

            // Delta calculation after selecting the tile
            val deltaX = tileX - charX
            val deltaY = tileY - charY

            if (settings.mouseMovements) {
              //            logs :+= Log("Mouse movements are enabled.")
              // Assuming these values are correctly retrieved from your JSON and they don't depend on the tileId being iterated over
              val arrowsX = (json \ "screenInfo" \ "inventoryPanelLoc" \ "inventoryWindow" \ "contentsPanel" \ "slot10" \ "x").asOpt[Int].getOrElse(0)
              val arrowsY = (json \ "screenInfo" \ "inventoryPanelLoc" \ "inventoryWindow" \ "contentsPanel" \ "slot10" \ "y").asOpt[Int].getOrElse(0)

              // Calculate target tile key based on the deltas
              val targetTileScreenKey = s"${8 + deltaX}x${6 + deltaY}"
              val targetTileScreenX = (json \ "screenInfo" \ "mapPanelLoc" \ targetTileScreenKey \ "x").asOpt[Int].getOrElse(0)
              val targetTileScreenY = (json \ "screenInfo" \ "mapPanelLoc" \ targetTileScreenKey \ "y").asOpt[Int].getOrElse(0)

              println(s"targetTileScreenX: $targetTileScreenX, targetTileScreenY: $targetTileScreenY, arrowsX: $arrowsX, arrowsY: $arrowsY")

              val actionsSeq = Seq(
                MouseAction(arrowsX, arrowsY, "move"),
                MouseAction(arrowsX, arrowsY, "pressRight"),
                MouseAction(arrowsX, arrowsY, "releaseRight"),
                MouseAction(targetTileScreenX, targetTileScreenY, "move"),
                MouseAction(targetTileScreenX, targetTileScreenY, "pressLeft"),
                MouseAction(targetTileScreenX, targetTileScreenY, "releaseLeft")
              )

              actions = actions :+ FakeAction("useMouse", Some(ItemInfo(2874, Option(7))), Some(MouseActions(actionsSeq)))
              logs = logs :+ Log(s"Using fishing rod with mouse")
            } else {
              // Directly create a JSON object for the tile position
              val tilePositionJson: JsValue = Json.obj(
                "x" -> tileX,
                "y" -> tileY,
                "z" -> tileZ
              )
              logs = logs :+ Log("use fishing rod with function")
              actions = actions :+ FakeAction("fishingFunction", None, Some(JsonActionDetails(tilePositionJson)))
            }

          }
        }
      }
    }
    (actions, logs)
  }
}

