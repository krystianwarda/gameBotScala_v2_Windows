package processing
import scala.util.{Success, Failure} // Import only the necessary classes
import akka.pattern.ask
import akka.util.Timeout
import mouse.FakeAction
import play.api.libs.json.JsValue
import userUI.SettingsUtils
import play.api.libs.json._
import userUI.SettingsUtils.UISettings
import play.api.libs.json.{JsValue, Json}
import userUI.SettingsUtils
import userUI.SettingsUtils.UISettings
import mouse.{ActionCompleted, ActionTypes, FakeAction, ItemInfo, Mouse, MouseMoveCommand, MouseMovementSettings}
import play.api.libs.json.{JsNumber, JsObject, JsValue, Json}
import scala.util.Try
import System.currentTimeMillis
import javax.swing.JList
import scala.:+
import scala.collection.immutable.Seq
import scala.collection.mutable
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import scala.util.{Random}
import scala.util.control.Breaks.break
import utils.consoleColorPrint._
import scala.concurrent.ExecutionContext.Implicits.global

object CaveBot {
  def computeCaveBotActions(json: JsValue, settings: UISettings, currentState: ProcessorState): ((Seq[FakeAction], Seq[Log]), ProcessorState) = {
    //    println("Performing computeCaveBotActions action.")
    var actions: Seq[FakeAction] = Seq.empty
    var logs: Seq[Log] = Seq.empty
    var updatedState = currentState // Initialize updatedState
    printInColor(ANSI_RED, f"[DEBUG] computeAutoLootActions process started with status:${updatedState.stateHunting}")
    val currentTime = currentTimeMillis()

    if (settings.caveBotSettings.enabled) {


      // Safely attempt to parse battleInfo as a map
      val battleInfoResult = (json \ "battleInfo").validate[Map[String, JsValue]]

      // Initial loading of waypoints
      if (!updatedState.waypointsLoaded) {
        updatedState = loadingWaypointsFromSettings(settings, updatedState)
        val presentCharLocationX = (json \ "characterInfo" \ "PositionX").as[Int]
        val presentCharLocationY = (json \ "characterInfo" \ "PositionY").as[Int]
        val presentCharLocationZ = (json \ "characterInfo" \ "PositionZ").as[Int]
        val presentCharLocation = Vec(presentCharLocationX, presentCharLocationY)
        updatedState = updatedState.copy(presentCharLocation = presentCharLocation)
        println(s"[DEBUG] Initial character PositionX: $presentCharLocationX, PositionY: $presentCharLocationY, PositionZ: $presentCharLocationZ")

        // Find the closest waypoint to the current character location
        val (closestWaypointIndex, _) = updatedState.fixedWaypoints
          .zipWithIndex
          .map { case (waypoint, index) =>
            (index, presentCharLocation.manhattanDistance(Vec(waypoint.waypointX, waypoint.waypointY)))
          }
          .minBy(_._2) // Find the minimum by distance

        // Update the currentWaypointIndex to the index of the closest waypoint
        updatedState = updatedState.copy(currentWaypointIndex = closestWaypointIndex, subWaypoints = List.empty)
      }



      // DEBUGGING if character went level up or down by mistake
      val presentCharLocationZ = (json \ "characterInfo" \ "PositionZ").as[Int]
      if (!updatedState.caveBotLevelsList.contains(presentCharLocationZ)) {
//        printInColor(ANSI_BLUE, "[WRONG FLOOR] Character is on wrong floor.")
        updatedState = updatedState.copy(lastDirection = Option(""))
        if (currentTime - updatedState.antiOverpassDelay >= 1000) {
          updatedState = updatedState.copy(antiOverpassDelay = currentTime)

          val levelMovementEnablersIdsList: List[Int] = List(414, 369, 469, 1977, 1947, 1948)
//          printInColor(ANSI_BLUE, f"[WRONG FLOOR] Level movement enablers: $levelMovementEnablersIdsList")

          val presentCharLocationX = (json \ "characterInfo" \ "PositionX").as[Int]
          val presentCharLocationY = (json \ "characterInfo" \ "PositionY").as[Int]
          val presentCharLocation = Vec(presentCharLocationX, presentCharLocationY)
          updatedState = updatedState.copy(presentCharLocation = presentCharLocation)
//          printInColor(ANSI_BLUE, f"[WRONG FLOOR] Character position - X: $presentCharLocationX, Y: $presentCharLocationY")

          val tiles = (json \ "areaInfo" \ "tiles").as[Map[String, JsObject]]

          // Identify all tiles with a movement enabler and their distances
          val potentialTiles = tiles.collect {
            case (tileId, tileData) if (tileData \ "items").as[Map[String, JsObject]].exists {
              case (_, itemData) =>
                val itemIdInt = (itemData \ "id").as[Int]
                val containsEnabler = levelMovementEnablersIdsList.contains(itemIdInt)
//                println(s"Checking item with ID $itemIdInt: containsEnabler = $containsEnabler")
                containsEnabler
            } =>
              // Extract game coordinates directly from tileId
              val gameX = tileId.substring(0, 5).toInt
              val gameY = tileId.substring(5, 10).toInt
              val distance = Math.abs(gameX - presentCharLocationX) + Math.abs(gameY - presentCharLocationY)
//              println(s"Calculated game distance: $distance for tile at ($gameX, $gameY)")
              (tileId, distance)
          }.toList

          // Find the closest tile within range
          val nearbyEnablerTileOpt = potentialTiles.filter(_._2 <= 5).sortBy(_._2).headOption.map(_._1)

          // Fetch the screen coordinates for the nearby enabler tile
          nearbyEnablerTileOpt.flatMap { tileId =>
//            println(s"Nearby enabler tile ID: $tileId")
            // Get the index from the areaInfo to use in screenInfo lookup
            (tiles(tileId) \ "index").asOpt[String].flatMap { index =>
              (json \ "screenInfo" \ "mapPanelLoc" \ index).asOpt[JsObject].flatMap { screenData =>
                val x = (screenData \ "x").asOpt[Int]
                val y = (screenData \ "y").asOpt[Int]
                (x, y) match {
                  case (Some(x), Some(y)) =>
//                    println(s"Screen coordinates found: ($x, $y)")
                    Some(x, y)
                  case _ =>
                    println("Screen coordinates not found")
                    None
                }
              }
            }
          } match {
            case Some((stairsTileX, stairsTileY)) =>
              printInColor(ANSI_BLUE, s"[WRONG FLOOR] slowWalkStatus: ${updatedState.slowWalkStatus}, Found valid tile on screen at ($stairsTileX, $stairsTileY), Character location: ($presentCharLocationX, $presentCharLocationY)")
              if (updatedState.slowWalkStatus >= updatedState.retryAttempts) {
                printInColor(ANSI_BLUE, f"[WRONG FLOOR] Clicking on stairs at X: $stairsTileX, Y: $stairsTileY.")
                val actionsSeq = Seq(
                  MouseAction(stairsTileX, stairsTileY, "move"),
                  MouseAction(stairsTileX, stairsTileY, "pressLeft"),
                  MouseAction(stairsTileX, stairsTileY, "releaseLeft"),
                )
                actions = actions :+ FakeAction("useMouse", None, Some(MouseActions(actionsSeq)))

                updatedState = updatedState.copy(slowWalkStatus = 0)
              } else {
                printInColor(ANSI_BLUE, s"[WRONG FLOOR] Before slowWalkStatus: (${updatedState.slowWalkStatus})")
                updatedState = updatedState.copy(slowWalkStatus = updatedState.slowWalkStatus + 1)
                printInColor(ANSI_BLUE, s"[WRONG FLOOR] After slowWalkStatus: ${updatedState.slowWalkStatus}")
              }

            case None =>
              printInColor(ANSI_BLUE, "[WRONG FLOOR] No valid waypoint found within range.")
          }

        }
      } else {
        // character is on good floor  - proceeding
        battleInfoResult match {
          case JsSuccess(battleInfo, _) =>
            updatedState = updatedState.copy(lastDirection = Option(""))
            val hasMonsters = battleInfo.exists { case (_, creature) =>
              (creature \ "IsMonster").asOpt[Boolean].getOrElse(false)
            }
            if (!hasMonsters && updatedState.stateHunting == "free") {
              printInColor(ANSI_RED, f"[DEBUG] executeWhenNoMonstersOnScreen process started with status:${updatedState.stateHunting}")
              val result = executeWhenNoMonstersOnScreen(json, settings, updatedState, actions, logs)
              actions = result._1._1
              logs = result._1._2
              updatedState = result._2
            } else if  (updatedState.stateHunting == "free") {
              updatedState = updatedState.copy(lastDirection = Option(""))
              val presentCharLocationX = (json \ "characterInfo" \ "PositionX").as[Int]
              val presentCharLocationY = (json \ "characterInfo" \ "PositionY").as[Int]
              val presentCharLocation = Vec(presentCharLocationX, presentCharLocationY)
              updatedState = updatedState.copy(presentCharLocation = presentCharLocation)

              // track if character crossed a subwaypoint
              if (updatedState.subWaypoints.nonEmpty) {
                
                // Get the current subWaypoint
                val currentWaypoint = updatedState.subWaypoints.head
                // Check if character is at the current subWaypoint or needs to move towards the next
                if (Math.abs(currentWaypoint.x - presentCharLocationX) <= 1 && Math.abs(currentWaypoint.y - presentCharLocationY) <= 1) {
                  // Advance to next subWaypoint, ensuring we do not exceed the list's bounds
                  updatedState = updatedState.copy(subWaypoints = updatedState.subWaypoints.tail)
                  // Log for debugging
                  printInColor(ANSI_BLUE, f"[SUBWAYPOINT PROGRESS] Character advanced to next subWaypoint. Remaining Path: ${updatedState.subWaypoints}")
                }
              }


              // track if character crossed a waypoint
              val currentWaypointIndex = updatedState.currentWaypointIndex
              if (updatedState.fixedWaypoints.isDefinedAt(currentWaypointIndex)) {
                val currentWaypoint = updatedState.fixedWaypoints(currentWaypointIndex)
                if (Math.abs(currentWaypoint.waypointX - presentCharLocationX) <= 2 && Math.abs(currentWaypoint.waypointY - presentCharLocationY) <= 2) {
                  // Move to the next waypoint, clear sub-waypoints, and force path recalculation
                  val nextWaypointIndex = (currentWaypointIndex + 1) % updatedState.fixedWaypoints.size
                  updatedState = updatedState.copy(currentWaypointIndex = nextWaypointIndex, subWaypoints = List.empty)
                  printInColor(ANSI_BLUE, f"[WAYPOINT PROGRESS] Character advanced to next Waypoint. Advancing to next Waypoint Index: $nextWaypointIndex")
                }
              }

            }
          case JsError(_) =>
            printInColor(ANSI_RED, f"[DEBUG] case JsError with status:${updatedState.stateHunting}")
            if (updatedState.stateHunting == "free") {
              val result = executeWhenNoMonstersOnScreen(json, settings, updatedState, actions, logs)
              actions = result._1._1
              logs = result._1._2
              updatedState = result._2
            }
        }
      }
    }
    ((actions, logs), updatedState)
  }


