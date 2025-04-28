package processing

import keyboard.{KeyboardAction, PressKey}
import play.api.libs.json.{JsObject, JsValue}
import processing.CaveBot.Vec
import utils.{GameState, StaticGameInfo}
import utils.ProcessingUtils.{MKActions, MKTask, Step}
import utils.SettingsUtils.UISettings
import utils.consoleColorPrint.{ANSI_BLUE, printInColor}

import scala.collection.mutable
import scala.util.Try
import scala.util.chaining.scalaUtilChainingOps
import keyboard.KeyboardAction
import mouse.{LeftButtonPress, LeftButtonRelease, MoveMouse}


object CaveBotFeature {

  def run(json: JsValue, settings: UISettings, state: GameState):
  (GameState, List[MKTask]) =
    if (!settings.caveBotSettings.enabled) (state, Nil)
    else {
      val (s, maybeTask) = Steps.runFirst(json, settings, state)
      (s, maybeTask.toList)
    }

  private object Steps {
    // ordered list of steps
    val allSteps: List[Step] = List(
      WaypointLoader,
      WrongFloorSaver,
      WaypointStuckSaver,
      UpdateLocation,
      MovementToSubwaypoint
    )


    def runFirst(
                  json: JsValue,
                  settings: UISettings,
                  startState: GameState
                ): (GameState, Option[MKTask]) = {
      @annotation.tailrec
      def loop(
                remaining: List[Step],
                current: GameState
              ): (GameState, Option[MKTask]) = remaining match {
        case Nil => (current, None)
        case step :: rest =>
          step.run(current, json, settings) match {
            case Some((newState, task)) =>
              (newState, Some(task))
            case None =>
              loop(rest, current)
          }
      }

      loop(allSteps, startState)
    }
  }


  private object WaypointLoader extends Step {
    private val taskName = "WaypointLoader"

    def run(
             state:    GameState,
             json:     JsValue,
             settings: UISettings
           ): Option[(GameState, MKTask)] =

      // only load once
      Option.when(!state.caveBot.waypointsLoaded) {
        // 1) load from settings
        val loaded = loadingWaypointsFromSettings(settings, state)

        // 2) read current position
        val x = (json \ "characterInfo" \ "PositionX").as[Int]
        val y = (json \ "characterInfo" \ "PositionY").as[Int]
        val pos = Vec(x, y)
        println(s"[DEBUG] Initial character pos: ($x,$y)")

        // 3) find nearest waypoint
        val indexedDistances =
          loaded.caveBot.fixedWaypoints.zipWithIndex.map { case (wp, idx) =>
            idx -> pos.manhattanDistance(Vec(wp.waypointX, wp.waypointY))
          }
        val (closestIdx, _) = indexedDistances.minBy(_._2)


        val newCaveBotState = state.caveBot.copy(
          waypointsLoaded = true,
          currentWaypointIndex = closestIdx,
          subWaypoints         = List.empty
        )

        val newCharInfoState = state.characterInfo.copy(presentCharLocation  = pos)

        val updatedState = state.copy(
          caveBot = newCaveBotState,
          characterInfo = newCharInfoState
        )

        // 5) no mouse/keyboard actions — just a “marker” task so Steps.runFirst will stop here
        updatedState -> MKTask(taskName, MKActions.empty)
      }
  }


