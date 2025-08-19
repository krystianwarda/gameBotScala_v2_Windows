package processing

import keyboard.{DirectionalKey, KeyboardAction, KeyboardUtils}
import play.api.libs.json.{JsObject, JsValue}
import utils.{GameState, StaticGameInfo}
import utils.ProcessingUtils.{MKActions, MKTask, NoOpTask, Step}
import utils.SettingsUtils.UISettings
import utils.consoleColorPrint.{ANSI_BLUE, ANSI_PURPLE, printInColor}

import scala.collection.mutable
import scala.util.Try
import scala.util.chaining.scalaUtilChainingOps
import mouse.{LeftButtonPress, LeftButtonRelease, MoveMouse}


object CaveBotFeature {
  import cats.syntax.all._

  def run(json: JsValue, settings: UISettings, state: GameState): (GameState, List[MKTask]) =
    (!settings.caveBotSettings.enabled).guard[Option]
      .as((state, Nil))
      .getOrElse {
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
      def loop(remaining: List[Step], current: GameState): (GameState, Option[MKTask]) =
        remaining match {
          case Nil => (current, None)
          case step :: rest =>
            step.run(current, json, settings) match {
              case Some((newState, task)) if task == NoOpTask =>
                // ✅ keep the newState and keep going
                loop(rest, newState)

              case Some((newState, task)) =>
                // ✅ return early with state AND task
                (newState, Some(task))

              case None =>
                // ✅ no state change, continue with existing
                loop(rest, current)
            }
        }

      // ✅ always return the latest state, even if no task
      loop(allSteps, startState)
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

      val cb  = state.caveBot
      val idx = cb.currentWaypointIndex
      val wps = cb.fixedWaypoints

      println(s"[UpdateLocation] starting; idx=$idx, totalWPs=${wps.size}, pos=($x,$y)")

      if (!wps.isDefinedAt(idx)) {
        println(s"[UpdateLocation] no waypoint at index $idx → skipping")
        None
      } else {
        val wp = wps(idx)
        val dx = math.abs(wp.waypointX - x)
        val dy = math.abs(wp.waypointY - y)
        println(s"[UpdateLocation] current WP #$idx at (${wp.waypointX},${wp.waypointY}), Δ=($dx,$dy)")
        val man   = math.abs(wp.waypointX - x) + math.abs(wp.waypointY - y)
        if (man <= 3) {
          val nextIdx = (idx + 1) % wps.size
          println(s"[UpdateLocation] within 3 tiles (man=$man) → advancing to index $nextIdx")

          val newCb = cb.copy(
            currentWaypointIndex   = nextIdx,
            subWaypoints           = List.empty,
            antiCaveBotStuckStatus = 0
          )
          val updatedState = state.copy(caveBot = newCb)

          println(s"[UpdateLocation] emitting marker task $taskName")
          Some(updatedState -> NoOpTask)
        } else {
          println(s"[UpdateLocation] not yet at WP → no task")
          None
        }
      }
    }
  }

  private object MovementToSubwaypoint extends Step {
    private val taskName = "MovementToSubwaypoint"

    override def run(
                      state: GameState,
                      json: JsValue,
                      settings: UISettings
                    ): Option[(GameState, MKTask)] = {
      val at = state.autoTarget
      val cb = state.caveBot
      val idx = cb.currentWaypointIndex

      println(s"[$taskName] starting; idx=$idx, subWaypoints.size=${cb.subWaypoints.size}")

      val fixedWps = cb.fixedWaypoints
      if (!fixedWps.isDefinedAt(idx)) {
        println(s"[$taskName] no fixed WP at $idx → skipping")
        return None
      }

      val currentWp = fixedWps(idx)
      println(s"[$taskName] target fixed WP #$idx = (${currentWp.waypointX},${currentWp.waypointY})")

      // 1) read current character pos from JSON
      val px = (json \ "characterInfo" \ "PositionX").as[Int]
      val py = (json \ "characterInfo" \ "PositionY").as[Int]
      val loc = Vec(px, py)

      println(s"[TICK] Character pos = $loc, WP target = (${currentWp.waypointX}, ${currentWp.waypointY})")

      // If target is chosen, don't generate subwaypoints, only track waypoints
      if (at.stateAutoTarget == "target chosen") {
        println(s"[$taskName] target chosen → skipping movement generation")
        return None
      }

      // Only proceed if caveBot is in 'free' state
      if (cb.stateHunting != "free") {
        println(s"[$taskName] caveBot status is '${cb.stateHunting}' → skipping")
        return None
      }

      // Proceed to generate subwaypoints and move
      println(s"[$taskName] generating path to target WP")
      val gen = generateSubwaypoints(currentWp, state, json)
      val subs0 = gen.caveBot.subWaypoints
      println(s"[$taskName] regenerated path length = ${subs0.size}")

      subs0.headOption.map { nextWp =>
        println(s"[$taskName] moving from $loc to next subWP $nextWp")

        val dirOpt = calculateDirection(loc, nextWp, state.characterInfo.lastDirection)
        println(s"[$taskName] calculateDirection → $dirOpt")

        val updatedCharInfo = state.characterInfo.copy(
          lastDirection = dirOpt,
          presentCharLocation = loc
        )

        val kbActions: List[KeyboardAction] = dirOpt.toList.map(DirectionalKey(_))
        println(s"[$taskName] emitting movement task with ${kbActions.size} keyboard actions")

        val updatedCaveBot = gen.caveBot.copy(
          subWaypoints = subs0.drop(1)
        )

        val nextState = gen.copy(
          characterInfo = updatedCharInfo,
          caveBot = updatedCaveBot
        )

        nextState -> MKTask(
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
    println(s"[generateSubwaypoints] tile count = ${tiles.size}")
    val xs    = tiles.keys.map(_.substring(0,5).toInt)
    val ys    = tiles.keys.map(_.substring(5,10).toInt)
    val (minX, minY, maxX, maxY) = (xs.min, ys.min, xs.max, ys.max)
    val gridBounds                = (minX, minY, maxX, maxY)

    // 2) build boolean grid, getting its offset minimums
    val (grid, (offX, offY)) = createBooleanGrid(tiles, minX, minY)
    println(s"[generateSubwaypoints] grid bounds = ${gridBounds}, offset = ($offX,$offY)")

    // 3) desired waypoint position (no longer clipped)
    val rawWpPos = Vec(currentWaypoint.waypointX, currentWaypoint.waypointY)
    if (
      rawWpPos.x < minX || rawWpPos.x > maxX ||
        rawWpPos.y < minY || rawWpPos.y > maxY
    ) {
      printInColor(ANSI_BLUE,
        s"[WARNING] Waypoint $rawWpPos is outside grid $gridBounds — attempting full A* anyway")
    }


    val px = (json \ "characterInfo" \ "PositionX").as[Int]
    val py = (json \ "characterInfo" \ "PositionY").as[Int]
    val presentLoc = Vec(px, py)


    // 5) decide whether we're already at the WP
    val (nextIndex, targetPos) =
      if (presentLoc == rawWpPos) {
        // wrap to next waypoint
        val nextIdx = (state.caveBot.currentWaypointIndex + 1) % state.caveBot.fixedWaypoints.size
        val nwp     = state.caveBot.fixedWaypoints(nextIdx)
        val np      = Vec(nwp.waypointX, nwp.waypointY)
        printInColor(ANSI_BLUE,
          s"[WAYPOINTS] Reached $rawWpPos, advancing to #$nextIdx → $np")
        (nextIdx, np)
      } else {
        (state.caveBot.currentWaypointIndex, rawWpPos)
      }

    // 6) always run A* from presentLoc to targetPos
    val rawPath =
      if (presentLoc != targetPos)
        aStarSearch(presentLoc, targetPos, grid, offX, offY)
      else
        Nil

    // 7) drop the starting position from the path
    val subWaypoints = rawPath.drop(1)


    // 8) debug‐print full grid & path
    printGrid(
      grid           = grid,
      gridBounds     = gridBounds,
      path           = rawPath,
      charPos        = presentLoc,
      waypointPos    = rawWpPos
    )

    // 9) assemble new caveBot state
    val newCaveBot = state.caveBot.copy(
      currentWaypointIndex    = nextIndex,
      subWaypoints            = subWaypoints,
      gridBoundsState         = gridBounds,
      gridState               = grid,
      currentWaypointLocation = targetPos
    )

    state.copy(caveBot = newCaveBot)
  }



  private object WaypointLoader extends Step {
    private val taskName = "WaypointLoader"

    def run(state: GameState, json: JsValue, settings: UISettings): Option[(GameState, MKTask)] = {
      if (state.caveBot.waypointsLoaded) None
      else {
        println("[WaypointLoader] Loading waypoints from settings")
        val updatedState = loadingWaypointsFromSettings(settings, state)

        // If no waypoints, mark loaded and skip
        if (updatedState.caveBot.fixedWaypoints.isEmpty) {
          println("[WaypointLoader] No waypoints found, marking as loaded and skipping")
          val cleared = updatedState.copy(caveBot = updatedState.caveBot.copy(waypointsLoaded = true))
          None
        } else {
          println(s"[WaypointLoader] Loaded waypoints: ${updatedState.caveBot.fixedWaypoints}")
          val x = (json \ "characterInfo" \ "PositionX").as[Int]
          val y = (json \ "characterInfo" \ "PositionY").as[Int]
          val pos = Vec(x, y)
          println(s"[WaypointLoader] Character pos: ($x,$y)")

          val indexed = updatedState.caveBot.fixedWaypoints.zipWithIndex.map { case (wp, i) =>
            i -> pos.manhattanDistance(Vec(wp.waypointX, wp.waypointY))
          }
          val (closest, dist) = indexed.minBy(_._2)
          println(s"[WaypointLoader] Closest waypoint idx=$closest dist=$dist")

          val newCave = updatedState.caveBot.copy(
            waypointsLoaded = true,
            currentWaypointIndex = closest,
            subWaypoints = List.empty
          )
          val newChar = updatedState.characterInfo.copy(presentCharLocation = pos)
          val finalState = updatedState.copy(caveBot = newCave, characterInfo = newChar)

          println(s"[WaypointLoader] Emitting marker task: $taskName")
          Some(finalState -> NoOpTask)
        }
      }
    }
  }


  def loadingWaypointsFromSettings(settings: UISettings, state: GameState): GameState = {
    // before you group, do:
    val rawLines: Seq[String] = settings.caveBotSettings.waypointsList
    println(s"[loadingWaypointsFromSettings] Raw lines (${rawLines.size}): $rawLines")

    // split on commas and/or whitespace, trim out any empties
    val tokens: Seq[String] =
      rawLines
        .flatMap(_.split("[,\\s]+"))
        .map(_.trim)
        .filter(_.nonEmpty)

    println(s"[loadingWaypointsFromSettings] Tokens (${tokens.size}): $tokens")
    // should now print Tokens (25): List(walk, center, 32681, 31687, 7, walk, center, …)


    // Each group of 5 tokens defines a waypoint (walk, placement, x, y, z)
    val chunks: List[Seq[String]] = tokens.grouped(5).toList
    println(s"[loadingWaypointsFromSettings] Token chunks (sizes): ${chunks.map(_.size)}")

    val waypointsInfoList: List[WaypointInfo] = chunks.collect {
      case Seq("walk", placement, xS, yS, zS)
        if Try(xS.toInt).isSuccess &&
          Try(yS.toInt).isSuccess &&
          Try(zS.toInt).isSuccess =>
        WaypointInfo("walk", xS.toInt, yS.toInt, zS.toInt, placement)
    }
    println(s"[loadingWaypointsFromSettings] Parsed waypoints: $waypointsInfoList")

    val levels: List[Int] = waypointsInfoList.map(_.waypointZ).distinct
    println(s"[loadingWaypointsFromSettings] Levels: $levels")

    val newCaveBot = state.caveBot.copy(
      fixedWaypoints    = waypointsInfoList,
      waypointsLoaded   = false,  // only mark loaded when first step completes
      caveBotLevelsList = levels
    )
    println("[loadingWaypointsFromSettings] Returning updated caveBot state")
    state.copy(caveBot = newCaveBot)
  }




  private object WrongFloorSaver extends Step {
    private val taskName = "WrongFloorSaver"
    private val levelMovementEnablersIds = StaticGameInfo.LevelMovementEnablers.AllIds

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
          Some(baseState -> NoOpTask)
        } else {
          // update delay and character position
          val x = (json \ "characterInfo" \ "PositionX").as[Int]
          val y = (json \ "characterInfo" \ "PositionY").as[Int]
          val newLoc = Vec(x, y)

          val withPos = baseState.copy(
            caveBot = baseState.caveBot.copy(antiOverpassDelay = now),
            characterInfo = baseState.characterInfo.copy(presentCharLocation = newLoc)
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
              val dist = math.abs(gx - newLoc.x) + math.abs(gy - newLoc.y)
              (tileId, dist)
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
                      state: GameState,
                      json: JsValue,
                      settings: UISettings
                    ): Option[(GameState, MKTask)] = {
      printInColor(ANSI_PURPLE, s"[WaypointStuckSaver] Entered step. Hunting=${state.caveBot.stateHunting}")

      if (state.caveBot.stateHunting != "free") return None

      val monsters = (json \ "battleList").asOpt[Seq[JsValue]].getOrElse(Nil)
      if (monsters.nonEmpty) return None

      val x       = (json \ "characterInfo" \ "PositionX").as[Int]
      val y       = (json \ "characterInfo" \ "PositionY").as[Int]
      val newLoc  = Vec(x, y)
      val prevLoc = state.characterInfo.presentCharLocation

      val updatedCharInfo = state.characterInfo.copy(presentCharLocation = newLoc)
      val cb              = state.caveBot

      val updatedCB =
        if (prevLoc == newLoc) {
          val stuckCount = cb.antiCaveBotStuckStatus + 1

          if (stuckCount >= state.general.retryAttemptsVerLong) {
            printInColor(ANSI_PURPLE, s"[WaypointStuckSaver] Character stuck for $stuckCount loops. Resetting to closest waypoint.")

            val newCB =
              if (cb.fixedWaypoints.nonEmpty) {
                val tiles      = (json \ "areaInfo" \ "tiles").as[Map[String, JsObject]]
                val tileCoords = tiles.keySet.map(k => Vec(k.take(5).toInt, k.drop(5).take(5).toInt))

                val reachableWaypoints = cb.fixedWaypoints.zipWithIndex.collect {
                  case (wp, idx) if tileCoords.contains(Vec(wp.waypointX, wp.waypointY)) =>
                    val dist = newLoc.manhattanDistance(Vec(wp.waypointX, wp.waypointY))
                    idx -> dist
                }

                val closestIdx = if (reachableWaypoints.nonEmpty) {
                  reachableWaypoints.minBy(_._2)._1
                } else {
                  printInColor(ANSI_PURPLE, s"[WaypointStuckSaver] WARNING: No reachable waypoint found on screen — keeping current")
                  cb.currentWaypointIndex
                }

                cb.copy(
                  currentWaypointIndex   = closestIdx,
                  subWaypoints           = List.empty,
                  antiCaveBotStuckStatus = 0
                )
              } else {
                printInColor(ANSI_PURPLE, s"[WaypointStuckSaver] ERROR: fixedWaypoints list is empty!")
                cb.copy(antiCaveBotStuckStatus = 0)
              }

            newCB
          } else {
            if (stuckCount >= 5)
              printInColor(ANSI_PURPLE, s"[WaypointStuckSaver] Still stuck: count = $stuckCount")
            cb.copy(antiCaveBotStuckStatus = stuckCount)
          }

        } else {
          if (cb.antiCaveBotStuckStatus >= 5)
            printInColor(ANSI_PURPLE, s"[WaypointStuckSaver] Movement detected, stuck counter reset")
          cb.copy(antiCaveBotStuckStatus = 0)
        }

      val updatedState = state.copy(
        caveBot       = updatedCB,
        characterInfo = updatedCharInfo
      )

      if (updatedCB.antiCaveBotStuckStatus != cb.antiCaveBotStuckStatus ||
        updatedCB.currentWaypointIndex != cb.currentWaypointIndex) {
        printInColor(ANSI_PURPLE, s"[WaypointStuckSaver] Returning update. stuckStatus=${updatedCB.antiCaveBotStuckStatus}")
        Some(updatedState -> NoOpTask)
      } else {
        None
      }
    }
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

  case class Vec(x: Int, y: Int) {
    def +(other: Vec): Vec = Vec(x + other.x, y + other.y)
    def manhattanDistance(other: Vec): Int = (x - other.x).abs + (y - other.y).abs

    def chebyshevDistance(other: Vec): Int = {
      math.max(math.abs(this.x - other.x), math.abs(this.y - other.y))
    }

    // Euclidean distance between two points
    def distanceTo(other: Vec): Double = {
      val dx = this.x - other.x
      val dy = this.y - other.y
      Math.sqrt(dx * dx + dy * dy)
    }

    // Normalize the vector to unit length
    def normalize(): Vec = {
      val magnitude = distanceTo(Vec(0, 0))
      if (magnitude != 0) {
        Vec((x / magnitude).toInt, (y / magnitude).toInt)
      } else {
        this
      }
    }

    // Add another Vec to this Vec
//    def +(other: Vec): Vec = {
//      Vec(this.x + other.x, this.y + other.y)
//    }

    // Subtract another Vec from this Vec
    def -(other: Vec): Vec = {
      Vec(this.x - other.x, this.y - other.y)
    }

    // Multiply by a scalar value
    def *(scalar: Int): Vec = {
      Vec(this.x * scalar, this.y * scalar)
    }

    // Round to the nearest grid point (e.g., for grid alignment)
    def roundToGrid(): Vec = {
      Vec(Math.round(x).toInt, Math.round(y).toInt)
    }
  }

  case class WaypointInfo(
                           waypointType: String,
                           waypointX: Int,
                           waypointY: Int,
                           waypointZ: Int,
                           waypointPlacement: String
                         )


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

}