package processing

import mouse.FakeAction
import play.api.libs.json.{JsError, JsObject, JsValue}
import userUI.SettingsUtils.UISettings
import play.api.libs.json._
import processing.Process.extractOkButtonPosition
import utils.consoleColorPrint.{ANSI_GREEN, ANSI_RED, printInColor}

import scala.util.Random

object AutoLoot {
  def computeAutoLootActions(json: JsValue, settings: UISettings, currentState: ProcessorState): ((Seq[FakeAction], Seq[Log]), ProcessorState) = {
    val startTime = System.nanoTime()
    var actions: Seq[FakeAction] = Seq.empty
    var logs: Seq[Log] = Seq.empty
    var updatedState = currentState // Initialize updatedState
//    printInColor(ANSI_RED, f"[DEBUG] autoLoot: ${settings.autoTargetSettings.enabled}, autoLoot: ${settings.autoLootSettings.enabled}")
    if (settings.autoTargetSettings.enabled && settings.autoLootSettings.enabled) {

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
            actions = actions :+ FakeAction("useMouse", None, Some(MouseActions(actionsSeq)))

            // Update the extraWidowLootStatus with the current time to mark this execution
            updatedState = updatedState.copy(lastExtraWindowLoot = updatedState.currentTime)
          } else {
            printInColor(ANSI_RED, f"[DEBUG] Closing object movement window. Not enough time has passed since the last execution: ${updatedState.lastExtraWindowLoot}ms ago.")
          }

        case None => // Do nothing
      }

//      val screenInfoExtraWindowOpt: Option[JsObject] = (json \ "screenInfo" \ "extraWindowLoc" ).asOpt[JsObject]