  def loadingWaypointsFromSettings(
                                    settings: UISettings,
                                    state:    GameState
                                  ): GameState = {
    // 1) grab the raw strings from your UISettings (e.g. Seq("walk, ground, 100,200,7", ...))
    val waypointsSeq: Seq[String] = settings.caveBotSettings.waypointsList

    // 2) parse them into WaypointInfo, filtering out any non-"walk" or malformed entries
    val waypointsInfoList: List[WaypointInfo] = waypointsSeq.flatMap { raw =>
      raw.split(", ").toList match {
        case "walk" :: placement :: xS :: yS :: zS :: Nil =>
          Try {
            WaypointInfo(
              waypointType      = "walk",
              waypointPlacement = placement,
              waypointX         = xS.toInt,
              waypointY         = yS.toInt,
              waypointZ         = zS.toInt
            )
          }.toOption.tap {
            case None => println(s"[WaypointLoader] failed to parse waypoint '$raw'")
          }
        case other =>
          println(s"[WaypointLoader] skipping non-walk or bad format: $raw")
          None
      }
    }.toList

    // 3) build the distinct list of Z-levels we’ll need to traverse
    val caveBotLevelsList: List[Int] =
      waypointsInfoList.map(_.waypointZ).distinct

    val newCaveBotState = state.caveBot.copy(
      fixedWaypoints     = waypointsInfoList,
      waypointsLoaded    = true,
      caveBotLevelsList  = caveBotLevelsList
    )
    // 4) return the new state with fixedWaypoints, flags, and levels updated
    state.copy(
      caveBot = newCaveBotState
    )
  }


  private object WrongFloorSaver extends Step {
    private val taskName = "WrongFloorSaver"
    private val levelMovementEnablersIds = List(
      414, 433, 369, 469, 1977, 1947, 1948, 386, 594
    )

    def run(
             state:    GameState,
             json:     JsValue,
             settings: UISettings
           ): Option[(GameState, MKTask)] = {
      val currentZ = (json \ "characterInfo" \ "PositionZ").as[Int]

      // only trigger when on a floor we didn’t plan for
      if (state.caveBot.caveBotLevelsList.contains(currentZ)) None
      else {
        val now = System.currentTimeMillis()

        val newCharacterInfoState = state.characterInfo.copy(lastDirection = Some(""))
        // reset walking direction immediately
        val baseState = state.copy(characterInfo = newCharacterInfoState)

        // throttle to once per second
        if (now - state.caveBot.antiOverpassDelay < 1000) {
          Some(baseState -> MKTask(taskName, MKActions(Nil, Nil)))
        } else {
          // update delay and character position
          val x = (json \ "characterInfo" \ "PositionX").as[Int]
          val y = (json \ "characterInfo" \ "PositionY").as[Int]
          val withPos = baseState.copy(
            caveBot = baseState.caveBot.copy(antiOverpassDelay = now),
            characterInfo     = baseState.characterInfo.copy(presentCharLocation = Vec(x, y))
          )

          // collect all “stairs” tiles within manhattan distance ≤5
          val tiles = (json \ "areaInfo" \ "tiles").as[Map[String, JsObject]]
          val potential: List[(String, Int)] = tiles.collect {
            case (tileId, td)
              if (td \ "items").as[Map[String, JsObject]].values.exists { item =>
                levelMovementEnablersIds.contains((item \ "id").as[Int])
              } =>
              val gx = tileId.take(5).toInt
              val gy = tileId.drop(5).take(5).toInt
              (tileId, math.abs(gx - x) + math.abs(gy - y))
          }.toList.filter(_._2 <= 5)

          // pick closest, then look up its screen coords
          val maybeScreenXY: Option[(Int, Int)] = potential
            .sortBy(_._2)
            .headOption
            .flatMap { case (tileId, _) =>
              for {
                idx       <- (tiles(tileId) \ "index").asOpt[String]
                screenObj <- (json \ "screenInfo" \ "mapPanelLoc" \ idx).asOpt[JsObject]
                sx        <- (screenObj \ "x").asOpt[Int]
                sy        <- (screenObj \ "y").asOpt[Int]
              } yield (sx, sy)
            }

          // now branch on whether we found stairs
          val (nextState, mouseActions) = maybeScreenXY match {
            case Some((sx, sy)) if withPos.caveBot.slowWalkStatus >= withPos.general.retryAttempts =>
              // final retry → click it
              val seq = List(
                MoveMouse(sx, sy),
                LeftButtonPress(sx, sy),
                LeftButtonRelease(sx, sy)
              )
              (withPos.copy(caveBot = withPos.caveBot.copy(slowWalkStatus = 0)), seq)

            case Some(_) =>
              // bump retry counter, no click yet
              (withPos.copy(
                caveBot = withPos.caveBot.copy(
                  slowWalkStatus = withPos.caveBot.slowWalkStatus + 1)
              ), Nil)

            case None =>
              // nothing found, just yield empty actions
              (withPos, Nil)
          }

          Some(
            nextState -> MKTask(
              taskName,
              MKActions(mouseActions, Nil)  // no keyboard actions here
            )
          )
        }
      }
    }
  }