  def executeWhenNoMonstersOnScreen(json: JsValue, settings: UISettings, initialState: ProcessorState, initialActions: Seq[FakeAction], initialLogs: Seq[Log]): ((Seq[FakeAction], Seq[Log]), ProcessorState) = {
    //    println("Performing executeWhenNoMonstersOnScreen.")

    val startTime = System.nanoTime()
    var actions = initialActions
    var logs = initialLogs
    var updatedState = initialState

    val presentCharLocationX = (json \ "characterInfo" \ "PositionX").as[Int]
    val presentCharLocationY = (json \ "characterInfo" \ "PositionY").as[Int]
    val presentCharLocationZ = (json \ "characterInfo" \ "PositionZ").as[Int]
    val presentCharLocation = Vec(presentCharLocationX, presentCharLocationY)

    // Check if the character's location is the same as the last update
    if (updatedState.presentCharLocation == presentCharLocation) {
      if (updatedState.antiCaveBotStuckStatus >= 20) {
        // Reset if the counter is 20 or more
        printInColor(ANSI_BLUE, "[ANTI CAVEBOT STUCK] Character has been in one place for too long. Finding new waypoint")

        // Find the closest waypoint
        val (closestWaypointIndex, _) = updatedState.fixedWaypoints
          .zipWithIndex
          .map { case (waypoint, index) =>
            (index, presentCharLocation.manhattanDistance(Vec(waypoint.waypointX, waypoint.waypointY)))
          }
          .minBy(_._2) // Find the minimum by distance

        // Reset the state
        updatedState = updatedState.copy(currentWaypointIndex = closestWaypointIndex, subWaypoints = List.empty, antiCaveBotStuckStatus = 0, presentCharLocation = presentCharLocation)
      } else {
        // Increment the counter if not yet reached 20
        if (updatedState.antiCaveBotStuckStatus >= 5) {
          printInColor(ANSI_BLUE, s"[ANTI CAVEBOT STUCK] COUNT STUCK ${updatedState.antiCaveBotStuckStatus}")
        }
        updatedState = updatedState.copy(antiCaveBotStuckStatus = updatedState.antiCaveBotStuckStatus + 1, presentCharLocation = presentCharLocation)
      }
    } else {
      updatedState = updatedState.copy(antiCaveBotStuckStatus = 0, presentCharLocation = presentCharLocation)
    }

    printInColor(ANSI_RED, f"[DEBUG] Character PositionX: $presentCharLocationX, PositionY: $presentCharLocationY, PositionZ: $presentCharLocationZ")



    ////////// START //////////
    // Define thresholdDistance with the specified value
    val thresholdDistance = 25

    // Calculate the distance from the current character position to the current waypoint

    var currentWaypointIndex = updatedState.currentWaypointIndex
    var currentWaypoint = updatedState.fixedWaypoints(updatedState.currentWaypointIndex)
    val distanceToCurrentWaypoint = presentCharLocation.manhattanDistance(Vec(currentWaypoint.waypointX, currentWaypoint.waypointY))


    // is waypoint within range threshold?
    if (distanceToCurrentWaypoint > thresholdDistance) {
      printInColor(ANSI_RED, f"[ABOVE THRESHOLD] FALSE - distance above threshold")
      // Check if there are alternative waypoints closer and within the threshold distance
      val possibleWaypoints = updatedState.fixedWaypoints.zipWithIndex.filter { case (waypoint, _) =>
        val distance = presentCharLocation.manhattanDistance(Vec(waypoint.waypointX, waypoint.waypointY))
        distance <= thresholdDistance
      }
      if (possibleWaypoints.nonEmpty) {
        printInColor(ANSI_RED, f"[ABOVE THRESHOLD] TRUE - There is at least one waypoint within the threshold distance")
        val (closestWaypoint, closestIndex) = possibleWaypoints.minBy { case (waypoint, _) =>
          presentCharLocation.manhattanDistance(Vec(waypoint.waypointX, waypoint.waypointY))
        }
        updatedState = updatedState.copy(currentWaypointIndex = closestIndex, subWaypoints = List.empty)
        printInColor(ANSI_RED, f"[ABOVE THRESHOLD] Using closer waypoint idx: $closestIndex, X: ${closestWaypoint.waypointX}, Y: ${closestWaypoint.waypointY}")
      } else {
        printInColor(ANSI_RED, f"[ABOVE THRESHOLD] FALSE - No closer waypoints within the threshold, so find the nearest waypoint regardless of threshold")
        val nextWaypointIndex = updatedState.fixedWaypoints.zipWithIndex
          .minByOption { case (waypoint, _) =>
            presentCharLocation.manhattanDistance(Vec(waypoint.waypointX, waypoint.waypointY))
          }
          .map(_._2)
          .getOrElse(updatedState.currentWaypointIndex) // Fallback to current waypoint index if no suitable waypoint found

        // Update the current waypoint index with the found index
        updatedState = updatedState.copy(currentWaypointIndex = nextWaypointIndex, subWaypoints = List.empty)
        currentWaypoint = updatedState.fixedWaypoints(updatedState.currentWaypointIndex)
        printInColor(ANSI_RED, f"[ABOVE THRESHOLD] Nearest waypoint is idx: $nextWaypointIndex, X: ${currentWaypoint.waypointX}, Y: ${currentWaypoint.waypointY}")
      }
      printInColor(ANSI_RED, f"[ABOVE THRESHOLD] Waypoint idx: $currentWaypointIndex, PositionX: ${currentWaypoint.waypointX}, PositionY: ${currentWaypoint.waypointY}")
    }

    // checking waypoints
    if (Math.abs(currentWaypoint.waypointX - presentCharLocationX) <= 3 && Math.abs(currentWaypoint.waypointY - presentCharLocationY) <= 3) {
      printInColor(ANSI_RED, f"[DEBUG] Move to the next waypoint, clear sub-waypoints, and force path recalculation")
      val nextWaypointIndex = (currentWaypointIndex + 1) % updatedState.fixedWaypoints.size
      updatedState = updatedState.copy(currentWaypointIndex = nextWaypointIndex, subWaypoints = List.empty)
      var currentWaypoint = updatedState.fixedWaypoints(updatedState.currentWaypointIndex)
    }

    // Checking subwaypoints
    if (updatedState.subWaypoints.nonEmpty) {
      // Define a condition to filter out subway points that are too close or exactly at the current location
      val tooCloseOrCurrentSubwaypoints = updatedState.subWaypoints.filter(subwaypoint =>
        Math.abs(subwaypoint.x - updatedState.presentCharLocation.x) <= 1 &&
          Math.abs(subwaypoint.y - updatedState.presentCharLocation.y) <= 1
      )

      // Explicitly check if the current position is in the subwaypoints and remove it
      val isCurrentLocationInSubwaypoints = updatedState.subWaypoints.exists(subwaypoint =>
        subwaypoint.x == updatedState.presentCharLocation.x && subwaypoint.y == updatedState.presentCharLocation.y
      )

      if (tooCloseOrCurrentSubwaypoints.nonEmpty || isCurrentLocationInSubwaypoints) {
        printInColor(ANSI_RED, "[DEBUG] Close or current location subwaypoints detected, they will be cleared to avoid redundancy.")
        // Filter out subway points that are either too close or exactly at the current location
        val remainingSubwaypoints = updatedState.subWaypoints.filterNot(subwaypoint =>
          Math.abs(subwaypoint.x - updatedState.presentCharLocation.x) <= 1 &&
            Math.abs(subwaypoint.y - updatedState.presentCharLocation.y) <= 1
        )
        updatedState = updatedState.copy(subWaypoints = remainingSubwaypoints)
      }
    }


    if (updatedState.subWaypoints.length < 2) {
      printInColor(ANSI_RED, f"[DEBUG] Subwaypoints are empty or few. Generating the path")
      currentWaypoint = updatedState.fixedWaypoints(updatedState.currentWaypointIndex)

      updatedState = generateSubwaypoints(currentWaypoint, updatedState, json)

    } else {
      printInColor(ANSI_RED, f"[DEBUG] Proceeding the path")
      controlPath(currentWaypoint, updatedState, json)
    }


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


    val endTime = System.nanoTime()
    val duration = (endTime - startTime) / 1e9d
    printInColor(ANSI_GREEN, f"[INFO] Processing computeCaveBotActions took $duration%.6f seconds")

    ((actions, logs), updatedState)
  }

