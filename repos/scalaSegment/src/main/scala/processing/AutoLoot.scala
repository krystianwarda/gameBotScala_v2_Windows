//package processing
//
//import mouse.FakeAction
//import play.api.libs.json._
//import utils.SettingsUtils.UISettings
//import processing.AutoTarget.{parseCreature, transformToJSON}
//import processing.CaveBot.{Vec, calculateDirection}
//import processing.Process.{addMouseAction, extractOkButtonPosition}
//import processing.TeamHunt.generateSubwaypointsToGamePosition
//import utils.{ProcessorState, StaticGameInfo}
//import utils.consoleColorPrint.{ANSI_GREEN, ANSI_RED, printInColor}
//
//import scala.util.Random
//
//case class ProcessResult(actions: Seq[FakeAction], logs: Seq[Log], updatedState: ProcessorState)
//
//object AutoLoot {
//  def computeAutoLootActions(json: JsValue, settings: UISettings, currentState: ProcessorState): ((Seq[FakeAction], Seq[Log]), ProcessorState) = {
//    val startTime = System.nanoTime()
//    var actions: Seq[FakeAction] = Seq.empty
//    var logs: Seq[Log] = Seq.empty
//    var updatedState = currentState // Initialize updatedState
//    val currentTime = System.currentTimeMillis()
//
//
//    if (updatedState.staticContainersList.isEmpty) {
//      // Assuming `json` is already defined as JsValue containing the overall data
//      val containersInfo = (json \ "containersInfo").as[JsObject]
//
//      // Extracting keys as a list of container names, but only include those which have "bag", "backpack", or "ring" in their names
//      val containerKeys = containersInfo.keys.toList.filter { key =>
//        val name = (containersInfo \ key \ "name").asOpt[String].getOrElse("")
//        name.contains("bag") || name.contains("backpack") || name.contains("ring")
//      }
//      printInColor(ANSI_RED, f"[DEBUG] Static containers loaded: $containerKeys")
//      updatedState = updatedState.copy(staticContainersList = containerKeys)
//    }
//
//    // Extracting and using the position
//    extractOkButtonPosition(json) match {
//      case Some((posX, posY)) =>
//        // Now use this stored current time for all time checks
//        val timeExtraWindowLoot = updatedState.currentTime - updatedState.lastExtraWindowLoot
//        if (timeExtraWindowLoot >= updatedState.longTimeLimit) {
//          val actionsSeq = Seq(
//            MouseAction(posX, posY, "move"),
//            MouseAction(posX, posY, "pressLeft"),
//            MouseAction(posX, posY, "releaseLeft")
//          )
//          printInColor(ANSI_RED, "[DEBUG] Closing object movement window.")
//
//          actions = actions :+ addMouseAction("useMouse", None, updatedState, actionsSeq)
//          //            actions = actions :+ FakeAction("useMouse", None, Some(MouseActions(actionsSeq)))
//
//          // Update the extraWidowLootStatus with the current time to mark this execution
//          updatedState = updatedState.copy(lastExtraWindowLoot = updatedState.currentTime)
//        } else {
//          printInColor(ANSI_RED, f"[DEBUG] Closing object movement window. Not enough time has passed since the last execution: ${updatedState.lastExtraWindowLoot}ms ago.")
//        }
//
//      case None => // Do nothing
//    }
//
////    printInColor(ANSI_RED, f"[DEBUG] autoLoot: ${settings.autoTargetSettings.enabled}, autoLoot: ${settings.autoLootSettings.enabled}")
//    if (settings.autoTargetSettings.enabled && settings.autoLootSettings.enabled) {
//      printInColor(ANSI_RED, f"[DEBUG] computeAutoLootActions process started with status:${updatedState.cavebot.stateHunting}")
//
//
//
//
//
//      val processResultDetectDeadCreatures = detectDeadCreatures(json, settings, updatedState)
//      actions ++= processResultDetectDeadCreatures.actions
//      logs ++= processResultDetectDeadCreatures.logs
//      updatedState = processResultDetectDeadCreatures.updatedState
//
//
//      // Process the hunting state
//      val processResultStateHunting = processStateHunting(json, settings, updatedState)
//      actions ++= processResultStateHunting.actions
//      logs ++= processResultStateHunting.logs
//      updatedState = processResultStateHunting.updatedState
//
//      println(s"After stateLooting: ${updatedState.autoloot.stateLooting},stateLootPlunder: ${updatedState.autoloot.stateLootPlunder}.")
//
//
//    } else if (settings.autoTargetSettings.enabled) {
//      println("settings.autoTargetSettings.enabled autoloot")
//      val lastAttackedCreatureInfo = (json \ "lastAttackedCreatureInfo").asOpt[JsObject].getOrElse(Json.obj())
//      val lastAttackedId = (lastAttackedCreatureInfo \ "LastAttackedId").asOpt[Int].getOrElse(0)
//      val isLastAttackedCreatureDead = (lastAttackedCreatureInfo \ "IsDead").asOpt[Boolean].getOrElse(false)
//
//      if (isLastAttackedCreatureDead) {
//        logs = logs :+ Log(f"[DEBUG] Creature $lastAttackedId  is dead.")
//
//        updatedState = updatedState.copy(
//          cavebot = updatedState.cavebot.copy(
//            stateHunting = "free"
//          )
//        )
//
//      }
//    }
//    val endTime = System.nanoTime()
//    val duration = (endTime - startTime) / 1e9d
//    printInColor(ANSI_GREEN, f"[INFO] Processing computeAutoLootActions took $duration%.6f seconds")
//
//    ((actions, logs), updatedState)
//  }
//
//
//
//  def processStateHunting(json: JsValue, settings: UISettings, currentState: ProcessorState): ProcessResult = {
//    var updatedState = currentState
//    updatedState.cavebot.stateHunting match {
//      case "attacking" =>
//        println("Attacking creature")
//        val ProcessResult(actions, logs, newState) = handleAttackingState(json, settings, updatedState)
//        ProcessResult(actions, logs, newState)
//
//      case "creature killed" =>
//        println("Creature killed.")
//        val ProcessResult(actions, logs, newState) = handleCreatureKilledState(json, settings, updatedState)
//        ProcessResult(actions, logs, newState)
//
//      case "loot or fight or free" =>
//        println("Loot or fight or free.")
//        val ProcessResult(actions, logs, newState) = handleLootOrFightOrFreeState(json, settings, updatedState)
//        ProcessResult(actions, logs, newState)
//
//      case "looting in progress" =>
//        println("Looting in progress.")
//        val ProcessResult(actions, logs, newState) = handleLooting(json, settings, updatedState)
//        ProcessResult(actions, logs, newState)
//
//      case "free" =>
//        println("Nothing to do.")
//        ProcessResult(Seq(), Seq(), updatedState)
//
//      case _ =>
//        println("Unknown state, doing nothing.")
//        ProcessResult(Seq(), Seq(), updatedState)
//    }
//  }
//
//
//  def handleLooting(json: JsValue, settings: UISettings, currentState: ProcessorState): ProcessResult = {
//    // Initialize empty actions and logs
//    val actions: Seq[FakeAction] = Seq.empty
//    var logs: Seq[Log] = Seq.empty
//    var updatedState = currentState
//    println(s"Start stateLooting: ${updatedState.autoloot.stateLooting},stateLootPlunder: ${updatedState.autoloot.stateLootPlunder}.")
//
//    updatedState.autoloot.stateLooting match {
//      case "free" =>
//        println("Looting -> free.")
//        val ProcessResult(actions, logs, newState) = handleLootOrMoveCarcass(json, settings, updatedState)
//        ProcessResult(actions, logs, newState)
//
//      case "moving carcass" =>
//        println("Looting -> moving carcass.")
//        val ProcessResult(actions, logs, newState) = handleMovingOldCarcass(json, settings, updatedState)
//        ProcessResult(actions, logs, newState)
//
//      case "opening carcass" =>
//        println("Looting -> opening carcass.")
//        val ProcessResult(actions, logs, newState) = handleOpeningCarcass(json, settings, updatedState)
//        ProcessResult(actions, logs, newState)
//
//      case "clicking open button" =>
//        println("Looting -> clicking open button.")
//        val ProcessResult(actions, logs, newState) = handleClickingOpen(json, settings, updatedState)
//        ProcessResult(actions, logs, newState)
//
//      case "loot plunder" =>
//        println("Looting -> loot plunder.")
//        val ProcessResult(actions, logs, newState) = handleLootPlunder(json, settings, updatedState)
//        ProcessResult(actions, logs, newState)
//
//    }
//  }
//
//
//  def handleLootPlunder(json: JsValue, settings: UISettings, currentState: ProcessorState): ProcessResult = {
//    var actions: Seq[FakeAction] = Seq.empty
//    var logs: Seq[Log] = Seq.empty
//    var updatedState = currentState
//
//    // Process the stateLootPlunder to determine the appropriate looting action
//    updatedState.autoloot.stateLootPlunder match {
//      case "free" =>
//        // Placeholder logic to assess the loot
//        logs = logs :+ Log("[DEBUG] Assessing the loot from the carcass.")
//        val ProcessResult(assessActions, assessLogs, newState) = handleAssessLoot(json, settings, updatedState)
//        ProcessResult(assessActions, assessLogs, newState)
//
//      case "move item to backpack" =>
//        // Placeholder logic to move item to the backpack
//        logs = logs :+ Log("[DEBUG] Moving loot item to the backpack.")
//        ProcessResult(actions, logs, updatedState)
//      //            val ProcessResult(moveToBackpackActions, moveToBackpackLogs, newState) = handleMoveItemToBackpack(json, settings, updatedState)
//      //            ProcessResult(moveToBackpackActions, moveToBackpackLogs, newState)
//
//      case "move item to ground" =>
//        // Placeholder logic to move item to the ground
//        logs = logs :+ Log("[DEBUG] Moving loot item to the ground.")
//        val ProcessResult(moveToGroundActions, moveToGroundLogs, newState) = handleMoveItemToGround(json, settings, updatedState)
//        ProcessResult(moveToGroundActions, moveToGroundLogs, newState)
//
//      case "handle food" =>
//        // Placeholder logic to handle food
//        logs = logs :+ Log("[DEBUG] Handling food from the loot.")
//        val ProcessResult(handleFoodActions, handleFoodLogs, newState) = handleFood(json, settings, updatedState)
//        ProcessResult(handleFoodActions, handleFoodLogs, newState)
//
//      case "open subcontainer" =>
//        // Placeholder logic to open subcontainers
//        logs = logs :+ Log("[DEBUG] Opening subcontainer within the loot.")
//        val ProcessResult(openSubcontainerActions, openSubcontainerLogs, newState) = handleOpenSubcontainer(json, settings, updatedState)
//        ProcessResult(openSubcontainerActions, openSubcontainerLogs, newState)
//
//      case _ =>
//        // Default case if no state is matched
//        logs = logs :+ Log("[ERROR] Unknown state in stateLootPlunder.")
//        // Correct usage of .copy to modify autoloot
//
//        updatedState = updatedState.copy(
//          autoloot = updatedState.autoloot.copy(
//            stateLooting = "free",
//            stateLootPlunder = "free"
//          )
//        )
//
//        ProcessResult(actions, logs, updatedState)
//    }
//  }
//
//
//  def handleCreatureKilledState(json: JsValue, settings: UISettings, currentState: ProcessorState): ProcessResult = {
//    // Initialize empty actions and logs
//    val actions: Seq[FakeAction] = Seq.empty
//    var logs: Seq[Log] = Seq.empty
//    var updatedState = currentState
//
//    println(s"updatedState.lastTargetName: ${updatedState.lastTargetName}. Creature is killed, setting to 'loot or fight or free'.")
//
//    updatedState = updatedState.copy(
//      cavebot = updatedState.cavebot.copy(
//        stateHunting = "loot or fight or free"
//      )
//    )
//    // Return the ProcessResult with the updated actions, updated logs, and updated state
//    ProcessResult(actions, logs, updatedState)
//  }
//
//
//  def handleAttackingState(json: JsValue, settings: UISettings, currentState: ProcessorState): ProcessResult = {
//    var actions: Seq[FakeAction] = Seq.empty
//    var logs: Seq[Log] = Seq.empty
//    var updatedState = currentState
//
//    // Check if there is any active attack
//    (json \ "attackInfo" \ "Id").asOpt[Int] match {
//      case None =>
//        // No active attack, check if the last creature attacked is dead
//        val lastAttackedCreatureInfo = (json \ "lastAttackedCreatureInfo").asOpt[JsObject].getOrElse(Json.obj())
//        val lastAttackedId = (lastAttackedCreatureInfo \ "LastAttackedId").asOpt[Int].getOrElse(0)
//        val isLastAttackedCreatureDead = (lastAttackedCreatureInfo \ "IsDead").asOpt[Boolean].getOrElse(false)
//
//        if (isLastAttackedCreatureDead) {
//          logs = logs :+ Log(f"[DEBUG] Creature with ID $lastAttackedId is dead.")
//
//          updatedState = updatedState.copy(
//            cavebot = updatedState.cavebot.copy(
//              stateHunting = "creature killed"
//            )
//          )
//
//        } else {
//          logs = logs :+ Log(f"[DEBUG] No active attack, but creature with ID $lastAttackedId is not dead yet.")
//
//          updatedState = updatedState.copy(
//            cavebot = updatedState.cavebot.copy(
//              stateHunting = "free"
//            )
//          )
//
//        }
//
//      case Some(_) =>
//        // Attack is still in progress
//        logs = logs :+ Log("[DEBUG] Still fighting.")
//    }
//
//    ProcessResult(actions, logs, updatedState)
//  }
//
//
//
//
//  def handleOpenSubcontainer(json: JsValue, settings: UISettings, currentState: ProcessorState): ProcessResult = {
//    var actions: Seq[FakeAction] = Seq.empty
//    var logs: Seq[Log] = Seq.empty
//    var updatedState = currentState
//
//    // Get the screen position of the subcontainer from lootScreenPosToPlunder
//    val subcontainerScreenPos = updatedState.autoloot.lootScreenPosToPlunder
//
//    if (subcontainerScreenPos != Vec(0, 0)) {
//      // Right-click on the subcontainer at the given screen position
//      logs = logs :+ Log(f"[DEBUG] Right-clicking on subcontainer at position $subcontainerScreenPos.")
//
//      val actionsSeq = Seq(
//        MouseAction(subcontainerScreenPos.x, subcontainerScreenPos.y, "move"),
//        MouseAction(subcontainerScreenPos.x, subcontainerScreenPos.y, "pressRight"),
//        MouseAction(subcontainerScreenPos.x, subcontainerScreenPos.y, "releaseRight")
//      )
//
//      // Add the mouse actions to the sequence
//      actions = actions :+ FakeAction("useMouse", None, Some(MouseActions(actionsSeq)))
//
//      // Update the state to indicate the subcontainer has been opened and reset loot status
//
//      updatedState = updatedState.copy(
//        autoloot = updatedState.autoloot.copy(
//          stateLootPlunder = "free",
//          lootIdToPlunder = 0,
//          lootScreenPosToPlunder = Vec(0, 0)
//        )
//      )
//
//
//
//      logs = logs :+ Log(f"[DEBUG] Subcontainer opened, stateLootPlunder set to free.")
//
//    } else {
//      // If no valid screen position, log an error and reset state
//      logs = logs :+ Log(f"[ERROR] No valid screen position found for subcontainer.")
//
//      updatedState = updatedState.copy(
//        autoloot = updatedState.autoloot.copy(
//          stateLootPlunder = "free"
//        )
//      )
//    }
//
//    ProcessResult(actions, logs, updatedState)
//  }
//
//
//  def handleMoveItemToGround(json: JsValue, settings: UISettings, currentState: ProcessorState): ProcessResult = {
//    var actions: Seq[FakeAction] = Seq.empty
//    var logs: Seq[Log] = Seq.empty
//    var updatedState = currentState
//
//    // Convert tiles from carcassToLootImmediately and carcassToLootAfterFight to grid format, excluding those not in mapPanelLoc
//    val excludedTilesGrid = (updatedState.autoloot.carcassToLootImmediately ++ updatedState.autoloot.carcassToLootAfterFight)
//      .flatMap { case (tileId, _) => convertGameLocationToGrid(json, tileId) }.toSet
//
//    // Get the item's screen position, ID, and count
//    val itemScreenPos = updatedState.autoloot.lootScreenPosToPlunder
//    val itemId = updatedState.autoloot.lootIdToPlunder
//    val itemCount = updatedState.autoloot.lootCountToPlunder
//
//    if (itemScreenPos != Vec(0, 0)) {
//      // List of possible walkable tiles
//      val possibleTiles = List("7x5", "7x6", "7x7", "8x5", "8x6", "8x7", "9x5", "9x6", "9x7")
//      val areaInfo = (json \ "areaInfo").as[JsObject]
//
//      // Find a random walkable tile, excluding the tiles in excludedTilesGrid
//      val walkableTileIndexOpt = findRandomWalkableTile(areaInfo, possibleTiles.filterNot(excludedTilesGrid.contains))
//
//      walkableTileIndexOpt match {
//        case Some(tileIndex) =>
//          // Extract x and y coordinates for the selected walkable tile from screenInfo
//          val mapPanelLoc = (json \ "screenInfo" \ "mapPanelLoc" \ tileIndex).as[JsObject]
//          val (targetX, targetY) = ((mapPanelLoc \ "x").as[Int], (mapPanelLoc \ "y").as[Int])
//
//          logs = logs :+ Log(f"[DEBUG] Moving item $itemId with count $itemCount to ground at tile $tileIndex ($targetX, $targetY).")
//
//          // Define actions to move the item based on its count (single or multiple)
//          val actionsSeq = if (itemCount == 1) {
//            moveSingleItem(itemScreenPos.x, itemScreenPos.y, targetX, targetY)
//          } else {
//            moveMultipleItems(itemScreenPos.x, itemScreenPos.y, targetX, targetY)
//          }
//
//          // Add the mouse actions to the sequence
//          actions = actions :+ FakeAction("useMouse", None, Some(MouseActions(actionsSeq)))
//
//          // Update the state to reflect the item has been moved
//          updatedState = updatedState.copy(
//            autoloot = updatedState.autoloot.copy(
//              stateLootPlunder = "free",
//              lootIdToPlunder = 0,
//              lootCountToPlunder = 0,
//              lootScreenPosToPlunder = Vec(0, 0)
//            )
//          )
//          logs = logs :+ Log(f"[DEBUG] Successfully moved item $itemId to ground at ($targetX, $targetY).")
//
//        case None =>
//          logs = logs :+ Log(f"[ERROR] No valid walkable tile found to move the item.")
//
//          updatedState = updatedState.copy(
//            autoloot = updatedState.autoloot.copy(
//              stateLootPlunder = "free"
//            )
//          )
//      }
//    } else {
//      logs = logs :+ Log(f"[ERROR] No valid screen position found for the item to move.")
//
//      updatedState = updatedState.copy(
//        autoloot = updatedState.autoloot.copy(
//          stateLootPlunder = "free"
//        )
//      )
//    }
//
//    ProcessResult(actions, logs, updatedState)
//  }
//
//
//  def handleFood(json: JsValue, settings: UISettings, currentState: ProcessorState): ProcessResult = {
//    var actions: Seq[FakeAction] = Seq.empty
//    var logs: Seq[Log] = Seq.empty
//    var updatedState = currentState
//
//    // Get the current time and last time food was eaten
//    val currentTime = updatedState.currentTime
//    val lastEatFoodTime = updatedState.autoloot.lastEatFoodTime
//    val timeSinceLastEat = currentTime - lastEatFoodTime
//
//    // Check if regTime is less than 600
//    val regTime = (json \ "regTime").as[Int]
//    if (regTime < 600) {
//      logs = logs :+ Log(f"[DEBUG] regTime is $regTime. You should eat food.")
//
//      // Check if enough time has passed since the last time food was eaten (at least 0.5 seconds)
//      if (timeSinceLastEat >= 500) {
//        // Get the screen position of the food to plunder
//        val foodScreenPos = updatedState.autoloot.lootScreenPosToPlunder
//
//        if (foodScreenPos != Vec(0, 0)) {
//          // Right-click on the food
//          logs = logs :+ Log(f"[DEBUG] Eating food at screen position $foodScreenPos.")
//          val actionsSeq = Seq(
//            MouseAction(foodScreenPos.x, foodScreenPos.y, "move"),
//            MouseAction(foodScreenPos.x, foodScreenPos.y, "pressRight"),
//            MouseAction(foodScreenPos.x, foodScreenPos.y, "releaseRight")
//          )
//
//          // Add the mouse actions to perform the right-click
//          actions = actions :+ FakeAction("useMouse", None, Some(MouseActions(actionsSeq)))
//
//          // Update the last eat food time and set stateLootPlunder to "free"
//          updatedState = updatedState.copy(
//            autoloot = updatedState.autoloot.copy(
//              lastEatFoodTime = currentTime,
//              stateLootPlunder = "free"
//            )
//          )
//
//          logs = logs :+ Log(f"[DEBUG] Food eaten. StateLootPlunder set to free.")
//        } else {
//          logs = logs :+ Log(f"[ERROR] No valid screen position for food found.")
//        }
//      } else {
//        logs = logs :+ Log(f"[DEBUG] Not enough time has passed since the last food was eaten. Waiting for 0.5 seconds.")
//      }
//    } else {
//      logs = logs :+ Log(f"[DEBUG] regTime is greater than 600. No need to eat food.")
//      // If regTime is greater than 600, just set stateLootPlunder to free
//      updatedState = updatedState.copy(
//        autoloot = updatedState.autoloot.copy(
//          stateLootPlunder = "free"
//        )
//      )
//    }
//
//    ProcessResult(actions, logs, updatedState)
//  }
//
//
//  def handleAssessLoot(json: JsValue, settings: UISettings, currentState: ProcessorState): ProcessResult = {
//    var actions: Seq[FakeAction] = Seq.empty
//    var logs: Seq[Log] = Seq.empty
//    var updatedState = currentState
//
//    // Extract containers and screen info from the JSON
//    val containersInfo = (json \ "containersInfo").as[JsObject]
//    val screenInfo = (json \ "screenInfo").as[JsObject]
//
//    // Get the last opened container (assumed to be the loot container)
//    val lastContainerIndex = containersInfo.keys.maxBy(_.replace("container", "").toInt)
//    val lastContainer = (containersInfo \ lastContainerIndex).as[JsObject]
//
//    // Handle the case where the container is empty
//    (lastContainer \ "items").asOpt[JsValue] match {
//      case Some(JsString("empty")) =>
//        // If the items field is "empty", log it and mark looting as complete
//        logs = logs :+ Log(f"[DEBUG] Container $lastContainerIndex is empty.")
//
//        updatedState = updatedState.copy(
//          autoloot = updatedState.autoloot.copy(
//            stateLooting = "free",
//            stateLootPlunder = "free",
//          ),
//          cavebot = updatedState.cavebot.copy(
//            stateHunting = "loot or fight or free"
//          )
//        )
//
//      case Some(itemsObj: JsObject) =>
//        // If the container has items, process them
//        logs = logs :+ Log(f"[DEBUG] Assessing items in container $lastContainerIndex.")
//
//        // Prepare a set of item IDs from the loot settings
//        val lootItems = settings.autoLootSettings.lootList.map(_.trim.split(",\\s*")(0).toInt).toSet
//
//        // Try to find lootable items
//        val foundItemOpt = itemsObj.fields.collectFirst {
//          case (slot, itemInfo) if lootItems.contains((itemInfo \ "itemId").as[Int]) =>
//            (slot, itemInfo)
//        }
//
//        foundItemOpt match {
//          case Some((slot, itemInfo)) =>
//            val itemId = (itemInfo \ "itemId").as[Int]
//            val itemCount = (itemInfo \ "itemCount").as[Int]
//            val action = settings.autoLootSettings.lootList
//              .find(_.trim.split(",\\s*")(0).toInt == itemId)
//              .map(_.trim.split(",\\s*")(1)) // Extract the action part (like "g" for ground)
//              .getOrElse("")
//
//            // Find screen position for the item from screenInfo
//            val itemSlot = slot.replace("slot", "item")
//            val itemScreenInfoOpt = (screenInfo \ "inventoryPanelLoc" \ lastContainerIndex \ "contentsPanel" \ itemSlot).asOpt[JsObject]
//
//            val lootScreenPosToPlunder = itemScreenInfoOpt match {
//              case Some(itemScreenInfo) =>
//                Vec((itemScreenInfo \ "x").as[Int], (itemScreenInfo \ "y").as[Int])
//              case None =>
//                Vec(0, 0) // Default if no screen info found
//            }
//
//            action match {
//              case "g" =>
//                logs = logs :+ Log(f"[DEBUG] Item $itemId will be moved to the ground at screen position $lootScreenPosToPlunder.")
//
//                updatedState = updatedState.copy(
//                  autoloot = updatedState.autoloot.copy(
//                    stateLootPlunder = "move item to ground",
//                    lootIdToPlunder = itemId,
//                    lootCountToPlunder = itemCount, // Store the item count
//                    lootScreenPosToPlunder = lootScreenPosToPlunder
//                  )
//                )
//
//              case _ =>
//                logs = logs :+ Log(f"[DEBUG] Placeholder for item $itemId with action $action.")
//
//                updatedState = updatedState.copy(
//                  autoloot = updatedState.autoloot.copy(
//                    stateLootPlunder = "move item to backpack",
//                    lootIdToPlunder = itemId,
//                    lootCountToPlunder = itemCount, // Store the item count
//                    lootScreenPosToPlunder = lootScreenPosToPlunder
//                  )
//                )
//            }
//
//          case None =>
//            // No lootable items found, check for food
//            logs = logs :+ Log(f"[DEBUG] No loot items found, checking for food.")
//            val foundFoodOpt = itemsObj.fields.collectFirst {
//              case (slot, itemInfo) if StaticGameInfo.Items.FoodsIds.contains((itemInfo \ "itemId").as[Int]) =>
//                (slot, itemInfo)
//            }
//
//
//            foundFoodOpt match {
//              case Some((slot, foodItem)) =>
//                // Get screen position for the food item
//                val itemSlot = slot.replace("slot", "item")
//                val foodScreenInfoOpt = (screenInfo \ "inventoryPanelLoc" \ lastContainerIndex \ "contentsPanel" \ itemSlot).asOpt[JsObject]
//
//                val lootScreenPosToPlunder = foodScreenInfoOpt match {
//                  case Some(itemScreenInfo) =>
//                    Vec((itemScreenInfo \ "x").as[Int], (itemScreenInfo \ "y").as[Int])
//                  case None =>
//                    Vec(0, 0) // Default if no screen info found
//                }
//
//                logs = logs :+ Log(f"[DEBUG] Food found in slot $slot at screen position $lootScreenPosToPlunder.")
//
//                updatedState = updatedState.copy(
//                  autoloot = updatedState.autoloot.copy(
//                    stateLootPlunder = "handle food",
//                    lootIdToPlunder = (foodItem \ "itemId").as[Int],
//                    lootCountToPlunder = (foodItem \ "itemCount").as[Int], // Store the food count
//                    lootScreenPosToPlunder = lootScreenPosToPlunder
//                  )
//                )
//
//              case None =>
//                // No food found, check for subcontainers
//                logs = logs :+ Log(f"[DEBUG] No food found, checking for subcontainers.")
//                val foundSubcontainerOpt = itemsObj.fields.collectFirst {
//                  case (slot, itemInfo) if (itemInfo \ "isContainer").asOpt[Boolean].getOrElse(false) =>
//                    (slot, itemInfo)
//                }
//
//
//                foundSubcontainerOpt match {
//                  case Some((slot, subcontainer)) =>
//                    // Get screen position for the subcontainer
//                    val itemSlot = slot.replace("slot", "item")
//                    val subcontainerScreenInfoOpt = (screenInfo \ "inventoryPanelLoc" \ lastContainerIndex \ "contentsPanel" \ itemSlot).asOpt[JsObject]
//
//                    val lootScreenPosToPlunder = subcontainerScreenInfoOpt match {
//                      case Some(itemScreenInfo) =>
//                        Vec((itemScreenInfo \ "x").as[Int], (itemScreenInfo \ "y").as[Int])
//                      case None =>
//                        Vec(0, 0) // Default if no screen info found
//                    }
//
//                    logs = logs :+ Log(f"[DEBUG] Subcontainer found in slot $slot at screen position $lootScreenPosToPlunder.")
//
//                    updatedState = updatedState.copy(
//                      autoloot = updatedState.autoloot.copy(
//                        stateLootPlunder = "open subcontainer",
//                        lootIdToPlunder = (subcontainer \ "itemId").as[Int],
//                        lootScreenPosToPlunder = lootScreenPosToPlunder
//                      )
//                    )
//
//                  case None =>
//                    // Nothing left to loot, set looting to free
//                    logs = logs :+ Log(f"[DEBUG] No more items, food, or subcontainers. Looting complete.")
//
//                    updatedState = updatedState.copy(
//                      autoloot = updatedState.autoloot.copy(
//                        stateLooting = "free",
//                        stateLootPlunder = "free",
//                        lootIdToPlunder = 0,
//                        lootCountToPlunder = 0
//                      ),
//                      cavebot = updatedState.cavebot.copy(
//                        stateHunting = "loot or fight or free",
//                      )
//                    )
//                }
//            }
//        }
//
//      case Some(_) | None =>
//        // If items field is not in the expected format or missing, log an error
//        logs = logs :+ Log(f"[ERROR] Failed to parse items in the container $lastContainerIndex.")
//
//        updatedState = updatedState.copy(
//          autoloot = updatedState.autoloot.copy(
//            stateLooting = "free",
//            stateLootPlunder = "free",
//          ),
//          cavebot = updatedState.cavebot.copy(
//            stateHunting = "loot or fight or free"
//          )
//        )
//    }
//
//    ProcessResult(actions, logs, updatedState)
//  }
//
//  def handleMovingOldCarcass(json: JsValue, settings: UISettings, currentState: ProcessorState): ProcessResult = {
//    var actions: Seq[FakeAction] = Seq.empty
//    var logs: Seq[Log] = Seq  .empty
//    var updatedState = currentState
//    val currentTime = System.currentTimeMillis()
//
//    println(s"carcassToLootImmediately: ${updatedState.autoloot.carcassToLootImmediately}")
//    println(s"carcassToLootAfterFight: ${updatedState.autoloot.carcassToLootAfterFight}")
//    // Calculate time since the last auto-loot action
//    val timeSinceLastAction = currentTime - updatedState.autoloot.lastAutoLootAction
//
//    if (timeSinceLastAction < 400) {
//      logs = logs :+ Log(s"Too soon to move the carcass. Time since last action: $timeSinceLastAction ms.")
//      return ProcessResult(actions, logs, updatedState)
//    }
//
//    // Convert tiles from carcassToLootImmediately and carcassToLootAfterFight to grid format
//    val excludedTilesGrid = (updatedState.autoloot.carcassToLootImmediately ++ updatedState.autoloot.carcassToLootAfterFight)
//      .flatMap { case (tileId, _) => convertGameLocationToGrid(json, tileId) }.toSet
//
//    // Use lastLootedCarcassTile for the carcass to move
//    val carcassToMoveOpt = updatedState.autoloot.lastLootedCarcassTile
//
//    carcassToMoveOpt match {
//      case Some((carcassToMove, _)) => // Extract the tile from the tuple
//        // Extract the item position from mapPanelLoc based on lastLootedCarcassTile
//        val itemPositionToMoveOpt = extractItemPositionFromMapOnScreen(json, carcassToMove)
//
//        itemPositionToMoveOpt match {
//          case Some((itemToMovePosX, itemToMovePosY)) =>
//            // List of possible walkable tiles
//            val possibleTiles = List("7x5", "7x6", "7x7", "8x5", "8x6", "8x7", "9x5", "9x6", "9x7")
//            val areaInfo = (json \ "areaInfo").as[JsObject]
//
//            // Find a random walkable tile, excluding the ones in excludedTilesGrid and the current tile
//            val walkableTileIndexOpt = findRandomWalkableTile(areaInfo, possibleTiles.filterNot(tile =>
//              excludedTilesGrid.contains(tile) || tile == convertGameLocationToGrid(json, carcassToMove).getOrElse("")
//            ))
//
//            walkableTileIndexOpt match {
//              case Some(tileIndex) =>
//                // Extract x and y coordinates for the selected walkable tile
//                val mapPanelLoc = (json \ "screenInfo" \ "mapPanelLoc" \ tileIndex).as[JsObject]
//                val (targetX, targetY) = ((mapPanelLoc \ "x").as[Int], (mapPanelLoc \ "y").as[Int])
//
//                // Move the carcass to the random walkable tile
//                val actionsSeq = moveSingleItem(itemToMovePosX, itemToMovePosY, targetX, targetY)
//                actions = actions :+ FakeAction("useMouse", None, Some(MouseActions(actionsSeq)))
//
//                // Log the movement and update the state
//                logs = logs :+ Log(s"Moving carcass from ($itemToMovePosX, $itemToMovePosY) to ($targetX, $targetY), avoiding excluded tiles and the same tile.")
//
//                updatedState = updatedState.copy(
//                  autoloot = updatedState.autoloot.copy(
//                    stateLooting = "opening carcass",
//                    lastAutoLootAction = currentTime
//                  )
//                )
//
//              case None =>
//                logs = logs :+ Log("No walkable tile found.")
//
//                updatedState = updatedState.copy(
//                  autoloot = updatedState.autoloot.copy(
//                    stateLooting = "free",
//                  ),
//                  cavebot = updatedState.cavebot.copy(
//                    stateHunting = "loot or fight or free"
//                  )
//                )
//            }
//
//          case None =>
//            logs = logs :+ Log(s"Could not find position for carcass tile $carcassToMove.")
//
//            updatedState = updatedState.copy(
//              autoloot = updatedState.autoloot.copy(
//                stateLooting = "free",
//              ),
//              cavebot = updatedState.cavebot.copy(
//                stateHunting = "loot or fight or free"
//              )
//            )
//        }
//
//      case None =>
//        logs = logs :+ Log(s"No lastLootedCarcassTile found in updatedState.")
//        updatedState = updatedState.copy(
//          autoloot = updatedState.autoloot.copy(
//            stateLooting = "free",
//          ),
//          cavebot = updatedState.cavebot.copy(
//            stateHunting = "loot or fight or free"
//          )
//        )
//    }
//
//    ProcessResult(actions, logs, updatedState)
//  }
//
//
//  def convertGameLocationToGrid(json: JsValue, tileId: String): Option[String] = {
//    val mapPanelLoc = (json \ "screenInfo" \ "mapPanelLoc").asOpt[JsObject]
//
//    // Look for the tileId in mapPanelLoc and return the corresponding grid key, otherwise return None
//    mapPanelLoc.flatMap(_.fields.collectFirst {
//      case (gridKey, jsValue) if (jsValue \ "id").asOpt[String].contains(tileId) =>
//        gridKey // Return the grid key if the id matches
//    })
//
//  }
//
//  def handleOpeningCarcass(json: JsValue, settings: UISettings, currentState: ProcessorState): ProcessResult = {
//    var actions: Seq[FakeAction] = Seq.empty
//    var logs: Seq[Log] = Seq.empty
//    var updatedState = currentState
//    val currentTime = System.currentTimeMillis()
//
//    // Calculate time since the last auto-loot action
//    val timeSinceLastAction = currentTime - updatedState.autoloot.lastAutoLootAction
//
//    // Skip looting attempt if less than 0.4 seconds have passed since the last action
//    if (timeSinceLastAction < 400) {
//      logs = logs :+ Log(s"Too soon to open carcass. Time since last action: $timeSinceLastAction ms.")
//      return ProcessResult(actions, logs, updatedState)
//    }
//
//    // Retrieve the carcass tile to loot
//    val carcassTileToLootOpt = updatedState.autoloot.carcassTileToLoot
//
//    carcassTileToLootOpt match {
//      case Some((carcassTileToLoot, _)) =>
//        // Extract the carcass's in-game position
//        val carcassInGamePosition: Vec = Vec(
//          carcassTileToLoot.substring(0, 5).toInt,
//          carcassTileToLoot.substring(5, 10).toInt
//        )
//
//        // Extract the character's current position
//        val presentCharPosition = updatedState.presentCharLocation
//
//        // Calculate the Chebyshev distance between the character and the carcass
//        val chebyshevDistance = math.max(
//          math.abs(carcassInGamePosition.x - presentCharPosition.x),
//          math.abs(carcassInGamePosition.y - presentCharPosition.y)
//        )
//
//        // Check if the character is close enough to open the carcass
//        val isCloseEnough = chebyshevDistance <= 1
//
//        if (isCloseEnough) {
//          // If the character is close enough, proceed with opening the carcass
//          val mapPanelLoc = (json \ "screenInfo" \ "mapPanelLoc").asOpt[JsObject].getOrElse(Json.obj())
//
//          // Find the screen coordinates for the carcass to loot
//          val screenCoordsOpt = mapPanelLoc.fields.collectFirst {
//            case (_, obj: JsObject) if (obj \ "id").asOpt[String].contains(carcassTileToLoot) =>
//              for {
//                x <- (obj \ "x").asOpt[Int]
//                y <- (obj \ "y").asOpt[Int]
//              } yield (x, y)
//          }.flatten
//
//          screenCoordsOpt match {
//            case Some((xPositionScreen, yPositionScreen)) =>
//              // Log the coordinates
//              logs = logs :+ Log(s"[DEBUG] Opening creature carcass at screen position ($xPositionScreen, $yPositionScreen)")
//
//              // Define the sequence of mouse actions to open the carcass
//              val actionsSeq = Seq(
//                MouseAction(xPositionScreen, yPositionScreen, "move"),
//                MouseAction(xPositionScreen, yPositionScreen, "pressCtrl"),
//                MouseAction(xPositionScreen, yPositionScreen, "pressRight"),
//                MouseAction(xPositionScreen, yPositionScreen, "releaseRight"),
//                MouseAction(xPositionScreen, yPositionScreen, "releaseCtrl")
//              )
//
//              // Add the mouse actions to the action list
//              actions = actions :+ FakeAction("useMouse", None, Some(MouseActions(actionsSeq)))
//
//              // Update the state to indicate that the carcass is being opened and record the time of the action
//
//              updatedState = updatedState.copy(
//                autoloot = updatedState.autoloot.copy(
//                  stateLooting = "clicking open button",
//                  lastAutoLootAction = currentTime
//                )
//              )
//
//            case None =>
//              // Log the failure to find the carcass
//              logs = logs :+ Log(s"[ERROR] Could not find screen coordinates for carcass tile $carcassTileToLoot")
//
//              // Reset the state since the carcass could not be opened
//
//              updatedState = updatedState.copy(
//                autoloot = updatedState.autoloot.copy(
//                  stateLooting = "free",
//                ),
//                cavebot = updatedState.cavebot.copy(
//                  stateHunting = "loot or fight or free"
//                )
//              )
//          }
//        } else {
//          // If the character is too far away, generate waypoints and move closer
//          logs = logs :+ Log(s"[DEBUG] Character is too far from the carcass at position ${carcassInGamePosition}. Distance: $chebyshevDistance")
//
//          // Generate subwaypoints to move closer to the carcass
//          updatedState = generateSubwaypointsToGamePosition(carcassInGamePosition, updatedState, json)
//
//          if (updatedState.subWaypoints.nonEmpty) {
//            val nextWaypoint = updatedState.subWaypoints.head
//            val direction = calculateDirection(presentCharPosition, nextWaypoint, updatedState.lastDirection)
//            logs = logs :+ Log(f"[DEBUG] Calculated Next Direction: $direction")
//
//            updatedState = updatedState.copy(lastDirection = direction)
//
//            direction.foreach { dir =>
//              // Add the movement action (key press) to the action sequence
//              actions = actions :+ FakeAction("pressKey", None, Some(PushTheButton(dir)))
//              logs = logs :+ Log(s"Moving closer to the carcass in direction: $dir")
//            }
//
//            // Remove the used waypoint from the state
//            updatedState = updatedState.copy(subWaypoints = updatedState.subWaypoints.tail)
//          } else {
//            // If no waypoints are found, reset the state
//            logs = logs :+ Log(s"[ERROR] No waypoints found to reach the carcass.")
//            updatedState = updatedState.copy(
//              autoloot = updatedState.autoloot.copy(
//                stateLooting = "free",
//              ),
//              cavebot = updatedState.cavebot.copy(
//                stateHunting = "loot or fight or free"
//              )
//            )
//          }
//        }
//
//      case None =>
//        logs = logs :+ Log(s"[ERROR] No carcassTileToLoot found in updatedState.")
//        updatedState = updatedState.copy(
//          autoloot = updatedState.autoloot.copy(
//            stateLooting = "free",
//          ),
//          cavebot = updatedState.cavebot.copy(
//            stateHunting = "loot or fight or free"
//          )
//        )
//    }
//
//    ProcessResult(actions, logs, updatedState)
//  }
//
//
//  def handleClickingOpen(json: JsValue, settings: UISettings, currentState: ProcessorState): ProcessResult = {
//    var actions: Seq[FakeAction] = Seq.empty
//    var logs: Seq[Log] = Seq.empty
//    var updatedState = currentState
//    val currentTime = System.currentTimeMillis()
//
//    // Calculate how much time has passed since the last auto-loot action was initiated
//    val timeSinceLastAction = currentTime - updatedState.autoloot.lastAutoLootAction
//    println((json \ "screenInfo" \ "extraWindowLoc").asOpt[JsObject])
//    // If more than 1 second has passed since the last action, cancel looting
//    if (timeSinceLastAction > 1000) {
//      logs = logs :+ Log(s"Failed to open looting window after 1 second. Cancelling looting.")
//
//      updatedState = updatedState.copy(
//        autoloot = updatedState.autoloot.copy(
//          stateLooting = "free",
//        ),
//        cavebot = updatedState.cavebot.copy(
//          stateHunting = "loot or fight or free"
//        )
//      )
//    } else {
//      // Attempt to retrieve the position of the "Open" button from the extra window
//      val openPosOpt = (json \ "screenInfo" \ "extraWindowLoc").asOpt[JsObject].flatMap { extraWindowLoc =>
//        (extraWindowLoc \ "Open").asOpt[JsObject].flatMap { open =>
//          for {
//            posX <- (open \ "posX").asOpt[Int]
//            posY <- (open \ "posY").asOpt[Int]
//          } yield (posX, posY)
//        }
//      }
//
//      // Handle the result of the attempt to click on the "Open" button
//      openPosOpt match {
//        case Some((xPosWindowOpen, yPosWindowOpen)) =>
//          // Clicking on the "Open" button
//          logs = logs :+ Log(s"Clicking on 'Open' button at coordinates: x=$xPosWindowOpen, y=$yPosWindowOpen")
//          val actionsSeq = Seq(
//            MouseAction(xPosWindowOpen, yPosWindowOpen, "move"),
//            MouseAction(xPosWindowOpen, yPosWindowOpen, "pressLeft"),
//            MouseAction(xPosWindowOpen, yPosWindowOpen, "releaseLeft")
//          )
//          actions = actions :+ FakeAction("useMouse", None, Some(MouseActions(actionsSeq)))
//
//          updatedState = updatedState.copy(
//            autoloot = updatedState.autoloot.copy(
//              stateLooting = "loot plunder"
//            )
//          )
//
//        case None =>
//          logs = logs :+ Log("No Open button detected. Still waiting for opening carcass")
////          updatedState = updatedState.copy(stateLooting = "free", stateHunting = "loot or fight or free")
//      }
//    }
//
//    // Return the ProcessResult with updated actions, logs, and state
//    ProcessResult(actions, logs, updatedState)
//  }
//
//
//
//  def extractItemPositionFromMapOnScreen(json: JsValue, carcassTileToLoot: String): Option[(Int, Int)] = {
//    // Extract the key from the carcassTileToLoot (in this case, "345323456507" format)
//    val posX = carcassTileToLoot.substring(0, 5)
//    val posY = carcassTileToLoot.substring(5, 10)
//    val posZ = carcassTileToLoot.substring(10, 12)
//
//    // Search for the position in the mapPanelLoc
//    val mapPanelLoc = (json \ "screenInfo" \ "mapPanelLoc").asOpt[JsObject]
//    val tileKeyOpt = mapPanelLoc.flatMap(_.fields.collectFirst {
//      case (tileIndex, jsValue) if (jsValue \ "id").asOpt[String].contains(carcassTileToLoot) =>
//        (jsValue \ "x").asOpt[Int].getOrElse(0) -> (jsValue \ "y").asOpt[Int].getOrElse(0)
//    })
//
//    tileKeyOpt match {
//      case Some((x, y)) =>
//        println(s"Found coordinates for $carcassTileToLoot: x=$x, y=$y")
//        Some((x, y))
//      case None =>
//        println(s"No coordinates found for $carcassTileToLoot")
//        None
//    }
//  }
//
//
//  def handleLootOrMoveCarcass(json: JsValue, settings: UISettings, currentState: ProcessorState): ProcessResult = {
//    var actions: Seq[FakeAction] = Seq.empty
//    var logs: Seq[Log] = Seq.empty
//    var updatedState = currentState
//
//    println(s"carcassToLootImmediately: ${updatedState.autoloot.carcassToLootImmediately}.")
//    println(s"carcassToLootAfterFight: ${updatedState.autoloot.carcassToLootAfterFight}.")
//
//    // Check if there is a carcass tile to loot
//    updatedState.autoloot.carcassTileToLoot match {
//      case Some((carcassTile, timeOfDeath)) =>
//        updatedState.autoloot.lastLootedCarcassTile match {
//          case Some((lastCarcassTile, lastTimeOfDeath)) =>
//            // If the tiles are the same, we need to move the carcass
//            if (carcassTile == lastCarcassTile) {
//              logs = logs :+ Log(f"Same carcass tile as last looted: $carcassTile. Moving carcass.")
//
//              updatedState = updatedState.copy(
//                autoloot = updatedState.autoloot.copy(
//                  stateLooting = "moving carcass"
//                )
//              )
//
//            } else {
//              // Different carcass, set state to open new carcass
//              logs = logs :+ Log(f"New carcass tile detected. Opening new carcass: $carcassTile.")
//
//              updatedState = updatedState.copy(
//                autoloot = updatedState.autoloot.copy(
//                  stateLooting = "opening carcass",
//                  lastLootedCarcassTile = Some((carcassTile, timeOfDeath))
//                )
//              )
//            }
//
//          case None =>
//            // No previous carcass, so loot this one
//            logs = logs :+ Log(f"No previously looted carcass. Opening carcass: $carcassTile.")
//
//            updatedState = updatedState.copy(
//              autoloot = updatedState.autoloot.copy(
//                stateLooting = "opening carcass",
//                lastLootedCarcassTile = Some((carcassTile, timeOfDeath))
//              )
//            )
//        }
//
//      case None =>
//        logs = logs :+ Log(f"No carcass tile to loot.")
//
//        updatedState = updatedState.copy(
//          autoloot = updatedState.autoloot.copy(
//            stateLooting = "free"
//          )
//        )
//    }
//
//    // Return the ProcessResult with the updated actions, logs, and state
//    ProcessResult(actions, logs, updatedState)
//  }
//
//
//  def handleLootOrFightOrFreeState(json: JsValue, settings: UISettings, currentState: ProcessorState): ProcessResult = {
//    var actions: Seq[FakeAction] = Seq.empty
//    var logs: Seq[Log] = Seq.empty
//    var updatedState = currentState
//
//    // Retrieve the battleShootableCreaturesList from the game state to determine if it is safe to loot
//    val battleShootableCreaturesList = getBattleShootableCreaturesList(json)
//    println(s"battleShootableCreaturesList: $battleShootableCreaturesList")
//
//    // Check if carcassToLootImmediately is non-empty and proceed with immediate looting
//    if (updatedState.autoloot.carcassToLootImmediately.nonEmpty) {
//
//      // Sort carcass by character proximity if there are multiple carcasses
//      if (updatedState.autoloot.carcassToLootImmediately.length > 1) {
//
//        updatedState = updatedState.copy(
//          autoloot = updatedState.autoloot.copy(
//            carcassToLootImmediately = sortTileListByCharacterProximity(json, updatedState.autoloot.carcassToLootImmediately)
//          )
//        )
//      }
//
//      // Get the unique carcass and time from the sorted list
//      val (carcassToLoot, timeOfDeath) = updatedState.autoloot.carcassToLootImmediately.head
//
//      logs = logs :+ Log(s"Looting immediately. Carcass to loot: $carcassToLoot, Time: $timeOfDeath")
//
//      updatedState = updatedState.copy(
//        autoloot = updatedState.autoloot.copy(
//          carcassTileToLoot = Some((carcassToLoot, timeOfDeath)),
//          carcassToLootImmediately = updatedState.autoloot.carcassToLootImmediately.tail // Remove the looted carcass
//        ),
//        cavebot = updatedState.cavebot.copy(
//          stateHunting = "looting in progress",
//        )
//      )
//    }
//
//    // Check if carcassToLootAfterFight is non-empty and it is safe to loot
//    else if (updatedState.autoloot.carcassToLootAfterFight.nonEmpty && battleShootableCreaturesList.isEmpty) {
//
//      // Sort carcass by character proximity if there are multiple carcasses
//      if (updatedState.autoloot.carcassToLootAfterFight.length > 1) {
//        updatedState = updatedState.copy(
//
//        )
//
//        updatedState = updatedState.copy(
//          autoloot = updatedState.autoloot.copy(
//            carcassToLootAfterFight = sortTileListByCharacterProximity(json, updatedState.autoloot.carcassToLootAfterFight)
//          )
//        )
//      }
//
//      // Get the unique carcass and time from the sorted list
//      val (carcassToLoot, timeOfDeath) = updatedState.autoloot.carcassToLootAfterFight.head
//
//      logs = logs :+ Log(s"Looting after fight. Carcass to loot: $carcassToLoot, Time: $timeOfDeath")
//
//      updatedState = updatedState.copy(
//        autoloot = updatedState.autoloot.copy(
//          carcassTileToLoot = Some((carcassToLoot, timeOfDeath)),
//          carcassToLootAfterFight = updatedState.autoloot.carcassToLootAfterFight.tail // Remove the looted carcass
//        ),
//        cavebot = updatedState.cavebot.copy(
//          stateHunting = "looting in progress",
//        )
//      )
//    }
//
//    // If neither looting list is non-empty or it's not safe to loot, set state to "free"
//    else {
//      logs = logs :+ Log("No carcass to loot or not safe to loot. Setting state to free.")
//
//      updatedState = updatedState.copy(
//        cavebot = updatedState.cavebot.copy(
//          stateHunting = "free"
//        )
//      )
//
//    }
//
//    // Return the ProcessResult with updated actions, logs, and state
//    ProcessResult(actions, logs, updatedState)
//  }
//
//
//
//
//  def sortTileListByCharacterProximity(json: JsValue, tileList: List[(String, Long)]): List[(String, Long)] = {
//    // Extract character position from JSON
//    val (charX, charY, charZ) = (json \ "characterInfo").asOpt[JsObject].map { characterInfo =>
//      val x = (characterInfo \ "PositionX").asOpt[Int].getOrElse(0)
//      val y = (characterInfo \ "PositionY").asOpt[Int].getOrElse(0)
//      val z = (characterInfo \ "PositionZ").asOpt[Int].getOrElse(0)
//      (x, y, z)
//    }.getOrElse((0, 0, 0)) // Default to (0, 0, 0) if character position is not found
//
//    // Helper function to extract posX, posY, posZ from tile string
//    def extractTilePosition(tile: String): (Int, Int, Int) = {
//      val posX = tile.substring(0, 5).toInt
//      val posY = tile.substring(5, 10).toInt
//      val posZ = tile.substring(10, 12).toInt
//      (posX, posY, posZ)
//    }
//
//    // Helper function to calculate the distance between two 3D points
//    def calculateDistance(x1: Int, y1: Int, z1: Int, x2: Int, y2: Int, z2: Int): Double = {
//      math.sqrt(math.pow(x2 - x1, 2) + math.pow(y2 - y1, 2) + math.pow(z2 - z1, 2))
//    }
//
//    // Sort the tile tuples by their distance to the character's position
//    tileList.sortBy { case (tile, _) =>
//      val (tileX, tileY, tileZ) = extractTilePosition(tile)
//      calculateDistance(tileX, tileY, tileZ, charX, charY, charZ)
//    }
//  }
//
//
//
//  // check if updatedState.carsassToLoot has elements create match case else create different case
//  def getBattleShootableCreaturesList(json: JsValue): List[String] = {
//    // Extract the battleInfo from the JSON
//    val battleInfo = (json \ "battleInfo").asOpt[Map[String, JsValue]].getOrElse(Map.empty)
//
//    // Filter for creatures that are monsters and shootable
//    val battleShootableCreaturesList = battleInfo.flatMap { case (_, data) =>
//      val isMonster = (data \ "IsMonster").asOpt[Boolean].getOrElse(false)
//      val isShootable = (data \ "IsShootable").asOpt[Boolean].getOrElse(false)
//
//      if (isMonster && isShootable) {
//        Some((data \ "Name").as[String]) // Add the creature name to the list
//      } else {
//        None
//      }
//    }.toList
//
//    // Return the filtered list (could be empty if no creatures match)
//    battleShootableCreaturesList
//  }
//
//
//
//
//
//  def generatePositionKey(x: Int, y: Int, z: Int): String = f"$x$y${z}%02d"
//
//  // Updated function to check for fresh blood and return the tile index if blood is found
//  def checkForBloodAndContainerAndGetIndex(json: JsValue, positionKey: String): Option[String] = {
//    (json \ "areaInfo" \ "tiles" \ positionKey).asOpt[JsObject] match {
//      case Some(tileInfo) =>
//        val items = (tileInfo \ "items").as[JsObject]
//        val itemsList = items.values.toList.reverse // reverse to access the last items first
//
//        // Check if there's any fresh blood in itemsList (ID 2886 or 2887)
//        val hasBlood = itemsList.exists { item =>
//          val id = (item \ "id").as[Int]
//          id == 2886 || id == 2887
//        }
//
//        if (hasBlood) {
//          println(s"Blood found at positionKey: $positionKey")
//          Some(positionKey) // Return the positionKey if blood is found
//        } else {
//          println("No blood found.")
//          None
//        }
//
//      case None =>
//        println(s"No tile information found for positionKey: $positionKey")
//        None
//    }
//  }
//
//
//
//  def detectDeadCreatures(json: JsValue, settings: UISettings, currentState: ProcessorState): ProcessResult = {
//    var actions: Seq[FakeAction] = Seq.empty
//    var logs: Seq[Log] = Seq.empty
//    var updatedState = currentState
//    val currentTime = System.currentTimeMillis()
//
//    // Extract the list of killed creatures from the JSON
//    val killedCreatures = (json \ "lastKilledCreatures").asOpt[JsObject].getOrElse(Json.obj())
//
//    // Iterate through each killed creature
//    killedCreatures.fields.foreach { case (creatureId, creatureInfo) =>
//      val creatureName = (creatureInfo \ "Name").asOpt[String].getOrElse("")
//      val creaturePosX = (creatureInfo \ "LastPositionX").asOpt[Int].getOrElse(0)
//      val creaturePosY = (creatureInfo \ "LastPositionY").asOpt[Int].getOrElse(0)
//      val creaturePosZ = (creatureInfo \ "LastPositionZ").asOpt[Int].getOrElse(0)
//      val isDead = (creatureInfo \ "IsDead").asOpt[Boolean].getOrElse(false)
//
//      // If the creature is dead, proceed with settings extraction and looting logic
//      if (isDead) {
//        // Generate the main position key (or index) based on the creature's last position
//        val mainIndex = generatePositionKey(creaturePosX, creaturePosY, creaturePosZ)
//
//        // Extract the creature settings based on the creature's name
//        val creatureSettingsOpt = settings.autoTargetSettings.creatureList
//          .find(creatureStr => parseCreature(creatureStr).name.equalsIgnoreCase(creatureName))
//          .map(parseCreature)
//
//        creatureSettingsOpt match {
//          case Some(creatureSettings) =>
//            // Create the tuple for the carcass with the current time
//            val carcassEntry = (mainIndex, currentTime)
//
//            if (creatureSettings.lootMonsterImmediately) {
//              // Add the carcass index and time to carcassToLootImmediately
//              logs = logs :+ Log(s"$creatureName will be looted immediately at position ($creaturePosX, $creaturePosY, $creaturePosZ).")
//
//              updatedState = updatedState.copy(
//                autoloot = updatedState.autoloot.copy(
//                  carcassToLootImmediately = updatedState.autoloot.carcassToLootImmediately :+ carcassEntry
//                )
//              )
//
//            } else if (creatureSettings.lootMonsterAfterFight) {
//              // Add the carcass index and time to carcassToLootAfterFight
//              logs = logs :+ Log(s"$creatureName will be looted after the fight at position ($creaturePosX, $creaturePosY, $creaturePosZ).")
//
//              updatedState = updatedState.copy(
//                autoloot = updatedState.autoloot.copy(
//                  carcassToLootAfterFight = updatedState.autoloot.carcassToLootAfterFight :+ carcassEntry
//                )
//              )
//
//            } else {
//              // No looting settings for this creature
//              logs = logs :+ Log(s"$creatureName has no looting settings.")
//            }
//
//          case None =>
//            // No settings found for this creature
//            logs = logs :+ Log(s"No creature settings found for $creatureName.")
//        }
//      }
//    }
//
//    // Return the ProcessResult with the updated actions, logs, and state
//    ProcessResult(actions, logs, updatedState)
//  }
//
//
//
//  def moveSingleItem(xItemPosition: Int, yItemPositon: Int, xDestPos: Int, yDestPos: Int): Seq[MouseAction] =
//    Seq(
//      MouseAction(xItemPosition, yItemPositon, "move"),
//      MouseAction(xItemPosition, yItemPositon, "pressLeft"),
//      MouseAction(xDestPos, yDestPos, "move"),
//      MouseAction(xDestPos, yDestPos, "releaseLeft")
//    )
//
//  def moveMultipleItems(xItemPosition: Int, yItemPositon: Int, xDestPos: Int, yDestPos: Int): Seq[MouseAction] = {
//    Seq(
//      MouseAction(xItemPosition, yItemPositon, "move"),
//      MouseAction(xItemPosition, yItemPositon, "pressCtrl"),
//      MouseAction(xItemPosition, yItemPositon, "pressLeft"),
//      MouseAction(xDestPos, yDestPos, "move"),
//      MouseAction(xDestPos, yDestPos, "releaseLeft"),
//      MouseAction(xDestPos, yDestPos, "releaseCtrl")
//    )
//  }
//
//    // Function to find a random walkable tile
//
//  // Assume this function is corrected to return List[String]
//  def findRandomWalkableTile(areaInfo: JsObject, possibleTiles: List[String]): Option[String] = {
//    println("Inside findRandomWalkableTile")
//    val allMovementEnablerIds: List[Int] = StaticGameInfo.LevelMovementEnablers.AllIds
//    // Extract the tiles information from the area info JSON object
//    val tilesInfo = (areaInfo \ "tiles").as[JsObject]
//
//    // Collect all indices of walkable tiles that do not have blocking items or carcasses
//    val allWalkableIndices = tilesInfo.fields.collect {
//      case (tileId, tileObj: JsObject) if possibleTiles.contains((tileObj \ "index").asOpt[String].getOrElse("")) =>
//        val tileIsWalkable = (tileObj \ "isWalkable").asOpt[Boolean].getOrElse(false)
//        val tileItems = (tileObj \ "items").asOpt[JsObject].getOrElse(Json.obj())
//
//        // Check if the tile contains any blocking items (i.e., carcasses or movement blockers)
//        val hasBlockingItem = tileItems.fields.exists { case (_, itemObj) =>
//          allMovementEnablerIds.contains((itemObj \ "id").asOpt[Int].getOrElse(0))
//        }
//
//        // Only consider tiles that are walkable and do not contain blocking items
//        if (tileIsWalkable && !hasBlockingItem) {
//          (tileObj \ "index").as[String]
//        } else {
//          // Skip this tile if it has blocking items or is not walkable
//          ""
//        }
//    }.filterNot(_.isEmpty).toList // Filter out any empty results
//
//    // Shuffle the list of all walkable indices and return one at random
//    Random.shuffle(allWalkableIndices).headOption
//  }
//
//
//  def findRandomWalkableTileOld(areaInfo: JsObject, possibleTiles: List[String]): Option[String] = {
//    println("Inside findRandomWalkableTile")
//
//    // Extract the tiles information from the area info JSON object
//    val tilesInfo = (areaInfo \ "tiles").as[JsObject]
//
//    // Collect all indices of walkable tiles
//    val allWalkableIndices = tilesInfo.fields.collect {
//      case (tileId, jsValue) if possibleTiles.contains((jsValue \ "index").asOpt[String].getOrElse("")) &&
//        (jsValue \ "isWalkable").asOpt[Boolean].getOrElse(false) =>
//        (jsValue \ "index").as[String]
//    }.toList
//
//    // Shuffle the list of all walkable indices and return one at random
//    Random.shuffle(allWalkableIndices).headOption
//  }
//
//}
//
//
