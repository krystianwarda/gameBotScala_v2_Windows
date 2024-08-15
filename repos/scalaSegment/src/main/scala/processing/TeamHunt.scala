package processing

import mouse.FakeAction
import play.api.libs.json._
import userUI.SettingsUtils.UISettings
import utils.consoleColorPrint.{ANSI_RED, printInColor}
import processing.CaveBot.{Vec, aStarSearch, adjustGoalWithinBounds, calculateDirection, createBooleanGrid, generateSubwaypoints, printGrid}
import play.api.libs.json._

import java.lang.System.currentTimeMillis
import utils.consoleColorPrint._




object TeamHunt {
  def computeTeamHuntActions(json: JsValue, settings: UISettings, currentState: ProcessorState): ((Seq[FakeAction], Seq[Log]), ProcessorState) = {
    printInColor(ANSI_RED, f"[DEBUG] Performing computeTeamHuntActions action")
    var actions: Seq[FakeAction] = Seq.empty
    var logs: Seq[Log] = Seq.empty
    var updatedState = currentState // Initialize updatedState

    val startTime = System.nanoTime()

    if (settings.teamHuntSettings.enabled) {

      val presentCharLocationX = (json \ "characterInfo" \ "PositionX").as[Int]
      val presentCharLocationY = (json \ "characterInfo" \ "PositionY").as[Int]
      val presentCharLocationZ = (json \ "characterInfo" \ "PositionZ").as[Int]
      val presentCharLocation = Vec(presentCharLocationX, presentCharLocationY)

      updatedState = updatedState.copy(presentCharLocation = presentCharLocation, presentCharZLocation=presentCharLocationZ)

      if (settings.teamHuntSettings.followBlocker) {
        (json \ "spyLevelInfo").validate[JsObject] match {
          case JsSuccess(spyInfo, _) =>
            val tiles = (json \ "areaInfo" \ "tiles").as[Map[String, JsObject]]
            val xs = tiles.keys.map(_.substring(0, 5).trim.toInt)
            val ys = tiles.keys.map(_.substring(5, 10).trim.toInt)
            val gridBounds = (xs.min, ys.min, xs.max, ys.max) // Properly define gridBounds here

            val (grid, _) = createBooleanGrid(tiles, xs.min, ys.min) // Correct usage of createBooleanGrid

            val blockerName = settings.teamHuntSettings.blockerName
            // Try to find the blocker
            val maybeBlocker = spyInfo.value.collectFirst {
              case (_, jsValue) if (jsValue \ "Name").asOpt[String].contains(blockerName) =>
                val blockerPos = Vec((jsValue \ "PositionX").as[Int], (jsValue \ "PositionY").as[Int])
                val blockerZ = (jsValue \ "PositionZ").as[Int]
                (blockerPos, blockerZ)
            }

            maybeBlocker match {
              // Blocker found
              case Some((blockerPos, blockerZ)) =>
                if (blockerZ != currentState.presentCharZLocation) {
                  // Blocker is on a different level, so we change levels
                  val result = changeLevel(
                    blockerZ,
                    presentCharLocationZ,
                    json,
                    tiles,
                    updatedState)

                  actions ++= result._1._1
                  logs ++= result._1._2
                  updatedState = result._2

                } else {
                  // Blocker is on the same level, check if path is available
                  if (isPathAvailable(presentCharLocation, blockerPos, grid, gridBounds)) {

                    // following blocker
                    val result = followTarget(
                      blockerPos,
                      presentCharLocation,
                      json,
                      updatedState
                    )

                    actions ++= result._1._1
                    logs ++= result._1._2
                    updatedState = result._2

                  } else {

                    // Blocker not reachable, find a reachable team member
                    findReachableTeamMember(settings, currentState, spyInfo, grid, gridBounds).map { teamMemberPos =>
                      // following team member
                      val result = followTarget(
                        teamMemberPos,
                        presentCharLocation,
                        json,
                        updatedState
                      )

                      actions ++= result._1._1
                      logs ++= result._1._2
                      updatedState = result._2

                    }.getOrElse {
                      logs :+= Log("Blocker and no team member are reachable.")
                    }
                  }
                }

              // No blocker found in the spy info
              case None =>
                logs :+= Log("Blocker not found in spy info.")
            }




            // Determine the effective target
//              val effectiveTarget = determineEffectiveTarget(settings, currentState, spyInfo, presentCharLocation, grid, gridBounds)


            // check if blocker is available
            // if blocker is on different vertical level than use function changeLevel
            // if blocker is cant not be found or there is no path to him change effectiveTarget from blocker to team member

















//
//            // Follow the determined target
//            effectiveTarget match {
//              case Some((target, targetZ)) =>
//                println(s"[DEBUG] Target determined at: X=${target.x}, Y=${target.y}, Z=$targetZ")
////                  val blockerPosX = (targetInfo \ "PositionX").as[Int]
//                val blockerPosX = target.x
////                  val blockerPosY = (targetInfo \ "PositionY").as[Int]
//                val blockerPosY = target.y
////                  val blockerPosZ = (targetInfo \ "PositionZ").as[Int]
//                val blockerPosZ = targetZ
//
//                println(s"Blocker Position: X=$blockerPosX, Y=$blockerPosY")
//                val blockerCharLocation = Vec(blockerPosX, blockerPosY)
//                updatedState = updatedState.copy(lastBlockerPos = (blockerPosX, blockerPosY, blockerPosZ))
//
//                if (!isPathAvailable(presentCharLocation, blockerCharLocation, grid, gridBounds)) {
//                  println("[DEBUG] Path not available to blocker, checking for other team members.")
//                }
//
//
//                // Check if the blocker is at the same vertical level
//                if (blockerPosZ == presentCharLocationZ) {
//                  println("Blocker is on the same level")
//                  val chebyshevDistance = Math.max(
//                    Math.abs(target.x - presentCharLocation.x),
//                    Math.abs(target.y - presentCharLocation.y)
//                  )
//
//                  // Process the JSON to extract battle info
//                  val battleInfoResult = (json \ "battleInfo").validate[Map[String, JsValue]]
//
//                  battleInfoResult match {
//                    case JsSuccess(battleInfo, _) =>
//
//                      // Check if there are any monsters in the battle info
//                      val hasMonsters = battleInfo.exists { case (_, creature) =>
//                        (creature \ "IsMonster").asOpt[Boolean].getOrElse(false)
//                      }
//
//                      // Determine the required distance based on the presence of monsters
//                      val requiredDistance = if (hasMonsters) 3 else 2
//
//                      // Check if the conditions are met to update the path and follow the blocker
//                      // Adjust distances for movement based on monster presence
//                      if (chebyshevDistance > requiredDistance) {
//                        updatedState = generateSubwaypointsToBlocker(blockerCharLocation, updatedState, json)
//
//                        if (updatedState.subWaypoints.nonEmpty) {
//                          val nextWaypoint = updatedState.subWaypoints.head
//                          val direction = calculateDirection(presentCharLocation, nextWaypoint, updatedState.lastDirection)
//                          printInColor(ANSI_RED, f"[DEBUG] Calculated Next Direction: $direction")
//                          updatedState = updatedState.copy(lastDirection = direction)
//
//                          direction.foreach { dir =>
//                            actions = actions :+ FakeAction("pressKey", None, Some(PushTheButton(dir)))
//                            logs :+= Log(s"Moving closer to the subWaypoint in direction: $dir")
//                          }
//                        }
//                      } else {
//                        printInColor(ANSI_YELLOW, "[DEBUG] Too close to blocker, stopping pursuit.")
//                      }
//                  }
//                } else {
//                  println(s"Blocker changed level. Last blocker position: ${updatedState.lastBlockerPos}")
//
//                  // Fetch tiles data from JSON
//                  val tiles = (json \ "areaInfo" \ "tiles").as[Map[String, JsObject]]
//
//
//                  // Declare the level movement enablers lists outside the conditionals
//                  var levelMovementEnablersIdsList: List[Int] = List()
//
//                  if (blockerPosZ < presentCharLocationZ) {
//                    println("Blocker went up")
//                    levelMovementEnablersIdsList = List(
//                      1948, // ladder up
//                      1947, // stairs up
//                      1952, // stone stairs up
//                      386, // rope up
//                      1958 // stairs up
//                    )
//                  } else if (blockerPosZ > presentCharLocationZ) {
//                    println("Blocker went down")
//                    levelMovementEnablersIdsList = List(
//                      414, // ladder down
//                      385, // hole down
//                      428, // stairs down
//                      435, // grate down
//                      434, // stairs down
//                      593, // closed shovel hole
//                      594, // opened shovel hole
//                      469 // stone stairs down
//                    )
//                  }
//
//                  // Collect tiles which contain items that are present in the current levelMovementEnablersIdsList
//                  val potentialTiles = tiles.collect {
//                    case (tileId, tileData) if (tileData \ "items").as[Map[String, JsObject]].values.exists(itemData =>
//                      levelMovementEnablersIdsList.contains((itemData \ "id").as[Int])
//                    ) =>
//                      (tileId, (tileData \ "index").as[String]) // Extract the index which is used to find screen coordinates
//                  }
//
//                  // Retrieve screen coordinates from the index and map them
//                  val tileScreenCoordinates = potentialTiles.flatMap { case (tileId, index) =>
//                    (json \ "screenInfo" \ "mapPanelLoc" \ index).asOpt[JsObject].map { screenData =>
//                      val x = (screenData \ "x").as[Int]
//                      val y = (screenData \ "y").as[Int]
//                      printInColor(ANSI_BLUE, s"[WRONG FLOOR] Tile check ID: $tileId ($x, $y)")
//                      (tileId, x, y) // Note this tuple includes tileId
//                    }
//                  }.headOption // Get the first available screen coordinate
//
//
//                  tileScreenCoordinates match {
//                    case Some((tileId, screenX, screenY)) =>
//                      printInColor(ANSI_BLUE, s"[WRONG FLOOR] Found valid tile ($tileId) on screen at ($screenX, $screenY), Character location: ($presentCharLocationX, $presentCharLocationY)")
//
//                      // Check if it's the first time or if sufficient time has elapsed since the last action
//                      if (updatedState.chasingBlockerLevelChangeTime == 0 || (updatedState.currentTime - updatedState.chasingBlockerLevelChangeTime > updatedState.longTimeLimit)) {
//
//                        printInColor(ANSI_BLUE, "[WRONG FLOOR] Chasing blocker.")
//
//
//                        if (tileId == 386) {
//
//                          // Step 1: Locate container and slot with itemId 3003 (Rope)
//                          val containersInfo = (json \ "containersInfo").as[JsObject]
//                          val ropeLocation = containersInfo.value.collectFirst {
//                            case (containerName, container) if (container \ "items").as[JsObject].value.exists {
//                              case (slotName, item) => (item \ "itemId").as[Int] == 3003
//                            } => (containerName, (container \ "items").as[JsObject].value.find {
//                              case (slotName, item) => (item \ "itemId").as[Int] == 3003
//                            }.get._1)
//                          }
//
//                          // Step 2: Find screen location in screenInfo based on container and slot
//                          ropeLocation match {
//                            case Some((containerName, slot)) =>
//                              val screenInfo = (json \ "screenInfo" \ "inventoryPanelLoc").as[JsObject]
//                              // Extract the container substring and match it with screenInfo
//                              val matchedContainer = screenInfo.keys.find(key => key.contains(containerName)).getOrElse("")
//
//                              // Get slot number, map it to item number
//                              val itemNumber = slot.replace("slot", "item")
//
//                              // Now, find the item screen position
//                              val itemPosition = (json \ "screenInfo" \ "inventoryPanelLoc" \ matchedContainer \ "contentsPanel" \ itemNumber).as[JsObject]
//                              val itemScreenLocX = (itemPosition \ "x").as[Int]
//                              val itemScreenLocY = (itemPosition \ "y").as[Int]
//
//                              println(s"Rope Found in container: $containerName, slot: $slot, Screen Position: x=$itemScreenLocX, y=$itemScreenLocY")
//
//                              val actionsSeq = Seq(
//                                MouseAction(itemScreenLocX, itemScreenLocY, "move"),
//                                MouseAction(itemScreenLocX, itemScreenLocY, "pressRight"),
//                                MouseAction(itemScreenLocX, itemScreenLocY, "releaseRight"),
//                                MouseAction(screenX, screenY, "move"),
//                                MouseAction(screenX, screenY, "pressLeft"),
//                                MouseAction(screenX, screenY, "releaseLeft")
//                              )
//                              actions = actions :+ FakeAction("useMouse", None, Some(MouseActions(actionsSeq)))
//                              updatedState = updatedState.copy(chasingBlockerLevelChangeTime = updatedState.currentTime)
//                            case None =>
//                              println("Rope not found.")
//                          }
//
//                        } else {
//                          val actionsSeq = Seq(
//                            MouseAction(screenX, screenY, "move"),
//                            MouseAction(screenX, screenY, "pressLeft"),
//                            MouseAction(screenX, screenY, "releaseLeft")
//                          )
//                          actions = actions :+ FakeAction("useMouse", None, Some(MouseActions(actionsSeq)))
//                          updatedState = updatedState.copy(chasingBlockerLevelChangeTime = updatedState.currentTime)
//                        }
//
//
//                      } else {
//                        printInColor(ANSI_BLUE, "[WRONG FLOOR] Blocker was chased recently.")
//                      }
//
//                    case None =>
//                      printInColor(ANSI_BLUE, "[WRONG FLOOR] No valid waypoint found within range.")
//                  }
//                }
//
//              case None =>
//
//                println("Blocker not on the screen")
//                val lastBlockerXPos = updatedState.lastBlockerPos._1
//                val lastBlockerYPos = updatedState.lastBlockerPos._2
//                val lastBlockerZPos = updatedState.lastBlockerPos._3
//                println(s"Blocker last position: X=$lastBlockerXPos, Y=$lastBlockerYPos, Z=$lastBlockerZPos")
//
//
//                // Adjusting X coordinate to ensure it's within a max distance of 7
//                val adjustedBlockerXPos = if ((lastBlockerXPos - presentCharLocationX).abs > 7) {
//                  if (lastBlockerXPos > presentCharLocationX) presentCharLocationX + 7 else presentCharLocationX - 7
//                } else lastBlockerXPos
//
//                // Adjusting Y coordinate to ensure it's within a max distance of 6
//                val adjustedBlockerYPos = if ((lastBlockerYPos - presentCharLocationY).abs > 6) {
//                  if (lastBlockerYPos > presentCharLocationY) presentCharLocationY + 6 else presentCharLocationY - 6
//                } else lastBlockerYPos
//
//                println(s"Adjusted Blocker Position to: X = $adjustedBlockerXPos, Y = $adjustedBlockerYPos, Z = $lastBlockerZPos")
//
//                val screenCoordinates = gameToScreenCoordinatesByTileId(adjustedBlockerXPos, lastBlockerYPos, lastBlockerZPos, json)
//                screenCoordinates match {
//                  case Some((screenX, screenY)) =>
//                    printInColor(ANSI_BLUE, s"[LAST SEEN] Found last known tile on screen at tile ${updatedState.lastBlockerPos} ($screenX, $screenY)")
//
//                    // Check if it's the first time or if sufficient time has elapsed since the last action
//                    if (updatedState.chasingBlockerLevelChangeTime == 0 || (updatedState.currentTime - updatedState.chasingBlockerLevelChangeTime > updatedState.longTimeLimit)) {
//                      printInColor(ANSI_BLUE, "[LAST SEEN] Chasing last known position of blocker.")
//                      val actionsSeq = Seq(
//                        MouseAction(screenX, screenY, "move"),
//                        MouseAction(screenX, screenY, "pressLeft"),
//                        MouseAction(screenX, screenY, "releaseLeft")
//                      )
//                      actions = actions :+ FakeAction("useMouse", None, Some(MouseActions(actionsSeq)))
//                      updatedState = updatedState.copy(chasingBlockerLevelChangeTime = updatedState.currentTime)
//                    } else {
//                      printInColor(ANSI_BLUE, "[LAST SEEN] Blocker was chased recently.")
//                    }
//                  case None =>
//                    println("No screen coordinates found for the given tile ID.")
//                }
//            }
          case JsError(errors) =>
            println(s"Error parsing spyLevelInfo: $errors")
        }

      }

    }
    val endTime = System.nanoTime()
    val duration = (endTime - startTime) / 1e9d
    printInColor(ANSI_GREEN, f"[INFO] Processing computeTeamHuntActions took $duration%.6f seconds")

    ((actions, logs), updatedState)
  }

