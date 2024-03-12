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
    var actions = initialActions
    var logs = initialLogs
    var updatedState = initialState
    val grid = createMatrixFromJson(json)

    // Extract tiles from JSON to calculate min_x and min_y
    val tiles = (json \ "areaInfo" \ "tiles").as[Map[String, JsObject]]

    // Initialize min_x and min_y with max possible values to ensure they get correctly updated
    var min_x = Int.MaxValue
    var min_y = Int.MaxValue

    tiles.keys.foreach { key =>
      val x = key.substring(0, 5).toInt
      val y = key.substring(5, 10).toInt
      if (x < min_x) min_x = x
      if (y < min_y) min_y = y
    }

    val presentCharLocationX = (json \ "characterInfo" \ "PositionX").as[Int]
    val presentCharLocationY = (json \ "characterInfo" \ "PositionY").as[Int]
    val presentCharLocationZ = (json \ "characterInfo" \ "PositionZ").as[Int]

    // Translate world coordinates to grid coordinates
    val charLocationGrid = Vec(presentCharLocationX - min_x, presentCharLocationY - min_y)
    // Print player position in grid coordinates for debugging
    println(s"Player grid position: $charLocationGrid")

    val currentWaypointIndex = updatedState.currentWaypointIndex
    val waypointsModel = settings.caveBotSettings.waypointsList.getModel

    if (currentWaypointIndex < waypointsModel.getSize) {
      val waypointString = waypointsModel.getElementAt(currentWaypointIndex).toString.split(", ")
      if (waypointString(0) == "walk" && waypointString(4).toInt == presentCharLocationZ) {
        val waypointX = waypointString(2).toInt
        val waypointY = waypointString(3).toInt

        val waypointLocationGrid = Vec(waypointX - min_x, waypointY - min_y)


        println(s"Waypoint grid position: $waypointLocationGrid")

        val path = findPathAndAdjustWaypoint(charLocationGrid, waypointLocationGrid, grid, Vec(0, 0), Vec(grid(0).length - 1, grid.length - 1))
        println(path)

        if (Math.abs(waypointX - presentCharLocationX) <= 2 && Math.abs(waypointY - presentCharLocationY) <= 2) {
          // Character is near the waypoint, increment waypoint index
          val nextWaypointIndex = (currentWaypointIndex + 1) % waypointsModel.getSize
          updatedState = updatedState.copy(currentWaypointIndex = nextWaypointIndex)
          logs :+= Log(s"Moving to waypoint index: $nextWaypointIndex")
        }

        if (path.length >= 2) {
          // The player's current position is the last element in the path
          val playerPosition = path.last

          // The next waypoint to move towards is the second to last element in the path
          val nextWaypoint = path(path.length - 2)

          // Calculate the deltas to determine the direction
          val deltaX = nextWaypoint.x - playerPosition.x
          val deltaY = nextWaypoint.y - playerPosition.y

          (deltaX, deltaY) match {
            case (0, -1) => actions = actions :+ FakeAction("pressKey", None, Some(PushTheButton("ArrowUp")))
            case (0, 1) => actions = actions :+ FakeAction("pressKey", None, Some(PushTheButton("ArrowDown")))
            case (-1, 0) => actions = actions :+ FakeAction("pressKey", None, Some(PushTheButton("ArrowLeft")))
            case (1, 0) => actions = actions :+ FakeAction("pressKey", None, Some(PushTheButton("ArrowRight")))
            case _ => logs :+= Log("Unexpected direction")
          }


        } else {
          println("Path is too short to calculate the next step.")
        }




      }
    }

    ((actions, logs), updatedState)
  }



  def createMatrixFromJson(json: JsValue): Array[Array[String]] = {
    // Extract the tiles dictionary from the parsed JSON data
    val tiles = (json \ "areaInfo" \ "tiles").as[Map[String, JsObject]]

    // Initialize min and max values for x and y
    var min_x = Int.MaxValue
    var min_y = Int.MaxValue
    var max_x = Int.MinValue
    var max_y = Int.MinValue

    // Find min and max x, y values to determine the matrix size
    tiles.keys.foreach { key =>
      val x = key.substring(0, 5).toInt
      val y = key.substring(5, 10).toInt

      // Update min and max values
      min_x = math.min(min_x, x)
      min_y = math.min(min_y, y)
      max_x = math.max(max_x, x)
      max_y = math.max(max_y, y)
    }

    // Calculate grid dimensions
    val gridWidth = max_x - min_x + 1
    val gridHeight = max_y - min_y + 1

    // Initialize the grid with placeholder values
    val grid: Array[Array[String]] = Array.fill(gridHeight, gridWidth)("[?]")

    // Fill the grid with walkable/unwalkable symbols
    tiles.foreach { case (key, tile) =>
      val x = key.substring(0, 5).toInt - min_x
      val y = key.substring(5, 10).toInt - min_y
      val isWalkable = (tile \ "isWalkable").as[Boolean]

      grid(y)(x) = if (isWalkable) "[O]" else "[X]"
    }

    grid
  }


  case class Vec(x: Int, y: Int) {
    def +(other: Vec): Vec = Vec(x + other.x, y + other.y)
    def manhattanDistance(other: Vec): Int = (x - other.x).abs + (y - other.y).abs
  }

  def heuristic(a: Vec, b: Vec): Int = a.manhattanDistance(b)


  def aStarSearch(start: Vec, goal: Vec, grid: Array[Array[String]], width: Int, height: Int): List[Vec] = {
    val frontier = mutable.PriorityQueue.empty[(Int, Vec)](Ordering.by(-_._1))
    frontier.enqueue((0, start))
    val cameFrom = mutable.Map[Vec, Vec]()
    val costSoFar = mutable.Map[Vec, Int](start -> 0)

//    println(s"Starting A* search from $start to $goal")

    var goalReached = false
    val directions = List(Vec(-1, 0), Vec(1, 0), Vec(0, -1), Vec(0, 1))

    while (frontier.nonEmpty && !goalReached) {
      val current = frontier.dequeue()._2

//      println(s"Exploring node $current")

      if (current == goal) {
//        println("Goal reached!")
        goalReached = true
      } else {
        for (direction <- directions) {
          val next = current + direction
          if (next.x >= 0 && next.x < width && next.y >= 0 && next.y < height && (grid(next.y)(next.x) == "[O]" || grid(next.y)(next.x) == "[?]")) {
            val newCost = costSoFar(current) + 1
            if (!costSoFar.contains(next) || newCost < costSoFar(next)) {
              costSoFar(next) = newCost
              val priority = newCost + heuristic(goal, next)
              frontier.enqueue((priority, next))
              cameFrom(next) = current

//              println(s"Node $next added to the frontier with priority $priority")
            }
          }
        }
      }
    }

    if (goalReached) {
      // Reconstruct path
      var current = goal
      var path = List[Vec]()
      while (cameFrom.contains(current)) {
        path = current :: path
        current = cameFrom.get(current).getOrElse(start) // Safely get previous node or default to start
      }
      path = start :: path // Ensure the start is included

//      println("Path reconstruction complete")
      path.reverse
    } else {
      List()
    }
  }

  def adjustPosition(pos: Vec, min: Vec, max: Vec): Vec = {
    Vec(
      x = pos.x.max(min.x).min(max.x),
      y = pos.y.max(min.y).min(max.y)
    )
  }

  def findPathAndAdjustWaypoint(charLocation: Vec, waypointLocation: Vec, grid: Array[Array[String]], minCoord: Vec, maxCoord: Vec): List[Vec] = {
    val adjustedWaypointLocation = adjustPosition(waypointLocation, minCoord, maxCoord)
    val path = aStarSearch(charLocation, adjustedWaypointLocation, grid, grid(0).length, grid.length)

    // Marking path on the grid for visualization
    path.foreach { case Vec(x, y) =>
      grid(y)(x) = "[P]"
    }
    grid(adjustedWaypointLocation.y)(adjustedWaypointLocation.x) = "[T]"

    // Print path for verification
//    println("Final Path:")
//    path.foreach { p => println(s"(${p.x}, ${p.y})") }

    path
  }

}

