package processing

import akka.pattern.ask
import akka.util.Timeout
import main.scala.MainApp.subwaypointGeneratorActorRef
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

import System.currentTimeMillis
import javax.swing.JList
import scala.:+
import scala.collection.immutable.Seq
import scala.collection.mutable
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import scala.util.Random
import scala.util.control.Breaks.break


object CaveBot {
  def computeCaveBotActions(json: JsValue, settings: UISettings, currentState: ProcessorState): ((Seq[FakeAction], Seq[Log]), ProcessorState) = {
    //    println("Performing computeCaveBotActions action.")
    var actions: Seq[FakeAction] = Seq.empty
    var logs: Seq[Log] = Seq.empty
    var updatedState = currentState // Initialize updatedState


    // Determine the next waypoint index and update if needed
    //    val nextWaypointIndex = (currentState.currentWaypointIndex + 1) % settings.caveBotSettings.waypoints.length


    if (settings.caveBotSettings.enabled && updatedState.stateHunting == "free") {


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
        println(s"Initial character PositionX: $presentCharLocationX, PositionY: $presentCharLocationY, PositionZ: $presentCharLocationZ")

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

      val currentTime = currentTimeMillis()


      // DEBUGING if character went level up or down by mistake
      val presentCharLocationZ = (json \ "characterInfo" \ "PositionZ").as[Int]
//      println(s"Debug: Looking for character positionZ: $presentCharLocationZ in ${updatedState.caveBotLevelsList}")
      if (!updatedState.caveBotLevelsList.contains(presentCharLocationZ)) {
        updatedState = updatedState.copy(lastDirection = Option(""))
        if (currentTime - updatedState.antiOverpassDelay >= 1000) {
          updatedState = updatedState.copy(antiOverpassDelay = currentTime)


          val levelMovementEnablersIdsList: List[Int] = List(414, 1977)
          println(s"Debug: Level movement enablers: $levelMovementEnablersIdsList")

          val presentCharLocationX = (json \ "characterInfo" \ "PositionX").as[Int]
          val presentCharLocationY = (json \ "characterInfo" \ "PositionY").as[Int]
          val presentCharLocation = Vec(presentCharLocationX, presentCharLocationY)
          updatedState = updatedState.copy(presentCharLocation = presentCharLocation)
          println(s"Debug: Character position - X: $presentCharLocationX, Y: $presentCharLocationY")

          val tiles = (json \ "areaInfo" \ "tiles").as[Map[String, JsObject]]
          println(s"Debug: Number of tiles loaded: ${tiles.size}")

          val currentWaypointLocationOpt = tiles.collectFirst {
            case (key, value) if (value \ "items").validate[Map[String, Int]].asOpt.exists(items =>
              items.values.exists(id => levelMovementEnablersIdsList.contains(id))) =>
              // Extracting x and y from the key considering the first 5 digits for x and the next 5 for y
              val x = key.take(5).toInt
              val y = key.drop(5).take(5).toInt
              println(s"Debug: Found matching item in tile - X: $x, Y: $y, Key: $key")
              Vec(x, y)
          }

          currentWaypointLocationOpt match {
            case Some(currentWaypointLocation) =>
              println(s"Found current waypoint location: $currentWaypointLocation")

              val xs = tiles.keys.map(_.substring(0, 5).toInt)
              val ys = tiles.keys.map(_.substring(5, 10).toInt)
              val min_x = xs.min
              val min_y = ys.min
              val maxX = xs.max
              val maxY = ys.max

              val gridBounds = (min_x, min_y, maxX, maxY)
              val (grid, _) = createBooleanGrid(tiles, min_x, min_y)

              // Before calling findPathUsingGameCoordinates, ensure the waypoint is within bounds
              val adjustedWaypointLocation = if (currentWaypointLocation.x < min_x || currentWaypointLocation.x > maxX ||
                currentWaypointLocation.y < min_y || currentWaypointLocation.y > maxY) {

                adjustGoalWithinBounds(currentWaypointLocation, grid, gridBounds)
              } else {
                currentWaypointLocation
              }

              val newPath = findPathUsingGameCoordinates(presentCharLocation, adjustedWaypointLocation, grid, gridBounds)
              updatedState = updatedState.copy(subWaypoints = newPath, lastDirection = None)

              val nextWaypoint = updatedState.subWaypoints.head
              val direction = calculateDirection(presentCharLocation, nextWaypoint, updatedState.lastDirection)
              updatedState = updatedState.copy(lastDirection = direction)
              println(s"Direction ${direction}")
              printGrid(grid, gridBounds, newPath, presentCharLocation, currentWaypointLocation)

              direction.foreach { dir =>
                actions = actions :+ FakeAction("pressKey", None, Some(PushTheButton(dir)))
                logs :+= Log(s"Moving closer to the subWaypoint in direction: $dir")
              }
            case None =>
              println("No valid waypoint found based on level movement enablers.")
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
            if (!hasMonsters) {
              val result = executeWhenNoMonstersOnScreen(json, settings, updatedState, actions, logs)
              actions = result._1._1
              logs = result._1._2
              updatedState = result._2
            } else {
              updatedState = updatedState.copy(lastDirection = Option(""))
              println("Skipping actions due to presence of monsters.")
              val presentCharLocationX = (json \ "characterInfo" \ "PositionX").as[Int]
              val presentCharLocationY = (json \ "characterInfo" \ "PositionY").as[Int]
              val presentCharLocation = Vec(presentCharLocationX, presentCharLocationY)
              updatedState = updatedState.copy(presentCharLocation = presentCharLocation)

              // track if character crossed a subwaypoint
              if (updatedState.subWaypoints.nonEmpty) {
                // Check if character is at the current subWaypoint or needs to move towards the next
                if (isCloseToWaypoint(presentCharLocation, updatedState.subWaypoints.head)) {
                  // Advance to next subWaypoint, ensuring we do not exceed the list's bounds
                  updatedState = updatedState.copy(subWaypoints = updatedState.subWaypoints.tail)
                  // Log for debugging
                  println(s"Character advanced to next subWaypoint. Remaining Path: ${updatedState.subWaypoints}")
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
                  println(s"Character advanced to next Waypoint. Advancing to next Waypoint Index: $nextWaypointIndex")
                }
              }

            }
          case JsError(_) =>
            val result = executeWhenNoMonstersOnScreen(json, settings, updatedState, actions, logs)
            actions = result._1._1
            logs = result._1._2
            updatedState = result._2
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
    updatedState = updatedState.copy(presentCharLocation = presentCharLocation)

    println(s"Character PositionX: $presentCharLocationX, PositionY: $presentCharLocationY, PositionZ: $presentCharLocationZ")

    updatedState = updateCharacterPositionAndCheckStagnation(presentCharLocation, updatedState)

    if (updatedState.positionStagnantCount > 20) {
      // Reset waypointsLoaded to false to force a reload of waypoints
      updatedState = updatedState.copy(lastDirection = Option(""))
      updatedState = updatedState.copy(waypointsLoaded = false, positionStagnantCount = 0)
      // Potentially log this event or take additional recovery actions
      println("Character has been stagnant for too long, resetting waypoints...")
    }


    ////////// START //////////
    // Define thresholdDistance with the specified value
    val thresholdDistance = 35

    // Calculate the distance from the current character position to the current waypoint

    var currentWaypointIndex = updatedState.currentWaypointIndex
    var currentWaypoint = updatedState.fixedWaypoints(updatedState.currentWaypointIndex)
    val distanceToCurrentWaypoint = presentCharLocation.manhattanDistance(Vec(currentWaypoint.waypointX, currentWaypoint.waypointY))


    // is waypoint within range threshold?
    if (distanceToCurrentWaypoint > thresholdDistance) {
      println("FALSE - distance above threshold")
      // Check if there are alternative waypoints closer and within the threshold distance
      val possibleWaypoints = updatedState.fixedWaypoints.zipWithIndex.filter { case (waypoint, _) =>
        val distance = presentCharLocation.manhattanDistance(Vec(waypoint.waypointX, waypoint.waypointY))
        distance <= thresholdDistance
      }

      if (possibleWaypoints.nonEmpty) {
        println("TRUE - There is at least one waypoint within the threshold distance")
        val (closestWaypoint, closestIndex) = possibleWaypoints.minBy { case (waypoint, _) =>
          presentCharLocation.manhattanDistance(Vec(waypoint.waypointX, waypoint.waypointY))
        }
        updatedState = updatedState.copy(currentWaypointIndex = closestIndex, subWaypoints = List.empty)
        println(s"Using closer waypoint idx: $closestIndex, X: ${closestWaypoint.waypointX}, Y: ${closestWaypoint.waypointY}")
      } else {
        println("FALSE - No closer waypoints within the threshold, so find the nearest waypoint regardless of threshold")
        val nextWaypointIndex = updatedState.fixedWaypoints.zipWithIndex
          .minByOption { case (waypoint, _) =>
            presentCharLocation.manhattanDistance(Vec(waypoint.waypointX, waypoint.waypointY))
          }
          .map(_._2)
          .getOrElse(updatedState.currentWaypointIndex) // Fallback to current waypoint index if no suitable waypoint found

        // Update the current waypoint index with the found index
        updatedState = updatedState.copy(currentWaypointIndex = nextWaypointIndex, subWaypoints = List.empty)
        currentWaypoint = updatedState.fixedWaypoints(updatedState.currentWaypointIndex)
        println(s"Nearest waypoint is idx: $nextWaypointIndex, X: ${currentWaypoint.waypointX}, Y: ${currentWaypoint.waypointY}")
      }

      var currentWaypointLocation = Vec(currentWaypoint.waypointX, currentWaypoint.waypointY)
      println(s"Waypoint idx: $currentWaypointIndex, PositionX: ${currentWaypoint.waypointX}, PositionY: ${currentWaypoint.waypointY}")

      println(s"Launching subwaypoint generator Actor")

      subwaypointGeneratorActorRef ! GenerateSubwaypoints(currentWaypoint, currentWaypointIndex, updatedState, json)

    } else {

      println(s"check if next waypoint is within 2 sqm")
      if (Math.abs(currentWaypoint.waypointX - presentCharLocationX) <= 2 && Math.abs(currentWaypoint.waypointY - presentCharLocationY) <= 2) {
        println(s"Move to the next waypoint, clear sub-waypoints, and force path recalculation")

        val nextWaypointIndex = (currentWaypointIndex + 1) % updatedState.fixedWaypoints.size
        updatedState = updatedState.copy(currentWaypointIndex = nextWaypointIndex, subWaypoints = List.empty)
        var currentWaypoint = updatedState.fixedWaypoints(updatedState.currentWaypointIndex)

      } else {
        println(s"Next waypoint is farer than 2sq, check if subwaypoints is empty")
        // Ensure we are within the bounds of the subWaypoints list
        if (updatedState.subWaypoints.size <= 2) {
          println(s"Subwaypoint list has less or equal than 2 elements")
          // generate new subwaypoints
          var currentWaypointLocation = Vec(currentWaypoint.waypointX, currentWaypoint.waypointY)
          println(s"Waypoint idx: $currentWaypointIndex, PositionX: ${currentWaypoint.waypointX}, PositionY: ${currentWaypoint.waypointY}")

          // Previous calculations to determine gridBounds
          val tiles = (json \ "areaInfo" \ "tiles").as[Map[String, JsObject]]
          val xs = tiles.keys.map(_.substring(0, 5).toInt)
          val ys = tiles.keys.map(_.substring(5, 10).toInt)
          val min_x = xs.min
          val min_y = ys.min
          val maxX = xs.max
          val maxY = ys.max


          // Check if the character's current position is outside the grid bounds
          if ((currentWaypointLocation.x < min_x || currentWaypointLocation.x > maxX ||
            currentWaypointLocation.y < min_y || currentWaypointLocation.y > maxY) && (updatedState.subWaypoints.isEmpty)) {

            // Before calling findPathUsingGameCoordinates, adjust the waypoint if it's out of bounds
            val gridBounds = (min_x, min_y, maxX, maxY)
            // Now call createBooleanGrid with the correct parameters
            val (grid, _) = createBooleanGrid(tiles, min_x, min_y)

            val newWaypointLocation = adjustGoalWithinBounds(currentWaypointLocation, grid, gridBounds)
            currentWaypointLocation = newWaypointLocation

            println(s"Waypoint is out of grid range. Temporary waypoint: $currentWaypointLocation")
          } else {
            println(s"Waypoint is in range.")
          }

          subwaypointGeneratorActorRef ! GenerateSubwaypoints(currentWaypoint, currentWaypointIndex, updatedState, json)

        } else {
          println(s"Check if character is at the current subWaypoint or needs to move towards the next")
          //
          if (isCloseToWaypoint(presentCharLocation, updatedState.subWaypoints.head) && updatedState.subWaypoints.size <= 1) {
            println(s"Advance to next subWaypoint, ensuring we do not exceed the list's bounds")
            updatedState = updatedState.copy(subWaypoints = updatedState.subWaypoints.tail)

            // Log for debugging
            println(s"Character advanced to next subWaypoint. Remaining Path: ${updatedState.subWaypoints}")
          }
        }
      }
    }


    // Ask the SubwaypointGeneratorActor for current subwaypoints
    println(s"Requesting subwaypoints.")
    val futureSubwaypoints = (subwaypointGeneratorActorRef ? GetSubwaypoints).mapTo[List[Vec]]
    val subwaypoints = Await.result(futureSubwaypoints, timeout.duration)  // This blocks until the future is resolved or timeout
    updatedState = updatedState.copy(subWaypoints = subwaypoints)
    println(s"Recivied subwaypoints.")

    if (updatedState.subWaypoints.nonEmpty) {
      println(s"Current subwaypoints before filtering: $subwaypoints")
      println(s"Last Direction when filtering: ${updatedState.lastDirection}")
      val directionallyFilteredSubwaypoints = filterSubwaypointsByDirection(presentCharLocation, subwaypoints, updatedState.lastDirection)
      val filteredSubwaypoints = filterCloseSubwaypoints(presentCharLocation, directionallyFilteredSubwaypoints)
//      val filteredSubwaypoints = filterCloseSubwaypoints(presentCharLocation, subwaypoints)
      updatedState = updatedState.copy(subWaypoints = filteredSubwaypoints)
      println(s"Filtered subwaypoints after filtering: $filteredSubwaypoints")

    }




    if (updatedState.subWaypoints.nonEmpty) {
      println(s"Making move.")
      val nextWaypoint = updatedState.subWaypoints.head
      val direction = calculateDirection(presentCharLocation, nextWaypoint, updatedState.lastDirection)

      println(s"Last Direction: ${updatedState.lastDirection}")
      println(s"Calculated Next Direction: $direction")
      println(s"Filtered Subwaypoints: ${updatedState.subWaypoints}")

      // Update lastDirection in ProcessorState after moving
      updatedState = updatedState.copy(lastDirection = direction)

      direction.foreach { dir =>
        actions = actions :+ FakeAction("pressKey", None, Some(PushTheButton(dir)))
        logs :+= Log(s"Moving closer to the subWaypoint in direction: $dir")
      }
    } else {
      println(f"subWaypoints are empty")


    }

    val endTime = System.nanoTime()
    val duration = (endTime - startTime) / 1e6d
    println(f"Processing computeCaveBotActions took $duration%.3f ms")

    ((actions, logs), updatedState)
  }

  def manhattanDistance(pos1: Vec, pos2: Vec): Int = {
    Math.abs(pos1.x - pos2.x) + Math.abs(pos1.y - pos2.y)
  }

  def filterCloseSubwaypoints(currentLocation: Vec, waypoints: List[Vec]): List[Vec] = {
    waypoints.dropWhile(waypoint => currentLocation.manhattanDistance(waypoint) <= 2)
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


  def calculateDirectionOld(currentLocation: Vec, nextLocation: Vec, lastDirection: Option[String]): Option[String] = {
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
    val adjustedStart = Vec(start.x - min_x, start.y - min_y)
    val adjustedGoal = Vec(goal.x - min_x, goal.y - min_y)

    // Perform A* search on the adjusted coordinates
    val path = aStarSearch(adjustedStart, adjustedGoal, grid)

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

  def findPathUsingGameCoordinatesOld(start: Vec, goal: Vec, grid: Array[Array[Boolean]], gridBounds: (Int, Int, Int, Int)): List[Vec] = {
    val (min_x, min_y, _, _) = gridBounds

    //    println(s"Using gridBounds for pathfinding: min_x=$min_x, min_y=$min_y")

    // Adjust start and goal based on the input min_x and min_y to fit grid-relative coordinates
    val adjustedStart = Vec(start.x - min_x, start.y - min_y)
    val adjustedGoal = Vec(goal.x - min_x, goal.y - min_y)

    //    println(s"Inputs for aStarSearch: adjustedStart=$adjustedStart, adjustedGoal=$adjustedGoal")
    // Perform A* search on the adjusted coordinates
    val path = aStarSearch(adjustedStart, adjustedGoal, grid)

    // Adjust the path back to the original coordinate system
    path.map(p => Vec(p.x + min_x, p.y + min_y))
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
    // Determine the dimensions of the grid
    val maxX = tiles.keys.map(key => key.substring(0, 5).toInt).max
    val maxY = tiles.keys.map(key => key.substring(5, 10).toInt).max
    val width = maxX - min_x + 1
    val height = maxY - min_y + 1

    //    println(s"Creating boolean grid with dimensions: width=$width, height=$height, maxX=$maxX, maxY=$maxY, min_x=$min_x, min_y=$min_y")

    // Initialize the grid
    val grid = Array.fill(height, width)(false)

    // Populate the grid based on the walkability of each tile
    tiles.foreach { case (key, tileObj) =>
      val x = key.substring(0, 5).toInt - min_x
      val y = key.substring(5, 10).toInt - min_y
      try {
        grid(y)(x) = (tileObj \ "isWalkable").asOpt[Boolean].getOrElse(false)
      } catch {
        case e: ArrayIndexOutOfBoundsException =>
          //          println(s"Exception accessing grid position: x=$x, y=$y, width=$width, height=$height")
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


  def aStarSearch(start: Vec, goal: Vec, grid: Array[Array[Boolean]]): List[Vec] = {
    //    println(s"Starting aStarSearch with start=$start, goal=$goal")

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
        //        println("Goal reached")
      } else {
        val directions = List(Vec(-1, 0), Vec(1, 0), Vec(0, -1), Vec(0, 1))

        for (direction <- directions) {
          val next = current + direction
          if (next.x >= 0 && next.x < grid(0).length && next.y >= 0 && next.y < grid.length && grid(next.y)(next.x)) {
            val newCost = costSoFar(current) + 1
            if (!costSoFar.contains(next) || newCost < costSoFar(next)) {
              costSoFar(next) = newCost
              val priority = newCost + heuristic(start, goal)
              frontier.enqueue((priority, next))
              cameFrom(next) = current
              //              println(s"Enqueued: $next with priority $priority")
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
        current = cameFrom.getOrElse(current, start) // Fallback to start if mapping is missing, to avoid infinite loop
        //        println(s"Path building: $current")
      }
      start :: path // Ensure to include the start point in the path
    } else {
      //      println("Path not found")
      List()
    }
  }

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
  implicit val timeout: Timeout = Timeout(300.milliseconds)  // Set a timeout for 300 milliseconds
}