  def determineEffectiveTarget(settings: UISettings, currentState: ProcessorState, spyInfo: JsObject, presentCharLocation: Vec, grid: Array[Array[Boolean]], gridBounds: (Int, Int, Int, Int)): Option[(Vec, Int)] = {
    val blockerName = settings.teamHuntSettings.blockerName
    val maybeBlocker = spyInfo.value.collectFirst {
      case (_, jsValue) if (jsValue \ "Name").asOpt[String].contains(blockerName) &&
        (jsValue \ "PositionZ").as[Int] == currentState.presentCharZLocation =>
        val pos = Vec((jsValue \ "PositionX").as[Int], (jsValue \ "PositionY").as[Int])
        val posZ = (jsValue \ "PositionZ").as[Int]
        if (isPathAvailable(presentCharLocation, pos, grid, gridBounds)) Some((pos, posZ))
        else None
    }.flatten

    maybeBlocker.orElse {
      println("[DEBUG] No path to blocker, checking for reachable team members.")
      findReachableTeamMember(settings, currentState, spyInfo, grid, gridBounds).map {
        vec => (vec, currentState.presentCharZLocation)
      }
    }
  }



  def extractTeamMembers(settings: UISettings, currentState: ProcessorState, spyInfo: JsObject): Seq[(String, Vec)] = {
    spyInfo.value.flatMap {
      case (id, jsValue) =>
        for {
          name <- (jsValue \ "Name").asOpt[String] if settings.teamHuntSettings.teamMembersList.contains(name)
          posX <- (jsValue \ "PositionX").asOpt[Int]
          posY <- (jsValue \ "PositionY").asOpt[Int]
          posZ <- (jsValue \ "PositionZ").asOpt[Int] if posZ == currentState.presentCharZLocation
        } yield (id -> Vec(posX, posY))
    }.toSeq
  }