  private object WaypointStuckSaver extends Step {
    private val taskName = "WaypointStuckSaver"

    override def run(
                      state:    GameState,
                      json:     JsValue,
                      settings: UISettings
                    ): Option[(GameState, MKTask)] = {
      // 1) only when not hunting
      if (state.caveBot.stateHunting != "free") return None

      // 2) only when there are no monsters in your battle list
      val monsters = (json \ "battleList").asOpt[Seq[JsValue]].getOrElse(Nil)
      if (monsters.nonEmpty) return None

      // 3) compute old vs new char position
      val x       = (json \ "characterInfo" \ "PositionX").as[Int]
      val y       = (json \ "characterInfo" \ "PositionY").as[Int]
      val newLoc  = Vec(x, y)
      val prevLoc = state.characterInfo.presentCharLocation

      // pull out caveBot sub‐state for convenience
      val cb = state.caveBot

      // 4) decide how to update antiCaveBotStuckStatus (and maybe waypoint index)
      val updatedCB = if (prevLoc == newLoc) {
        if (cb.antiCaveBotStuckStatus >= state.general.retryAttemptsVerLong) {
          // stuck for too long → pick nearest waypoint and reset counter
          printInColor(ANSI_BLUE, "[ANTI CAVEBOT STUCK] Character has been in one place for too long. Finding new waypoint")

          val (closestIdx, _) = cb.fixedWaypoints
            .zipWithIndex
            .map { case (wp, idx) =>
              idx -> newLoc.manhattanDistance(Vec(wp.waypointX, wp.waypointY))
            }
            .minBy(_._2)

          cb.copy(
            currentWaypointIndex     = closestIdx,
            subWaypoints             = List.empty,
            antiCaveBotStuckStatus   = 0
          )
        } else {
          // still bumping the stuck counter
          if (cb.antiCaveBotStuckStatus >= 5)
            printInColor(ANSI_BLUE, s"[ANTI CAVEBOT STUCK] COUNT STUCK ${cb.antiCaveBotStuckStatus}")

          cb.copy(antiCaveBotStuckStatus = cb.antiCaveBotStuckStatus + 1)
        }
      } else {
        // moved: reset counter
        cb.copy(antiCaveBotStuckStatus = 0)
      }

      // 5) assemble the new overall state
      val updatedState = state.copy(
        caveBot       = updatedCB,
        characterInfo = state.characterInfo.copy(presentCharLocation = newLoc)
      )

      // 6) always return a “marker” task (no mouse/keyboard) to block further steps this tick
      Some(updatedState -> MKTask(taskName, MKActions.empty))
    }
  }


  private object UpdateLocation extends Step {
    private val taskName = "UpdateLocation"

    override def run(
                      state:    GameState,
                      json:     JsValue,
                      settings: UISettings
                    ): Option[(GameState, MKTask)] = {
      // read current character position
      val x = (json \ "characterInfo" \ "PositionX").as[Int]
      val y = (json \ "characterInfo" \ "PositionY").as[Int]

      // shorthands
      val cb      = state.caveBot
      val idx     = cb.currentWaypointIndex
      val wps     = cb.fixedWaypoints

      // only proceed if there's actually a waypoint at this index
      if (!wps.isDefinedAt(idx)) None
      else {
        val wp = wps(idx)
        // check if within 4 tiles on both axes
        if (math.abs(wp.waypointX - x) <= 4 && math.abs(wp.waypointY - y) <= 4) {
          // compute next index, wrapping around
          val nextIdx = (idx + 1) % wps.size

          // debug log
          printInColor(
            ANSI_BLUE,
            f"[WAYPOINT PROGRESS] Character advanced to next waypoint. Advancing to next Waypoint Index: $nextIdx"
          )

          // update caveBot state
          val newCb = cb.copy(
            currentWaypointIndex   = nextIdx,
            subWaypoints           = List.empty,
            antiCaveBotStuckStatus = 0
          )

          // assemble new GameState
          val updatedState = state.copy(caveBot = newCb)

          // return a “marker” task so that no further steps run this tick
          Some(updatedState -> MKTask(taskName, MKActions.empty))
        } else None
      }
    }
  }

