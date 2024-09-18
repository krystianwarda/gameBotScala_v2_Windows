package processing

import mouse.FakeAction
import play.api.libs.json.{JsError, JsObject, JsValue}
import userUI.SettingsUtils.UISettings
import play.api.libs.json._
import processing.AutoTarget.{parseCreature, transformToJSON}
import processing.CaveBot.{Vec, calculateDirection}
import processing.Process.{addMouseAction, extractOkButtonPosition}
import processing.TeamHunt.generateSubwaypointsToGamePosition
import utils.consoleColorPrint.{ANSI_GREEN, ANSI_RED, printInColor}

import scala.util.Random

case class ProcessResult(actions: Seq[FakeAction], logs: Seq[Log], updatedState: ProcessorState)

object AutoLoot {
  def computeAutoLootActions(json: JsValue, settings: UISettings, currentState: ProcessorState): ((Seq[FakeAction], Seq[Log]), ProcessorState) = {
    val startTime = System.nanoTime()
    var actions: Seq[FakeAction] = Seq.empty
    var logs: Seq[Log] = Seq.empty
    var updatedState = currentState // Initialize updatedState
    val currentTime = System.currentTimeMillis()

//    printInColor(ANSI_RED, f"[DEBUG] autoLoot: ${settings.autoTargetSettings.enabled}, autoLoot: ${settings.autoLootSettings.enabled}")
    if (settings.autoTargetSettings.enabled && settings.autoLootSettings.enabled) {
      printInColor(ANSI_RED, f"[DEBUG] computeAutoLootActions process started with status:${updatedState.stateHunting}")


      if (updatedState.staticContainersList.isEmpty) {
        // Assuming `json` is already defined as JsValue containing the overall data
        val containersInfo = (json \ "containersInfo").as[JsObject]

        // Extracting keys as a list of container names, but only include those which have "bag", "backpack", or "ring" in their names
        val containerKeys = containersInfo.keys.toList.filter { key =>
          val name = (containersInfo \ key \ "name").asOpt[String].getOrElse("")
          name.contains("bag") || name.contains("backpack") || name.contains("ring")
        }
        printInColor(ANSI_RED, f"[DEBUG] Static containers loaded: $containerKeys")
        updatedState = updatedState.copy(staticContainersList = containerKeys)
      }


      // Extracting and using the position
      extractOkButtonPosition(json) match {
        case Some((posX, posY)) =>
          // Now use this stored current time for all time checks
          val timeExtraWindowLoot = updatedState.currentTime - updatedState.lastExtraWindowLoot
          if (timeExtraWindowLoot >= updatedState.longTimeLimit) {
            val actionsSeq = Seq(
              MouseAction(posX, posY, "move"),
              MouseAction(posX, posY, "pressLeft"),
              MouseAction(posX, posY, "releaseLeft")
            )
            printInColor(ANSI_RED, "[DEBUG] Closing object movement window.")

            actions = actions :+ addMouseAction("useMouse", None, updatedState, actionsSeq)
            //            actions = actions :+ FakeAction("useMouse", None, Some(MouseActions(actionsSeq)))

            // Update the extraWidowLootStatus with the current time to mark this execution
            updatedState = updatedState.copy(lastExtraWindowLoot = updatedState.currentTime)
          } else {
            printInColor(ANSI_RED, f"[DEBUG] Closing object movement window. Not enough time has passed since the last execution: ${updatedState.lastExtraWindowLoot}ms ago.")
          }

        case None => // Do nothing
      }


      def processStateHunting(json: JsValue, settings: UISettings, updatedState: ProcessorState): ProcessResult = {
        updatedState.stateHunting match {
          case "attacking" =>
            println("Still fighting.")
            ProcessResult(Seq(), Seq(), updatedState)

          case "creature killed" =>
            println("Creature killed.")
            val ProcessResult(actions, logs, newState) = handleCreatureKilledState(json, settings, updatedState)
            ProcessResult(actions, logs, newState)

          case "loot or fight" =>
            println("Loot or fight.")
            val ProcessResult(actions, logs, newState) = handleLootOrFightState(json, settings, updatedState)
            ProcessResult(actions, logs, newState)

          case "looting in progress" =>
            println("Looting in progress.")
            val ProcessResult(actions, logs, newState) = handleLooting(json, settings, updatedState)
            ProcessResult(actions, logs, newState)

          case "free" =>
            println("Back to fight.")
            ProcessResult(Seq(), Seq(), updatedState.copy(stateHunting = "attacking"))

          case _ =>
            println("Unknown state, doing nothing.")
            ProcessResult(Seq(), Seq(), updatedState)
        }
      }


      def handleLooting(json: JsValue, settings: UISettings, currentState: ProcessorState): ProcessResult = {
        // Initialize empty actions and logs
        val actions: Seq[FakeAction] = Seq.empty
        var logs: Seq[Log] = Seq.empty
        var updatedState = currentState

        updatedState.stateLooting match {
          case "free" =>
            println("Looting -> free.")
            val ProcessResult(actions, logs, newState) = handleLootOrMoveCarcass(json, settings, updatedState)
            ProcessResult(actions, logs, newState)

          case "moving carcass" =>
            println("Looting -> moving carcass.")
            val ProcessResult(actions, logs, newState) = handleMovingOldCarcass(json, settings, updatedState)
            ProcessResult(actions, logs, newState)

          case "opening carcass" =>
            println("Looting -> opening carcass.")
            val ProcessResult(actions, logs, newState) = handleOpeningCarcass(json, settings, updatedState)
            ProcessResult(actions, logs, newState)

          case "clicking open button" =>
            println("Looting -> clicking open button.")
            val ProcessResult(actions, logs, newState) = handleClickingOpen(json, settings, updatedState)
            ProcessResult(actions, logs, newState)

          case "loot plunder" =>
            println("Looting -> loot plunder.")
            val ProcessResult(actions, logs, newState) = handleLootPlunder(json, settings, updatedState)
            ProcessResult(actions, logs, newState)


        }

        ProcessResult(actions, logs, updatedState)
      }


      def handleLootPlunder(json: JsValue, settings: UISettings, currentState: ProcessorState): ProcessResult = {
        var actions: Seq[FakeAction] = Seq.empty
        var logs: Seq[Log] = Seq.empty
        var updatedState = currentState

        // Process the stateLootPlunder to determine the appropriate looting action
        updatedState.stateLootPlunder match {
          case "free" =>
            // Placeholder logic to assess the loot
            logs = logs :+ Log("[DEBUG] Assessing the loot from the carcass.")
            val ProcessResult(assessActions, assessLogs, newState) = handleAssessLoot(json, settings, updatedState)
            ProcessResult(assessActions, assessLogs, newState)

          case "move item to backpack" =>
            // Placeholder logic to move item to the backpack
            logs = logs :+ Log("[DEBUG] Moving loot item to the backpack.")
            val ProcessResult(moveToBackpackActions, moveToBackpackLogs, newState) = handleMoveItemToBackpack(json, settings, updatedState)
            ProcessResult(moveToBackpackActions, moveToBackpackLogs, newState)

          case "move item to ground" =>
            // Placeholder logic to move item to the ground
            logs = logs :+ Log("[DEBUG] Moving loot item to the ground.")
            val ProcessResult(moveToGroundActions, moveToGroundLogs, newState) = handleMoveItemToGround(json, settings, updatedState)
            ProcessResult(moveToGroundActions, moveToGroundLogs, newState)

          case "handle food" =>
            // Placeholder logic to handle food
            logs = logs :+ Log("[DEBUG] Handling food from the loot.")
            val ProcessResult(handleFoodActions, handleFoodLogs, newState) = handleFood(json, settings, updatedState)
            ProcessResult(handleFoodActions, handleFoodLogs, newState)

          case "open subcontainer" =>
            // Placeholder logic to open subcontainers
            logs = logs :+ Log("[DEBUG] Opening subcontainer within the loot.")
            val ProcessResult(openSubcontainerActions, openSubcontainerLogs, newState) = handleOpenSubcontainer(json, settings, updatedState)
            ProcessResult(openSubcontainerActions, openSubcontainerLogs, newState)

          case _ =>
            // Default case if no state is matched
            logs = logs :+ Log("[ERROR] Unknown state in stateLootPlunder.")
            updatedState = updatedState.copy(stateLooting = "free", stateLootPlunder = "free")
            ProcessResult(actions, logs, updatedState)
        }
      }


      def handleAssessLoot(json: JsValue, settings: UISettings, currentState: ProcessorState): ProcessResult = {
        var actions: Seq[FakeAction] = Seq.empty
        var logs: Seq[Log] = Seq.empty
        var updatedState = currentState

        // Extract containers and screen info from the JSON
        val containersInfo = (json \ "containersInfo").as[JsObject]
        val screenInfo = (json \ "screenInfo").as[JsObject]

        // Get the last opened container (assumed to be the loot container)
        val lastContainerIndex = containersInfo.keys.maxBy(_.replace("container", "").toInt)
        val lastContainer = (containersInfo \ lastContainerIndex).as[JsObject]

        // Extract the items from the container
        (lastContainer \ "items").validate[JsObject] match {
          case JsSuccess(itemsInContainer, _) =>
            logs = logs :+ Log(f"[DEBUG] Assessing items in container $lastContainerIndex.")

            // Filter out already looted items
            val unlootedItems = JsObject(itemsInContainer.fields.filterNot { case (_, itemInfo) =>
              val itemId = (itemInfo \ "itemId").as[Int]
              updatedState.alreadyLootedIds.contains(itemId)
            })

            // Prepare a set of item IDs from the loot settings
            val lootItems = settings.autoLootSettings.lootList.map(_.trim.split(",\\s*")(0).toInt).toSet
 
            // Try to find lootable items
            val foundItemOpt = unlootedItems.fields.collectFirst {
              case (slot, itemInfo) if lootItems.contains((itemInfo \ "itemId").as[Int]) =>
                (slot, itemInfo)
            }

            foundItemOpt match {
              case Some((slot, itemInfo)) =>
                val itemId = (itemInfo \ "itemId").as[Int]
                val action = settings.autoLootSettings.lootList
                  .find(_.trim.split(",\\s*")(0).toInt == itemId)
                  .map(_.trim.split(",\\s*")(1)) // Extract the action part (like "g" for ground)
                  .getOrElse("")

                action match {
                  case "g" =>
                    logs = logs :+ Log(f"[DEBUG] Item $itemId will be moved to the ground.")
                    updatedState = updatedState.copy(
                      stateLootPlunder = "move item to ground",
                      itemLootToPlunder = itemId
                    )

                  case _ =>
                    logs = logs :+ Log(f"[DEBUG] Placeholder for item $itemId with action $action.")
                    updatedState = updatedState.copy(stateLootPlunder = "move item to backpack", itemLootToPlunder = itemId)
                }

              case None =>
                // No lootable items found, check for food
                logs = logs :+ Log(f"[DEBUG] No loot items found, checking for food.")
                val foundFoodOpt = unlootedItems.fields.collectFirst {
                  case (slot, itemInfo) if (itemInfo \ "itemId").as[Int] == 3577 => (slot, itemInfo)
                }

                foundFoodOpt match {
                  case Some((slot, foodItem)) =>
                    logs = logs :+ Log(f"[DEBUG] Food found in slot $slot.")
                    updatedState = updatedState.copy(stateLootPlunder = "handle food", itemLootToPlunder = (foodItem \ "itemId").as[Int])

                  case None =>
                    // No food found, check for subcontainers
                    logs = logs :+ Log(f"[DEBUG] No food found, checking for subcontainers.")
                    val foundSubcontainerOpt = unlootedItems.fields.collectFirst {
                      case (slot, itemInfo) if (itemInfo \ "isContainer").asOpt[Boolean].getOrElse(false) =>
                        (slot, itemInfo)
                    }

                    foundSubcontainerOpt match {
                      case Some((slot, subcontainer)) =>
                        logs = logs :+ Log(f"[DEBUG] Subcontainer found in slot $slot.")
                        updatedState = updatedState.copy(stateLootPlunder = "open subcontainer", itemLootToPlunder = (subcontainer \ "itemId").as[Int])

                      case None =>
                        // Nothing left to loot, set looting to free
                        logs = logs :+ Log(f"[DEBUG] No more items, food, or subcontainers. Looting complete.")
                        updatedState = updatedState.copy(stateLooting = "free", stateLootPlunder = "free", stateHunting = "free")
                    }
                }
            }

          case JsError(_) =>
            logs = logs :+ Log(f"[ERROR] Failed to parse items in the container $lastContainerIndex.")
            updatedState = updatedState.copy(stateLooting = "free", stateLootPlunder = "free", stateHunting = "free")
        }

        ProcessResult(actions, logs, updatedState)
      }



      def handleMovingOldCarcass(json: JsValue, settings: UISettings, currentState: ProcessorState): ProcessResult = {
        var actions: Seq[FakeAction] = Seq.empty
        var logs: Seq[Log] = Seq.empty
        var updatedState = currentState

        // Convert tiles from carsassToLootImmediately and carsassToLootAfterFight to grid format, ignoring those not found in mapPanelLoc
        val excludedTilesGrid = (updatedState.carsassToLootImmediately ++ updatedState.carsassToLootAfterFight)
          .flatMap(tileId => convertGameLocationToGrid(json, tileId)).toSet

        // Use lastLootedCarcassTile for the carcass to move
        val carcassToMove = updatedState.lastLootedCarcassTile

        // Extract the item position from mapPanelLoc based on lastLootedCarcassTile
        val itemPositionToMoveOpt = extractItemPositionFromMapOnScreen(json, carcassToMove)

        itemPositionToMoveOpt match {
          case Some((itemToMovePosX, itemToMovePosY)) =>
            // List of possible walkable tiles
            val possibleTiles = List("7x5", "7x6", "7x7", "8x5", "8x6", "8x7", "9x5", "9x6", "9x7")
            val areaInfo = (json \ "areaInfo").as[JsObject]

            // Find a random walkable tile, excluding the ones that map to excludedTilesGrid
            val walkableTileIndexOpt = findRandomWalkableTile(areaInfo, possibleTiles.filterNot(excludedTilesGrid.contains))

            walkableTileIndexOpt match {
              case Some(tileIndex) =>
                // Extract x and y coordinates for the selected walkable tile
                val mapPanelLoc = (json \ "screenInfo" \ "mapPanelLoc" \ tileIndex).as[JsObject]
                val (targetX, targetY) = ((mapPanelLoc \ "x").as[Int], (mapPanelLoc \ "y").as[Int])

                // Move the carcass to the random walkable tile
                val actionsSeq = moveSingleItem(itemToMovePosX, itemToMovePosY, targetX, targetY)
                actions = actions :+ FakeAction("useMouse", None, Some(MouseActions(actionsSeq)))
                updatedState = updatedState.copy(stateLooting = "opening carcass")
                logs = logs :+ Log(s"Moving carcass to random walkable tile at ($targetX, $targetY), avoiding tiles in carsassToLootImmediately and carsassToLootAfterFight.")

              case None =>
                logs = logs :+ Log("No walkable tile found.")
                updatedState = updatedState.copy(stateLooting = "free", stateHunting = "free")
            }

          case None =>
            logs = logs :+ Log(s"Could not find position for carcass tile ${updatedState.lastLootedCarcassTile}.")
            updatedState = updatedState.copy(stateLooting = "free", stateHunting = "free")

        }

        ProcessResult(actions, logs, updatedState)
      }


      def convertGameLocationToGrid(json: JsValue, tileId: String): Option[String] = {
        val mapPanelLoc = (json \ "screenInfo" \ "mapPanelLoc").asOpt[JsObject]

        // Look for the tileId in mapPanelLoc and return the corresponding grid key, otherwise return None
        mapPanelLoc.flatMap(_.fields.collectFirst {
          case (gridKey, jsValue) if (jsValue \ "id").asOpt[String].contains(tileId) =>
            gridKey // Return the grid key if the id matches
        })
      }


      def handleOpeningCarcass(json: JsValue, settings: UISettings, currentState: ProcessorState): ProcessResult = {
        var actions: Seq[FakeAction] = Seq.empty
        var logs: Seq[Log] = Seq.empty
        var updatedState = currentState

        // Retrieve the carcass tile to loot
        val carcassTileToLoot = updatedState.carcassTileToLoot

        // Extract the carcass's in-game position
        val carcassInGamePosition: Vec = Vec(
          carcassTileToLoot.substring(0, 5).toInt,
          carcassTileToLoot.substring(5, 10).toInt
        )

        // Extract the character's current position
        val presentCharPosition = updatedState.presentCharLocation

        // Calculate the Chebyshev distance between the character and the carcass
        val chebyshevDistance = math.max(
          math.abs(carcassInGamePosition.x - presentCharPosition.x),
          math.abs(carcassInGamePosition.y - presentCharPosition.y)
        )

        // Check if the character is close enough to open the carcass
        val isCloseEnough = chebyshevDistance <= 1

        if (isCloseEnough) {
          // If the character is close enough, proceed with opening the carcass

          // Extract mapPanelLoc from JSON
          val mapPanelLoc = (json \ "screenInfo" \ "mapPanelLoc").asOpt[JsObject].getOrElse(Json.obj())

          // Find the screen coordinates for the carcass to loot
          val screenCoordsOpt = mapPanelLoc.fields.collectFirst {
            case (_, obj: JsObject) if (obj \ "id").asOpt[String].contains(carcassTileToLoot) =>
              for {
                x <- (obj \ "x").asOpt[Int]
                y <- (obj \ "y").asOpt[Int]
              } yield (x, y)
          }.flatten

          screenCoordsOpt match {
            case Some((xPositionScreen, yPositionScreen)) =>
              // Log the coordinates
              logs = logs :+ Log(s"[DEBUG] Opening creature carcass at screen position ($xPositionScreen, $yPositionScreen)")

              // Define the sequence of mouse actions to open the carcass
              val actionsSeq = Seq(
                MouseAction(xPositionScreen, yPositionScreen, "move"),
                MouseAction(xPositionScreen, yPositionScreen, "pressCtrl"),
                MouseAction(xPositionScreen, yPositionScreen, "pressRight"),
                MouseAction(xPositionScreen, yPositionScreen, "releaseRight"),
                MouseAction(xPositionScreen, yPositionScreen, "releaseCtrl")
              )

              // Add the mouse actions to the action list
              actions = actions :+ FakeAction("useMouse", None, Some(MouseActions(actionsSeq)))

              // Update the state to indicate that the carcass is being opened
              updatedState = updatedState.copy(stateLooting = "clicking open button")

            case None =>
              // Log the failure to find the carcass
              logs = logs :+ Log(s"[ERROR] Could not find screen coordinates for carcass tile $carcassTileToLoot")

              // Reset the state since the carcass could not be opened
              updatedState = updatedState.copy(stateLooting = "free", stateHunting = "free")
          }
        } else {
          // If the character is too far away, generate waypoints and move closer

          logs = logs :+ Log(s"[DEBUG] Character is too far from the carcass at position ${carcassInGamePosition}. Distance: $chebyshevDistance")

          // Generate subwaypoints to move closer to the carcass
          updatedState = generateSubwaypointsToGamePosition(carcassInGamePosition, updatedState, json)

          if (updatedState.subWaypoints.nonEmpty) {
            val nextWaypoint = updatedState.subWaypoints.head
            val direction = calculateDirection(presentCharPosition, nextWaypoint, updatedState.lastDirection)
            logs = logs :+ Log(f"[DEBUG] Calculated Next Direction: $direction")

            updatedState = updatedState.copy(lastDirection = direction)

            direction.foreach { dir =>
              // Add the movement action (key press) to the action sequence
              actions = actions :+ FakeAction("pressKey", None, Some(PushTheButton(dir)))
              logs = logs :+ Log(s"Moving closer to the carcass in direction: $dir")
            }

            // Remove the used waypoint from the state
            updatedState = updatedState.copy(subWaypoints = updatedState.subWaypoints.tail)
          } else {
            // If no waypoints are found, reset the state
            logs = logs :+ Log(s"[ERROR] No waypoints found to reach the carcass.")
            updatedState = updatedState.copy(stateLooting = "free", stateHunting = "free")
          }
        }

        ProcessResult(actions, logs, updatedState)
      }



      def handleClickingOpen(json: JsValue, settings: UISettings, currentState: ProcessorState): ProcessResult = {
        var actions: Seq[FakeAction] = Seq.empty
        var logs: Seq[Log] = Seq.empty
        var updatedState = currentState

        // Attempt to retrieve the position of the "Open" button from the extra window
        val openPosOpt = (json \ "screenInfo" \ "extraWindowLoc").asOpt[JsObject].flatMap { extraWindowLoc =>
          (extraWindowLoc \ "Open").asOpt[JsObject].flatMap { open =>
            for {
              posX <- (open \ "posX").asOpt[Int]
              posY <- (open \ "posY").asOpt[Int]
            } yield (posX, posY)
          }
        }

        // Handle the result of the attempt to click on the "Open" button
        openPosOpt match {
          case Some((xPosWindowOpen, yPosWindowOpen)) =>
            // Clicking on the "Open" button
            logs = logs :+ Log(s"Clicking on 'Open' button at coordinates: x=$xPosWindowOpen, y=$yPosWindowOpen")
            val actionsSeq = Seq(
              MouseAction(xPosWindowOpen, yPosWindowOpen, "move"),
              MouseAction(xPosWindowOpen, yPosWindowOpen, "pressLeft"),
              MouseAction(xPosWindowOpen, yPosWindowOpen, "releaseLeft")
            )
            actions = actions :+ FakeAction("useMouse", None, Some(MouseActions(actionsSeq)))
            updatedState = updatedState.copy(stateLooting = "assess loot")

          case None =>

            logs = logs :+ Log("Failed to open looting window. Cancelling looting.")
            updatedState = updatedState.copy(stateLooting = "free", stateHunting = "free")
        }

        // Return the ProcessResult with updated actions, logs, and state
        ProcessResult(actions, logs, updatedState)
      }



      def extractItemPositionFromMapOnScreen(json: JsValue, carcassTileToLoot: String): Option[(Int, Int)] = {
        // Extract the key from the carcassTileToLoot (in this case, "345323456507" format)
        val posX = carcassTileToLoot.substring(0, 5)
        val posY = carcassTileToLoot.substring(5, 10)
        val posZ = carcassTileToLoot.substring(10, 12)

        // Search for the position in the mapPanelLoc
        val mapPanelLoc = (json \ "screenInfo" \ "mapPanelLoc").asOpt[JsObject]
        val tileKeyOpt = mapPanelLoc.flatMap(_.fields.collectFirst {
          case (tileIndex, jsValue) if (jsValue \ "id").asOpt[String].contains(carcassTileToLoot) =>
            (jsValue \ "x").asOpt[Int].getOrElse(0) -> (jsValue \ "y").asOpt[Int].getOrElse(0)
        })

        tileKeyOpt match {
          case Some((x, y)) =>
            println(s"Found coordinates for $carcassTileToLoot: x=$x, y=$y")
            Some((x, y))
          case None =>
            println(s"No coordinates found for $carcassTileToLoot")
            None
        }
      }


      def handleLootOrMoveCarcass(json: JsValue, settings: UISettings, currentState: ProcessorState): ProcessResult = {
        var actions: Seq[FakeAction] = Seq.empty
        var logs: Seq[Log] = Seq.empty
        var updatedState = currentState


        // TO DO cases for item/items on carcass
        // TO DO cases for field on carcass

        // Compare carcassTileToLoot with lastLootedCarcassTile
        if (updatedState.carcassTileToLoot == updatedState.lastLootedCarcassTile) {
          println("Same carcass tile as last looted. Setting state to 'moving carcass'.")
          updatedState = updatedState.copy(stateLooting = "moving carcass")
        } else {
          println("Different carcass tile. Setting state to 'opening carcass'.")
          updatedState = updatedState.copy(stateLooting = "opening carcass")
        }

        // Return the ProcessResult with the updated actions, logs, and state
        ProcessResult(actions, logs, updatedState)
      }


      def handleCreatureKilledState(json: JsValue, settings: UISettings, currentState: ProcessorState): ProcessResult = {
        // Initialize empty actions and logs
        val actions: Seq[FakeAction] = Seq.empty
        var logs: Seq[Log] = Seq.empty
        var updatedState = currentState

        println(s"updatedState.lastTargetName: ${updatedState.lastTargetName}")

        // Retrieve position coordinates
        val xPositionGame = updatedState.lastTargetPos._1
        val yPositionGame = updatedState.lastTargetPos._2
        val zPositionGame = updatedState.lastTargetPos._3

        printInColor(ANSI_RED, f"[DEBUG] Creature (${updatedState.lastTargetName}) in game position $xPositionGame, $yPositionGame, $zPositionGame")

        // Generate the position key
        val mainPositionKey = generatePositionKey(xPositionGame, yPositionGame, zPositionGame)
        println(s"mainPositionKey: $mainPositionKey")

        // Retrieve the main index from the game state (using your custom logic)
        val mainIndexOpt = checkForBloodAndContainerAndGetIndex(json, mainPositionKey)
        println(s"mainIndexOpt: $mainIndexOpt")

        // Extract the creature settings based on the lastTargetName
        val creatureSettingsOpt = settings.autoTargetSettings.creatureList
          .find(creatureStr => parseCreature(creatureStr).name.equalsIgnoreCase(updatedState.lastTargetName))
          .map(parseCreature)

        println(s"creatureSettingsOpt: $creatureSettingsOpt")

        // Handle the creature settings
        val (newState, updatedLogs) = creatureSettingsOpt match {
          case Some(creatureSettings) =>
            mainIndexOpt match {
              case Some(mainIndex) =>
                if (creatureSettings.lootMonsterImmediately) {
                  // Add the carcass index to carsassToLootImmediately
                  logs = logs :+ Log(s"${updatedState.lastTargetName} will be looted immediately.")
                  (updatedState.copy(
                    carsassToLootImmediately = updatedState.carsassToLootImmediately :+ mainIndex.toString, // Assuming mainIndex is the index
                    stateHunting = "loot or fight"
                  ), logs)

                } else if (creatureSettings.lootMonsterAfterFight) {
                  // Add the carcass index to carsassToLootAfterFight
                  logs = logs :+ Log(s"${updatedState.lastTargetName} will be looted after the fight.")
                  (updatedState.copy(
                    carsassToLootAfterFight = updatedState.carsassToLootAfterFight :+ mainIndex.toString, // Assuming mainIndex is the index
                    stateHunting = "loot or fight"
                  ), logs)

                } else {
                  // No looting settings, just log it
                  logs = logs :+ Log(s"${updatedState.lastTargetName} has no looting settings.")
                  (updatedState.copy(stateHunting = "free"), logs)
                }

              case None =>
                // Handle the case where mainIndexOpt is None
                logs = logs :+ Log(s"No valid index found for ${updatedState.lastTargetName}.")
                (updatedState.copy(stateHunting = "free"), logs)
            }

          case None =>
            logs = logs :+ Log(s"No creature settings found for ${updatedState.lastTargetName}.")
            (updatedState.copy(stateHunting = "free"), logs)
        }

        // Return the ProcessResult with the updated actions, updated logs, and updated state
        ProcessResult(actions, updatedLogs, newState)
      }


      def handleLootOrFightState(json: JsValue, settings: UISettings, currentState: ProcessorState): ProcessResult = {
        var actions: Seq[FakeAction] = Seq.empty
        var logs: Seq[Log] = Seq.empty
        var updatedState = currentState

        // Retrieve the battleShootableCreaturesList from the game state
        val battleShootableCreaturesList = getBattleShootableCreaturesList(json)
        println(s"battleShootableCreaturesList: $battleShootableCreaturesList")

        // Check if carsassToLootImmediately is empty
        if (updatedState.carsassToLootImmediately.nonEmpty) {

          // sort carcass by char proximity
          if (updatedState.carsassToLootImmediately.length > 1) {
            updatedState = updatedState.copy(carsassToLootImmediately = sortTileListByCharacterProximity(json, updatedState.carsassToLootImmediately))
          }

          logs = logs :+ Log(s"Looting immediately. Carcass to loot: ${updatedState.carsassToLootImmediately.head}")
          updatedState = updatedState.copy(stateHunting = "looting in progress", carcassTileToLoot=updatedState.carsassToLootImmediately.head)
        }
        // Check if carsassToLootAfterFight is empty and if the player is safe
        else if (updatedState.carsassToLootAfterFight.nonEmpty && battleShootableCreaturesList.isEmpty) {

          // sort carcass by char proximity
          if (updatedState.carsassToLootAfterFight.length > 1) {
            updatedState = updatedState.copy(carsassToLootAfterFight = sortTileListByCharacterProximity(json, updatedState.carsassToLootAfterFight))
          }

          logs = logs :+ Log(s"Looting after fight. Carcass to loot: ${updatedState.carsassToLootAfterFight.head}")
          updatedState = updatedState.copy(stateHunting = "looting in progress", carcassTileToLoot=updatedState.carsassToLootAfterFight.head)
        }
        // If neither looting list is non-empty or it's not safe, set state to "free"
        else {
          logs = logs :+ Log("No carcass to loot or not safe to loot. Setting state to free.")
          updatedState = updatedState.copy(stateHunting = "free")
        }

        // Return the ProcessResult with updated actions, logs, and state
        ProcessResult(actions, logs, updatedState)
      }





      def sortTileListByCharacterProximity(json: JsValue, tileList: List[String]): List[String] = {
        // Extract character position from JSON
        val (charX, charY, charZ) = (json \ "characterInfo").asOpt[JsObject].map { characterInfo =>
          val x = (characterInfo \ "PositionX").asOpt[Int].getOrElse(0)
          val y = (characterInfo \ "PositionY").asOpt[Int].getOrElse(0)
          val z = (characterInfo \ "PositionZ").asOpt[Int].getOrElse(0)
          (x, y, z)
        }.getOrElse((0, 0, 0)) // Default to (0, 0, 0) if character position is not found

        // Helper function to extract posX, posY, posZ from tile string
        def extractTilePosition(tile: String): (Int, Int, Int) = {
          val posX = tile.substring(0, 5).toInt
          val posY = tile.substring(5, 10).toInt
          val posZ = tile.substring(10, 12).toInt
          (posX, posY, posZ)
        }

        // Helper function to calculate the distance between two 3D points
        def calculateDistance(x1: Int, y1: Int, z1: Int, x2: Int, y2: Int, z2: Int): Double = {
          math.sqrt(math.pow(x2 - x1, 2) + math.pow(y2 - y1, 2) + math.pow(z2 - z1, 2))
        }

        // Sort the tiles by their distance to the character's position
        tileList.sortBy { tile =>
          val (tileX, tileY, tileZ) = extractTilePosition(tile)
          calculateDistance(tileX, tileY, tileZ, charX, charY, charZ)
        }
      }













      // check if updatedState.carsassToLoot has elements create match case else create different case
      def getBattleShootableCreaturesList(json: JsValue): List[String] = {
        // Extract the battleInfo from the JSON
        val battleInfo = (json \ "battleInfo").asOpt[Map[String, JsValue]].getOrElse(Map.empty)

        // Filter for creatures that are monsters and shootable
        val battleShootableCreaturesList = battleInfo.flatMap { case (_, data) =>
          val isMonster = (data \ "IsMonster").asOpt[Boolean].getOrElse(false)
          val isShootable = (data \ "IsShootable").asOpt[Boolean].getOrElse(false)

          if (isMonster && isShootable) {
            Some((data \ "Name").as[String]) // Add the creature name to the list
          } else {
            None
          }
        }.toList

        // Return the filtered list (could be empty if no creatures match)
        battleShootableCreaturesList
      }





      println(s"updatedState.carsassToLoot: ${updatedState.carsassToLoot}")

      println(s"lastTargetName: ${updatedState.lastTargetName}")

      val battleShootableCreaturesList = getBattleShootableCreaturesList(json)
      println(s"battleShootableCreaturesList: ${battleShootableCreaturesList}")
      if (battleShootableCreaturesList.isEmpty) {
        println(s"battleShootableCreaturesList.isEmpty")
        // If battleShootableCreaturesList is empty, check if carsassToLoot is not empty
        if (updatedState.carsassToLoot.nonEmpty) {
          println(s"carsassToLoot has elements: ${updatedState.carsassToLoot}")

          // Process looting if carsassToLoot is not empty
          val carcassIndexList = updatedState.carsassToLoot
          val resultProcessLootingAfterFight = processLootingAfterFight(carcassIndexList, json, settings, updatedState)

          // Correctly concatenate the sequences using ++
          actions = actions ++ resultProcessLootingAfterFight._1._1
          logs = logs ++ resultProcessLootingAfterFight._1._2
          updatedState = resultProcessLootingAfterFight._2


        } else {
          println("carsassToLoot is empty")
        }
      }



      (json \ "attackInfo" \ "Id").asOpt[Int] match {
        case Some(attackedCreatureTarget) =>
          updatedState = updatedState.copy(stateHunting = "attacking")
        case None =>
          if (updatedState.lastTargetName == "") {
             println("No target specified, no looting needed.")
          } else {
            println(s"updatedState.lastTargetName: ${updatedState.lastTargetName}")
            updatedState.stateHunting match {
              case "looting" =>
                val resultProcessLootingState = processLootingState(json, settings, updatedState)
                actions = actions ++ resultProcessLootingState._1._1
                logs = logs ++ resultProcessLootingState._1._2
                updatedState = resultProcessLootingState._2

              case "opening window" =>
                val resultProcessOpeningWindowState = processOpeningWindowState(json, settings, updatedState)
                actions = actions ++ resultProcessOpeningWindowState._1._1
                logs = logs ++ resultProcessOpeningWindowState._1._2
                updatedState = resultProcessOpeningWindowState._2


              case "attacking" =>
                printInColor(ANSI_RED, f"[DEBUG] attacking state")
                println(s"updatedState.lastTargetName.nonEmpty: ${updatedState.lastTargetName.nonEmpty}")
                println(s"updatedState.monstersListToLoot.contains(updatedState.lastTargetName)): ${updatedState.monstersListToLoot.contains(updatedState.lastTargetName)}")

                val resultProcessAttackingState = processAttackingState(json, settings, updatedState)
                actions = actions ++ resultProcessAttackingState._1._1
                logs = logs ++ resultProcessAttackingState._1._2
                updatedState = resultProcessAttackingState._2

              case "free" =>
                println("CASE FREE")

            }

          }
      }
    }
    val endTime = System.nanoTime()
    val duration = (endTime - startTime) / 1e9d
    printInColor(ANSI_GREEN, f"[INFO] Processing computeAutoLootActions took $duration%.6f seconds")

    ((actions, logs), updatedState)
  }