  // Find reachable team member if the blocker isn't found
  def findReachableTeamMember(settings: UISettings, currentState: ProcessorState, spyInfo: JsObject, grid: Array[Array[Boolean]], gridBounds: (Int, Int, Int, Int)): Option[Vec] = {
    val teamMembers = extractTeamMembers(settings, currentState, spyInfo)

    val reachableMembers = teamMembers.filter {
      case (_, vec) => isPathAvailable(currentState.presentCharLocation, vec, grid, gridBounds)
    }

    reachableMembers.minByOption {
      case (_, vec) => Math.hypot(vec.x - currentState.presentCharLocation.x, vec.y - currentState.presentCharLocation.y)
    }.map(_._2)
  }


  // Check path availability using A* search
  def isPathAvailable(start: Vec, end: Vec, grid: Array[Array[Boolean]], gridBounds: (Int, Int, Int, Int)): Boolean = {
    aStarSearch(start, end, grid, gridBounds._1, gridBounds._2).nonEmpty
  }

  // Function to convert game coordinates to a single string identifier and find screen coordinates from JSON
  def gameToScreenCoordinatesByTileId(gameX: Int, gameY: Int, gameZ: Int, json: JsValue): Option[(Int, Int)] = {
    val tileId = s"${gameX}${gameY}${gameZ}"

    // Extracting the mapPanelLoc part of the JSON
    val mapPanelLoc = (json \ "screenInfo" \ "mapPanelLoc").as[JsObject]

    // Finding the matching tile by id
    mapPanelLoc.values.find {
      case JsObject(obj) if obj.apply("id").asOpt[String].contains(tileId) => true
      case _ => false
    }.flatMap { tileJsValue =>
      for {
        x <- (tileJsValue \ "x").asOpt[Int]
        y <- (tileJsValue \ "y").asOpt[Int]
      } yield (x, y)
    }
  }





