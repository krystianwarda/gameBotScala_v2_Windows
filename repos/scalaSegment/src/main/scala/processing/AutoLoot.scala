package processing

import mouse.FakeAction
import play.api.libs.json.{JsError, JsObject, JsValue}
import userUI.SettingsUtils.UISettings
import play.api.libs.json._

object AutoLoot {
  def computeAutoLootActions(json: JsValue, settings: UISettings, currentState: ProcessorState): ((Seq[FakeAction], Seq[Log]), ProcessorState) = {
    var actions: Seq[FakeAction] = Seq.empty
    var logs: Seq[Log] = Seq.empty
    var updatedState = currentState // Initialize updatedState

    if (settings.autoTargetSettings.enabled && settings.autoLootSettings.enabled) {
      println(s"computeAutoLootActions process started with status:${updatedState.stateHunting}")
      //test
      // Assuming 'json' is your input JsValue
      val containersInfoOpt: Option[JsObject] = (json \ "containersInfo").asOpt[JsObject]

      containersInfoOpt.foreach { containersInfoTest =>
        containersInfoTest.fields.foreach {
          case (key, value) =>
            val nameOpt = (value \ "name").asOpt[String]
            nameOpt.foreach { name =>
              println(s"$key: $name")
            }
        }
      }

      (json \ "attackInfo" \ "Id").asOpt[Int] match {
        case Some(attackedCreatureTarget) =>
          updatedState = updatedState.copy(stateHunting = "attacking")
        case None =>
          if (updatedState.lastTargetName == "") {
            // println("No target specified, no looting needed.")
          } else {

            if (updatedState.stateHunting == "looting") {
              println(s"Looting process started")
              val containersInfo = (json \ "containersInfo").as[JsObject]
              println(s"gate1")
              val screenInfo = (json \ "screenInfo").as[JsObject]
              println(s"gate2")
              // Get the last container from containersInfo
              val lastContainerIndex = containersInfo.keys.maxBy(_.replace("container", "").toInt)
              println(s"gate3")
              val lastContainer = (containersInfo \ lastContainerIndex).as[JsObject]
              println(s"gate4 - Last Container: $lastContainer")  // Added debug print for lastContainer



              // Check if the items field is a string indicating "empty" or contains objects
              (lastContainer \ "items").validate[JsObject] match {
                case JsError(_) =>
                  (lastContainer \ "items").asOpt[String] match {
                    case Some("empty") =>
                      println("Items field is 'empty'. No items to process.")
                      updatedState = updatedState.copy(stateHunting = "free")

                    case None =>
                      println("No items field present or it is not in expected format")
                      updatedState = updatedState.copy(stateHunting = "free")

                    case Some(other) =>
                      println(s"Unexpected string in items field: $other")
                      updatedState = updatedState.copy(stateHunting = "free")
                  }

                case JsSuccess(itemsInContainer, _) =>

                  val itemsInContainer = (lastContainer \ "items").as[JsObject]
                  println(s"Items in container detected: $itemsInContainer")

                  println(s"Considering looting from from $lastContainerIndex")
                  println(s"Statis container list ${updatedState.staticContainersList}")

                  // Check if the last container is not already in the updatedState.staticContainersList
                  if (!updatedState.staticContainersList.contains(lastContainerIndex)) {

                    println(s"Looting from $lastContainerIndex")
                    // Prepare a set of item IDs from lootList
                    val lootItems = settings.autoLootSettings.lootList.map(_.trim.split(",\\s*")(0).toInt).toSet
                    println(s"Available Loot Items: ${lootItems.mkString(", ")}")


                    // Assuming `json` is your JSON object parsed into a JsValue
                    val container = (json \ "containersInfo" \ lastContainerIndex \ "items").as[JsObject]

                    // Printing each item in the container
                    container.fields.foreach { case (slot, itemInfo) =>
                      val itemName = (itemInfo \ "Name").as[String]
                      val itemCount = (itemInfo \ "itemCount").as[Int]
                      val itemId = (itemInfo \ "itemId").as[Int]
                      println(s"Slot $slot: $itemName, Count: $itemCount, Item ID: $itemId")
                    }

                    // Extract all items from the last container and find first matching loot item
                    val foundItemOpt = itemsInContainer.fields.reverse.collectFirst {
                      case (slot, itemInfo) if lootItems((itemInfo \ "itemId").as[Int]) =>
                        (slot, itemInfo.as[JsObject])
                    }

                    foundItemOpt match {
                      case Some((slot, item)) =>

                        val itemId = (item \ "itemId").as[Int]
                        val itemCount = (item \ "itemCount").as[Int]
                        println(s"Item to loot has been found: $itemId")

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
                                println(s"Coordinates for $itemSlot: (x: $x, y: $y)")


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
                                    println(s"Move item to ground at ($targetX, $targetY)")

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
                                    println(s"Move item to $containerName at ($targetX, $targetY)")

                                }


                              case JsError(errors) =>
                                println(s"Error accessing item info for $itemSlot: ${errors.mkString(", ")}")
                              // Handle the error case here, such as logging or corrective actions
                            }
                          case JsError(errors) =>
                            println(s"Error accessing contents panel for container $lastContainerIndex: ${errors.mkString(", ")}")
                          // Handle the error case here, such as logging or corrective actions
                        }


                      case None =>
                        println(s"No item has been found, looking for container inside container")
                        // Iterate over items in the last container to find any that are marked as a container
                        itemsInContainer.fields.collectFirst {
                          case (slot, itemInfo) if (itemInfo \ "isContainer").asOpt[Boolean].getOrElse(false) =>
                            val itemSlot = slot.replace("slot", "item")
                            val itemScreenInfo = (screenInfo \ "inventoryPanelLoc" \ lastContainerIndex \ "contentsPanel" \ itemSlot).as[JsObject]
                            val (x, y) = ((itemScreenInfo \ "x").as[Int], (itemScreenInfo \ "y").as[Int])
                            (x, y)
                        } match {
                          case Some((x, y)) =>
                            println(s"Another container detected at position ($x, $y)")
                            // Right-click action sequence for the detected container
                            val actionsSeq = Seq(
                              MouseAction(x, y, "move"),
                              MouseAction(x, y, "pressRight"), // Changed to pressRight for right-click
                              MouseAction(x, y, "releaseRight") // Changed to releaseRight for right-click
                            )
                            actions = actions :+ FakeAction("useMouse", None, Some(MouseActions(actionsSeq)))
                          case None =>
                            println(s"No container ( and no items to loot) detected within the items, setting the state to free")
                            updatedState = updatedState.copy(stateHunting = "free")
                        }
                    }
                  } else {
                    println(s"No new backpack has been found.")
                    updatedState = updatedState.copy(stateHunting = "free")
                  }
              }


            } else if (updatedState.stateHunting == "opening window") {

              println("stage: opening window")
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
                  println(s"stage: window is opened, clicking on Open button. x=$xPosWindowOpen , $yPosWindowOpen")

                  val actionsSeq = Seq(
                    MouseAction(xPosWindowOpen, yPosWindowOpen, "move"),
                    MouseAction(xPosWindowOpen, yPosWindowOpen, "pressLeft"),
                    MouseAction(xPosWindowOpen, yPosWindowOpen, "releaseLeft"),
                  )
                  actions = actions :+ FakeAction("useMouse", None, Some(MouseActions(actionsSeq)))
                  updatedState = updatedState.copy(stateHunting = "looting") // looting later
                case None =>
                  println("No Open position available or extraWindowLoc is null")
                  updatedState = updatedState.copy(stateHunting = "free")
              }

            } else if (updatedState.stateHunting == "attacking") {

              if (updatedState.lastTargetName.nonEmpty && !updatedState.monstersListToLoot.contains(updatedState.lastTargetName)) {
                updatedState = updatedState.copy(stateHunting = "free")
              } else {
                // Retrieve position coordinates
                val xPositionGame = updatedState.lastTargetPos._1
                val yPositionGame = updatedState.lastTargetPos._2
                val zPositionGame = updatedState.lastTargetPos._3

                println(s"Creature (${updatedState.lastTargetName}) in game position $xPositionGame, $yPositionGame, $zPositionGame")


                // Function to generate position keys with padding on z-coordinate
                def generatePositionKey(x: Int, y: Int, z: Int): String = f"$x$y${z}%02d"

                // Function to check for blood and container, and return the index if conditions are met
                def checkForBloodAndContainerAndGetIndex(positionKey: String): Option[String] = {
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

                // Check the main position and surrounding positions
                val positionsToCheck = for {
                  dx <- -1 to 1
                  dy <- -1 to 1
                } yield (xPositionGame + dx, yPositionGame + dy)

                // Find the first position that meets the criteria and retrieve the index
                val indexOpt = positionsToCheck.flatMap { case (x, y) =>
                  val key = generatePositionKey(x, y, zPositionGame)
                  checkForBloodAndContainerAndGetIndex(key)
                }.headOption

                println(s"Creature screen index $indexOpt")

                indexOpt match {
                  case Some(index) =>
                    // Fetch screen coordinates from JSON using the index
                    val screenCoordsOpt = (json \ "screenInfo" \ "mapPanelLoc" \ index).asOpt[JsObject].flatMap { coords =>
                      for {
                        x <- (coords \ "x").asOpt[Int]
                        y <- (coords \ "y").asOpt[Int]
                      } yield (x, y)
                    }.getOrElse((0, 0)) // Default coordinates if not found

                    println(s"Screen coordinates are x: ${screenCoordsOpt._1}, y: ${screenCoordsOpt._2}")

                    // Define the sequence of mouse actions based on retrieved screen coordinates
                    val (xPositionScreen, yPositionScreen) = screenCoordsOpt
                    println(s"Creature body screen position $xPositionScreen, $yPositionScreen")

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
                    println(s"Creature screen index $indexOpt")
                    updatedState = updatedState.copy(stateHunting = "free")
                }

              }

            }
          }
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