  def manhattanDistance(pos1: Vec, pos2: Vec): Int = {
    Math.abs(pos1.x - pos2.x) + Math.abs(pos1.y - pos2.y)
  }


  def filterCloseSubwaypoints(currentLocation: Vec, waypoints: List[Vec]): List[Vec] = {
    // Exclude waypoints that are too close to the current location
    waypoints.filterNot(waypoint =>
      Math.abs(waypoint.x - currentLocation.x) <= 2 &&
        Math.abs(waypoint.y - currentLocation.y) <= 2
    )
  }


  def isOppositeDirection(currentDirection: String, newDirection: String): Boolean = {
    (currentDirection, newDirection) match {
      case ("ArrowLeft", "ArrowRight") | ("ArrowRight", "ArrowLeft") |
           ("ArrowUp", "ArrowDown") | ("ArrowDown", "ArrowUp") => true
      case _ => false
    }
  }

  def filterSubwaypointsByDirection(currentLocation: Vec, subwaypoints: List[Vec], lastDirection: Option[String]): List[Vec] = {
    subwaypoints.filter { waypoint =>
      lastDirection match {
        case Some("ArrowRight") => waypoint.x >= currentLocation.x
        case Some("ArrowLeft") => waypoint.x <= currentLocation.x
        case Some("ArrowUp") => waypoint.y <= currentLocation.y
        case Some("ArrowDown") => waypoint.y >= currentLocation.y
        case _ => true
      }
    }
  }