  def processAttackingState(json: JsValue, settings: UISettings, currentState: ProcessorState): ((Seq[FakeAction], Seq[Log]), ProcessorState) = {
    var updatedState = currentState
    var actions: Seq[FakeAction] = Seq()
    var logs: Seq[Log] = Seq()

    if (updatedState.lastTargetName.nonEmpty && !updatedState.monstersListToLoot.contains(updatedState.lastTargetName)) {
      printInColor(ANSI_RED, f"[DEBUG] Reseting status to free, monsters are not in list [${updatedState.monstersListToLoot}]")
      updatedState = updatedState.copy(stateHunting = "free", subWaypoints = List.empty)
      println(s"Setting up stateHunting to free (1)")
    } else {
      // Retrieve position coordinates
      val xPositionGame = updatedState.lastTargetPos._1
      val yPositionGame = updatedState.lastTargetPos._2
      val zPositionGame = updatedState.lastTargetPos._3

      printInColor(ANSI_RED, f"[DEBUG] Creature (${updatedState.lastTargetName}) in game position $xPositionGame, $yPositionGame, $zPositionGame")

      // Function to generate position keys with padding on z-coordinate

      println(s"Gate0")
      // Check the main position first
      val mainPositionKey = generatePositionKey(xPositionGame, yPositionGame, zPositionGame)
      println(s"mainPositionKey: ${mainPositionKey}")
      val mainIndexOpt = checkForBloodAndContainerAndGetIndex(json, mainPositionKey)
      println(s"mainIndexOpt: ${mainIndexOpt}")

      // Extract the creature settings based on the lastTargetName
      val creatureSettingsOpt = settings.autoTargetSettings.creatureList
        .find(creatureStr => parseCreature(creatureStr).name.equalsIgnoreCase(updatedState.lastTargetName))
        .map(parseCreature)

      println(s"creatureSettingsOpt: ${creatureSettingsOpt}")

      creatureSettingsOpt match {
        case Some(creatureSettings) =>
          // Check for lootMonsterImmediately and lootMonsterAfterFight
          println(s"creatureSettings: ${creatureSettings}")
          if (creatureSettings.lootMonsterAfterFight) {
            println(s"Placeholder for Loot After Fight for creature: ${updatedState.lastTargetName}")
            println(s"Before updatedState.carsassToLoot: ${updatedState.carsassToLoot}")
            updatedState = updatedState.copy(carsassToLoot = mainIndexOpt.toList, stateHunting = "free")
            println(s"After updatedState.carsassToLoot: ${updatedState.carsassToLoot}")
            println(s"Setting up stateHunting to free (2)")

          } else {
            mainIndexOpt match {
              case Some(index) =>
                // Fetch screen coordinates from JSON using the index
                println(s"Loot immediately and carcass index ($index) exists")
                val screenCoordsOpt = (json \ "screenInfo" \ "mapPanelLoc" \ index).asOpt[JsObject].flatMap { coords =>
                  for {
                    x <- (coords \ "x").asOpt[Int]
                    y <- (coords \ "y").asOpt[Int]
                  } yield (x, y)
                }.getOrElse((0, 0)) // Default coordinates if not found

                // Define the sequence of mouse actions based on retrieved screen coordinates
                val (xPositionScreen, yPositionScreen) = screenCoordsOpt
                printInColor(ANSI_RED, f"[DEBUG] Opening creature carcass on screen position $xPositionScreen, $yPositionScreen")
                updatedState = updatedState.copy(alreadyLootedIds = List())
                val actionsSeq = Seq(
                  MouseAction(xPositionScreen, yPositionScreen, "move"),
                  MouseAction(xPositionScreen, yPositionScreen, "pressCtrl"),
                  MouseAction(xPositionScreen, yPositionScreen, "pressRight"),
                  MouseAction(xPositionScreen, yPositionScreen, "releaseRight"),
                  MouseAction(xPositionScreen, yPositionScreen, "releaseCtrl")
                )

                actions = actions :+ addMouseAction("useMouse", None, updatedState, actionsSeq)
                //                    actions = actions :+ FakeAction("useMouse", None, Some(MouseActions(actionsSeq)))
                updatedState = updatedState.copy(stateHunting = "opening window")

              case None =>
                printInColor(ANSI_RED, f"[DEBUG] Monster carcass with blood not found at x: ${xPositionGame}, y: ${yPositionGame}. Extending search to nearby tiles.")
                val positionsToCheck = for {
                  dx <- -1 to 1
                  dy <- -1 to 1
                } yield (xPositionGame + dx, yPositionGame + dy)

                // Find the first position that meets the criteria and retrieve the index
                val indexOpt = positionsToCheck.flatMap { case (x, y) =>
                  val key = generatePositionKey(x, y, zPositionGame)
                  checkForBloodAndContainerAndGetIndex(json, key)
                }.headOption

                indexOpt match {
                  case Some(index) =>
                    // Fetch screen coordinates from JSON using the index
                    val screenCoordsOpt = (json \ "screenInfo" \ "mapPanelLoc" \ index).asOpt[JsObject].flatMap { coords =>
                      for {
                        x <- (coords \ "x").asOpt[Int]
                        y <- (coords \ "y").asOpt[Int]
                      } yield (x, y)
                    }.getOrElse((0, 0)) // Default coordinates if not found

                    printInColor(ANSI_RED, f"[DEBUG] Screen coordinates are x: ${screenCoordsOpt._1}, y: ${screenCoordsOpt._2}")

                    // Define the sequence of mouse actions based on retrieved screen coordinates
                    val (xPositionScreen, yPositionScreen) = screenCoordsOpt
                    println(s"Creature body screen position $xPositionScreen, $yPositionScreen")
                    updatedState = updatedState.copy(alreadyLootedIds = List())
                    val actionsSeq = Seq(
                      MouseAction(xPositionScreen, yPositionScreen, "move"),
                      MouseAction(xPositionScreen, yPositionScreen, "pressCtrl"),
                      MouseAction(xPositionScreen, yPositionScreen, "pressLeft"),
                      MouseAction(xPositionScreen, yPositionScreen, "releaseLeft"),
                      MouseAction(xPositionScreen, yPositionScreen, "releaseCtrl")
                    )

                    actions = actions :+ FakeAction("useMouse", None, Some(MouseActions(actionsSeq)))
                    updatedState = updatedState.copy(stateHunting = "opening window")

                  case None =>
                    printInColor(ANSI_RED, f"[DEBUG] Creature carcass with blood in the area has not been found.")
                    updatedState = updatedState.copy(stateHunting = "free")
                    println(s"Setting up stateHunting to free (3)")
                }
            }
          }
        case None =>
          println(s"No settings found for creature: ${updatedState.lastTargetName}")
      }


    }

    ((actions, logs), updatedState)
  }


