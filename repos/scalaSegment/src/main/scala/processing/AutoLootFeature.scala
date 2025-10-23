package processing

import keyboard.{DirectionalKey, GeneralKey, HoldCtrlFor, KeyboardAction, KeyboardUtils, PressCtrl, ReleaseCtrl}
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
        val (s, maybeTask) = Steps.runAll(json, settings, state)
        (s, maybeTask.toList)
      }

  private object Steps {
    // ordered list of steps
    val allSteps: List[Step] = List(
      CheckForOkButton,
      ProcessLootingInformation,
      ProcessLooting,
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

  private def resetAutoLootState(state: GameState, reason: String): GameState = {
    printInColor(ANSI_BLUE, s"[AutoLoot-Reset] Resetting autoLoot state. Reason: $reason")

    state.copy(
      autoLoot = state.autoLoot.copy(
        stateLooting = "free",
        stateLootPlunder = "free",
        lootIdToPlunder = 0,
        lootCountToPlunder = 0,
        lootScreenPosToPlunder = Vec(0, 0),
        dropScreenPosToPlunder = Vec(0, 0),
        lastItemIdAndCountEngaged = (0, 0),
        lastLootedCarcassTile = None,
        carcassTileToLoot = None
      ),
      autoTarget = state.autoTarget.copy(
        stateAutoTarget = "free",
      ),
    )
  }


  private object ProcessLootingInformation extends Step {
    private val taskName = "ProcessLootingInformation"

    override def run(
                      state:   GameState,
                      json:    JsValue,
                      settings: UISettings
                    ): Option[(GameState, MKTask)] = {

      // Retrieve battle info
      val shootable = getBattleShootableCreaturesList(json)

      if (state.autoTarget.stateAutoTarget == "stop")  {
        println(s"[$taskName] Entered function.")
        println(s"[$taskName] Evaluating loot queues: immediate=${state.autoLoot.carcassToLootImmediately}, postFight=${state.autoLoot.carcassToLootAfterFight}")

        println(s"[$taskName] Start stateLooting: ${state.autoLoot.stateLooting}, stateLootPlunder: ${state.autoLoot.stateLootPlunder}.")
        println(s"[$taskName] Start stateHunting: ${state.caveBot.stateHunting}, stateAutoTarget: ${state.autoTarget.stateAutoTarget}.")

        if (state.autoLoot.carcassToLootImmediately.nonEmpty) {
          val updatedState = startLooting(state, json, state.autoLoot.carcassToLootImmediately, true)
          return Some((updatedState, NoOpTask))
        } else if (state.autoLoot.carcassToLootAfterFight.nonEmpty && shootable.isEmpty) {
          val updatedState = startLooting(state, json, state.autoLoot.carcassToLootAfterFight, false)
          return Some((updatedState, NoOpTask))
        }
      }
      None
    }


    // Sort helper: takes the JSON context and list of carcasses
    private def sortByDistance(json: JsValue, list: List[(String, Long, String)]): List[(String, Long, String)] =
      sortTileListByCharacterProximity(json, list)

    // Unified looting starter: requires state, JSON, list and immediacy flag
    private def    startLooting(
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
        caveBot  = state.caveBot.copy(stateHunting = "stop")
      )
    }

  }



  object ProcessLooting extends Step {
    private val taskName = "ProcessLooting"

    override def run(state: GameState, json: JsValue, settings: UISettings): Option[(GameState, MKTask)] = {

      if (state.autoLoot.carcassTileToLoot.isEmpty) {
        return Some((state, NoOpTask))
      }

      println(s"[$taskName] Start stateLooting: ${state.autoLoot.stateLooting}, stateLootPlunder: ${state.autoLoot.stateLootPlunder}.")
      println(s"[$taskName] Start stateHunting: ${state.caveBot.stateHunting}, stateAutoTarget: ${state.autoTarget.stateAutoTarget}.")


      if (state.caveBot.stateHunting == "stop") {
        state.autoLoot.stateLooting match {
          case "free" => handleLootOrMoveCarcass(json, settings, state)
          case "moving carcass" => handleMovingOldCarcass(json, settings, state)
          case "focus on carcass" => handleFocusingOnCarcass(json, settings, state)
          case "clicking carcass" => handleClickingCarcass(json, settings, state)
          case "clicking open button" => handleClickingOpen(json, settings, state)
          case "opened loot container detection" => handleOpenedLootContainerDetection(json, settings, state)
          case "loot plunder" => handleLootPlunder(json, settings, state)
          case _ => Some((state, NoOpTask))
        }
      } else {
        Some((state, NoOpTask))
        }

    }
  }


  def handleFocusingOnCarcass(json: JsValue, settings: UISettings, state: GameState): Option[(GameState, MKTask)] = {
    val currentTime = System.currentTimeMillis()
    val autoLoot = state.autoLoot
    val taskName = "handleFocusingOnCarcass"
    val metaId: String = (json \ "metaGeneratedId").asOpt[String].getOrElse("null")

    println(s"[$taskName] Entered — stateLooting = ${autoLoot.stateLooting}")

    if (state.autoLoot.autoLootActionThrottle > currentTime - autoLoot.lastAutoLootActionTime) {
      println(s"[$taskName] Too soon since last action → NoOp")
      return Some(state -> NoOpTask)
    }

    println(s"[$taskName] carcassTileToLoot = ${autoLoot.carcassTileToLoot}")

    autoLoot.carcassTileToLoot match {
      case Some((carcassTileToLoot, deathTime)) =>
        println(s"[$taskName] Will focus on carcass at $carcassTileToLoot, died at $deathTime")

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
        println(s"[$taskName] carcassPos=$carcassPos, charPos=$charPos, chebyshevDist=$chebyshevDist")

        if (chebyshevDist <= 1) {
          // Find screen coordinates for the carcass
          val mapPanelMap = (json \ "screenInfo" \ "mapPanelLoc").as[Map[String, JsObject]]
          val screenCoordsOpt = mapPanelMap.values.collectFirst {
            case obj if (obj \ "id").asOpt[String].contains(carcassTileToLoot) =>
              for {
                x <- (obj \ "x").asOpt[Int]
                y <- (obj \ "y").asOpt[Int]
              } yield (x, y)
          }.flatten

          screenCoordsOpt match {
            case Some((x, y)) =>
              println(s"[$taskName] Moving mouse to carcass at ($x,$y)")

              val mouseActions = List(MoveMouse(x, y, metaId))

              val newState = state.copy(autoLoot = autoLoot.copy(
                stateLooting = "clicking carcass",
                lastAutoLootActionTime = currentTime
              ))

              Some((newState, MKTask("focus on carcass", MKActions(mouse = mouseActions, keyboard = Nil))))

            case None =>
              println(s"[$taskName] ❌ Couldn't find screen coords for carcassTileToLoot → aborting loot")
              Some((state.copy(
                autoLoot = autoLoot.copy(stateLooting = "free")
              ), NoOpTask))
          }

        } else {
          // need to path-find closer
          println(s"[$taskName] Too far (dist=$chebyshevDist) → pathing toward $carcassPos")
          val newState = generateSubwaypointsToGamePosition(carcassPos, state, json)
          val sw = newState.autoLoot.subWaypoints
          println(s"[$taskName] New subWaypoints = $sw")

          if (sw.nonEmpty) {
            val next = sw.head
            val dirOpt = calculateDirection(charPos, next, newState.autoLoot.lastDirection)
            println(s"[$taskName] Moving one step: dir=$dirOpt toward $next")

            val updatedAutoLoot = newState.autoLoot.copy(
              subWaypoints = sw.tail,
              lastDirection = dirOpt,
              lastAutoLootActionTime = currentTime
            )
            val updatedState = newState.copy(autoLoot = updatedAutoLoot)

            val kbActions = dirOpt.toList.map(d => keyboard.DirectionalKey(d, metaId))
            Some((updatedState, MKTask("approachingLoot", MKActions(mouse = Nil, keyboard = kbActions))))
          } else {
            println(s"[$taskName] No subWaypoints → giving up and resetting to free")
            Some((newState.copy(
              autoLoot = autoLoot.copy(stateLooting = "free")
            ), NoOpTask))
          }
        }

      case None =>
        println(s"[$taskName] carcassTileToLoot is None → resetting to free")
        Some((state.copy(
          autoLoot = autoLoot.copy(stateLooting = "free")
        ), NoOpTask))
    }
  }

  def handleOpenedLootContainerDetection(json: JsValue, settings: UISettings, state: GameState): Option[(GameState, MKTask)] = {

    val al = state.autoLoot
    val taskName = "handleOpenedLootContainerDetection"
    val currentTime = System.currentTimeMillis()

    println(s"[$taskName] Entered — stateLooting = ${al.stateLooting}")

    if (al.autoLootActionThrottle * al.throttleCoefficient  > currentTime - al.lastAutoLootActionTime) {
      println(s"[$taskName] Too soon since last action → NoOp")
      return Some(state -> NoOpTask)
    }

    val gen = state.general
    val ignoreContainers = gen.initialContainersList.toSet
    val containersInfoOpt = (json \ "containersInfo").asOpt[JsObject]

    containersInfoOpt match {
      case Some(containersInfo) =>
        val newKeys = containersInfo.keys.toList.filterNot(ignoreContainers.contains).sorted
        println(s"[$taskName] New candidate containers (excluding initial): $newKeys")

        newKeys.lastOption match {
          case Some(lastContainerKey) =>
            (containersInfo \ lastContainerKey).asOpt[JsObject] match {
              case Some(lastContainer) =>
                (lastContainer \ "items").toOption match {
                  case Some(itemsValue) =>
                    val currentContainerContent = itemsValue.toString
                    println(s"[$taskName] Found lastContainerKey: $lastContainerKey with items: $itemsValue")
                    println(s"[$taskName] Previous content: ${al.lastContainerContent}")
                    println(s"[$taskName] Current content: $currentContainerContent")

                    if (currentContainerContent != al.lastContainerContent) {
                      println(s"[$taskName] Container content changed → proceeding to loot plunder")
                      val newState = state.copy(autoLoot = al.copy(
                        stateLooting = "loot plunder",
                        lastAutoLootActionTime = currentTime
                      ))
                      Some((newState, NoOpTask))
                    } else {
                      println(s"[$taskName] Container content unchanged → right-click missed, returning to focus on carcass")
                      val newState = state.copy(autoLoot = al.copy(
                        stateLooting = "focus on carcass",
                        lastAutoLootActionTime = currentTime
                      ))
                      Some((newState, NoOpTask))
                    }

                  case None =>
                    println(s"[$taskName] No items found in container → resetting")
                    val updatedState = resetAutoLootState(state, s"[$taskName] No items found in container.")
                    Some((updatedState, NoOpTask))
                }

              case None =>
                println(s"[$taskName] Last container not found → resetting")
                val updatedState = resetAutoLootState(state, s"[$taskName] Last container not found.")
                Some((updatedState, NoOpTask))
            }

          case None =>
            println(s"[$taskName] No new containers found → resetting")
            val updatedState = resetAutoLootState(state, s"[$taskName] No new containers found.")
            Some((updatedState, NoOpTask))
        }

      case None =>
        println(s"[$taskName] ❌ Missing containersInfo → resetting")
        val updatedState = resetAutoLootState(state, s"[$taskName] Missing containersInfo.")
        Some((updatedState, NoOpTask))
    }
  }

  def handleClickingCarcass(json: JsValue, settings: UISettings, state: GameState): Option[(GameState, MKTask)] = {
    val currentTime = System.currentTimeMillis()
    val autoLoot = state.autoLoot
    val taskName = "handleClickingCarcass"
    val metaId: String = (json \ "metaGeneratedId").asOpt[String].getOrElse("null")

    println(s"[$taskName] Entered — stateLooting = ${autoLoot.stateLooting}")

    if (state.autoLoot.autoLootActionThrottle > currentTime - autoLoot.lastAutoLootActionTime) {
      println(s"[$taskName] Too soon since last action → NoOp")
      return Some(state -> NoOpTask)
    }

    autoLoot.carcassTileToLoot match {
      case Some((carcassTileToLoot, deathTime)) =>
        println(s"[$taskName] Will check carcass at $carcassTileToLoot, died at $deathTime")

        // Parse map coords
        val carcassPos = Vec(
          carcassTileToLoot.substring(0, 5).toInt,
          carcassTileToLoot.substring(5, 10).toInt
        )

        // Check if the target tile has a container
        val targetTileContainerOpt = findContainerOnTile(json, carcassTileToLoot)

        val (finalTileId, finalPos) = targetTileContainerOpt match {
          case Some(_) =>
            println(s"[$taskName] Container found on target tile $carcassTileToLoot")
            (carcassTileToLoot, carcassPos)

          case None =>
            println(s"[$taskName] No container on target tile, searching nearby tiles")
            findNearbyTileWithContainer(json, carcassPos) match {
              case Some((nearbyTileId, nearbyPos)) =>
                println(s"[$taskName] Found container on nearby tile $nearbyTileId")
                (nearbyTileId, nearbyPos)

              case None =>
                println(s"[$taskName] No container found on target or nearby tiles → resetting")
                val updatedState = resetAutoLootState(state, s"[$taskName] No container found. Resetting states.")
                return Some((updatedState, NoOpTask))
            }
        }

        // Continue with the container tile we found
        val mapPanelMap = (json \ "screenInfo" \ "mapPanelLoc").as[Map[String, JsObject]]
        val screenCoordsOpt = mapPanelMap.values.collectFirst {
          case obj if (obj \ "id").asOpt[String].contains(finalTileId) =>
            for {
              x <- (obj \ "x").asOpt[Int]
              y <- (obj \ "y").asOpt[Int]
            } yield (x, y)
        }.flatten

        screenCoordsOpt match {
          case Some((x, y)) =>
            // Capture current container content before clicking
            val gen = state.general
            val ignoreContainers = gen.initialContainersList.toSet
            val currentContainerContent = (json \ "containersInfo").asOpt[JsObject].flatMap { containersInfo =>
              val newKeys = containersInfo.keys.toList.filterNot(ignoreContainers.contains).sorted
              newKeys.lastOption.flatMap { lastContainerKey =>
                (containersInfo \ lastContainerKey).asOpt[JsObject].flatMap { lastContainer =>
                  (lastContainer \ "items").toOption.map(_.toString)
                }
              }
            }.getOrElse("")

            val hasNearbyInterference = checkForNearbyInterference(json, finalPos)

            if (hasNearbyInterference) {
              println(s"[$taskName] Nearby creatures/players detected, using Ctrl+right-click approach")

              val mouseActions = List(
                MoveMouse(x, y, metaId),
                RightButtonPress(x, y, metaId),
                RightButtonRelease(x, y, metaId)
              )

              val keyboardActions = List(
                PressCtrl(metaId),
                HoldCtrlFor(1.second, metaId),
                ReleaseCtrl(metaId)
              )

              val newState = state.copy(autoLoot = autoLoot.copy(
                stateLooting = "clicking open button",
                carcassTileToLoot = Some((finalTileId, deathTime)),
                lastContainerContent = currentContainerContent,
                lastAutoLootActionTime = currentTime
              ))

              Some((newState, MKTask("ctrl right-click carcass", MKActions(mouse = mouseActions, keyboard = keyboardActions))))

            } else {
              println(s"[$taskName] No interference detected, using regular right-click")

              val mouseActions = List(
                MoveMouse(x, y, metaId),
                RightButtonPress(x, y, metaId),
                RightButtonRelease(x, y, metaId)
              )

              val newState = state.copy(autoLoot = autoLoot.copy(
                stateLooting = "opened loot container detection",
                carcassTileToLoot = Some((finalTileId, deathTime)),
                lastContainerContent = currentContainerContent,
                lastAutoLootActionTime = currentTime
              ))

              Some((newState, MKTask("right-click carcass", MKActions(mouse = mouseActions, keyboard = Nil))))
            }

          case None =>
            println(s"[$taskName] ❌ Couldn't find screen coords for container tile → aborting loot")
            val updatedState = resetAutoLootState(state, s"[$taskName] Couldn't find screen coords for container tile.")
            Some((updatedState, NoOpTask))
        }

      case None =>
        println(s"[$taskName] carcassTileToLoot is None → resetting to free")
        Some((state.copy(
          autoLoot = autoLoot.copy(stateLooting = "free")
        ), NoOpTask))
    }
  }


//  def handleClickingCarcass(json: JsValue, settings: UISettings, state: GameState): Option[(GameState, MKTask)] = {
//    val currentTime = System.currentTimeMillis()
//    val autoLoot = state.autoLoot
//    val taskName = "handleClickingCarcass"
//
//    println(s"[$taskName] Entered — stateLooting = ${autoLoot.stateLooting}")
//
//    if (state.autoLoot.autoLootActionThrottle > currentTime - autoLoot.lastAutoLootActionTime) {
//      println(s"[$taskName] Too soon since last action → NoOp")
//      return Some(state -> NoOpTask)
//    }
//
//    autoLoot.carcassTileToLoot match {
//      case Some((carcassTileToLoot, deathTime)) =>
//        println(s"[$taskName] Will check carcass at $carcassTileToLoot, died at $deathTime")
//
//        // Parse map coords
//        val carcassPos = Vec(
//          carcassTileToLoot.substring(0, 5).toInt,
//          carcassTileToLoot.substring(5, 10).toInt
//        )
//
//        // Check if the target tile has a container
//        val targetTileContainerOpt = findContainerOnTile(json, carcassTileToLoot)
//
//        val (finalTileId, finalPos) = targetTileContainerOpt match {
//          case Some(_) =>
//            println(s"[$taskName] Container found on target tile $carcassTileToLoot")
//            (carcassTileToLoot, carcassPos)
//
//          case None =>
//            println(s"[$taskName] No container on target tile, searching nearby tiles")
//            findNearbyTileWithContainer(json, carcassPos) match {
//              case Some((nearbyTileId, nearbyPos)) =>
//                println(s"[$taskName] Found container on nearby tile $nearbyTileId")
//                (nearbyTileId, nearbyPos)
//
//              case None =>
//                println(s"[$taskName] No container found on target or nearby tiles → resetting")
//                val updatedState = resetAutoLootState(state, s"[$taskName] No container found. Resetting states.")
//                return Some((updatedState, NoOpTask))
//            }
//        }
//
//        // Continue with the container tile we found
//        val mapPanelMap = (json \ "screenInfo" \ "mapPanelLoc").as[Map[String, JsObject]]
//        val screenCoordsOpt = mapPanelMap.values.collectFirst {
//          case obj if (obj \ "id").asOpt[String].contains(finalTileId) =>
//            for {
//              x <- (obj \ "x").asOpt[Int]
//              y <- (obj \ "y").asOpt[Int]
//            } yield (x, y)
//        }.flatten
//
//        screenCoordsOpt match {
//          case Some((x, y)) =>
//            val hasNearbyInterference = checkForNearbyInterference(json, finalPos)
//
//            if (hasNearbyInterference) {
//              println(s"[$taskName] Nearby creatures/players detected, using Ctrl+right-click approach")
//
//              val mouseActions = List(
//                MoveMouse(x, y),
//                RightButtonPress(x, y),
//                RightButtonRelease(x, y)
//              )
//
//              val keyboardActions = List(
//                PressCtrl,
//                HoldCtrlFor(1.second),
//                ReleaseCtrl
//              )
//
//              val newState = state.copy(autoLoot = autoLoot.copy(
//                stateLooting = "clicking open button",
//                carcassTileToLoot = Some((finalTileId, deathTime)), // Update to the actual container tile
//                lastAutoLootActionTime = currentTime
//              ))
//
//              Some((newState, MKTask("ctrl right-click carcass", MKActions(mouse = mouseActions, keyboard = keyboardActions))))
//
//            } else {
//              println(s"[$taskName] No interference detected, using regular right-click")
//
//              val mouseActions = List(
//                MoveMouse(x, y),
//                RightButtonPress(x, y),
//                RightButtonRelease(x, y)
//              )
//
//              val newState = state.copy(autoLoot = autoLoot.copy(
//                stateLooting = "loot plunder",
//                carcassTileToLoot = Some((finalTileId, deathTime)), // Update to the actual container tile
//                lastAutoLootActionTime = currentTime
//              ))
//
//              Some((newState, MKTask("right-click carcass", MKActions(mouse = mouseActions, keyboard = Nil))))
//            }
//
//          case None =>
//            println(s"[$taskName] ❌ Couldn't find screen coords for container tile → aborting loot")
//            val updatedState = resetAutoLootState(state, s"[$taskName] Couldn't find screen coords for container tile.")
//            Some((updatedState, NoOpTask))
//        }
//
//      case None =>
//        println(s"[$taskName] carcassTileToLoot is None → resetting to free")
//        Some((state.copy(
//          autoLoot = autoLoot.copy(stateLooting = "free")
//        ), NoOpTask))
//    }
//  }

  private def findContainerOnTile(json: JsValue, tileId: String): Option[JsObject] = {
    val tilesInfo = (json \ "areaInfo" \ "tiles").asOpt[JsObject].getOrElse(Json.obj())

    (tilesInfo \ tileId).asOpt[JsObject].flatMap { tileObj =>
      val items = (tileObj \ "items").asOpt[JsObject].getOrElse(Json.obj())

      // Convert items to list and sort by key (which represents stack position)
      val sortedItems = items.fields.sortBy(_._1.toInt).reverse // Highest index first (top of stack)

      // Check if there's a player/creature on top (isItem = false)
      val hasPlayerOrCreatureOnTop = sortedItems.headOption.exists { case (_, itemObj: JsObject) =>
        (itemObj \ "isItem").asOpt[Boolean].contains(false)
      }

      if (hasPlayerOrCreatureOnTop) {
        println(s"[findContainerOnTile] Player/creature detected on top of tile $tileId, checking item below")
        // Look for container in the second highest position
        val containerItems = sortedItems.drop(1).collect {
          case (itemKey, itemObj: JsObject) if (itemObj \ "isContainer").asOpt[Boolean].getOrElse(false) =>
            println(s"[findContainerOnTile] Found container below player/creature on tile $tileId: item $itemKey")
            itemObj
        }
        containerItems.headOption
      } else {
        // Normal logic: find the highest container
        val containerItems = sortedItems.collect {
          case (itemKey, itemObj: JsObject) if (itemObj \ "isContainer").asOpt[Boolean].getOrElse(false) =>
            println(s"[findContainerOnTile] Found container on tile $tileId: item $itemKey")
            itemObj
        }

        if (containerItems.nonEmpty) {
          println(s"[findContainerOnTile] Selected highest container on tile $tileId")
          containerItems.headOption
        } else {
          println(s"[findContainerOnTile] No container found on tile $tileId")
          None
        }
      }
    }
  }

  private def findNearbyTileWithContainer(json: JsValue, centerPos: Vec): Option[(String, Vec)] = {
    val tilesInfo = (json \ "areaInfo" \ "tiles").asOpt[JsObject].getOrElse(Json.obj())

    // Get Z position from character info
    val charZ = (json \ "characterInfo" \ "PositionZ").asOpt[Int].getOrElse(0)

    // Check tiles in a 3x3 area around the center position
    val nearbyTiles = for {
      dx <- -1 to 1
      dy <- -1 to 1
      if !(dx == 0 && dy == 0) // Skip the center tile (already checked)
    } yield {
      val checkPos = Vec(centerPos.x + dx, centerPos.y + dy)
      val tileId = f"${checkPos.x}%05d${checkPos.y}%05d${charZ}%02d" // Use character's Z position

      findContainerOnTile(json, tileId).map { containerObj =>
        val hasBlood = checkForBloodOnTile(json, tileId)
        (tileId, checkPos, containerObj, hasBlood)
      }
    }

    val validTiles = nearbyTiles.flatten.toList

    if (validTiles.nonEmpty) {
      // Sort by blood presence (blood first), then by distance
      val sortedTiles = validTiles.sortBy { case (tileId, pos, _, hasBlood) =>
        val distance = math.max(math.abs(pos.x - centerPos.x), math.abs(pos.y - centerPos.y))
        (!hasBlood, distance) // !hasBlood so true (blood present) comes first
      }

      val (selectedTileId, selectedPos, _, hasBlood) = sortedTiles.head
      println(s"[findNearbyTileWithContainer] Selected tile $selectedTileId (blood: $hasBlood)")
      Some((selectedTileId, selectedPos))
    } else {
      println(s"[findNearbyTileWithContainer] No nearby tiles with containers found")
      None
    }
  }

  private def checkForBloodOnTile(json: JsValue, tileId: String): Boolean = {
    val tilesInfo = (json \ "areaInfo" \ "tiles").asOpt[JsObject].getOrElse(Json.obj())
    val bloodId = 2886

    (tilesInfo \ tileId).asOpt[JsObject].exists { tileObj =>
      val items = (tileObj \ "items").asOpt[JsObject].getOrElse(Json.obj())
      items.fields.exists { case (_, itemObj: JsObject) =>
        (itemObj \ "id").asOpt[Int].contains(bloodId)
      }
    }
  }

//  def handleClickingCarcass(json: JsValue, settings: UISettings, state: GameState): Option[(GameState, MKTask)] = {
//    val currentTime = System.currentTimeMillis()
//    val autoLoot = state.autoLoot
//    val taskName = "handleClickingCarcass"
//
//    println(s"[$taskName] Entered — stateLooting = ${autoLoot.stateLooting}")
//
//    if (state.autoLoot.autoLootActionThrottle > currentTime - autoLoot.lastAutoLootActionTime) {
//      println(s"[$taskName] Too soon since last action → NoOp")
//      return Some(state -> NoOpTask)
//    }
//
//    autoLoot.carcassTileToLoot match {
//      case Some((carcassTileToLoot, deathTime)) =>
//        println(s"[$taskName] Will click on carcass at $carcassTileToLoot, died at $deathTime")
//
//        // parse map coords
//        val carcassPos = Vec(
//          carcassTileToLoot.substring(0, 5).toInt,
//          carcassTileToLoot.substring(5, 10).toInt
//        )
//
//        // Find screen coordinates for the carcass
//        val mapPanelMap = (json \ "screenInfo" \ "mapPanelLoc").as[Map[String, JsObject]]
//        val screenCoordsOpt = mapPanelMap.values.collectFirst {
//          case obj if (obj \ "id").asOpt[String].contains(carcassTileToLoot) =>
//            for {
//              x <- (obj \ "x").asOpt[Int]
//              y <- (obj \ "y").asOpt[Int]
//            } yield (x, y)
//        }.flatten
//
//        screenCoordsOpt match {
//          case Some((x, y)) =>
//            val hasNearbyInterference = checkForNearbyInterference(json, carcassPos)
//
//            if (hasNearbyInterference) {
//              println(s"[$taskName] Nearby creatures/players detected, using Ctrl+right-click approach")
//
//              // Coordinated keyboard and mouse actions
//              val mouseActions = List(
//                MoveMouse(x, y),
//                RightButtonPress(x, y),
//                RightButtonRelease(x, y)
//              )
//
//              val keyboardActions = List(
//                PressCtrl,
//                HoldCtrlFor(1.second),
//                ReleaseCtrl
//              )
//
//              val newState = state.copy(autoLoot = autoLoot.copy(
//                stateLooting = "clicking open button",
//                lastAutoLootActionTime = currentTime
//              ))
//
//              Some((newState, MKTask("ctrl right-click carcass", MKActions(mouse = mouseActions, keyboard = keyboardActions))))
//
//            } else {
//              println(s"[$taskName] No interference detected, using regular right-click")
//
//              val mouseActions = List(
//                MoveMouse(x, y),
//                RightButtonPress(x, y),
//                RightButtonRelease(x, y)
//              )
//
//              val newState = state.copy(autoLoot = autoLoot.copy(
//                stateLooting = "loot plunder",
//                lastAutoLootActionTime = currentTime
//              ))
//
//              Some((newState, MKTask("right-click carcass", MKActions(mouse = mouseActions, keyboard = Nil))))
//            }
//
//          case None =>
//            println(s"[$taskName] ❌ Couldn't find screen coords for carcassTileToLoot → aborting loot")
//            Some((state.copy(
//              autoLoot = autoLoot.copy(stateLooting = "free")
//            ), NoOpTask))
//        }
//
//      case None =>
//        println(s"[$taskName] carcassTileToLoot is None → resetting to free")
//        Some((state.copy(
//          autoLoot = autoLoot.copy(stateLooting = "free")
//        ), NoOpTask))
//    }
//  }




//  def handleFocusingOnCarcass(json: JsValue, settings: UISettings, state: GameState): Option[(GameState, MKTask)] = {
//    val currentTime        = System.currentTimeMillis()
//    val autoLoot           = state.autoLoot
//    val taskName = "handleOpeningCarcass"
//
//    println(s"[$taskName] Entered — stateLooting = ${autoLoot.stateLooting}")
//
//    if (state.autoLoot.autoLootActionThrottle > currentTime - autoLoot.lastAutoLootActionTime) {
//      println(s"[$taskName] Too soon since last action → NoOp")
//      return Some(state -> NoOpTask)
//    }
//
//    println(s"[$taskName] carcassTileToLoot = ${autoLoot.carcassTileToLoot}")
//
//
//    autoLoot.carcassTileToLoot match {
//      case Some((carcassTileToLoot, deathTime)) =>
//        println(s"[$taskName] Will open carcass at $carcassTileToLoot, died at $deathTime")
//
//        // parse map coords
//        val carcassPos = Vec(
//          carcassTileToLoot.substring(0, 5).toInt,
//          carcassTileToLoot.substring(5, 10).toInt
//        )
//        val charPos = Vec(
//          (json \ "characterInfo" \ "PositionX").as[Int],
//          (json \ "characterInfo" \ "PositionY").as[Int]
//        )
//        val chebyshevDist = math.max(
//          math.abs(carcassPos.x - charPos.x),
//          math.abs(carcassPos.y - charPos.y)
//        )
//        println(s"[$taskName] carcassPos=$carcassPos, charPos=$charPos, chebyshevDist=$chebyshevDist")
//
//        if (chebyshevDist <= 1) {
//
//          val hasNearbyInterference = checkForNearbyInterference(json, carcassPos)
//          if (hasNearbyInterference) {
//            println(s"[$taskName] Nearby creatures/players detected, using Ctrl+right-click approach")
//
//            val mapPanelMap = (json \ "screenInfo" \ "mapPanelLoc").as[Map[String, JsObject]]
//            val screenCoordsOpt = mapPanelMap.values.collectFirst {
//              case obj if (obj \ "id").asOpt[String].contains(carcassTileToLoot) =>
//                for {
//                  x <- (obj \ "x").asOpt[Int]
//                  y <- (obj \ "y").asOpt[Int]
//                } yield (x, y)
//            }.flatten
//
//            screenCoordsOpt match {
//              case Some((x, y)) =>
//                println(s"[$taskName] Issuing Ctrl+right-click at ($x,$y)")
//
//                // Coordinated keyboard and mouse actions
//                val mouseActions = List(
//                  MoveMouse(x, y),
//                  RightButtonPress(x, y),
//                  RightButtonRelease(x, y)
//                )
//
//                val keyboardActions = List(
//                  PressCtrl,
//                  HoldCtrlFor(1.second),
//                  ReleaseCtrl
//                )
//
//                val newState = state.copy(autoLoot = autoLoot.copy(
//                  stateLooting = "clicking open button",
//                  lastAutoLootActionTime = currentTime
//                ))
//
//                Some((newState, MKTask("ctrl right-click carcass", MKActions(mouse = mouseActions, keyboard = keyboardActions))))
//
//              case None =>
//                println(s"[$taskName] ❌ Couldn't find screen coords for carcassTileToLoot → aborting loot")
//                Some((state.copy(
//                  autoLoot = autoLoot.copy(stateLooting = "free")
//                ), NoOpTask))
//            }
//
//          } else {
//            // right‐click to open
//            val mapPanelMap = (json \ "screenInfo" \ "mapPanelLoc").as[Map[String, JsObject]]
//            val screenCoordsOpt = mapPanelMap.values.collectFirst {
//              case obj if (obj \ "id").asOpt[String].contains(carcassTileToLoot) =>
//                for {
//                  x <- (obj \ "x").asOpt[Int]
//                  y <- (obj \ "y").asOpt[Int]
//                } yield (x, y)
//            }.flatten
//
//            println(s"[$taskName] screenCoordsOpt = $screenCoordsOpt")
//
//            screenCoordsOpt match {
//              case Some((x, y)) =>
//                println(s"[$taskName] Issuing right‐click at ($x,$y)")
//                val actions = List(
//                  MoveMouse(x, y),
//                  RightButtonPress(x, y),
//                  RightButtonRelease(x, y)
//                )
//                val newState = state.copy(autoLoot = autoLoot.copy(
//                  stateLooting       = "loot plunder",
//                  lastAutoLootActionTime = currentTime
//                ))
//                Some((newState, MKTask("focus on carcass", MKActions(mouse = actions, keyboard = Nil))))
//
//              case None =>
//                println(s"[$taskName] ❌ Couldn't find screen coords for carcassTileToLoot → aborting loot")
//                Some((state.copy(
//                  autoLoot = autoLoot.copy(stateLooting = "free"),
//                  //                caveBot  = state.caveBot.copy(stateHunting = "free")
//                ), NoOpTask))
//            }
//          }
//
//        } else {
//          // need to path‐find closer
//          println(s"[$taskName] Too far (dist=$chebyshevDist) → pathing toward $carcassPos")
//          val newState = generateSubwaypointsToGamePosition(carcassPos, state, json)
//          val sw = newState.autoLoot.subWaypoints
//          println(s"[$taskName] New subWaypoints = $sw")
//
//          if (sw.nonEmpty) {
//            val next   = sw.head
//            val dirOpt = calculateDirection(charPos, next, newState.autoLoot.lastDirection)
//            println(s"[$taskName] Moving one step: dir=$dirOpt toward $next")
//
//            val updatedAutoLoot = newState.autoLoot.copy(
//              subWaypoints   = sw.tail,
//              lastDirection  = dirOpt,
//              lastAutoLootActionTime = currentTime
//            )
//            val updatedState = newState.copy(autoLoot = updatedAutoLoot)
//            val kbActions = dirOpt.toList.map(DirectionalKey(_))
//            Some((updatedState, MKTask("approachingLoot", MKActions(mouse = Nil, keyboard = kbActions))))
//          } else {
//            println(s"[$taskName] No subWaypoints → giving up and resetting to free")
//            Some((newState.copy(
//              autoLoot = autoLoot.copy(stateLooting = "free"),
//              //              caveBot  = state.caveBot.copy(stateHunting = "free")
//            ), NoOpTask))
//          }
//        }
//
//      case None =>
//        println(s"[$taskName] carcassTileToLoot is None → resetting to free")
//        Some((state.copy(
//          autoLoot = autoLoot.copy(stateLooting = "free"),
//          //          caveBot  = state.caveBot.copy(stateHunting = "free")
//        ), NoOpTask))
//    }
//  }


  private def checkForNearbyInterference(json: JsValue, carcassPos: Vec): Boolean = {
    // Check battleInfo for creatures within 1 sqm of corpse
    val battleInfo = (json \ "battleInfo").asOpt[Map[String, JsValue]].getOrElse(Map.empty)

    val nearbyCreatures = battleInfo.exists { case (_, creatureData) =>
      val creatureX = (creatureData \ "PositionX").asOpt[Int].getOrElse(0)
      val creatureY = (creatureData \ "PositionY").asOpt[Int].getOrElse(0)
      val creaturePos = Vec(creatureX, creatureY)

      val distance = math.max(
        math.abs(creaturePos.x - carcassPos.x),
        math.abs(creaturePos.y - carcassPos.y)
      )

      distance <= 1
    }

    println(s"[checkForNearbyInterference] Found nearby creatures/players: $nearbyCreatures")
    nearbyCreatures
  }

  def handleClickingOpen(json: JsValue, settings: UISettings, state: GameState): Option[(GameState, MKTask)] = {
    val currentTime = System.currentTimeMillis()
    val al = state.autoLoot
    val taskName = "handleClickingOpen"
    val metaId: String = (json \ "metaGeneratedId").asOpt[String].getOrElse("null")

    if (al.autoLootActionThrottle * al.throttleCoefficient > currentTime - al.lastAutoLootActionTime) {
      return Some(state -> NoOpTask)
    }

    println(s"[$taskName] Entered function.")
    val extraWindowLoc = (json \ "screenInfo" \ "extraWindowLoc").asOpt[JsObject]
    println(s"[$taskName] extraWindowLoc: $extraWindowLoc")

    val openButtonOpt = extraWindowLoc
      .flatMap { windowObj =>
        println(s"[$taskName] Available keys: ${windowObj.keys.mkString(", ")}")

        (windowObj \ "Open").asOpt[JsObject] match {
          case Some(openObj) =>
            println(s"[$taskName] Found Open object: $openObj")
            // Fixed: Use posX and posY instead of x and y
            for {
              x <- (openObj \ "posX").asOpt[Int]
              y <- (openObj \ "posY").asOpt[Int]
            } yield {
              println(s"[$taskName] Extracted Open button coordinates: x=$x, y=$y")
              (x, y)
            }
          case None =>
            println(s"[$taskName] No Open button found in extraWindowLoc")
            None
        }
      }

    println(s"[$taskName] openButtonOpt: $openButtonOpt")

    openButtonOpt match {
      case Some((x, y)) =>
        println(s"[$taskName] Clicking Open button at ($x, $y)")

        // Capture current container content before clicking
        val gen = state.general
        val ignoreContainers = gen.initialContainersList.toSet
        val currentContainerContent = (json \ "containersInfo").asOpt[JsObject].flatMap { containersInfo =>
          val newKeys = containersInfo.keys.toList.filterNot(ignoreContainers.contains).sorted
          newKeys.lastOption.flatMap { lastContainerKey =>
            (containersInfo \ lastContainerKey \ "items").asOpt[String]
          }
        }.getOrElse("")

        val actions = List(
          MoveMouse(x, y, metaId),
          LeftButtonPress(x, y, metaId),
          LeftButtonRelease(x, y, metaId)
        )

        val newState = state.copy(autoLoot = al.copy(
          stateLooting = "opened loot container detection",
          carcassTileToLoot = al.carcassTileToLoot, // Preserve the carcass tile info
          lastContainerContent = currentContainerContent,
          lastAutoLootActionTime = currentTime
        ))
        Some((newState, MKTask("pressing open", MKActions(mouse = actions, keyboard = Nil))))

      case None =>
        println(s"[$taskName] No Open button found, resetting loot state")
        val resetState = state.copy(autoLoot = al.copy(
          stateLooting = "free",
          carcassTileToLoot = None
        ))
        Some((resetState, NoOpTask))
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
      val minDelay = state.fishing.retryMidDelay
      val metaId: String = (json \ "metaGeneratedId").asOpt[String].getOrElse("null")

      if (state.autoLoot.autoLootThrottle > now - state.autoLoot.lastAutoLootActionTime) {
        println(s"[$taskName] Too soon since last action → NoOp")
        return Some(state -> NoOpTask)
      }

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
        // 5) map into the actual click task
        .map { case (x, y) =>
          val mkActions = MKActions(
//            List(
//              MoveMouse(x, y),
//              LeftButtonPress(x, y),
//              LeftButtonRelease(x, y)
//            ),
            Nil,
            List(GeneralKey(java.awt.event.KeyEvent.VK_ENTER, metaId)) // Press Enter key
          )
          val task = MKTask(taskName, mkActions)

          val newGeneral = state.general.copy(
            lastActionCommand   = Some(taskName),
            lastActionTimestamp = Some(now)
          )

          (state.copy(general = newGeneral), task)
        }
    }
  }

  def handleLootOrMoveCarcass(json: JsValue, settings: UISettings, state: GameState): Option[(GameState, MKTask)] = {
    val taskName = "handleLootOrMoveCarcass"
    println(s"[$taskName] Entered function.")

    println(s"[$taskName] Start stateLooting: ${state.autoLoot.stateLooting}, stateLootPlunder: ${state.autoLoot.stateLootPlunder}.")
    println(s"[$taskName] Start stateHunting: ${state.caveBot.stateHunting}, stateAutoTarget: ${state.autoTarget.stateAutoTarget}.")

    if (state.autoLoot.lastLootedCarcassTile.nonEmpty) {
      val als = state.autoLoot
      val (lastTile, _) = als.lastLootedCarcassTile.get
      val currentCarcassOpt = als.carcassTileToLoot.map(_._1)

      currentCarcassOpt match {
        case Some(currentTile) if currentTile == lastTile =>
          // Same carcass - let it continue processing
          println(s"[$taskName] Same carcass $currentTile being processed, continuing")
        case Some(currentTile) =>
          // Different carcass - clear the last looted and proceed with new one
          println(s"[$taskName] New carcass $currentTile, clearing last looted $lastTile")
          val clearedState = state.copy(autoLoot = als.copy(lastLootedCarcassTile = None))
          return Some((clearedState, NoOpTask))
        case None =>
          // No current carcass but last looted exists - clear it
          println(s"[$taskName] No current carcass, clearing last looted $lastTile")
          val clearedState = state.copy(autoLoot = als.copy(
            lastLootedCarcassTile = None,
            stateLooting = "free"
          ))
          return Some((clearedState, NoOpTask))
      }
    }


    val autoLootState = state.autoLoot
    println(s"[$taskName] carcassToLootImmediately: ${autoLootState.carcassToLootImmediately}")
    println(s"[$taskName] carcassToLootAfterFight: ${autoLootState.carcassToLootAfterFight}")
    println(s"[$taskName] carcassTileToLoot: ${autoLootState.carcassTileToLoot}")
    println(s"[$taskName] lastLootedCarcassTile: ${autoLootState.lastLootedCarcassTile}")

    val nextState = autoLootState.carcassTileToLoot match {
      case Some((carcassTile, timeOfDeath)) =>
        println(s"[$taskName] Found carcassTileToLoot=$carcassTile at time=$timeOfDeath")
        autoLootState.lastLootedCarcassTile match {
          case Some((lastTile, _)) if lastTile == carcassTile =>
            println(s"[$taskName] carcassTile $carcassTile already moved last, switching to moving carcass state")
            state.copy(autoLoot = autoLootState.copy(stateLooting = "moving carcass"))

          case _ =>
            println(s"[$taskName] new carcassTile $carcassTile, focus on carcass")
            state.copy(autoLoot = autoLootState.copy(
              stateLooting = "focus on carcass",
              lastLootedCarcassTile = Some((carcassTile, timeOfDeath))
            ))
        }

      case None =>
        println(s"[$taskName] no carcass to loot, setting state to free")
        state.copy(autoLoot = autoLootState.copy(stateLooting = "free"))
    }

    println(s"[$taskName] next autoLoot.stateLooting = ${nextState.autoLoot.stateLooting}")
    Some((nextState, NoOpTask))
  }



  def handleMovingOldCarcass(json: JsValue, settings: UISettings, state: GameState): Option[(GameState, MKTask)] = {
    val taskName = "handleMovingOldCarcass"
    println(s"[$taskName] Entered function.")
    val metaId: String = (json \ "metaGeneratedId").asOpt[String].getOrElse("null")
    val currentTime = System.currentTimeMillis()
    val autoLoot = state.autoLoot


    if (state.autoLoot.autoLootActionThrottle > currentTime - autoLoot.lastAutoLootActionTime) {
      return Some(state -> NoOpTask)
    }

    println(s"[$taskName] carcassToLootImmediately: ${autoLoot.carcassToLootImmediately}")
    println(s"[$taskName] carcassToLootAfterFight: ${autoLoot.carcassToLootAfterFight}")

    val excludedTilesGrid = (autoLoot.carcassToLootImmediately ++ autoLoot.carcassToLootAfterFight)
      .flatMap { case (tileId, _, _) => convertGameLocationToGrid(json, tileId) }.toSet

    autoLoot.lastLootedCarcassTile match {
      case Some((carcassToMove, _)) =>
        extractItemPositionFromMapOnScreen(json, carcassToMove) match {
          case Some((itemX, itemY)) =>
            val possibleTiles = List("7x5", "7x6", "7x7", "8x5", "8x6", "8x7", "9x5", "9x6", "9x7")
            val areaInfo = (json \ "areaInfo").as[JsObject]

            val walkableTileOpt = findRandomWalkableTile(json, areaInfo, possibleTiles.filterNot(tile =>
              excludedTilesGrid.contains(tile) || tile == convertGameLocationToGrid(json, carcassToMove).getOrElse("")
            ))

            walkableTileOpt match {
              case Some(tileIndex) =>
                val mapPanelMap = (json \ "screenInfo" \ "mapPanelLoc").as[Map[String, JsObject]]
                val tileObj = mapPanelMap.getOrElse(tileIndex, JsObject.empty)
                val targetX = (tileObj \ "x").asOpt[Int].getOrElse(0)
                val targetY = (tileObj \ "y").asOpt[Int].getOrElse(0)

                val actions = moveSingleItem(itemX, itemY, targetX, targetY, metaId)
                val newState = state.copy(
                  autoLoot = autoLoot.copy(
                    stateLooting = "focus on carcass",
                    lastAutoLootActionTime = currentTime
                  )
                )
                Some((newState, MKTask("handleMovingOldCarcas", MKActions(actions, Nil))))

              case None =>
                Some((state.copy(
                  autoLoot = autoLoot.copy(stateLooting = "free"),
//                  caveBot = state.caveBot.copy(stateHunting = "free")
                ), NoOpTask))
            }

          case None =>
            Some((state.copy(
              autoLoot = autoLoot.copy(stateLooting = "free"),
//              caveBot = state.caveBot.copy(stateHunting = "free")
            ), NoOpTask))
        }

      case None =>
        Some((state.copy(
          autoLoot = autoLoot.copy(stateLooting = "free"),
//          caveBot = state.caveBot.copy(stateHunting = "free")
        ), NoOpTask))
    }
  }


  def handleLootPlunder(json: JsValue, settings: UISettings, state: GameState): Option[(GameState, MKTask)] = {
    println(s"Start handleLootPlunder. stateLooting: ${state.autoLoot.stateLooting}, stateLootPlunder: ${state.autoLoot.stateLootPlunder}.")
    val taskName = "handleLootPlunder"
    val currentTime        = System.currentTimeMillis()

    if (state.autoLoot.autoLootActionThrottle > currentTime - state.autoLoot.lastAutoLootActionTime) {
      println(s"[$taskName] Too soon since last action → NoOp")
      return Some(state -> NoOpTask)
    }


    state.autoLoot.stateLootPlunder match {
      case "free" => handleAssessLoot(json, settings, state)
      case "move item" => handleMoveItem(json, settings, state)
      case "handle food" => handleEatingFood(json, settings, state)
      case "open subcontainer" => handleOpenSubcontainer(json, settings, state)
      case _ =>
        Some((state.copy(autoLoot = state.autoLoot.copy(
          stateLooting = "free",
          stateLootPlunder = "free",
          lootIdToPlunder             = 0,
          lootCountToPlunder          = 0,
          lootScreenPosToPlunder      = Vec(0,0),
          dropScreenPosToPlunder      = Vec(0,0),
          lastItemIdAndCountEngaged = (0,0),
        )), NoOpTask))
    }
  }

  def handleAssessLoot(json: JsValue, settings: UISettings, state: GameState): Option[(GameState, MKTask)] = {
    val taskName = "handleAssessLoot"
    val al = state.autoLoot
    println(s"[$taskName] Entered function.")

    val currentTime        = System.currentTimeMillis()
    if (al.autoLootActionThrottle * al.throttleCoefficient > currentTime - al.lastAutoLootActionTime) {
      println(s"[$taskName] Too soon since last action → NoOp")
      return Some(state -> NoOpTask)
    }

    if (al.unfreezeAutoLootCooldown  < currentTime - al.startTimeOfLooting) {
      val updatedState = resetAutoLootState(state, s"[$taskName] Time for looting has ended. Resetting.")
      return Some(updatedState -> NoOpTask)
    }


    val gen                = state.general
    val ignoreContainers   = gen.initialContainersList.toSet
    val containersInfoOpt  = (json \ "containersInfo").asOpt[JsObject]
    val screenInfoOpt      = (json \ "screenInfo").asOpt[JsObject]

    if (containersInfoOpt.isEmpty) println(s"[$taskName] ❌ Missing containersInfo")
    if (screenInfoOpt.isEmpty)     println(s"[$taskName] ❌ Missing screenInfo")

    for {
      containersInfo   <- containersInfoOpt
      screenInfo       <- screenInfoOpt
      newKeys           = containersInfo.keys.toList.filterNot(ignoreContainers.contains).sorted
      _ = println(s"[$taskName] New candidate containers (excluding initial): $newKeys")
      lastContainerKey <- newKeys.lastOption
      lastContainer    <- (containersInfo \ lastContainerKey).asOpt[JsObject]
      itemsValue       <- (lastContainer \ "items").toOption
    } yield {
      println(s"[$taskName] Found lastContainerKey: $lastContainerKey with items: $itemsValue")
      println(s"[$taskName] Loot settings: ${settings.autoLootSettings.lootList}")


      itemsValue match {
        case JsString("empty") =>
          println(s"")
          val updatedState = resetAutoLootState(state, s"[$taskName] Container empty. Resetting states.")
          return Some(updatedState -> NoOpTask)

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

          println(s"[$taskName] Parsed lootActions: $lootActions")

          // now find the first item whose ID is in the map
          val foundLoot = itemsObj.fields.collectFirst {
            case (slot, info) if lootActions.contains((info \ "itemId").as[Int]) =>
              (slot, info, lootActions((info \ "itemId").as[Int]))
          }

          foundLoot match {
            case Some((slot, info, action)) =>
              val itemId    = (info \ "itemId").as[Int]
              val itemCount = (info \ "itemCount").as[Int]


              if (state.autoLoot.lastItemIdAndCountEngaged == (itemId, itemCount)) {
                println(s"[$taskName] Item $itemId with count $itemCount already looted, skipping.")
                return Some(state -> NoOpTask)
              }

              val itemSlot  = slot.replace("slot", "item")
              val invPanelLoc = (screenInfo \ "inventoryPanelLoc").asOpt[JsObject].getOrElse {
                println(s"[$taskName] ❗️ inventoryPanelLoc is missing or not an object")
                JsObject.empty
              }
              val invKeys = invPanelLoc.keys.toList
              println(s"[$taskName] invPanelLoc keys: $invKeys")

              // find the one whose key begins with your containerX prefix
              val matchedContainerKey = invKeys
                .find(_.startsWith(lastContainerKey))
                .getOrElse {
                  println(s"[$taskName] ❗️ No inventoryPanelLoc key startsWith '$lastContainerKey', defaulting to literal")
                  lastContainerKey
                }

              println(s"[$taskName] matched pickup container key = '$matchedContainerKey'")
              println(s"[$taskName] itemSlot = '$itemSlot'")

              // now grab the contentsPanel→itemN under that matched key
              val pickupPos = (screenInfo \ "inventoryPanelLoc" \ matchedContainerKey \ "contentsPanel" \ itemSlot)
                .asOpt[JsObject]
                .map(s => Vec((s \ "x").as[Int], (s \ "y").as[Int]))
                .getOrElse {
                  println(s"[$taskName] ❗️ Couldn't find contentsPanel→$itemSlot under inventoryPanelLoc→'$matchedContainerKey'")
                  Vec(0,0)
                }
              println(s"[$taskName] Pickup at $pickupPos, action = '$action'")

              // figure out drop-position
              val dropPos: Vec =
                if (action == "g") {
                  // find and reserve a ground tile here
                  val excluded = (state.autoLoot.carcassToLootImmediately ++ state.autoLoot.carcassToLootAfterFight)
                    .flatMap { case (tileId,_,_) => convertGameLocationToGrid(json, tileId) }
                    .toSet

                  val tiles = List("7x5","7x6","7x7","8x5","8x6","8x7","9x5","9x6","9x7")
                  val freeTileOpt = findRandomWalkableTile(json, (json \ "areaInfo").as[JsObject], tiles.filterNot(excluded.contains))

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
                      println(s"[$taskName] ❗️ No inventoryPanelLoc key startsWith '$targetPrefix', defaulting to literal")
                      targetPrefix
                    }
                  println(s"[$taskName] matched drop container key = '$destKey'")

                  val dropPos = (invPanelLoc \ destKey \ "contentsPanel" \ "item0")
                    .asOpt[JsObject]
                    .map(s => Vec((s \ "x").as[Int], (s \ "y").as[Int]))
                    .getOrElse {
                      println(s"[$taskName] ❗️ Couldn't find contentsPanel→item0 under inventoryPanelLoc→'$destKey'")
                      Vec(0,0)
                    }
                  println(s"[$taskName] Drop at $dropPos")

                  dropPos
                }
                else {
                  println(s"[$taskName] Unknown action '$action' → dropPos = Vec(0,0)")
                  Vec(0,0)
                }


              val updatedState = state.copy(autoLoot = state.autoLoot.copy(
                stateLootPlunder          = "move item",
                lootIdToPlunder           = itemId,
                lootCountToPlunder        = itemCount,
                lootScreenPosToPlunder    = pickupPos,
                dropScreenPosToPlunder    = dropPos,
                lastAutoLootActionTime    = currentTime,
                lastItemIdAndCountEngaged = (0, 0)
              ))
              (updatedState, NoOpTask)

            case None =>

              val regTime = (json \ "characterInfo" \ "RegTime").asOpt[Int].getOrElse(0)

              println(s"[$taskName] No primary loot found, searching for food.")

              val foundFood = if (regTime > 700) {
                println(s"[$taskName] RegTime ($regTime) > 700, skipping food search.")
                None
              } else {
                itemsObj.fields.collectFirst {
                  case (slot, itemInfo) if StaticGameInfo.Items.FoodsIds.contains((itemInfo \ "itemId").as[Int]) =>
                    println(s"[$taskName] Found food in slot $slot: itemId=${(itemInfo \ "itemId").as[Int]}")
                    (slot, itemInfo)
                }
              }

              foundFood match {
                case Some((slot, foodInfo)) =>
                  val itemSlot = slot.replace("slot", "item")
                  val itemId = (foodInfo \ "itemId").as[Int]
                  val itemCount = (foodInfo \ "itemCount").as[Int]

                  if (state.autoLoot.lastItemIdAndCountEngaged == (itemId, itemCount)) {
                    println(s"[$taskName] Food $itemId already looted, skipping.")
                    return Some(state -> NoOpTask)
                  }

                  // reuse invPanelLoc & invKeys
                  val invPanelLoc = (screenInfo \ "inventoryPanelLoc").asOpt[JsObject].getOrElse(JsObject.empty)
                  val invKeys     = invPanelLoc.keys.toList

                  // find the full container key
                  val matchedContainerKey = invKeys
                    .find(_.startsWith(lastContainerKey))
                    .getOrElse {
                      println(s"[$taskName] ❗️ No inventoryPanelLoc key startsWith '$lastContainerKey', defaulting to literal")
                      lastContainerKey
                    }
                  println(s"[$taskName] Using container key = '$matchedContainerKey' for food")

                  // pull out the item’s coords
                  val lootScreenPos = (invPanelLoc \ matchedContainerKey \ "contentsPanel" \ itemSlot)
                    .asOpt[JsObject]
                    .map(s => Vec((s \ "x").as[Int], (s \ "y").as[Int]))
                    .getOrElse {
                      println(s"[$taskName] ❗️ Couldn't find contentsPanel→$itemSlot under '$matchedContainerKey'")
                      Vec(0,0)
                    }
                  println(s"[$taskName] Food screen position: $lootScreenPos")

                  val updatedState = state.copy(autoLoot = state.autoLoot.copy(
                    stateLootPlunder = "handle food",
                    lootIdToPlunder = itemId,
                    lootCountToPlunder = itemCount,
                    lootScreenPosToPlunder = lootScreenPos,
                    lastAutoLootActionTime    = currentTime,
                    lastItemIdAndCountEngaged = (0, 0),
                  ))
                  println(s"[$taskName] Updated state to handle food.")
                  (updatedState, NoOpTask)

                case None =>
                  println(s"[$taskName] No food found, searching for containers.")
                  val foundContainer = itemsObj.fields.collectFirst {
                    case (slot, itemInfo) if (itemInfo \ "isContainer").asOpt[Boolean].getOrElse(false) =>
                      println(s"[$taskName] Found subcontainer in slot $slot: itemId=${(itemInfo \ "itemId").as[Int]}")
                      (slot, itemInfo)
                  }

                  foundContainer match {
                    case Some((slot, containerInfo)) =>
                      val itemSlot = slot.replace("slot", "item")
                      val bagId   = (containerInfo \ "itemId").as[Int]

                      if (state.autoLoot.lastItemIdAndCountEngaged == (bagId, 1)) {
                        println(s"[$taskName] ")
                        val updatedState = resetAutoLootState(state, s"[$taskName] Bag $bagId already looted, skipping.")
                        return Some(updatedState -> NoOpTask)
                      }

                      // reuse invPanelLoc & invKeys
                      val invPanelLoc = (screenInfo \ "inventoryPanelLoc").asOpt[JsObject].getOrElse(JsObject.empty)
                      val invKeys     = invPanelLoc.keys.toList

                      // full key lookup
                      val matchedContainerKey = invKeys
                        .find(_.startsWith(lastContainerKey))
                        .getOrElse {
                          println(s"[$taskName] ❗️ No inventoryPanelLoc key startsWith '$lastContainerKey', defaulting to literal")
                          lastContainerKey
                        }
                      println(s"[$taskName] Using container key = '$matchedContainerKey' for subcontainer")

                      // get coords
                      val lootScreenPos = (invPanelLoc \ matchedContainerKey \ "contentsPanel" \ itemSlot)
                        .asOpt[JsObject]
                        .map(s => Vec((s \ "x").as[Int], (s \ "y").as[Int]))
                        .getOrElse {
                          println(s"[$taskName] ❗️ Couldn't find contentsPanel→$itemSlot under '$matchedContainerKey'")
                          Vec(0,0)
                        }
                      println(s"[$taskName] Subcontainer screen position: $lootScreenPos")

                      val newState = state.copy(autoLoot = state.autoLoot.copy(
                        stateLootPlunder = "open subcontainer",
                        lootIdToPlunder = bagId,
                        lootCountToPlunder = 1,
                        lastItemIdAndCountEngaged = (0, 0),
                        lootScreenPosToPlunder = lootScreenPos,
                        lastAutoLootActionTime    = currentTime
                      ))
                      println(s"[$taskName] Updated state for open subcontainer")
                      (newState, NoOpTask)

                    case None =>
                      val updatedState = resetAutoLootState(state, s"[$taskName] Nothing to loot. Resetting states.")
                      return Some(updatedState -> NoOpTask)
                  }
              }
          }

        case _ =>
          val updatedState = resetAutoLootState(state, s"[$taskName] Items value has unexpected type. Resetting states.")
          return Some(updatedState -> NoOpTask)
      }
    }
  }


    def handleMoveItem(json: JsValue, settings: UISettings, state: GameState)
    : Option[(GameState, MKTask)] = {
      val taskName = "handleMoveItem"
      val al = state.autoLoot
      val metaId: String = (json \ "metaGeneratedId").asOpt[String].getOrElse("null")
      println(s"[$taskName] Entered function.")
      val currentTime        = System.currentTimeMillis()

      if (al.autoLootActionThrottle > currentTime - al.lastAutoLootActionTime) {
        println(s"[$taskName] Too soon since last action → NoOp")
        return Some(state -> NoOpTask)
      }

      val pickup = al.lootScreenPosToPlunder
      val drop   = al.dropScreenPosToPlunder
      val count  = al.lootCountToPlunder

      // guard against missing positions
      if (pickup == Vec(0,0) || drop == Vec(0,0)) {
        val updatedState = resetAutoLootState(state, s"[$taskName] Invalid pickup or drop → abort.")
        return Some(updatedState -> NoOpTask)
      }

      // always the same mouse actions (we’re just dragging one slot)
      val mouseActions = moveSingleItem(pickup.x, pickup.y, drop.x, drop.y, metaId)

      // ctrl‐hold for stacks
      val keyboardActions =
        if (count > 1) List(PressCtrl(metaId), HoldCtrlFor(1.second, metaId), ReleaseCtrl(metaId))
        else Nil

      // build the MKTask name straight from stateLootPlunder

      // reset all the autoLoot fields and stamp the new timestamp
      val updatedState = state.copy(autoLoot = state.autoLoot.copy(
        stateLootPlunder            = "free",
        lootIdToPlunder             = 0,
        lootCountToPlunder          = 0,
        lootScreenPosToPlunder      = Vec(0,0),
        dropScreenPosToPlunder      = Vec(0,0),
        lastAutoLootActionTime      = currentTime,
        lastItemIdAndCountEngaged = (al.lootIdToPlunder, al.lootCountToPlunder)
      ))

      println(s"[$taskName] Moving Item ${al.lootIdToPlunder}, Count: ${al.lootCountToPlunder}'")
      Some((updatedState,
        MKTask(taskName, MKActions(mouse = mouseActions, keyboard = keyboardActions))
      ))
    }


  def handleEatingFood(json: JsValue, settings: UISettings, state: GameState): Option[(GameState, MKTask)] = {
    val taskName = "handleEatingFood"
    println(s"[$taskName] Entered function.")
    val al = state.autoLoot
    val currentTime        = System.currentTimeMillis()
    val metaId: String = (json \ "metaGeneratedId").asOpt[String].getOrElse("null")

    if (al.autoLootActionThrottle > currentTime - al.lastAutoLootActionTime) {
      println(s"[$taskName] Too soon since last action → NoOp")
      return Some(state -> NoOpTask)
    }

    val pos = al.lootScreenPosToPlunder

    // if we never got a valid food position, bail out
    if (pos == Vec(0, 0)) {
      println("[handleEatingFood] Invalid food position → abort")
      val updated = state.copy(autoLoot = al.copy(stateLootPlunder = "free"))
      return Some((updated, NoOpTask))
    }

    // right-click on the food
    val mouseActions = List(
      MoveMouse(pos.x, pos.y, metaId),
      RightButtonPress(pos.x, pos.y, metaId),
      RightButtonRelease(pos.x, pos.y, metaId)
    )

    // reset our autoLoot state
    val updatedState = state.copy(autoLoot = state.autoLoot.copy(
      stateLootPlunder       = "free",
      lootIdToPlunder        = 0,
      lootCountToPlunder     = 0,
      lootScreenPosToPlunder = Vec(0, 0),
      lastItemIdAndCountEngaged = (al.lootIdToPlunder, al.lootCountToPlunder),
      lastAutoLootActionTime = currentTime
    ))

    println(s"[handleEatingFood] issuing eat food at $pos")
    Some((updatedState, MKTask("eat food", MKActions(mouse = mouseActions, keyboard = Nil))))
  }


  def handleOpenSubcontainer(json: JsValue, settings: UISettings, state: GameState): Option[(GameState, MKTask)] = {
    val taskName = "handleOpenSubcontainer"
    println(s"[$taskName] Entered function.")
    val metaId: String = (json \ "metaGeneratedId").asOpt[String].getOrElse("null")
    val currentTime        = System.currentTimeMillis()

    if (state.autoLoot.autoLootActionThrottle > currentTime - state.autoLoot.lastAutoLootActionTime) {
      println(s"[$taskName] Too soon since last action → NoOp")
      return Some(state -> NoOpTask)
    }

    val pos = state.autoLoot.lootScreenPosToPlunder

    if (pos == Vec(0, 0)) {
      val updated = state.copy(autoLoot = state.autoLoot.copy(stateLootPlunder = "free"))
      return Some((updated, NoOpTask))
    }

    val mouseActions = List(
      MoveMouse(pos.x, pos.y, metaId),
      RightButtonPress(pos.x, pos.y, metaId),
      RightButtonRelease(pos.x, pos.y, metaId)
    )

    val updatedState = state.copy(autoLoot = state.autoLoot.copy(
      stateLootPlunder = "free",
      lastItemIdAndCountEngaged = (state.autoLoot.lootIdToPlunder, 1),
      lootIdToPlunder = 0,
      lootScreenPosToPlunder = Vec(0, 0),
      lastAutoLootActionTime   = currentTime
    ))

    println(s"[$taskName] Openning subcontainer.")
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

  def moveSingleItem(xItemPosition: Int, yItemPositon: Int, xDestPos: Int, yDestPos: Int, metaId: String): List[MouseAction] =
    List(
      MoveMouse(xItemPosition, yItemPositon, metaId),
      LeftButtonPress(xItemPosition, yItemPositon, metaId),
      MoveMouse(xDestPos, yDestPos, metaId),
      LeftButtonRelease(xDestPos, yDestPos, metaId)
    )

  def findRandomWalkableTile(json: JsValue, areaInfo: JsObject, possibleTiles: List[String]): Option[String] = {
    println("Inside findRandomWalkableTile")
    val allMovementEnablerIds: List[Int] = StaticGameInfo.LevelMovementEnablers.AllIds

    // Get character position
    val charPos = Vec(
      (json \ "characterInfo" \ "PositionX").as[Int],
      (json \ "characterInfo" \ "PositionY").as[Int]
    )

    val tilesInfo = (areaInfo \ "tiles").as[JsObject]
    println(s"[DEBUG] Character at: $charPos, checking within distance 3")


    // Filter tiles by distance first, then check walkability
    val nearbyWalkableTiles = tilesInfo.fields.collect {
      case (tileId, tileObj: JsObject) =>
        // Parse tile position from tileId
        val tileX = tileId.substring(0, 5).toInt
        val tileY = tileId.substring(5, 10).toInt
        val tilePos = Vec(tileX, tileY)

        // Calculate distance from character
        val distance = math.max(
          math.abs(tilePos.x - charPos.x),
          math.abs(tilePos.y - charPos.y)
        )

        if (distance <= 3) {
          val indexOpt = (tileObj \ "index").asOpt[String]

          if (indexOpt.exists(possibleTiles.contains)) {
            val tileIsWalkable = (tileObj \ "isWalkable").asOpt[Boolean].getOrElse(false)
            val tileItems = (tileObj \ "items").asOpt[JsObject].getOrElse(Json.obj())

            val hasBlockingItem = tileItems.fields.exists { case (_, itemObj) =>
              val idOpt = (itemObj \ "id").asOpt[Int]
              allMovementEnablerIds.contains(idOpt.getOrElse(0))
            }

            if (tileIsWalkable && !hasBlockingItem) {
              println(s"[DEBUG] Found nearby walkable tile: ${indexOpt.get} at distance $distance")
              Some(indexOpt.get)
            } else None
          } else None
        } else None
    }.flatten.toList

    println(s"[DEBUG] Found ${nearbyWalkableTiles.size} nearby walkable tiles")
    Random.shuffle(nearbyWalkableTiles).headOption


//    val allWalkableIndices = tilesInfo.fields.collect {
//      case (tileId, tileObj: JsObject) =>
//        val indexOpt = (tileObj \ "index").asOpt[String]
//        println(s"\n[DEBUG] Checking tileId = $tileId, index = $indexOpt")
//
//        if (!indexOpt.exists(possibleTiles.contains)) {
//          println(s"[DEBUG] → Skipped: index not in possibleTiles")
//          None
//        } else {
//          val tileIsWalkable = (tileObj \ "isWalkable").asOpt[Boolean].getOrElse(false)
//          println(s"[DEBUG] isWalkable = $tileIsWalkable")
//
//          val tileItemsTry = (tileObj \ "items")
//          println(s"[DEBUG] Raw 'items' value = $tileItemsTry")
//
//          val tileItems = (tileObj \ "items").asOpt[JsObject].getOrElse(Json.obj())
//
//          val hasBlockingItem = tileItems.fields.exists { case (_, itemObj) =>
//            val idOpt = (itemObj \ "id").asOpt[Int]
//            println(s"[DEBUG]   → item id = $idOpt")
//            allMovementEnablerIds.contains(idOpt.getOrElse(0))
//          }
//
//          println(s"[DEBUG] hasBlockingItem = $hasBlockingItem")
//
//          if (tileIsWalkable && !hasBlockingItem) {
//            println(s"[DEBUG] → Accepted: ${indexOpt.get}")
//            Some(indexOpt.get)
//          } else {
//            println(s"[DEBUG] → Skipped: not walkable or blocked")
//            None
//          }
//        }
//    }.flatten.toList
//
//    println(s"\n[DEBUG] Final walkable tile indices: $allWalkableIndices")
//
//    val result = Random.shuffle(allWalkableIndices).headOption
//    println(s"[DEBUG] Selected random walkable tile: $result")
//    result
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