  def calculateDirectionlol(currentLocation: Vec, nextLocation: Vec, lastDirection: Option[String]): Option[String] = {
    // Debugging the inputs directly to ensure they're as expected
    println(s"Debug - Input currentLocation: $currentLocation, nextLocation: $nextLocation")

    val deltaX = nextLocation.x - currentLocation.x
    val deltaY = nextLocation.y - currentLocation.y
    println(s"Debug - Calculated DeltaX: $deltaX, DeltaY: $deltaY based on inputs")

    (deltaX.sign, deltaY.sign) match {
      case (0, 0) =>
        println("Debug - Matched case: Character is already at the destination.")
        None
      case (0, -1) =>
        println("Debug - Matched case: ArrowUp")
        Some("ArrowUp")
      case (0, 1) =>
        println("Debug - Matched case: ArrowDown")
        Some("ArrowDown")
      case (-1, 0) =>
        println("Debug - Matched case: ArrowLeft")
        Some("ArrowLeft")
      case (1, 0) =>
        println("Debug - Matched case: ArrowRight")
        Some("ArrowRight")
      case _ =>
        println("Debug - Matched case: Diagonal or multiple options available.")
        // This is a simplified approach to randomly choose between horizontal or vertical movement
        val random = new Random()
        val decision = if (random.nextBoolean()) {
          // Choose based on deltaX if randomly selected boolean is true
          if (deltaX < 0) "ArrowLeft" else "ArrowRight"
        } else {
          // Choose based on deltaY otherwise
          if (deltaY < 0) "ArrowUp" else "ArrowDown"
        }
        println(s"Debug - Randomly chosen direction: $decision based on DeltaX: $deltaX, DeltaY: $deltaY")
        Some(decision)
    }
  }

