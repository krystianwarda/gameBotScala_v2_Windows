package processing

import cats.implicits.{catsSyntaxAlternativeGuard, toFunctorOps}
import keyboard.{DirectionalKey, KeyboardAction}
import play.api.libs.json.{JsObject, JsValue, Json, OFormat}
import processing.AutoTargetFeature.aStarSearch
import utils.ProcessingUtils.{MKActions, MKTask, NoOpTask, Step}
import utils.SettingsUtils.UISettings
import utils.consoleColorPrint.{ANSI_BLUE, printInColor}
import utils.{GameState, RandomUtils, StaticGameInfo}

//import scala.util.Random
import mouse._
import processing.CaveBotFeature.Vec

import scala.collection.mutable
import scala.util.Try


object TeamHuntFeature {

  def run(json: JsValue, settings: UISettings, state: GameState): (GameState, List[MKTask]) =
    (!settings.teamHuntSettings.enabled).guard[Option]
      .as((state, Nil))
      .getOrElse {
        val (s, maybeTask) = Steps.runAll(json, settings, state)
        (s, maybeTask.toList)
      }

  private object Steps {
    // ordered list of steps
    val allSteps: List[Step] = List(
      GetTeamPositionInfo,
      AnalyseTeamMembersPosition,
      ChangeLevelAction,
      DesignSmartMovement,
      EngageMovement,
//      ShipTravelAfterTeam,

    )


