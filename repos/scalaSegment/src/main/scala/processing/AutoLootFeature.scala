package processing

import keyboard.{DirectionalKey, HoldCtrlFor, KeyboardAction, KeyboardUtils, PressCtrl, ReleaseCtrl}
import play.api.libs.json.{JsObject, JsString, JsValue, Json}
import utils.{GameState, StaticGameInfo}
import utils.ProcessingUtils.{MKActions, MKTask, NoOpTask, Step}
import utils.SettingsUtils.UISettings
import utils.consoleColorPrint.{ANSI_BLUE, ANSI_PURPLE, printInColor}

import scala.collection.mutable
import scala.util.{Random, Try}
import scala.util.chaining.scalaUtilChainingOps
import mouse.{LeftButtonPress, LeftButtonRelease, MouseAction, MoveMouse, RightButtonPress, RightButtonRelease}
import processing.AutoTargetFeature.parseCreature
import processing.CaveBotFeature.{Vec, aStarSearch, calculateDirection, createBooleanGrid}

import scala.concurrent.duration.DurationInt


object AutoLootFeature {

  import cats.syntax.all._

  def run(json: JsValue, settings: UISettings, state: GameState): (GameState, List[MKTask]) =
    (!settings.autoLootSettings.enabled).guard[Option]
      .as((state, Nil))
      .getOrElse {
        val (s, maybeTask) = Steps.runFirst(json, settings, state)
        (s, maybeTask.toList)
      }