  private object MovementToSubwaypoint extends Step {
    private val taskName = "MovementToSubwaypoint"

    override def run(
                      state:    GameState,
                      json:     JsValue,
                      settings: UISettings
                    ): Option[(GameState, MKTask)] = {
      val cb       = state.caveBot
      val idx      = cb.currentWaypointIndex
      val fixedWps = cb.fixedWaypoints
      if (!fixedWps.isDefinedAt(idx)) return None

      val currentWp      = fixedWps(idx)
      val generatedState = generateSubwaypoints(currentWp, state, json)

      generatedState.caveBot.subWaypoints.headOption.map { nextWp =>
        val px  = (json \ "characterInfo" \ "PositionX").as[Int]
        val py  = (json \ "characterInfo" \ "PositionY").as[Int]
        val loc = Vec(px, py)

        val dirOpt = calculateDirection(loc, nextWp, generatedState.characterInfo.lastDirection)
        val updatedState = generatedState.copy(
          characterInfo = generatedState.characterInfo.copy(lastDirection = dirOpt)
        )

        // translate MoveUp/MoveDownLeft/etc. into actual arrow key presses
        val kbActions: List[KeyboardAction] = dirOpt.toList.flatMap { dir =>
          // strip "Move" prefix, e.g. "UpLeft"
          val keyPart = dir.stripPrefix("Move")
          // split diagonals into two arrows
          val keys = keyPart match {
            case "Up"         => List("Up")
            case "Down"       => List("Down")
            case "Left"       => List("Left")
            case "Right"      => List("Right")
            case "UpLeft"     => List("Up", "Left")
            case "UpRight"    => List("Up", "Right")
            case "DownLeft"   => List("Down", "Left")
            case "DownRight"  => List("Down", "Right")
            case _            => Nil
          }
          keys.map(PressKey.fromKeyString)
        }

        updatedState -> MKTask(
          taskName,
          MKActions(mouse = Nil, keyboard = kbActions)
        )
      }
    }
  }


