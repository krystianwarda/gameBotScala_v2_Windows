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
      ProcessStateHunting,
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


  private object DetectDeadCreatures extends Step {
    private val taskName = "DetectDeadCreatures"

    override def run(state: GameState, json: JsValue, settings: UISettings): Option[(GameState, MKTask)] = {
      val currentTime = System.currentTimeMillis()
      val killedCreatures = (json \ "lastKilledCreatures").asOpt[JsObject].getOrElse(Json.obj())

      var updatedState = state
      var logs = Vector.empty[String]

      println(s"[$taskName] Running DetectDeadCreatures... Found ${killedCreatures.fields.size} entries.")

      killedCreatures.fields.foreach { case (_, creatureInfo) =>
        val name = (creatureInfo \ "Name").asOpt[String].getOrElse("")
        val x    = (creatureInfo \ "LastPositionX").asOpt[Int].getOrElse(0)
        val y    = (creatureInfo \ "LastPositionY").asOpt[Int].getOrElse(0)
        val z    = (creatureInfo \ "LastPositionZ").asOpt[Int].getOrElse(0)
        val isDead = (creatureInfo \ "IsDead").asOpt[Boolean].getOrElse(false)

        println(s"[$taskName] Creature: $name at ($x, $y, $z), isDead = $isDead")

        if (isDead) {
          val tileKey = generatePositionKey(x, y, z)

          val creatureSettingsOpt = settings.autoTargetSettings.creatureList
            .map(parseCreature)
            .find(_.name.equalsIgnoreCase(name))

          creatureSettingsOpt match {
            case Some(cs) if cs.lootMonsterImmediately =>
              val msg = s"[$taskName] $name will be looted immediately at ($x, $y, $z)."
              logs :+= msg
              println(msg)

              val updatedAutoloot = updatedState.autoLoot.copy(
                carcassToLootImmediately = updatedState.autoLoot.carcassToLootImmediately :+ (tileKey -> currentTime)
              )
              updatedState = updatedState.copy(autoLoot = updatedAutoloot)

            case Some(cs) if cs.lootMonsterAfterFight =>
              val msg = s"[$taskName] $name will be looted after the fight at ($x, $y, $z)."
              logs :+= msg
              println(msg)

              val updatedAutoloot = updatedState.autoLoot.copy(
                carcassToLootAfterFight = updatedState.autoLoot.carcassToLootAfterFight :+ (tileKey -> currentTime)
              )
              updatedState = updatedState.copy(autoLoot = updatedAutoloot)

            case Some(_) =>
              val msg = s"[$taskName] $name has no looting settings."
              logs :+= msg
              println(msg)

            case None =>
              val msg = s"[$taskName] No creature settings found for $name."
              logs :+= msg
              println(msg)
          }
        }
      }

      Some((updatedState, NoOpTask))
    }

    def generatePositionKey(x: Int, y: Int, z: Int): String = f"$x$y${z}%02d"
  }


  object ProcessStateHunting extends Step {
    private val taskName = "ProcessStateHunting"

    override def run(state: GameState, json: JsValue, settings: UISettings): Option[(GameState, MKTask)] = {
      println(s"[$taskName] stateHunting = ${state.caveBot.stateHunting}")
      state.caveBot.stateHunting match {
        case "attacking" => handleAttackingState(json, settings, state)
        case "creature killed" => handleCreatureKilledState(json, settings, state)
        case "loot or fight or free" => handleLootOrFightOrFreeState(json, settings, state)
        case "looting in progress" => handleLooting(json, settings, state)
        case "free" => Some((state, NoOpTask))
        case _ => Some((state, NoOpTask))
      }
    }

    private def handleLootOrFightOrFreeState(json: JsValue, settings: UISettings, state: GameState): Option[(GameState, MKTask)] = {
      println("[handleLootOrFightOrFreeState] Entered handler")
      val shootable = getBattleShootableCreaturesList(json)
      var updatedState = state
      val now = System.currentTimeMillis()

      def sortByDistance(list: List[(String, Long)]): List[(String, Long)] =
        sortTileListByCharacterProximity(json, list)

      if (state.autoLoot.carcassToLootImmediately.nonEmpty) {
        println(s"[handleLootOrFightOrFreeState] Found ${state.autoLoot.carcassToLootImmediately.size} carcasses to loot immediately")
        val sorted = if (state.autoLoot.carcassToLootImmediately.length > 1)
          sortByDistance(state.autoLoot.carcassToLootImmediately)
        else
          state.autoLoot.carcassToLootImmediately

        val (tile, time) = sorted.head
        println(s"[handleLootOrFightOrFreeState] Looting immediately from tile: $tile")
        updatedState = updatedState.copy(
          autoLoot = state.autoLoot.copy(
            carcassToLootImmediately = sorted.tail,
            carcassTileToLoot = Some((tile, time))
          ),
          caveBot = state.caveBot.copy(stateHunting = "looting in progress")
        )
      } else if (state.autoLoot.carcassToLootAfterFight.nonEmpty && shootable.isEmpty) {
        println(s"[handleLootOrFightOrFreeState] Found ${state.autoLoot.carcassToLootAfterFight.size} carcasses to loot after fight and no shootable creatures.")
        val sorted = if (state.autoLoot.carcassToLootAfterFight.length > 1)
          sortByDistance(state.autoLoot.carcassToLootAfterFight)
        else
          state.autoLoot.carcassToLootAfterFight

        val (tile, time) = sorted.head
        println(s"[handleLootOrFightOrFreeState] Looting after fight from tile: $tile")
        updatedState = updatedState.copy(
          autoLoot = state.autoLoot.copy(
            carcassToLootAfterFight = sorted.tail,
            carcassTileToLoot = Some((tile, time))
          ),
          caveBot = state.caveBot.copy(stateHunting = "looting in progress")
        )
      } else {
        println("[handleLootOrFightOrFreeState] No lootable carcasses or still in battle → setting stateHunting to 'free'")
        updatedState = state.copy(caveBot = state.caveBot.copy(stateHunting = "free"))
      }

      Some((updatedState, NoOpTask))
    }


    private def handleAttackingState(json: JsValue, settings: UISettings, state: GameState): Option[(GameState, MKTask)] = {
      val lastAttacked = (json \ "lastAttackedCreatureInfo").asOpt[JsObject].getOrElse(Json.obj())
      val lastId = (lastAttacked \ "LastAttackedId").asOpt[Int].getOrElse(0)
      val isDead = (lastAttacked \ "IsDead").asOpt[Boolean].getOrElse(false)

      val attackId = (json \ "attackInfo" \ "Id").asOpt[Int]

      val updatedState = attackId match {
        case None =>
          if (isDead) {
            state.copy(caveBot = state.caveBot.copy(stateHunting = "creature killed"))
          } else {
            state.copy(caveBot = state.caveBot.copy(stateHunting = "free"))
          }
        case Some(_) =>
          state
      }

      Some((updatedState, NoOpTask))
    }

    private def handleCreatureKilledState(json: JsValue, settings: UISettings, state: GameState): Option[(GameState, MKTask)] = {
      println(s"[Hunting] Last target '${state.autoTarget.lastTargetName}' is killed. Switching to 'loot or fight or free'.")
      val updatedState = state.copy(caveBot = state.caveBot.copy(stateHunting = "loot or fight or free"))
      Some((updatedState, NoOpTask))
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

    private def sortTileListByCharacterProximity(json: JsValue, tileList: List[(String, Long)]): List[(String, Long)] = {
      // Extract character position from JSON
      val (charX, charY, charZ) = (json \ "characterInfo").asOpt[JsObject].map { characterInfo =>
        val x = (characterInfo \ "PositionX").asOpt[Int].getOrElse(0)
        val y = (characterInfo \ "PositionY").asOpt[Int].getOrElse(0)
        val z = (characterInfo \ "PositionZ").asOpt[Int].getOrElse(0)
        (x, y, z)
      }.getOrElse((0, 0, 0)) // Default to (0, 0, 0) if character position is not found

      // Helper function to extract posX, posY, posZ from tile string
      def extractTilePosition(tile: String): (Int, Int, Int) = {
        val posX = tile.substring(0, 5).toInt
        val posY = tile.substring(5, 10).toInt
        val posZ = tile.substring(10, 12).toInt
        (posX, posY, posZ)
      }

      // Helper function to calculate the distance between two 3D points
      def calculateDistance(x1: Int, y1: Int, z1: Int, x2: Int, y2: Int, z2: Int): Double = {
        math.sqrt(math.pow(x2 - x1, 2) + math.pow(y2 - y1, 2) + math.pow(z2 - z1, 2))
      }

      // Sort the tile tuples by their distance to the character's position
      tileList.sortBy { case (tile, _) =>
        val (tileX, tileY, tileZ) = extractTilePosition(tile)
        calculateDistance(tileX, tileY, tileZ, charX, charY, charZ)
      }
    }
  }


  def handleLooting(json: JsValue, settings: UISettings, currentState: GameState): Option[(GameState, MKTask)] = {
    println(s"Start stateLooting: ${currentState.autoLoot.stateLooting}, stateLootPlunder: ${currentState.autoLoot.stateLootPlunder}.")

    currentState.autoLoot.stateLooting match {
      case "free" => handleLootOrMoveCarcass(json, settings, currentState)
      case "moving carcass" => handleMovingOldCarcass(json, settings, currentState)
      case "opening carcass" => handleOpeningCarcass(json, settings, currentState)
      case "clicking open button" => handleClickingOpen(json, settings, currentState)
      case "loot plunder" => handleLootPlunder(json, settings, currentState)
      case _ => Some((currentState, NoOpTask))
    }
  }

  def handleLootOrMoveCarcass(json: JsValue, settings: UISettings, state: GameState): Option[(GameState, MKTask)] = {

    val autoLootState = state.autoLoot

    println(s"[handleLootOrMoveCarcass] carcassToLootImmediately: ${autoLootState.carcassToLootImmediately}")
    println(s"[handleLootOrMoveCarcass] carcassToLootAfterFight: ${autoLootState.carcassToLootAfterFight}")

    val nextState = autoLootState.carcassTileToLoot match {
      case Some((carcassTile, timeOfDeath)) =>
        autoLootState.lastLootedCarcassTile match {
          case Some((lastTile, _)) if lastTile == carcassTile =>
            state.copy(autoLoot = autoLootState.copy(stateLooting = "moving carcass"))

          case _ =>
            state.copy(autoLoot = autoLootState.copy(
              stateLooting = "opening carcass",
              lastLootedCarcassTile = Some((carcassTile, timeOfDeath))
            ))
        }

      case None =>
        state.copy(autoLoot = autoLootState.copy(stateLooting = "free"))
    }

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
      .flatMap { case (tileId, _) => convertGameLocationToGrid(json, tileId) }.toSet

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
    val currentTime = System.currentTimeMillis()
    val autoLoot = state.autoLoot
    val timeSinceLastAction = currentTime - autoLoot.lastAutoLootAction

    if (timeSinceLastAction < 400)
      return Some((state, NoOpTask))

    autoLoot.carcassTileToLoot match {
      case Some((carcassTileToLoot, _)) =>
        val carcassPos = Vec(
          carcassTileToLoot.substring(0, 5).toInt,
          carcassTileToLoot.substring(5, 10).toInt
        )

        val charPos = Vec(
          (json \ "characterInfo" \ "PositionX").as[Int],
          (json \ "characterInfo" \ "PositionY").as[Int]
        )

        val chebyshevDist = math.max(math.abs(carcassPos.x - charPos.x), math.abs(carcassPos.y - charPos.y))

        if (chebyshevDist <= 1) {
          val mapPanelMap = (json \ "screenInfo" \ "mapPanelLoc").as[Map[String, JsObject]]

          val screenCoordsOpt = mapPanelMap.values.collectFirst {
            case obj if (obj \ "id").asOpt[String].contains(carcassTileToLoot) =>
              for {
                x <- (obj \ "x").asOpt[Int]
                y <- (obj \ "y").asOpt[Int]
              } yield (x, y)
          }.flatten

          // check if there is a need to use ctrl open
          if (1 == 1) {
              screenCoordsOpt match {
                case Some((x, y)) =>
                  val actions = List(
                    MoveMouse(x, y),
                    RightButtonPress(x, y),
                    MoveMouse(x, y),
                    RightButtonRelease(x, y)
                  )

                  val newState = state.copy(autoLoot = autoLoot.copy(
                    stateLooting = "loot plunder",
                    lastAutoLootAction = currentTime
                  ))
                  Some((newState, MKTask("opening carcass", MKActions(mouse = actions, keyboard = Nil))))
                case None =>
                  Some((state.copy(
                    autoLoot = autoLoot.copy(stateLooting = "free"),
                    caveBot = state.caveBot.copy(stateHunting = "loot or fight or free")
                  ), NoOpTask))
              }

          } else {
            // placeholder for click with ctrl
            val newState = state.copy(autoLoot = autoLoot.copy(
              stateLooting = "clicking open button",
              lastAutoLootAction = currentTime
            ))
              Some((newState, MKTask("opening carcass with control", MKActions(mouse = Nil, keyboard = Nil))))

          }
        } else {
          val newState = generateSubwaypointsToGamePosition(carcassPos, state, json)
          if (newState.autoLoot.subWaypoints.nonEmpty) {
            val next = newState.autoLoot.subWaypoints.head
            val dirOpt = calculateDirection(charPos, next, newState.autoLoot.lastDirection)

            val updatedAutoLoot = newState.autoLoot.copy(
              subWaypoints = newState.autoLoot.subWaypoints.tail,
              lastDirection = dirOpt
            )
            val updatedState = newState.copy(
              autoLoot = updatedAutoLoot
            )

            val kbActions: List[KeyboardAction] = dirOpt.toList
              .map(DirectionalKey(_))
            Some((updatedState, MKTask("approuchingLoot", MKActions(mouse = Nil, keyboard = kbActions))))

          } else {
            Some((newState.copy(
              autoLoot = autoLoot.copy(stateLooting = "free"),
              caveBot = state.caveBot.copy(stateHunting = "loot or fight or free")
            ), NoOpTask))
          }
        }

      case None =>
        Some((state.copy(
          autoLoot = autoLoot.copy(stateLooting = "free"),
          caveBot = state.caveBot.copy(stateHunting = "loot or fight or free")
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
      case "move item to backpack" => handleMoveItemToBackpack(json, settings, state)
      case "move item to ground" => handleMoveItemToGround(json, settings, state)
      case "handle food" => handleHandleFood(json, settings, state)
      case "open subcontainer" => handleOpenSubcontainer(json, settings, state)
      case _ =>
        Some((state.copy(autoLoot = state.autoLoot.copy(
          stateLooting = "free",
          stateLootPlunder = "free"
        )), NoOpTask))
    }
  }
  def handleAssessLoot(json: JsValue, settings: UISettings, state: GameState): Option[(GameState, MKTask)] = {
    val containersInfoOpt = (json \ "containersInfo").asOpt[JsObject]
    val screenInfoOpt = (json \ "screenInfo").asOpt[JsObject]

    for {
      containersInfo <- containersInfoOpt
      screenInfo     <- screenInfoOpt
      lastContainerKey <- containersInfo.keys.toList.sorted.lastOption
      lastContainer <- (containersInfo \ lastContainerKey).asOpt[JsObject]
      itemsValue <- (lastContainer \ "items").toOption
    } yield {
      itemsValue match {
        case JsString("empty") =>
          val updated = state.copy(
            autoLoot = state.autoLoot.copy(
              stateLooting = "free",
              stateLootPlunder = "free"
            ),
            caveBot = state.caveBot.copy(
              stateHunting = "loot or fight or free"
            )
          )
          (updated, NoOpTask)

        case itemsObj: JsObject =>
          val lootItems = settings.autoLootSettings.lootList.map(_.trim.split(",\\s*")(0).toInt).toSet
          val foundLoot = itemsObj.fields.collectFirst {
            case (slot, itemInfo) if lootItems.contains((itemInfo \ "itemId").as[Int]) =>
              (slot, itemInfo)
          }

          foundLoot match {
            case Some((slot, itemInfo)) =>
              val itemId = (itemInfo \ "itemId").as[Int]
              val itemCount = (itemInfo \ "itemCount").as[Int]
              val action = settings.autoLootSettings.lootList
                .find(_.trim.split(",\\s*")(0).toInt == itemId)
                .map(_.trim.split(",\\s*")(1))
                .getOrElse("")

              val itemSlot = slot.replace("slot", "item")
              val screenPosOpt = (screenInfo \ "inventoryPanelLoc" \ lastContainerKey \ "contentsPanel" \ itemSlot).asOpt[JsObject]
              val lootScreenPos = screenPosOpt.map(s => Vec((s \ "x").as[Int], (s \ "y").as[Int])).getOrElse(Vec(0, 0))

              val newState = state.copy(autoLoot = state.autoLoot.copy(
                stateLootPlunder = if (action == "g") "move item to ground" else "move item to backpack",
                lootIdToPlunder = itemId,
                lootCountToPlunder = itemCount,
                lootScreenPosToPlunder = lootScreenPos
              ))
              (newState, NoOpTask)

            case None =>
              val foundFood = itemsObj.fields.collectFirst {
                case (slot, itemInfo) if StaticGameInfo.Items.FoodsIds.contains((itemInfo \ "itemId").as[Int]) =>
                  (slot, itemInfo)
              }

              foundFood match {
                case Some((slot, foodInfo)) =>
                  val itemSlot = slot.replace("slot", "item")
                  val screenPosOpt = (screenInfo \ "inventoryPanelLoc" \ lastContainerKey \ "contentsPanel" \ itemSlot).asOpt[JsObject]
                  val lootScreenPos = screenPosOpt.map(s => Vec((s \ "x").as[Int], (s \ "y").as[Int])).getOrElse(Vec(0, 0))

                  val newState = state.copy(autoLoot = state.autoLoot.copy(
                    stateLootPlunder = "handle food",
                    lootIdToPlunder = (foodInfo \ "itemId").as[Int],
                    lootCountToPlunder = (foodInfo \ "itemCount").as[Int],
                    lootScreenPosToPlunder = lootScreenPos
                  ))
                  (newState, NoOpTask)

                case None =>
                  val foundContainer = itemsObj.fields.collectFirst {
                    case (slot, itemInfo) if (itemInfo \ "isContainer").asOpt[Boolean].getOrElse(false) =>
                      (slot, itemInfo)
                  }

                  foundContainer match {
                    case Some((slot, containerInfo)) =>
                      val itemSlot = slot.replace("slot", "item")
                      val screenPosOpt = (screenInfo \ "inventoryPanelLoc" \ lastContainerKey \ "contentsPanel" \ itemSlot).asOpt[JsObject]
                      val lootScreenPos = screenPosOpt.map(s => Vec((s \ "x").as[Int], (s \ "y").as[Int])).getOrElse(Vec(0, 0))

                      val newState = state.copy(autoLoot = state.autoLoot.copy(
                        stateLootPlunder = "open subcontainer",
                        lootIdToPlunder = (containerInfo \ "itemId").as[Int],
                        lootScreenPosToPlunder = lootScreenPos
                      ))
                      (newState, NoOpTask)

                    case None =>
                      val updated = state.copy(
                        autoLoot = state.autoLoot.copy(
                          stateLooting = "free",
                          stateLootPlunder = "free",
                          lootIdToPlunder = 0,
                          lootCountToPlunder = 0
                        ),
                        caveBot = state.caveBot.copy(stateHunting = "loot or fight or free")
                      )
                      (updated, NoOpTask)
                  }
              }
          }

        case _ =>
          val updated = state.copy(
            autoLoot = state.autoLoot.copy(
              stateLooting = "free",
              stateLootPlunder = "free"
            ),
            caveBot = state.caveBot.copy(stateHunting = "loot or fight or free")
          )
          (updated, NoOpTask)
      }
    }
  }


  def handleMoveItemToBackpack(json: JsValue, settings: UISettings, state: GameState): Option[(GameState, MKTask)] = ???

  def handleMoveItemToGround(json: JsValue, settings: UISettings, state: GameState): Option[(GameState, MKTask)] = {
    println("[handleMoveItemToGround] Entered")
    val excludedTiles = (state.autoLoot.carcassToLootImmediately ++ state.autoLoot.carcassToLootAfterFight)
      .flatMap { case (tileId, _) =>
        val result = convertGameLocationToGrid(json, tileId)
        println(s"[handleMoveItemToGround] Excluding tile $tileId → $result")
        result
      }.toSet

    val itemPos = state.autoLoot.lootScreenPosToPlunder
    val itemId = state.autoLoot.lootIdToPlunder
    val itemCount = state.autoLoot.lootCountToPlunder

    println(s"[handleMoveItemToGround] Item pos = $itemPos, id = $itemId, count = $itemCount")

    if (itemPos == Vec(0, 0)) {
      println("[handleMoveItemToGround] Invalid screen position for item → skipping")
      val updated = state.copy(autoLoot = state.autoLoot.copy(stateLootPlunder = "free"))
      return Some((updated, NoOpTask))
    }

    val areaInfo = (json \ "areaInfo").headOption.flatMap(_.asOpt[JsObject]).getOrElse(Json.obj())
    val possibleTiles = List("7x5", "7x6", "7x7", "8x5", "8x6", "8x7", "9x5", "9x6", "9x7")
    val walkableTileOpt = findRandomWalkableTile(areaInfo, possibleTiles.filterNot(excludedTiles.contains))

    walkableTileOpt.flatMap { tileIdx =>
      val mapPanelMap = (json \ "screenInfo").headOption.flatMap(_.asOpt[JsObject]).flatMap(obj => (obj \ "mapPanelLoc").headOption.flatMap(_.asOpt[Map[String, JsObject]])).getOrElse(Map.empty)
      mapPanelMap.get(tileIdx).map { obj =>
        val targetX = (obj \ "x").headOption.flatMap(_.asOpt[Int]).getOrElse(0)
        val targetY = (obj \ "y").headOption.flatMap(_.asOpt[Int]).getOrElse(0)

        println(s"[handleMoveItemToGround] Selected tile $tileIdx → screen pos = ($targetX, $targetY)")

        val (mouseActions, keyboardActions) =
          if (itemCount == 1)
            (moveSingleItem(itemPos.x, itemPos.y, targetX, targetY), Nil)
          else
            (moveSingleItem(itemPos.x, itemPos.y, targetX, targetY), List(
              PressCtrl,
              HoldCtrlFor(1.second),
              ReleaseCtrl
            ))

        val updatedState = state.copy(autoLoot = state.autoLoot.copy(
          stateLootPlunder = "free",
          lootIdToPlunder = 0,
          lootCountToPlunder = 0,
          lootScreenPosToPlunder = Vec(0, 0)
        ))

        println(s"[handleMoveItemToGround] Issuing move item to ground task for itemId=$itemId")

        (updatedState, MKTask("move item to ground", MKActions(mouse = mouseActions, keyboard = keyboardActions)))
      }
    }
  }

  def handleHandleFood(json: JsValue, settings: UISettings, state: GameState): Option[(GameState, MKTask)] = ???

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