  def calculateDirection(currentLocation: Vec, nextLocation: Vec, lastDirection: Option[String]): Option[String] = {
    // Debugging the inputs directly to ensure they're as expected
    println(s"Debug - Input currentLocation: $currentLocation, nextLocation: $nextLocation")

    val deltaX = nextLocation.x - currentLocation.x
    val deltaY = nextLocation.y - currentLocation.y
    println(s"Debug - Calculated DeltaX: $deltaX, DeltaY: $deltaY based on inputs")

    (deltaX.sign, deltaY.sign) match {
      case (0, 0) =>
        println("Debug - Matched case: Character is already at the destination.")
        None
      case (0, -1) =>
        checkForSingleMove("ArrowUp", lastDirection, "ArrowDown")
      case (0, 1) =>
        checkForSingleMove("ArrowDown", lastDirection, "ArrowUp")
      case (-1, 0) =>
        checkForSingleMove("ArrowLeft", lastDirection, "ArrowRight")
      case (1, 0) =>
        checkForSingleMove("ArrowRight", lastDirection, "ArrowLeft")
      case _ =>
        println("Debug - Matched case: Diagonal or multiple options available.")
        // Choose direction avoiding reversal of the last direction
        lastDirection match {
          case Some("ArrowRight") if deltaX < 0 =>
            chooseDirectionBasedOnDeltaY(deltaY)
          case Some("ArrowLeft") if deltaX > 0 =>
            chooseDirectionBasedOnDeltaY(deltaY)
          case Some("ArrowDown") if deltaY < 0 =>
            chooseDirectionBasedOnDeltaX(deltaX)
          case Some("ArrowUp") if deltaY > 0 =>
            chooseDirectionBasedOnDeltaX(deltaX)
          case _ =>
            // If not reversing, choose randomly between available directions
            randomDirectionChoice(deltaX, deltaY)
        }
    }
  }


  def calculateDirectionSlow(currentLocation: Vec, nextLocation: Vec, lastDirection: Option[String]): Option[String] = {
    // Debugging the inputs directly to ensure they're as expected
//    println(s"Debug - Input currentLocation: $currentLocation, nextLocation: $nextLocation")

    val deltaX = nextLocation.x - currentLocation.x
    val deltaY = nextLocation.y - currentLocation.y
//    println(s"Debug - Calculated DeltaX: $deltaX, DeltaY: $deltaY based on inputs")

    (deltaX.sign, deltaY.sign) match {
      case (0, 0) =>
//        println("Debug - Matched case: Character is already at the destination.")
        None
      case (0, -1) =>
        checkForSingleMove("ArrowUpSingle", lastDirection, "ArrowDownSingle")
      case (0, 1) =>
        checkForSingleMove("ArrowDownSingle", lastDirection, "ArrowUpSingle")
      case (-1, 0) =>
        checkForSingleMove("ArrowLeftSingle", lastDirection, "ArrowRightSingle")
      case (1, 0) =>
        checkForSingleMove("ArrowRightSingle", lastDirection, "ArrowLeftSingle")
      case _ =>
//        println("Debug - Matched case: Diagonal or multiple options available.")
        // Choose direction avoiding reversal of the last direction
        lastDirection match {
          case Some("ArrowRightSingle") if deltaX < 0 =>
            chooseDirectionBasedOnDeltaY(deltaY)
          case Some("ArrowLeftSingle") if deltaX > 0 =>
            chooseDirectionBasedOnDeltaY(deltaY)
          case Some("ArrowDownSingle") if deltaY < 0 =>
            chooseDirectionBasedOnDeltaX(deltaX)
          case Some("ArrowUpSingle") if deltaY > 0 =>
            chooseDirectionBasedOnDeltaX(deltaX)
          case _ =>
            // If not reversing, choose randomly between available directions
            randomDirectionChoice(deltaX, deltaY)
        }
    }
  }

  def checkForSingleMove(chosenDirection: String, lastDirection: Option[String], oppositeDirection: String): Option[String] = {
    if (lastDirection.contains(oppositeDirection)) {
      println(s"Debug - Avoiding reversal from $oppositeDirection to $chosenDirection")
      None
    } else {
      println(s"Debug - Chosen single move direction: $chosenDirection")
      Some(chosenDirection)
    }
  }

  def chooseDirectionBasedOnDeltaX(deltaX: Int): Option[String] = {
    if (deltaX < 0) Some("ArrowLeft") else Some("ArrowRight")
  }

  def chooseDirectionBasedOnDeltaY(deltaY: Int): Option[String] = {
    if (deltaY < 0) Some("ArrowUp") else Some("ArrowDown")
  }

  def randomDirectionChoice(deltaX: Int, deltaY: Int): Option[String] = {
    val random = new Random()
    val decision = if (random.nextBoolean()) {
      if (deltaX < 0) "ArrowLeft" else "ArrowRight"
    } else {
      if (deltaY < 0) "ArrowUp" else "ArrowDown"
    }
    println(s"Debug - Randomly chosen direction: $decision based on DeltaX: $deltaX, DeltaY: $deltaY")
    Some(decision)
  }

  def calculateDirectionOldOldOld(currentLocation: Vec, nextLocation: Vec, lastDirection: Option[String]): Option[String] = {
    // Debugging the inputs directly to ensure they're as expected
    println(s"Debug - Input currentLocation: $currentLocation, nextLocation: $nextLocation")

    val deltaX = nextLocation.x - currentLocation.x
    val deltaY = nextLocation.y - currentLocation.y
    println(s"Debug - Calculated DeltaX: $deltaX, DeltaY: $deltaY based on inputs")

    (deltaX.sign, deltaY.sign) match {
      case (0, 0) =>
        println("Debug - Matched case: Character is already at the destination.")
        None
      case (0, -1) =>
        println("Debug - Matched case: ArrowUp")
        Some("ArrowUp")
      case (0, 1) =>
        println("Debug - Matched case: ArrowDown")
        Some("ArrowDown")
      case (-1, 0) =>
        println("Debug - Matched case: ArrowLeft")
        Some("ArrowLeft")
      case (1, 0) =>
        println("Debug - Matched case: ArrowRight")
        Some("ArrowRight")
      case _ =>
        println("Debug - Matched case: Diagonal or multiple options available.")
        // Choose direction avoiding reversal of the last direction
        lastDirection match {
          case Some("ArrowRight") if deltaX < 0 => // Avoid going left if last went right
            chooseDirectionBasedOnDeltaY(deltaY)
          case Some("ArrowLeft") if deltaX > 0 => // Avoid going right if last went left
            chooseDirectionBasedOnDeltaY(deltaY)
          case Some("ArrowDown") if deltaY < 0 => // Avoid going up if last went down
            chooseDirectionBasedOnDeltaX(deltaX)
          case Some("ArrowUp") if deltaY > 0 => // Avoid going down if last went up
            chooseDirectionBasedOnDeltaX(deltaX)
          case _ =>
            // If not reversing, choose randomly between available directions
            randomDirectionChoice(deltaX, deltaY)
        }
    }
  }