  def generateSubwaypoints(
                            currentWaypoint: WaypointInfo,
                            state:           GameState,
                            json:            JsValue
                          ): GameState = {
    // 1) pull all tiles and compute grid bounds
    val tiles = (json \ "areaInfo" \ "tiles").as[Map[String, JsObject]]
    val xs    = tiles.keys.map(_.substring(0,5).toInt)
    val ys    = tiles.keys.map(_.substring(5,10).toInt)
    val (minX, minY, maxX, maxY) = (xs.min, ys.min, xs.max, ys.max)
    val gridBounds                = (minX, minY, maxX, maxY)

    // 2) build boolean grid, getting its offset minimums
    val (grid, (offX, offY)) = createBooleanGrid(tiles, minX, minY)

    // 3) desired waypoint position and adjust if outside grid
    val rawWpPos = Vec(currentWaypoint.waypointX, currentWaypoint.waypointY)
    val wpPos = if (
      rawWpPos.x < minX || rawWpPos.x > maxX ||
        rawWpPos.y < minY || rawWpPos.y > maxY
    ) {
      val adj = adjustGoalWithinBounds(rawWpPos, grid, gridBounds)
      printInColor(ANSI_BLUE,
        s"[WARNING] Waypoint out of grid. Adjusting from $rawWpPos to $adj")
      adj
    } else rawWpPos

    // 4) current character loc (from prior steps)
    val presentLoc = state.characterInfo.presentCharLocation

    // 5) decide whether we're already at wp — if so, advance main index
    val (nextIndex, targetPos) =
      if (presentLoc == wpPos) {
        // wrap to next waypoint
        val nextIdx = (state.caveBot.currentWaypointIndex + 1) % state.caveBot.fixedWaypoints.size
        val newWp   = state.caveBot.fixedWaypoints(nextIdx)
        val newPos  = Vec(newWp.waypointX, newWp.waypointY)
        printInColor(ANSI_BLUE,
          s"[WAYPOINTS] Reached waypoint, advancing to index $nextIdx → $newPos")
        (nextIdx, newPos)
      } else {
        // stay on the same index
        (state.caveBot.currentWaypointIndex, wpPos)
      }

    // 6) always run A* from presentLoc to targetPos
    val rawPath =
      if (presentLoc != targetPos)
        aStarSearch(presentLoc, targetPos, grid, offX, offY)
      else
        Nil

    // 7) drop the starting position from the path
    val subWaypoints = rawPath.filterNot(_ == presentLoc)

    // 8) assemble new caveBot state
    val newCaveBot = state.caveBot.copy(
      currentWaypointIndex     = nextIndex,
      subWaypoints             = subWaypoints,
      gridBoundsState          = gridBounds,
      gridState                = grid,
      currentWaypointLocation  = targetPos
    )

    // 9) return updated GameState
    state.copy(caveBot       = newCaveBot)
  }