  def generatePositionKey(x: Int, y: Int, z: Int): String = f"$x$y${z}%02d"

  // Updated function to check for fresh blood and return the tile index if blood is found
  def checkForBloodAndContainerAndGetIndex(json: JsValue, positionKey: String): Option[String] = {
    (json \ "areaInfo" \ "tiles" \ positionKey).asOpt[JsObject] match {
      case Some(tileInfo) =>
        val items = (tileInfo \ "items").as[JsObject]
        val itemsList = items.values.toList.reverse // reverse to access the last items first

        // Check if there's any fresh blood in itemsList (ID 2886 or 2887)
        val hasBlood = itemsList.exists { item =>
          val id = (item \ "id").as[Int]
          id == 2886 || id == 2887
        }

        if (hasBlood) {
          println(s"Blood found at positionKey: $positionKey")
          Some(positionKey) // Return the positionKey if blood is found
        } else {
          println("No blood found.")
          None
        }

      case None =>
        println(s"No tile information found for positionKey: $positionKey")
        None
    }
  }



  def processOpeningWindowState(json: JsValue, settings: UISettings, currentState: ProcessorState): ((Seq[FakeAction], Seq[Log]), ProcessorState) = {
    var updatedState = currentState
    var actions: Seq[FakeAction] = Seq()
    var logs: Seq[Log] = Seq()

    printInColor(ANSI_RED, f"[DEBUG] stage: opening window")
    // Accessing and checking if 'extraWindowLoc' is non-null and retrieving 'Open' positions
    val openPosOpt = (json \ "screenInfo" \ "extraWindowLoc").asOpt[JsObject].flatMap { extraWindowLoc =>
      (extraWindowLoc \ "Open").asOpt[JsObject].flatMap { open =>
        for {
          posX <- (open \ "posX").asOpt[Int]
          posY <- (open \ "posY").asOpt[Int]
        } yield (posX, posY)
      }
    }

    // Handle the option to do something with the coordinates
    openPosOpt match {
      case Some((xPosWindowOpen, yPosWindowOpen)) =>
        printInColor(ANSI_RED, f"[DEBUG] stage: window is opened, clicking on Open button. x=$xPosWindowOpen , $yPosWindowOpen")

        val actionsSeq = Seq(
          MouseAction(xPosWindowOpen, yPosWindowOpen, "move"),
          MouseAction(xPosWindowOpen, yPosWindowOpen, "pressLeft"),
          MouseAction(xPosWindowOpen, yPosWindowOpen, "releaseLeft"),
        )
        actions = actions :+ FakeAction("useMouse", None, Some(MouseActions(actionsSeq)))
        updatedState = updatedState.copy(stateHunting = "looting") // looting later
      case None =>
        if (updatedState.extraWidowLootStatus >= updatedState.retryAttempts) {
          printInColor(ANSI_RED, f"[DEBUG] Miss-clicked the carcass body. Resetting.")
          val presentCharLocation = (json \ "screenInfo" \ "mapPanelLoc" \ "10x3").as[JsObject]
          val presentCharLocationX = (presentCharLocation \ "x").as[Int]
          val presentCharLocationY = (presentCharLocation \ "y").as[Int]

          val actionsSeq = Seq(
            MouseAction(presentCharLocationX, presentCharLocationY, "move"),
            MouseAction(presentCharLocationX, presentCharLocationY, "pressLeft"),
            MouseAction(presentCharLocationX, presentCharLocationY, "releaseLeft")
          )
          actions = actions :+ FakeAction("useMouse", None, Some(MouseActions(actionsSeq)))
          updatedState = updatedState.copy(stateHunting = "free", extraWidowLootStatus = 0) // looting later and reset retryStatus
          println(s"Setting up stateHunting to free (4)")
        } else {
          printInColor(ANSI_RED, f"[DEBUG] No Open position available or extraWindowLoc is null. Retrying... (Attempt ${updatedState.extraWidowLootStatus + 1})")
          updatedState = updatedState.copy(extraWidowLootStatus = updatedState.extraWidowLootStatus + 1) // increment retryStatus
        }
    }
    ((actions, logs), updatedState)
  }