  def calculateDirectionOldOld(currentLocation: Vec, nextLocation: Vec, lastDirection: Option[String]): Option[String] = {
    val deltaX = nextLocation.x - currentLocation.x
    val deltaY = nextLocation.y - currentLocation.y
    //    println(s"DeltaX: $deltaX, DeltaY: $deltaY, LastDirection: $lastDirection")

    (deltaX.sign, deltaY.sign) match {
      case (0, 0) =>
        //        println("Character is already at the destination.")
        None
      case (0, -1) => Some("ArrowUp")
      case (0, 1) => Some("ArrowDown")
      case (-1, 0) => Some("ArrowLeft")
      case (1, 0) => Some("ArrowRight")
      case (signX, signY) =>
        // Random choice logic here might be too simplistic, consider enhancing or removing for more deterministic behavior
        val decision = if (lastDirection.exists(dir => Seq("ArrowLeft", "ArrowRight").contains(dir)) && signY != 0) {
          if (signY < 0) "ArrowUp" else "ArrowDown"
        } else if (lastDirection.exists(dir => Seq("ArrowUp", "ArrowDown").contains(dir)) && signX != 0) {
          if (signX < 0) "ArrowLeft" else "ArrowRight"
        } else {
          // Fallback to random direction if no clear choice
          val random = new Random()
          if (random.nextBoolean()) {
            if (signX < 0) "ArrowLeft" else "ArrowRight"
          } else {
            if (signY < 0) "ArrowUp" else "ArrowDown"
          }
        }
        //        println(s"Calculated Direction: $decision based on deltaX: $deltaX, deltaY: $deltaY, and lastDirection: $lastDirection")
        Some(decision)
    }
  }


  // Helper function to check if character is close to a waypoint
  def isCloseToWaypoint(charLocation: Vec, waypoint: Vec): Boolean = {
    charLocation.manhattanDistance(waypoint) <= 4
  }

  // Utility function to convert a JList model to a Scala Seq
  def jListModelToSeq(jList: JList[String]): Seq[String] = {
    val model = jList.getModel
    (0 until model.getSize).map(i => model.getElementAt(i).toString)
  }

  def loadingWaypointsFromSettings(settings: UISettings, state: ProcessorState): ProcessorState = {
    // Convert the waypoints JList model into a Scala Seq[String]
    val waypointsSeq: Seq[String] = settings.caveBotSettings.waypointsList

    // Parse the waypoints sequence to extract waypoint information
    val waypointsInfoList = waypointsSeq.flatMap { waypoint =>
      val waypointComponents = waypoint.split(", ")
      if (waypointComponents(0) == "walk") {
        try {
          val x = waypointComponents(2).toInt
          val y = waypointComponents(3).toInt
          val z = waypointComponents(4).toInt
          Some(WaypointInfo(waypointType = waypointComponents(0), waypointX = x, waypointY = y, waypointZ = z, waypointPlacement = waypointComponents(1)))
        } catch {
          case e: Exception =>
            println(s"Error parsing waypoint: $waypoint, error: ${e.getMessage}")
            None
        }
      } else None
    }.toList

    // Extract Z values from waypointsInfoList and update state
    val caveBotLevelsList = waypointsInfoList.map(_.waypointZ).distinct
    state.copy(fixedWaypoints = waypointsInfoList, waypointsLoaded = true, caveBotLevelsList = caveBotLevelsList)
  }

  def findPathUsingGameCoordinates(start: Vec, goal: Vec, grid: Array[Array[Boolean]], gridBounds: (Int, Int, Int, Int)): List[Vec] = {
    val (min_x, min_y, _, _) = gridBounds

    // Adjust start and goal based on the input min_x and min_y to fit grid-relative coordinates
//    val adjustedStart = Vec(start.x - min_x, start.y - min_y)
//    val adjustedGoal = Vec(goal.x - min_x, goal.y - min_y)

    // Perform A* search on the adjusted coordinates
    val path = aStarSearch(start, goal, grid, min_x, min_y)

    // Adjust the path back to the original coordinate system and remove the first element if it matches the start position
    val adjustedPath = path.map(p => Vec(p.x + min_x, p.y + min_y))

    // Check if the first element of the path is the start position and remove it if so
    // This is done because the start position is the current character position
    if (adjustedPath.headOption.contains(start)) {
      adjustedPath.tail
    } else {
      adjustedPath
    }
  }



