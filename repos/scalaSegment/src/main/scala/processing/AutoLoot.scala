package processing

import mouse.FakeAction
import play.api.libs.json.{JsError, JsObject, JsValue}
import userUI.SettingsUtils.UISettings
import play.api.libs.json._
import processing.AutoTarget.parseCreature
import processing.Process.{addMouseAction, extractOkButtonPosition}
import utils.consoleColorPrint.{ANSI_GREEN, ANSI_RED, printInColor}

import scala.util.Random

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



      val battleShootableCreaturesList = getBattleShootableCreaturesList(json)

      println(s"updatedState.carsassToLoot: ${updatedState.carsassToLoot}")
      println(s"battleShootableCreaturesList: ${battleShootableCreaturesList}")
      println(s"lastTargetName: ${updatedState.lastTargetName}")

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
            updatedState = updatedState.copy(carsassToLoot = mainIndexOpt.toList, stateHunting = "free")
            println(s"Setting up stateHunting to free (2)")

          } else {
            mainIndexOpt match {
              case Some(index) =>
                // Fetch screen coordinates from JSON using the index
                println(s"Loot immediately and carcass index exists")
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


    println(s"Loot carcass processFreeStateAfterFight")
    val screenCoordsOpt = (json \ "screenInfo" \ "mapPanelLoc" \ carcassIndexList.head).asOpt[JsObject].flatMap { coords =>
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