  def generateSubwaypointsToBlocker(currentWaypointLocation: Vec, initialState: ProcessorState, json: JsValue): ProcessorState = {
    println("[DEBUG] Generating subwaypoints for current waypoint")
    var updatedState = initialState
    // Parse tiles to determine the grid bounds and create a boolean grid
    val tiles = (json \ "areaInfo" \ "tiles").as[Map[String, JsObject]]
    val xs = tiles.keys.map(_.substring(0, 5).trim.toInt)
    val ys = tiles.keys.map(_.substring(5, 10).trim.toInt)
    val gridBounds = (xs.min, ys.min, xs.max, ys.max)
    println(s"[DEBUG] GridBounds: $gridBounds")

    val (grid, (min_x, min_y)) = createBooleanGrid(tiles, xs.min, ys.min)

    // Determine current waypoint location
    println(s"[DEBUG] Current Waypoint: $currentWaypointLocation")

    // Adjust waypoint location if out of grid range
//    if ((currentWaypointLocation.x < gridBounds._1 || currentWaypointLocation.x > gridBounds._3 ||
//      currentWaypointLocation.y < gridBounds._2 || currentWaypointLocation.y > gridBounds._4)) {
//
//      val currentWaypointLocationTemp = adjustGoalWithinBounds(currentWaypointLocation, grid, gridBounds)
//      println(s"[WARNING] Waypoint is out of grid range. Adjusting from ${currentWaypointLocation} to ${currentWaypointLocationTemp}")
//      currentWaypointLocation = currentWaypointLocationTemp
//    }

    // Determine character's current location and perform A* search
    val presentCharLocation = updatedState.presentCharLocation
    println(s"[DEBUG] Character location: $presentCharLocation")
    var newPath: List[Vec] = List()

    if (presentCharLocation != currentWaypointLocation) {
      // Make sure to include min_x and min_y when calling aStarSearch
      newPath = aStarSearch(presentCharLocation, currentWaypointLocation, grid, min_x, min_y)
      printInColor(ANSI_BLUE, f"[WAYPOINTS] Path: $newPath.")
    } else {
      println("[DEBUG] Current location matches the current waypoint, moving to the next waypoint.")
//      // Increment the waypoint index safely with modulo to cycle through the list
//      val nextWaypointIndex = (updatedState.currentWaypointIndex + 1) % updatedState.fixedWaypoints.size
//      updatedState = updatedState.copy(currentWaypointIndex = nextWaypointIndex)
//
//      // Retrieve the new current waypoint from the updated index
//      val currentWaypoint = updatedState.fixedWaypoints(nextWaypointIndex)
//      var currentWaypointLocation = Vec(currentWaypoint.waypointX, currentWaypoint.waypointY)
//
//      println(s"[DEBUG] New current waypoint set to: $currentWaypointLocation")
//
//      // You may choose to trigger a new path calculation here if necessary
//      // For example, you might want to calculate the path to this new waypoint
//      newPath = aStarSearch(presentCharLocation, currentWaypointLocation, grid, min_x, min_y)
//      println(s"[DEBUG] Path: ${newPath.mkString(" -> ")}")
//      updatedState = updatedState.copy(subWaypoints = newPath)
    }

    // Remove the presentCharLocation from the newPath if it exists
    val filteredPath = newPath.filterNot(loc => loc == presentCharLocation)

    println(s"[DEBUG] Path: ${filteredPath.mkString(" -> ")}")
    println(s"[DEBUG] Char loc: $presentCharLocation")
    println(s"[DEBUG] Waypoint loc: $currentWaypointLocation")


    if (presentCharLocation != currentWaypointLocation) {
      printGrid(grid, gridBounds, filteredPath, updatedState.presentCharLocation, currentWaypointLocation)
      // Locations are different, update state accordingly
      updatedState.copy(
        subWaypoints = filteredPath,
        gridBoundsState = gridBounds,
        gridState = grid,
        currentWaypointLocation = currentWaypointLocation,
        presentCharLocation = presentCharLocation
      )
    } else {
      println(s"[DEBUG] presentCharLocation &  Char loc are the same.")
      updatedState
    }
  }