  def adjustGoalWithinBounds(goal: Vec, grid: Array[Array[Boolean]], gridBounds: (Int, Int, Int, Int)): Vec = {
    val (min_x, min_y, maxX, maxY) = gridBounds

    // Ensure goal is within the grid bounds
    var adjustedX = Math.max(min_x, Math.min(goal.x, maxX))
    var adjustedY = Math.max(min_y, Math.min(goal.y, maxY))

    // Function to check if a tile is within bounds and walkable
    def isWalkable(x: Int, y: Int): Boolean =
      x >= min_x && x < maxX && y >= min_y && y < maxY && grid(y - min_y)(x - min_x)

    // Start from the goal and expand search area until a walkable tile is found
    var radius = 1
    var foundWalkable = isWalkable(adjustedX, adjustedY)
    while (!foundWalkable && radius < Math.max(maxX - min_x, maxY - min_y)) {
      for (dx <- -radius to radius if !foundWalkable; dy <- -radius to radius if !foundWalkable) {
        val newX = adjustedX + dx
        val newY = adjustedY + dy
        if (isWalkable(newX, newY)) {
          adjustedX = newX
          adjustedY = newY
          foundWalkable = true
        }
      }
      radius += 1
    }

    Vec(adjustedX, adjustedY)
  }



  def createBooleanGrid(tiles: Map[String, JsObject], min_x: Int, min_y: Int): (Array[Array[Boolean]], (Int, Int)) = {
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
        grid(y)(x) = (tileObj \ "isWalkable").asOpt[Boolean].getOrElse(false)
      } catch {
        case e: ArrayIndexOutOfBoundsException =>
          println(s"Exception accessing grid position: x=$x, y=$y, width=$width, height=$height")
          throw e
      }
    }

    (grid, (min_x, min_y))
  }


  def printGrid(grid: Array[Array[Boolean]], gridBounds: (Int, Int, Int, Int), path: List[Vec], charPos: Vec, waypointPos: Vec): Unit = {
    val (min_x, min_y, maxX, maxY) = gridBounds

    // ANSI escape codes for colors
    val red = "\u001B[31m" // Non-walkable
    val green = "\u001B[32m" // Walkable
    val gold = "\u001B[33m" // Character position
    val pink = "\u001B[35m" // Path
    val lightBlue = "\u001B[34m" // Waypoint
    val reset = "\u001B[0m" // Reset to default

    // Calculate offset for mapping game coordinates to grid indices
    val offsetX = min_x
    val offsetY = min_y
    val width = maxX - min_x + 1
    val height = maxY - min_y + 1

    // Ensure the grid dimensions match expected size based on bounds
    require(grid.length >= height && grid(0).length >= width, "Grid size does not match the provided bounds.")

    for (y <- 0 until height) {
      for (x <- 0 until width) {
        // Translate grid indices back to game coordinates
        val gameX = x + offsetX
        val gameY = y + offsetY
        val cellVec = Vec(gameX, gameY)

        val symbol = (cellVec, path.contains(cellVec), cellVec == charPos, cellVec == waypointPos, grid(y)(x)) match {
          case (_, _, true, _, _) => s"${gold}[P]$reset"
          case (_, _, _, true, _) => s"${lightBlue}[T]$reset"
          case (_, true, _, _, _) => s"${pink}[W]$reset"
          case (_, _, _, _, true) => s"${green}[O]$reset"
          case _ => s"${red}[X]$reset"
        }

        print(symbol)
      }
      println() // New line after each row
    }
  }


  // Define Vec class outside for simplicity
  case class Vec(x: Int, y: Int) {
    def +(other: Vec): Vec = Vec(x + other.x, y + other.y)
    def manhattanDistance(other: Vec): Int = (x - other.x).abs + (y - other.y).abs
  }

  def heuristic(a: Vec, b: Vec): Int = a.manhattanDistance(b)




  def updateCharacterPositionAndCheckStagnation(currentPosition: Vec, updatedState: ProcessorState): ProcessorState = {
    updatedState.lastPosition match {
      case Some(lastPos) if lastPos == currentPosition =>
        // Character has not moved, increment the stagnant counter
        updatedState.copy(positionStagnantCount = updatedState.positionStagnantCount + 1)
      case _ =>
        // Character has moved or this is the first position check, reset the counter and update position
        updatedState.copy(lastPosition = Some(currentPosition), positionStagnantCount = 0)
    }
  }

  def aStarSearch(start: Vec, goal: Vec, grid: Array[Array[Boolean]], min_x: Int, min_y: Int): List[Vec] = {
    println(s"Starting aStarSearch with start=$start, goal=$goal")

    // Adjust start and goal coordinates within the grid index without changing their actual values
    val offsetX = min_x
    val offsetY = min_y

    // Check if start and goal are within grid bounds using adjusted coordinates
    if ((start.x - offsetX) < 0 || (start.y - offsetY) < 0 || (start.x - offsetX) >= grid(0).length || (start.y - offsetY) >= grid.length ||
      (goal.x - offsetX) < 0 || (goal.y - offsetY) < 0 || (goal.x - offsetX) >= grid(0).length || (goal.y - offsetY) >= grid.length) {
      println("Error: Start or goal position out of grid bounds!")
      return List()
    }

    val frontier = mutable.PriorityQueue.empty[(Int, Vec)](Ordering.by(-_._1))
    frontier.enqueue((0, start))
    val cameFrom = mutable.Map[Vec, Vec]()
    val costSoFar = mutable.Map[Vec, Int](start -> 0)

    var goalReached = false

    while (frontier.nonEmpty && !goalReached) {
      val (_, current) = frontier.dequeue()
//      println(s"Dequeued: $current")

      if (current == goal) {
        goalReached = true
      } else {
        val directions = List(Vec(-1, 0), Vec(1, 0), Vec(0, -1), Vec(0, 1))

        for (direction <- directions) {
          val next = current + direction
          // Adjust the check within the grid using offsets
          if ((next.x - offsetX) >= 0 && (next.x - offsetX) < grid(0).length && (next.y - offsetY) >= 0 && (next.y - offsetY) < grid.length) {
            if (grid(next.y - offsetY)(next.x - offsetX)) {
              val newCost = costSoFar(current) + 1
              if (!costSoFar.contains(next) || newCost < costSoFar(next)) {
                costSoFar(next) = newCost
                val priority = newCost + heuristic(next, goal)
                frontier.enqueue((priority, next))
                cameFrom(next) = current
//                println(s"Enqueued: $next with priority $priority")
              }
            }
          }
        }
      }
    }

    if (goalReached) {
      var path = List[Vec]()
      var current = goal
      while (current != start) {
        path = current :: path
        current = cameFrom.getOrElse(current, start)
//        println(s"Path building: $current")
      }
      start :: path
    } else {
      println("Path not found")
      List()
    }
  }

  def generateSubwaypoints(currentWaypoint: WaypointInfo, initialState: ProcessorState, json: JsValue): ProcessorState = {
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
    var currentWaypointLocation = Vec(currentWaypoint.waypointX, currentWaypoint.waypointY)
    println(s"[DEBUG] Current Waypoint: $currentWaypointLocation")

    // Adjust waypoint location if out of grid range
    if ((currentWaypointLocation.x < gridBounds._1 || currentWaypointLocation.x > gridBounds._3 ||
      currentWaypointLocation.y < gridBounds._2 || currentWaypointLocation.y > gridBounds._4)) {

      val currentWaypointLocationTemp = adjustGoalWithinBounds(currentWaypointLocation, grid, gridBounds)
      println(s"[WARNING] Waypoint is out of grid range. Adjusting from ${currentWaypointLocation} to ${currentWaypointLocationTemp}")
      currentWaypointLocation = currentWaypointLocationTemp
    }

    // Determine character's current location and perform A* search
    val presentCharLocation = updatedState.presentCharLocation
    println(s"[DEBUG] Character location: $presentCharLocation")
    var newPath: List[Vec] = List()

    if (presentCharLocation != currentWaypointLocation) {
      // Make sure to include min_x and min_y when calling aStarSearch
      newPath = aStarSearch(presentCharLocation, currentWaypointLocation, grid, min_x, min_y)
    } else {
      println("[DEBUG] Current location matches the current waypoint, moving to the next waypoint.")
      // Increment the waypoint index safely with modulo to cycle through the list
      val nextWaypointIndex = (updatedState.currentWaypointIndex + 1) % updatedState.fixedWaypoints.size
      updatedState = updatedState.copy(currentWaypointIndex = nextWaypointIndex)

      // Retrieve the new current waypoint from the updated index
      val currentWaypoint = updatedState.fixedWaypoints(nextWaypointIndex)
      var currentWaypointLocation = Vec(currentWaypoint.waypointX, currentWaypoint.waypointY)

      println(s"[DEBUG] New current waypoint set to: $currentWaypointLocation")

      // You may choose to trigger a new path calculation here if necessary
      // For example, you might want to calculate the path to this new waypoint
      newPath = aStarSearch(presentCharLocation, currentWaypointLocation, grid, min_x, min_y)
      println(s"[DEBUG] Path: ${newPath.mkString(" -> ")}")
      updatedState = updatedState.copy(subWaypoints = newPath)
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


  def generateSubwaypointsOld(currentWaypoint: WaypointInfo, initialState: ProcessorState, json: JsValue): ProcessorState = {
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
    var currentWaypointLocation = Vec(currentWaypoint.waypointX, currentWaypoint.waypointY)
    println(s"[DEBUG] Current Waypoint: $currentWaypointLocation")

    // Adjust waypoint location if out of grid range
    if ((currentWaypointLocation.x < gridBounds._1 || currentWaypointLocation.x > gridBounds._3 ||
      currentWaypointLocation.y < gridBounds._2 || currentWaypointLocation.y > gridBounds._4)) {
      println("[WARNING] Waypoint is out of grid range. Adjusting...")
      currentWaypointLocation = adjustGoalWithinBounds(currentWaypointLocation, grid, gridBounds)
    }

    // Determine character's current location and perform A* search
    val presentCharLocation = updatedState.presentCharLocation
    println(s"[DEBUG] Character location: $presentCharLocation")

    // Make sure to include min_x and min_y when calling aStarSearch
    val newPath = aStarSearch(presentCharLocation, currentWaypointLocation, grid, min_x, min_y)

    println(s"[DEBUG] Path: ${newPath.mkString(" -> ")}")
    println(s"[DEBUG] Char loc: $presentCharLocation")
    println(s"[DEBUG] Waypoint loc: $currentWaypointLocation")
    printGrid(grid, gridBounds, newPath, updatedState.presentCharLocation, currentWaypointLocation)

    updatedState.copy(
      subWaypoints = newPath,
      gridBoundsState = gridBounds,
      gridState = grid,
      currentWaypointLocation = currentWaypointLocation,
      presentCharLocation = presentCharLocation
    )
  }

  def controlPath(currentWaypoint: WaypointInfo, initialState: ProcessorState, json: JsValue): Unit = {
    // Parse tiles to determine the grid bounds and create a boolean grid
    val tiles = (json \ "areaInfo" \ "tiles").as[Map[String, JsObject]]
    val xs = tiles.keys.map(_.substring(0, 5).trim.toInt)
    val ys = tiles.keys.map(_.substring(5, 10).trim.toInt)
    val gridBounds = (xs.min, ys.min, xs.max, ys.max)

    val (grid, (min_x, min_y)) = createBooleanGrid(tiles, xs.min, ys.min)

    // Determine current waypoint location
    var currentWaypointLocation = Vec(currentWaypoint.waypointX, currentWaypoint.waypointY)

    // Determine character's current location, but do not calculate the path
    val presentCharLocation = initialState.presentCharLocation

    // Instead of finding a path, prepare a dummy path list for visual demonstration
    val presentPath = initialState.subWaypoints // This would typically show an empty path or a preset static path for illustration

    printGrid(grid, gridBounds, presentPath, presentCharLocation, currentWaypointLocation)
  }



  implicit val timeout: Timeout = Timeout(1000.milliseconds)  // Set a timeout for 300 milliseconds
}