  def processLootingState(json: JsValue, settings: UISettings, currentState: ProcessorState): ((Seq[FakeAction], Seq[Log]), ProcessorState) = {
    var updatedState = currentState
    var actions: Seq[FakeAction] = Seq()
    var logs: Seq[Log] = Seq()

    printInColor(ANSI_RED, f"[DEBUG] Looting State started")
    try {


      val containersInfo = (json \ "containersInfo").as[JsObject]
      val screenInfo = (json \ "screenInfo").as[JsObject]
      println(containersInfo)
      // Get the last container from containersInfo
      val lastContainerIndex = containersInfo.keys.maxBy(_.replace("container", "").toInt)
      val lastContainer = (containersInfo \ lastContainerIndex).as[JsObject]
      println(s"gate4 - Last Container: $lastContainer") // Added debug print for lastContainer



      // Check if the items field is a string indicating "empty" or contains objects
      (lastContainer \ "items").validate[JsObject] match {
        case JsError(_) =>
          (lastContainer \ "items").asOpt[String] match {
            case Some("empty") =>

              if (updatedState.lootingRestryStatus >= updatedState.retryAttempts) {
                printInColor(ANSI_RED, f"[DEBUG] Items field is 'empty'. No items to process.")
                updatedState = updatedState.copy(stateHunting = "free", lootingRestryStatus = 0)
                println(s"Setting up stateHunting to free (5)")
              } else {
                printInColor(ANSI_RED, f"[DEBUG] Retrying - Items field is 'empty'. No items to process (Attempt ${updatedState.lootingRestryStatus + 1})")
                updatedState = updatedState.copy(lootingRestryStatus = updatedState.lootingRestryStatus + 1)
              }

            case None =>

              if (updatedState.lootingRestryStatus >= updatedState.retryAttempts) {
                printInColor(ANSI_RED, f"[DEBUG] No items field present or it is not in expected format")
                updatedState = updatedState.copy(stateHunting = "free", lootingRestryStatus = 0)
                println(s"Setting up stateHunting to free (6)")
              } else {
                printInColor(ANSI_RED, f"[DEBUG] Retrying - no items field present or it is not in expected format (Attempt ${updatedState.lootingRestryStatus + 1})")
                updatedState = updatedState.copy(lootingRestryStatus = updatedState.lootingRestryStatus + 1)
              }

            case Some(other) =>

              if (updatedState.lootingRestryStatus >= updatedState.retryAttempts) {
                printInColor(ANSI_RED, f"[DEBUG] Unexpected string in items field: $other")
                updatedState = updatedState.copy(stateHunting = "free", lootingRestryStatus = 0)
                println(s"Setting up stateHunting to free (7)")
              } else {
                printInColor(ANSI_RED, f"[DEBUG] Retrying - unexpected string in items field (Attempt ${updatedState.lootingRestryStatus + 1})")
                updatedState = updatedState.copy(lootingRestryStatus = updatedState.lootingRestryStatus + 1)
              }

          }

        case JsSuccess(itemsInContainer, _) =>



          printInColor(ANSI_RED, f"[DEBUG] Considering looting from$lastContainerIndex (statics: ${updatedState.staticContainersList})")

          // Check if the last container is not already in the updatedState.staticContainersList
          if (!updatedState.staticContainersList.contains(lastContainerIndex)) {
            printInColor(ANSI_RED, f"[DEBUG] Looting from $lastContainerIndex")
            val itemsInContainerInitial = (lastContainer \ "items").as[JsObject]
            //                    printInColor(ANSI_RED, f"[DEBUG] Items in container detected: $itemsInContainerInitial")

            // Assuming itemsInContainerInitial is immutable and fetched as a JsObject
            var itemsInContainer = JsObject(itemsInContainerInitial.fields.filterNot { case (_, itemInfo) =>
              val itemId = (itemInfo \ "itemId").as[Int]
              updatedState.alreadyLootedIds.contains(itemId)
            })

            if ((updatedState.currentTime - updatedState.lastEatFoodTime) < 5000) {
              // Filter out item 3577 if the last meal was less than 5 seconds ago
              itemsInContainer = JsObject(itemsInContainer.fields.filterNot {
                case (_, itemInfo) => (itemInfo \ "itemId").as[Int] == 3577
              })
            }

            // Prepare a set of item IDs from lootList
            val lootItems = settings.autoLootSettings.lootList.map(_.trim.split(",\\s*")(0).toInt).toSet
            printInColor(ANSI_RED, f"[DEBUG] Available Loot Items: ${lootItems.mkString(", ")}")


            // Extract all items from the last container and find first matching loot item
            val foundItemOpt = itemsInContainer.fields.reverse.collectFirst {
              case (slot, itemInfo) if (itemInfo \ "itemId").as[Int] == 3577 =>
                (slot, itemInfo.as[JsObject])

              case (slot, itemInfo) if lootItems((itemInfo \ "itemId").as[Int]) =>
                (slot, itemInfo.as[JsObject])
            }

            foundItemOpt match {

              case Some((slot, item)) if (item \ "itemId").as[Int] == 3577 =>
                println("FOUND FOOD")
                println(slot)
                println(item)
                val itemSlot = slot.replace("slot", "item") // Convert "slot2" to "item2"

                // Find the key in 'inventoryPanelLoc' that contains the 'lastContainerIndex'
                val inventoryPanelLoc = (screenInfo \ "inventoryPanelLoc").as[JsObject]
                val matchedKey = inventoryPanelLoc.keys.find(_.contains(lastContainerIndex))
                  .getOrElse(throw new NoSuchElementException("Key containing the substring not found"))

                val itemScreenInfo = (inventoryPanelLoc \ matchedKey \ "contentsPanel" \ itemSlot).as[JsObject]

                val x = (itemScreenInfo \ "x").as[Int]
                val y = (itemScreenInfo \ "y").as[Int]
                println(s"Item Screen Info for $itemSlot: x=$x, y=$y")

                // Perform right click actions
                val actionsSeq = Seq(
                  MouseAction(x, y, "move"),
                  MouseAction(x, y, "pressRight"), // Changed to pressRight for right-click
                  MouseAction(x, y, "releaseRight") // Changed to releaseRight for right-click
                )

                updatedState = updatedState.copy(lastEatFoodTime = updatedState.currentTime)
                actions = actions :+ FakeAction("useMouse", None, Some(MouseActions(actionsSeq)))
                println("[DEBUG] Right-click executed at coordinates for specified item")


              case Some((slot, item)) =>

                val itemId = (item \ "itemId").as[Int]
                val itemCount = (item \ "itemCount").as[Int]

                // Assuming lastContainerIndex and slot are already defined
                val itemSlot = slot.replace("slot", "item") // Convert "slot2" to "item2"

                //                        val itemScreenInfo = (screenInfo \ "inventoryPanelLoc" \ lastContainerIndex.replace("container", "item" + slot.replace("slot", ""))).as[JsObject]

                // Validate and access the contentsPanel under the specified container
                (screenInfo \ "inventoryPanelLoc" \ lastContainerIndex \ "contentsPanel").validate[JsObject] match {
                  case JsSuccess(contentsPanel, _) =>
                    // Now validate and access the specific item information within the contentsPanel
                    (contentsPanel \ itemSlot).validate[JsObject] match {

                      case JsSuccess(itemInfo, _) =>
                        val x = (itemInfo \ "x").as[Int]
                        val y = (itemInfo \ "y").as[Int]
                        printInColor(ANSI_RED, f"[DEBUG] Coordinates for $itemSlot: (x: $x, y: $y)")


                        // Retrieve action from lootList based on itemId
                        val action = settings.autoLootSettings.lootList
                          .find(_.trim.split(",\\s*")(0).toInt == itemId) // Find the correct item
                          .map(_.trim.split(",\\s*")) // Split each entry into an array
                          .map(arr => (arr(0), arr(1), arr(2))) // Convert array to a tuple
                          .get // Safely get the tuple
                          ._2 // Extract the second element (action) from the tuple

                        action match {
                          case "g" => // Handle ground placement
                            //                                    val mapTarget = (screenInfo \ "mapPanelLoc" \ "8x6").as[JsObject]
                            //                                    val (targetX, targetY) = ((mapTarget \ "x").as[Int], (mapTarget \ "y").as[Int])
                            printInColor(ANSI_RED, f"[DEBUG] Item: $itemId, is planned to be put on the ground")
                            // Define the list of potential tiles around "8x6"
                            val possibleTiles = List("7x5", "7x6", "7x7", "8x5", "8x6", "8x7", "9x5", "9x6", "9x7")

                            val areaInfo = (json \ "areaInfo").as[JsObject]
                            // Usage: Get the index of a random walkable tile
                            //                                    println(f"AreaInfo: $areaInfo")
                            //                                    val walkableTileIndices  = findRandomWalkableTile(areaInfo, possibleTiles)
                            //                                    val shuffledWalkableIndices = Random.shuffle(walkableTileIndices)

                            val walkableTileIndex = findRandomWalkableTile(areaInfo, possibleTiles)
                            println(walkableTileIndex)

                            walkableTileIndex match {
                              case Some(tileIndex) =>
                                printInColor(ANSI_RED, f"[DEBUG] Trying to find screen info about item")
                                // Extract x and y coordinates for the selected walkable tile from screenInfo
                                val mapTarget = (screenInfo \ "mapPanelLoc" \ tileIndex).as[JsObject]
                                val (targetX, targetY) = ((mapTarget \ "x").as[Int], (mapTarget \ "y").as[Int])

                                printInColor(ANSI_RED, f"[DEBUG] Item to loot has been found: $itemId")
                                updatedState = updatedState.copy(alreadyLootedIds = updatedState.alreadyLootedIds :+ itemId)
                                printInColor(ANSI_RED, f"[DEBUG] Adding $itemId to already looted items: ${updatedState.alreadyLootedIds}")

                                if (itemCount == 1) {
                                  val actionsSeq = moveSingleItem(x, y, targetX, targetY)
                                  actions = actions :+ FakeAction("useMouse", None, Some(MouseActions(actionsSeq)))
                                } else {
                                  val actionsSeq = moveMultipleItems(x, y, targetX, targetY)
                                  actions = actions :+ FakeAction("useMouse", None, Some(MouseActions(actionsSeq)))
                                }
                                printInColor(ANSI_RED, f"[DEBUG] Move item to ground at $tileIndex ($targetX, $targetY)")


                              case None =>
                                println("No tile found for placing loot.")
                            }


                          case containerIndex if containerIndex.forall(_.isDigit) => // Check if action is a digit, indicating a container
                            val containerName = s"container$containerIndex"
                            val itemLocation = (screenInfo \ "inventoryPanelLoc" \ containerName \ "contentsPanel" \ "item0").as[JsObject]
                            val (targetX, targetY) = ((itemLocation \ "x").as[Int], (itemLocation \ "y").as[Int])

                            if (itemCount == 1) {
                              val actionsSeq = moveSingleItem(x, y, targetX, targetY)
                              actions = actions :+ FakeAction("useMouse", None, Some(MouseActions(actionsSeq)))
                            } else {
                              val actionsSeq = moveMultipleItems(x, y, targetX, targetY)
                              actions = actions :+ FakeAction("useMouse", None, Some(MouseActions(actionsSeq)))
                            }
                            printInColor(ANSI_RED, f"[DEBUG] Move item to $containerName at ($targetX, $targetY)")

                        }


                      case JsError(errors) =>
                        printInColor(ANSI_RED, f"[DEBUG] Error accessing item info for $itemSlot: ${errors.mkString(", ")}")
                      // Handle the error case here, such as logging or corrective actions
                    }
                  case JsError(errors) =>
                    printInColor(ANSI_RED, f"[DEBUG] Error accessing contents panel for container $lastContainerIndex: ${errors.mkString(", ")}")
                  // Handle the error case here, such as logging or corrective actions
                }


              case None =>
                printInColor(ANSI_RED, f"[DEBUG] No item has been found, looking for container inside container")
                // Iterate over items in the last container to find any that are marked as a container
                println(s"itemsInContainer : $itemsInContainer")
                itemsInContainer.fields.collectFirst {
                  case (slot, itemInfo) if (itemInfo \ "isContainer").asOpt[Boolean].getOrElse(false) =>
                    val itemSlot = slot.replace("slot", "item")
                    println(s"lastContainerIndex : $lastContainerIndex")
                    //                              val itemScreenInfo = (screenInfo \ "inventoryPanelLoc" \ lastContainerIndex \ "contentsPanel" \ itemSlot).as[JsObject]

                    // Find the key in 'inventoryPanelLoc' that contains the 'lastContainerIndex'
                    val inventoryPanelLoc = (screenInfo \ "inventoryPanelLoc").as[JsObject]
                    val matchedKey = inventoryPanelLoc.keys.find(_.contains(lastContainerIndex)).getOrElse(throw new NoSuchElementException("Key containing the substring not found"))
                    val itemScreenInfo = (inventoryPanelLoc \ matchedKey \ "contentsPanel" \ itemSlot).as[JsObject]

                    println(s"Item Screen Info for $itemSlot: $itemScreenInfo")

                    val (x, y) = ((itemScreenInfo \ "x").as[Int], (itemScreenInfo \ "y").as[Int])
                    (x, y)
                } match {
                  case Some((x, y)) =>
                    printInColor(ANSI_RED, f"[DEBUG] Another container detected at position ($x, $y)")
                    // Right-click action sequence for the detected container
                    val actionsSeq = Seq(
                      MouseAction(x, y, "move"),
                      MouseAction(x, y, "pressRight"), // Changed to pressRight for right-click
                      MouseAction(x, y, "releaseRight") // Changed to releaseRight for right-click
                    )
                    actions = actions :+ FakeAction("useMouse", None, Some(MouseActions(actionsSeq)))


                  case None =>
                    printInColor(ANSI_RED, f"[DEBUG] No container ( and no items to loot) detected within the items, setting the state to free")
                    if (updatedState.lootingStatus >= updatedState.retryAttempts) {
                      printInColor(ANSI_RED, f"[DEBUG] No container nor items to loot")
                      updatedState = updatedState.copy(stateHunting = "free", lootingStatus = 0)
                      println(s"Setting up stateHunting to free (8)")
                    } else {
                      printInColor(ANSI_RED, f"[DEBUG] Retrying - No container nor items to loot. (Attempt ${updatedState.lootingStatus + 1})")
                      updatedState = updatedState.copy(lootingStatus = updatedState.lootingStatus + 1)
                    }
                }

            }
          } else {
            printInColor(ANSI_RED, f"[DEBUG] Backpack ($lastContainerIndex) is from static opened backpacks: ${updatedState.staticContainersList}")
            if (updatedState.lootingStatus >= updatedState.retryAttempts) {
              printInColor(ANSI_RED, f"[DEBUG] No new backpack has been found. Finishing looting")
              updatedState = updatedState.copy(stateHunting = "free", lootingStatus = 0)
              println(s"Setting up stateHunting to free (9)")
            } else {
              printInColor(ANSI_RED, f"[DEBUG] Retrying - No new backpack has been found. (Attempt ${updatedState.lootingStatus + 1})")
              updatedState = updatedState.copy(lootingStatus = updatedState.lootingStatus + 1)
            }
          }
      }

    } catch {
      case e: NoSuchElementException =>
        // Handle cases where the key is not found or cannot be converted as expected
        printInColor(ANSI_RED, f"[ERROR] Error processing containers: ${e.getMessage}")
        updatedState = updatedState.copy(stateHunting = "free", lootingStatus = 0)
        println(s"Setting up stateHunting to free (10)")
      case e: Exception =>
        // Handle any other unexpected exceptions
        printInColor(ANSI_RED, f"[ERROR] Unexpected error: ${e.getMessage}")
        updatedState = updatedState.copy(stateHunting = "free", lootingStatus = 0)
        println(s"Setting up stateHunting to free (11)")
    }
    ((actions, logs), updatedState)
  }