  def createBooleanGrid(tiles: Map[String, JsObject], min_x: Int, min_y: Int): (Array[Array[Boolean]], (Int, Int)) = {
    val allMovementEnablerIds: List[Int] = StaticGameInfo.LevelMovementEnablers.AllIds

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
        val tileElevation = (tileObj \ "getElevation").asOpt[Int].getOrElse(0)
        val tileIsWalkable = (tileObj \ "isWalkable").asOpt[Boolean].getOrElse(false)
        val tileItems = (tileObj \ "items").as[JsObject]
        val hasBlockingItem = tileItems.values.exists(item =>
          allMovementEnablerIds.contains((item \ "id").as[Int])
        )

        // Consider tile non-walkable if elevation is 2, otherwise use other conditions
        if (tileElevation >= 2) {
          grid(y)(x) = false
        } else {
          grid(y)(x) = tileIsWalkable && !hasBlockingItem
        }
      } catch {
        case e: ArrayIndexOutOfBoundsException =>
          println(s"Exception accessing grid position: x=$x, y=$y, width=$width, height=$height")
          throw e
      }
    }

    //    println(s"Grid: ${grid.map(_.mkString(" ")).mkString("\n")}")
    (grid, (min_x, min_y))
  }


  def aStarSearch(start: Vec, goal: Vec, grid: Array[Array[Boolean]], min_x: Int, min_y: Int): List[Vec] = {
    println(s"Starting aStarSearch with start=$start, goal=$goal, min_x=$min_x, min_y=$min_y")

    if ((start.x - min_x) < 0 || (start.y - min_y) < 0 || (start.x - min_x) >= grid(0).length || (start.y - min_y) >= grid.length ||
      (goal.x - min_x) < 0 || (goal.y - min_y) < 0 || (goal.x - min_x) >= grid(0).length || (goal.y - min_y) >= grid.length) {
      println(s"Error: Start or goal position out of grid bounds. Start: (${start.x - min_x}, ${start.y - min_y}), Goal: (${goal.x - min_x}, ${goal.y - min_y})")
      return List()
    }

    grid(start.y - min_y)(start.x - min_x) = true
    grid(goal.y - min_y)(goal.x - min_x) = true

    val frontier = mutable.PriorityQueue.empty[(Int, Vec)](Ordering.by(-_._1))
    frontier.enqueue((0, start))
    val cameFrom = mutable.Map[Vec, Vec]()
    val costSoFar = mutable.Map[Vec, Int](start -> 0)

    while (frontier.nonEmpty) {
      val (_, current) = frontier.dequeue()

      // If the current tile is adjacent (including diagonal) to the goal, stop the search
      if (isAdjacent(current, goal)) {
        var path = List[Vec]()
        var temp = current
        while (temp != start) {
          path = temp :: path
          temp = cameFrom.getOrElse(temp, start)
        }
        println("Path found: " + (start :: path).mkString(" -> "))
        return start :: path
      }

      val directions = List(Vec(-1, 0), Vec(1, 0), Vec(0, -1), Vec(0, 1), Vec(-1, -1), Vec(1, 1), Vec(-1, 1), Vec(1, -1))
      directions.foreach { direction =>
        val next = current + direction
        if ((next.x - min_x) >= 0 && (next.x - min_x) < grid(0).length && (next.y - min_y) >= 0 && (next.y - min_y) < grid.length && grid(next.y - min_y)(next.x - min_x)) {
          val newCost = costSoFar(current) + (if (direction.x != 0 && direction.y != 0) 21 else 10)
          if (!costSoFar.contains(next) || newCost < costSoFar(next)) {
            costSoFar(next) = newCost
            val priority = newCost + heuristic(next, goal)
            frontier.enqueue((priority, next))
            cameFrom(next) = current
          }
        }
      }
    }

    println(s"Frontier contains: ${frontier.mkString(", ")}")

    println("Final Grid state:")
    grid.foreach(row => println(row.mkString(" ")))

    println("Path not found")
    List()
  }

  def calculateDirection(currentLocation: Vec, nextLocation: Vec, lastDirection: Option[String]): Option[String] = {
    println(s"Debug - Input currentLocation: $currentLocation, nextLocation: $nextLocation")

    val deltaX = nextLocation.x - currentLocation.x
    val deltaY = nextLocation.y - currentLocation.y
    println(s"Debug - Calculated DeltaX: $deltaX, DeltaY: $deltaY based on inputs")

    val chosenDirection = chooseDirection(deltaX, deltaY)
    chosenDirection match {
      case Some(direction) if lastDirection.contains(oppositeDirection(direction)) =>
        println(s"Debug - Avoiding reversal from ${oppositeDirection(direction)} to $direction")
        None
      case Some(direction) =>
        println(s"Debug - Chosen direction: $direction")
        Some(direction)
      case None =>
        println("Debug - No movement needed.")
        None
    }
  }

  def heuristic(a: Vec, b: Vec): Int = {
    Math.abs(a.x - b.x) + Math.abs(a.y - b.y)
  }

  def isAdjacent(current: Vec, goal: Vec): Boolean = {
    val dx = math.abs(current.x - goal.x)
    val dy = math.abs(current.y - goal.y)
    (dx <= 1 && dy <= 1) // If the current position is adjacent (1 tile away in any direction, including diagonals)
  }

  def oppositeDirection(direction: String): String = {
    direction match {
      case "MoveUp" => "MoveDown"
      case "MoveDown" => "MoveUp"
      case "MoveLeft" => "MoveRight"
      case "MoveRight" => "MoveLeft"
      case "MoveUpLeft" => "MoveDownRight"
      case "MoveDownLeft" => "MoveUpRight"
      case "MoveUpRight" => "MoveDownLeft"
      case "MoveDownRight" => "MoveUpLeft"
      case _ => direction
    }
  }

  def chooseDirection(deltaX: Int, deltaY: Int): Option[String] = {
    (deltaX.sign, deltaY.sign) match {
      case (0, 0) => None
      case (0, -1) => Some("MoveUp")
      case (0, 1) => Some("MoveDown")
      case (-1, 0) => Some("MoveLeft")
      case (1, 0) => Some("MoveRight")
      case (-1, -1) => Some("MoveUpLeft")
      case (-1, 1) => Some("MoveDownLeft")
      case (1, -1) => Some("MoveUpRight")
      case (1, 1) => Some("MoveDownRight")
      case _ => None // Shouldn't be reached
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




}