      (json \ "attackInfo" \ "Id").asOpt[Int] match {
        case Some(attackedCreatureTarget) =>
          updatedState = updatedState.copy(stateHunting = "attacking")
        case None =>
          if (updatedState.lastTargetName == "") {
            // println("No target specified, no looting needed.")
          } else {

            if (updatedState.stateHunting == "looting") {
              printInColor(ANSI_RED, f"[DEBUG] Looting process started")

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
                        } else {
                          printInColor(ANSI_RED, f"[DEBUG] Retrying - Items field is 'empty'. No items to process (Attempt ${updatedState.lootingRestryStatus + 1})")
                          updatedState = updatedState.copy(lootingRestryStatus = updatedState.lootingRestryStatus + 1)
                        }

                      case None =>

                        if (updatedState.lootingRestryStatus >= updatedState.retryAttempts) {
                          printInColor(ANSI_RED, f"[DEBUG] No items field present or it is not in expected format")
                          updatedState = updatedState.copy(stateHunting = "free", lootingRestryStatus = 0)
                        } else {
                          printInColor(ANSI_RED, f"[DEBUG] Retrying - no items field present or it is not in expected format (Attempt ${updatedState.lootingRestryStatus + 1})")
                          updatedState = updatedState.copy(lootingRestryStatus = updatedState.lootingRestryStatus + 1)
                        }

                      case Some(other) =>

                        if (updatedState.lootingRestryStatus >= updatedState.retryAttempts) {
                          printInColor(ANSI_RED, f"[DEBUG] Unexpected string in items field: $other")
                          updatedState = updatedState.copy(stateHunting = "free", lootingRestryStatus = 0)
                        } else {
                          printInColor(ANSI_RED, f"[DEBUG] Retrying - unexpected string in items field (Attempt ${updatedState.lootingRestryStatus + 1})")
                          updatedState = updatedState.copy(lootingRestryStatus = updatedState.lootingRestryStatus + 1)
                        }

                    }

                  case JsSuccess(itemsInContainer, _) =>



                    printInColor(ANSI_RED, f"[DEBUG] Considering looting from from $lastContainerIndex (statics: ${updatedState.staticContainersList})")

                    // Check if the last container is not already in the updatedState.staticContainersList
                    if (!updatedState.staticContainersList.contains(lastContainerIndex)) {
                      printInColor(ANSI_RED, f"[DEBUG] Looting from $lastContainerIndex")
                      val itemsInContainerInitial = (lastContainer \ "items").as[JsObject]
                      //                    printInColor(ANSI_RED, f"[DEBUG] Items in container detected: $itemsInContainerInitial")

                      val itemsInContainer = JsObject(itemsInContainerInitial.fields.filterNot { case (_, itemInfo) =>
                        val itemId = (itemInfo \ "itemId").as[Int]
                        updatedState.alreadyLootedIds.contains(itemId)
                      })
                      //                    printInColor(ANSI_RED, f"[DEBUG] Items already looted: ${updatedState.alreadyLootedIds}")
                      //                    printInColor(ANSI_RED, f"[DEBUG] Filtered items excluding already looted: $itemsInContainer")


                      // Prepare a set of item IDs from lootList
                      val lootItems = settings.autoLootSettings.lootList.map(_.trim.split(",\\s*")(0).toInt).toSet
                      printInColor(ANSI_RED, f"[DEBUG] Available Loot Items: ${lootItems.mkString(", ")}")


                      //                    // Assuming `json` is your JSON object parsed into a JsValue
                      //                    val container = (json \ "containersInfo" \ lastContainerIndex \ "items").as[JsObject]

                      // Extract all items from the last container and find first matching loot item
                      val foundItemOpt = itemsInContainer.fields.reverse.collectFirst {
                        case (slot, itemInfo) if lootItems((itemInfo \ "itemId").as[Int]) =>
                          (slot, itemInfo.as[JsObject])
                      }

                      foundItemOpt match {
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
                case e: Exception =>
                  // Handle any other unexpected exceptions
                  printInColor(ANSI_RED, f"[ERROR] Unexpected error: ${e.getMessage}")
                  updatedState = updatedState.copy(stateHunting = "free", lootingStatus = 0)
              }


            } else if (updatedState.stateHunting == "opening window") {

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
                  } else {
                    printInColor(ANSI_RED, f"[DEBUG] No Open position available or extraWindowLoc is null. Retrying... (Attempt ${updatedState.extraWidowLootStatus + 1})")
                    updatedState = updatedState.copy(extraWidowLootStatus = updatedState.extraWidowLootStatus + 1) // increment retryStatus
                  }
              }

            } else if (updatedState.stateHunting == "attacking") {

              if (updatedState.lastTargetName.nonEmpty && !updatedState.monstersListToLoot.contains(updatedState.lastTargetName)) {
                printInColor(ANSI_RED, f"[DEBUG] Reseting status to free, monsters are not in list [${updatedState.monstersListToLoot}]")
                updatedState = updatedState.copy(stateHunting = "free", subWaypoints = List.empty)
              } else {
                // Retrieve position coordinates
                val xPositionGame = updatedState.lastTargetPos._1
                val yPositionGame = updatedState.lastTargetPos._2
                val zPositionGame = updatedState.lastTargetPos._3

                printInColor(ANSI_RED, f"[DEBUG] Creature (${updatedState.lastTargetName}) in game position $xPositionGame, $yPositionGame, $zPositionGame")

                // Function to generate position keys with padding on z-coordinate
                def generatePositionKey(x: Int, y: Int, z: Int): String = f"$x$y${z}%02d"

                def checkForBloodAndContainerAndGetIndexOld(positionKey: String): Option[String] = {
                  (json \ "areaInfo" \ "tiles" \ positionKey).asOpt[JsObject] match {
                    case Some(tileInfo) =>
                      val items = (tileInfo \ "items").as[JsObject]
                      val itemsList = items.values.toList.reverse // reverse to access the last items first

                      val hasBlood = itemsList.exists { item =>
                        val id = (item \ "id").as[Int]
                        id == 2886 || id == 2887
                      }

                      val isLastContainer = (itemsList.head \ "isContainer").as[Boolean]

                      if (hasBlood && isLastContainer) (tileInfo \ "index").asOpt[String]
                      else None

                    case None => None // No tile information found
                  }
                }

                // Function to check for blood and container, and return the index if conditions are met
                def checkForBloodAndContainerAndGetIndex_GoodButCanBeBetter(positionKey: String): Option[String] = {
                  (json \ "areaInfo" \ "tiles" \ positionKey).asOpt[JsObject] match {
                    case Some(tileInfo) =>
                      val topThingId = (tileInfo \ "topThingId").as[Int]

                      // Check if topThingId indicates the presence of blood or a container
                      val hasBlood = topThingId == 2886 || topThingId == 2887
                      val isContainer = (tileInfo \ "isContainer").as[Boolean]

                      if (hasBlood && isContainer) (tileInfo \ "index").asOpt[String]
                      else None

                    case None => None // No tile information found
                  }
                }



                // Function to check for blood and container, and return the index if conditions are met
                def checkForBloodAndContainerAndGetIndex(positionKey: String): Option[String] = {
                  (json \ "areaInfo" \ "tiles" \ positionKey).asOpt[JsObject] match {
                    case Some(tileInfo) =>
                      val topThingId = (tileInfo \ "topThingId").as[Int]
                      val items = (tileInfo \ "items").as[JsObject]
                      val itemsList = items.values.toList.reverse // reverse to access the last items first

                      // Check if there's any blood in itemsList
                      val hasBlood = itemsList.exists { item =>
                        val id = (item \ "id").as[Int]
                        id == 2886 || id == 2887
                      }

                      // Find the item in items that corresponds to topThingId
                      val isContainer = items.keys.flatMap { key =>
                        val item = (items \ key).asOpt[JsObject]
                        item.filter(i => (i \ "id").as[Int] == topThingId).map(i => (i \ "isContainer").as[Boolean])
                      }.headOption.getOrElse(false)

                      if (hasBlood && isContainer) (tileInfo \ "index").asOpt[String]
                      else None

                    case None => None // No tile information found
                  }
                }

                // Check the main position first
                val mainPositionKey = generatePositionKey(xPositionGame, yPositionGame, zPositionGame)
                val mainIndexOpt = checkForBloodAndContainerAndGetIndex(mainPositionKey)

                mainIndexOpt match {
                  case Some(index) =>
                    // Fetch screen coordinates from JSON using the index
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
                      MouseAction(xPositionScreen, yPositionScreen, "pressLeft"),
                      MouseAction(xPositionScreen, yPositionScreen, "releaseLeft"),
                      MouseAction(xPositionScreen, yPositionScreen, "releaseCtrl")
                    )

                    actions = actions :+ FakeAction("useMouse", None, Some(MouseActions(actionsSeq)))
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
                      checkForBloodAndContainerAndGetIndex(key)
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
                    }
                }
              }
            }

          }
      }
    }
    val endTime = System.nanoTime()
    val duration = (endTime - startTime) / 1e9d
    printInColor(ANSI_GREEN, f"[INFO] Processing computeAutoLootActions took $duration%.6f seconds")

    ((actions, logs), updatedState)
  }


  def moveSingleItem(xItemPosition: Int, yItemPositon: Int, xDestPos: Int, yDestPos: Int): Seq[MouseAction] =
    Seq(
      MouseAction(xItemPosition, yItemPositon, "move"),
      MouseAction(xItemPosition, yItemPositon, "pressLeft"),
      MouseAction(xDestPos, yDestPos, "move"),
      MouseAction(xDestPos, yDestPos, "releaseLeft")
    )

  def moveMultipleItems(xItemPosition: Int, yItemPositon: Int, xDestPos: Int, yDestPos: Int): Seq[MouseAction] =
    Seq(
      MouseAction(xItemPosition, yItemPositon, "move"),
      MouseAction(xItemPosition, yItemPositon, "pressCtrl"),
      MouseAction(xItemPosition, yItemPositon, "pressLeft"),
      MouseAction(xDestPos, yDestPos, "move"),
      MouseAction(xDestPos, yDestPos, "releaseLeft"),
      MouseAction(xDestPos, yDestPos, "releaseCtrl")
    )

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