  private object Steps {
    // ordered list of steps
    val allSteps: List[Step] = List(
      CheckForOkButton,
      DetectDeadCreatures,
      ProcessLootingInformation,
      ProcessLooting,
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




  private object DetectDeadCreatures extends Step {
    private val taskName = "DetectDeadCreatures"

    override def run(state: GameState, json: JsValue, settings: UISettings): Option[(GameState, MKTask)] = {
      val currentTime     = System.currentTimeMillis()
      val killedCreatures = (json \ "lastKilledCreatures").asOpt[JsObject].getOrElse(Json.obj())

      // skip the placeholder-only case
      if (killedCreatures.fields.size == 1 && {
        val only = killedCreatures.fields.head._2
        (only \ "None").asOpt[Boolean].contains(true)
      }) {
        println(s"[$taskName] No real kills detected, skipping.")
        return Some((state, NoOpTask))
      }

      println(s"[$taskName] ENTER: stateHunting = ${state.caveBot.stateHunting}")
      println(s"[$taskName] Running DetectDeadCreatures… Found ${killedCreatures.fields.size} entries.")
      println(s"[$taskName] Current autoLoot lists: immediately=${state.autoLoot.carcassToLootImmediately.size}, afterFight=${state.autoLoot.carcassToLootAfterFight.size}")

      var updatedState = state

      killedCreatures.fields.foreach { case (creatureId, creatureInfo) =>
        val name   = (creatureInfo \ "Name").asOpt[String].getOrElse("<unknown>")

        // try both new and old keys for backwards-compatibility
        val x = (creatureInfo \ "X").asOpt[Int]
          .orElse((creatureInfo \ "LastPositionX").asOpt[Int])
          .getOrElse(0)
        val y = (creatureInfo \ "Y").asOpt[Int]
          .orElse((creatureInfo \ "LastPositionY").asOpt[Int])
          .getOrElse(0)
        val z = (creatureInfo \ "Z").asOpt[Int]
          .orElse((creatureInfo \ "LastPositionZ").asOpt[Int])
          .getOrElse(0)

        val isDead = (creatureInfo \ "IsDead").asOpt[Boolean].getOrElse(false)

        println(s"[$taskName] Creature: $name at ($x,$y,$z), isDead=$isDead")

        if (isDead) {
          val tileKey = generatePositionKey(x, y, z)
          val lootImmediateExists =
            state.autoLoot.carcassToLootImmediately.exists(_._3 == creatureId)
          val lootAfterFightExists =
            state.autoLoot.carcassToLootAfterFight.exists(_._3 == creatureId)

          if (!lootImmediateExists && !lootAfterFightExists) {
            settings.autoTargetSettings.creatureList
              .map(parseCreature)
              .find(_.name.equalsIgnoreCase(name)) match {

              case Some(cs) if cs.lootMonsterImmediately =>
                println(s"[$taskName] Queuing immediate loot for $name at $tileKey (id: $creatureId), switching to looting…")
                updatedState = updatedState
                  // mark that we’re now looting so UpdateAttackStatus will skip its reset logic
                  .copy(caveBot = updatedState.caveBot.copy(stateHunting = "looting in progress"))
                  .copy(autoLoot = updatedState.autoLoot.copy(
                    carcassToLootImmediately =
                      updatedState.autoLoot.carcassToLootImmediately :+ (tileKey, currentTime, creatureId)
                  ))

              case Some(cs) if cs.lootMonsterAfterFight =>
                println(s"[$taskName] Queuing post‐fight loot for $name at $tileKey (id: $creatureId)")
                updatedState = updatedState.copy(autoLoot =
                  updatedState.autoLoot.copy(
                    carcassToLootAfterFight =
                      updatedState.autoLoot.carcassToLootAfterFight :+ (tileKey, currentTime, creatureId)
                  )
                )

              case Some(_) =>
                println(s"[$taskName] Loot disabled for $name")

              case None =>
                println(s"[$taskName] No autoTargetSettings for $name")
            }
          }
        }
      }

      println(s"[$taskName] After run: immediately=${updatedState.autoLoot.carcassToLootImmediately.size}, afterFight=${updatedState.autoLoot.carcassToLootAfterFight.size}")
      Some((updatedState, NoOpTask))
    }

    private def generatePositionKey(x: Int, y: Int, z: Int): String =
      f"$x$y$z"  // or keep your previous f"$x$y${z}%02d" if that’s what your map key expects
  }



  private object ProcessLootingInformation extends Step {
    private val taskName = "ProcessLootingInformation"

    override def run(
                      state:   GameState,
                      json:    JsValue,
                      settings: UISettings
                    ): Option[(GameState, MKTask)] = {
      println(s"[ProcessLootingInformation] stateHunting = ${state.caveBot.stateHunting}")

      // Retrieve battle info
      val shootable = getBattleShootableCreaturesList(json)
      val attackId  = (json \ "attackInfo" \ "Id").asOpt[Int]

      // Handle attack end or free state
      var updated = state
      if ((state.caveBot.stateHunting == "attacking" && attackId.isEmpty) || state.caveBot.stateHunting == "free")  {
        println(s"[ProcessLootingInformation] Evaluating loot queues: immediate=${state.autoLoot.carcassToLootImmediately}, postFight=${state.autoLoot.carcassToLootAfterFight}")

        if (state.autoLoot.carcassToLootImmediately.nonEmpty) {
          updated = startLooting(state, json, state.autoLoot.carcassToLootImmediately, true)
        } else if (state.autoLoot.carcassToLootAfterFight.nonEmpty && shootable.isEmpty) {
          updated = startLooting(state, json, state.autoLoot.carcassToLootAfterFight, false)
        }

      } else if (state.caveBot.stateHunting == "looting in progress") {
        println("[ProcessLootingInformation] Already looting → evaluate next tile")
      }

      Some((updated, NoOpTask))
    }


    // Sort helper: takes the JSON context and list of carcasses
    private def sortByDistance(json: JsValue, list: List[(String, Long, String)]): List[(String, Long, String)] =
      sortTileListByCharacterProximity(json, list)

    // Unified looting starter: requires state, JSON, list and immediacy flag
    private def startLooting(
                              state:    GameState,
                              json:     JsValue,
                              list:     List[(String, Long, String)],
                              isImmediate: Boolean
                            ): GameState = {
      val sorted = if (list.size > 1) sortByDistance(json, list) else list
      val (tile, time, _) = sorted.head
      println(s"[ProcessLootingInformation] Looting ${if (isImmediate) "immediate" else "post-fight"} from $tile")

      val updatedAutoLoot = if (isImmediate) state.autoLoot.copy(
        carcassToLootImmediately = sorted.tail,
        carcassTileToLoot        = Some((tile, time))
      ) else state.autoLoot.copy(
        carcassToLootAfterFight  = sorted.tail,
        carcassTileToLoot        = Some((tile, time))
      )

      state.copy(
        autoLoot = updatedAutoLoot,
        caveBot  = state.caveBot.copy(stateHunting = "looting in progress")
      )
    }

  }



  object ProcessLooting extends Step {
    private val taskName = "ProcessLooting"

    override def run(state: GameState, json: JsValue, settings: UISettings): Option[(GameState, MKTask)] = {

      println(s"[ProcessLooting] Start stateLooting: ${state.autoLoot.stateLooting}, stateLootPlunder: ${state.autoLoot.stateLootPlunder}.")
      println(s"[ProcessLooting] Start stateHunting: ${state.caveBot.stateHunting}, stateAutoTarget: ${state.autoTarget.stateAutoTarget}.")

      if (state.caveBot.stateHunting == "looting in progress") {
        state.autoLoot.stateLooting match {
          case "free" => handleLootOrMoveCarcass(json, settings, state)
          case "moving carcass" => handleMovingOldCarcass(json, settings, state)
          case "opening carcass" => handleOpeningCarcass(json, settings, state)
          case "clicking open button" => handleClickingOpen(json, settings, state)
          case "loot plunder" => handleLootPlunder(json, settings, state)
          case _ => Some((state, NoOpTask))
        }
      } else {
        Some((state, NoOpTask))
        }

    }
    }


  private def sortTileListByCharacterProximity(
                                                json: JsValue,
                                                tileList: List[(String, Long, String)]
                                              ): List[(String, Long, String)] = {
    // Extract character position
    val (charX, charY, charZ) = (json \ "characterInfo").asOpt[JsObject].map { characterInfo =>
      val x = (characterInfo \ "PositionX").asOpt[Int].getOrElse(0)
      val y = (characterInfo \ "PositionY").asOpt[Int].getOrElse(0)
      val z = (characterInfo \ "PositionZ").asOpt[Int].getOrElse(0)
      (x, y, z)
    }.getOrElse((0, 0, 0))

    // Helper to parse tileKey
    def extractTilePosition(tile: String): (Int, Int, Int) = {
      val posX = tile.substring(0, 5).toInt
      val posY = tile.substring(5, 10).toInt
      val posZ = tile.substring(10, 12).toInt
      (posX, posY, posZ)
    }

    def calculateDistance(x1: Int, y1: Int, z1: Int, x2: Int, y2: Int, z2: Int): Double =
      math.sqrt(math.pow(x2 - x1, 2) + math.pow(y2 - y1, 2) + math.pow(z2 - z1, 2))

    tileList.sortBy { case (tile, _, _) =>
      val (tileX, tileY, tileZ) = extractTilePosition(tile)
      calculateDistance(tileX, tileY, tileZ, charX, charY, charZ)
    }
  }

  // Assume these are defined elsewhere
  private def getBattleShootableCreaturesList(json: JsValue): List[String] = {
    // Extract the battleInfo from the JSON
    val battleInfo = (json \ "battleInfo").asOpt[Map[String, JsValue]].getOrElse(Map.empty)

    // Filter for creatures that are monsters and shootable
    val battleShootableCreaturesList = battleInfo.flatMap { case (_, data) =>
      val isMonster = (data \ "IsMonster").asOpt[Boolean].getOrElse(false)
      val isShootable = (data \ "IsShootable").asOpt[Boolean].getOrElse(false)

      if (isMonster && isShootable) {
        Some((data \ "Name").as[String]) // Add the creature name to the list
      } else {
        None
      }
    }.toList

    // Return the filtered list (could be empty if no creatures match)
    battleShootableCreaturesList
  }

  private object CheckForOkButton extends Step {
    private val taskName = "pressOkButton"

    def run(state: GameState, json: JsValue, settings: UISettings): Option[(GameState, MKTask)] = {
      val now      = System.currentTimeMillis()
      val lastTry  = state.fishing.lastFishingCommandSent
      val minDelay = state.fishing.retryMidDelay

      // 1) extract the extraWindowLoc object
      (json \ "screenInfo" \ "extraWindowLoc").validate[JsObject].asOpt
        // 2) only proceed if this is the “Move Objects” popup
        .filter(loc => loc.keys.exists(_ == "Move Objects"))
        // 3) try to find the Ok button inside it
        .flatMap { loc =>
          (loc \ "Ok").validate[JsObject].asOpt.flatMap { okBtn =>
            for {
              x <- (okBtn \ "posX").validate[Int].asOpt
              y <- (okBtn \ "posY").validate[Int].asOpt
            } yield (x, y)
          }
        }
        // 4) rate‐limit by retryMidDelay
        .filter { case (_, _) =>
          lastTry == 0 || (now - lastTry) >= minDelay
        }
        // 5) map into the actual click task
        .map { case (x, y) =>
          val mkActions = MKActions(
            List(
              MoveMouse(x, y),
              LeftButtonPress(x, y),
              LeftButtonRelease(x, y)
            ),
            Nil
          )
          val task = MKTask(taskName, mkActions)

          val newGeneral = state.general.copy(
            lastActionCommand   = Some(taskName),
            lastActionTimestamp = Some(now)
          )
          val newFishing = state.fishing.copy(
            lastFishingCommandSent = now
          )

          (state.copy(general = newGeneral, fishing = newFishing), task)
        }
    }
  }
  def handleLootOrMoveCarcass(json: JsValue, settings: UISettings, state: GameState): Option[(GameState, MKTask)] = {
    println("[handleLootOrMoveCarcass] invoked")
    val autoLootState = state.autoLoot
    println(s"[handleLootOrMoveCarcass] carcassToLootImmediately: ${autoLootState.carcassToLootImmediately}")
    println(s"[handleLootOrMoveCarcass] carcassToLootAfterFight: ${autoLootState.carcassToLootAfterFight}")
    println(s"[handleLootOrMoveCarcass] carcassTileToLoot: ${autoLootState.carcassTileToLoot}")
    println(s"[handleLootOrMoveCarcass] lastLootedCarcassTile: ${autoLootState.lastLootedCarcassTile}")

    val nextState = autoLootState.carcassTileToLoot match {
      case Some((carcassTile, timeOfDeath)) =>
        println(s"[handleLootOrMoveCarcass] Found carcassTileToLoot=$carcassTile at time=$timeOfDeath")
        autoLootState.lastLootedCarcassTile match {
          case Some((lastTile, _)) if lastTile == carcassTile =>
            println(s"[handleLootOrMoveCarcass] carcassTile $carcassTile already moved last, switching to moving carcass state")
            state.copy(autoLoot = autoLootState.copy(stateLooting = "moving carcass"))

          case _ =>
            println(s"[handleLootOrMoveCarcass] new carcassTile $carcassTile, opening carcass")
            state.copy(autoLoot = autoLootState.copy(
              stateLooting = "opening carcass",
              lastLootedCarcassTile = Some((carcassTile, timeOfDeath))
            ))
        }

      case None =>
        println("[handleLootOrMoveCarcass] no carcass to loot, setting state to free")
        state.copy(autoLoot = autoLootState.copy(stateLooting = "free"))
    }

    println(s"[handleLootOrMoveCarcass] next autoLoot.stateLooting = ${nextState.autoLoot.stateLooting}")
    Some((nextState, NoOpTask))
  }



  def handleMovingOldCarcass(json: JsValue, settings: UISettings, state: GameState): Option[(GameState, MKTask)] = {
    val currentTime = System.currentTimeMillis()
    val autoLoot = state.autoLoot
    val timeSinceLastAction = currentTime - autoLoot.lastAutoLootAction

    println(s"[handleMovingOldCarcass] Time since last action: $timeSinceLastAction ms")
    println(s"[handleMovingOldCarcass] carcassToLootImmediately: ${autoLoot.carcassToLootImmediately}")
    println(s"[handleMovingOldCarcass] carcassToLootAfterFight: ${autoLoot.carcassToLootAfterFight}")

    if (timeSinceLastAction < 400)
      return Some((state, NoOpTask))

    val excludedTilesGrid = (autoLoot.carcassToLootImmediately ++ autoLoot.carcassToLootAfterFight)
      .flatMap { case (tileId, _, _) => convertGameLocationToGrid(json, tileId) }.toSet

    autoLoot.lastLootedCarcassTile match {
      case Some((carcassToMove, _)) =>
        extractItemPositionFromMapOnScreen(json, carcassToMove) match {
          case Some((itemX, itemY)) =>
            val possibleTiles = List("7x5", "7x6", "7x7", "8x5", "8x6", "8x7", "9x5", "9x6", "9x7")
            val areaInfo = (json \ "areaInfo").as[JsObject]

            val walkableTileOpt = findRandomWalkableTile(areaInfo, possibleTiles.filterNot(tile =>
              excludedTilesGrid.contains(tile) || tile == convertGameLocationToGrid(json, carcassToMove).getOrElse("")
            ))

            walkableTileOpt match {
              case Some(tileIndex) =>
                val mapPanelMap = (json \ "screenInfo" \ "mapPanelLoc").as[Map[String, JsObject]]
                val tileObj = mapPanelMap.getOrElse(tileIndex, JsObject.empty)
                val (targetX, targetY) = ((tileObj \ "x").head.as[Int], (tileObj \ "y").head.as[Int])

                val actions = moveSingleItem(itemX, itemY, targetX, targetY)
                val newState = state.copy(
                  autoLoot = autoLoot.copy(
                    stateLooting = "opening carcass",
                    lastAutoLootAction = currentTime
                  )
                )
                Some((newState, MKTask("handleMovingOldCarcas", MKActions(actions, Nil))))

              case None =>
                Some((state.copy(
                  autoLoot = autoLoot.copy(stateLooting = "free"),
                  caveBot = state.caveBot.copy(stateHunting = "loot or fight or free")
                ), NoOpTask))
            }

          case None =>
            Some((state.copy(
              autoLoot = autoLoot.copy(stateLooting = "free"),
              caveBot = state.caveBot.copy(stateHunting = "loot or fight or free")
            ), NoOpTask))
        }

      case None =>
        Some((state.copy(
          autoLoot = autoLoot.copy(stateLooting = "free"),
          caveBot = state.caveBot.copy(stateHunting = "loot or fight or free")
        ), NoOpTask))
    }
  }

  def handleOpeningCarcass(json: JsValue, settings: UISettings, state: GameState): Option[(GameState, MKTask)] = {
    val currentTime        = System.currentTimeMillis()
    val autoLoot           = state.autoLoot
    val timeSinceLastAction = currentTime - autoLoot.lastAutoLootAction

    println(s"[handleOpeningCarcass] Entered — stateLooting = ${autoLoot.stateLooting}, lastAutoLootAction = ${autoLoot.lastAutoLootAction}, now = $currentTime, Δ = $timeSinceLastAction ms")
    println(s"[handleOpeningCarcass] carcassTileToLoot = ${autoLoot.carcassTileToLoot}")

    if (timeSinceLastAction < 400) {
      println(s"[handleOpeningCarcass] Too soon since last action (<400ms) → NoOp")
      return Some((state, NoOpTask))
    }

    autoLoot.carcassTileToLoot match {
      case Some((carcassTileToLoot, deathTime)) =>
        println(s"[handleOpeningCarcass] Will open carcass at $carcassTileToLoot, died at $deathTime")

        // parse map coords
        val carcassPos = Vec(
          carcassTileToLoot.substring(0, 5).toInt,
          carcassTileToLoot.substring(5, 10).toInt
        )
        val charPos = Vec(
          (json \ "characterInfo" \ "PositionX").as[Int],
          (json \ "characterInfo" \ "PositionY").as[Int]
        )
        val chebyshevDist = math.max(
          math.abs(carcassPos.x - charPos.x),
          math.abs(carcassPos.y - charPos.y)
        )
        println(s"[handleOpeningCarcass] carcassPos=$carcassPos, charPos=$charPos, chebyshevDist=$chebyshevDist")

        if (chebyshevDist <= 1) {
          // right‐click to open
          val mapPanelMap = (json \ "screenInfo" \ "mapPanelLoc").as[Map[String, JsObject]]
          val screenCoordsOpt = mapPanelMap.values.collectFirst {
            case obj if (obj \ "id").asOpt[String].contains(carcassTileToLoot) =>
              for {
                x <- (obj \ "x").asOpt[Int]
                y <- (obj \ "y").asOpt[Int]
              } yield (x, y)
          }.flatten

          println(s"[handleOpeningCarcass] screenCoordsOpt = $screenCoordsOpt")

          screenCoordsOpt match {
            case Some((x, y)) =>
              println(s"[handleOpeningCarcass] Issuing right‐click at ($x,$y)")
              val actions = List(
                MoveMouse(x, y),
                RightButtonPress(x, y),
                RightButtonRelease(x, y)
              )
              val newState = state.copy(autoLoot = autoLoot.copy(
                stateLooting       = "loot plunder",
                lastAutoLootAction = currentTime
              ))
              Some((newState, MKTask("opening carcass", MKActions(mouse = actions, keyboard = Nil))))

            case None =>
              println("[handleOpeningCarcass] ❌ Couldn't find screen coords for carcassTileToLoot → aborting loot")
              Some((state.copy(
                autoLoot = autoLoot.copy(stateLooting = "free"),
                caveBot  = state.caveBot.copy(stateHunting = "loot or fight or free")
              ), NoOpTask))
          }
        } else {
          // need to path‐find closer
          println(s"[handleOpeningCarcass] Too far (dist=$chebyshevDist) → pathing toward $carcassPos")
          val newState = generateSubwaypointsToGamePosition(carcassPos, state, json)
          val sw = newState.autoLoot.subWaypoints
          println(s"[handleOpeningCarcass] New subWaypoints = $sw")

          if (sw.nonEmpty) {
            val next   = sw.head
            val dirOpt = calculateDirection(charPos, next, newState.autoLoot.lastDirection)
            println(s"[handleOpeningCarcass] Moving one step: dir=$dirOpt toward $next")

            val updatedAutoLoot = newState.autoLoot.copy(
              subWaypoints   = sw.tail,
              lastDirection  = dirOpt,
              lastAutoLootAction = currentTime
            )
            val updatedState = newState.copy(autoLoot = updatedAutoLoot)
            val kbActions = dirOpt.toList.map(DirectionalKey(_))
            Some((updatedState, MKTask("approachingLoot", MKActions(mouse = Nil, keyboard = kbActions))))
          } else {
            println("[handleOpeningCarcass] No subWaypoints → giving up and resetting to free")
            Some((newState.copy(
              autoLoot = autoLoot.copy(stateLooting = "free"),
              caveBot  = state.caveBot.copy(stateHunting = "loot or fight or free")
            ), NoOpTask))
          }
        }

      case None =>
        println("[handleOpeningCarcass] carcassTileToLoot is None → resetting to free")
        Some((state.copy(
          autoLoot = autoLoot.copy(stateLooting = "free"),
          caveBot  = state.caveBot.copy(stateHunting = "loot or fight or free")
        ), NoOpTask))
    }
  }


  def handleClickingOpen(json: JsValue, settings: UISettings, state: GameState): Option[(GameState, MKTask)] = {
    val currentTime = System.currentTimeMillis()
    val autoLoot = state.autoLoot
    val timeSinceLastAction = currentTime - autoLoot.lastAutoLootAction

    if (timeSinceLastAction > 1000)
      return Some((state.copy(
        autoLoot = autoLoot.copy(stateLooting = "free"),
        caveBot = state.caveBot.copy(stateHunting = "loot or fight or free")
      ), NoOpTask))

    val openButtonOpt = (json \ "extraWindowLoc").asOpt[JsObject]
      .flatMap(_.asOpt[JsObject])
      .flatMap(_ \ "Open" match {
        case js if js.isInstanceOf[JsObject] =>
          for {
            posX <- (js \ "posX").asOpt[Int]
            posY <- (js \ "posY").asOpt[Int]
          } yield (posX, posY)
        case _ => None
      })

    openButtonOpt match {
      case Some((x, y)) =>
        val actions = List(
          MoveMouse(x, y),
          LeftButtonPress(x, y),
          LeftButtonRelease(x, y)
        )
        val newState = state.copy(autoLoot = autoLoot.copy(stateLooting = "loot plunder"))
        Some((newState, MKTask("pressing open", MKActions(mouse = actions, keyboard = Nil))))

      case None => Some((state, NoOpTask))
    }
  }

  def handleLootPlunder(json: JsValue, settings: UISettings, state: GameState): Option[(GameState, MKTask)] = {
    println(s"Start handleLootPlunder. stateLooting: ${state.autoLoot.stateLooting}, stateLootPlunder: ${state.autoLoot.stateLootPlunder}.")

    state.autoLoot.stateLootPlunder match {
      case "free" => handleAssessLoot(json, settings, state)
      case "move item" => handleMoveItem(json, settings, state)
      case "handle food" => handleEatingFood(json, settings, state)
      case "open subcontainer" => handleOpenSubcontainer(json, settings, state)
      case _ =>
        Some((state.copy(autoLoot = state.autoLoot.copy(
          stateLooting = "free",
          stateLootPlunder = "free"
        )), NoOpTask))
    }
  }

  def handleAssessLoot(json: JsValue, settings: UISettings, state: GameState): Option[(GameState, MKTask)] = {
    val gen                = state.general
    val ignoreContainers   = gen.initialContainersList.toSet
    val containersInfoOpt = (json \ "containersInfo").asOpt[JsObject]
    val screenInfoOpt     = (json \ "screenInfo").asOpt[JsObject]

    for {
      containersInfo   <- containersInfoOpt
      screenInfo       <- screenInfoOpt
      newKeys           = containersInfo.keys.toList.filterNot(ignoreContainers.contains).sorted
      lastContainerKey <- newKeys.lastOption
      lastContainer    <- (containersInfo \ lastContainerKey).asOpt[JsObject]
      itemsValue       <- (lastContainer \ "items").toOption
    } yield {
      println(s"[handleAssessLoot] Ignoring initialContainers: $ignoreContainers")
      println(s"[handleAssessLoot] Found new lastContainerKey: $lastContainerKey with items: $itemsValue")
      println(s"[handleAssessLoot] Loot settings: ${settings.autoLootSettings.lootList}")


      itemsValue match {
        case JsString("empty") =>
          println("[handleAssessLoot] Container empty. Resetting states.")
          val updated = state.copy(
            autoLoot = state.autoLoot.copy(
              stateLooting     = "free",
              stateLootPlunder = "free"
            ),
            caveBot = state.caveBot.copy(stateHunting = "loot or fight or free")
          )
          (updated, NoOpTask)


        case itemsObj: JsObject =>
          // parse each comma-separated setting entry "id, action, name"
          val lootActions: Map[Int, String] =
            settings.autoLootSettings.lootList.iterator.flatMap { entry =>
              entry.trim.split(",\\s*") match {
                case Array(idStr, action, _*) =>
                  try Some(idStr.toInt -> action.trim)
                  catch { case _: NumberFormatException => None }
                case _ =>
                  None
              }
            }.toMap

          println(s"[handleAssessLoot] Parsed lootActions: $lootActions")

          // now find the first item whose ID is in the map
          val foundLoot = itemsObj.fields.collectFirst {
            case (slot, info) if lootActions.contains((info \ "itemId").as[Int]) =>
              (slot, info, lootActions((info \ "itemId").as[Int]))
          }

          foundLoot match {
            case Some((slot, info, action)) =>
              val itemId    = (info \ "itemId").as[Int]
              val itemCount = (info \ "itemCount").as[Int]
              val itemSlot  = slot.replace("slot", "item")


              val invPanelLoc = (screenInfo \ "inventoryPanelLoc").asOpt[JsObject].getOrElse {
                println("[handleAssessLoot] ❗️ inventoryPanelLoc is missing or not an object")
                JsObject.empty
              }
              val invKeys = invPanelLoc.keys.toList
              println(s"[handleAssessLoot] invPanelLoc keys: $invKeys")

              // find the one whose key begins with your containerX prefix
              val matchedContainerKey = invKeys
                .find(_.startsWith(lastContainerKey))
                .getOrElse {
                  println(s"[handleAssessLoot] ❗️ No inventoryPanelLoc key startsWith '$lastContainerKey', defaulting to literal")
                  lastContainerKey
                }

              println(s"[handleAssessLoot] matched pickup container key = '$matchedContainerKey'")
              println(s"[handleAssessLoot] itemSlot = '$itemSlot'")

              // now grab the contentsPanel→itemN under that matched key
              val pickupPos = (screenInfo \ "inventoryPanelLoc" \ matchedContainerKey \ "contentsPanel" \ itemSlot)
                .asOpt[JsObject]
                .map(s => Vec((s \ "x").as[Int], (s \ "y").as[Int]))
                .getOrElse {
                  println(s"[DEBUG] ❗️ Couldn't find contentsPanel→$itemSlot under inventoryPanelLoc→'$matchedContainerKey'")
                  Vec(0,0)
                }
              println(s"[DEBUG] Pickup at $pickupPos, action = '$action'")

              // figure out drop-position
              val dropPos: Vec =
                if (action == "g") {
                  // find and reserve a ground tile here
                  val excluded = (state.autoLoot.carcassToLootImmediately ++ state.autoLoot.carcassToLootAfterFight)
                    .flatMap { case (tileId,_,_) => convertGameLocationToGrid(json, tileId) }
                    .toSet

                  val tiles = List("7x5","7x6","7x7","8x5","8x6","8x7","9x5","9x6","9x7")
                  val freeTileOpt = findRandomWalkableTile((json \ "areaInfo").as[JsObject], tiles.filterNot(excluded.contains))

                  freeTileOpt.flatMap { tidx =>
                    (screenInfo \ "mapPanelLoc" \ tidx).asOpt[JsObject].map { m =>
                      Vec((m \ "x").as[Int], (m \ "y").as[Int])
                    }
                  }.getOrElse(Vec(0,0))

                }  else if (action.matches("\\d+")) {
                  // figure out drop-position
                  val targetPrefix = s"container$action"
                  val destKey = invKeys
                    .find(_.startsWith(targetPrefix))
                    .getOrElse {
                      println(s"[DEBUG] ❗️ No inventoryPanelLoc key startsWith '$targetPrefix', defaulting to literal")
                      targetPrefix
                    }
                  println(s"[DEBUG] matched drop container key = '$destKey'")

                  val dropPos = (invPanelLoc \ destKey \ "contentsPanel" \ "item0")
                    .asOpt[JsObject]
                    .map(s => Vec((s \ "x").as[Int], (s \ "y").as[Int]))
                    .getOrElse {
                      println(s"[DEBUG] ❗️ Couldn't find contentsPanel→item0 under inventoryPanelLoc→'$destKey'")
                      Vec(0,0)
                    }
                  println(s"[handleAssessLoot] Drop at $dropPos")

                  dropPos
                }
                else {
                  println(s"[DEBUG] Unknown action '$action' → dropPos = Vec(0,0)")
                  Vec(0,0)
                }


              val newState = state.copy(autoLoot = state.autoLoot.copy(
                stateLootPlunder          = "move item",
                lootIdToPlunder           = itemId,
                lootCountToPlunder        = itemCount,
                lootScreenPosToPlunder    = pickupPos,
                dropScreenPosToPlunder    = dropPos
              ))
              (newState, NoOpTask)

            case None =>
              println("[handleAssessLoot] No primary loot found, searching for food.")
              val foundFood = itemsObj.fields.collectFirst {
                case (slot, itemInfo) if StaticGameInfo.Items.FoodsIds.contains((itemInfo \ "itemId").as[Int]) =>
                  println(s"[handleAssessLoot] Found food in slot $slot: itemId=${(itemInfo \ "itemId").as[Int]}")
                  (slot, itemInfo)
              }

              foundFood match {
                case Some((slot, foodInfo)) =>
                  val itemSlot = slot.replace("slot", "item")

                  // reuse invPanelLoc & invKeys
                  val invPanelLoc = (screenInfo \ "inventoryPanelLoc").asOpt[JsObject].getOrElse(JsObject.empty)
                  val invKeys     = invPanelLoc.keys.toList

                  // find the full container key
                  val matchedContainerKey = invKeys
                    .find(_.startsWith(lastContainerKey))
                    .getOrElse {
                      println(s"[handleAssessLoot] ❗️ No inventoryPanelLoc key startsWith '$lastContainerKey', defaulting to literal")
                      lastContainerKey
                    }
                  println(s"[handleAssessLoot] Using container key = '$matchedContainerKey' for food")

                  // pull out the item’s coords
                  val lootScreenPos = (invPanelLoc \ matchedContainerKey \ "contentsPanel" \ itemSlot)
                    .asOpt[JsObject]
                    .map(s => Vec((s \ "x").as[Int], (s \ "y").as[Int]))
                    .getOrElse {
                      println(s"[handleAssessLoot] ❗️ Couldn't find contentsPanel→$itemSlot under '$matchedContainerKey'")
                      Vec(0,0)
                    }
                  println(s"[handleAssessLoot] Food screen position: $lootScreenPos")

                  val newState = state.copy(autoLoot = state.autoLoot.copy(
                    stateLootPlunder = "handle food",
                    lootIdToPlunder = (foodInfo \ "itemId").as[Int],
                    lootCountToPlunder = (foodInfo \ "itemCount").as[Int],
                    lootScreenPosToPlunder = lootScreenPos
                  ))
                  println(s"[handleAssessLoot] Updated state for food: $newState")
                  (newState, NoOpTask)

                case None =>
                  println("[handleAssessLoot] No food found, searching for containers.")
                  val foundContainer = itemsObj.fields.collectFirst {
                    case (slot, itemInfo) if (itemInfo \ "isContainer").asOpt[Boolean].getOrElse(false) =>
                      println(s"[handleAssessLoot] Found subcontainer in slot $slot: itemId=${(itemInfo \ "itemId").as[Int]}")
                      (slot, itemInfo)
                  }

                  foundContainer match {
                    case Some((slot, containerInfo)) =>
                      val itemSlot = slot.replace("slot", "item")

                      // reuse invPanelLoc & invKeys
                      val invPanelLoc = (screenInfo \ "inventoryPanelLoc").asOpt[JsObject].getOrElse(JsObject.empty)
                      val invKeys     = invPanelLoc.keys.toList

                      // full key lookup
                      val matchedContainerKey = invKeys
                        .find(_.startsWith(lastContainerKey))
                        .getOrElse {
                          println(s"[handleAssessLoot] ❗️ No inventoryPanelLoc key startsWith '$lastContainerKey', defaulting to literal")
                          lastContainerKey
                        }
                      println(s"[handleAssessLoot] Using container key = '$matchedContainerKey' for subcontainer")

                      // get coords
                      val lootScreenPos = (invPanelLoc \ matchedContainerKey \ "contentsPanel" \ itemSlot)
                        .asOpt[JsObject]
                        .map(s => Vec((s \ "x").as[Int], (s \ "y").as[Int]))
                        .getOrElse {
                          println(s"[handleAssessLoot] ❗️ Couldn't find contentsPanel→$itemSlot under '$matchedContainerKey'")
                          Vec(0,0)
                        }
                      println(s"[handleAssessLoot] Subcontainer screen position: $lootScreenPos")

                      val newState = state.copy(autoLoot = state.autoLoot.copy(
                        stateLootPlunder = "open subcontainer",
                        lootIdToPlunder = (containerInfo \ "itemId").as[Int],
                        lootScreenPosToPlunder = lootScreenPos
                      ))
                      println(s"[handleAssessLoot] Updated state for opening container: $newState")
                      (newState, NoOpTask)

                    case None =>
                      println("[handleAssessLoot] Nothing to loot. Resetting states.")
                      val updated = state.copy(
                        autoLoot = state.autoLoot.copy(
                          stateLooting = "free",
                          stateLootPlunder = "free",
                          lootIdToPlunder = 0,
                          lootCountToPlunder = 0
                        ),
                        caveBot = state.caveBot.copy(stateHunting = "loot or fight or free")
                      )
                      println(s"[handleAssessLoot] New state after reset: $updated")
                      (updated, NoOpTask)
                  }
              }
          }

        case _ =>
          println("[handleAssessLoot] Items value has unexpected type. Resetting states.")
          val updated = state.copy(
            autoLoot = state.autoLoot.copy(
              stateLooting = "free",
              stateLootPlunder = "free"
            ),
            caveBot = state.caveBot.copy(stateHunting = "loot or fight or free")
          )
          println(s"[handleAssessLoot] New state: $updated")
          (updated, NoOpTask)
      }
    }
  }


    def handleMoveItem(json: JsValue, settings: UISettings, state: GameState)
    : Option[(GameState, MKTask)] = {

      println(s"[handleMoveItem] Entered for '${state.autoLoot.stateLootPlunder}'")
      val now     = System.currentTimeMillis()
      val last    = state.autoLoot.lastItemActionCommandSend
      val elapsed = now - last

      // ─────────── debouncing ───────────
      if (last > 0 && elapsed < 1000) {
        println(s"[handleMoveItem] last item action sent $elapsed ms ago — skipping to avoid spamming")
        return None
      }
      // ───────────────────────────────────

      val pickup = state.autoLoot.lootScreenPosToPlunder
      val drop   = state.autoLoot.dropScreenPosToPlunder
      val count  = state.autoLoot.lootCountToPlunder

      // guard against missing positions
      if (pickup == Vec(0,0) || drop == Vec(0,0)) {
        println("[handleMoveItem] Invalid pickup or drop → abort")
        val reset = state.copy(autoLoot = state.autoLoot.copy(stateLootPlunder = "free"))
        return Some((reset, NoOpTask))
      }

      // always the same mouse actions (we’re just dragging one slot)
      val mouseActions = moveSingleItem(pickup.x, pickup.y, drop.x, drop.y)

      // ctrl‐hold for stacks
      val keyboardActions =
        if (count > 1) List(PressCtrl, HoldCtrlFor(1.second), ReleaseCtrl)
        else Nil

      // build the MKTask name straight from stateLootPlunder
      val taskName = state.autoLoot.stateLootPlunder

      // reset all the autoLoot fields and stamp the new timestamp
      val updatedState = state.copy(autoLoot = state.autoLoot.copy(
        stateLootPlunder            = "free",
        lootIdToPlunder             = 0,
        lootCountToPlunder          = 0,
        lootScreenPosToPlunder      = Vec(0,0),
        dropScreenPosToPlunder      = Vec(0,0),
        lastItemActionCommandSend   = now
      ))

      println(s"[handleMoveItem] issuing '$taskName'")
      Some((updatedState,
        MKTask(taskName, MKActions(mouse = mouseActions, keyboard = keyboardActions))
      ))
    }


  def handleEatingFood(json: JsValue, settings: UISettings, state: GameState): Option[(GameState, MKTask)] = {
    println("[handleEatingFood] Entered")
    val pos = state.autoLoot.lootScreenPosToPlunder

    // if we never got a valid food position, bail out
    if (pos == Vec(0, 0)) {
      println("[handleEatingFood] Invalid food position → abort")
      val updated = state.copy(autoLoot = state.autoLoot.copy(stateLootPlunder = "free"))
      return Some((updated, NoOpTask))
    }

    // right-click on the food
    val mouseActions = List(
      MoveMouse(pos.x, pos.y),
      RightButtonPress(pos.x, pos.y),
      RightButtonRelease(pos.x, pos.y)
    )

    // reset our autoLoot state
    val updatedState = state.copy(autoLoot = state.autoLoot.copy(
      stateLootPlunder       = "free",
      lootIdToPlunder        = 0,
      lootCountToPlunder     = 0,
      lootScreenPosToPlunder = Vec(0, 0)
    ))

    println(s"[handleEatingFood] issuing eat food at $pos")
    Some((updatedState, MKTask("eat food", MKActions(mouse = mouseActions, keyboard = Nil))))
  }
  def handleOpenSubcontainer(json: JsValue, settings: UISettings, state: GameState): Option[(GameState, MKTask)] = {
    val pos = state.autoLoot.lootScreenPosToPlunder

    if (pos == Vec(0, 0)) {
      val updated = state.copy(autoLoot = state.autoLoot.copy(stateLootPlunder = "free"))
      return Some((updated, NoOpTask))
    }

    val mouseActions = List(
      MoveMouse(pos.x, pos.y),
      RightButtonPress(pos.x, pos.y),
      RightButtonRelease(pos.x, pos.y)
    )

    val updatedState = state.copy(autoLoot = state.autoLoot.copy(
      stateLootPlunder = "free",
      lootIdToPlunder = 0,
      lootScreenPosToPlunder = Vec(0, 0)
    ))

    Some((updatedState, MKTask("open subcontainer", MKActions(mouse = mouseActions, keyboard = Nil))))
  }




  def convertGameLocationToGrid(json: JsValue, tileId: String): Option[String] = {
    val mapPanelLoc = (json \ "screenInfo" \ "mapPanelLoc").asOpt[JsObject]

    // Look for the tileId in mapPanelLoc and return the corresponding grid key, otherwise return None
    mapPanelLoc.flatMap(_.fields.collectFirst {
      case (gridKey, jsValue) if (jsValue \ "id").asOpt[String].contains(tileId) =>
        gridKey // Return the grid key if the id matches
    })
  }

  def extractItemPositionFromMapOnScreen(json: JsValue, carcassTileToLoot: String): Option[(Int, Int)] = {
    // Extract the key from the carcassTileToLoot (in this case, "345323456507" format)
    val posX = carcassTileToLoot.substring(0, 5)
    val posY = carcassTileToLoot.substring(5, 10)
    val posZ = carcassTileToLoot.substring(10, 12)

    // Search for the position in the mapPanelLoc
    val mapPanelLoc = (json \ "screenInfo" \ "mapPanelLoc").asOpt[JsObject]
    val tileKeyOpt = mapPanelLoc.flatMap(_.fields.collectFirst {
      case (tileIndex, jsValue) if (jsValue \ "id").asOpt[String].contains(carcassTileToLoot) =>
        (jsValue \ "x").asOpt[Int].getOrElse(0) -> (jsValue \ "y").asOpt[Int].getOrElse(0)
    })

    tileKeyOpt match {
      case Some((x, y)) =>
        println(s"Found coordinates for $carcassTileToLoot: x=$x, y=$y")
        Some((x, y))
      case None =>
        println(s"No coordinates found for $carcassTileToLoot")
        None
    }
  }

  def moveSingleItem(xItemPosition: Int, yItemPositon: Int, xDestPos: Int, yDestPos: Int): List[MouseAction] =
    List(
      MoveMouse(xItemPosition, yItemPositon),
      LeftButtonPress(xItemPosition, yItemPositon),
      MoveMouse(xDestPos, yDestPos),
      LeftButtonRelease(xDestPos, yDestPos)
    )


  def findRandomWalkableTile(areaInfo: JsObject, possibleTiles: List[String]): Option[String] = {
    println("Inside findRandomWalkableTile")
    val allMovementEnablerIds: List[Int] = StaticGameInfo.LevelMovementEnablers.AllIds
    // Extract the tiles information from the area info JSON object
    val tilesInfo = (areaInfo \ "tiles").as[JsObject]

    // Collect all indices of walkable tiles that do not have blocking items or carcasses
    val allWalkableIndices = tilesInfo.fields.collect {
      case (tileId, tileObj: JsObject) if possibleTiles.contains((tileObj \ "index").asOpt[String].getOrElse("")) =>
        val tileIsWalkable = (tileObj \ "isWalkable").asOpt[Boolean].getOrElse(false)
        val tileItems = (tileObj \ "items").asOpt[JsObject].getOrElse(Json.obj())

        // Check if the tile contains any blocking items (i.e., carcasses or movement blockers)
        val hasBlockingItem = tileItems.fields.exists { case (_, itemObj) =>
          allMovementEnablerIds.contains((itemObj \ "id").asOpt[Int].getOrElse(0))
        }

        // Only consider tiles that are walkable and do not contain blocking items
        if (tileIsWalkable && !hasBlockingItem) {
          (tileObj \ "index").as[String]
        } else {
          // Skip this tile if it has blocking items or is not walkable
          ""
        }
    }.filterNot(_.isEmpty).toList // Filter out any empty results

    // Shuffle the list of all walkable indices and return one at random
    Random.shuffle(allWalkableIndices).headOption
  }

  def generateSubwaypointsToGamePosition(target: Vec, state: GameState, json: JsValue): GameState = {
    val tiles = (json \ "areaInfo" \ "tiles").as[Map[String, JsObject]]
    val xs = tiles.keys.map(_.substring(0, 5).trim.toInt)
    val ys = tiles.keys.map(_.substring(5, 10).trim.toInt)
    val gridBounds = (xs.min, ys.min, xs.max, ys.max)

    val (grid, (minX, minY)) = createBooleanGrid(tiles, xs.min, ys.min)

    val charPos = Vec(
      (json \ "characterInfo" \ "PositionX").as[Int],
      (json \ "characterInfo" \ "PositionY").as[Int]
    )

    val path =
      if (charPos != target)
        aStarSearch(charPos, target, grid, minX, minY).filterNot(_ == charPos)
      else
        List.empty

    val updatedAutoLoot = state.autoLoot.copy(
      subWaypoints = path,
      gridBoundsState = gridBounds,
      gridState = grid,
      currentWaypointLocation = target
    )

    state.copy(
      autoLoot = updatedAutoLoot,
    )
  }



}


