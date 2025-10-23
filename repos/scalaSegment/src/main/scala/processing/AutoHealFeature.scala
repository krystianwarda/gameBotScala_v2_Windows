package processing

import cats.effect.{IO, Ref}
import keyboard.{GeneralKey, KeyboardAction, KeyboardUtils, TextType}
import mouse._
import play.api.libs.json.{JsObject, JsValue}
import processing.Process.generateRandomDelay
import utils.{AutoHealState, GameState, StaticGameInfo}
import utils.SettingsUtils.{HealingSettings, UISettings}
import cats.syntax.all._
//import processing.Process.{findItemInContainerSlot14, generateRandomDelay}
import utils.ProcessingUtils._

object AutoHealFeature {

  def run(json: JsValue, settings: UISettings, state: GameState):
  (GameState, List[MKTask]) =
    if (!settings.healingSettings.enabled) (state, Nil)
    else {
      val (s, maybeTask) = Steps.runFirst(json, settings, state)
      (s, maybeTask.toList)
    }

  private object Steps {
    // ordered list of steps
    val allSteps: List[Step] = List(
      SetUpHealingSupplies,
      CheckHealingSupplies,
      HandleHealingBackpacks,
      CheckDynamicHealing,
      RingsHandling,
      UpdateAutoHealState,
//      DangerLevelHealing,
      EngageHeal,
      PrepareHealing,
//      SelfHealing,
      TeamHealing
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


  private object RingsHandling extends Step {
    private val taskName = "RingsHandling"

    def run(
             state: GameState,
             json: JsValue,
             settings: UISettings
           ): Option[(GameState, MKTask)] = {

      val ah = state.autoHeal
      val now = System.currentTimeMillis()
      val timeSinceLastAction = now - ah.lastRingAmuletActionTime

      println(s"[$taskName] ENTRY - Checking rings handling")


      println(s"[$taskName] Life ring is enabled, proceeding with checks")

      // Check throttle
      if (timeSinceLastAction < ah.ringAmuletThrottle) {
        println(s"[$taskName] Throttle active - waiting (timeSince: ${timeSinceLastAction}ms, throttle: ${ah.ringAmuletThrottle}ms)")
        return None
      }

      println(s"[$taskName] Throttle check passed: timeSinceLastAction=${timeSinceLastAction}, throttle=${ah.ringAmuletThrottle}")

      val currentMana = (json \ "characterInfo" \ "Mana").asOpt[Int].getOrElse(0)

      println(s"[$taskName] Current Mana: $currentMana")

      // Get current ring info from slot9
      val slot9Info = (json \ "EqInfo" \ "9").asOpt[JsObject]
      val currentRingItemId = slot9Info.flatMap(s => (s \ "itemId").asOpt[Int])
      val isSlot9Empty = currentRingItemId.isEmpty ||
        slot9Info.exists(s => (s \ "itemName").asOpt[String].contains("Empty Slot"))

      println(s"[$taskName] Current ring in slot9: ${if (isSlot9Empty) "empty" else currentRingItemId.getOrElse("unknown")}")

      // Determine what action to take
      val shouldPutOnRing = isSlot9Empty && currentMana < settings.healingSettings.lifeRingManaMin
      val shouldTakeOffRing = !isSlot9Empty &&
        currentRingItemId.contains(3089) &&
        currentMana >= settings.healingSettings.lifeRingManaMax

      println(s"[$taskName] shouldPutOnRing: $shouldPutOnRing (empty slot && MP < ${settings.healingSettings.lifeRingManaMin})")
      println(s"[$taskName] shouldTakeOffRing: $shouldTakeOffRing ( $isSlot9Empty && MP >= ${settings.healingSettings.lifeRingManaMax})")

      if (shouldPutOnRing) {
        println(s"[$taskName] Attempting to put on ring")
        handlePutOnRing(state, json, "3052", now)
      } else if (shouldTakeOffRing) {
        println(s"[$taskName] Attempting to take off ring")
        val metaId = (json \ "metaGeneratedId").asOpt[String].getOrElse("null")
        handleTakeOffRing(state, json, 3089, metaId, now)
      } else {
        println(s"[$taskName] EXIT - No ring action needed")
        None
      }
    }

    private def handlePutOnRing(
                                 state: GameState,
                                 json: JsValue,
                                 metaId: String,
                                 now: Long
                               ): Option[(GameState, MKTask)] = {

      println(s"[$taskName] handlePutOnRing - START")
      val ah = state.autoHeal

      // Update ringBackpack if container changed
      val updatedRingBackpack = updateRingBackpackIfNeeded(json, ah.ringBackpack, 3052)
      println(s"[$taskName] handlePutOnRing - ringBackpack: ${ah.ringBackpack} -> $updatedRingBackpack")

      // Search for ring in the ringBackpack only
      val ringPosOpt = findRingInBackpack(json, updatedRingBackpack, 3052)
      println(s"[$taskName] handlePutOnRing - ring position: $ringPosOpt")

      ringPosOpt match {
        case Some((rx, ry)) =>
          println(s"[$taskName] handlePutOnRing - Found ring at ($rx, $ry)")

          // Get slot9 screen position
          val slot9PosOpt = for {
            screenInfo <- (json \ "screenInfo").asOpt[JsObject]
            inventoryPanelLoc <- (screenInfo \ "inventoryPanelLoc").asOpt[JsObject]
            inventoryWindow <- (inventoryPanelLoc \ "inventoryWindow").asOpt[JsObject]
            contentsPanel <- (inventoryWindow \ "contentsPanel").asOpt[JsObject]
            slot9 <- (contentsPanel \ "slot9").asOpt[JsObject]
            x <- (slot9 \ "x").asOpt[Int]
            y <- (slot9 \ "y").asOpt[Int]
          } yield (x, y)

          println(s"[$taskName] handlePutOnRing - slot9 position: $slot9PosOpt")

          slot9PosOpt match {
            case Some((sx, sy)) =>
              println(s"[$taskName] handlePutOnRing - Creating mouse actions: drag from ($rx,$ry) to ($sx,$sy)")
              val mouseActions = List(
                MoveMouse(rx, ry, metaId),
                LeftButtonPress(rx, ry, metaId),
                MoveMouse(sx, sy, metaId),
                LeftButtonRelease(sx, sy, metaId)
              )

              val updatedAh = ah.copy(
                ringBackpack = updatedRingBackpack,
                lastRingAmuletActionTime = now,
                ringAmuletThrottle = ah.ringAmuletOnThrottle
              )

              val updatedState = state.copy(autoHeal = updatedAh)
              println(s"[$taskName] handlePutOnRing - SUCCESS, setting throttle to ${ah.ringAmuletOnThrottle}ms")
              Some(updatedState -> MKTask(s"$taskName - put_on_ring", MKActions(mouseActions, Nil)))

            case None =>
              println(s"[$taskName] handlePutOnRing - ERROR: Could not find slot9 screen position")
              None
          }

        case None =>
          println(s"[$taskName] handlePutOnRing - ERROR: Ring 3052 not found in backpack $updatedRingBackpack")
          val updatedAh = ah.copy(ringBackpack = updatedRingBackpack)
          val updatedState = state.copy(autoHeal = updatedAh)
          Some(updatedState -> MKTask(taskName, MKActions.empty))
      }
    }

    private def handleTakeOffRing(
                                   state: GameState,
                                   json: JsValue,
                                   ringId: Int,
                                   metaId: String,
                                   now: Long
                                 ): Option[(GameState, MKTask)] = {

      println(s"[$taskName] handleTakeOffRing - START (ringId: $ringId)")
      val ah = state.autoHeal

      // Update ringBackpack if container changed
      val updatedRingBackpack = updateRingBackpackIfNeeded(json, ah.ringBackpack, ringId)
      println(s"[$taskName] handleTakeOffRing - ringBackpack: ${ah.ringBackpack} -> $updatedRingBackpack")

      // Get slot9 screen position
      val slot9PosOpt = for {
        screenInfo <- (json \ "screenInfo").asOpt[JsObject]
        inventoryPanelLoc <- (screenInfo \ "inventoryPanelLoc").asOpt[JsObject]
        inventoryWindow <- (inventoryPanelLoc \ "inventoryWindow").asOpt[JsObject]
        contentsPanel <- (inventoryWindow \ "contentsPanel").asOpt[JsObject]
        slot9 <- (contentsPanel \ "slot9").asOpt[JsObject]
        x <- (slot9 \ "x").asOpt[Int]
        y <- (slot9 \ "y").asOpt[Int]
      } yield (x, y)

      println(s"[$taskName] handleTakeOffRing - slot9 position: $slot9PosOpt")

      slot9PosOpt match {
        case Some((sx, sy)) =>
          // Get target position in ringBackpack (item0)
          val backpackPosOpt = for {
            invPanel <- (json \ "screenInfo" \ "inventoryPanelLoc").asOpt[JsObject]
            panelKey <- invPanel.keys.find(_.contains(updatedRingBackpack))
            item0 <- (invPanel \ panelKey \ "contentsPanel" \ "item0").asOpt[JsObject]
            x <- (item0 \ "x").asOpt[Int]
            y <- (item0 \ "y").asOpt[Int]
          } yield (x, y)



          println(s"[$taskName] handleTakeOffRing - backpack position: $backpackPosOpt")

          backpackPosOpt match {
            case Some((bx, by)) =>
              println(s"[$taskName] handleTakeOffRing - Creating mouse actions: drag from ($sx,$sy) to ($bx,$by)")
              val mouseActions = List(
                MoveMouse(sx, sy, metaId),
                LeftButtonPress(sx, sy, metaId),
                MoveMouse(bx, by, metaId),
                LeftButtonRelease(bx, by, metaId)
              )

              val updatedAh = ah.copy(
                ringBackpack = updatedRingBackpack,
                lastRingAmuletActionTime = now,
                ringAmuletThrottle = ah.ringAmuletOffThrottle
              )

              val updatedState = state.copy(autoHeal = updatedAh)
              println(s"[$taskName] handleTakeOffRing - SUCCESS, setting throttle to ${ah.ringAmuletOffThrottle}ms")
              Some(updatedState -> MKTask(s"$taskName - take_off_ring", MKActions(mouseActions, Nil)))

            case None =>
              println(s"[$taskName] handleTakeOffRing - ERROR: Could not find backpack position for $updatedRingBackpack")
              None
          }

        case None =>
          println(s"[$taskName] handleTakeOffRing - ERROR: Could not find slot9 screen position")
          None
      }
    }

    private def updateRingBackpackIfNeeded(
                                            json: JsValue,
                                            currentBackpack: String,
                                            ringItemId: Int
                                          ): String = {
      println(s"[$taskName] updateRingBackpackIfNeeded - currentBackpack: $currentBackpack, ringItemId: $ringItemId")

      // Find which container has the ring
      val containersOpt = (json \ "containersInfo").asOpt[JsObject]

      val result = containersOpt.flatMap { containers =>
        println(s"[$taskName] updateRingBackpackIfNeeded - checking ${containers.fields.size} containers")
        containers.fields.collectFirst {
          case (containerName, containerData)
            if (containerData \ "items").asOpt[JsObject]
              .exists(_.values.exists(item => (item \ "itemId").asOpt[Int].contains(ringItemId)))
          =>
            println(s"[$taskName] updateRingBackpackIfNeeded - Found ring in container: $containerName")
            containerName
        }
      }.getOrElse {
        println(s"[$taskName] updateRingBackpackIfNeeded - Ring not found, keeping current: $currentBackpack")
        currentBackpack
      }

      result
    }

    private def findRingInBackpack(
                                    json: JsValue,
                                    backpackName: String,
                                    ringItemId: Int
                                  ): Option[(Int, Int)] = {

      println(s"[$taskName] findRingInBackpack - backpackName: $backpackName, ringItemId: $ringItemId")

      val result = for {
        containers <- (json \ "containersInfo").asOpt[JsObject]
        container <- (containers \ backpackName).asOpt[JsObject]
        items <- (container \ "items").asOpt[JsObject]
        _ = println(s"[$taskName] findRingInBackpack - Found container with ${items.fields.size} items")
        slotIndex <- (0 until 20).find { idx =>
          val hasRing = (items \ s"slot$idx" \ "itemId").asOpt[Int].contains(ringItemId)
          if (hasRing) println(s"[$taskName] findRingInBackpack - Found ring in slot$idx")
          hasRing
        }
        invPanel <- (json \ "screenInfo" \ "inventoryPanelLoc").asOpt[JsObject]
        panelKey <- invPanel.keys.find(_.contains(backpackName))
        _ = println(s"[$taskName] findRingInBackpack - Found panel key: $panelKey")
        contentsPanel <- (invPanel \ panelKey \ "contentsPanel").asOpt[JsObject]
        itemObj <- (contentsPanel \ s"item$slotIndex").asOpt[JsObject]
        x <- (itemObj \ "x").asOpt[Int]
        y <- (itemObj \ "y").asOpt[Int]
      } yield {
        println(s"[$taskName] findRingInBackpack - Final position: ($x, $y)")
        (x, y)
      }

      if (result.isEmpty) {
        println(s"[$taskName] findRingInBackpack - ERROR: Could not find ring position")
      }

      result
    }
  }


  private object EngageHeal extends Step {
    private val taskName = "EngageHeal"

    def run(
             state: GameState,
             json: JsValue,
             settings: UISettings
           ): Option[(GameState, MKTask)] = {

      val ah = state.autoHeal
      val currentTime = System.currentTimeMillis()

      // Only run when status is "heal"
      if (ah.statusOfAutoheal != "heal") {
        return None
      }

      println(s"[$taskName] Running with healMethod: ${ah.healMethod}")

      ah.healMethod match {
        case Some((healType, (itemId, _))) =>
          healType match {
            case "rune" | "fluid" =>
              executeRuneOrFluid(state, json, currentTime)
            case "spell" =>
              executeSpell(state, json, settings, itemId, currentTime)
            case unknownType =>
              println(s"[$taskName] Unknown heal type: '$unknownType' - resetting to ready")
              resetToReady(state)
          }

        case None =>
          println(s"[$taskName] No healMethod specified - resetting to ready")
          resetToReady(state)
      }
    }

    private def executeRuneOrFluid(
                                    state: GameState,
                                    json: JsValue,
                                    currentTime: Long
                                  ): Option[(GameState, MKTask)] = {

      val ah = state.autoHeal
      val metaId: String = (json \ "metaGeneratedId").asOpt[String].getOrElse("null")
      // Check if crosshair is active
      val isActiveFromJson = (json \ "characterInfo" \ "IsCrosshairActive")
        .asOpt[Boolean].getOrElse(false)

      if (!isActiveFromJson) {
        // Check if we've been waiting too long (similar to attack code's 1500ms timeout)
        val waitTime = currentTime - ah.lastHealUseTime
        if (waitTime > 1500) {
          println(s"[$taskName] Crosshair timeout after ${waitTime}ms - resetting to ready")
          return resetToReady(state)
        } else {
          println(s"[$taskName] Waiting for crosshair to appear (${waitTime}ms)")
          return Some(state -> MKTask(taskName, MKActions.empty))
        }
      }

      // Get character position using map panel location
      val characterPositionOpt = for {
        mpObj <- (json \ "screenInfo" \ "mapPanelLoc" \ "8x6").asOpt[JsObject]
        tx <- (mpObj \ "x").asOpt[Int]
        ty <- (mpObj \ "y").asOpt[Int]
      } yield (tx, ty)

      characterPositionOpt match {
        case Some((charScreenX, charScreenY)) =>
          println(s"[$taskName] Executing rune/fluid healing at character position: x=$charScreenX, y=$charScreenY")

          // Left-click on character position to use the item
          val mouseActions = List(
            MoveMouse(charScreenX, charScreenY, metaId),
            LeftButtonPress(charScreenX, charScreenY, metaId),
            LeftButtonRelease(charScreenX, charScreenY, metaId)
          )

          // Reset status and clear healMethod after successful execution
          val updatedState = state.copy(
            autoHeal = ah.copy(
              statusOfAutoheal = "ready",
              healMethod = None,
              lastHealUseTime = currentTime
            )
          )

          println(s"[$taskName] Executed rune/fluid healing, resetting to ready")
          Some(updatedState -> MKTask(s"$taskName - rune_fluid", MKActions(mouseActions, Nil)))

        case None =>
          println(s"[$taskName] Could not determine character screen position from map panel")
          resetToReady(state)
      }
    }

    private def executeSpell(
                              state: GameState,
                              json: JsValue,
                              settings: UISettings,
                              spell: String,
                              currentTime: Long
                            ): Option[(GameState, MKTask)] = {

      val ah = state.autoHeal
      val metaId: String = (json \ "metaGeneratedId").asOpt[String].getOrElse("null")
      // Check settings to determine whether to use hotkey or text typing
      val keyboardAction = settings.healingSettings.spellsHealSettings.headOption match {
        case Some(cfg) =>
          // Check if this is a strong heal spell
          if (cfg.strongHealSpell == spell && cfg.strongHealHotkeyEnabled) {
            GeneralKey(KeyboardUtils.fromHotkeyString(cfg.strongHealHotkey), metaId)
          }
          // Check if this is a light heal spell
          else if (cfg.lightHealSpell == spell && cfg.lightHealHotkeyEnabled) {
            GeneralKey(KeyboardUtils.fromHotkeyString(cfg.lightHealHotkey), metaId)
          }
          // Default to text typing
          else {
            TextType(spell, metaId)
          }
        case None =>
          TextType(spell, metaId)
      }

      val keyboardActions = List(keyboardAction)

      // Reset status and clear healMethod after successful execution
      val updatedState = state.copy(
        autoHeal = ah.copy(
          statusOfAutoheal = "ready",
          healMethod = None,
          lastHealUseTime = currentTime
        )
      )

      println(s"[$taskName] Executed spell healing: $spell, resetting to ready")
      Some(updatedState -> MKTask(s"$taskName - spell", MKActions(Nil, keyboardActions)))
    }

    private def resetToReady(state: GameState): Option[(GameState, MKTask)] = {
      val updatedState = state.copy(
        autoHeal = state.autoHeal.copy(
          statusOfAutoheal = "ready",
          healMethod = None
        )
      )
      Some(updatedState -> MKTask(taskName, MKActions.empty))
    }
  }

  private object PrepareHealing extends Step {
    private val taskName = "PrepareHealing"

    def run(
             state: GameState,
             json: JsValue,
             settings: UISettings
           ): Option[(GameState, MKTask)] = {

      val ah = state.autoHeal
      val currentTime = System.currentTimeMillis()

      // Only run when status is "prepare_heal"
      if (ah.statusOfAutoheal != "prepare_heal") {
        return None
      }

      println(s"[$taskName] Running with healMethod: ${ah.healMethod}")

      ah.healMethod match {
        case Some((healType, (itemId, _))) =>
          healType match {
            case "rune" | "fluid" =>
              prepareRuneOrFluid(state, json, itemId, currentTime)
            case unknownType =>
              println(s"[$taskName] Unknown heal type: '$unknownType' - resetting to ready")
              val updatedState = state.copy(
                autoHeal = ah.copy(statusOfAutoheal = "ready")
              )
              Some(updatedState -> MKTask(taskName, MKActions.empty))
          }

        case None =>
          println(s"[$taskName] No healMethod specified - resetting to ready")
          val updatedState = state.copy(
            autoHeal = ah.copy(statusOfAutoheal = "ready")
          )
          Some(updatedState -> MKTask(taskName, MKActions.empty))
      }
    }

    private def prepareRuneOrFluid(
                                    state: GameState,
                                    json: JsValue,
                                    itemId: String,
                                    currentTime: Long
                                  ): Option[(GameState, MKTask)] = {

      val ah = state.autoHeal
      val metaId: String = (json \ "metaGeneratedId").asOpt[String].getOrElse("null")
      println(s"[$taskName] prepareRuneOrFluid: itemId=$itemId")

      // Get character position using map panel location
      val characterPositionOpt = for {
        mpObj <- (json \ "screenInfo" \ "mapPanelLoc" \ "8x6").asOpt[JsObject]
        tx <- (mpObj \ "x").asOpt[Int]
        ty <- (mpObj \ "y").asOpt[Int]
      } yield (tx, ty)

      characterPositionOpt match {
        case Some((charScreenX, charScreenY)) =>
          println(s"[$taskName] Character screen position: x=$charScreenX, y=$charScreenY")

          // Find the container for this item
          val containerName = ah.autoHealingContainerMapping.get(itemId)

          containerName match {
            case Some(contName) =>
              println(s"[$taskName] Looking for item $itemId in container: $contName")

              val itemCoords = for {
                invPanel <- (json \ "screenInfo" \ "inventoryPanelLoc").asOpt[JsObject]
                runeKey <- invPanel.keys.find(_.contains(contName))
                item <- (invPanel \ runeKey \ "contentsPanel" \ "item0").asOpt[JsObject]
                rx <- (item \ "x").asOpt[Int]
                ry <- (item \ "y").asOpt[Int]
              } yield (rx, ry)

              itemCoords match {
                case Some((rx, ry)) =>
                  println(s"[$taskName] Found item at coordinates: x=$rx, y=$ry")

                  // Right-click item and move to character position
                  val mouseActions = List(
                    MoveMouse(rx, ry, metaId),
                    RightButtonPress(rx, ry, metaId),
                    RightButtonRelease(rx, ry, metaId),
                    MoveMouse(charScreenX, charScreenY, metaId)
                  )

                  // Transition to "heal" status - crosshair preparation complete
                  val updatedState = state.copy(
                    autoHeal = ah.copy(statusOfAutoheal = "heal")
                  )

                  println(s"[$taskName] Prepared healing item, transitioning to heal status")
                  Some(updatedState -> MKTask(s"$taskName - prepare_$itemId", MKActions(mouseActions, Nil)))

                case None =>
                  println(s"[$taskName] Could not find item $itemId in container $contName")
                  val updatedState = state.copy(
                    autoHeal = ah.copy(statusOfAutoheal = "ready")
                  )
                  Some(updatedState -> MKTask(taskName, MKActions.empty))
              }

            case None =>
              println(s"[$taskName] No container found for item $itemId")
              val updatedState = state.copy(
                autoHeal = ah.copy(statusOfAutoheal = "ready")
              )
              Some(updatedState -> MKTask(taskName, MKActions.empty))
          }

        case None =>
          println(s"[$taskName] Could not determine character screen position from map panel")
          val updatedState = state.copy(
            autoHeal = ah.copy(statusOfAutoheal = "ready")
          )
          Some(updatedState -> MKTask(taskName, MKActions.empty))
      }
    }
  }


  private object UpdateAutoHealState extends Step {
    private val taskName = "UpdateAutoHealState"

    def run(
             state: GameState,
             json: JsValue,
             settings: UISettings
           ): Option[(GameState, MKTask)] = {

      val ah = state.autoHeal
      val currentStatus = ah.statusOfAutoheal
      val dangerLevelHealing = ah.dangerLevelHealing
      val healthPercent = (json \ "characterInfo" \ "HealthPercent").asOpt[Int].getOrElse(100)
      val mana = (json \ "characterInfo" \ "Mana").asOpt[Int].getOrElse(0)
      val now  = System.currentTimeMillis()

      if ((now - ah.lastHealUseTime) < (ah.healingUseCooldown + ah.healUseRandomness) ) {
        return None
      }

      println(s"[$taskName] ENTRY - currentStatus: '$currentStatus', dangerLevelHealing: $dangerLevelHealing, HP: $healthPercent%, mana: $mana")
      println(s"[$taskName] autoHealingContainerMapping: ${ah.autoHealingContainerMapping}")
      println(s"[$taskName] healingSettings enabled: ${settings.healingSettings.enabled}")

      // Print healing settings details
      println(s"[$taskName] healingSettings details:")
      println(s"  - uhHealHealthPercent: ${settings.healingSettings.uhHealHealthPercent}")
      println(s"  - uhHealHealthPercent: ${settings.healingSettings.uhHealHealthPercent}")
      println(s"  - hotkeyMode: ${settings.hotkeyMode}")
      settings.healingSettings.spellsHealSettings.headOption.foreach { spellCfg =>
        println(s"  - strongHealSpell: '${spellCfg.strongHealSpell}', HP: ${spellCfg.strongHealHealthPercent}, mana: ${spellCfg.strongHealMana}")
        println(s"  - lightHealSpell: '${spellCfg.lightHealSpell}', HP: ${spellCfg.lightHealHealthPercent}, mana: ${spellCfg.lightHealMana}")
      }

      println(s"[$taskName] Pre check determineHotkeyHealing")
      if (settings.hotkeyMode) {
        println(s"[$taskName] check determineHotkeyHealing")
        determineHotkeyHealing(healthPercent, mana, state, json, settings)

      } else {
        // Skip if handling backpack operations
        if (currentStatus == "remove_backpack" || currentStatus == "open_new_backpack" || currentStatus == "heal" || currentStatus == "prepare_heal") {
          println(s"[$taskName] EXIT - Skipping due to backpack operations (status: $currentStatus)")
          return None
        }

        // Handle danger state transitions
        val newStatus = currentStatus match {
          case "ready" if dangerLevelHealing =>
            println(s"[$taskName] Transitioning from ready to danger")
            "danger"

          case "danger" if !dangerLevelHealing =>
            println(s"[$taskName] Leaving danger state, checking healing needs")
            determineHealingStatus(healthPercent, mana, settings.healingSettings, ah.autoHealingContainerMapping)._1

          case "ready" =>
            println(s"[$taskName] Checking if healing is needed")
            determineHealingStatus(healthPercent, mana, settings.healingSettings, ah.autoHealingContainerMapping)._1

          case _ =>
            println(s"[$taskName] Determining healing action for current state")
            determineHealingStatus(healthPercent, mana, settings.healingSettings, ah.autoHealingContainerMapping)._1
        }

        if (newStatus != currentStatus) {
          val (finalStatus, healMethod) = if (newStatus != "danger") {
            println(s"[$taskName] Determining final healing status and method")
            val result = determineHealingStatus(healthPercent, mana, settings.healingSettings, ah.autoHealingContainerMapping)
            println(s"[$taskName] Final determination result: status='${result._1}', method=${result._2}")
            result
          } else {
            println(s"[$taskName] Using danger status, keeping existing healMethod: ${ah.healMethod}")
            (newStatus, ah.healMethod)
          }

          val updatedAH = ah.copy(
            statusOfAutoheal = finalStatus,
            healMethod = healMethod
          )
          val updatedState = state.copy(autoHeal = updatedAH)

          println(s"[$taskName] EXIT - Status changed: $currentStatus → $finalStatus, healMethod: $healMethod")
          Some(updatedState -> MKTask(taskName, MKActions.empty))
        } else {
          println(s"[$taskName] EXIT - No status change needed (status remains: $currentStatus)")
          None
        }
      }

    }

    private def determineHotkeyHealing(
                                        hpPercent: Int,
                                        mana: Int,
                                        state: GameState,
                                        json: JsValue,
                                        settings: UISettings
                                      ): Option[(GameState, MKTask)] = {

      val taskName    = "UpdateAutoHealState"
      val currentTime = System.currentTimeMillis()
      val ah          = state.autoHeal
      val hs          = settings.healingSettings
      val metaId: String = (json \ "metaGeneratedId").asOpt[String].getOrElse("null")
      val hotkeysObjOpt = (json \ "hotkeysBinds").asOpt[JsObject]

      def preferSingleKey(keys: List[String]): Option[String] =
        keys.sortBy(k => if (k.contains("+")) 1 else 0).headOption

      def hotkeysForItem(itemId: Int, requiredUseType: Option[Int]): List[String] = {
        hotkeysObjOpt.toList.flatMap { obj =>
          obj.fields.collect {
            case (k, v) if k.endsWith("_itemId") && v.asOpt[Int].contains(itemId) =>
              val base = k.stripSuffix("_itemId")
              val ok   = requiredUseType.forall { rt =>
                (obj \ s"${base}_useType").asOpt[Int].contains(rt)
              }
              if (ok) Some(base) else None
          }.flatten
        }
      }

      def hotkeyForSpell(spell: String): Option[String] =
        hotkeysObjOpt.flatMap(_.fields.collectFirst {
          case (k, v) if k.endsWith("_value") && v.asOpt[String].exists(_.equalsIgnoreCase(spell)) =>
            k.stripSuffix("_value")
        })

      def buildHotkeyActions(hotkey: String, metaId: String): List[KeyboardAction] = {
        val parts = hotkey.split("\\+").toList
        val base  = parts.lastOption.getOrElse(hotkey)
        val mods  = parts.dropRight(1).map(_.toLowerCase)

        val pressMods: List[KeyboardAction] = mods.flatMap {
          case "shift" => Some(keyboard.PressShift(metaId))
          case _       => None
        }

        val baseAction: KeyboardAction = GeneralKey(KeyboardUtils.fromHotkeyString(base), metaId)

        val releaseMods: List[KeyboardAction] = mods.reverse.flatMap {
          case "shift" => Some(keyboard.ReleaseShift(metaId))
          case _       => None
        }

        pressMods ++ List(baseAction) ++ releaseMods
      }

      // Stop MP boost if max reached
      if (ah.mpBoost && hs.mPotionHealManaMax > 0 && mana >= hs.mPotionHealManaMax) {
        val updatedState = state.copy(autoHeal = ah.copy(mpBoost = false))
        return Some(updatedState -> MKTask(taskName, MKActions.empty))
      }

      sealed trait HealChoice
      case class RuneChoice(itemId: Int, requiredUseType: Option[Int]) extends HealChoice
      case class SpellChoice(spell: String, isStrong: Boolean) extends HealChoice
      case object MPPotionChoice extends HealChoice

      val mpShouldStart    = hs.mPotionHealManaMin > 0 && mana <= hs.mPotionHealManaMin
      val mpShouldContinue = ah.mpBoost && hs.mPotionHealManaMax > 0 && mana < hs.mPotionHealManaMax

      val choiceOpt: Option[HealChoice] = {
        // UH rune (useType 1)
        if (hs.uhHealHealthPercent > 0 && hpPercent <= hs.uhHealHealthPercent && mana >= hs.uhHealMana)
          Some(RuneChoice(3160, requiredUseType = Some(1)))
        // IH rune (no useType restriction)
        else if (hs.ihHealHealthPercent > 0 && hpPercent <= hs.ihHealHealthPercent && mana >= hs.ihHealMana)
          Some(RuneChoice(3152, requiredUseType = None))
        // HP potion via hotkey (if you use 2874 for HP, keep here; otherwise remove)
        else if (hs.hPotionHealHealthPercent > 0 && hpPercent <= hs.hPotionHealHealthPercent && mana >= hs.hPotionHealMana)
          Some(RuneChoice(2874, requiredUseType = Some(1)))
        // MP potion boost via hotkey (2874, useType 1)
        else if (mpShouldStart || mpShouldContinue)
          Some(MPPotionChoice)
        else {
          hs.spellsHealSettings.headOption.flatMap { cfg =>
            if (cfg.strongHealSpell.nonEmpty && hpPercent <= cfg.strongHealHealthPercent && mana >= cfg.strongHealMana)
              Some(SpellChoice(cfg.strongHealSpell, isStrong = true))
            else if (cfg.lightHealSpell.length > 1 && hpPercent <= cfg.lightHealHealthPercent && mana >= cfg.lightHealMana)
              Some(SpellChoice(cfg.lightHealSpell, isStrong = false))
            else None
          }
        }
      }

      choiceOpt.flatMap {
        case RuneChoice(itemId, requiredUseType) =>
          val keys = hotkeysForItem(itemId, requiredUseType)
          preferSingleKey(keys).map { keyStr =>
            val keyboardActions = buildHotkeyActions(keyStr, metaId)
            val updatedState = state.copy(autoHeal = ah.copy(lastHealUseTime = currentTime))
            updatedState -> MKTask(s"$taskName - hotkey_item_$itemId", MKActions(Nil, keyboardActions))
          }

        case MPPotionChoice =>
          val keys = hotkeysForItem(2874, requiredUseType = Some(1)) // F10\_itemId=2874, F10\_useType=1
          preferSingleKey(keys).map { keyStr =>
            val keyboardActions = buildHotkeyActions(keyStr, metaId)
            val updatedState = state.copy(autoHeal = ah.copy(
              lastHealUseTime = currentTime,
              mpBoost = true,
              statusOfAutoheal = "ready",
              healMethod = None
            ))
            updatedState -> MKTask(s"$taskName - mp_potion", MKActions(Nil, keyboardActions))
          }

        case SpellChoice(spell, isStrong) =>
          val cfgOpt = hs.spellsHealSettings.headOption
          val explicitHotkeyOpt =
            cfgOpt.flatMap { cfg =>
              if (isStrong && cfg.strongHealHotkeyEnabled) Some(cfg.strongHealHotkey)
              else if (!isStrong && cfg.lightHealHotkeyEnabled) Some(cfg.lightHealHotkey)
              else None
            }

          val keyboardActions: List[KeyboardAction] =
            hotkeyForSpell(spell)
              .orElse(explicitHotkeyOpt)
              .map(h => buildHotkeyActions(h, metaId))
              .getOrElse(List(TextType(spell, metaId)))


          val updatedState = state.copy(autoHeal = ah.copy(
            lastHealUseTime = currentTime,
            healMethod = None,
            statusOfAutoheal = "ready"
          ))
          Some(updatedState -> MKTask(s"$taskName - spell", MKActions(Nil, keyboardActions)))
      }
    }

    private def determineHealingStatus(currentHp: Int, currentMana: Int, settings: HealingSettings,
                                       autoHealingContainerMapping: Map[String, String]): (String, Option[(String, (String, String))]) = {
      if (!settings.enabled) return ("ready", None)

      val hpPercent = currentHp
      val mana = currentMana

      // Check UH rune first (highest priority)
      if (autoHealingContainerMapping.contains("3160") &&
        settings.uhHealHealthPercent > 0 &&
        hpPercent <= settings.uhHealHealthPercent &&
        mana >= settings.uhHealMana) {
        return ("prepare_heal", Some(("rune", ("3160", autoHealingContainerMapping("3160")))))
      }


      // Check IH rune second
      if (autoHealingContainerMapping.contains("3152") &&
        settings.ihHealHealthPercent > 0 &&
        hpPercent <= settings.ihHealHealthPercent &&
        mana >= settings.ihHealMana) {
        return ("prepare_heal", Some(("rune", ("3152", autoHealingContainerMapping("3152")))))
      }

      // Check HP potion third
      if (autoHealingContainerMapping.contains("2874") &&
        settings.hPotionHealHealthPercent > 0 &&
        hpPercent <= settings.hPotionHealHealthPercent &&
        mana >= settings.hPotionHealMana) {
        return ("prepare_heal", Some(("rune", ("2874", autoHealingContainerMapping("2874")))))
      }

      // Check strong heal spell fourth
      if (settings.spellsHealSettings.headOption.exists(cfg =>
        cfg.strongHealSpell.nonEmpty &&
          cfg.strongHealHealthPercent > 0 &&
          hpPercent <= cfg.strongHealHealthPercent &&
          mana >= cfg.strongHealMana)) {
        val strongSpell = settings.spellsHealSettings.head.strongHealSpell
        return ("heal", Some(("spell", (strongSpell, "0"))))
      }

      // Check light heal spell last (lowest priority)
      if (settings.spellsHealSettings.headOption.exists(cfg =>
        cfg.lightHealSpell.length > 1 &&
          cfg.lightHealHealthPercent > 0 &&
          hpPercent <= cfg.lightHealHealthPercent &&
          mana >= cfg.lightHealMana)) {
        val lightSpell = settings.spellsHealSettings.head.lightHealSpell
        return ("heal", Some(("spell", (lightSpell, "0"))))
      }

      ("ready", None)
    }
  }

  private object CheckDynamicHealing extends Step {
    private val taskName = "checkDynamicHealing"

    def run(
             state:    GameState,
             json:     JsValue,
             settings: UISettings
           ): Option[(GameState, MKTask)] = {

      if (settings.hotkeyMode) {
        // Dynamic healing not supported in hotkey mode
        return None
      }
      // 1) parse the UI-list into (name, countThreshold, dangerLevel)
      case class Cfg(name: String, count: Int, danger: Int)
      def parse(line: String): Option[Cfg] = {
        val pat = """Name:\s*([^,]+),\s*Count:\s*(\d+).*?Danger:\s*(\d+),.*""".r
        line match {
          case pat(n, c, d) => Some(Cfg(n.trim, c.toInt, d.toInt))
          case _            => None
        }
      }

      val thresholds = settings.autoTargetSettings.creatureList
        .flatMap(parse)
        .filter(_.danger >= 5)

      // EARLY EXIT: nothing to check if no high‐danger creatures configured
      if (thresholds.isEmpty) return None

      // 2) tally how many of each are in battle
      val counts: Map[String, Int] =
        (json \ "battleInfo").asOpt[JsObject]
          .map(_.value.values.toSeq)
          .getOrElse(Seq.empty)
          .flatMap(cre => (cre \ "Name").asOpt[String])
          .groupBy(identity)
          .view.mapValues(_.size)
          .toMap

      // 3) decide whether we *should* be in danger mode
      val shouldBeOn = thresholds.exists { cfg =>
        val seen = counts.getOrElse(cfg.name, 0)
        if (cfg.count == 0) seen >= 1 else seen >= cfg.count
      }

      (state.autoHeal.dangerLevelHealing, shouldBeOn) match {
        case (false, true) =>
          println("[CheckDynamicHealing] → ARM danger‐healing")
          val updatedState = state.copy(
            autoHeal = state.autoHeal.copy(dangerLevelHealing = true)
          )
          Some(updatedState -> MKTask(taskName, MKActions.empty))

        case (true, false) =>
          println("[CheckDynamicHealing] → DISARM danger‐healing")
          val updatedState = state.copy(
            autoHeal = state.autoHeal.copy(dangerLevelHealing = false)
          )
          Some(updatedState -> MKTask(taskName, MKActions.empty))

        case _ =>
          None
      }
    }
  }

  private object CheckHealingSupplies extends Step {
    private val taskName = "CheckSupplies"
    def run(
             state:    GameState,
             json:     JsValue,
             settings: UISettings
           ): Option[(GameState, MKTask)] = {
      val ah     = state.autoHeal
      val status = ah.statusOfAutoheal
      if (status != "ready") return None

      // Check all containers from the mapping
      val containerMapping = ah.autoHealingContainerMapping
      val metaId: String = (json \ "metaGeneratedId").asOpt[String].getOrElse("null")

      containerMapping.collectFirst {
        case (itemId, containerName)
          if containerName != "not_set" &&
            !(json \ "containersInfo" \ containerName \ "items").asOpt[JsObject]
              .exists(_.values.exists(item => (item \ "itemId").asOpt[Int].contains(itemId.toInt)))
        => containerName
      } match {
        case Some(emptyContainer) =>
          println(s"[$taskName] '$emptyContainer' is empty → removing it")
          val upSeqOpt = for {
            invObj   <- (json \ "screenInfo" \ "inventoryPanelLoc").asOpt[JsObject]
            panelKey <- invObj.keys.find(_.contains(emptyContainer))
            upBtn    <- (invObj \ panelKey \ "upButton").asOpt[JsObject]
            x        <- (upBtn \ "x").asOpt[Int]
            y        <- (upBtn \ "y").asOpt[Int]
          } yield List(
            MoveMouse(x, y, metaId),
            LeftButtonPress(x, y, metaId),
            LeftButtonRelease(x, y, metaId)
          )

          val actions     = upSeqOpt.getOrElse(Nil)
          val updatedAH   = ah.copy(
            statusOfAutoheal = "remove_backpack",
            autoHealContainerToRemove = emptyContainer
          )
          val updatedState = state.copy(autoHeal = updatedAH)
          Some(updatedState -> MKTask(taskName, MKActions(actions, Nil)))

        case None =>
          None
      }
    }
  }


  private object SetUpHealingSupplies extends Step {
    private val taskName = "SetUpSupplies"
    def run(
             state:    GameState,
             json:     JsValue,
             settings: UISettings
           ): Option[(GameState, MKTask)] = {

      val ah = state.autoHeal

      // Only run if supplies haven't been set up yet
      if (ah.isHealingSuppliesContainerSet) {
        return None
      }

      println("start SetUpSupplies")

      val containers = (json \ "containersInfo").asOpt[JsObject]
        .map(_.fields)
        .getOrElse(Nil)

      println(s"  [SetUpSupplies] found ${containers.size} containers:")
      containers.foreach { case (name, details) =>
        val ids = (details \ "items").asOpt[JsObject]
          .map(_.values.flatMap(item => (item \ "itemId").asOpt[Int])).getOrElse(Seq.empty)
        println(s"    - $name contains IDs: ${ids.mkString(",")}")
      }

      // Helper function to find container with any healing item
      def findContainerWithHealingItems(): Map[String, String] = {
        val healingItems = StaticGameInfo.Items.healingItems

        healingItems.map { itemId =>
          val containerName = containers.collectFirst {
            case (name, details)
              if (details \ "items").asOpt[JsObject]
                .exists(_.values.exists(item => (item \ "itemId").asOpt[Int].contains(itemId)))
            => name
          }.getOrElse("not_set")

          itemId.toString -> containerName
        }.toMap.filter(_._2 != "not_set") // Filter out items with no container
      }

      val containerMapping = findContainerWithHealingItems()

      println(s"  [SetUpSupplies] container mapping: $containerMapping")

      val updatedState = state.copy(autoHeal = ah.copy(
        autoHealingContainerMapping = containerMapping,
        isHealingSuppliesContainerSet = true,
        statusOfAutoheal = "ready"
      ))

      println(s"  → SetUpSupplies: mapping set, isHealingSuppliesContainerSet=true")
      Some(updatedState -> MKTask(taskName, MKActions.empty))
    }
  }

  private object HandleHealingBackpacks extends Step {
    private val taskName = "HandleBackpacks"

    def run(
             state: GameState,
             json:  JsValue,
             settings: UISettings
           ): Option[(GameState, MKTask)] = {
      val metaId: String = (json \ "metaGeneratedId").asOpt[String].getOrElse("null")
      state.autoHeal.statusOfAutoheal match {

        case "remove_backpack" =>
          val subTaskName = "remove_backpack"
          val containerToHandle = state.autoHeal.autoHealContainerToRemove

          if (containerToHandle.isEmpty) {
            println("[HandleBackpacks] ERROR: no container specified for removal")
            return None
          }

          println(s"start remove_backpack for container: $containerToHandle")

          // 1) character position as Option[(x,y)]
          val maybeCharLoc: Option[(Int, Int)] =
            for {
              mp <- (json \ "screenInfo" \ "mapPanelLoc" \ "8x6").asOpt[JsObject]
              x <- (mp \ "x").asOpt[Int]
              y <- (mp \ "y").asOpt[Int]
            } yield (x, y)

          // 2) build the four‑step remove sequence if everything is present
          val maybeActions: Option[List[MouseAction]] = for {
            (cx, cy) <- maybeCharLoc
            invObj <- (json \ "screenInfo" \ "inventoryPanelLoc").asOpt[JsObject]
            matching <- invObj.keys.find(_.contains(containerToHandle))
            item0Obj <- (invObj \ matching \ "contentsPanel" \ "item0").asOpt[JsObject]
            emptyX <- (item0Obj \ "x").asOpt[Int]
            emptyY <- (item0Obj \ "y").asOpt[Int]
          } yield List(
            MoveMouse(emptyX, emptyY, metaId),
            LeftButtonPress(emptyX, emptyY, metaId),
            MoveMouse(cx, cy, metaId),
            LeftButtonRelease(cx, cy, metaId)
          )

          // 3) fold: if we found them, use them; if not, return no actions
          val mouseActions: List[MouseAction] =
            maybeActions.fold(List.empty[MouseAction])(identity)

          // 4) bump the auto‑heal status
          val newAutoHeal = state.autoHeal.copy(
            statusOfAutoheal = "open_new_backpack"
          )
          val updatedState = state.copy(autoHeal = newAutoHeal)

          return Some(updatedState -> MKTask(s"$taskName - $subTaskName", MKActions(mouseActions, Nil)))

        case "open_new_backpack" =>
          val subTaskName = "open_new_backpack"
          val containerToHandle = state.autoHeal.autoHealContainerToRemove

          // find the new‑backpack slot and build a right‑click sequence
          val maybeOpenSeq: Option[List[MouseAction]] = for {
            invObj <- (json \ "screenInfo" \ "inventoryPanelLoc").asOpt[JsObject]
            matching <- invObj.keys.find(_.contains(containerToHandle))
            item0Obj <- (invObj \ matching \ "contentsPanel" \ "item0").asOpt[JsObject]
            x <- (item0Obj \ "x").asOpt[Int]
            y <- (item0Obj \ "y").asOpt[Int]
          } yield List(
            MoveMouse(x, y, metaId),
            RightButtonPress(x, y, metaId),
            RightButtonRelease(x, y, metaId)
          )

          val mouseActions = maybeOpenSeq.getOrElse(Nil)

          // bump to "verifying" so the next loop can check runes
          val updatedState = state.copy(
            autoHeal = state.autoHeal.copy(statusOfAutoheal = "verifying")
          )

          return Some(updatedState -> MKTask(s"$taskName - $subTaskName", MKActions(mouseActions, Nil)))

        case "verifying" =>
          val sub = "verifying"
          val ah = state.autoHeal
          val containerToHandle = ah.autoHealContainerToRemove
          println(s"[$taskName] [$sub] verifying container='$containerToHandle'")

          // Find the item ID that should be in this container
          val expectedItemId = ah.autoHealingContainerMapping.collectFirst {
            case (itemId, containerName) if containerName == containerToHandle => itemId.toInt
          }

          expectedItemId match {
            case Some(itemId) =>
              // Check if items are still present
              val hasItems = (json \ "containersInfo" \ containerToHandle \ "items").asOpt[JsObject]
                .exists(_.values.exists(item =>
                  (item \ "itemId").asOpt[Int].contains(itemId)
                ))

              if (hasItems) {
                println(s"[$taskName] [$sub] items still present, switching to READY")
                val newState = state.copy(
                  autoHeal = ah.copy(
                    statusOfAutoheal = "ready",
                    autoHealContainerToRemove = ""
                  )
                )
                return Some(newState -> MKTask(s"$taskName - $sub", MKActions.empty))
              } else {
                // Container is empty, remove it from mapping and go back to ready
                println(s"[$taskName] [$sub] container empty, removing from mapping")
                val updatedMapping = ah.autoHealingContainerMapping.filter(_._2 != containerToHandle)
                val newState = state.copy(
                  autoHeal = ah.copy(
                    statusOfAutoheal = "ready",
                    autoHealContainerToRemove = "",
                    autoHealingContainerMapping = updatedMapping
                  )
                )
                return Some(newState -> MKTask(s"$taskName - $sub", MKActions.empty))
              }

            case None =>
              println(s"[$taskName] [$sub] ERROR: no item ID found for container '$containerToHandle'")
              val newState = state.copy(
                autoHeal = ah.copy(
                  statusOfAutoheal = "ready",
                  autoHealContainerToRemove = ""
                )
              )
              return Some(newState -> MKTask(s"$taskName - $sub", MKActions.empty))
          }

        case _ =>
          None
      }
    }
  }
//
//  private object DangerLevelHealing extends Step {
//    private val taskName = "DangerLevelHealing"
//    def run(
//             state:    GameState,
//             json:     JsValue,
//             settings: UISettings
//           ): Option[(GameState, MKTask)] = {
//
//      val metaId: String = (json \ "metaGeneratedId").asOpt[String].getOrElse("null")
//
//      if (settings.hotkeyMode) {
//        // Dynamic healing not supported in hotkey mode
//        return None
//      }
//
//      val armed = state.autoHeal.dangerLevelHealing
//      val isActiveFromJson = (json \ "characterInfo" \ "IsCrosshairActive")
//        .asOpt[Boolean].getOrElse(false)
//
//      // sync our state
//      val synced = state.copy(autoHeal = state.autoHeal.copy(
//        healingCrosshairActive = isActiveFromJson
//      ))
//
//      // === explicitly type these as Option[List[MouseAction]] ===
//      val activateSeq: Option[List[MouseAction]] =
//        if (armed && !isActiveFromJson) {
//          // The for-comprehension here already returns Option[List[MouseAction]]
//          for {
//            mp      <- (json \ "screenInfo" \ "mapPanelLoc" \ "8x6").asOpt[JsObject]
//            tx      <- (mp   \ "x").asOpt[Int]
//            ty      <- (mp   \ "y").asOpt[Int]
//            runePos <- findItemInContainerSlot14(json, synced, 3160, 1).headOption
//            rx       = (runePos \ "x").as[Int]
//            ry       = (runePos \ "y").as[Int]
//          } yield List(
//            MoveMouse(rx, ry, metaId),
//            RightButtonPress(rx, ry, metaId),
//            RightButtonRelease(rx, ry, metaId),
//            MoveMouse(tx, ty, metaId)
//          )
//        } else {
//          // now this is unambiguously the same type
//          Option.empty[List[MouseAction]]
//        }
//
//      val deactivateSeq: Option[List[MouseAction]] =
//        if (!armed && isActiveFromJson) {
//          println("[DangerLevelHealing] deactivating crosshair")
//          Some(List(RightButtonRelease(0, 0, metaId)))
//        } else {
//          Option.empty[List[MouseAction]]
//        }
//
//      // now orElse, isDefined, etc. will compile
//      val mouseActionsOpt = activateSeq.orElse(deactivateSeq)
//
//      mouseActionsOpt match {
//        case Some(mouseActions) if mouseActions.nonEmpty =>
//          println(s"[DangerLevelHealing] will emit: $mouseActions")
//
//          // determine the new crosshair‐active state
//          val newActive = activateSeq.isDefined
//
//          val newState = synced.copy(autoHeal = synced.autoHeal.copy(
//            healingCrosshairActive = newActive
//          ))
//          Some(newState -> MKTask(taskName, MKActions(mouseActions, Nil)))
//
//        case _ =>
//          println("[DangerLevelHealing] no crosshair actions, passing through")
//          None
//      }
//    }
//  }

  private object TeamHealing extends Step {
    private val taskName = "TeamHealing"

    def run(
             state: GameState,
             json: JsValue,
             settings: UISettings
           ): Option[(GameState, MKTask)] = {

      val ah = state.autoHeal
      val currentTime = System.currentTimeMillis()
      val metaId: String = (json \ "metaGeneratedId").asOpt[String].getOrElse("null")

      if ((currentTime - ah.lastHealUseTime) < (ah.healingUseCooldown + ah.healUseRandomness) ) {
        return None
      }

      // Check if team healing is enabled
      println(s"[$taskName] Checking team healing")

      // Get spy level info to check friend health
      val spyLevelInfoOpt = (json \ "spyLevelInfo").asOpt[JsObject]

      spyLevelInfoOpt.flatMap { spyLevelInfo =>
        // Define friends to monitor with their settings
        val friends = settings.healingSettings.friendsHealSettings.headOption.map { friendsCfg =>
          List(
            (friendsCfg.friend1Name,
              friendsCfg.friend1HealHealthPercent,
              friendsCfg.friend1HealMana,
              friendsCfg.friend1HealSpell),
            (friendsCfg.friend2Name,
              friendsCfg.friend2HealHealthPercent,
              friendsCfg.friend2HealMana,
              friendsCfg.friend2HealSpell),
            (friendsCfg.friend3Name,
              friendsCfg.friend3HealHealthPercent,
              friendsCfg.friend3HealMana,
              friendsCfg.friend3HealSpell)
          ).filter(_._1.nonEmpty)
        }.getOrElse(List.empty)

        println(s"[$taskName] Monitoring ${friends.size} friends")

        // Get current character mana
        val currentMana = (json \ "characterInfo" \ "Mana").asOpt[Int].getOrElse(0)

        // Find first friend that needs healing
        val friendToHeal = friends.collectFirst {
          case (name, minHealth, minMana, spell)
            if spell.nonEmpty && currentMana >= minMana =>

            // Search for friend in spy level info
            spyLevelInfo.fields.collectFirst {
              case (_, playerInfo: JsObject)
                if (playerInfo \ "Name").asOpt[String].contains(name) &&
                  (playerInfo \ "IsPlayer").asOpt[Boolean].getOrElse(false) =>

                val healthPercent = (playerInfo \ "HealthPercent").asOpt[Int].getOrElse(100)

                println(s"[$taskName] Found $name with $healthPercent% HP (threshold: $minHealth%)")

                if (healthPercent <= minHealth) {
                  Some((name, spell))
                } else None
            }.flatten
        }.flatten

        friendToHeal.flatMap { case (friendName, spell) =>
          println(s"[$taskName] Healing $friendName with spell: $spell")

          // Check if spell is bound to a hotkey
          val hotkeysObjOpt = (json \ "hotkeysBinds").asOpt[JsObject]

          println(s"[$taskName] DEBUG: hotkeysBinds available: ${hotkeysObjOpt.isDefined}")

          hotkeysObjOpt.foreach { hotkeys =>
            println(s"[$taskName] DEBUG: All hotkey fields:")
            hotkeys.fields.foreach { case (key, value) =>
              if (key.endsWith("_value")) {
                val valueStr = value.asOpt[String].getOrElse("")
                val autoSend = (hotkeys \ key.replace("_value", "_autoSend")).asOpt[Boolean].getOrElse(false)
                println(s"  $key = '$valueStr', autoSend = $autoSend")
                println(s"  - contains friendName '$friendName': ${valueStr.contains(friendName)}")
              }
            }
          }

          val keyboardAction = hotkeysObjOpt.flatMap { hotkeys =>
            println(s"[$taskName] DEBUG: Searching for hotkey matching friendName='$friendName'")

            hotkeys.fields.collectFirst {
              case (key, value: JsValue)
                if key.endsWith("_value") &&
                  (value).asOpt[String].exists { v =>
                    println(s"[$taskName] DEBUG: Checking key='$key', value='$v'")
                    val matches = v.contains(friendName)
                    println(s"[$taskName] DEBUG: Contains check result: $matches")
                    matches
                  } &&
                  (hotkeys \ key.replace("_value", "_autoSend")).asOpt[Boolean].getOrElse(false) =>

                val hotkeyStr = key.stripSuffix("_value")
                println(s"[$taskName] DEBUG: MATCH FOUND - Using hotkey: $hotkeyStr")

                // Parse the hotkey string to handle modifier keys
                val parts = hotkeyStr.split("\\+").toList
                val baseKey = parts.lastOption.getOrElse(hotkeyStr)
                val hasShift = parts.exists(_.equalsIgnoreCase("Shift"))

                // Build the keyboard actions with modifier support
                val keyActions = if (hasShift) {
                  List(
                    keyboard.PressShift(metaId),
                    GeneralKey(KeyboardUtils.fromHotkeyString(baseKey), metaId),
                    keyboard.ReleaseShift(metaId)
                  )
                } else {
                  List(GeneralKey(KeyboardUtils.fromHotkeyString(baseKey), metaId))
                }

                keyActions
            }
          }.getOrElse {
            println(s"[$taskName] DEBUG: No hotkey found, typing spell: $spell")
            List(TextType(spell, metaId))
          }

          val keyboardActions = keyboardAction

          // Update state with new heal time
          val updatedAh = ah.copy(
            lastHealUseTime = currentTime,
          )

          val updatedState = state.copy(autoHeal = updatedAh)

          println(s"[$taskName] Successfully cast heal on $friendName")
          Some(updatedState -> MKTask(s"$taskName - $friendName", MKActions(Nil, keyboardActions)))
        }
      }
    }
  }

}