  def processFreeStateAfterFight(carcassIndexList: List[String], json: JsValue, settings: UISettings, currentState: ProcessorState): ((Seq[FakeAction], Seq[Log]), ProcessorState) = {
    var updatedState = currentState
    var actions: Seq[FakeAction] = Seq()
    var logs: Seq[Log] = Seq()



    val carcassToLootId = updatedState.carsassToLoot.headOption.getOrElse("")

    val mapPanelLoc = (json \ "screenInfo" \ "mapPanelLoc").asOpt[JsObject].getOrElse(Json.obj())
    // Find the coordinates corresponding to the carcassToLoot id
    val screenCoordsOpt = mapPanelLoc.fields.collectFirst {
      case (_, obj: JsObject) if (obj \ "id").asOpt[String].contains(carcassToLootId) =>
        for {
          x <- (obj \ "x").asOpt[Int]
          y <- (obj \ "y").asOpt[Int]
        } yield (x, y)
    }.flatten.getOrElse((0, 0)) // Default coordinates if not found

    // Define the sequence of mouse actions based on retrieved screen coordinates
    val (xPositionScreen, yPositionScreen) = screenCoordsOpt
    printInColor(ANSI_RED, f"[DEBUG] Opening creature carcass on screen position $xPositionScreen, $yPositionScreen")
    updatedState = updatedState.copy(alreadyLootedIds = List())
    val actionsSeq = Seq(
      MouseAction(xPositionScreen, yPositionScreen, "move"),
      MouseAction(xPositionScreen, yPositionScreen, "pressCtrl"),
      MouseAction(xPositionScreen, yPositionScreen, "pressRight"),
      MouseAction(xPositionScreen, yPositionScreen, "releaseRight"),
      MouseAction(xPositionScreen, yPositionScreen, "releaseCtrl")
    )

    actions = actions :+ addMouseAction("useMouse", None, updatedState, actionsSeq)
    //                    actions = actions :+ FakeAction("useMouse", None, Some(MouseActions(actionsSeq)))
    updatedState = updatedState.copy(stateHunting = "opening window")
    
    ((actions, logs), updatedState)
  }


