//package processing
//
//import mouse.FakeAction
//import play.api.libs.json.{JsValue, _}
//import utils.consoleColorPrint.{ANSI_RED, printInColor}
//import processing.CaveBot.{Vec, aStarSearch, adjustGoalWithinBounds, calculateDirection, createBooleanGrid, generateSubwaypoints, printGrid}
//import play.api.libs.json._
//import utils.SettingsUtils.UISettings
//import utils.{ProcessorState, StaticGameInfo}
//
//import java.lang.System.currentTimeMillis
//import utils.consoleColorPrint._
//
//import scala.collection.immutable.Seq
//import scala.math.abs
//
//
//
//
//object TeamHunt {
//  def computeTeamHuntActions(json: JsValue, settings: UISettings, currentState: ProcessorState): ((Seq[FakeAction], Seq[Log]), ProcessorState) = {
//    printInColor(ANSI_RED, f"[DEBUG] Performing computeTeamHuntActions action")
//    var actions: Seq[FakeAction] = Seq.empty
//    var logs: Seq[Log] = Seq.empty
//    var updatedState = currentState // Initialize updatedState
//
//    val startTime = System.nanoTime()
//
//    if (settings.teamHuntSettings.enabled) {
//
//      val presentCharLocationX = (json \ "characterInfo" \ "PositionX").as[Int]
//      val presentCharLocationY = (json \ "characterInfo" \ "PositionY").as[Int]
//      val presentCharLocationZ = (json \ "characterInfo" \ "PositionZ").as[Int]
//      val presentCharLocation = Vec(presentCharLocationX, presentCharLocationY)
//
//      updatedState = updatedState.copy(presentCharLocation = presentCharLocation, presentCharZLocation=presentCharLocationZ)
//
//      if (settings.teamHuntSettings.followBlocker) {
//        (json \ "spyLevelInfo").validate[JsObject] match {
//          case JsSuccess(spyInfo, _) =>
//            val tiles = (json \ "areaInfo" \ "tiles").as[Map[String, JsObject]]
//            val xs = tiles.keys.map(_.substring(0, 5).trim.toInt)
//            val ys = tiles.keys.map(_.substring(5, 10).trim.toInt)
//            val gridBounds = (xs.min, ys.min, xs.max, ys.max) // Properly define gridBounds here
//
//            val (grid, _) = createBooleanGrid(tiles, xs.min, ys.min) // Correct usage of createBooleanGrid
//
//            val blockerName = settings.teamHuntSettings.blockerName
//            println(s"blockerName: ${blockerName}")
//            // Try to find the blocker
//            val maybeBlocker = spyInfo.value.collectFirst {
//              case (_, jsValue) if (jsValue \ "Name").asOpt[String].contains(blockerName) =>
//                val blockerPos = Vec((jsValue \ "PositionX").as[Int], (jsValue \ "PositionY").as[Int])
//                val blockerZ = (jsValue \ "PositionZ").as[Int]
//                (blockerPos, blockerZ)
//            }
//            println(s"maybeBlocker: ${maybeBlocker}")
//            maybeBlocker match {
//              // Blocker found
//              case Some((blockerPos, blockerZ)) =>
//                if (blockerZ != currentState.presentCharZLocation) {
//                  // Blocker is on a different level, so we change levels
//                  println("Blocker is on a different level, so we change levels")
//                  val resultChangeLevel = changeLevel(
//                    blockerPos,
//                    blockerZ,
//                    presentCharLocationZ,
//                    json,
//                    tiles,
//                    actions,
//                    logs,
//                    updatedState)
//
//                  actions = resultChangeLevel._1._1
//                  logs = resultChangeLevel._1._2
//                  updatedState = resultChangeLevel._2
//
//                } else {
//                  // Blocker is on the same level, check if path is available
//                  println("Blocker is on the same level, check if path is available")
//                  updatedState = updatedState.copy(lastBlockerPos = blockerPos, lastBlockerPosZ = blockerZ)
//                  if (isPathAvailable(presentCharLocation, blockerPos, grid, gridBounds)) {
//
//                    // engaging Target
//                    val resultFollowTeamMember = followTeamMember(
//                      blockerPos,
//                      presentCharLocation,
//                      json,
//                      actions,
//                      logs,
//                      updatedState
//                    )
//
//                    actions = resultFollowTeamMember._1._1
//                    logs = resultFollowTeamMember._1._2
//                    updatedState = resultFollowTeamMember._2
//
//                  }
//                  else {
//                    println("Blocker is visable but the path is blocked. Following teammember.")
//                    findReachableTeamMember(json, settings, currentState, spyInfo, grid, gridBounds).map { teamMemberPos =>
//
//                      if (isPathAvailable(presentCharLocation, teamMemberPos, grid, gridBounds)) {
//                        // following team member
//                        val resultFollowTeamMember = followTeamMember(
//                          teamMemberPos,
//                          presentCharLocation,
//                          json,
//                          actions,
//                          logs,
//                          updatedState
//                        )
//
//                        actions = resultFollowTeamMember._1._1
//                        logs = resultFollowTeamMember._1._2
//                        updatedState = resultFollowTeamMember._2
//                      } else {
//                        logs :+= Log("team member are not reachable.")
//                      }
//                    }
//
//                  }
//                }
//
//              // No blocker found in the spy info
//              case None =>
//                logs :+= Log("Blocker not found in spy info.")
//                println("Blocker not found in spy info, looking for a team member")
//                // Blocker not reachable, find a reachable team member
//                findReachableTeamMember(json, settings, currentState, spyInfo, grid, gridBounds).map { teamMemberPos =>
//
//                  if (isPathAvailable(presentCharLocation, teamMemberPos, grid, gridBounds)) {
//                    // following team member
//                    val resultFollowTeamMember = followTeamMember(
//                      teamMemberPos,
//                      presentCharLocation,
//                      json,
//                      actions,
//                      logs,
//                      updatedState
//                    )
//
//                    actions = resultFollowTeamMember._1._1
//                    logs = resultFollowTeamMember._1._2
//                    updatedState = resultFollowTeamMember._2
//                  } else {
//                    logs :+= Log("Blocker and no team member are reachable. Go to last blocker position.")
//
//                    val blockerPos = updatedState.lastBlockerPos
//                    // engaging Target
//                    val resultFollowLastBlocerPosition = followTeamMember(
//                      blockerPos,
//                      presentCharLocation,
//                      json,
//                      actions,
//                      logs,
//                      updatedState
//                    )
//
//                    actions = resultFollowLastBlocerPosition._1._1
//                    logs = resultFollowLastBlocerPosition._1._2
//                    updatedState = resultFollowLastBlocerPosition._2
//
//                  }
//                }
//
//            }
//
//          case JsError(errors) =>
//            println(s"Error parsing spyLevelInfo: $errors")
//        }
//
//      }
//
//    }
//    val endTime = System.nanoTime()
//    val duration = (endTime - startTime) / 1e9d
//    printInColor(ANSI_GREEN, f"[INFO] Processing computeTeamHuntActions took $duration%.6f seconds")
//
//    ((actions, logs), updatedState)
//  }
//
//  def determineEffectiveTarget(json: JsValue, settings: UISettings, currentState: ProcessorState, spyInfo: JsObject, presentCharLocation: Vec, grid: Array[Array[Boolean]], gridBounds: (Int, Int, Int, Int)): Option[(Vec, Int)] = {
//    val blockerName = settings.teamHuntSettings.blockerName
//    val maybeBlocker = spyInfo.value.collectFirst {
//      case (_, jsValue) if (jsValue \ "Name").asOpt[String].contains(blockerName) &&
//        (jsValue \ "PositionZ").as[Int] == currentState.presentCharZLocation =>
//        val pos = Vec((jsValue \ "PositionX").as[Int], (jsValue \ "PositionY").as[Int])
//        val posZ = (jsValue \ "PositionZ").as[Int]
//        if (isPathAvailable(presentCharLocation, pos, grid, gridBounds)) Some((pos, posZ))
//        else None
//    }.flatten
//
//    maybeBlocker.orElse {
//      println("[DEBUG] No path to blocker, checking for reachable team members.")
//      findReachableTeamMember(json, settings, currentState, spyInfo, grid, gridBounds).map {
//        vec => (vec, currentState.presentCharZLocation)
//      }
//    }
//  }
//
//
//  def extractTeamMembers(json: JsValue, settings: UISettings, currentState: ProcessorState, spyInfo: JsObject): Seq[(String, Vec)] = {
//    // Extract the character's name from the provided JSON
//    val characterName = (json \ "characterInfo" \ "Name").as[String]
//
//    // Extract team members and filter out the current character
//    spyInfo.value.flatMap {
//      case (id, jsValue) =>
//        for {
//          name <- (jsValue \ "Name").asOpt[String]
//          if settings.teamHuntSettings.teamMembersList.contains(name) && name != characterName // Ensure the current character is excluded
//          posX <- (jsValue \ "PositionX").asOpt[Int]
//          posY <- (jsValue \ "PositionY").asOpt[Int]
//          posZ <- (jsValue \ "PositionZ").asOpt[Int] if posZ == currentState.presentCharZLocation
//        } yield (id -> Vec(posX, posY))
//    }.toSeq
//  }
//
//  def extractTeamMembersOld(settings: UISettings, currentState: ProcessorState, spyInfo: JsObject): Seq[(String, Vec)] = {
//    spyInfo.value.flatMap {
//      case (id, jsValue) =>
//        for {
//          name <- (jsValue \ "Name").asOpt[String] if settings.teamHuntSettings.teamMembersList.contains(name)
//          posX <- (jsValue \ "PositionX").asOpt[Int]
//          posY <- (jsValue \ "PositionY").asOpt[Int]
//          posZ <- (jsValue \ "PositionZ").asOpt[Int] if posZ == currentState.presentCharZLocation
//        } yield (id -> Vec(posX, posY))
//    }.toSeq
//  }
//
//  def findReachableTeamMember(
//                               json: JsValue,
//                               settings: UISettings,
//                               currentState: ProcessorState,
//                               spyInfo: JsObject,
//                               grid: Array[Array[Boolean]],
//                               gridBounds: (Int, Int, Int, Int),
//                             ): Option[Vec] = {
//
//    // Extract team members
//    val teamMembers = extractTeamMembers(json, settings, currentState, spyInfo)
//
//    // Sort team members by their distance to the lastBlockerPos
//    val sortedMembers = teamMembers.sortBy {
//      case (_, vec) => Math.hypot(vec.x - currentState.lastBlockerPos.x, vec.y - currentState.lastBlockerPos.y)
//    }
//
//    // Filter the sorted members by path availability
//    val reachableMembers = sortedMembers.filter {
//      case (_, vec) => isPathAvailable(currentState.presentCharLocation, vec, grid, gridBounds)
//    }
//
//    // Return the closest reachable member to the presentCharLocation
//    reachableMembers.minByOption {
//      case (_, vec) => Math.hypot(vec.x - currentState.presentCharLocation.x, vec.y - currentState.presentCharLocation.y)
//    }.map(_._2)
//
//  }
//
//  def findReachableTeamMemberOld(json: JsValue, settings: UISettings, currentState: ProcessorState, spyInfo: JsObject, grid: Array[Array[Boolean]], gridBounds: (Int, Int, Int, Int)): Option[Vec] = {
//    val teamMembers = extractTeamMembers(json, settings, currentState, spyInfo)
//
//    val reachableMembers = teamMembers.filter {
//      case (_, vec) => isPathAvailable(currentState.presentCharLocation, vec, grid, gridBounds)
//    }
//    reachableMembers.minByOption {
//      case (_, vec) => Math.hypot(vec.x - currentState.presentCharLocation.x, vec.y - currentState.presentCharLocation.y)
//    }.map(_._2)
//
//  }
//
//
//  // Find reachable team member if the blocker isn't found
////  def findReachableTeamMemberOld(json:JsValue, settings: UISettings, currentState: ProcessorState, spyInfo: JsObject, grid: Array[Array[Boolean]], gridBounds: (Int, Int, Int, Int)): Option[Vec] = {
////    val teamMembers = extractTeamMembers(settings, currentState, spyInfo)
////
////    val reachableMembers = teamMembers.filter {
////      case (_, vec) => isPathAvailable(currentState.presentCharLocation, vec, grid, gridBounds)
////    }
////
////    reachableMembers.minByOption {
////      case (_, vec) => Math.hypot(vec.x - currentState.presentCharLocation.x, vec.y - currentState.presentCharLocation.y)
////    }.map(_._2)
////  }
//
//
//  // Check path availability using A* search
//  def isPathAvailable(start: Vec, end: Vec, grid: Array[Array[Boolean]], gridBounds: (Int, Int, Int, Int)): Boolean = {
//    aStarSearch(start, end, grid, gridBounds._1, gridBounds._2).nonEmpty
//  }
//
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
//
//  def generateSubwaypointsToGamePosition(currentWaypointLocation: Vec, initialState: ProcessorState, json: JsValue): ProcessorState = {
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
//
//
//  def changeLevel(
//                   blockerPos: Vec,
//                   blockerPosZ: Int,
//                   presentCharLocationZ: Int,
//                   json: JsValue,
//                   tiles: Map[String, JsObject],
//                   initialActions: Seq[FakeAction],
//                   intialLogs: Seq[Log],
//                   currentState: ProcessorState
//                 ): ((Seq[FakeAction], Seq[Log]), ProcessorState) = {
//
//    var actions: Seq[FakeAction] = initialActions
//    var logs: Seq[Log] = intialLogs
//    var updatedState = currentState
//
//    val lastBlockerPosSeen: Vec = updatedState.lastBlockerPos
//    // Declare the level movement enablers lists based on whether the blocker is above or below
//    var levelMovementEnablersIdsList: List[Int] = List()
//
//    if (blockerPosZ < presentCharLocationZ) {
//      logs :+= Log("Blocker went up")
//      levelMovementEnablersIdsList = StaticGameInfo.LevelMovementEnablers.AllUpIds
//
//    } else if (blockerPosZ > presentCharLocationZ) {
//      logs :+= Log("Blocker went down")
//      levelMovementEnablersIdsList = StaticGameInfo.LevelMovementEnablers.AllDownIds
//    }
//
//
//    val potentialTiles = tiles.collect {
//      case (tileId, tileData) if {
//        // Create a list of item IDs from the items on the tile
//        val itemIds = (tileData \ "items").as[Map[String, JsObject]].values.map(itemData => (itemData \ "id").as[Int]).toList
//        // Check if any item in the tile is in the levelMovementEnablersIdsList
//        itemIds.exists(id => levelMovementEnablersIdsList.contains(id))
//      } =>
//        // Extract both the index and the items from the tileData
//        val index = (tileData \ "index").as[String]
//        val items = (tileData \ "items").as[Map[String, JsObject]] // Extract items data
//        val itemIds = items.values.map(itemData => (itemData \ "id").as[Int]).toList
//        (tileId, index, itemIds)
//    }
//
//
//    // Convert the tile's ID to coordinates and calculate distance from blockerPos
//    val sortedTiles = potentialTiles.flatMap { case (tileId, index, itemIds) =>
//      // Extract x, y, z from the tile ID
//      val tileX = tileId.take(5).toInt
//      val tileY = tileId.slice(5, 10).toInt
//      val tileZ = tileId.takeRight(2).toInt
//
//      // Only consider tiles on the same Z-level as the player is
//      if (tileZ == presentCharLocationZ) {
//        val distance = manhattanDistance(lastBlockerPosSeen.x, lastBlockerPosSeen.y, tileX, tileY)
//        Some((tileId, index, itemIds, distance)) // Include distance in the tuple
//      } else {
//        None
//      }
//    }.toList.sortBy(_._4) // Sort by distance (ascending)
//
//
//    // Retrieve screen coordinates from the index and map them, while keeping the itemIds available
//    val tileScreenCoordinates = sortedTiles.flatMap { case (tileId, index, itemIds, distance) =>
//      (json \ "screenInfo" \ "mapPanelLoc" \ index).asOpt[JsObject].map { screenData =>
//        val x = (screenData \ "x").as[Int]
//        val y = (screenData \ "y").as[Int]
//        logs :+= Log(s"[WRONG FLOOR] Tile check ID: $tileId ($x, $y) - Distance: $distance")
//
//        (tileId, x, y, itemIds) // Return the itemIds along with screen coordinates
//      }
//    }.headOption // Get the first available screen coordinate
//
//    println(s"Test tile: $tileScreenCoordinates")
//    tileScreenCoordinates match {
//      case Some((tileId, screenX, screenY, itemIds)) =>
//        logs :+= Log(s"[WRONG FLOOR] Found valid tile ($tileId) on screen at ($screenX, $screenY), Character location: (current: $presentCharLocationZ, blocker: $blockerPosZ)")
//
//        // Check if it's the first time or if sufficient time has elapsed since the last action
//        if (updatedState.chasingBlockerLevelChangeTime == 0 || (updatedState.currentTime - updatedState.chasingBlockerLevelChangeTime > updatedState.longTimeLimit)) {
//          logs :+= Log("[WRONG FLOOR] Chasing blocker.")
//
//          val ropeTileId = StaticGameInfo.LevelMovementEnablers.UpRopesIds
//          val ladderTileId = StaticGameInfo.LevelMovementEnablers.UpLadderIds
//          val restTileIds = StaticGameInfo.LevelMovementEnablers.leftClickMovement
//          // Handle the special case of using rope, check if there is exactly one item and its id is 386
//
//          println(s"Items: $itemIds")
//          if (itemIds.size == 1 && itemIds.exists(ropeTileId.contains)) {
//
//            // Step 1: Locate container and slot with itemId 3003 (Rope)
//            val containersInfo = (json \ "containersInfo").as[JsObject]
//            val ropeLocation = containersInfo.value.collectFirst {
//              case (containerName, container) if (container \ "items").as[JsObject].value.exists {
//                case (_, item) => (item \ "itemId").as[Int] == 3003
//              } => (containerName, (container \ "items").as[JsObject].value.find {
//                case (_, item) => (item \ "itemId").as[Int] == 3003
//              }.get._1)
//            }
//
//            // Step 2: Find screen location in screenInfo based on container and slot
//            ropeLocation match {
//              case Some((containerName, slot)) =>
//                val screenInfo = (json \ "screenInfo" \ "inventoryPanelLoc").as[JsObject]
//                val matchedContainer = screenInfo.keys.find(_.contains(containerName)).getOrElse("")
//
//                // Get slot number, map it to item number
//                val itemNumber = slot.replace("slot", "item")
//                val itemPosition = (screenInfo \ matchedContainer \ "contentsPanel" \ itemNumber).as[JsObject]
//                val itemScreenLocX = (itemPosition \ "x").as[Int]
//                val itemScreenLocY = (itemPosition \ "y").as[Int]
//
//                logs :+= Log(s"Rope Found in container: $containerName, slot: $slot, Screen Position: x=$itemScreenLocX, y=$itemScreenLocY")
//
//                val actionsSeq = Seq(
//                  MouseAction(itemScreenLocX, itemScreenLocY, "move"),
//                  MouseAction(itemScreenLocX, itemScreenLocY, "pressRight"),
//                  MouseAction(itemScreenLocX, itemScreenLocY, "releaseRight"),
//                  MouseAction(screenX, screenY, "move"),
//                  MouseAction(screenX, screenY, "pressLeft"),
//                  MouseAction(screenX, screenY, "releaseLeft")
//                )
//                actions = actions :+ FakeAction("useMouse", None, Some(MouseActions(actionsSeq)))
//                updatedState = updatedState.copy(chasingBlockerLevelChangeTime = updatedState.currentTime)
//
//              case None =>
//                logs :+= Log("Rope not found.")
//            }
//
//          } else if (itemIds.size > 1 && itemIds.exists(ropeTileId.contains)) {
//            logs :+= Log("Rope placed trashed.")
//
//          } else if (itemIds.exists(ladderTileId.contains)) {
//            // If any item in the tile has the ladderTileId, perform the actions
//            logs :+= Log("Clicking on ladder.")
//
//            // Define the mouse actions for interacting with the ladder
//            val actionsSeq = Seq(
//              MouseAction(screenX, screenY, "move"),
//              MouseAction(screenX, screenY, "pressRight"),
//              MouseAction(screenX, screenY, "releaseRight")
//            )
//
//            // Add the new actions to the sequence
//            actions = actions :+ FakeAction("useMouse", None, Some(MouseActions(actionsSeq)))
//
//            // Update the state to reflect the ladder interaction
//            updatedState = updatedState.copy(chasingBlockerLevelChangeTime = updatedState.currentTime)
//            StaticGameInfo.LevelMovementEnablers.UpLadderIds
//          } else if (itemIds.exists(restTileIds.contains)) {
//            logs :+= Log("Clicking on stairs.")
//            val actionsSeq = Seq(
//              MouseAction(screenX, screenY, "move"),
//              MouseAction(screenX, screenY, "pressLeft"),
//              MouseAction(screenX, screenY, "releaseLeft")
//            )
//            actions = actions :+ FakeAction("useMouse", None, Some(MouseActions(actionsSeq)))
//            updatedState = updatedState.copy(chasingBlockerLevelChangeTime = updatedState.currentTime)
//          } else {
//            println(s"Tile not matched: ${itemIds}")
//          }
//
//        } else {
//          logs :+= Log("[WRONG FLOOR] Blocker was chased recently.")
//        }
//
//      case None =>
//        logs :+= Log("[WRONG FLOOR] No valid waypoint found within range.")
//    }
//
//    ((actions, logs), updatedState)
//  }
//
//
//
//  def followTeamMember(
//                    target: Vec, // The location of the target (blocker)
//                    presentCharLocation: Vec, // The character's current location
//                    json: JsValue, // The game state JSON
//                    initialActions: Seq[FakeAction],
//                    intialLogs: Seq[Log],
//                    currentState: ProcessorState // The current state of the character
//                  ): ((Seq[FakeAction], Seq[Log]), ProcessorState) = {
//
//    var actions: Seq[FakeAction] = initialActions
//    var logs: Seq[Log] = intialLogs
//    var updatedState = currentState
//
//    // Calculate Chebyshev Distance between the character and the target
//    val chebyshevDistance = Math.max(
//      Math.abs(target.x - presentCharLocation.x),
//      Math.abs(target.y - presentCharLocation.y)
//    )
//
//    // Process the JSON to extract battle info
//    val battleInfoResult = (json \ "battleInfo").validate[Map[String, JsValue]]
//
//    battleInfoResult match {
//      case JsSuccess(battleInfo, _) =>
//
//        // Check if there are any monsters in the battle info
//        val hasMonsters = battleInfo.exists { case (_, creature) =>
//          (creature \ "IsMonster").asOpt[Boolean].getOrElse(false)
//        }
//
//        // Determine the required distance based on the presence of monsters
//        val requiredDistance = if (hasMonsters) 3 else 2
//
//        // Check if the character is too far from the blocker
//        if (chebyshevDistance > requiredDistance) {
//          // Generate subwaypoints to move closer to the blocker
//          updatedState = generateSubwaypointsToGamePosition(target, updatedState, json)
//
//          // If there are sub-waypoints, move towards the next one
//          if (updatedState.subWaypoints.nonEmpty) {
//            val nextWaypoint = updatedState.subWaypoints.head
//            val direction = calculateDirection(presentCharLocation, nextWaypoint, updatedState.lastDirection)
//            logs :+= Log(f"[DEBUG] Calculated Next Direction: $direction")
//
//            updatedState = updatedState.copy(lastDirection = direction)
//
//            direction.foreach { dir =>
//              // Add the movement action (key press) to the action sequence
//              actions = actions :+ FakeAction("pressKey", None, Some(PushTheButton(dir)))
//              logs :+= Log(s"Moving closer to the subWaypoint in direction: $dir")
//            }
//
//            // Remove the used waypoint from the state
//            updatedState = updatedState.copy(subWaypoints = updatedState.subWaypoints.tail)
//          }
//        } else {
//          logs :+= Log("[DEBUG] Too close to target, stopping pursuit.")
//
//          // eat food when close to blocker and when no monster on screen
//          val battleInfoResult = (json \ "battleInfo").validate[Map[String, JsValue]]
//          battleInfoResult match {
//            case JsSuccess(battleInfo, _) =>
//              updatedState = updatedState.copy(lastDirection = Option(""))
//              val hasMonsters = battleInfo.exists { case (_, creature) =>
//                (creature \ "IsMonster").asOpt[Boolean].getOrElse(false)
//              }
//              if (!hasMonsters) {
//                val regenerationTime = (json \ "characterInfo" \ "RegTime").as[Int]
//
//                if (regenerationTime < 400) {
//                  val tiles = (json \ "areaInfo" \ "tiles").as[Map[String, JsObject]]
//
//                  // Get character position
//                  val characterX = (json \ "characterInfo" \ "PositionX").as[Int]
//                  val characterY = (json \ "characterInfo" \ "PositionY").as[Int]
//                  val characterZ = (json \ "characterInfo" \ "PositionZ").as[Int]
//
////                  println(s"Character position: ($characterX, $characterY, $characterZ)")
//
//                  // Define movement range (1-tile distance in any direction, including diagonals)
//                  def isAdjacentTile(tileX: Int, tileY: Int, tileZ: Int, characterX: Int, characterY: Int, characterZ: Int): Boolean = {
//                    val adjacent = tileZ == characterZ &&
//                      math.abs(tileX - characterX) <= 1 &&
//                      math.abs(tileY - characterY) <= 1
////                    println(s"Checking adjacency for Tile ($tileX, $tileY, $tileZ) and Character ($characterX, $characterY, $characterZ): Result = $adjacent")
//                    adjacent
//                  }
//
//                  // Find all adjacent tiles near the character with food on top
//                  val result: List[Option[(String, Int, Int, Int)]] = tiles.toList.flatMap {
//                    case (tileId, tileData) =>
//                      // Extract tileX, tileY, tileZ from the tileId string
//                      val tileX = tileId.take(5).toInt
//                      val tileY = tileId.slice(5, 10).toInt
//                      val tileZ = tileId.takeRight(2).toInt
//
//                      // Print the extracted tile position
////                      println(s"Checking Tile ID: $tileId => Tile Position: ($tileX, $tileY, $tileZ)")
//
//                      // Check if the tile is adjacent to the character
//                      if (isAdjacentTile(tileX, tileY, tileZ, characterX, characterY, characterZ)) {
//                        // Extract topThingId as the ID of the top item
//                        val topThingId = (tileData \ "topThingId").asOpt[Int]
//
//                        // Check if topThingId is a food item
//                        topThingId match {
//                          case Some(id) if StaticGameInfo.Items.FoodsIds.contains(id) =>
////                            println(s"Food found: $id on tile $tileId")
//                            Some((tileId.toString, tileX, tileY, id)) :: Nil // Return List with Some
//                          case _ =>
////                            println(s"No food found on tile $tileId")
//                            None :: Nil // Return List with None
//                        }
//                      } else {
////                        println(s"Tile $tileId is not adjacent.")
//                        Nil // Return empty list if not adjacent
//                      }
//                  }
//
//                  // Filter out the None values from the result
//                  val foodTiles = result.flatten
//
////                  println(s"Result: $foodTiles")
//
//                  // Handle the result of finding any adjacent tile with food on top
//                  foodTiles.headOption match {
//                    case Some((tileId, tileX, tileY, foodId)) =>
////                      println(s"Food found on tile $tileId at ($tileX, $tileY): Item ID $foodId")
//                      val screenInfo = (json \ "screenInfo" \ "mapPanelLoc").as[Map[String, JsObject]]
//
//                      // Find the tile in the screenInfo section that matches the tileId
//                      val screenTileOpt = screenInfo.collectFirst {
//                        case (screenPos, screenData) if (screenData \ "id").as[String] == tileId =>
//                          val screenX = (screenData \ "x").as[Int]
//                          val screenY = (screenData \ "y").as[Int]
//                          (screenX, screenY)
//                      }
//
//                      screenTileOpt match {
//                        case Some((screenX, screenY)) =>
////                          println(s"Screen position for tile $tileId: screenX = $screenX, screenY = $screenY")
//
//                          logs :+= Log("Clicking on food.")
//                          val actionsSeq = Seq(
//                            MouseAction(screenX, screenY, "move"),
//                            MouseAction(screenX, screenY, "pressRight"),
//                            MouseAction(screenX, screenY, "releaseRight")
//                          )
//                          actions = actions :+ FakeAction("useMouse", None, Some(MouseActions(actionsSeq)))
//
//                        case None =>
////                          println(s"No screen information found for tile $tileId.")
//                      }
//
//                    case None =>
////                      println("No food found on adjacent tiles.")
//                    // Add your logic here for "food not found"
//                  }
//                }
//              }
//
//
//
//            case JsError(_) =>
//              // placeholder
//          }
//
//        }
//
//      case JsError(errors) =>
////        logs :+= Log(s"Error parsing battleInfo: $errors")
//
//    }
//    ((actions, logs), updatedState)
//  }
//
//
//  // Helper function to calculate Manhattan distance
//  def manhattanDistance(x1: Int, y1: Int, x2: Int, y2: Int): Int = {
//    abs(x1 - x2) + abs(y1 - y2)
//  }
//
//  // Define movement range (1-tile distance in any direction, including diagonals)
//  // Define movement range (1-tile distance in any direction, including diagonals)
//  def isAdjacentTile(tileX: Int, tileY: Int, tileZ: Int, characterX: Int, characterY: Int, characterZ: Int): Boolean = {
//    val adjacent = tileZ == characterZ &&
//      math.abs(tileX - characterX) <= 1 &&
//      math.abs(tileY - characterY) <= 1
//    println(s"Checking adjacency for Tile ($tileX, $tileY, $tileZ) and Character ($characterX, $characterY, $characterZ): Result = $adjacent")
//    adjacent
//  }
//
//
//}
//
//
