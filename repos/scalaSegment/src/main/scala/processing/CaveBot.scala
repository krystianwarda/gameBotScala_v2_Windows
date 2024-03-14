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
    println("Performing computeCaveBotActions action.")
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
    println("Performing executeWhenNoMonstersOnScreen.")



    val startTime = System.nanoTime()

    var actions = initialActions
    var logs = initialLogs
    var updatedState = initialState

    // Modified to correctly handle mutable state update
    if (!updatedState.waypointsLoaded) {
      updatedState = loadingWaypointsFromSettings(settings, updatedState)
    }


    val presentCharLocationX = (json \ "characterInfo" \ "PositionX").as[Int]
    val presentCharLocationY = (json \ "characterInfo" \ "PositionY").as[Int]
    val presentCharLocationZ = (json \ "characterInfo" \ "PositionZ").as[Int]
    val presentCharLocation = Vec(presentCharLocationX, presentCharLocationY)

    println(s"Character PositionX: $presentCharLocationX, PositionY: $presentCharLocationY, PositionZ: $presentCharLocationZ")

    // Move to next waypoint if close enough and recalculate path
    val currentWaypointIndex = updatedState.currentWaypointIndex
    if (updatedState.fixedWaypoints.isDefinedAt(currentWaypointIndex)) {
      val currentWaypoint = updatedState.fixedWaypoints(currentWaypointIndex)
      if (Math.abs(currentWaypoint.waypointX - presentCharLocationX) <= 2 && Math.abs(currentWaypoint.waypointY - presentCharLocationY) <= 2) {
        // Move to the next waypoint, clear sub-waypoints, and force path recalculation
        val nextWaypointIndex = (currentWaypointIndex + 1) % updatedState.fixedWaypoints.size
        updatedState = updatedState.copy(currentWaypointIndex = nextWaypointIndex, subWaypoints = List.empty)
      }
    }

    // Recalculate path if no sub-waypoints are available
    if (updatedState.subWaypoints.isEmpty && updatedState.fixedWaypoints.isDefinedAt(updatedState.currentWaypointIndex)) {
      val newCurrentWaypoint = updatedState.fixedWaypoints(updatedState.currentWaypointIndex)
      val newWaypointLocation = Vec(newCurrentWaypoint.waypointX, newCurrentWaypoint.waypointY)
      println(s"New Waypoint PositionX: ${newCurrentWaypoint.waypointX}, PositionY: ${newCurrentWaypoint.waypointY}")

      val newPath = findPathUsingGameCoordinates(presentCharLocation, newWaypointLocation, (json \ "areaInfo" \ "tiles").as[Map[String, JsObject]])
      updatedState = updatedState.copy(subWaypoints = newPath)
    }


    println(s"New Waypoint X: ${updatedState.fixedWaypoints(updatedState.currentWaypointIndex).waypointX}, New Waypoint Y: ${updatedState.fixedWaypoints(updatedState.currentWaypointIndex).waypointY}")
    println(s"New Sub Waypoint X: ${updatedState.subWaypoints.head.x}, New Sub Waypoint Y: ${updatedState.subWaypoints.head.y}")
    println(s"Path : ${updatedState.subWaypoints}")

    // Before calculating direction, check if character is at the current sub waypoint and advance if necessary
    if (updatedState.subWaypoints.nonEmpty && presentCharLocation == updatedState.subWaypoints.head) {
      println("Character is at the current sub waypoint, advancing to the next waypoint.")
      // Remove the current waypoint from the list, effectively advancing to the next
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
      } else {
        println("No more subWaypoints to navigate.")
      }
    } else {
      println("subWaypoints list is empty.")
    }

    val endTime = System.nanoTime()
    val duration = (endTime - startTime) / 1e6d
    println(f"Processing executeWhenNoMonstersOnScreen took $duration%.3f ms")

    ((actions, logs), updatedState)
  }


  def calculateDirection(currentLocation: Vec, nextLocation: Vec, lastDirection: Option[String]): Option[String] = {
    val deltaX = nextLocation.x - currentLocation.x
    val deltaY = nextLocation.y - currentLocation.y
    println(s"DeltaX: $deltaX, DeltaY: $deltaY, LastDirection: $lastDirection")

    (deltaX.sign, deltaY.sign) match {
      case (0, 0) =>
        println("Character is already at the destination.")
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
        println(s"Calculated Direction: $decision based on deltaX: $deltaX, deltaY: $deltaY, and lastDirection: $lastDirection")
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


  def findPathUsingGameCoordinates(start: Vec, goal: Vec, tiles: Map[String, JsObject]): List[Vec] = {
    // Extract min and max values directly from tile keys
    val xs = tiles.keys.map(_.substring(0, 5).toInt)
    val ys = tiles.keys.map(_.substring(5, 10).toInt)
    val min_x = xs.min
    val min_y = ys.min
    val maxX = xs.max
    val maxY = ys.max

    println(s"Calculated min_x: $min_x, min_y: $min_y, maxX: $maxX, maxY: $maxY from tile data")

    // Convert tiles to a boolean grid representation for pathfinding
    val (grid, _) = createBooleanGrid(tiles, min_x, min_y)

    // Adjust start and goal based on the already calculated min_x and min_y
    val adjustedStart = Vec(start.x - min_x, start.y - min_y)
    val adjustedGoal = Vec(goal.x - min_x, goal.y - min_y)

    // Perform A* search on the adjusted coordinates
    val path = aStarSearch(adjustedStart, adjustedGoal, grid)

    // Adjust the path back using min_x and min_y to map back to the original coordinate system
    path.map(p => Vec(p.x + min_x, p.y + min_y))
  }

  def createBooleanGrid(tiles: Map[String, JsObject], min_x: Int, min_y: Int): (Array[Array[Boolean]], (Int, Int)) = {
    // Determine the dimensions of the grid
    val maxX = tiles.keys.map(key => key.substring(0, 5).toInt).max
    val maxY = tiles.keys.map(key => key.substring(5, 10).toInt).max
    val width = maxX - min_x + 1
    val height = maxY - min_y + 1

    println(s"Creating boolean grid with dimensions: width=$width, height=$height, maxX=$maxX, maxY=$maxY, min_x=$min_x, min_y=$min_y")

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
          println(s"Exception accessing grid position: x=$x, y=$y, width=$width, height=$height")
          throw e
      }
    }

    (grid, (min_x, min_y))
  }


  // Define Vec class outside for simplicity
  case class Vec(x: Int, y: Int) {
    def +(other: Vec): Vec = Vec(x + other.x, y + other.y)
    def manhattanDistance(other: Vec): Int = (x - other.x).abs + (y - other.y).abs
  }

  def heuristic(a: Vec, b: Vec): Int = a.manhattanDistance(b)


  def aStarSearch(start: Vec, goal: Vec, grid: Array[Array[Boolean]]): List[Vec] = {
    val frontier = mutable.PriorityQueue.empty[(Int, Vec)](Ordering.by(-_._1))
    frontier.enqueue((0, start))
    val cameFrom = mutable.Map[Vec, Vec]()
    val costSoFar = mutable.Map[Vec, Int](start -> 0)

    var goalReached = false

    while (frontier.nonEmpty && !goalReached) {
      val current = frontier.dequeue()._2

      if (current == goal) {
        goalReached = true
      } else {
        val directions = List(Vec(-1, 0), Vec(1, 0), Vec(0, -1), Vec(0, 1))

        for (direction <- directions) {
          val next = current + direction
          if (next.x >= 0 && next.x < grid(0).length && next.y >= 0 && next.y < grid.length && grid(next.y)(next.x)) {
            val newCost = costSoFar(current) + 1
            if (!costSoFar.contains(next) || newCost < costSoFar(next)) {
              costSoFar(next) = newCost
              val priority = newCost + start.manhattanDistance(goal) // Use Manhattan distance as heuristic
              frontier.enqueue((priority, next))
              cameFrom(next) = current
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
        current = cameFrom(current)
      }
      start :: path // Ensure to include the start point in the path
    } else {
      List()
    }

  }


}