    def runAll(
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

  private object ChangeLevelAction extends Step {
    private val taskName = "ChangeLevelAction"

    override def run(
                      state: GameState,
                      json: JsValue,
                      settings: UISettings
                    ): Option[(GameState, MKTask)] = {

      val currentTime = System.currentTimeMillis()
      val taskName = "ChangeLevelAction"
      val teamHuntState = state.teamHunt

      if (teamHuntState.teamHuntActionThrottle*teamHuntState.throttleCoefficient > currentTime - teamHuntState.lastTeamHuntActionTime) {
        println(s"[$taskName] Too soon since last action → NoOp")
        return Some(state -> NoOpTask)
      }

      println(s"[$taskName] Entered function.")



      // Check if changeLevelState is not "active"
      if (teamHuntState.changeLevelState != "active") {
        println(s"[$taskName] Change level state is not active. Skipping step.")
        return Some(state -> NoOpTask)
      }

      val (_, _, _, newPosZ) = teamHuntState.followedTeamMemberPos

      val characterZ = (json \ "characterInfo" \ "PositionZ").as[Int]

      if (characterZ == newPosZ && !teamHuntState.spyInfoIssue) {
        println(s"[$taskName] On the same level as the followed team member again. Back to following blocker.")
        val updatedState = state.copy(teamHunt = teamHuntState.copy(
          changeLevelState = "free",
        ))
        return Some(updatedState -> NoOpTask)
      }

      val (_, oldPosX, oldPosY, oldPosZ) = teamHuntState.lastPosFollowedTeamMemberOnTheSameLevel

      println(s"[$taskName] Checking tiles around followed team member at ($oldPosX, $oldPosY, $oldPosZ)")


      // Determine if the team member went up or down
      val movementIds = if (newPosZ < characterZ) {
        println(s"[$taskName] Team member moved up. Looking for AllUpIds.")
        StaticGameInfo.LevelMovementEnablers.AllUpIds
      } else if (newPosZ > characterZ) {
        println(s"[$taskName] Team member moved down. Looking for AllDownIds.")
        StaticGameInfo.LevelMovementEnablers.AllDownIds
      } else {
        println(s"[$taskName] Team member is gone somewhere not available in spyInfo. Look for all. ")
        StaticGameInfo.LevelMovementEnablers.AllIds
      }


      val tiles = (json \ "areaInfo" \ "tiles").as[Map[String, JsObject]]
      val level = f"$characterZ%02d" // Convert to two-digit string
      val filteredTilesList = filterTilesByLevel(tiles, level)
      val searchStartTile = (oldPosX, oldPosY, oldPosZ)
      // Search for level movement enabler tiles around the team member's position
      val movementTile = findChangeLevelTile(filteredTilesList, movementIds, searchStartTile)

      movementTile match {
        case Some((tileId, tileIndex, movementId)) =>
          println(s"Found movement enabler tile: $tileId at $tileIndex with movementId: $movementId")

          val (updatedState, mouseActions, keyboardActions) = analyzeTileTactic(tileId, tileIndex, movementId, json, state, settings)
          return Some((updatedState, MKTask("changing level", MKActions(mouse = mouseActions, keyboard = keyboardActions))))

        case None =>
          println("No movement enabler tiles found.")
          return Some(state -> NoOpTask)
      }

    }
  }

  private object GetTeamPositionInfo extends Step {
    private val taskName = "GetTeamPositionInfo"

    override def run(
                      state: GameState,
                      json: JsValue,
                      settings: UISettings
                    ): Option[(GameState, MKTask)] = {

      val spyLevelInfo = (json \ "spyLevelInfo").asOpt[JsObject].getOrElse(Json.obj())
      val thSettings = settings.teamHuntSettings
      val teamHuntState = state.teamHunt
      val charPosZ = (json \ "characterInfo" \ "PositionZ").as[Int]


      println(s"[$taskName] Blocker name: ${thSettings.blockerName}")
      println(s"[$taskName] Team members: ${thSettings.teamMembersList}")
      println(s"[$taskName] Team member to follow: ${teamHuntState.teamMemberToFollow}, ${teamHuntState.followedTeamMemberPos}")


      println(s"spyLevelInfo: ${spyLevelInfo}")
      val filteredSpyLevelInfo = filterSpyLevelInfo(spyLevelInfo, thSettings.blockerName, thSettings.teamMembersList)
      println(s"filteredSpyLevelInfo: ${filteredSpyLevelInfo}")

      // Update blocker position
      val blockerPosition = filteredSpyLevelInfo.fields.collectFirst {
        case (_, creatureData) if (creatureData \ "Name").asOpt[String].contains(thSettings.blockerName) =>
          val posX = (creatureData \ "PositionX").asOpt[Int].getOrElse(0)
          val posY = (creatureData \ "PositionY").asOpt[Int].getOrElse(0)
          val posZ = (creatureData \ "PositionZ").asOpt[Int].getOrElse(0)
          println(s"[$taskName] Blocker found at ($posX, $posY, $posZ)")
          (thSettings.blockerName, posX, posY, posZ)
      }.getOrElse {
        println(s"[$taskName] Blocker not found, using last known position: ${state.teamHunt.lastBlockerPos}")
        state.teamHunt.lastBlockerPos
      }

      // Update team members positions
      val teamPositions = thSettings.teamMembersList.flatMap { teamMember =>
        filteredSpyLevelInfo.fields.collectFirst {
          case (_, creatureData) if (creatureData \ "Name").asOpt[String].contains(teamMember) =>
            val posX = (creatureData \ "PositionX").asOpt[Int].getOrElse(0)
            val posY = (creatureData \ "PositionY").asOpt[Int].getOrElse(0)
            val posZ = (creatureData \ "PositionZ").asOpt[Int].getOrElse(0)
            println(s"[$taskName] Team member $teamMember found at ($posX, $posY, $posZ)")
            (teamMember, posX, posY, posZ)
        }
      }

      // Update followed player position
      val followedPlayerPosition = filteredSpyLevelInfo.fields.collectFirst {
        case (_, creatureData) if (creatureData \ "Name").asOpt[String].contains(teamHuntState.teamMemberToFollow) =>
          val posX = (creatureData \ "PositionX").asOpt[Int].getOrElse(0)
          val posY = (creatureData \ "PositionY").asOpt[Int].getOrElse(0)
          val posZ = (creatureData \ "PositionZ").asOpt[Int].getOrElse(0)
          println(s"[$taskName] Followed player ${teamHuntState.teamMemberToFollow} found at ($posX, $posY, $posZ)")
          (teamHuntState.teamMemberToFollow, posX, posY, posZ)
      }.getOrElse {
        println(s"[$taskName] Followed player not found, using last known position: ${state.teamHunt.followedTeamMemberPos}")
        state.teamHunt.lastBlockerPos
      }

      // Check if teamMemberToFollow exists in filteredSpyLevelInfo
      val updatedFollowedTeamMemberPosOnTheSameLevel = filteredSpyLevelInfo.fields.collectFirst {
        case (_, creatureData) if (creatureData \ "Name").asOpt[String].contains(teamHuntState.teamMemberToFollow) =>
          val posX = (creatureData \ "PositionX").asOpt[Int].getOrElse(0)
          val posY = (creatureData \ "PositionY").asOpt[Int].getOrElse(0)
          val posZ = (creatureData \ "PositionZ").asOpt[Int].getOrElse(0)

          if (posZ == charPosZ) {
            println(s"[$taskName] Updating followed team member position: (${teamHuntState.teamMemberToFollow}, $posX, $posY, $posZ)")
            (teamHuntState.teamMemberToFollow, posX, posY, posZ)
          } else {
            println(s"[$taskName] Team member ${teamHuntState.teamMemberToFollow} is on a different level. Keeping previous position: ${teamHuntState.lastPosFollowedTeamMemberOnTheSameLevel}")
            teamHuntState.lastPosFollowedTeamMemberOnTheSameLevel
          }
      }.getOrElse {
        println(s"[$taskName] Team member ${teamHuntState.teamMemberToFollow} not found. Keeping previous position: ${teamHuntState.followedTeamMemberPos}")
        teamHuntState.followedTeamMemberPos
      }

      // Update the state with the new positions
      val updatedTeamHunt = state.teamHunt.copy(
        lastBlockerPos = blockerPosition,
        lastTeamPos = teamPositions,
        followedTeamMemberPos = followedPlayerPosition,
        lastPosFollowedTeamMemberOnTheSameLevel =  updatedFollowedTeamMemberPosOnTheSameLevel
      )

      val updatedState = state.copy(teamHunt = updatedTeamHunt)

      println(s"[$taskName] Updated team positions: $teamPositions")
      println(s"[$taskName] Updated blocker position: ${updatedTeamHunt.lastBlockerPos}")
      println(s"[$taskName] Updated followedTeamMember position: ${updatedTeamHunt.followedTeamMemberPos}")

      Some(updatedState -> NoOpTask)
    }
  }


  private object AnalyseTeamMembersPosition extends Step {
    private val taskName = "AnalyseTeamMembersPosition"

    override def run(state: GameState, json: JsValue, settings: UISettings): Option[(GameState, MKTask)] = {

      val blockerName = settings.teamHuntSettings.blockerName
      val currentlyFollowing = state.teamHunt.teamMemberToFollow
      val spyInfo = (json \ "spyLevelInfo").asOpt[JsObject].getOrElse(JsObject.empty)
      val characterZ = (json \ "characterInfo" \ "PositionZ").asOpt[Int].getOrElse(0)
      val characterPos = extractCharPosition(json)

      println(s"[$taskName] Blocker: $blockerName, Currently following: $currentlyFollowing")
      // Extract team positions from spy info
      val teamPositions = extractTeamPositions(spyInfo, settings.teamHuntSettings.teamMembersList :+ blockerName)
      println(s"[$taskName] Team positions found: ${teamPositions.size}")

      // Check blocker availability and level
      teamPositions.get(blockerName) match {
        case Some((blockerX, blockerY, blockerZ)) =>
          println(s"[$taskName] Blocker found at ($blockerX, $blockerY, $blockerZ), character at Z=$characterZ")

          if (blockerZ != characterZ) {
            // Blocker is on different level - set change level state but don't change following
            println(s"[$taskName] Blocker on different level, activating level change")
            val updatedState = state.copy(teamHunt = state.teamHunt.copy(
              changeLevelState = "active",
            ))
            return Some(updatedState -> NoOpTask)
          } else {
            // Blocker is on same level - check if path can be generated
            val blockerPos = Vec(blockerX, blockerY)
            val newPath = generateSmartWaypointsToTeamMember(blockerPos, state, json)

            if (newPath.nonEmpty) {
              println(s"[$taskName] Path to blocker available, path to blocker generated.")
              val updatedState = state.copy(teamHunt = state.teamHunt.copy(
                teamMemberToFollow = blockerName,
                followedTeamMemberPos = (blockerName, blockerX, blockerY, blockerZ),
                changeLevelState = "free",
                subWaypoints = newPath,
              ))
              return Some(updatedState -> NoOpTask)
            } else {
              println(s"[$taskName] Assuming blocker is standing next to you.")
              return Some(state -> NoOpTask)
            }

          }

        case None =>
          println(s"[$taskName] Blocker not found in spy info")
          analyzeAlternativeTeamMembers(state, json, settings, teamPositions, characterZ, currentlyFollowing)
      }

      // Blocker not available or no path - analyze other team members

    }

  }


  private object DesignSmartMovement extends Step {
    private val taskName = "DesignSmartMovement"

    override def run(
                      state: GameState,
                      json: JsValue,
                      settings: UISettings
                    ): Option[(GameState, MKTask)] = {
      val th = state.teamHunt
      val teamMemberToFollow = th.teamMemberToFollow
      val characterZ = (json \ "characterInfo" \ "PositionZ").as[Int]

      if (teamMemberToFollow.isEmpty || th.changeLevelState == "active") {
        return Some(state -> NoOpTask)
      }

      println(s"[$taskName] Designing movement to follow: $teamMemberToFollow")

      // Get team member position from spy info
      val spyInfo = (json \ "spyLevelInfo").asOpt[JsObject].getOrElse(JsObject.empty)
      val teamPositions = extractTeamPositions(spyInfo, List(teamMemberToFollow))

      teamPositions.get(teamMemberToFollow) match {
        case Some((x, y, z)) =>
          val targetLocation = Vec(x, y)
          val presentLoc = Vec(
            (json \ "characterInfo" \ "PositionX").as[Int],
            (json \ "characterInfo" \ "PositionY").as[Int]
          )

          println(s"[$taskName] Target: $teamMemberToFollow at $targetLocation, Character at: $presentLoc")

          // Generate waypoints using the new smart pathfinding
          val newPath = generateSmartWaypointsToTeamMember(targetLocation, state, json)

          println(s"[$taskName] Generated ${newPath.length} waypoints")

          val updatedState = state.copy(teamHunt = state.teamHunt.copy(
            subWaypoints = newPath
          ))

          Some(updatedState -> NoOpTask)

        case None =>
          println(s"[$taskName] Team member $teamMemberToFollow not found in spy info")
          return Some(state -> NoOpTask)
      }
    }

    private def extractTeamPositions(spyInfo: JsObject, teamMembers: List[String]): Map[String, (Int, Int, Int)] = {
      spyInfo.fields.flatMap { case (_, playerData) =>
        for {
          name <- (playerData \ "Name").asOpt[String]
          if teamMembers.contains(name)
          x <- (playerData \ "PositionX").asOpt[Int]
          y <- (playerData \ "PositionY").asOpt[Int]
          z <- (playerData \ "PositionZ").asOpt[Int]
        } yield name -> (x, y, z)
      }.toMap
    }
  }

  private object EngageMovement extends Step {
    private val taskName = "EngageMovement"

    override def run(
                      state: GameState,
                      json: JsValue,
                      settings: UISettings
                    ): Option[(GameState, MKTask)] = {

      if (state.teamHunt.subWaypoints.isEmpty) {
        return Some(state -> NoOpTask)
      }

      val presentLoc = Vec(
        (json \ "characterInfo" \ "PositionX").as[Int],
        (json \ "characterInfo" \ "PositionY").as[Int]
      )

      println(s"[$taskName] Current position: $presentLoc, Waypoints: ${state.teamHunt.subWaypoints.length}")

      state.teamHunt.subWaypoints.headOption match {
        case Some(next) =>
          val dirOpt = calculateDirection(presentLoc, next, state.characterInfo.lastDirection)
          println(s"[$taskName] Calculated direction: $dirOpt from $presentLoc to $next")

          val updatedChar = state.characterInfo.copy(
            lastDirection = dirOpt,
            presentCharLocation = presentLoc
          )
          val updatedTeamHunt = state.teamHunt.copy(
            subWaypoints = state.teamHunt.subWaypoints.tail
          )
          val updatedState = state.copy(characterInfo = updatedChar, teamHunt = updatedTeamHunt)
          val keyboardActions = dirOpt.toList.map(DirectionalKey(_))
          return Some((updatedState, MKTask(taskName, MKActions(Nil, keyboardActions))))

        case None =>
          println(s"[$taskName] No waypoints available")
          return Some(state -> NoOpTask)

      }
    }
  }


  def filterSpyLevelInfo(
                          spyLevelInfo: JsObject,
                          blockerName: String,
                          teamMembersList: List[String]
                        ): JsObject = {
    val validNames = blockerName :: teamMembersList
    val filteredFields = spyLevelInfo.fields.filter { case (_, creatureData) =>
      val creatureName = (creatureData \ "Name").asOpt[String].getOrElse("")
      validNames.contains(creatureName)
    }
    JsObject(filteredFields)
  }

  def analyzeTileTactic(
                         tileId: String,
                         tileIndex: String,
                         movementId: Int,
                         json: JsValue,
                         state: GameState,
                         settings: UISettings
                       ): (GameState, List[MouseAction], List[KeyboardAction]) = {


    val keyboardActions = scala.collection.mutable.ListBuffer[KeyboardAction]()
    var updatedState = state
    val currentTime = System.currentTimeMillis()

    // Check if the movementId matches specific LevelMovementEnablers
    if (StaticGameInfo.LevelMovementEnablers.UpLadderIds.contains(movementId)) {
      // Right-click on the tile
      val screenInfo = (json \ "screenInfo" \ "mapPanelLoc" \ tileIndex).as[JsObject]
      val screenX = (screenInfo \ "x").as[Int]
      val screenY = (screenInfo \ "y").as[Int]

      val mouseActions = List(
        MoveMouse(screenX, screenY),
        RightButtonPress(screenX, screenY),
        RightButtonRelease(screenX, screenY)
      )

      val updatedState = state.copy(teamHunt = state.teamHunt.copy(
        lastTeamHuntActionTime = currentTime,
        spyInfoIssue = false,
      ))

      return (updatedState, mouseActions, Nil)

    } else if (StaticGameInfo.LevelMovementEnablers.UpRopesIds.contains(movementId)) {
      // Use rope: right-click on the rope and left-click on the tile
      val containersInfo = (json \ "containersInfo").as[JsObject]
      val ropeLocation = containersInfo.value.collectFirst {
        case (containerName, container) if (container \ "items").as[JsObject].value.exists {
          case (_, item) => (item \ "itemId").as[Int] == 3003
        } => (containerName, (container \ "items").as[JsObject].value.find {
          case (_, item) => (item \ "itemId").as[Int] == 3003
        }.get._1)
      }

      ropeLocation match {
        case Some((containerName, slot)) =>
          val screenInfo = (json \ "screenInfo" \ "inventoryPanelLoc").as[JsObject]
          val matchedContainer = screenInfo.keys.find(_.contains(containerName)).getOrElse("")
          val itemNumber = slot.replace("slot", "item")
          val itemPosition = (screenInfo \ matchedContainer \ "contentsPanel" \ itemNumber).as[JsObject]
          val itemScreenLocX = (itemPosition \ "x").as[Int]
          val itemScreenLocY = (itemPosition \ "y").as[Int]


          // Add actions for clicking on the tile
          val tileScreenInfo = (json \ "screenInfo" \ "mapPanelLoc" \ tileIndex).as[JsObject]
          val tileScreenX = (tileScreenInfo \ "x").as[Int]
          val tileScreenY = (tileScreenInfo \ "y").as[Int]

          val mouseActions = List(
            MoveMouse(itemScreenLocX, itemScreenLocY),
            RightButtonPress(itemScreenLocX, itemScreenLocY),
            RightButtonRelease(itemScreenLocX, itemScreenLocY),
            MoveMouse(tileScreenX, tileScreenY),
            LeftButtonPress(tileScreenX, tileScreenY),
            LeftButtonRelease(tileScreenX, tileScreenY)
          )

          val updatedState = state.copy(teamHunt = state.teamHunt.copy(
            lastTeamHuntActionTime = currentTime
          ))

          return (updatedState, mouseActions, Nil)

        case None =>
          println("Rope not found in inventory.")
          return (state, Nil, Nil)
      }

    } else {
      // Default case: Left-click on the tile
      val screenInfo = (json \ "screenInfo" \ "mapPanelLoc" \ tileIndex).as[JsObject]
      val screenX = (screenInfo \ "x").as[Int]
      val screenY = (screenInfo \ "y").as[Int]

      val mouseActions = List(
        MoveMouse(screenX, screenY),
        LeftButtonPress(screenX, screenY),
        LeftButtonRelease(screenX, screenY)
      )
      return (updatedState, mouseActions, Nil)

    }
  }

  def findChangeLevelTile(
                           tiles: Map[String, JsObject],
                           movementIds: List[Int],
                           searchStartTile: (Int, Int, Int)
                         ): Option[(String, String, Int)] = {

    println(s"Filtered Tiles: ${tiles.keys.mkString(", ")}")
    println(s"Movement IDs: $movementIds")
    println(s"Search Start Tile: $searchStartTile")

    // Helper function to generate spiral coordinates around a center
    def generateSpiral(centerX: Int, centerY: Int, maxRadius: Int): Seq[(Int, Int)] = {
      for {
        radius <- 0 to maxRadius
        dx <- -radius to radius
        dy <- -radius to radius
        if math.abs(dx) == radius || math.abs(dy) == radius // Only include the outer layer of the square
      } yield (centerX + dx, centerY + dy)
    }

    // Extract character position
    val (startX, startY, startZ) = searchStartTile

    // Iterate through spiral coordinates and check tiles, but limit to 15 tiles
    val spiralCoordinates = generateSpiral(startX, startY, maxRadius = 10)
    var tilesChecked = 0
    val maxTilesToCheck = 15

    for {
      (tileX, tileY) <- spiralCoordinates
      if tilesChecked < maxTilesToCheck
    } {
      val tileId = f"$tileX%05d$tileY%05d$startZ%02d" // Construct tileId
      tilesChecked += 1
      println(s"Checking tileId: $tileId, and looking for items: $movementIds (${tilesChecked}/$maxTilesToCheck)")

      tiles.get(tileId).flatMap { tileData =>
        // Extract items from the tile
        val tileItems = (tileData \ "items").asOpt[JsObject].getOrElse(Json.obj())

        // Check if any item matches the movementIds
        tileItems.fields.collectFirst {
          case (_, item) if movementIds.contains((item \ "id").as[Int]) =>
            val movementIdTile = (item \ "id").as[Int]
            println(s"Found movementIds tile $movementIdTile at ($tileX, $tileY, $startZ)")
            val tileIndex = (tileData \ "index").as[String]
            return Some((tileId, tileIndex, movementIdTile)) // Return immediately when found
        }
      }
    }

    println(s"No movement enabler tiles found after checking $tilesChecked tiles.")
    None
  }

  private def smartAStarSearch(start: Vec, goal: Vec, grid: Array[Array[Boolean]], offX: Int, offY: Int): List[Vec] = {
    // For now, copy the existing aStarSearch logic
    // In the future, this can be enhanced with team-specific pathfinding improvements
    aStarSearch(start, goal, grid, offX, offY)
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



  private def calculateDirection(from: Vec, to: Vec, lastDirection: Option[String]): Option[String] = {
    val dx = to.x - from.x
    val dy = to.y - from.y

    (dx, dy) match {
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

  private def extractTeamPositions(spyInfo: JsObject, teamMembers: List[String]): Map[String, (Int, Int, Int)] = {
    spyInfo.fields.flatMap { case (_, playerData) =>
      for {
        name <- (playerData \ "Name").asOpt[String]
        if teamMembers.contains(name)
        x <- (playerData \ "PositionX").asOpt[Int]
        y <- (playerData \ "PositionY").asOpt[Int]
        z <- (playerData \ "PositionZ").asOpt[Int]
      } yield name -> (x, y, z)
    }.toMap
  }

  def filterTilesByLevel(tiles: Map[String, JsObject], level: String): Map[String, JsObject] = {
    tiles.filter { case (key, _) =>
      key.takeRight(2) == level
    }
  }

  private def generateSmartWaypointsToTeamMember(
                                                  targetLocation: Vec,
                                                  state: GameState,
                                                  json: JsValue
                                                ): List[Vec] = {
    val subTaskName = "generateSmartWaypointsToTeamMember"
    println(s"[$subTaskName] Starting ENHANCED human-like pathfinding.")

    val currentTime = System.currentTimeMillis()
    val teamHuntState = state.teamHunt

    // Get character position and battle info
    val presentLoc = Vec(
      (json \ "characterInfo" \ "PositionX").as[Int],
      (json \ "characterInfo" \ "PositionY").as[Int]
    )

    val battleInfo = (json \ "battleInfo").asOpt[JsObject].getOrElse(Json.obj())
    val isTargetShootable = checkIfTargetIsShootable(battleInfo, teamHuntState.teamMemberToFollow)

    println(s"[$subTaskName] Character: $presentLoc → Target: $targetLocation, IsShootable: $isTargetShootable")

    // 1. Update movement detection
    val updatedTimestamps = updateMovementTimestamps(
      teamHuntState.targetMovementTimestamps,
      teamHuntState.lastTargetPosition,
      targetLocation,
      currentTime
    )

    val movementActivity = updatedTimestamps.length
    println(s"[$subTaskName] Movement activity (last 3s): $movementActivity timestamps")

    // 2. Calculate distance to target
    val distanceToTarget = calculateDistance(presentLoc, targetLocation)
    val distanceChanged = math.abs(distanceToTarget - teamHuntState.lastDistanceToTarget) > 1.0

    println(s"[$subTaskName] Distance to target: $distanceToTarget (was: ${teamHuntState.lastDistanceToTarget})")

    // 3. Update no-cross line based on character movement vector
    val updatedNoCrossLine = updateNoCrossLine(presentLoc, targetLocation, teamHuntState.noCrossLine)
    println(s"[$subTaskName] No-cross line: $updatedNoCrossLine")

    // 4. Determine if we should update temporary rest position
    val shouldUpdateRestPosition = shouldUpdateTemporaryRestPosition(
      movementActivity,
      currentTime,
      teamHuntState.lastTemporaryRestUpdate,
      teamHuntState.movementInfluenceMultiplier,
      teamHuntState.restPositionChangeBaseProbability,
      distanceToTarget,
      teamHuntState.safeDistanceCoefficient,
      isTargetShootable
    )

    // 5. Calculate new temporary rest position if needed
    val newTemporaryRestPosition = if (shouldUpdateRestPosition) {
      val newPos = calculateTemporaryRestPosition(
        presentLoc,
        targetLocation,
        updatedNoCrossLine,
        movementActivity,
        isTargetShootable,
        distanceToTarget
      )
      println(s"[$subTaskName] Updated temporary rest position: ${teamHuntState.temporaryRestPosition} → $newPos")
      Some(newPos)
    } else {
      teamHuntState.temporaryRestPosition
    }

    // 6. Determine movement behavior based on various factors
    val movementDecision = determineMovementBehavior(
      presentLoc,
      targetLocation,
      newTemporaryRestPosition,
      movementActivity,
      distanceToTarget,
      currentTime,
      teamHuntState.lastMovementDecision,
      isTargetShootable
    )

    println(s"[$subTaskName] Movement decision: $movementDecision")

    // 7. Generate path based on decision
    val finalPath = movementDecision match {
      case "chase" =>
        newTemporaryRestPosition match {
          case Some(restPos) => generatePathToPosition(presentLoc, restPos, state, json)
          case None => generatePathToPosition(presentLoc, targetLocation, state, json)
        }
      case "retreat" =>
        val retreatPos = calculateRetreatPosition(presentLoc, targetLocation, updatedNoCrossLine)
        generatePathToPosition(presentLoc, retreatPos, state, json)
      case "wait" =>
        List.empty[Vec] // Stay in place
      case _ =>
        List.empty[Vec]
    }

    // 8. Debug visualization
    val tiles = (json \ "areaInfo" \ "tiles").as[Map[String, JsObject]]
    val xs = tiles.keys.map(_.substring(0, 5).toInt)
    val ys = tiles.keys.map(_.substring(5, 10).toInt)
    val gridBounds = (xs.min, ys.min, xs.max, ys.max)
    val (grid, (offX, offY)) = createBooleanGrid(tiles, gridBounds._1, gridBounds._2)

    printEnhancedGridVisualization(
      grid = grid,
      gridBounds = gridBounds,
      path = finalPath,
      charPos = presentLoc,
      targetPos = targetLocation,
      temporaryRestPos = newTemporaryRestPosition,
      noCrossLine = updatedNoCrossLine,
      movementTimestamps = updatedTimestamps
    )

    println(s"[$subTaskName] Final path length: ${finalPath.length}")
    finalPath
  }

  private def calculateRetreatPosition(charPos: Vec, targetPos: Vec, noCrossLine: Option[(Vec, Vec)]): Vec = {
    val direction = Vec(charPos.x - targetPos.x, charPos.y - targetPos.y)
    val length = math.sqrt(direction.x * direction.x + direction.y * direction.y)

    if (length > 0) {
      val retreatDistance = RandomUtils.randomIntBetween(2, 4).toInt
      val normalizedDir = Vec(
        (direction.x / length * retreatDistance).toInt,
        (direction.y / length * retreatDistance).toInt
      )

      Vec(charPos.x + normalizedDir.x, charPos.y + normalizedDir.y)
    } else {
      Vec(
        charPos.x + RandomUtils.randomIntBetween(-2, 2).toInt,
        charPos.y + RandomUtils.randomIntBetween(-2, 2).toInt
      )
    }
  }

  private def updateMovementTimestamps(
                                        currentTimestamps: List[Long],
                                        lastPosition: Option[Vec],
                                        currentPosition: Vec,
                                        currentTime: Long
                                      ): List[Long] = {
    val threeSecondsAgo = currentTime - 3000
    val filteredTimestamps = currentTimestamps.filter(_ > threeSecondsAgo)

    lastPosition match {
      case Some(lastPos) if lastPos != currentPosition =>
        (currentTime :: filteredTimestamps).take(10) // Limit to prevent excessive memory usage
      case _ =>
        filteredTimestamps
    }
  }

  private def checkIfTargetIsShootable(battleInfo: JsObject, targetName: String): Boolean = {
    battleInfo.fields.exists { case (_, creatureData) =>
      (creatureData \ "Name").asOpt[String].contains(targetName) &&
        (creatureData \ "IsShootable").asOpt[Boolean].getOrElse(false)
    }
  }

  private def updateNoCrossLine(charPos: Vec, targetPos: Vec, currentLine: Option[(Vec, Vec)]): Option[(Vec, Vec)] = {
    // Create a line perpendicular to character's movement vector, positioned at target location
    val direction = Vec(targetPos.x - charPos.x, targetPos.y - charPos.y)
    if (direction.x == 0 && direction.y == 0) return currentLine

    // Create perpendicular vector
    val perpendicular = Vec(-direction.y, direction.x)
    val length = math.sqrt(perpendicular.x * perpendicular.x + perpendicular.y * perpendicular.y)

    if (length > 0) {
      val normalizedPerp = Vec(
        (perpendicular.x / length * 10).toInt,
        (perpendicular.y / length * 10).toInt
      )

      val linePoint1 = Vec(targetPos.x + normalizedPerp.x, targetPos.y + normalizedPerp.y)
      val linePoint2 = Vec(targetPos.x - normalizedPerp.x, targetPos.y - normalizedPerp.y)

      Some((linePoint1, linePoint2))
    } else {
      currentLine
    }
  }


  private def shouldUpdateTemporaryRestPosition(
                                                 movementActivity: Int,
                                                 currentTime: Long,
                                                 lastUpdate: Long,
                                                 movementMultiplier: Double,
                                                 baseProbability: Double,
                                                 distanceToTarget: Double,
                                                 safeCoefficient: Double,
                                                 isTargetShootable: Boolean
                                               ): Boolean = {
    val timeSinceLastUpdate = currentTime - lastUpdate
    val minimumUpdateInterval = if (movementActivity > 0) 1000 else 2000 // Reduced from 3s to 2s

    if (timeSinceLastUpdate < minimumUpdateInterval) return false

    // Always update if distance is too large (prioritize staying close)
    val distanceForceUpdate = distanceToTarget > 4.0 // Reduced from 7.0 to 4.0

    // Higher probability when target is moving frequently
    val activityProbability = movementActivity * movementMultiplier * baseProbability

    // Force update if target not shootable and we're not very close
    val shootabilityUpdate = !isTargetShootable && distanceToTarget > 2.5

    val randomCheck = RandomUtils.randomBetween(0, 100) < (activityProbability * 100)

    val shouldUpdate = distanceForceUpdate || shootabilityUpdate || randomCheck
    println(s"Update rest position? Distance: $distanceForceUpdate, Shootability: $shootabilityUpdate, Random: $randomCheck -> $shouldUpdate")

    shouldUpdate
  }

  private def calculateTemporaryRestPosition(
                                              charPos: Vec,
                                              targetPos: Vec,
                                              noCrossLine: Option[(Vec, Vec)],
                                              movementActivity: Int,
                                              isTargetShootable: Boolean,
                                              currentDistance: Double
                                            ): Vec = {
    // Always stay close to target - distance 2-3 max
    val baseDistance = if (isTargetShootable) {
      if (currentDistance > 3.0) 2 else RandomUtils.randomIntBetween(2, 3) // Force closer if too far
    } else {
      if (currentDistance > 4.0) 2 else RandomUtils.randomIntBetween(2, 3) // Force closer if too far
    }

    // Reduce distance variation to keep closer
    val distanceVariation = if (movementActivity > 2) RandomUtils.randomIntBetween(-1, 0) else 0 // Only reduce, don't increase
    val finalDistance = math.max(2, math.min(3, baseDistance + distanceVariation)) // Cap at 3

    println(s"Calculating rest position with final distance: $finalDistance (current: $currentDistance)")

    // Calculate direction vector from target to character
    val direction = Vec(charPos.x - targetPos.x, charPos.y - targetPos.y)
    val length = math.sqrt(direction.x * direction.x + direction.y * direction.y)

    if (length > 0) {
      val normalizedDir = Vec(
        (direction.x / length * finalDistance).toInt,
        (direction.y / length * finalDistance).toInt
      )

      // Reduce side offset to stay closer
      val sideOffset = if (currentDistance > 3.0) 0 else RandomUtils.randomIntBetween(-1, 1).toInt
      val perpendicular = Vec(-normalizedDir.y, normalizedDir.x)

      Vec(
        targetPos.x + normalizedDir.x + perpendicular.x * sideOffset,
        targetPos.y + normalizedDir.y + perpendicular.y * sideOffset
      )
    } else {
      // Fallback: random position around target, but keep very close
      Vec(
        targetPos.x + RandomUtils.randomIntBetween(-2, 2).toInt,
        targetPos.y + RandomUtils.randomIntBetween(-2, 2).toInt
      )
    }
  }

  private def determineMovementBehavior(
                                         charPos: Vec,
                                         targetPos: Vec,
                                         restPosition: Option[Vec],
                                         movementActivity: Int,
                                         distanceToTarget: Double,
                                         currentTime: Long,
                                         lastDecisionTime: Long,
                                         isTargetShootable: Boolean
                                       ): String = {
    val decisionCooldown = RandomUtils.randomBetween(800, 2000) // Reduced from 1-3s to 0.8-2s
    val timeSinceLastDecision = currentTime - lastDecisionTime

    // Emergency situations override cooldown - prioritize staying close
    if (distanceToTarget > 4.0) return "chase" // Reduced from 8.0 to 4.0
    if (distanceToTarget < 1.0) return "retreat" // Reduced from 1.5 to 1.0

    // If too soon since last decision but distance is concerning, still chase
    if (timeSinceLastDecision < decisionCooldown) {
      if (distanceToTarget > 3.5) return "chase" // Override cooldown if getting too far
      return "wait"
    }

    // Normal behavior based on movement activity - more aggressive chasing
    if (movementActivity > 1) {
      // Target is moving, chase more aggressively
      if (RandomUtils.randomBetween(0, 100) < 85) "chase" else "wait" // Increased from 70% to 85%
    } else if (movementActivity == 0) {
      // Target stopped, decide whether to adjust position
      if (!isTargetShootable) "chase" // Always move closer if can't shoot
      else if (distanceToTarget > 3.0) "chase" // Chase if too far even when shootable
      else if (RandomUtils.randomBetween(0, 100) < 30) "chase" else "wait" // Increased from 20% to 30%
    } else {
      if (distanceToTarget > 3.0) "chase" else "wait" // Chase if distance is concerning
    }
  }

  private def generatePathToPosition(from: Vec, to: Vec, state: GameState, json: JsValue): List[Vec] = {
    val tiles = (json \ "areaInfo" \ "tiles").as[Map[String, JsObject]]
    val xs = tiles.keys.map(_.substring(0, 5).toInt)
    val ys = tiles.keys.map(_.substring(5, 10).toInt)
    val (minX, minY) = (xs.min, ys.min)

    val (grid, (offX, offY)) = createBooleanGrid(tiles, minX, minY)
    val path = aStarSearch(from, to, grid, offX, offY)

    path.filterNot(_ == from)
  }

  private def calculateDistance(pos1: Vec, pos2: Vec): Double = {
    math.sqrt(math.pow(pos1.x - pos2.x, 2) + math.pow(pos1.y - pos2.y, 2))
  }

  def printEnhancedGridVisualization(
                                      grid: Array[Array[Boolean]],
                                      gridBounds: (Int, Int, Int, Int),
                                      path: List[Vec],
                                      charPos: Vec,
                                      targetPos: Vec,
                                      temporaryRestPos: Option[Vec],
                                      noCrossLine: Option[(Vec, Vec)],
                                      movementTimestamps: List[Long]
                                    ): Unit = {
    val (min_x, min_y, maxX, maxY) = gridBounds

    // ANSI colors
    val red = "\u001B[31m"      // Non-walkable
    val green = "\u001B[32m"    // Walkable
    val gold = "\u001B[33m"     // Character
    val pink = "\u001B[35m"     // Path
    val lightBlue = "\u001B[34m" // Target
    val cyan = "\u001B[36m"     // Temporary rest
    val yellow = "\u001B[93m"   // No-cross line
    val reset = "\u001B[0m"

    val offsetX = min_x
    val offsetY = min_y
    val width = maxX - min_x + 1
    val height = maxY - min_y + 1

    println(s"=== ENHANCED GRID VISUALIZATION ===")
    println(s"Movement Activity: ${movementTimestamps.length} moves in last 3s")
    println(s"Temporary Rest Position: $temporaryRestPos")
    println(s"No-Cross Line: $noCrossLine")

    for (y <- 0 until height) {
      for (x <- 0 until width) {
        val gameX = x + offsetX
        val gameY = y + offsetY
        val cellVec = Vec(gameX, gameY)

        val isOnNoCrossLine = noCrossLine.exists { case (p1, p2) =>
          isPointOnLine(cellVec, p1, p2, tolerance = 1)
        }

        val symbol = (cellVec, isOnNoCrossLine, path.contains(cellVec), cellVec == charPos,
          cellVec == targetPos, temporaryRestPos.contains(cellVec), grid(y)(x)) match {
          case (_, _, _, true, _, _, _) => s"${gold}[P]$reset" // Character
          case (_, _, _, _, true, _, _) => s"${lightBlue}[T]$reset" // Target
          case (_, _, _, _, _, true, _) => s"${cyan}[R]$reset" // Rest position
          case (_, true, _, _, _, _, _) => s"${yellow}[L]$reset" // No-cross line
          case (_, _, true, _, _, _, _) => s"${pink}[W]$reset" // Path
          case (_, _, _, _, _, _, true) => s"${green}[O]$reset" // Walkable
          case _ => s"${red}[X]$reset" // Non-walkable
        }

        print(symbol)
      }
      println()
    }
    println("Legend: [P]=Player, [T]=Target, [R]=Rest, [L]=Line, [W]=Way, [O]=Open, [X]=Blocked")
  }

  private def isPointOnLine(point: Vec, lineStart: Vec, lineEnd: Vec, tolerance: Int): Boolean = {
    val dx = lineEnd.x - lineStart.x
    val dy = lineEnd.y - lineStart.y

    if (dx == 0 && dy == 0) return false

    val crossProduct = math.abs((point.y - lineStart.y) * dx - (point.x - lineStart.x) * dy)
    val lineLength = math.sqrt(dx * dx + dy * dy)

    crossProduct / lineLength <= tolerance
  }


//  private def generateSmartWaypointsToTeamMember(
//                                              targetLocation: Vec,
//                                              state:          GameState,
//                                              json:           JsValue
//                                            ): List[Vec] = {
//    val subTaskName = "generateSmartWaypointsToTeamMember"
//    println(s"[$subTaskName] Starting.")
//
//    // 1) pull all tiles and build grid
//    val tiles = (json \ "areaInfo" \ "tiles").as[Map[String, JsObject]]
//    val xs = tiles.keys.map(_.substring(0, 5).toInt)
//    val ys = tiles.keys.map(_.substring(5, 10).toInt)
//    val gridBounds @ (minX, minY, maxX, maxY) = (xs.min, ys.min, xs.max, ys.max)
//
//    val (grid, (offX, offY)) = createBooleanGrid(tiles, minX, minY)
//    println(s"[$subTaskName] Grid bounds = $gridBounds, offset = ($offX,$offY)")
//
//    // 2) get character position
//    val presentLoc = Vec(
//      (json \ "characterInfo" \ "PositionX").as[Int],
//      (json \ "characterInfo" \ "PositionY").as[Int]
//    )
//
//    println(s"[$subTaskName] Character: $presentLoc → Target: $targetLocation")
//
//    // 3) grid bounds warning
//    if (
//      targetLocation.x < minX || targetLocation.x > maxX ||
//        targetLocation.y < minY || targetLocation.y > maxY
//    ) {
//      printInColor(ANSI_BLUE,
//        s"[$subTaskName] Target $targetLocation is outside grid $gridBounds — attempting A* anyway")
//    }
//
//    // 4) run A* pathfinding
//    val rawPath =
//      if (presentLoc != targetLocation)
//        aStarSearch(presentLoc, targetLocation, grid, offX, offY)
//      else {
//        println(s"[$subTaskName] Already at creature's location.")
//        Nil
//      }
//
//    val filteredPath = rawPath.filterNot(_ == presentLoc)
//    println(s"[$subTaskName] Final path: $filteredPath")
//
//    // 5) debug visualization with printGridCreatures
//    printGridCreatures(
//      grid = grid,
//      gridBounds = gridBounds,
//      path = rawPath,
//      charPos = presentLoc,
//      waypointPos = targetLocation,
//      creaturePositions = List(targetLocation)
//    )
//
//    filteredPath
//  }

  def printGridCreatures(grid: Array[Array[Boolean]], gridBounds: (Int, Int, Int, Int), path: List[Vec], charPos: Vec, waypointPos: Vec, creaturePositions: List[Vec]): Unit = {
    val (min_x, min_y, maxX, maxY) = gridBounds

    // ANSI escape codes for colors
    val red = "\u001B[31m" // Non-walkable
    val green = "\u001B[32m" // Walkable
    val gold = "\u001B[33m" // Character position
    val pink = "\u001B[35m" // Path
    val lightBlue = "\u001B[34m" // Waypoint
    val cyan = "\u001B[36m" // Creature
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

        // Determine which symbol and color to use for the current cell
        val symbol = (cellVec, path.contains(cellVec), cellVec == charPos, cellVec == waypointPos, creaturePositions.contains(cellVec), grid(y)(x)) match {
          case (_, _, true, _, _, _) => s"${gold}[P]$reset" // Character position
          case (_, _, _, true, _, _) => s"${lightBlue}[T]$reset" // Waypoint position
          case (_, true, _, _, _, _) => s"${pink}[W]$reset" // Path position
          case (_, _, _, _, true, _) => s"${cyan}[C]$reset" // Creature position
          case (_, _, _, _, _, true) => s"${green}[O]$reset" // Walkable tile
          case _ => s"${red}[X]$reset" // Non-walkable tile
        }

        print(symbol)
      }
      println() // New line after each row
    }
  }


  private def analyzeAlternativeTeamMembers(
                                             state: GameState,
                                             json: JsValue,
                                             settings: UISettings,
                                             teamPositions: Map[String, (Int, Int, Int)],
                                             characterZ: Int,
                                             currentlyFollowing: String
                                           ): Option[(GameState, MKTask)] = {

    val subtaskName = "analyzeAlternativeTeamMembers"
    val blockerName = settings.teamHuntSettings.blockerName
    val characterPos = extractCharPosition(json)
    println(s"[$subtaskName] Looking for new member to follow.")
    // Check if currently following member is still valid
    if (currentlyFollowing.nonEmpty && currentlyFollowing != blockerName) {
      teamPositions.get(currentlyFollowing) match {
        case Some((x, y, z)) if z == characterZ =>
          val memberPos = Vec(x, y)
          val newPath = generateSmartWaypointsToTeamMember(memberPos, state, json)

          if (newPath.nonEmpty) {
            println(s"[$subtaskName] Continuing to follow current member: $currentlyFollowing")
            return None // Keep current following target
          } else {
            println(s"[$subtaskName] Lost path to current member: $currentlyFollowing")
          }

        case Some((_, _, z)) =>
          println(s"[$subtaskName] Current member $currentlyFollowing on different level (Z=$z)")

        case None =>
          println(s"[$subtaskName] Current member $currentlyFollowing disappeared from spy info")
      }
    }

    // Find alternative team member to follow
    val alternativeMembers = settings.teamHuntSettings.teamMembersList
      .filter(_ != blockerName)
      .filter(_ != currentlyFollowing) // Don't re-select current member if we just lost path to them

    val availableMembers = alternativeMembers.flatMap { memberName =>
      teamPositions.get(memberName) match {
        case Some((x, y, z)) if z == characterZ =>
          val memberPos = Vec(x, y)
          val hasPath = generateSmartWaypointsToTeamMember(memberPos, state, json)
          if (hasPath.nonEmpty) Some(memberName -> memberPos) else None
        case _ => None
      }
    }

    println(s"[$subtaskName] Available alternative members: ${availableMembers.map(_._1)}")

    availableMembers.headOption match {
      case Some((memberName, _)) =>
        println(s"[$subtaskName] Switching to follow alternative member: $memberName")
        val updatedState = state.copy(teamHunt = state.teamHunt.copy(
          teamMemberToFollow = memberName,
          changeLevelState = "free"
        ))
        Some(updatedState -> NoOpTask)

      case None =>
        println(s"[$subtaskName] No alternative team members available")
        // Keep current state but clear following target if no one is available

        if (currentlyFollowing.nonEmpty) {
          println(s"[$subtaskName] Looking for followed player on different level to trigger level change.")
          val updatedState = state.copy(teamHunt = state.teamHunt.copy(
            changeLevelState = "active",
            spyInfoIssue = true
          ))
          Some(updatedState -> NoOpTask)
        } else {
          None
        }
    }
  }

  private def extractCharPosition(json: JsValue): Vec = {
    for {
      x <- (json \ "characterInfo" \ "PositionX").asOpt[Int]
      y <- (json \ "characterInfo" \ "PositionY").asOpt[Int]
    } yield Vec(x, y)
  }.getOrElse(Vec(0, 0))



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

