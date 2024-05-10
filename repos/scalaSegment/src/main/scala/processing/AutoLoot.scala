package processing

import mouse.FakeAction
import play.api.libs.json.{JsError, JsObject, JsValue}
import userUI.SettingsUtils.UISettings
import play.api.libs.json._
import utils.consoleColorPrint.{ANSI_GREEN, ANSI_RED, printInColor}

object AutoLoot {
  def computeAutoLootActions(json: JsValue, settings: UISettings, currentState: ProcessorState): ((Seq[FakeAction], Seq[Log]), ProcessorState) = {
    val startTime = System.nanoTime()
    var actions: Seq[FakeAction] = Seq.empty
    var logs: Seq[Log] = Seq.empty
    var updatedState = currentState // Initialize updatedState
//    printInColor(ANSI_RED, f"[DEBUG] autoLoot: ${settings.autoTargetSettings.enabled}, autoLoot: ${settings.autoLootSettings.enabled}")
    if (settings.autoTargetSettings.enabled && settings.autoLootSettings.enabled) {


      // Assuming 'json' is your input JsValue
      val containersInfoOpt: Option[JsObject] = (json \ "containersInfo").asOpt[JsObject]

      (json \ "attackInfo" \ "Id").asOpt[Int] match {
        case Some(attackedCreatureTarget) =>
          updatedState = updatedState.copy(stateHunting = "attacking")
        case None =>
          if (updatedState.lastTargetName == "") {
            // println("No target specified, no looting needed.")
          } else {

            if (updatedState.stateHunting == "looting") {
              printInColor(ANSI_RED, f"[DEBUG] Looting process started")
              val containersInfo = (json \ "containersInfo").as[JsObject]
              val screenInfo = (json \ "screenInfo").as[JsObject]
              // Get the last container from containersInfo
              val lastContainerIndex = containersInfo.keys.maxBy(_.replace("container", "").toInt)
              val lastContainer = (containersInfo \ lastContainerIndex).as[JsObject]
//              println(s"gate4 - Last Container: $lastContainer")  // Added debug print for lastContainer



              // Check if the items field is a string indicating "empty" or contains objects
              (lastContainer \ "items").validate[JsObject] match {
                case JsError(_) =>
                  (lastContainer \ "items").asOpt[String] match {
                    case Some("empty") =>

                      if (updatedState.retryStatus >= updatedState.retryAttempts) {
                        printInColor(ANSI_RED, f"[DEBUG] Items field is 'empty'. No items to process.")
                        updatedState = updatedState.copy(stateHunting = "free")
                      } else {
                        printInColor(ANSI_RED, f"[DEBUG] Retrying - Items field is 'empty'. No items to process (Attempt ${updatedState.retryStatus + 1})")
                        updatedState = updatedState.copy(retryStatus = updatedState.retryStatus + 1)
                      }

                    case None =>

                      if (updatedState.retryStatus >= updatedState.retryAttempts) {
                        printInColor(ANSI_RED, f"[DEBUG] No items field present or it is not in expected format")
                        updatedState = updatedState.copy(stateHunting = "free")
                      } else {
                        printInColor(ANSI_RED, f"[DEBUG] Retrying - no items field present or it is not in expected format (Attempt ${updatedState.retryStatus + 1})")
                        updatedState = updatedState.copy(retryStatus = updatedState.retryStatus + 1)
                      }

                    case Some(other) =>

                      if (updatedState.retryStatus >= updatedState.retryAttempts) {
                        printInColor(ANSI_RED, f"[DEBUG] Unexpected string in items field: $other")
                        updatedState = updatedState.copy(stateHunting = "free")
                      } else {
                        printInColor(ANSI_RED, f"[DEBUG] Retrying - unexpected string in items field (Attempt ${updatedState.retryStatus + 1})")
                        updatedState = updatedState.copy(retryStatus = updatedState.retryStatus + 1)
                      }

                  }

                case JsSuccess(itemsInContainer, _) =>

                  val itemsInContainerInitial = (lastContainer \ "items").as[JsObject]
                  printInColor(ANSI_RED, f"[DEBUG] Items in container detected: $itemsInContainerInitial")

                  val itemsInContainer = JsObject(itemsInContainerInitial.fields.filterNot { case (_, itemInfo) =>
                    val itemId = (itemInfo \ "itemId").as[Int]
                    updatedState.alreadyLootedIds.contains(itemId)
                  })
                  printInColor(ANSI_RED, f"[DEBUG] Filtered items excluding already looted: $itemsInContainer")

                  printInColor(ANSI_RED, f"[DEBUG] Considering looting from from $lastContainerIndex")
//                  printInColor(ANSI_RED, f"[DEBUG] Statis container list ${updatedState.staticContainersList}")

                  // Check if the last container is not already in the updatedState.staticContainersList
                  if (!updatedState.staticContainersList.contains(lastContainerIndex)) {

                    printInColor(ANSI_RED, f"[DEBUG] Looting from $lastContainerIndex")
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
                        printInColor(ANSI_RED, f"[DEBUG] Item to loot has been found: $itemId")
                        updatedState = updatedState.copy(alreadyLootedIds = updatedState.alreadyLootedIds :+ itemId)

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
                                    val mapTarget = (screenInfo \ "mapPanelLoc" \ "8x6").as[JsObject]
                                    val (targetX, targetY) = ((mapTarget \ "x").as[Int], (mapTarget \ "y").as[Int])

                                    if (itemCount == 1) {
                                      val actionsSeq = moveSingleItem(x, y, targetX, targetY)
                                      actions = actions :+ FakeAction("useMouse", None, Some(MouseActions(actionsSeq)))
                                    } else {
                                      val actionsSeq = moveMultipleItems(x, y, targetX, targetY)
                                      actions = actions :+ FakeAction("useMouse", None, Some(MouseActions(actionsSeq)))
                                    }
                                    printInColor(ANSI_RED, f"[DEBUG] Move item to ground at ($targetX, $targetY)")

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
                        itemsInContainer.fields.collectFirst {
                          case (slot, itemInfo) if (itemInfo \ "isContainer").asOpt[Boolean].getOrElse(false) =>
                            val itemSlot = slot.replace("slot", "item")
                            val itemScreenInfo = (screenInfo \ "inventoryPanelLoc" \ lastContainerIndex \ "contentsPanel" \ itemSlot).as[JsObject]
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
                            updatedState = updatedState.copy(stateHunting = "free")
                        }
                    }
                  } else {

                    if (updatedState.retryStatus >= updatedState.retryAttempts) {
                      printInColor(ANSI_RED, f"[DEBUG] No new backpack has been found. Finishing looting")
                      updatedState = updatedState.copy(stateHunting = "free")
                    } else {
                      printInColor(ANSI_RED, f"[DEBUG] Retrying - No new backpack has been found. (Attempt ${updatedState.retryStatus + 1})")
                      updatedState = updatedState.copy(retryStatus = updatedState.retryStatus + 1)
                    }
                  }
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
                  if (updatedState.retryStatus >= updatedState.retryAttempts) {
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
                    updatedState = updatedState.copy(stateHunting = "free", retryStatus = 0) // looting later and reset retryStatus
                  } else {
                    printInColor(ANSI_RED, f"[DEBUG] No Open position available or extraWindowLoc is null. Retrying... (Attempt ${updatedState.retryStatus + 1})")
                    updatedState = updatedState.copy(retryStatus = updatedState.retryStatus + 1) // increment retryStatus
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


                // Function to check for blood and container, and return the index if conditions are met
                def checkForBloodAndContainerAndGetIndex(positionKey: String): Option[String] = {
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
                def checkForBloodAndContainerAndGetIndexOld(positionKey: String): Option[String] = {
                  (json \ "areaInfo" \ "tiles" \ positionKey).asOpt[JsObject] match {
                    case Some(tileInfo) =>
                      val items = (tileInfo \ "items").as[JsObject]
                      val itemsList = items.values.toList.reverse.filterNot { item =>
                        val id = (item \ "id").as[Int]
                        id == 1900 || id == 1899
                      }
                      printInColor(ANSI_RED, f"[DEBUG] checkForBloodAndContainerAndGetIndex, itemsList: $itemsList")

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
                    updatedState = updatedState.copy(alreadyLootedIds = List.empty)
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
                        updatedState = updatedState.copy(alreadyLootedIds = List.empty)
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


}