  def changeLevel(
                   blockerPosZ: Int,
                   presentCharLocationZ: Int,
                   json: JsValue, tiles: Map[String, JsObject],
                   currentState: ProcessorState
                 ): ((Seq[FakeAction], Seq[Log]), ProcessorState) = {
    var actions: Seq[FakeAction] = Seq.empty
    var logs: Seq[Log] = Seq.empty // Add logs to keep track of actions taken
    var updatedState = currentState
    // Declare the level movement enablers lists based on whether the blocker is above or below
    var levelMovementEnablersIdsList: List[Int] = List()

    if (blockerPosZ < presentCharLocationZ) {
      logs :+= Log("Blocker went up")
      levelMovementEnablersIdsList = List(
        1948, // ladder up
        1947, // stairs up
        1952, // stone stairs up
        386, // rope up
        1958 // stairs up
      )
    } else if (blockerPosZ > presentCharLocationZ) {
      logs :+= Log("Blocker went down")
      levelMovementEnablersIdsList = List(
        414, // ladder down
        385, // hole down
        428, // stairs down
        435, // grate down
        434, // stairs down
        593, // closed shovel hole
        594, // opened shovel hole
        469 // stone stairs down
      )
    }

    // Collect tiles which contain items that are present in the current levelMovementEnablersIdsList
    val potentialTiles = tiles.collect {
      case (tileId, tileData) if (tileData \ "items").as[Map[String, JsObject]].values.exists(itemData =>
        levelMovementEnablersIdsList.contains((itemData \ "id").as[Int])
      ) =>
        // Extract both the index and the items from the tileData
        val index = (tileData \ "index").as[String]
        val items = (tileData \ "items").as[Map[String, JsObject]] // Extract items data
        (tileId, index, items)
    }


    // Retrieve screen coordinates from the index and map them, while keeping the items data available
    val tileScreenCoordinates = potentialTiles.flatMap { case (tileId, index, items) =>
      (json \ "screenInfo" \ "mapPanelLoc" \ index).asOpt[JsObject].map { screenData =>
        val x = (screenData \ "x").as[Int]
        val y = (screenData \ "y").as[Int]
        logs :+= Log(s"[WRONG FLOOR] Tile check ID: $tileId ($x, $y)")

        (tileId, x, y, items) // Return the items along with screen coordinates
      }
    }.headOption // Get the first available screen coordinate


    tileScreenCoordinates match {
      case Some((tileId, screenX, screenY, items)) =>
        logs :+= Log(s"[WRONG FLOOR] Found valid tile ($tileId) on screen at ($screenX, $screenY), Character location: (current: $presentCharLocationZ, blocker: $blockerPosZ)")

        // Check if it's the first time or if sufficient time has elapsed since the last action
        if (updatedState.chasingBlockerLevelChangeTime == 0 || (updatedState.currentTime - updatedState.chasingBlockerLevelChangeTime > updatedState.longTimeLimit)) {
          logs :+= Log("[WRONG FLOOR] Chasing blocker.")

          // Handle the special case of using rope, check if there is exactly one item and its id is 386
          if (items.size == 1 && items.values.exists(itemData => (itemData \ "id").as[Int] == 386)) {

            // Step 1: Locate container and slot with itemId 3003 (Rope)
            val containersInfo = (json \ "containersInfo").as[JsObject]
            val ropeLocation = containersInfo.value.collectFirst {
              case (containerName, container) if (container \ "items").as[JsObject].value.exists {
                case (_, item) => (item \ "itemId").as[Int] == 3003
              } => (containerName, (container \ "items").as[JsObject].value.find {
                case (_, item) => (item \ "itemId").as[Int] == 3003
              }.get._1)
            }

            // Step 2: Find screen location in screenInfo based on container and slot
            ropeLocation match {
              case Some((containerName, slot)) =>
                val screenInfo = (json \ "screenInfo" \ "inventoryPanelLoc").as[JsObject]
                val matchedContainer = screenInfo.keys.find(_.contains(containerName)).getOrElse("")

                // Get slot number, map it to item number
                val itemNumber = slot.replace("slot", "item")
                val itemPosition = (screenInfo \ matchedContainer \ "contentsPanel" \ itemNumber).as[JsObject]
                val itemScreenLocX = (itemPosition \ "x").as[Int]
                val itemScreenLocY = (itemPosition \ "y").as[Int]

                logs :+= Log(s"Rope Found in container: $containerName, slot: $slot, Screen Position: x=$itemScreenLocX, y=$itemScreenLocY")

                val actionsSeq = Seq(
                  MouseAction(itemScreenLocX, itemScreenLocY, "move"),
                  MouseAction(itemScreenLocX, itemScreenLocY, "pressRight"),
                  MouseAction(itemScreenLocX, itemScreenLocY, "releaseRight"),
                  MouseAction(screenX, screenY, "move"),
                  MouseAction(screenX, screenY, "pressLeft"),
                  MouseAction(screenX, screenY, "releaseLeft")
                )
                actions = actions :+ FakeAction("useMouse", None, Some(MouseActions(actionsSeq)))
                updatedState = updatedState.copy(chasingBlockerLevelChangeTime = updatedState.currentTime)

              case None =>
                logs :+= Log("Rope not found.")
            }

          } else if (items.size > 1 && items.values.exists(itemData => (itemData \ "id").as[Int] == 386)) {
            logs :+= Log("Rope placed trashed.")
          } else {
            // Standard case for stairs or ladders
            val actionsSeq = Seq(
              MouseAction(screenX, screenY, "move"),
              MouseAction(screenX, screenY, "pressLeft"),
              MouseAction(screenX, screenY, "releaseLeft")
            )
            actions = actions :+ FakeAction("useMouse", None, Some(MouseActions(actionsSeq)))
            updatedState = updatedState.copy(chasingBlockerLevelChangeTime = updatedState.currentTime)
          }

        } else {
          logs :+= Log("[WRONG FLOOR] Blocker was chased recently.")
        }

      case None =>
        logs :+= Log("[WRONG FLOOR] No valid waypoint found within range.")
    }

    ((actions, logs), updatedState)
  }

