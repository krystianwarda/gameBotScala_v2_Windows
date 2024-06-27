package processing

import mouse.FakeAction
import play.api.libs.json._
import userUI.SettingsUtils.UISettings
import utils.consoleColorPrint.{ANSI_RED, printInColor}
import processing.CaveBot.{Vec, aStarSearch, adjustGoalWithinBounds, calculateDirection, generateSubwaypoints, printGrid}

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
      updatedState = updatedState.copy(presentCharLocation = presentCharLocation)

      if (settings.teamHuntSettings.followBlocker) {
        val blockerName = settings.teamHuntSettings.blockerName

        // Extracting the "battleInfo" object from the root JSON
        (json \ "spyLevelInfo").validate[JsObject] match {
          case JsSuccess(battleInfo, _) =>
            // Iterating over each entry in the "battleInfo" object
            val maybeBlocker = battleInfo.value.find {
              case (_, jsValue) =>
                (jsValue \ "Name").asOpt[String].contains(blockerName)
            }

            maybeBlocker match {
              case Some((_, blockerInfo)) =>
                val blockerPosX = (blockerInfo \ "PositionX").as[Int]
                val blockerPosY = (blockerInfo \ "PositionY").as[Int]
                val blockerPosZ = (blockerInfo \ "PositionZ").as[Int]
                println(s"Blocker Position: X=$blockerPosX, Y=$blockerPosY")
                val blockerCharLocation = Vec(blockerPosX, blockerPosY)
                updatedState = updatedState.copy(lastBlockerPos = (blockerPosX, blockerPosY, blockerPosZ))
                // Check if the blocker is at the same vertical level
                if (blockerPosZ == presentCharLocationZ) {
                  println("Blocker is reachable")
                  val chebyshevDistance = Math.max(
                    Math.abs(blockerCharLocation.x - presentCharLocation.x),
                    Math.abs(blockerCharLocation.y - presentCharLocation.y)
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

                      // Check if the conditions are met to update the path and follow the blocker
                      // Adjust distances for movement based on monster presence
                      if (chebyshevDistance > requiredDistance) {
                        updatedState = generateSubwaypointsToBlocker(blockerCharLocation, updatedState, json)

                        if (updatedState.subWaypoints.nonEmpty) {
                          val nextWaypoint = updatedState.subWaypoints.head
                          val direction = calculateDirection(presentCharLocation, nextWaypoint, updatedState.lastDirection)
                          printInColor(ANSI_RED, f"[DEBUG] Calculated Next Direction: $direction")
                          updatedState = updatedState.copy(lastDirection = direction)

                          direction.foreach { dir =>
                            actions = actions :+ FakeAction("pressKey", None, Some(PushTheButton(dir)))
                            logs :+= Log(s"Moving closer to the subWaypoint in direction: $dir")
                          }
                        }
                      } else {
                        printInColor(ANSI_YELLOW, "[DEBUG] Too close to blocker, stopping pursuit.")
                      }
                  }
                } else {
                  println("Blocker changed level")


                  // Fetch tiles data from JSON
                  val tiles = (json \ "areaInfo" \ "tiles").as[Map[String, JsObject]]

                  // Define levelMovementEnablersIdsList
                  val levelMovementEnablersIdsList: List[Int] = List(
                    414, 433, 369, 469, 1977, 1947, 1948, 386, 594
                  )

                  // Corrected collection manipulation
                  val potentialTiles = tiles.collect {
                    case (tileId, tileData) if (tileData \ "items").as[Map[String, JsObject]].values.exists(itemData =>
                      levelMovementEnablersIdsList.contains((itemData \ "id").as[Int])
                    ) =>
                      (tileId, (tileData \ "index").as[String]) // Extract the index which is used to find screen coordinates
                  }

                  // Retrieve screen coordinates from the index and map them
                  val tileScreenCoordinates = potentialTiles.flatMap { case (tileId, index) =>
                    (json \ "screenInfo" \ "mapPanelLoc" \ index).asOpt[JsObject].map { screenData =>
                      val x = (screenData \ "x").as[Int]
                      val y = (screenData \ "y").as[Int]
                      printInColor(ANSI_BLUE, s"[WRONG FLOOR] Tile check ID: $tileId ($x, $y)")
                      (tileId, x, y)
                    }
                  }.headOption // Get the first available screen coordinate

                  tileScreenCoordinates match {
                    case Some((tileId, screenX, screenY)) =>
                      printInColor(ANSI_BLUE, s"[WRONG FLOOR] Found valid tile on screen at ($screenX, $screenY), Character location: ($presentCharLocationX, $presentCharLocationY)")

                      // Check if it's the first time or if sufficient time has elapsed since the last action
                      if (updatedState.chasingBlockerLevelChangeTime == 0 || (updatedState.currentTime - updatedState.chasingBlockerLevelChangeTime > updatedState.longTimeLimit)) {
                        printInColor(ANSI_BLUE, "[WRONG FLOOR] Chasing blocker.")
                        val actionsSeq = Seq(
                          MouseAction(screenX, screenY, "move"),
                          MouseAction(screenX, screenY, "pressLeft"),
                          MouseAction(screenX, screenY, "releaseLeft")
                        )
                        actions = actions :+ FakeAction("useMouse", None, Some(MouseActions(actionsSeq)))
                        updatedState = updatedState.copy(chasingBlockerLevelChangeTime = updatedState.currentTime)
                      } else {
                        printInColor(ANSI_BLUE, "[WRONG FLOOR] Blocker was chased recently.")
                      }

                    case None =>
                      printInColor(ANSI_BLUE, "[WRONG FLOOR] No valid waypoint found within range.")
                  }
                }
              case None =>
                println("Blocker not on the screen")
                val lastBlockerXPos = updatedState.lastBlockerPos._1
                val lastBlockerYPos = updatedState.lastBlockerPos._2
                val lastBlockerZPos = updatedState.lastBlockerPos._3

                // Adjusting X coordinate to ensure it's within a max distance of 7
                val adjustedBlockerXPos = if ((lastBlockerXPos - presentCharLocationX).abs > 7) {
                  if (lastBlockerXPos > presentCharLocationX) presentCharLocationX + 7 else presentCharLocationX - 7
                } else lastBlockerXPos

                // Adjusting Y coordinate to ensure it's within a max distance of 6
                val adjustedBlockerYPos = if ((lastBlockerYPos - presentCharLocationY).abs > 6) {
                  if (lastBlockerYPos > presentCharLocationY) presentCharLocationY + 6 else presentCharLocationY - 6
                } else lastBlockerYPos

                println(s"Adjusted Blocker Position to: X = $adjustedBlockerXPos, Y = $adjustedBlockerYPos, Z = $lastBlockerZPos")

                val screenCoordinates = gameToScreenCoordinatesByTileId(adjustedBlockerXPos, lastBlockerYPos, lastBlockerZPos, json)
                screenCoordinates match {
                  case Some((screenX, screenY)) =>
                    printInColor(ANSI_BLUE, s"[LAST SEEN] Found last known tile on screen at tile ${updatedState.lastBlockerPos} ($screenX, $screenY)")

                    // Check if it's the first time or if sufficient time has elapsed since the last action
                    if (updatedState.chasingBlockerLevelChangeTime == 0 || (updatedState.currentTime - updatedState.chasingBlockerLevelChangeTime > updatedState.longTimeLimit)) {
                      printInColor(ANSI_BLUE, "[LAST SEEN] Chasing last known position of blocker.")
                      val actionsSeq = Seq(
                        MouseAction(screenX, screenY, "move"),
                        MouseAction(screenX, screenY, "pressLeft"),
                        MouseAction(screenX, screenY, "releaseLeft")
                      )
                      actions = actions :+ FakeAction("useMouse", None, Some(MouseActions(actionsSeq)))
                      updatedState = updatedState.copy(chasingBlockerLevelChangeTime = updatedState.currentTime)
                    } else {
                      printInColor(ANSI_BLUE, "[LAST SEEN] Blocker was chased recently.")
                    }
                  case None =>
                    println("No screen coordinates found for the given tile ID.")
                }

            }

          case JsError(errors) =>
            println("SpyInfo is empty: " + errors)
        }

      }



    }
    val endTime = System.nanoTime()
    val duration = (endTime - startTime) / 1e9d
    printInColor(ANSI_GREEN, f"[INFO] Processing computeTeamHuntActions took $duration%.6f seconds")

    ((actions, logs), updatedState)
  }


  def createBooleanGrid(tiles: Map[String, JsObject], min_x: Int, min_y: Int): (Array[Array[Boolean]], (Int, Int)) = {
    val levelMovementEnablersIdsList: List[Int] = List(414, 433, 369, 469, 1977, 1947, 1948, 386, 594)

    val maxX = tiles.keys.map(key => key.take(5).trim.toInt).max
    val maxY = tiles.keys.map(key => key.drop(5).take(5).trim.toInt).max
    val width = maxX - min_x + 1
    val height = maxY - min_y + 1

    println(s"Creating boolean grid with dimensions: width=$width, height=$height, maxX=$maxX, maxY=$maxY, min_x=$min_x, min_y=$min_y")

    val grid = Array.fill(height, width)(false)

    tiles.foreach { case (key, tileObj) =>
      val x = key.take(5).trim.toInt - min_x
      val y = key.drop(5).take(5).trim.toInt - min_y
      try {
        val tileItems = (tileObj \ "items").as[JsObject]
        val hasBlockingItem = tileItems.values.exists(item =>
          levelMovementEnablersIdsList.contains((item \ "id").as[Int])
        )

        grid(y)(x) = (tileObj \ "isWalkable").asOpt[Boolean].getOrElse(false) && !hasBlockingItem
      } catch {
        case e: ArrayIndexOutOfBoundsException =>
          println(s"Exception accessing grid position: x=$x, y=$y, width=$width, height=$height")
          throw e
      }
    }

    (grid, (min_x, min_y))
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
  // Function to convert game coordinates to a single string identifier and find screen coordinates from JSON
  def gameToScreenCoordinatesByTileId(gameX: Int, gameY: Int, gameZ: Int, json: JsValue): Option[(Int, Int)] = {
    val tileId = s"${gameX}${gameY}${gameZ}"

    // Extracting the mapPanelLoc part of the JSON
    val mapPanelLoc = (json \ "screenInfo" \ "mapPanelLoc").as[JsObject]

    // Finding the matching tile by id
    mapPanelLoc.values.find {
      case JsObject(obj) if (obj \ "id").asOpt[String].contains(tileId) => true
      case _ => false
    }.flatMap { tileJsValue =>
      for {
        x <- (tileJsValue \ "x").asOpt[Int]
        y <- (tileJsValue \ "y").asOpt[Int]
      } yield (x, y)
    }
  }

}
