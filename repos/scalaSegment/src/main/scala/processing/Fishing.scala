package processing

import mouse.{FakeAction, ItemInfo}
import play.api.libs.json.{JsDefined, JsNumber, JsObject, JsValue, Json}
import processing.Process.extractOkButtonPosition
import userUI.SettingsUtils

import scala.collection.immutable.Seq
import scala.util.Random

object Fishing {


  def computeFishingActions(json: JsValue, settings: SettingsUtils.UISettings): (Seq[FakeAction], Seq[Log]) = {
    var actions: Seq[FakeAction] = Seq()
    var logs: Seq[Log] = Seq()
    val fishId = 3578
    var singleFishCount = 0
    var fishStackFound = false
    var onGround = true

    if (settings.fishingSettings.enabled) {
      logs = logs :+ Log("I want a fish!")
      println("Selected tiles: " + settings.fishingSettings.selectedRectangles.mkString(", "))
      println(settings.fishingSettings.selectedRectangles.nonEmpty)


      // Check for extra window and press 'Ok' if it exists
      extractOkButtonPosition(json) match {
        case Some((posX, posY)) =>
          val actionsSeq = Seq(
            MouseAction(posX, posY, "move"),
            MouseAction(posX, posY, "pressLeft"),
            MouseAction(posX, posY, "releaseLeft")
          )
          println("[DEBUG] Closing object movement window.")
          actions = actions :+ FakeAction("useMouse", None, Some(MouseActions(actionsSeq)))
          return (actions, logs) // Exit early to handle only one action per function call
        case None => // No 'Ok' button or invalid format
      }


      // Accessing and checking containersInfo
      (json \ "containersInfo").asOpt[JsObject].foreach { containersInfo =>
        println("Iterate over containers to find single fish items")

        // Count single fishes and find stacks
        containersInfo.fields.foreach { case (containerName, containerDetails) =>
          println(s"Checking container: $containerName")
          (containerDetails \ "items").asOpt[JsObject].foreach { items =>
            items.fields.foreach {
              case (slot, itemDetails) =>
                val itemId = (itemDetails \ "itemId").asOpt[Int]
                val itemCount = (itemDetails \ "itemCount").asOpt[Int]
                println(s"Checking item in slot $slot: itemId=$itemId, itemCount=$itemCount")
                if (itemId.contains(fishId)) {
                  if (itemCount.contains(1)) {
                    println(s"Found single fish in slot: $slot")
                    singleFishCount += 1
                  } else if (itemCount.exists(count => count > 1 && count < 100)) {
                    println(s"Found stack of fish in slot: $slot with count $itemCount")
                    fishStackFound = true
                  }
                  // Check if the stack is greater than 10
                  if (itemCount.exists(count => count > 10)) {
                    println(s"Found stack of fish greater than 10 in slot: $slot")
                    val itemSlot = slot.replace("slot", "item")
                    val itemScreenInfoOpt = (json \ "screenInfo" \ "inventoryPanelLoc" \ s"$containerName bag" \ "contentsPanel" \ itemSlot).asOpt[JsObject]

                    itemScreenInfoOpt.foreach { itemScreenInfo =>
                      val (x, y) = ((itemScreenInfo \ "x").as[Int], (itemScreenInfo \ "y").as[Int])
                      println(s"Screen coordinates for item: x=$x, y=$y")
                      println(s"settings.fishingSettings.fishThrowoutRectangles: ${settings.fishingSettings.fishThrowoutRectangles}")
                      println(s"settings.fishingSettings.selectedRectangles: ${settings.fishingSettings.selectedRectangles}")

                      val targetLocation = if (settings.fishingSettings.fishThrowoutRectangles.nonEmpty) {
                        val randomTileId = scala.util.Random.shuffle(settings.fishingSettings.fishThrowoutRectangles.toList).head
                        val targetTileScreenX = (json \ "screenInfo" \ "mapPanelLoc" \ randomTileId \ "x").asOpt[Int].getOrElse(0)
                        val targetTileScreenY = (json \ "screenInfo" \ "mapPanelLoc" \ randomTileId \ "y").asOpt[Int].getOrElse(0)
                        println(s"Throwing item to random tile: $randomTileId, targetTileScreenX: $targetTileScreenX, targetTileScreenY: $targetTileScreenY")
                        (targetTileScreenX, targetTileScreenY)
                      } else {
                        val presentCharLocation = (json \ "screenInfo" \ "mapPanelLoc" \ "8x6").as[JsObject]
                        val presentCharLocationX = (presentCharLocation \ "x").as[Int]
                        val presentCharLocationY = (presentCharLocation \ "y").as[Int]
                        println(s"Throwing item on the ground at: x=$presentCharLocationX, y=$presentCharLocationY")
                        (presentCharLocationX, presentCharLocationY)
                      }

                      val (targetX, targetY) = targetLocation

                      // Throw item on the ground or to a random tile
                      val actionsSeq = Seq(
                        MouseAction(x, y, "move"),
                        MouseAction(targetX, targetY, "pressLeft"),
                        MouseAction(targetX, targetY, "move"),
                        MouseAction(targetX, targetY, "releaseLeft")
                      )
                      actions = actions :+ FakeAction("useMouse", None, Some(MouseActions(actionsSeq)))
                      return (actions, logs) // Exit early to handle only one action per function call
                    }
                  }

                }
            }
          }
        }


        // Collect slots of single fishes and stacks
        val singleFishSlots = containersInfo.fields.flatMap { case (containerName, containerDetails) =>
          (containerDetails \ "items").asOpt[JsObject].toSeq.flatMap { items =>
            items.fields.collect {
              case (slot, itemDetails) if
                (itemDetails \ "itemId").asOpt[Int].contains(fishId) &&
                  (itemDetails \ "itemCount").asOpt[Int].contains(1) =>
                (containerName, slot)
            }
          }
        }

        val stackFishSlots = containersInfo.fields.flatMap { case (containerName, containerDetails) =>
          (containerDetails \ "items").asOpt[JsObject].toSeq.flatMap { items =>
            items.fields.collect {
              case (slot, itemDetails) if
                (itemDetails \ "itemId").asOpt[Int].contains(fishId) &&
                  (itemDetails \ "itemCount").asOpt[Int].exists(count => count > 1 && count < 100) =>
                (containerName, slot)
            }
          }
        }

        println(s"Single fish slots found: ${singleFishSlots.size}")
        println(s"Stack fish slots found: ${stackFishSlots.size}")

        // Condition to merge single fish or merge single fish with stack
        if ((fishStackFound && singleFishCount == 1) || (singleFishCount == 2)) {
          println("merge fishes action.")

          // Collect slots of single fishes
          if (singleFishSlots.size >= 2) {
            val sortedSingleFishSlots = singleFishSlots.sortBy(_._2)
            val (containerName1, slot1) = sortedSingleFishSlots.head
            val (containerName2, slot2) = sortedSingleFishSlots(1)

            println(s"Processing single fish in container: $containerName1, slot: $slot1")
            println(s"Processing single fish in container: $containerName2, slot: $slot2")

            val itemSlot1 = slot1.replace("slot", "item")
            val itemSlot2 = slot2.replace("slot", "item")

            val itemScreenInfoOpt1 = (json \ "screenInfo" \ "inventoryPanelLoc" \ s"$containerName1 bag" \ "contentsPanel" \ itemSlot1).asOpt[JsObject]
            val itemScreenInfoOpt2 = (json \ "screenInfo" \ "inventoryPanelLoc" \ s"$containerName2 bag" \ "contentsPanel" \ itemSlot2).asOpt[JsObject]

            (itemScreenInfoOpt1, itemScreenInfoOpt2) match {
              case (Some(itemScreenInfo1), Some(itemScreenInfo2)) =>
                val (x1, y1) = ((itemScreenInfo1 \ "x").as[Int], (itemScreenInfo1 \ "y").as[Int])
                val (x2, y2) = ((itemScreenInfo2 \ "x").as[Int], (itemScreenInfo2 \ "y").as[Int])
                println(s"Screen coordinates for merging: x1=$x1, y1=$y1, x2=$x2, y2=$y2")

                // Merge single fishes
                val actionsSeq = Seq(
                  MouseAction(x1, y1, "move"),
                  MouseAction(x1, y1, "pressLeft"),
                  MouseAction(x2, y2, "move"),
                  MouseAction(x2, y2, "releaseLeft")
                )
                actions = actions :+ FakeAction("useMouse", None, Some(MouseActions(actionsSeq)))
              case _ =>
                println("Could not find screen info for both single fishes.")
            }
          } else if (singleFishSlots.size == 1 && stackFishSlots.nonEmpty) {
            // Merge single fish with stack fish
            val (singleContainerName, singleSlot) = singleFishSlots.head
            val (stackContainerName, stackSlot) = stackFishSlots.head

            println(s"Merging single fish in container: $singleContainerName, slot: $singleSlot with stack in container: $stackContainerName, slot: $stackSlot")

            val singleItemSlot = singleSlot.replace("slot", "item")
            val stackItemSlot = stackSlot.replace("slot", "item")

            val singleItemScreenInfoOpt = (json \ "screenInfo" \ "inventoryPanelLoc" \ s"$singleContainerName bag" \ "contentsPanel" \ singleItemSlot).asOpt[JsObject]
            val stackItemScreenInfoOpt = (json \ "screenInfo" \ "inventoryPanelLoc" \ s"$stackContainerName bag" \ "contentsPanel" \ stackItemSlot).asOpt[JsObject]

            (singleItemScreenInfoOpt, stackItemScreenInfoOpt) match {
              case (Some(singleItemScreenInfo), Some(stackItemScreenInfo)) =>
                val (x1, y1) = ((singleItemScreenInfo \ "x").as[Int], (singleItemScreenInfo \ "y").as[Int])
                val (x2, y2) = ((stackItemScreenInfo \ "x").as[Int], (stackItemScreenInfo \ "y").as[Int])
                println(s"Screen coordinates for merging: x1=$x1, y1=$y1, x2=$x2, y2=$y2")

                // Merge single fish with stack
                val actionsSeq = Seq(
                  MouseAction(x1, y1, "move"),
                  MouseAction(x1, y1, "pressLeft"),
                  MouseAction(x2, y2, "move"),
                  MouseAction(x2, y2, "releaseLeft")
                )
                actions = actions :+ FakeAction("useMouse", None, Some(MouseActions(actionsSeq)))
              case _ =>
                println("Could not find screen info for single fish and stack.")
            }
          } else {
            println("Unexpected error: Did not find enough single fishes or stack to merge.")
          }
        } else if (singleFishCount == 0 || (singleFishCount == 1 && !fishStackFound)) {
          println("continue fishing.")

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
                }
              }
            }
          }

        }



      }
    }
    (actions, logs)
  }
}