  def followTarget(
                    target: Vec, // The location of the target (blocker)
                    presentCharLocation: Vec, // The character's current location
                    json: JsValue, // The game state JSON
                    currentState: ProcessorState // The current state of the character
                  ): ((Seq[FakeAction], Seq[Log]), ProcessorState) = {

    var updatedState = currentState
    var actions: Seq[FakeAction] = Seq.empty
    var logs: Seq[Log] = Seq.empty

    // Calculate Chebyshev Distance between the character and the target
    val chebyshevDistance = Math.max(
      Math.abs(target.x - presentCharLocation.x),
      Math.abs(target.y - presentCharLocation.y)
    )

    // Process the JSON to extract battle info
    val battleInfoResult = (json \ "battleInfo").validate[Map[String, JsValue]]

    battleInfoResult match {
      case JsSuccess(battleInfo, _) =>

        // Check if there are any monsters in the battle info
        val hasMonsters = battleInfo.exists { case (_, creature) =>
          (creature \ "IsMonster").asOpt[Boolean].getOrElse(false)
        }

        // Determine the required distance based on the presence of monsters
        val requiredDistance = if (hasMonsters) 3 else 2

        // Check if the character is too far from the blocker
        if (chebyshevDistance > requiredDistance) {
          // Generate subwaypoints to move closer to the blocker
          updatedState = generateSubwaypointsToBlocker(target, updatedState, json)

          // If there are sub-waypoints, move towards the next one
          if (updatedState.subWaypoints.nonEmpty) {
            val nextWaypoint = updatedState.subWaypoints.head
            val direction = calculateDirection(presentCharLocation, nextWaypoint, updatedState.lastDirection)
            logs :+= Log(f"[DEBUG] Calculated Next Direction: $direction")

            updatedState = updatedState.copy(lastDirection = direction)

            direction.foreach { dir =>
              // Add the movement action (key press) to the action sequence
              actions = actions :+ FakeAction("pressKey", None, Some(PushTheButton(dir)))
              logs :+= Log(s"Moving closer to the subWaypoint in direction: $dir")
            }

            // Remove the used waypoint from the state
            updatedState = updatedState.copy(subWaypoints = updatedState.subWaypoints.tail)
          }
        } else {
          logs :+= Log("[DEBUG] Too close to blocker, stopping pursuit.")
        }

        ((actions, logs), updatedState)

      case JsError(errors) =>
        logs :+= Log(s"Error parsing battleInfo: $errors")
        ((actions, logs), updatedState)
    }
  }


}






