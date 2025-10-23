package processing

import cats.effect.IO
import cats.effect.Ref
import play.api.libs.json.{JsObject, JsString, JsValue, Json, Reads, Writes}
import mouse.{LeftButtonPress, LeftButtonRelease, MouseAction, MoveMouse, RightButtonPress, RightButtonRelease}
import keyboard.{HoldCtrlFor, KeyboardAction, PressCtrl, ReleaseCtrl}
import utils.SettingsUtils.UISettings
import utils.{GameState, HotkeyBind}
import utils.ProcessingUtils.{MKActions, MKTask, NoOpTask, Step}

import scala.concurrent.duration.DurationInt



object GeneralFeature {

  import cats.syntax.all._

  def run(json: JsValue, settings: UISettings, state: GameState): (GameState, List[MKTask]) = {
    val (s, maybeTask) = Steps.runAll(json, settings, state)
    (s, maybeTask.toList)
  }

  private object Steps {
    // ordered list of steps
    val allSteps: List[Step] = List(
      GoldChange,
      ResetButton,
      CheckingOpenBackpack,
      SortCarcassesByDistanceAndTime,
      EatingFoodFromFloor,
      UpdateHotkeys,
      MonitorMovement,
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

  private object MonitorMovement extends Step {
    private val taskName = "MonitorMovement"

    override def run(state: GameState, json: JsValue, settings: UISettings): Option[(GameState, MKTask)] = {
      val now = System.currentTimeMillis()

      // Extract current player position
      val currentX = (json \ "characterInfo" \ "PositionX").asOpt[Int].getOrElse(0)
      val currentY = (json \ "characterInfo" \ "PositionY").asOpt[Int].getOrElse(0)
      val currentZ = (json \ "characterInfo" \ "PositionZ").asOpt[Int].getOrElse(0)
      val currentPos = (currentX, currentY, currentZ)

      val lastPos = state.general.lastPlayerPosition

      // Check if position has changed
      if (currentPos != lastPos) {
        // Position changed - update both position and timestamp
        val newGeneral = state.general.copy(
          lastPlayerPosition = currentPos,
          lastPlayerChange = now
        )
        Some(state.copy(general = newGeneral) -> NoOpTask)
      } else {
        // Position unchanged - no update needed
        None
      }
    }
  }

  private object UpdateHotkeys extends Step {
    private val taskName = "UpdateHotkeys"

    override def run(state: GameState, json: JsValue, settings: UISettings): Option[(GameState, MKTask)] = {
      val currentStatus = state.hotkeys.hotkeysChangeStatus
      val hotkeysWindowOpen = (json \ "screenInfo" \ "extraWindowLoc" \ "Hotkeys").asOpt[JsObject].isDefined

      currentStatus match {
        case "free" =>
          // Initial state - perform first update
          performUpdate(state, json)

        case "ready" =>
          // Monitor for hotkeys window opening
          if (hotkeysWindowOpen) {
            println(s"[$taskName] Hotkeys window detected - switching to 'change in progress'")
            val newHotkeysState = state.hotkeys.copy(hotkeysChangeStatus = "change in progress")
            Some(state.copy(hotkeys = newHotkeysState) -> NoOpTask)
          } else {
            None
          }

        case "change in progress" =>
          // Wait for hotkeys window to close
          if (!hotkeysWindowOpen) {
            println(s"[$taskName] Hotkeys window closed - switching to 'update in progress'")
            val newHotkeysState = state.hotkeys.copy(hotkeysChangeStatus = "update in progress")
            Some(state.copy(hotkeys = newHotkeysState) -> NoOpTask)
          } else {
            None
          }

        case "update in progress" =>
          // Perform the update
          performUpdate(state, json)

        case _ =>
          println(s"[$taskName] Unknown status: $currentStatus - resetting to 'free'")
          val newHotkeysState = state.hotkeys.copy(hotkeysChangeStatus = "free")
          Some(state.copy(hotkeys = newHotkeysState) -> NoOpTask)
      }
    }

    private def performUpdate(state: GameState, json: JsValue): Option[(GameState, MKTask)] = {
      val hotkeysBindsOpt = (json \ "hotkeysBinds").asOpt[JsObject]

      hotkeysBindsOpt match {
        case Some(hotkeysBinds) =>
          // Parse all hotkey bindings
          val parsedBinds = hotkeysBinds.fields.flatMap { case (key, bindData) =>
            val bindOpt = (bindData \ "autoSend").asOpt[Boolean].map { autoSend =>
              val value = (bindData \ "value").asOpt[String].filter(_.nonEmpty)
              val itemId = (bindData \ "itemId").asOpt[Int]
              val useType = (bindData \ "useType").asOpt[Int]

              key -> HotkeyBind(
                autoSend = autoSend,
                value = value,
                itemId = itemId,
                useType = useType
              )
            }
            bindOpt
          }.toMap

          // Always update when in "free" or "update in progress" states
          val newHotkeysState = state.hotkeys.copy(
            binds = parsedBinds,
            hotkeysChangeStatus = "ready"
          )

          val newState = state.copy(hotkeys = newHotkeysState)

          println(s"[$taskName] Updated ${parsedBinds.size} hotkey bindings:")
          parsedBinds.foreach { case (key, bind) =>
            val bindType = (bind.itemId, bind.value) match {
              case (Some(id), _) =>
                val useTypeStr = bind.useType match {
                  case Some(1) => "use on yourself"
                  case Some(2) => "use on target"
                  case Some(3) => "use with crosshair"
                  case _ => "unknown"
                }
                s"item $id ($useTypeStr)"
              case (_, Some(text)) => s"text: $text"
              case _ => "empty"
            }
            println(s"  $key: $bindType, autoSend=${bind.autoSend}")
          }
          println(s"[$taskName] Status changed to 'ready'")

          Some(newState -> NoOpTask)

        case None =>
          println(s"[$taskName] No hotkeysBinds found in JSON")
          None
      }
    }
  }


  private object GoldChange extends Step {
    private val taskName = "GoldChange"
    private val goldIds = Set(3031, 3035) // gold coin and platinum coin
    private val targetCount = 100

    override def run(state: GameState, json: JsValue, settings: UISettings): Option[(GameState, MKTask)] = {
      val now = System.currentTimeMillis()
      val metaId: String = (json \ "metaGeneratedId").asOpt[String].getOrElse("null")
      // Check throttle
      if (state.general.generalMidThrottle > now - state.general.lastGeneralActionTime) {
        return Some(state -> NoOpTask)
      }

      val containersInfo = (json \ "containersInfo").asOpt[JsObject].getOrElse(Json.obj())
      val inventoryPanelLoc = (json \ "screenInfo" \ "inventoryPanelLoc").asOpt[JsObject].getOrElse(Json.obj())

      // Search through all containers
      val goldStackOpt = containersInfo.fields.collectFirst {
        case (containerKey, containerData: JsObject) =>
          val items = (containerData \ "items").asOpt[JsObject].getOrElse(Json.obj())

          items.fields.collectFirst {
            case (slotKey, item: JsObject)
              if goldIds.contains((item \ "itemId").asOpt[Int].getOrElse(0)) &&
                (item \ "itemCount").asOpt[Int].getOrElse(0) == targetCount =>
              (containerKey, slotKey)
          }
      }.flatten

      goldStackOpt match {
        case Some((containerKey, slotKey)) =>
          // Extract slot number from slotKey (e.g., "slot1" -> 1)
          val slotNum = slotKey.replaceAll("[^0-9]", "")

          // Find matching inventory panel by checking if key contains containerKey as substring
          val coordsOpt = inventoryPanelLoc.fields.collectFirst {
            case (panelKey, panelData: JsObject) if panelKey.contains(containerKey) =>
              val contentsPanel = (panelData \ "contentsPanel").asOpt[JsObject].getOrElse(Json.obj())
              val itemKey = s"item$slotNum"

              for {
                itemCoords <- contentsPanel.value.get(itemKey)
                x <- (itemCoords \ "x").asOpt[Int]
                y <- (itemCoords \ "y").asOpt[Int]
              } yield (x, y)
          }.flatten

          coordsOpt match {
            case Some((x, y)) =>
              val mouseActions = List(
                MoveMouse(x, y, metaId),
                RightButtonPress(x, y, metaId),
                RightButtonRelease(x, y, metaId)
              )
              val mkActions = MKActions(mouseActions, Nil)
              val newGeneral = state.general.copy(
                lastActionCommand = Some(taskName),
                lastActionTimestamp = Some(now),
                lastGeneralActionTime = now
              )
              println(s"[$taskName] Right-clicking gold stack at ($x,$y) in $containerKey, $slotKey")
              Some(state.copy(general = newGeneral) -> MKTask(taskName, mkActions))

            case None =>
              println(s"[$taskName] Found gold stack but couldn't find screen coordinates")
              None
          }

        case None =>
          None
      }
    }
  }

  private object ResetButton extends Step {
    private val taskName = "ResetButton"
    private val lookTimeout = 15000L // 15 seconds

    def run(state: GameState, json: JsValue, settings: UISettings): Option[(GameState, MKTask)] = {
      val now = System.currentTimeMillis()
      val metaId: String = (json \ "metaGeneratedId").asOpt[String].getOrElse("null")

      if (state.teamHunt.teamHuntActionThrottle > now - state.teamHunt.lastTeamHuntActionTime) {
        println(s"[$taskName] Too soon since last action → NoOp")
        return Some(state -> NoOpTask)
      }

      val extraWindowLocOpt = (json \ "screenInfo" \ "extraWindowLoc").validate[JsObject].asOpt

      extraWindowLocOpt match {
        case Some(loc) if loc.keys.exists(_ == "Look") =>
          val lookBtnOpt = (loc \ "Look").validate[JsObject].asOpt.flatMap { btn =>
            for {
              x <- (btn \ "posX").validate[Int].asOpt
              y <- (btn \ "posY").validate[Int].asOpt
            } yield (x, y)
          }

          lookBtnOpt match {
            case Some((x, y)) =>
              val lastTime = state.general.resetButtonTime
              val elapsed = now - lastTime
              if (lastTime == 0L) {
                // Start timer
                val newGeneral = state.general.copy(resetButtonTime = now)
                Some(state.copy(general = newGeneral) -> NoOpTask)
              } else if (elapsed > lookTimeout) {
                // Timeout reached, click and reset timer
                val mkActions = MKActions(
                  List(
                    MoveMouse(x, y, metaId),
                    LeftButtonPress(x, y, metaId),
                    LeftButtonRelease(x, y, metaId)
                  ),
                  Nil
                )
                val task = MKTask(taskName, mkActions)
                val newGeneral = state.general.copy(
                  lastActionCommand = Some(taskName),
                  lastActionTimestamp = Some(now),
                  resetButtonTime = 0L
                )
                println(s"[$taskName] Clicking 'Look' button at ($x,$y)")
                Some(state.copy(general = newGeneral) -> task)
              } else {
                // Still waiting
                None
              }
            case None =>
              // "Look" button exists but no position info
              None
          }
        case _ =>
          // "Look" button not present, reset timer if needed
          if (state.general.resetButtonTime != 0L) {
            val newGeneral = state.general.copy(resetButtonTime = 0L)
            Some(state.copy(general = newGeneral) -> NoOpTask)
          } else {
            None
          }
      }
    }
  }

  private object EatingFoodFromFloor extends Step {
    private val taskName = "EatingFoodFromFloor"

    override def run(state: GameState, json: JsValue, settings: UISettings): Option[(GameState, MKTask)] = {
      val now = System.currentTimeMillis()
      val metaId: String = (json \ "metaGeneratedId").asOpt[String].getOrElse("null")

      val regTime = (json \ "characterInfo" \ "RegTime").asOpt[Int].getOrElse(0)
      val charX = (json \ "characterInfo" \ "PositionX").asOpt[Int].getOrElse(0)
      val charY = (json \ "characterInfo" \ "PositionY").asOpt[Int].getOrElse(0)
      val charZ = (json \ "characterInfo" \ "PositionZ").asOpt[Int].getOrElse(0)
      val tiles = (json \ "areaInfo" \ "tiles").asOpt[Map[String, JsObject]].getOrElse(Map.empty)
      val foodIds = utils.StaticGameInfo.Items.FoodsIds

//      println(s"[$taskName] BEGIN: regTime=$regTime, foodThreshold=${state.general.foodThreshold}, char=($charX,$charY,$charZ), now=$now, lastGeneralActionTime=${state.general.lastGeneralActionTime}")

      if (regTime > state.general.foodThreshold) {
//        println(s"[$taskName] EXIT: RegTime ($regTime) > foodThreshold (${state.general.foodThreshold}), skipping food search.")
        return None
      }

      if (state.general.generalMidThrottle > now - state.general.lastGeneralActionTime) {
//        println(s"[$taskName] EXIT: Too soon since last action (throttle: ${state.general.generalMidThrottle}ms).")
        return Some(state -> NoOpTask)
      }

      def tileId(x: Int, y: Int, z: Int): String = f"$x%05d$y%05d$z%02d"

      val searchTiles = for {
        dx <- -1 to 1
        dy <- -1 to 1
      } yield tileId(charX + dx, charY + dy, charZ)


      val foundFoodTile = searchTiles.collectFirst {
        case id if tiles.get(id).exists { tile =>
          val items = (tile \ "items").asOpt[JsObject].getOrElse(Json.obj())
          val found = items.values.exists(item => foodIds.contains((item \ "id").as[Int]))
          if (found) println(s"[$taskName] Found food on tile $id")
          found
        } => id
      }

      foundFoodTile match {
        case Some(foodTileId) =>
          println(s"[$taskName] Found food tile: $foodTileId")
          val battleInfo = (json \ "battleInfo").asOpt[Map[String, JsValue]].getOrElse(Map.empty)
          val isBlocked = battleInfo.exists { case (_, creature) =>
            val x = (creature \ "PositionX").asOpt[Int].getOrElse(-1)
            val y = (creature \ "PositionY").asOpt[Int].getOrElse(-1)
            val z = (creature \ "PositionZ").asOpt[Int].getOrElse(-1)
            tileId(x, y, z) == foodTileId
          }
//          println(s"[$taskName] isBlocked=$isBlocked")

          val mapPanelLoc = (json \ "screenInfo" \ "mapPanelLoc").asOpt[Map[String, JsObject]].getOrElse(Map.empty)
          val tileIndexOpt = tiles.get(foodTileId).flatMap(tile => (tile \ "index").asOpt[String])
          val (screenX, screenY) = tileIndexOpt.flatMap(idx =>
            mapPanelLoc.get(idx).flatMap(obj =>
              for {
                x <- (obj \ "x").asOpt[Int]
                y <- (obj \ "y").asOpt[Int]
              } yield (x, y)
            )
          ).getOrElse((0, 0))
          println(s"[$taskName] screenX=$screenX, screenY=$screenY, tileIndexOpt=$tileIndexOpt")

          if (isBlocked) {
            val mouseActions = List(
              MoveMouse(screenX, screenY, metaId),
              RightButtonPress(screenX, screenY, metaId),
              RightButtonRelease(screenX, screenY, metaId)
            )
            val keyboardActions = List(PressCtrl(metaId), HoldCtrlFor(1.second, metaId), ReleaseCtrl(metaId))
            val mkActions = MKActions(mouseActions, keyboardActions)
            val newGeneral = state.general.copy(
              lastActionCommand = Some(taskName),
              lastActionTimestamp = Some(now)
            )
            println(s"[$taskName] EXIT: Blocked, issuing right-click+ctrl on ($screenX,$screenY)")
            Some(state.copy(general = newGeneral) -> MKTask(taskName, mkActions))
          } else {
            val useBtn = (json \ "screenInfo" \ "extraWindowLoc").validate[JsObject].asOpt
              .filter(loc => loc.keys.exists(_ == "Use"))
              .flatMap { loc =>
                (loc \ "Use").validate[JsObject].asOpt.flatMap { okBtn =>
                  for {
                    x <- (okBtn \ "posX").validate[Int].asOpt
                    y <- (okBtn \ "posY").validate[Int].asOpt
                  } yield (x, y)
                }
              }
            println(s"[$taskName] useBtn=$useBtn")
            val (mouseActions, exitMsg) = useBtn match {
              case Some((x, y)) =>
                (
                  List(
                    MoveMouse(x, y, metaId),
                    RightButtonPress(x, y, metaId),
                    RightButtonRelease(x, y, metaId)
                  ),
                  s"EXIT: Clicking Use at ($x,$y)"
                )
              case None =>
                (
                  List(
                    MoveMouse(screenX, screenY, metaId),
                    RightButtonPress(screenX, screenY, metaId),
                    RightButtonRelease(screenX, screenY, metaId)
                  ),
                  s"EXIT: No Use button, right-clicking food at ($screenX,$screenY)"
                )
            }
            val mkActions = MKActions(mouseActions, Nil)
            val newGeneral = state.general.copy(
              lastActionCommand = Some(taskName),
              lastActionTimestamp = Some(now)
            )
            println(s"[$taskName] $exitMsg")
            Some(state.copy(general = newGeneral) -> MKTask(taskName, mkActions))
          }

        case None =>
          println(s"[$taskName] EXIT: No food found on or around player.")
          None
      }
    }
  }


  private object CheckingOpenBackpack extends Step {
    private val taskName = "CheckingOpenBackpack"

    override def run(state: GameState, json: JsValue, settings: UISettings): Option[(GameState, MKTask)] = {
      val gen = state.general

      // Only run once when we haven't yet initialized our container list
      if (gen.areInitialContainerSet || gen.initialContainersList.nonEmpty) {
        None
      } else {
        // Extract the containersInfo object directly
        val containersInfo = (json \ "containersInfo").asOpt[JsObject].getOrElse(Json.obj())

        // Filter keys by container name containing bag, backpack, or ring
        val containerKeys = containersInfo.fields.collect {
          case (key, details: JsObject)
            if (details.value.get("name").exists {
              case JsString(n) =>
                val lower = n.toLowerCase
                lower.contains("bag") || lower.contains("backpack") || lower.contains("ring")
              case _ => false
            }) =>
            key
        }.toList

        // Debug output
//        println(s"[$taskName] Found static containers: $containerKeys")

        // Update general state
        val newGeneral = gen.copy(
          areInitialContainerSet      = true,
          initialContainersList   = containerKeys
        )
        val newState = state.copy(general = newGeneral)

        // No-op task, merely a marker
        Some(newState -> NoOpTask)
      }
    }
  }


  private object SortCarcassesByDistanceAndTime extends Step {
    private val taskName = "sortCarcasses"

    def run(
             state:    GameState,
             json:     JsValue,
             settings: UISettings
           ): Option[(GameState, MKTask)] = {
      // EARLY EXIT: nothing to sort
      if (state.autoLoot.carcassToLootImmediately.isEmpty &&
        state.autoLoot.carcassToLootAfterFight.isEmpty) {
        None
      } else {
        // do your sorting as before
        val sortedImm = sortCarcass(state.autoLoot.carcassToLootImmediately, json)
        val sortedPost = sortCarcass(state.autoLoot.carcassToLootAfterFight, json)
        val updatedState = state.copy(
          autoLoot = state.autoLoot.copy(
            carcassToLootImmediately = sortedImm,
            carcassToLootAfterFight  = sortedPost
          )
        )

        // wrap in an MKTask — no actions, so use MKActions.empty
        Some(updatedState -> MKTask(taskName, MKActions.empty))
      }
    }

    private def sortCarcass(carcassList: List[(String, Long, String)], json: JsValue) = {
      // extract character pos
      val (cx, cy, cz) = (json \ "characterInfo").asOpt[JsObject].map { info =>
        (
          (info \ "PositionX").asOpt[Int].getOrElse(0),
          (info \ "PositionY").asOpt[Int].getOrElse(0),
          (info \ "PositionZ").asOpt[Int].getOrElse(0)
        )
      }.getOrElse((0, 0, 0))

      def extract(tile: String): (Int, Int, Int) = {
        val x = tile.substring(0, 5).toInt
        val y = tile.substring(5, 10).toInt
        val z = tile.substring(10, 12).toInt
        (x, y, z)
      }

      def dist(a: (Int, Int, Int), b: (Int, Int, Int)): Double =
        math.sqrt(
          math.pow(a._1 - b._1, 2) +
            math.pow(a._2 - b._2, 2) +
            math.pow(a._3 - b._3, 2)
        )

      carcassList.sortBy { case (tile, time, id) =>
        val pt = extract(tile)
        (dist(pt, (cx, cy, cz)), time)
      }
    }
  }






}
