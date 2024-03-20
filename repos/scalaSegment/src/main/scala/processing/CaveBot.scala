package processing

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

import scala.:+
import scala.collection.immutable.Seq
import scala.collection.mutable
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


    if (settings.caveBotSettings.enabled) {


//      println("caveBotSettings enabled.")

      // Safely attempt to parse battleInfo as a map
      val battleInfoResult = (json \ "battleInfo").validate[Map[String, JsValue]]

      battleInfoResult match {
        case JsSuccess(battleInfo, _) =>
          val hasMonsters = battleInfo.exists { case (_, creature) =>
            (creature \ "IsMonster").asOpt[Boolean].getOrElse(false)
          }
          if (!hasMonsters) {
            val result = executeWhenNoMonstersOnScreen(json, settings, updatedState, actions, logs)
            actions = result._1._1
            logs = result._1._2
            updatedState = result._2
          } else {
            println("Skipping actions due to presence of monsters.")
            val presentCharLocationX = (json \ "characterInfo" \ "PositionX").as[Int]
            val presentCharLocationY = (json \ "characterInfo" \ "PositionY").as[Int]
            val presentCharLocationZ = (json \ "characterInfo" \ "PositionZ").as[Int]
            val presentCharLocation = Vec(presentCharLocationX, presentCharLocationY)

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
    ((actions, logs), updatedState)
  }


  def executeWhenNoMonstersOnScreen(json: JsValue, settings: UISettings, initialState: ProcessorState, initialActions: Seq[FakeAction], initialLogs: Seq[Log]): ((Seq[FakeAction], Seq[Log]), ProcessorState) = {
//    println("Performing executeWhenNoMonstersOnScreen.")

    val startTime = System.nanoTime()

    var actions = initialActions
    var logs = initialLogs
    var updatedState = initialState

    // Modified to correctly handle mutable state update
    if (!updatedState.waypointsLoaded) {
      updatedState = loadingWaypointsFromSettings(settings, updatedState)
      // Assuming waypoints are now loaded and available
      val presentCharLocationX = (json \ "characterInfo" \ "PositionX").as[Int]
      val presentCharLocationY = (json \ "characterInfo" \ "PositionY").as[Int]
      val presentCharLocationZ = (json \ "characterInfo" \ "PositionZ").as[Int]
      val presentCharLocation = Vec(presentCharLocationX, presentCharLocationY)
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


    val presentCharLocationX = (json \ "characterInfo" \ "PositionX").as[Int]
    val presentCharLocationY = (json \ "characterInfo" \ "PositionY").as[Int]
    val presentCharLocationZ = (json \ "characterInfo" \ "PositionZ").as[Int]
    val presentCharLocation = Vec(presentCharLocationX, presentCharLocationY)


    println(s"Character PositionX: $presentCharLocationX, PositionY: $presentCharLocationY, PositionZ: $presentCharLocationZ")

    // Define thresholdDistance with the specified value
    val thresholdDistance = 35

    // Calculate the distance from the current character position to the current waypoint
    var currentWaypoint = updatedState.fixedWaypoints(updatedState.currentWaypointIndex)
    val distanceToCurrentWaypoint = presentCharLocation.manhattanDistance(Vec(currentWaypoint.waypointX, currentWaypoint.waypointY))

    // Check if the distance to the current waypoint is greater than the thresholdDistance
    // find the nearest waypoint if present is too far
    if (distanceToCurrentWaypoint > thresholdDistance) {

      val presentCharLocation = Vec(presentCharLocationX, presentCharLocationY)
      updatedState = updateCharacterPositionAndCheckStagnation(presentCharLocation, updatedState)

      if (updatedState.positionStagnantCount > 5) {
        // Reset waypointsLoaded to false to force a reload of waypoints
        updatedState = updatedState.copy(waypointsLoaded = false, positionStagnantCount = 0)
        // Potentially log this event or take additional recovery actions
        println("Character has been stagnant for too long, resetting waypoints...")
      }

      println(s"Distance to waypoint is $distanceToCurrentWaypoint higher than $thresholdDistance")
      // The section of code to execute when the distance condition is met
      val nextWaypointIndex = updatedState.fixedWaypoints.zipWithIndex
        .filter { case (waypoint, _) =>
          Math.abs(waypoint.waypointX - presentCharLocationX) > thresholdDistance ||
            Math.abs(waypoint.waypointY - presentCharLocationY) > thresholdDistance
        }
        .minByOption { case (waypoint, _) =>
          presentCharLocation.manhattanDistance(Vec(waypoint.waypointX, waypoint.waypointY))
        }
        .map(_._2)
        .getOrElse(updatedState.currentWaypointIndex) // Fallback to current waypoint index if no suitable waypoint found

      // Update the current waypoint index with the found index
      updatedState = updatedState.copy(currentWaypointIndex = nextWaypointIndex, subWaypoints = List.empty)
      var currentWaypoint = updatedState.fixedWaypoints(updatedState.currentWaypointIndex)

      println(s"Nearest waypoint is idx: $nextWaypointIndex, X: ${currentWaypoint.waypointX}, Y: ${currentWaypoint.waypointY}")
    }

    // Move to next waypoint if close enough and recalculate path
    val currentWaypointIndex = updatedState.currentWaypointIndex
    if (updatedState.fixedWaypoints.isDefinedAt(currentWaypointIndex)) {
      var currentWaypoint = updatedState.fixedWaypoints(currentWaypointIndex)
      if (Math.abs(currentWaypoint.waypointX - presentCharLocationX) <= 2 && Math.abs(currentWaypoint.waypointY - presentCharLocationY) <= 2) {
        // Move to the next waypoint, clear sub-waypoints, and force path recalculation
        val nextWaypointIndex = (currentWaypointIndex + 1) % updatedState.fixedWaypoints.size
        updatedState = updatedState.copy(currentWaypointIndex = nextWaypointIndex, subWaypoints = List.empty)
        var currentWaypoint = updatedState.fixedWaypoints(updatedState.currentWaypointIndex)
      }
    }

    // Recalculate path if no sub-waypoints are available
    if (updatedState.subWaypoints.isEmpty) {

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

      // Before calling findPathUsingGameCoordinates, adjust the waypoint if it's out of bounds
      val gridBounds = (min_x, min_y, maxX, maxY)
      // Now call createBooleanGrid with the correct parameters
      val (grid, _) = createBooleanGrid(tiles, min_x, min_y)

      // Check if the character's current position is outside the grid bounds
      if (currentWaypointLocation.x < min_x || currentWaypointLocation.x > maxX ||
        currentWaypointLocation.y < min_y || currentWaypointLocation.y > maxY) {

        val newWaypointLocation = adjustGoalWithinBounds(currentWaypointLocation, grid, gridBounds)
        currentWaypointLocation = newWaypointLocation

        println(s"Waypoint is out of grid range. Temporary waypoint: $currentWaypointLocation")
      } else {
        println(s"Waypoint is in range.")
      }
      // Use calculated min_x, min_y, maxX, maxY as inputs to findPathUsingGameCoordinates
      val newPath = findPathUsingGameCoordinates(presentCharLocation, currentWaypointLocation, grid, gridBounds)
      updatedState = updatedState.copy(subWaypoints = newPath)
      printGrid(grid, gridBounds, newPath, presentCharLocation, currentWaypointLocation)
      println(s"Path recalculated: ${updatedState.subWaypoints}")
    } else {
      println(s"Path : ${updatedState.subWaypoints}")
    }



    // Before calculating direction, check if character is at the current sub waypoint and advance if necessary
    if (updatedState.subWaypoints.nonEmpty && presentCharLocation == updatedState.subWaypoints.head) {
      println("Character is at the current sub waypoint, advancing to the next waypoint.")
      // Remove the current waypoint from the list, effectively advancing to the next
      println(s"Before updating Path : ${updatedState.subWaypoints}")
      updatedState = updatedState.copy(subWaypoints = updatedState.subWaypoints.tail)
      println(s"Updated Path : ${updatedState.subWaypoints}")
    }


    // Ensure we are within the bounds of the subWaypoints list
    if (updatedState.subWaypoints.nonEmpty) {

      // Check if character is at the current subWaypoint or needs to move towards the next
      if (isCloseToWaypoint(presentCharLocation, updatedState.subWaypoints.head)) {
        // Advance to next subWaypoint, ensuring we do not exceed the list's bounds
        updatedState = updatedState.copy(subWaypoints = updatedState.subWaypoints.tail)

        // Log for debugging
        println(s"Character advanced to next subWaypoint. Remaining Path: ${updatedState.subWaypoints}")
      }

      if (updatedState.subWaypoints.nonEmpty) {
        val nextWaypoint = updatedState.subWaypoints.head
        val direction = calculateDirection(presentCharLocation, nextWaypoint, updatedState.lastDirection)

        // Update lastDirection in ProcessorState after moving
        updatedState = updatedState.copy(lastDirection = direction)

        direction.foreach { dir =>
          actions = actions :+ FakeAction("pressKey", None, Some(PushTheButton(dir)))
          logs :+= Log(s"Moving closer to the subWaypoint in direction: $dir")
        }
      }
    }

    val endTime = System.nanoTime()
    val duration = (endTime - startTime) / 1e6d
    println(f"Processing executeWhenNoMonstersOnScreen took $duration%.3f ms")

    ((actions, logs), updatedState)
  }


  def calculateDirection(currentLocation: Vec, nextLocation: Vec, lastDirection: Option[String]): Option[String] = {
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
    charLocation.manhattanDistance(waypoint) <= 3
  }

  def loadingWaypointsFromSettings(settings: UISettings, state: ProcessorState): ProcessorState = {
    // Fetch the model from the waypointsList, which is a javax.swing.DefaultListModel
    val waypointsModel = settings.caveBotSettings.waypointsList.getModel

    // Convert the DefaultListModel to a Scala Seq[String]
    // This is done by creating a Scala sequence from the Java Enumeration provided by elements()
    val waypointsSeq: Seq[String] = (0 until waypointsModel.getSize).map(waypointsModel.getElementAt(_).toString)

    // The rest of your code for processing waypointsSeq remains the same
    val waypointsInfoList = waypointsSeq.zipWithIndex.flatMap { case (waypoint, index) =>
      val waypointString = waypoint.split(", ")
      if (waypointString(0) == "walk") {
        Some(WaypointInfo(
          waypointType = waypointString(0),
          waypointX = waypointString(2).toInt,
          waypointY = waypointString(3).toInt,
          waypointZ = waypointString(4).toInt,
          waypointPlacement = waypointString(1)
        ))
      } else None
    }.toList

    state.copy(fixedWaypoints = waypointsInfoList, waypointsLoaded = true)
  }

  def findPathUsingGameCoordinates(start: Vec, goal: Vec, grid: Array[Array[Boolean]], gridBounds: (Int, Int, Int, Int)): List[Vec] = {
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


}