  def processLootingAfterFight(carcassIndexList: List[String], json: JsValue, settings: UISettings, currentState: ProcessorState): ((Seq[FakeAction], Seq[Log]), ProcessorState) = {
    var updatedState = currentState
    var actions: Seq[FakeAction] = Seq()
    var logs: Seq[Log] = Seq()

    // Extract xPos, yPos, and zPos from the carcass position string
    val xPos = carcassIndexList.head.substring(0, 5).toInt
    val yPos = carcassIndexList.head.substring(5, 10).toInt
    val zPos = carcassIndexList.head.substring(10, 12).toInt

    // Get character's position from the JSON
    val characterInfo = (json \ "characterInfo").as[JsValue]
    val charXPos = (characterInfo \ "PositionX").as[Int]
    val charYPos = (characterInfo \ "PositionY").as[Int]
    val charZPos = (characterInfo \ "PositionZ").as[Int]

    // Calculate Chebyshev distance between carcass and character
    val distance = Math.max(Math.abs(xPos - charXPos), Math.abs(yPos - charYPos))

    // Check if the carcass is on a different level (Z position) or farther than 5 tiles
    if (zPos != charZPos || distance > 5) {
      println(s"Carcass is either on a different level (Z: $zPos vs. $charZPos) or too far from the character. Distance: $distance")

      // Remove this carcass from the list by updating the state
      updatedState = updatedState.copy(carsassToLoot = carcassIndexList.tail)


    } else {
      // Carcass is on the same level and within 5 tiles

      println(s"Carcass is close to the character and within 5 tiles. Distance: $distance")
      println(s"updatedState.lastTargetName: ${updatedState.lastTargetName}")

      updatedState.stateHunting match {
        case "looting" =>
          printInColor(ANSI_RED, f"[DEBUG] looting window")
          val resultProcessLootingState = processLootingState(json, settings, updatedState)
          actions = actions ++ resultProcessLootingState._1._1
          logs = logs ++ resultProcessLootingState._1._2
          updatedState = resultProcessLootingState._2

        case "opening window" =>
          printInColor(ANSI_RED, f"[DEBUG] opening window")
          val resultProcessOpeningWindowState = processOpeningWindowState(json, settings, updatedState)
          actions = actions ++ resultProcessOpeningWindowState._1._1
          logs = logs ++ resultProcessOpeningWindowState._1._2
          updatedState = resultProcessOpeningWindowState._2


        case "attacking" =>
          printInColor(ANSI_RED, f"[DEBUG] attacking state")
          println(s"updatedState.lastTargetName.nonEmpty: ${updatedState.lastTargetName.nonEmpty}")
          println(s"updatedState.monstersListToLoot.contains(updatedState.lastTargetName)): ${updatedState.monstersListToLoot.contains(updatedState.lastTargetName)}")

          val resultProcessAttackingState = processAttackingState(json, settings, updatedState)
          actions = actions ++ resultProcessAttackingState._1._1
          logs = logs ++ resultProcessAttackingState._1._2
          updatedState = resultProcessAttackingState._2

        case "free" =>
          printInColor(ANSI_RED, f"[DEBUG] free state after fight")
          val resultProcessFreeStateAfterFight = processFreeStateAfterFight(carcassIndexList, json, settings, updatedState)
          actions = actions ++ resultProcessFreeStateAfterFight._1._1
          logs = logs ++ resultProcessFreeStateAfterFight._1._2
          updatedState = resultProcessFreeStateAfterFight._2

        //          updatedState = updatedState.copy(carsassToLoot = carcassIndexList.tail)
        // Handle the carcass here (add your logic)
      }
    }

    ((actions, logs), updatedState)
  }