//
//  import scala.collection.mutable
//  import play.api.libs.json._
//
//  object TeamHunt {
//    case class Vec(x: Int, y: Int) {
//      def +(other: Vec): Vec = Vec(x + other.x, y + other.y)
//    }
//
//    def computeTeamHuntActions(json: JsValue, settings: UISettings, currentState: ProcessorState): ((Seq[FakeAction], Seq[Log]), ProcessorState) = {
//      var actions: Seq[FakeAction] = Seq.empty
//      var logs: Seq[Log] = Seq.empty
//
//      if (settings.teamHuntSettings.enabled && settings.teamHuntSettings.followBlocker) {
//        (json \ "spyLevelInfo").validate[JsObject] match {
//          case JsSuccess(spyInfo, _) =>
//            val tiles = (json \ "areaInfo" \ "tiles").as[Map[String, JsObject]]
//            val xs = tiles.keys.map(_.substring(0, 5).trim.toInt)
//            val ys = tiles.keys.map(_.substring(5, 10).trim.toInt)
//            val (grid, gridBounds) = createBooleanGrid(tiles, xs.min, ys.min)
//
//            val blockerName = settings.teamHuntSettings.blockerName
//            val maybeBlocker = spyInfo.value.find {
//              case (_, jsValue) => (jsValue \ "Name").asOpt[String].contains(blockerName)
//            }
//
//            val effectiveTarget = maybeBlocker.orElse(findReachableTeamMember(spyInfo, grid, gridBounds))
//
//            effectiveTarget match {
//              case Some((_, targetInfo)) =>
//                val targetPosX = (targetInfo \ "PositionX").as[Int]
//                val targetPosY = (targetInfo \ "PositionY").as[Int]
//                println(s"Following target at position: X=$targetPosX, Y=$targetPosY")
//              // Logic to follow the target
//              case None =>
//                println("No accessible target found.")
//            }
//
//          case JsError(errors) =>
//            println(s"Error parsing spyLevelInfo: $errors")
//        }
//      }
//
//      ((actions, logs), currentState)
//    }
//
//    // Find reachable team member if the blocker isn't found
//    def findReachableTeamMember(spyInfo: JsObject, grid: Array[Array[Boolean]], gridBounds: (Int, Int, Int, Int)): Option[(String, JsValue)] = {
//      val teamMembers = extractTeamMembers(spyInfo)
//      val reachableMembers = teamMembers.filter { case (_, memberPos) =>
//        isPathAvailable(currentState.presentCharLocation, memberPos, grid, gridBounds)
//      }
//
//      val closestReachableMember = reachableMembers.minByOption { case (_, pos) =>
//        Math.hypot(pos.x - currentState.presentCharLocation.x, pos.y - currentState.presentCharLocation.y)
//      }
//
//      closestReachableMember
//    }
//
//    // Extract team members from JSON with their positions wrapped in Vec
//    def extractTeamMembers(spyInfo: JsObject): Seq[(String, Vec)] = {
//      spyInfo.value.flatMap {
//        case (id, jsValue) if settings.teamHuntSettings.teamMembersList.contains((jsValue \ "Name").as[String]) =>
//          val posX = (jsValue \ "PositionX").as[Int]
//          val posY = (jsValue \ "PositionY").as[Int]
//          val posZ = (jsValue \ "PositionZ").as[Int]
//          if (posZ == currentState.presentCharLocationZ) Some(id -> Vec(posX, posY)) else None
//      }.toSeq
//    }
//
//
//    def generateSubwaypointsToBlocker(currentWaypointLocation: Vec, initialState: ProcessorState, json: JsValue): ProcessorState = {
//    println("[DEBUG] Generating subwaypoints for current waypoint")
//    var updatedState = initialState
//    // Parse tiles to determine the grid bounds and create a boolean grid
//    val tiles = (json \ "areaInfo" \ "tiles").as[Map[String, JsObject]]
//    val xs = tiles.keys.map(_.substring(0, 5).trim.toInt)
//    val ys = tiles.keys.map(_.substring(5, 10).trim.toInt)
//    val gridBounds = (xs.min, ys.min, xs.max, ys.max)
//    println(s"[DEBUG] GridBounds: $gridBounds")
//
//    val (grid, (min_x, min_y)) = createBooleanGrid(tiles, xs.min, ys.min)
//
//    // Determine current waypoint location
//    println(s"[DEBUG] Current Waypoint: $currentWaypointLocation")
//
//    // Adjust waypoint location if out of grid range
////    if ((currentWaypointLocation.x < gridBounds._1 || currentWaypointLocation.x > gridBounds._3 ||
////      currentWaypointLocation.y < gridBounds._2 || currentWaypointLocation.y > gridBounds._4)) {
////
////      val currentWaypointLocationTemp = adjustGoalWithinBounds(currentWaypointLocation, grid, gridBounds)
////      println(s"[WARNING] Waypoint is out of grid range. Adjusting from ${currentWaypointLocation} to ${currentWaypointLocationTemp}")
////      currentWaypointLocation = currentWaypointLocationTemp
////    }
//
//    // Determine character's current location and perform A* search
//    val presentCharLocation = updatedState.presentCharLocation
//    println(s"[DEBUG] Character location: $presentCharLocation")
//    var newPath: List[Vec] = List()
//
//    if (presentCharLocation != currentWaypointLocation) {
//      // Make sure to include min_x and min_y when calling aStarSearch
//      newPath = aStarSearch(presentCharLocation, currentWaypointLocation, grid, min_x, min_y)
//      printInColor(ANSI_BLUE, f"[WAYPOINTS] Path: $newPath.")
//    } else {
//      println("[DEBUG] Current location matches the current waypoint, moving to the next waypoint.")
////      // Increment the waypoint index safely with modulo to cycle through the list
////      val nextWaypointIndex = (updatedState.currentWaypointIndex + 1) % updatedState.fixedWaypoints.size
////      updatedState = updatedState.copy(currentWaypointIndex = nextWaypointIndex)
////
////      // Retrieve the new current waypoint from the updated index
////      val currentWaypoint = updatedState.fixedWaypoints(nextWaypointIndex)
////      var currentWaypointLocation = Vec(currentWaypoint.waypointX, currentWaypoint.waypointY)
////
////      println(s"[DEBUG] New current waypoint set to: $currentWaypointLocation")
////
////      // You may choose to trigger a new path calculation here if necessary
////      // For example, you might want to calculate the path to this new waypoint
////      newPath = aStarSearch(presentCharLocation, currentWaypointLocation, grid, min_x, min_y)
////      println(s"[DEBUG] Path: ${newPath.mkString(" -> ")}")
////      updatedState = updatedState.copy(subWaypoints = newPath)
//    }
//
//    // Remove the presentCharLocation from the newPath if it exists
//    val filteredPath = newPath.filterNot(loc => loc == presentCharLocation)
//
//    println(s"[DEBUG] Path: ${filteredPath.mkString(" -> ")}")
//    println(s"[DEBUG] Char loc: $presentCharLocation")
//    println(s"[DEBUG] Waypoint loc: $currentWaypointLocation")
//
//
//    if (presentCharLocation != currentWaypointLocation) {
//      printGrid(grid, gridBounds, filteredPath, updatedState.presentCharLocation, currentWaypointLocation)
//      // Locations are different, update state accordingly
//      updatedState.copy(
//        subWaypoints = filteredPath,
//        gridBoundsState = gridBounds,
//        gridState = grid,
//        currentWaypointLocation = currentWaypointLocation,
//        presentCharLocation = presentCharLocation
//      )
//    } else {
//      println(s"[DEBUG] presentCharLocation &  Char loc are the same.")
//      updatedState
//    }
//  }
//  // Function to convert game coordinates to a single string identifier and find screen coordinates from JSON
//  def gameToScreenCoordinatesByTileId(gameX: Int, gameY: Int, gameZ: Int, json: JsValue): Option[(Int, Int)] = {
//    val tileId = s"${gameX}${gameY}${gameZ}"
//
//    // Extracting the mapPanelLoc part of the JSON
//    val mapPanelLoc = (json \ "screenInfo" \ "mapPanelLoc").as[JsObject]
//
//    // Finding the matching tile by id
//    mapPanelLoc.values.find {
//      case JsObject(obj) if obj.apply("id").asOpt[String].contains(tileId) => true
//      case _ => false
//    }.flatMap { tileJsValue =>
//      for {
//        x <- (tileJsValue \ "x").asOpt[Int]
//        y <- (tileJsValue \ "y").asOpt[Int]
//      } yield (x, y)
//    }
//  }
//
//}