  def moveSingleItem(xItemPosition: Int, yItemPositon: Int, xDestPos: Int, yDestPos: Int): Seq[MouseAction] =
    Seq(
      MouseAction(xItemPosition, yItemPositon, "move"),
      MouseAction(xItemPosition, yItemPositon, "pressLeft"),
      MouseAction(xDestPos, yDestPos, "move"),
      MouseAction(xDestPos, yDestPos, "releaseLeft")
    )

  def moveMultipleItems(xItemPosition: Int, yItemPositon: Int, xDestPos: Int, yDestPos: Int): Seq[MouseAction] = {
    Seq(
      MouseAction(xItemPosition, yItemPositon, "move"),
      MouseAction(xItemPosition, yItemPositon, "pressCtrl"),
      MouseAction(xItemPosition, yItemPositon, "pressLeft"),
      MouseAction(xDestPos, yDestPos, "move"),
      MouseAction(xDestPos, yDestPos, "releaseLeft"),
      MouseAction(xDestPos, yDestPos, "releaseCtrl")
    )
  }

    // Function to find a random walkable tile

  // Assume this function is corrected to return List[String]

  def findRandomWalkableTile(areaInfo: JsObject, possibleTiles: List[String]): Option[String] = {
    println("Inside findRandomWalkableTile")

    // Extract the tiles information from the area info JSON object
    val tilesInfo = (areaInfo \ "tiles").as[JsObject]

    // Collect all indices of walkable tiles
    val allWalkableIndices = tilesInfo.fields.collect {
      case (tileId, jsValue) if possibleTiles.contains((jsValue \ "index").asOpt[String].getOrElse("")) &&
        (jsValue \ "isWalkable").asOpt[Boolean].getOrElse(false) =>
        (jsValue \ "index").as[String]
    }.toList

    // Shuffle the list of all walkable indices and return one at random
    Random.shuffle(allWalkableIndices).headOption
  }

}


