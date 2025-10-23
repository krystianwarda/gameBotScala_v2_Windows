package processing

import cats.implicits.{catsSyntaxAlternativeGuard, toFunctorOps}
import keyboard.{DirectionalKey, GeneralKey, KeyboardAction, PressShift, ReleaseShift}
import play.api.libs.json.{JsObject, JsValue, Json, OFormat}
import utils.{GameState, RandomUtils, StaticGameInfo}
import utils.ProcessingUtils.{MKActions, MKTask, NoOpTask, Step}
import utils.SettingsUtils.UISettings
import utils.StaticGameInfo.Items.SpearsIds
import utils.consoleColorPrint.{ANSI_BLUE, printInColor}
import keyboard.KeyboardUtils.{getKeyCode, keyCodeFromString}
//import scala.util.Random
import scala.collection.mutable
import scala.util.Try
import mouse.{LeftButtonPress, LeftButtonRelease, MouseAction, MoveMouse, RightButtonPress, RightButtonRelease}
import processing.CaveBotFeature.Vec


object AutoTargetFeature {

  def run(json: JsValue, settings: UISettings, state: GameState): (GameState, List[MKTask]) =
    (!settings.autoTargetSettings.enabled).guard[Option]
      .as((state, Nil))
      .getOrElse {
        val (s, maybeTask) = Steps.runAll(json, settings, state)
        (s, maybeTask)
      }

  private object Steps {
    // ordered list of steps
    val allSteps: List[Step] = List(
      DetectDeadCreatures,
      CheckCorpesToLoot,
      UpdateAttackStatus,
      UnclickCrosshair,
      UnmarkPlayer,
      GetAttackInfo,
      SetUpAttackSupplies,
      CheckAttackSupplies,
      HandleAttackBackpacks,
      RefillAmmo,
      TargetFocusing,
      TargetMarking,
      EngageAttack,
      EngageMovement,
      PrepareToLoot
    )



    def runAll(
                json: JsValue,
                settings: UISettings,
                startState: GameState
              ): (GameState, List[MKTask]) = {
      @annotation.tailrec
      def loop(
                remaining: List[Step],
                currentState: GameState,
                acc: List[MKTask]
              ): (GameState, List[MKTask]) = remaining match {
        case Nil =>
//          println(s"[Steps] Final stateAutoTarget: '${currentState.autoTarget.stateAutoTarget}'")
          (currentState, acc)

        case step :: rest =>
          val stepName = step.getClass.getSimpleName.replace("$", "")
//          println(s"[Steps] Before $stepName: stateAutoTarget='${currentState.autoTarget.stateAutoTarget}'")

          step.run(currentState, json, settings) match {
            case Some((newState, NoOpTask)) =>
//              println(s"[Steps] After $stepName: stateAutoTarget='${newState.autoTarget.stateAutoTarget}' (NoOpTask)")
              loop(rest, newState, acc)

            case Some((newState, task)) =>
//              println(s"[Steps] After $stepName: stateAutoTarget='${newState.autoTarget.stateAutoTarget}' (Task: ${task})")
              loop(rest, newState, acc :+ task)

            case None =>
//              println(s"[Steps] After $stepName: stateAutoTarget='${currentState.autoTarget.stateAutoTarget}' (None)")
              loop(rest, currentState, acc)
          }
      }

      loop(allSteps, startState, Nil)
    }
  }

  private object UnclickCrosshair extends Step {
    private val taskName = "UnclickCrosshair"

    override def run(
                      state: GameState,
                      json: JsValue,
                      settings: UISettings
                    ): Option[(GameState, MKTask)] = {
      println(s"[UnclickCrosshair] stateAutoTarget: ${state.autoTarget.stateAutoTarget}")
      val at = state.autoTarget
      val characterInfo = json \ "characterInfo"
      val isCrosshairActive = (characterInfo \ "IsCrosshairActive").asOpt[Boolean].getOrElse(false)

      println(s"[UnclickCrosshair] Checking conditions - isCrosshairActive: $isCrosshairActive, chosenTargetId: ${at.chosenTargetId}")
      println(s"[UnclickCrosshair] attackRuneMode: ${at.attackRuneMode}")

      // Only proceed if crosshair is active and no target is chosen
      if (!isCrosshairActive || at.chosenTargetId != 0) {
        if (!isCrosshairActive) {
          println(s"[UnclickCrosshair] Skipping - crosshair not active")
        } else {
          println(s"[UnclickCrosshair] Skipping - target selected (${at.chosenTargetId})")
        }
        return None
      }

      println(s"[UnclickCrosshair] Crosshair active with no target - canceling attack")
      println(s"[UnclickCrosshair] Creating right-click actions to cancel crosshair")
      val metaId: String = (json \ "metaGeneratedId").asOpt[String].getOrElse("null")

      // Simple right-click to cancel crosshair (no mouse movement needed)
      val mouseActions = List(
        RightButtonPress(0, 0, metaId),
        RightButtonRelease(0, 0, metaId)
      )

      println(s"[UnclickCrosshair] Mouse actions created: $mouseActions")
      println(s"[UnclickCrosshair] Resetting attackRuneMode from '${at.attackRuneMode}' to 'free'")

      val updatedState = state.copy(autoTarget = at.copy(
        attackRuneMode = "free"
      ))

      val task = MKTask(taskName, MKActions(mouseActions, Nil))
      println(s"[UnclickCrosshair] Task created successfully")

      Some(updatedState -> task)
    }
  }

  private object CheckCorpesToLoot extends Step {
    private val taskName = "CheckCorpesToLoot"

    override def run(state: GameState, json: JsValue, settings: UISettings): Option[(GameState, MKTask)] = {
      val autoLoot = state.autoLoot

      // Check if there are creatures in carcassToLootImmediately
      if (autoLoot.carcassToLootImmediately.nonEmpty) {
        println(s"[$taskName] Found ${autoLoot.carcassToLootImmediately.length} corpses to loot immediately - stopping AutoTarget")
        val updatedState = state.copy(autoTarget = state.autoTarget.copy(
          stateAutoTarget = "stop"
        ))
        return Some((updatedState, NoOpTask))
      }

      // Check if there are creatures in carcassToLootAfterFight
      if (autoLoot.carcassToLootAfterFight.nonEmpty) {
        // Check if battle list has monsters
        val battleInfo = (json \ "battleInfo").asOpt[Map[String, JsValue]].getOrElse(Map.empty)
        val hasMonsters = battleInfo.values.exists { creatureData =>
          (creatureData \ "IsMonster").asOpt[Boolean].getOrElse(false)
        }

        if (hasMonsters) {
          println(s"[$taskName] Battle list not empty - disregarding corpses to loot after fight")
          return None
        } else {
          println(s"[$taskName] Battle list empty and found ${autoLoot.carcassToLootAfterFight.length} corpses to loot after fight - stopping AutoTarget")
          val updatedState = state.copy(autoTarget = state.autoTarget.copy(
            stateAutoTarget = "stop"
          ))
          return Some((updatedState, NoOpTask))
        }
      }

      // No action needed
      None
    }
  }


  private object DetectDeadCreatures extends Step {
    private val taskName = "DetectDeadCreatures"

    override def run(state: GameState, json: JsValue, settings: UISettings): Option[(GameState, MKTask)] = {
      // Skip if AutoTarget is already stopped
      if (state.autoTarget.stateAutoTarget == "stop") {
        println(s"[$taskName] Skipping - AutoTarget is stopped")
        return None
      }

      val currentTime = System.currentTimeMillis()

      // Check if current chosen target is in lastKilledCreatures
      if (state.autoTarget.chosenTargetId == 0) {
        println(s"[$taskName] Skipping - no chosen target")
        return None
      }

      val chosenTargetId = state.autoTarget.chosenTargetId
      val lastKilledCreatures = (json \ "lastKilledCreatures").asOpt[JsObject].getOrElse(Json.obj())

      println(s"[$taskName] Checking if target $chosenTargetId is in lastKilledCreatures")

      val killedCreatureOpt = (lastKilledCreatures \ chosenTargetId.toString).asOpt[JsObject]
      val isDead = killedCreatureOpt.flatMap(obj => (obj \ "IsDead").asOpt[Boolean]).getOrElse(false)

      println(s"[$taskName] killedCreatureData: $killedCreatureOpt, isDead: $isDead")

      if (!isDead) {
        println(s"[$taskName] Skipping - target $chosenTargetId not dead")
        return None
      }

      println(s"[$taskName] Target $chosenTargetId is dead - resetting chosenTargetId")

      // Extract creature info for loot handling
      val creatureName = state.autoTarget.lastTargetName
      val (x, y, z) = killedCreatureOpt.map { obj =>
        val killedX = (obj \ "X").asOpt[Int].getOrElse(state.autoTarget.lastTargetPos._1)
        val killedY = (obj \ "Y").asOpt[Int].getOrElse(state.autoTarget.lastTargetPos._2)
        val killedZ = (obj \ "Z").asOpt[Int].getOrElse(state.autoTarget.lastTargetPos._3)
        (killedX, killedY, killedZ)
      }.getOrElse(state.autoTarget.lastTargetPos)

      // Reset chosenTargetId since the creature is dead
      val updatedAutoTarget = state.autoTarget.copy(
        chosenTargetId = 0,
        chosenTargetName = "",
        lastTargetName = "",
        stateAutoTarget = "search" // Reset to search for new targets
      )

      val updatedState = state.copy(autoTarget = updatedAutoTarget)

      // Only handle loot if auto-loot is enabled
      if (settings.autoLootSettings.enabled) {
        // Check if this creature has loot settings
        settings.autoTargetSettings.creatureList
          .map(parseCreature)
          .find(_.name.equalsIgnoreCase(creatureName)) match {

          case Some(cs) if cs.lootMonsterImmediately =>
            println(s"[$taskName] Creature $creatureName died with immediate loot setting - stopping AutoTarget")

            // Add to loot queue
            val tileKey = generatePositionKey(x, y, z)
            val updatedStateWithLoot = updatedState.copy(
              autoLoot = updatedState.autoLoot.copy(
                carcassToLootImmediately = updatedState.autoLoot.carcassToLootImmediately :+ (tileKey, currentTime, chosenTargetId.toString),
                startTimeOfLooting = currentTime
              ),
              autoTarget = updatedAutoTarget.copy(stateAutoTarget = "stop")
            )
            Some((updatedStateWithLoot, NoOpTask))

          case Some(cs) if cs.lootMonsterAfterFight =>
            println(s"[$taskName] Creature $creatureName died with post-fight loot setting")
            val tileKey = generatePositionKey(x, y, z)
            val updatedStateWithLoot = updatedState.copy(
              autoLoot = updatedState.autoLoot.copy(
                carcassToLootAfterFight = updatedState.autoLoot.carcassToLootAfterFight :+ (tileKey, currentTime, chosenTargetId.toString)
              )
            )
            Some((updatedStateWithLoot, NoOpTask))

          case _ =>
            println(s"[$taskName] Creature $creatureName died with no loot settings - just reset target")
            Some((updatedState, NoOpTask))
        }
      } else {
        println(s"[$taskName] Auto-loot disabled - just reset target without looting")
        Some((updatedState, NoOpTask))
      }
    }

    private def generatePositionKey(x: Int, y: Int, z: Int): String = f"$x$y$z"
  }

  private object UpdateAttackStatus extends Step {
    private val taskName = "UpdateAttackStatus"

    override def run(state: GameState, json: JsValue, settings: UISettings): Option[(GameState, MKTask)] = {
      println(s"[$taskName] ENTRY: stateAutoTarget='${state.autoTarget.stateAutoTarget}', chosenTargetId=${state.autoTarget.chosenTargetId}")
      val currentTime = System.currentTimeMillis()

      if (state.autoTarget.stateAutoTarget == "stop") {
        println(s"[$taskName] EXIT 1: AutoTarget is stopped")
        return Some(state -> NoOpTask)
      }

      val battleInfo = (json \ "battleInfo").asOpt[Map[String, JsValue]].getOrElse(Map.empty)
      val currentAttackId = (json \ "attackInfo" \ "Id").asOpt[Int]
      println(s"[$taskName] currentAttackId=$currentAttackId, chosenTargetId=${state.autoTarget.chosenTargetId}")

      // First check if we're attacking someone that's not our chosen target
      currentAttackId match {
        case Some(attackedId) if attackedId != state.autoTarget.chosenTargetId =>
          // Check if this is a player
          val attackedCreatureOpt = battleInfo.values.find { creature =>
            (creature \ "Id").asOpt[Int].contains(attackedId)
          }

          attackedCreatureOpt.foreach { creature =>
            val isPlayer = (creature \ "IsPlayer").asOpt[Boolean].getOrElse(false)
            if (isPlayer) {
              println(s"[$taskName] EXIT 2: Detected player attack (id=$attackedId) - setting state to 'unmarking'")
              val updatedState = state.copy(autoTarget = state.autoTarget.copy(
                chosenTargetId = attackedId,
                chosenTargetName = (creature \ "Name").asOpt[String].getOrElse("Unknown Player"),
                stateAutoTarget = "unmarking"
              ))
              return Some(updatedState -> NoOpTask)
            }
          }
        case _ => // Continue with normal logic
      }

      // Check if there are any creatures in battle (not players)
      val hasCreatures = battleInfo.values.exists { creature =>
        (creature \ "IsMonster").asOpt[Boolean].getOrElse(false)
      }
      println(s"[$taskName] hasCreatures=$hasCreatures")

      if (!hasCreatures) {
        println(s"[$taskName] EXIT 3: No creatures in battle - setting caveBot to 'free'")
        val updatedCaveBot = state.caveBot.copy(stateHunting = "free")
        val updatedState = state.copy(caveBot = updatedCaveBot)
        return Some(updatedState -> NoOpTask)
      }

      val chosenTargetStillInBattle = state.autoTarget.chosenTargetId != 0 && battleInfo.values.exists { creature =>
        (creature \ "Id").asOpt[Int].contains(state.autoTarget.chosenTargetId) &&
          (creature \ "IsMonster").asOpt[Boolean].getOrElse(false)
      }

      println(s"[$taskName] chosenTargetStillInBattle=$chosenTargetStillInBattle")

      if (chosenTargetStillInBattle) {
        println(s"[$taskName] Chosen target ${state.autoTarget.chosenTargetId} still in battle - keeping current target")
        return Some(state -> NoOpTask)
      }

      // If state is 'unmarking' and no creature is attacked, reset and search for new target
      if (state.autoTarget.stateAutoTarget == "unmarking" && currentAttackId.isEmpty) {
        println(s"[$taskName] After unmarking, no creature attacked - resetting to search for new target")
        val updatedState = state.copy(autoTarget = state.autoTarget.copy(
          chosenTargetId = 0,
          chosenTargetName = "",
          stateAutoTarget = "free"
        ))
        // Continue to target selection instead of returning
        return run(updatedState, json, settings)
      }

      if (currentAttackId.contains(state.autoTarget.chosenTargetId) &&
        state.autoTarget.chosenTargetId != 0) {
        println(s"[$taskName] EXIT 5: Currently attacking chosen target - no change needed")
        return Some(state -> NoOpTask)
      }

      val at0 = state.autoTarget
      val now = System.currentTimeMillis()

      // 1. Throttle update frequency
      if (now - at0.updateAttackChangeTime < at0.updateAttackThrottleTime) {
        println(s"[UpdateAttackStatus] Holding picking new target. Last change was lately")
        return Some(state -> NoOpTask)
      }

      val characterPos = extractCharPosition(json)

      if (currentTime - at0.lastTargetLookoutTime < 1000) {
        println(s"[UpdateAttackStatus] Freezing picking new target for a while. Time: ${currentTime - at0.lastTargetLookoutTime}")
        return Some(state -> NoOpTask)
      }

      println(s"[UpdateAttackStatus] Looking for a new target")

      val parsedSettings = settings.autoTargetSettings.creatureList.map(parseCreature)
      val settingNamesLower = parsedSettings.map(_.name.toLowerCase).toSet
      val considerAll = settingNamesLower.contains("all")

      val filteredCreatures = battleInfo.values.toList.filter { idx =>
        val name = (idx \ "Name").asOpt[String].map(_.toLowerCase)
        val isCreature = (idx \ "IsMonster").asOpt[Boolean].getOrElse(false)
        (considerAll && isCreature) || name.exists(settingNamesLower.contains)
      }

      val sortedCreatures = filteredCreatures.flatMap { idx =>
        for {
          id <- (idx \ "Id").asOpt[Int]
          name <- (idx \ "Name").asOpt[String]
          x <- (idx \ "PositionX").asOpt[Int]
          y <- (idx \ "PositionY").asOpt[Int]
          z <- (idx \ "PositionZ").asOpt[Int]
        } yield CreatureInfo(
          id = id,
          name = name,
          healthPercent = 100,
          isShootable = (idx \ "IsShootable").asOpt[Boolean].getOrElse(true),
          isMonster = (idx \ "IsMonster").asOpt[Boolean].getOrElse(false),
          danger = 1,
          keepDistance = false,
          isPlayer = (idx \ "IsPlayer").asOpt[Boolean].getOrElse(false),
          posX = x,
          posY = y,
          posZ = z,
          lootMonsterImmediately = false,
          lootMonsterAfterFight = false,
          lureCreatureToTeam = false
        )
      }.sortBy(c => characterPos.manhattanDistance(Vec(c.posX, c.posY)))

      println(s"[UpdateAttackStatus] AutoTarget settings: $parsedSettings")
      println(s"[UpdateAttackStatus] Creatures list: $sortedCreatures")

      sortedCreatures.find { creature =>
        val targetPos = Vec(creature.posX, creature.posY)
        val distance = characterPos.chebyshevDistance(targetPos)

        println(s"[UpdateAttackStatus] ${creature.name}: HP=${creature.healthPercent}")
        println(s"[UpdateAttackStatus] characterPos: ${characterPos}, targetPos: ${targetPos}")
        println(s"[UpdateAttackStatus] Distance: ${characterPos.manhattanDistance(targetPos)}, chebyshev: $distance")

        // If creature is adjacent (distance 1), we can attack directly
        if (distance <= 1) {
          println(s"[UpdateAttackStatus] Creature is adjacent, can attack directly")
          true
        } else {
          // For creatures further away, check if we have a path
          val pathResult = generateSubwaypointsToCreature(targetPos, state, json)
          val hasPath = pathResult.autoTarget.subWaypoints.nonEmpty
          println(s"[UpdateAttackStatus] Checking path for distant creature, hasPath: $hasPath")
          hasPath
        }
      } match {
        case Some(chosen) =>
          println(s"[UpdateAttackStatus] New target selected: ${chosen.name} (${chosen.id})")

          val updatedAutoTargetState = state.autoTarget.copy(
            chosenTargetId = chosen.id,
            chosenTargetName = chosen.name,
            stateAutoTarget = "fight",
            chaseMode = "chase_to",
            creaturePositionHistory = Map.empty,
            lastTargetPos = (chosen.posX, chosen.posY, chosen.posZ),
            updateAttackChangeTime = currentTime,
            lastTargetLookoutTime = currentTime
          )

          val updatedCaveBotState = state.caveBot.copy(stateHunting = "stop")

          val updatedState = state.copy(
            autoTarget = updatedAutoTargetState,
            caveBot = updatedCaveBotState
          )

          return Some(updatedState -> NoOpTask)
        case None =>
          println(s"[UpdateAttackStatus] No target selected. Resetting the cavebot state.")
          val updatedCaveBot = state.caveBot.copy(stateHunting = "free")
          val updatedAutoTarget = state.autoTarget.copy(
            chosenTargetId = 0,
            chosenTargetName = "",
            stateAutoTarget = "free",
            lastTargetLookoutTime = currentTime
          )
          val updatedState = state.copy(
            caveBot = updatedCaveBot,
            autoTarget = updatedAutoTarget
          )
          return Some(updatedState -> NoOpTask)
      }
    }

    private def extractCharPosition(json: JsValue): Vec = {
      for {
        x <- (json \ "characterInfo" \ "PositionX").asOpt[Int]
        y <- (json \ "characterInfo" \ "PositionY").asOpt[Int]
      } yield Vec(x, y)
    }.getOrElse(Vec(0, 0))
  }

  private object SetUpAttackSupplies extends Step {
    private val taskName = "SetUpAttackSupplies"

    def run(state: GameState, json: JsValue, settings: UISettings):
    Option[(GameState, MKTask)] = {
      println(s"[$taskName] stateAutoTarget: ${state.autoTarget.stateAutoTarget}")
      val at = state.autoTarget

      // only on first pass (mapping empty & still in "init")
      if (at.autoTargetContainerMapping.nonEmpty || at.isAttackRuneContainerSet)
        None
      else {
        // 1) build rune→container mapping
        val mapping = getRuneContainerMapping(json.as[JsObject], settings)
        val firstCont = mapping.values.headOption.getOrElse("not_set")

        // 2) inspect arrow slot (EqInfo → "10")
        val ammoIdsList = List(3446, 3447)  // your arrow IDs
        val ammoIdOpt = (json \ "EqInfo" \ "10" \ "itemId").asOpt[Int]

        // decide isUsingAmmo, ammoId, and nextResupply count
        val (isUsingAmmoStr, ammoIdVal, ammoCountVal) = ammoIdOpt match {
          case Some(id) if ammoIdsList.contains(id) =>
            val rnd =  40 // scala.util.Random.nextInt(41) +
            println(s"[SetUpAttackSupplies] Detected ammo ID $id → using ammo; next refill count = $rnd")
            ("true", id, rnd)
          case _ =>
            println("[SetUpAttackSupplies] No valid ammo in slot → isUsingAmmo = false")
            ("false", at.ammoId, at.ammoCountForNextResupply)
        }

        println(s"[SetUpAttackSupplies] Rune-container mapping: $mapping")
        println(s"[SetUpAttackSupplies] First container: $firstCont")
        println(s"[SetUpAttackSupplies] Final ammoId: $ammoIdVal, next refill threshold: $ammoCountVal")


        val spearIdOpt = (json \ "EqInfo" \ "6" \ "itemId").asOpt[Int]
        val isUsingSpearsStr = spearIdOpt match {
          case Some(id) if SpearsIds.contains(id) =>
            println(s"[SetUpAttackSupplies] Detected spear ID $id in hand slot 6 → using spears")
            "true"
          case _ =>
            println("[SetUpAttackSupplies] No spear detected in hand slot 6")
            "false"
        }

        // 3) assemble new AutoTargetState
        val newAT = at.copy(
          autoTargetContainerMapping     = mapping,
          currentAutoAttackContainerName = firstCont,
          isUsingAmmo                    = isUsingAmmoStr,
          ammoId                         = ammoIdVal,
          ammoCountForNextResupply       = ammoCountVal,
          isAttackRuneContainerSet = true,
          isUsingSpears                  = isUsingSpearsStr
        )

        // 4) update GameState and emit a marker task
        val newState = state.copy(autoTarget = newAT)
        Some(newState -> NoOpTask)
      }
    }
  }

  private object CheckAttackSupplies extends Step {
    private val taskName = "CheckAttackRunes"
    def run(state: GameState, json: JsValue, settings: UISettings): Option[(GameState, MKTask)] = {
      val at = state.autoTarget
      println(s"[$taskName] stateAutoTarget: ${state.autoTarget.stateAutoTarget}")
      println(s"[CheckAttackRunes] Starting check - stateAutoTarget: ${at.stateAutoTarget}")
      println(s"[CheckAttackRunes] currentAutoAttackContainerName: '${at.currentAutoAttackContainerName}'")
      println(s"[CheckAttackRunes] autoTargetContainerMapping: ${at.autoTargetContainerMapping}")

      // Check available containers in JSON
      val containersInfo = (json \ "containersInfo").asOpt[JsObject]
      println(s"[CheckAttackRunes] Available containers in JSON: ${containersInfo.map(_.keys).getOrElse(Set.empty)}")

      // find the first rune whose container is now empty
      at.autoTargetContainerMapping.collectFirst {
        case (runeId, cont) =>
          println(s"[CheckAttackRunes] Checking runeId=$runeId in container='$cont'")

          val containerItems = (json \ "containersInfo" \ cont \ "items").asOpt[JsObject]
          println(s"[CheckAttackRunes] Container '$cont' items structure: $containerItems")

          val hasRune = containerItems.exists(_.values.exists { item =>
            val itemId = (item \ "itemId").asOpt[Int]
            println(s"[CheckAttackRunes] Found item with ID: $itemId")
            itemId.contains(runeId)
          })

          println(s"[CheckAttackRunes] Container '$cont' has rune $runeId: $hasRune")

          if (!hasRune) {
            println(s"[CheckAttackRunes] Container '$cont' is empty of rune $runeId")
            Some((runeId, cont))
          } else {
            None
          }
      }.flatten match {
        case Some((runeId, cont)) =>
          println(s"[CheckAttackRunes] Found empty container: '$cont' missing rune $runeId")

          // Check inventory panel structure
          val invObj = (json \ "screenInfo" \ "inventoryPanelLoc").asOpt[JsObject]
          println(s"[CheckAttackRunes] Inventory panel keys: ${invObj.map(_.keys).getOrElse(Set.empty)}")

          // build the "up backpack" click
          val upSeqOpt = for {
            invPanel <- invObj
            key      <- {
              val matchingKey = invPanel.keys.find(_.contains(cont))
              println(s"[CheckAttackRunes] Looking for key containing '$cont', found: $matchingKey")
              matchingKey
            }
            upBtn    <- {
              val upButton = (invPanel \ key \ "upButton").asOpt[JsObject]
              println(s"[CheckAttackRunes] Up button for key '$key': $upButton")
              upButton
            }
            x        <- (upBtn \ "x").asOpt[Int]
            y        <- (upBtn \ "y").asOpt[Int]
          } yield {
            println(s"[CheckAttackRunes] Up button coordinates: ($x, $y)")
            val metaId: String = (json \ "metaGeneratedId").asOpt[String].getOrElse("null")
            List(
              MoveMouse(x, y, metaId),
              LeftButtonPress(x, y, metaId),
              LeftButtonRelease(x, y, metaId)
            )
          }

          val actions = upSeqOpt.getOrElse {
            println(s"[CheckAttackRunes] Failed to generate up button actions for container '$cont'")
            Nil
          }

          val newAT = at.copy(
            currentAutoAttackContainerName = cont,
            stateAutoTarget = "remove_backpack"
          )

          println(s"[CheckAttackRunes] Updated state - currentAutoAttackContainerName: '${newAT.currentAutoAttackContainerName}'")
          println(s"[CheckAttackRunes] Updated state - stateAutoTarget: '${newAT.stateAutoTarget}'")

          Some(state.copy(autoTarget = newAT) -> MKTask(taskName, MKActions(actions, Nil)))

        case None =>
          println("[CheckAttackRunes] All containers still have their runes.")
          None
      }
    }
  }

  private object TargetFocusing extends Step {
    private val taskName = "TargetFocusing"

    override def run(state: GameState, json: JsValue, settings: UISettings): Option[(GameState, MKTask)] = {
      val at = state.autoTarget
      val now = System.currentTimeMillis()
      val markRetryIntervalMs = 5000L
      println(s"[$taskName] stateAutoTarget: ${state.autoTarget.stateAutoTarget}")
      // Debug: Log crucial information at the beginning
      println(s"[$taskName] === STARTING TARGETFOCUSING ===")
      println(s"[$taskName] stateMarkingTarget: '${at.stateMarkingTarget}'")
      println(s"[$taskName] chosenTargetId: ${at.chosenTargetId}")
      println(s"[$taskName] chosenTargetName: '${at.chosenTargetName}'")
      println(s"[$taskName] stateAutoTarget: '${at.stateAutoTarget}'")
      println(s"[$taskName] lastTargetActionTime: ${at.lastTargetActionTime}")
      println(s"[$taskName] targetActionThrottle: ${at.targetActionThrottle}")
      println(s"[$taskName] lastTargetMarkCommandSend: ${at.lastTargetMarkCommandSend}")
      println(s"[$taskName] currentTime: $now")
      println(s"[$taskName] timeSinceLastAction: ${now - at.lastTargetActionTime}")
      println(s"[$taskName] timeSinceLastMark: ${now - at.lastTargetMarkCommandSend}")
      println(s"[$taskName] markRetryIntervalMs: $markRetryIntervalMs")

      // Check if currently marked
      val alreadyMarked = (json \ "attackInfo" \ "Id").asOpt[Int].contains(at.chosenTargetId)
      println(s"[$taskName] alreadyMarked: $alreadyMarked")
      println(s"[$taskName] attackInfo.Id: ${(json \ "attackInfo" \ "Id").asOpt[Int]}")

      // Preconditions check
      if (at.stateMarkingTarget != "free" || at.chosenTargetId == 0) {
        println(s"[$taskName] EXIT 1: Precondition failed - stateMarkingTarget='${at.stateMarkingTarget}', chosenTargetId=${at.chosenTargetId}")
        return Some(state -> NoOpTask)
      }

      if (now - at.lastTargetActionTime < at.targetActionThrottle) {
        println(s"[$taskName] EXIT 2: Action throttled - need to wait ${at.targetActionThrottle - (now - at.lastTargetActionTime)}ms more")
        return Some(state -> NoOpTask)
      }

      if (at.stateAutoTarget != "fight" || alreadyMarked || (now - at.lastTargetMarkCommandSend < markRetryIntervalMs)) {
        println(s"[$taskName] EXIT 3: Conditions not met:")
        println(s"[$taskName]   - stateAutoTarget='${at.stateAutoTarget}' (should be 'fight')")
        println(s"[$taskName]   - alreadyMarked=$alreadyMarked (should be false)")
        println(s"[$taskName]   - timeSinceLastMark=${now - at.lastTargetMarkCommandSend}ms (should be >= ${markRetryIntervalMs}ms)")
        return Some(state -> NoOpTask)
      }

      val chosenId = at.chosenTargetId
      val chosenName = at.chosenTargetName

      println(s"[$taskName] Proceeding with targeting - chosenId=$chosenId, chosenName='$chosenName'")

      // Get character position
      val charX = (json \ "characterInfo" \ "PositionX").as[Int]
      val charY = (json \ "characterInfo" \ "PositionY").as[Int]
      println(s"[$taskName] Character position: ($charX, $charY)")

      // Get creature position
      val battleInfo = (json \ "battleInfo").asOpt[Map[String, JsValue]].getOrElse(Map.empty)
      println(s"[$taskName] BattleInfo creatures count: ${battleInfo.size}")

      val creaturePos = battleInfo.values
        .find(obj => (obj \ "Id").asOpt[Int].contains(chosenId))
        .flatMap { obj =>
          for {
            x <- (obj \ "PositionX").asOpt[Int]
            y <- (obj \ "PositionY").asOpt[Int]
          } yield {
            println(s"[$taskName] Found creature position: ($x, $y)")
            (x, y)
          }
        }

      if (creaturePos.isEmpty) {
        println(s"[$taskName] EXIT 4: Creature $chosenId not found in battleInfo")
        return Some(state -> NoOpTask)
      }

      val (creatureX, creatureY) = creaturePos.get
      val isAdjacent = creaturePos.exists { case (creatureX, creatureY) =>
        val distance = math.max(math.abs(charX - creatureX), math.abs(charY - creatureY))
        println(s"[$taskName] Distance to creature: $distance (adjacent if <= 1)")
        distance <= 1
      }

      println(s"[$taskName] isAdjacent: $isAdjacent")

      // Decide marking mode based on creature settings and adjacency
      val decidedModeOpt: Option[String] = {
        val csOpt = settings.autoTargetSettings.creatureList
          .map(parseCreature)
          .find(cs => cs.name.equalsIgnoreCase(chosenName) || cs.name.equalsIgnoreCase("All"))

        println(s"[$taskName] Found creature settings: ${csOpt.isDefined}")

        csOpt.flatMap { cs =>
          println(s"[$taskName] Creature settings - targetBattle=${cs.targetBattle}, targetScreen=${cs.targetScreen}")
          (cs.targetBattle, cs.targetScreen) match {
            case (true, true)  =>
              val mode = if (RandomUtils.chance(0.5)) "screen" else "battle"
              println(s"[$taskName] Both modes available, randomly chose: $mode")
              Some(mode)
            case (true, false) =>
              println(s"[$taskName] Only battle mode available")
              Some("battle")
            case (false, true) =>
              println(s"[$taskName] Only screen mode available")
              Some("screen")
            case _ =>
              println(s"[$taskName] No targeting modes enabled")
              None
          }
        }
      }

      if (decidedModeOpt.isEmpty) {
        println(s"[$taskName] EXIT 5: No targeting mode decided for creature '$chosenName'")
        return Some(state -> NoOpTask)
      }

      val decidedMode = decidedModeOpt.get
      println(s"[$taskName] Decided mode: '$decidedMode'")

      // Resolve the UI coordinates for the decided mode
      val posOpt = decidedMode match {
        case "battle" =>
          println(s"[$taskName] Getting battle position for creature $chosenId")
          getBattlePosition(chosenId, json)
        case "screen" =>
          println(s"[$taskName] Getting screen position for creature $chosenId")
          getScreenPosition(chosenId, json)
        case _ =>
          println(s"[$taskName] Invalid mode: '$decidedMode'")
          None
      }

      println(s"[$taskName] Position result: $posOpt")

      posOpt match {
        case Some((x, y)) =>
          val newAT = at.copy(
            plannedMarkingMode = Some(decidedMode),
            stateMarkingTarget = "target marking",
            lastTargetActionTime = now
          )
          val metaId: String = (json \ "metaGeneratedId").asOpt[String].getOrElse("null")
          println(s"[$taskName] EXIT 6: SUCCESS - Focusing on $chosenName ($chosenId) at ($x, $y) using mode '$decidedMode'")
          println(s"[$taskName] Updated stateMarkingTarget to: '${newAT.stateMarkingTarget}'")
          println(s"[$taskName] Updated plannedMarkingMode to: ${newAT.plannedMarkingMode}")
          val newState = state.copy(autoTarget = newAT)
          Some(newState -> MKTask(taskName, MKActions(mouse = List(MoveMouse(x, y, metaId)), keyboard = Nil)))

        case None =>
          val newAT = at.copy(
            plannedMarkingMode = None,
            stateMarkingTarget = "free"
          )
          println(s"[$taskName] EXIT 7: FAILED - Could not find position for mode '$decidedMode', resetting state")
          println(s"[$taskName] Reset stateMarkingTarget to: '${newAT.stateMarkingTarget}'")
          println(s"[$taskName] Reset plannedMarkingMode to: ${newAT.plannedMarkingMode}")
          Some(state.copy(autoTarget = newAT) -> NoOpTask)
      }
    }

    private def getCreaturePosition(chosenId: Int, json: JsValue): Option[(Int, Int)] = {
      getBattlePosition(chosenId, json).orElse(getScreenPosition(chosenId, json))
    }

    private def getBattlePosition(chosenId: Int, json: JsValue): Option[(Int, Int)] = {
      val result = for {
        posObj <- (json \ "screenInfo" \ "battlePanelLoc" \ chosenId.toString).asOpt[JsObject]
        x <- (posObj \ "PosX").asOpt[Int]
        y <- (posObj \ "PosY").asOpt[Int]
      } yield {
        println(s"[$taskName] getBattlePosition: Found battle position ($x, $y) for creature $chosenId")
        (x, y)
      }

      if (result.isEmpty) {
        println(s"[$taskName] getBattlePosition: No battle position found for creature $chosenId")
        println(s"[$taskName] getBattlePosition: Available battlePanelLoc keys: ${(json \ "screenInfo" \ "battlePanelLoc").asOpt[JsObject].map(_.keys).getOrElse(Set.empty)}")
      }

      result
    }

    private def getScreenPosition(chosenId: Int, json: JsValue): Option[(Int, Int)] = {
      println(s"[$taskName] getScreenPosition: Looking for screen position of creature $chosenId")

      val battleInfo = (json \ "battleInfo").asOpt[Map[String, JsValue]].getOrElse(Map.empty)
      val tileIdOpt = battleInfo.values
        .find(obj => (obj \ "Id").asOpt[Int].contains(chosenId))
        .flatMap { obj =>
          for {
            x <- (obj \ "PositionX").asOpt[Int]
            y <- (obj \ "PositionY").asOpt[Int]
            z <- (obj \ "PositionZ").asOpt[Int]
          } yield {
            val tileId = f"$x$y${z}%02d"
            println(s"[$taskName] getScreenPosition: Creature at ($x, $y, $z) -> tileId: $tileId")
            tileId
          }
        }

      if (tileIdOpt.isEmpty) {
        println(s"[$taskName] getScreenPosition: Could not determine tileId for creature $chosenId")
        return None
      }

      val mapTiles = (json \ "screenInfo" \ "mapPanelLoc").asOpt[Map[String, JsObject]].getOrElse(Map.empty)
      println(s"[$taskName] getScreenPosition: Available mapPanelLoc tiles: ${mapTiles.keys.take(5).mkString(", ")}... (showing first 5)")

      val result = mapTiles.values.find(obj => (obj \ "id").asOpt[String] == tileIdOpt).flatMap { obj =>
        for {
          x <- (obj \ "x").asOpt[Int]
          y <- (obj \ "y").asOpt[Int]
        } yield {
          println(s"[$taskName] getScreenPosition: Found screen position ($x, $y) for tileId ${tileIdOpt.get}")
          (x, y)
        }
      }

      if (result.isEmpty) {
        println(s"[$taskName] getScreenPosition: No screen position found for tileId ${tileIdOpt.get}")
      }

      result
    }
  }


//  private object TargetFocusing extends Step {
//    private val taskName = "TargetFocusing"
//
//    override def run(state: GameState, json: JsValue, settings: UISettings): Option[(GameState, MKTask)] = {
//      val at = state.autoTarget
//      val now = System.currentTimeMillis()
//      val markRetryIntervalMs = 2000L
//
//      // Preconditions
//      if (at.stateMarkingTarget != "free" || at.chosenTargetId == 0) {
//        return Some(state -> NoOpTask)
//      }
//      if (now - at.lastTargetActionTime < at.targetActionThrottle) {
//        return Some(state -> NoOpTask)
//      }
//      val alreadyMarked = (json \ "attackInfo" \ "Id").asOpt[Int].contains(at.chosenTargetId)
//      if (at.stateAutoTarget != "fight" || alreadyMarked || (now - at.lastTargetMarkCommandSend < markRetryIntervalMs)) {
//        return Some(state -> NoOpTask)
//      }
//
//      val chosenId = at.chosenTargetId
//      val chosenName = at.chosenTargetName
//
//      // Decide marking mode based on creature settings
//      val decidedModeOpt: Option[String] = {
//        val csOpt = settings.autoTargetSettings.creatureList
//          .map(parseCreature)
//          .find(_.name.equalsIgnoreCase(chosenName))
//        csOpt.flatMap { cs =>
//          (cs.targetBattle, cs.targetScreen) match {
//            case (true, true)  => Some(if (RandomUtils.chance(0.5)) "screen" else "battle")
//            case (true, false) => Some("battle")
//            case (false, true) => Some("screen")
//            case _             => None
//          }
//        }
//      }
//
//      if (decidedModeOpt.isEmpty) {
//        return Some(state -> NoOpTask)
//      }
//
//      val decidedMode = decidedModeOpt.get
//
//      // Resolve the UI coordinates for the decided mode
//      val posOpt = decidedMode match {
//        case "battle" => getBattlePosition(chosenId, json)
//        case "screen" => getScreenPosition(chosenId, json)
//        case _        => None
//      }
//
//      posOpt match {
//        case Some((x, y)) =>
//          val newAT = at.copy(
//            plannedMarkingMode = Some(decidedMode),
//            stateMarkingTarget = "target marking",
//            lastTargetActionTime = now
//          )
//          println(s"[TargetFocusing] Focusing on $chosenName ($chosenId) at ($x, $y) using mode '$decidedMode'")
//          val newState = state.copy(autoTarget = newAT)
//          Some(newState -> MKTask(taskName, MKActions(mouse = List(MoveMouse(x, y)), keyboard = Nil)))
//
//        case None =>
//          // Could not find a position for the decided mode, reset
//          val newAT = at.copy(
//            plannedMarkingMode = None,
//            stateMarkingTarget = "free"
//          )
//          Some(state.copy(autoTarget = newAT) -> NoOpTask)
//      }
//    }
//
//    // Kept for compatibility with your selection; routes to screen first then battle if needed.
//    // Does not use scala.util.Random.
//    private def getCreaturePosition(chosenId: Int, json: JsValue): Option[(Int, Int)] = {
//      getBattlePosition(chosenId, json).orElse(getScreenPosition(chosenId, json))
//    }
//
//    private def getBattlePosition(chosenId: Int, json: JsValue): Option[(Int, Int)] = {
//      for {
//        posObj <- (json \ "screenInfo" \ "battlePanelLoc" \ chosenId.toString).asOpt[JsObject]
//        x <- (posObj \ "PosX").asOpt[Int]
//        y <- (posObj \ "PosY").asOpt[Int]
//      } yield (x, y)
//    }
//
//    private def getScreenPosition(chosenId: Int, json: JsValue): Option[(Int, Int)] = {
//      val battleInfo = (json \ "battleInfo").asOpt[Map[String, JsValue]].getOrElse(Map.empty)
//      val tileIdOpt = battleInfo.values
//        .find(obj => (obj \ "Id").asOpt[Int].contains(chosenId))
//        .flatMap { obj =>
//          for {
//            x <- (obj \ "PositionX").asOpt[Int]
//            y <- (obj \ "PositionY").asOpt[Int]
//            z <- (obj \ "PositionZ").asOpt[Int]
//          } yield f"$x$y${z}%02d"
//        }
//
//      val mapTiles = (json \ "screenInfo" \ "mapPanelLoc").asOpt[Map[String, JsObject]].getOrElse(Map.empty)
//      mapTiles.values.find(obj => (obj \ "id").asOpt[String] == tileIdOpt).flatMap { obj =>
//        for {
//          x <- (obj \ "x").asOpt[Int]
//          y <- (obj \ "y").asOpt[Int]
//        } yield (x, y)
//      }
//    }
//  }

  private object TargetMarking extends Step {
    private val taskName = "TargetMarking"
    private val markRetryIntervalMs = 2000L

    override def run(state: GameState, json: JsValue, settings: UISettings): Option[(GameState, MKTask)] = {
      val at = state.autoTarget
      val now = System.currentTimeMillis()

      if (at.chosenTargetId == 0) {
        return Some(state -> NoOpTask)
      }

      // Already marked → clear any pending marking state
      val alreadyMarked = (json \ "attackInfo" \ "Id").asOpt[Int].contains(at.chosenTargetId)
      if (alreadyMarked) {
        val cleared = at.copy(stateMarkingTarget = "free", plannedMarkingMode = None)
        return Some(state.copy(autoTarget = cleared) -> NoOpTask)
      }

      // Consume the planned mode from focusing
      val modeOpt = at.plannedMarkingMode
      if (modeOpt.isEmpty) {
        return Some(state -> NoOpTask)
      }

      if (now - at.lastTargetMarkCommandSend < markRetryIntervalMs) {
        return Some(state -> NoOpTask)
      }

      val chosenId = at.chosenTargetId
      val mode = modeOpt.get
      val metaId: String = (json \ "metaGeneratedId").asOpt[String].getOrElse("null")

      val actions: List[MouseAction] = mode match {
        case "battle" =>
          getBattlePosition(chosenId, json) match {
            case Some((x, y)) => List(MoveMouse(x, y, metaId), LeftButtonPress(x, y, metaId), LeftButtonRelease(x, y, metaId))
            case None         => Nil
          }
        case "screen" =>
          getScreenPosition(chosenId, json) match {
            case Some((x, y)) => List(MoveMouse(x, y, metaId), RightButtonPress(x, y, metaId), RightButtonRelease(x, y, metaId))
            case None         => Nil
          }
        case _ => Nil
      }

      if (actions.isEmpty) {
        val resetAT = at.copy(stateMarkingTarget = "free", plannedMarkingMode = None)
        return Some(state.copy(autoTarget = resetAT) -> NoOpTask)
      }

      val newAT = at.copy(
        lastTargetMarkCommandSend = now,
        lastMarkingAttemptedId = chosenId,
        stateMarkingTarget = "free",
        plannedMarkingMode = None
      )
      println(s"[TargetMarking] Marking target ${at.chosenTargetId} using mode $mode")
      val newState = state.copy(autoTarget = newAT)
      Some(newState -> MKTask(taskName, MKActions(mouse = actions, keyboard = Nil)))
    }

    private def getBattlePosition(chosenId: Int, json: JsValue): Option[(Int, Int)] = {
      for {
        posObj <- (json \ "screenInfo" \ "battlePanelLoc" \ chosenId.toString).asOpt[JsObject]
        x <- (posObj \ "PosX").asOpt[Int]
        y <- (posObj \ "PosY").asOpt[Int]
      } yield (x, y)
    }

    private def getScreenPosition(chosenId: Int, json: JsValue): Option[(Int, Int)] = {
      val battleInfo = (json \ "battleInfo").asOpt[Map[String, JsValue]].getOrElse(Map.empty)
      val tileIdOpt = battleInfo.values
        .find(obj => (obj \ "Id").asOpt[Int].contains(chosenId))
        .flatMap { obj =>
          for {
            x <- (obj \ "PositionX").asOpt[Int]
            y <- (obj \ "PositionY").asOpt[Int]
            z <- (obj \ "PositionZ").asOpt[Int]
          } yield f"$x$y${z}%02d"
        }

      val mapTiles = (json \ "screenInfo" \ "mapPanelLoc").asOpt[Map[String, JsObject]].getOrElse(Map.empty)
      mapTiles.values.find(obj => (obj \ "id").asOpt[String] == tileIdOpt).flatMap { obj =>
        for {
          x <- (obj \ "x").asOpt[Int]
          y <- (obj \ "y").asOpt[Int]
        } yield (x, y)
      }
    }
  }


//
//  private object TargetFocusing extends Step {
//    override def run(state: GameState, json: JsValue, settings: UISettings): Option[(GameState, MKTask)] = {
//      val taskName = "TargetFocusing"
//
//      val autoTarget = state.autoTarget
//      val currentTime = System.currentTimeMillis()
//      val markRetryIntervalMs = 2000L
//
//      if (autoTarget.stateMarkingTarget != "free" || autoTarget.chosenTargetId == 0) {
//        return Some(state -> NoOpTask)
//      }
//
//      // Throttling check
//      if (currentTime - autoTarget.lastTargetActionTime < autoTarget.targetActionThrottle) {
//        return Some((state, NoOpTask))
//      }
//
//      val shouldProceed =  autoTarget.stateAutoTarget == "fight" && // true
//        !isTargetCurrentlyMarked(autoTarget.chosenTargetId, json) && // true
//        autoTarget.chosenTargetId != 0 && // true
//        (currentTime - autoTarget.lastTargetMarkCommandSend >= markRetryIntervalMs) // true
//
//      if (!shouldProceed) {
//        return Some((state, NoOpTask))
//      }
//
//      println(s"[$taskName] Entered function.")
//      val chosenId = autoTarget.chosenTargetId
//
//      // Find creature position using the same logic as markOnBattleUI/markOnScreenUI
//      getCreaturePosition(chosenId, json) match {
//        case Some((x, y)) =>
//          val mouseActions = List(MoveMouse(x, y))
//
//          val newState = state.copy(autoTarget = autoTarget.copy(
//            stateMarkingTarget = "target marking",
//            lastTargetActionTime = currentTime
//          ))
//
//          Some((newState, MKTask("target focusing", MKActions(mouse = mouseActions, keyboard = Nil))))
//
//        case None =>
//          println(s"[$taskName] Creature location not found.")
//          Some((state.copy(autoTarget = autoTarget.copy(stateMarkingTarget = "free")), NoOpTask))
//      }
//    }
//
//    private def isTargetCurrentlyMarked(targetId: Int, json: JsValue): Boolean = {
//      (json \ "attackInfo" \ "Id").asOpt[Int].contains(targetId)
//    }
//
//    private def getCreaturePosition(chosenId: Int, json: JsValue): Option[(Int, Int)] = {
//      // Try battle panel first (like markOnBattleUI)
//      val battlePanelResult = for {
//        posObj <- (json \ "screenInfo" \ "battlePanelLoc" \ chosenId.toString).asOpt[JsObject]
//        x <- (posObj \ "PosX").asOpt[Int]
//        y <- (posObj \ "PosY").asOpt[Int]
//      } yield (x, y)
//
//      battlePanelResult.orElse {
//        // Fallback to screen UI (like markOnScreenUI)
//        val battleInfo = (json \ "battleInfo").as[Map[String, JsValue]]
//        val tileIdOpt = battleInfo.values
//          .find(obj => (obj \ "Id").asOpt[Int].contains(chosenId))
//          .flatMap { obj =>
//            for {
//              x <- (obj \ "PositionX").asOpt[Int]
//              y <- (obj \ "PositionY").asOpt[Int]
//              z <- (obj \ "PositionZ").asOpt[Int]
//            } yield {
//              val zStr = f"$z%02d"
//              s"$x$y$zStr"
//            }
//          }
//
//        val mapTiles = (json \ "screenInfo" \ "mapPanelLoc").as[Map[String, JsObject]]
//        mapTiles.values
//          .find(obj => (obj \ "id").asOpt[String] == tileIdOpt)
//          .flatMap { obj =>
//            for {
//              x <- (obj \ "x").asOpt[Int]
//              y <- (obj \ "y").asOpt[Int]
//            } yield (x, y)
//          }
//      }
//    }
//  }
//
//  private object TargetMarking extends Step {
//    override def run(state: GameState, json: JsValue, settings: UISettings): Option[(GameState, MKTask)] = {
//      val taskName = "TargetMarking"
//      val autoTarget = state.autoTarget
//      val currentTime = System.currentTimeMillis()
//      val markRetryIntervalMs = 2000L
//
//      if (autoTarget.stateMarkingTarget != "target marking" || autoTarget.chosenTargetId == 0) {
//        return Some(state -> NoOpTask)
//      }
//
//      // Throttling check
//      if (currentTime - autoTarget.lastTargetActionTime < autoTarget.targetActionThrottle) {
//        return Some((state, NoOpTask))
//      }
//
//      val shouldProceed =  autoTarget.stateAutoTarget == "fight" && // true
//        !isTargetCurrentlyMarked(autoTarget.chosenTargetId, json) && // true
//        autoTarget.chosenTargetId != 0 && // true
//        (currentTime - autoTarget.lastTargetMarkCommandSend >= markRetryIntervalMs) // true
//
//      if (!shouldProceed) {
//        return Some((state, NoOpTask))
//      }
//
//      println(s"[$taskName] Entered function.")
//
//      val chosenId = autoTarget.chosenTargetId
//
//      // Find creature's UPDATED position again
//      getCreaturePosition(chosenId, json) match {
//        case Some((x, y)) =>
//          val mouseActions = List(
//            MoveMouse(x, y),
//            LeftButtonPress(x, y),
//            LeftButtonRelease(x, y)
//          )
//
//          val newState = state.copy(autoTarget = autoTarget.copy(
//            stateMarkingTarget = "free",
//            lastTargetActionTime = currentTime
//          ))
//
//          Some((newState, MKTask("target marking", MKActions(mouse = mouseActions, keyboard = Nil))))
//
//        case None =>
//          println(s"[$taskName] Creature location not found.")
//          Some((state.copy(autoTarget = autoTarget.copy(stateMarkingTarget = "free")), NoOpTask))
//      }
//    }
//
//        private def isTargetCurrentlyMarked(targetId: Int, json: JsValue): Boolean = {
//          (json \ "attackInfo" \ "Id").asOpt[Int].contains(targetId)
//        }
//
//    private def getCreaturePosition(chosenId: Int, json: JsValue): Option[(Int, Int)] = {
//      // Same logic as TargetFocusing - find updated position
//      val battlePanelResult = for {
//        posObj <- (json \ "screenInfo" \ "battlePanelLoc" \ chosenId.toString).asOpt[JsObject]
//        x <- (posObj \ "PosX").asOpt[Int]
//        y <- (posObj \ "PosY").asOpt[Int]
//      } yield (x, y)
//
//      battlePanelResult.orElse {
//        val battleInfo = (json \ "battleInfo").as[Map[String, JsValue]]
//        val tileIdOpt = battleInfo.values
//          .find(obj => (obj \ "Id").asOpt[Int].contains(chosenId))
//          .flatMap { obj =>
//            for {
//              x <- (obj \ "PositionX").asOpt[Int]
//              y <- (obj \ "PositionY").asOpt[Int]
//              z <- (obj \ "PositionZ").asOpt[Int]
//            } yield {
//              val zStr = f"$z%02d"
//              s"$x$y$zStr"
//            }
//          }
//
//        val mapTiles = (json \ "screenInfo" \ "mapPanelLoc").as[Map[String, JsObject]]
//        mapTiles.values
//          .find(obj => (obj \ "id").asOpt[String] == tileIdOpt)
//          .flatMap { obj =>
//            for {
//              x <- (obj \ "x").asOpt[Int]
//              y <- (obj \ "y").asOpt[Int]
//            } yield (x, y)
//          }
//      }
//    }
//  }
//


//  private object TargetMarking extends Step {
//    private val taskName = "TargetMarking"
//    private val markRetryIntervalMs = 2000L // Retry marking every 7 seconds
//
//    override def run(state: GameState, json: JsValue, settings: UISettings): Option[(GameState, MKTask)] = {
//
//      val at = state.autoTarget
//      val now = System.currentTimeMillis()
//      val currentAttackId = (json \ "attackInfo" \ "Id").asOpt[Int]
//
//      if (at.chosenTargetId == 0 ) {
//        return Some(state -> NoOpTask)
//      }
//
//      println(s"now: $now, last: ${state.autoTarget.lastTargetMarkCommandSend}, diff: ${now - state.autoTarget.lastTargetMarkCommandSend}")
//
//      val shouldProceed =  at.stateAutoTarget == "fight" && // true
//        !isTargetCurrentlyMarked(at.chosenTargetId, json) && // true
//        at.chosenTargetId != 0 && // true
//        (now - at.lastTargetMarkCommandSend >= markRetryIntervalMs) // true
//
//      if (!shouldProceed) {
////        val targetCreatureId = (json \ "attackInfo" \ "Id").asOpt[Int]
////        val timeSinceLastMark = now - at.lastTargetMarkCommandSend
//        return Some((state, NoOpTask))
//      }
//
//      println(s"[TargetMarking] Entered function.")
//
//      val chosenId = at.chosenTargetId
//      val chosenName = at.chosenTargetName
//
//
//      println(s"[TargetMarking] chosenId=$chosenId, chosenName=$chosenName, currentTarget=$currentAttackId")
//
//      // Look up creature settings
//      println(s"[TargetMarking] looking up settings for creature '$chosenName'")
//      val creatureSettingsOpt = settings.autoTargetSettings.creatureList
//        .map(parseCreature)
//        .find(_.name.equalsIgnoreCase(chosenName))
//
//      println(s"[TargetMarking] settings lookup result=$creatureSettingsOpt")
//
//      val (markOnBattle, markOnScreen) = creatureSettingsOpt match {
//        case Some(cs) => (cs.targetBattle, cs.targetScreen)
//        case None => (false, false)
//      }
//
//      println(s"[TargetMarking] markOnBattle=$markOnBattle, markOnScreen=$markOnScreen")
//
//      if (!markOnBattle && !markOnScreen) {
//        println(s"[TargetMarking] creature $chosenName not configured for marking")
//        return None
//      }
//
//
//      // Choose marking method
//      val useScreen = if (markOnBattle && markOnScreen) {
//        val choice = Random.nextBoolean()
//        println(s"[TargetMarking] both methods available, randomly choosing ${if (choice) "SCREEN" else "BATTLE"} mark for $chosenName")
//        choice
//      } else markOnScreen
//
//      // Generate click actions (but keep them commented out for simulation)
//      val clickActions = if (useScreen) {
//        println(s"[TargetMarking] markOnScreenUI() called for id=$chosenId")
//        markOnScreenUI(chosenId, json)
//      } else {
//        println(s"[TargetMarking] markOnBattleUI() called for id=$chosenId")
//        markOnBattleUI(chosenId, json)
//      }
//
//      println(s"[TargetMarking] clickActions generated: $clickActions")
//
//      if (clickActions.nonEmpty) {
//        val newAutoTarget = at.copy(
//          lastTargetMarkCommandSend = now,
//          lastMarkingAttemptedId = chosenId
//        )
//        println(s"[TargetMarking] updated autoTarget: $newAutoTarget")
//
//        val task = MKTask(taskName, MKActions(clickActions, Nil))
//        Some(state.copy(autoTarget = newAutoTarget) -> task)
//      } else {
//        println(s"[TargetMarking] failed to generate click actions for $chosenId")
//        None
//      }
//    }
//
//    private def isTargetCurrentlyMarked(targetId: Int, json: JsValue): Boolean = {
//      (json \ "attackInfo" \ "Id").asOpt[Int].contains(targetId)
//    }
//
//    private def markOnBattleUI(chosenId: Int, json: JsValue): List[MouseAction] = {
//      println(s"[TargetMarking] markOnBattleUI() called for id=$chosenId")
//      val result = for {
//        posObj <- (json \ "screenInfo" \ "battlePanelLoc" \ chosenId.toString).asOpt[JsObject]
//          x <- (posObj \"PosX").asOpt[Int]
//        y <- (posObj \"PosY").asOpt[Int]
//      } yield {
//        println(s"[TargetMarking] battle UI position x=$x, y=$y")
//        List(
//          MoveMouse(x, y),
//          LeftButtonPress(x, y),
//          LeftButtonRelease(x, y)
//        )
//      }
//      val actions = result.getOrElse {
//        println(s"[TargetMarking] failed to find battle UI position for $chosenId")
//        Nil
//      }
//      println(s"[TargetMarking] markOnBattleUI actions: $actions")
//      actions
//    }
//
//    private def markOnScreenUI(chosenId: Int, json: JsValue): List[MouseAction] = {
//      println(s"[TargetMarking] markOnScreenUI() called for id=$chosenId")
//      val battleInfo = (json \"battleInfo").as[Map[String, JsValue]]
//      val tileIdOpt = battleInfo.values
//        .find(obj => (obj \ "Id").asOpt[Int].contains(chosenId))
//        .flatMap { obj =>
//          for {
//            x <- (obj \ "PositionX").asOpt[Int]
//            y <- (obj \ "PositionY").asOpt[Int]
//            z <- (obj \ "PositionZ").asOpt[Int]
//          } yield {
//            // pad Z to 2 digits if needed
//            val zStr = f"$z%02d"
//            s"$x$y$zStr"
//          }
//        }
//
//
//      println(s"[TargetMarking] computed tileIdOpt=$tileIdOpt")
//
//      val mapTiles = (json \"screenInfo" \ "mapPanelLoc").as[Map[String, JsObject]]
//      val targetOpt = mapTiles.values.find(obj => (obj \"id").asOpt[String] == tileIdOpt)
//      println(s"[TargetMarking] found map panel tile object: $targetOpt")
//
//      val actions = targetOpt.flatMap { obj =>
//        for {
//          x <- (obj \"x").asOpt[Int]
//          y <- (obj \"y").asOpt[Int]
//        } yield {
//          println(s"[TargetMarking] screen UI position x=$x, y=$y")
//          List(
//            MoveMouse(x, y),
//            RightButtonPress(x, y),
//            RightButtonRelease(x, y)
//          )
//        }
//      }.getOrElse {
//        println(s"[TargetMarking] failed to find screen position for $chosenId")
//        Nil
//      }
//      println(s"[TargetMarking] markOnScreenUI actions: $actions")
//      actions
//    }
//
//  }



  private object EngageAttack extends Step {
    private val taskName = "EngageAttack"

    override def run(state: GameState, json: JsValue, settings: UISettings): Option[(GameState, MKTask)] = {
      val currentTime = System.currentTimeMillis()
      val at = state.autoTarget

      if ((state.autoTarget.stateAutoTarget == "stop") || state.autoTarget.chosenTargetId == 0) return None

      println(s"[$taskName] Entered function.")

      val chosenId = at.chosenTargetId
      val presentLoc = Vec(
        (json \ "characterInfo" \ "PositionX").as[Int],
        (json \ "characterInfo" \ "PositionY").as[Int]
      )
      val battleInfo = (json \ "battleInfo").as[Map[String, JsValue]]
      val creatureOpt = findCreatureInfoById(chosenId, battleInfo, settings)

      if (creatureOpt.isEmpty) {
        println(s"[$taskName] Chosen target ID $chosenId not found in battleInfo.")
        return Some((state, NoOpTask))
      }

      val st2 = state
      val trapMouse = Nil
      val trapKeys = Nil



      if ((currentTime - at.lastRuneUseTime) < (at.runeUseCooldown + at.runeUseRandomness) ) {
        return None
      }
      val currentAttackId = (json \ "attackInfo" \ "Id").asOpt[Int]

      // Check if ANY creature has useRune enabled before calling runRuneAttack
      val anyCreatureUsesRune = settings.autoTargetSettings.creatureList
        .map(parseCreature)
        .exists(_.useRune)

      val (st3, runeTask) = if (anyCreatureUsesRune) {
        creatureOpt match {
          case Some(creature) =>
            if (settings.hotkeyMode && currentAttackId.isDefined) {
              runRuneAttackWithHotkey(st2, json, settings).getOrElse((st2, MKTask("empty", MKActions(Nil, Nil))))
            } else {
              runRuneAttack(st2, json, settings, creature).getOrElse((st2, MKTask("empty", MKActions(Nil, Nil))))
            }

          case None =>
            (st2, NoOpTask)
        }
      } else {
        printInColor(ANSI_BLUE, s"[$taskName] No creatures configured to use runes, skipping")
        (st2, NoOpTask)
      }

      val runeMouse = runeTask.actions.mouse
      val runeKeys = runeTask.actions.keyboard

      val allMouse = trapMouse ++ runeMouse
      val allKeys = trapKeys ++ runeKeys
      val task = MKTask(taskName, MKActions(allMouse, allKeys))

      Some(st3 -> task)
    }
  }

  // Wherever 'runRuneAttackWithHotkey' is defined
  private def runRuneAttackWithHotkey(
                                       state: utils.GameState,
                                       json: play.api.libs.json.JsValue,
                                       settings: utils.SettingsUtils.UISettings
                                     ): Option[(utils.GameState, MKTask)] = {
    if (!settings.hotkeyMode) return None

    import play.api.libs.json.JsObject
    import keyboard.{GeneralKey, KeyboardAction, KeyboardUtils, PressShift, ReleaseShift}

    def preferSingleKey(keys: List[String]): Option[String] =
      keys.sortBy(k => if (k.contains("+")) 1 else 0).headOption

    // Build press/release sequence with a provided meta id
    def buildHotkeyActions(hotkey: String, metaId: String): List[KeyboardAction] = {
      val parts = hotkey.split("\\+").toList
      val base  = parts.lastOption.getOrElse(hotkey)
      val mods  = parts.dropRight(1).map(_.toLowerCase)

      val pressMods: List[KeyboardAction] = mods.flatMap {
        case "shift" => Some(PressShift(metaId))
        case _       => None
      }
      val baseAction: KeyboardAction = GeneralKey(KeyboardUtils.fromHotkeyString(base), metaId)
      val releaseMods: List[KeyboardAction] = mods.reverse.flatMap {
        case "shift" => Some(ReleaseShift(metaId))
        case _       => None
      }
      pressMods ++ List(baseAction) ++ releaseMods
    }
    val metaId: String = (json \ "metaGeneratedId").asOpt[String].getOrElse("null")

    val runeIdOpt: Option[Int] = {
      val useRuneYes = settings.autoTargetSettings.creatureList.find(_.contains("Use Rune: Yes"))
      useRuneYes.flatMap { line =>
        val rt = """.*Rune Type:\s*([^,]+).*""".r
        line match {
          case rt(tpe) => "\\d+".r.findFirstIn(tpe).map(_.toInt)
          case _       => None
        }
      }
    }

    val hotkeysObjOpt = (json \ "hotkeysBinds").asOpt[JsObject]
    val chosen: Option[(Int, String)] = runeIdOpt.flatMap { itemId =>
      val candidates: List[String] = hotkeysObjOpt.toList.flatMap { obj =>
        obj.fields.collect {
          case (k, v) if k.endsWith("_itemId") && v.asOpt[Int].contains(itemId) =>
            k.stripSuffix("_itemId")
        }
      }
      preferSingleKey(candidates).map(key => (itemId, key))
    }

    chosen.map { case (itemId, keyStr) =>
      val keyboardActions = buildHotkeyActions(keyStr, metaId)
      val taskName = s"RunRuneAttackWithHotkey - item_$itemId"
      state -> MKTask(taskName, MKActions(Nil, keyboardActions))
    }
  }

  private def runRuneAttack(
                             state: GameState,
                             json: JsValue,
                             settings: UISettings,
                             creature: CreatureInfo
                           ): Option[(GameState, MKTask)] = {

    val at = state.autoTarget
    val characterInfo = json \ "characterInfo"
    val isCrosshairActive = (characterInfo \ "IsCrosshairActive").asOpt[Boolean].getOrElse(false)
    val currentTime = System.currentTimeMillis()
    val metaId: String = (json \ "metaGeneratedId").asOpt[String].getOrElse("null")

    println(s"[runRuneAttack] Entered function with mode=${at.attackRuneMode}, crosshairActive=$isCrosshairActive")
    println(s"[runRuneAttack] at.currentAutoAttackContainerName=${at.currentAutoAttackContainerName}, crosshairActive=$isCrosshairActive")
    println(s"[runRuneAttack] chosenTargetId=${at.chosenTargetId}, currentTime=$currentTime")

    // Get current creature info and map position
    val battleInfo = (json \ "battleInfo").as[Map[String, JsValue]]
    println(s"[runRuneAttack] battleInfo keys: ${battleInfo.keys}")

    val creatureOpt = findCreatureInfoById(at.chosenTargetId, battleInfo, settings)
    println(s"[runRuneAttack] creatureOpt result: $creatureOpt")

    creatureOpt match {
      case Some(updatedCreature) =>
        println(s"[runRuneAttack] Found creature: $updatedCreature")

        // Get creature's battle panel position instead of 8x6
        val creaturePositionOpt = for {
          battleObj <- (json \ "screenInfo" \ "battlePanelLoc" \ at.chosenTargetId.toString).asOpt[JsObject]
          mx <- (battleObj \ "PosX").asOpt[Int]
          my <- (battleObj \ "PosY").asOpt[Int]
        } yield (mx, my)

        creaturePositionOpt match {
          case Some((mx, my)) =>
            println(s"[runRuneAttack] Creature battle position: mx=$mx, my=$my")

            at.attackRuneMode match {
              case "free" =>
                println(s"[runRuneAttack] Processing 'free' mode")

                // Check if too soon since last action
                val timeSinceLastAction = currentTime - at.lastEngageAttackActionTime

                if (timeSinceLastAction < at.engageAttackActionThrottle) {
                  println(s"[runRuneAttack] Too soon since last action → NoOp (${timeSinceLastAction}ms < ${at.engageAttackActionThrottle}ms)")
                  return Some((state, MKTask("NoOp", MKActions(Nil, Nil))))
                }

                // Phase 1: Right-click rune and prepare for targeting
                val invPanel = (json \ "screenInfo" \ "inventoryPanelLoc").asOpt[JsObject]
                println(s"[runRuneAttack] inventoryPanelLoc available: ${invPanel.isDefined}")

                val runeCoords = for {
                  contName <- Some(at.currentAutoAttackContainerName)
                  _ = println(s"[runRuneAttack] Looking for container: $contName")
                  invPanel <- (json \ "screenInfo" \ "inventoryPanelLoc").asOpt[JsObject]
                  _ = println(s"[runRuneAttack] Available inventory keys: ${invPanel.keys}")
                  runeKey <- invPanel.keys.find(_.contains(contName))
                  _ = println(s"[runRuneAttack] Found matching runeKey: $runeKey")
                  item <- (invPanel \ runeKey \ "contentsPanel" \ "item0").asOpt[JsObject]
                  _ = println(s"[runRuneAttack] Found item0: $item")
                  rx <- (item \ "x").asOpt[Int]
                  ry <- (item \ "y").asOpt[Int]
                  _ = println(s"[runRuneAttack] Rune coordinates: rx=$rx, ry=$ry")
                } yield (rx, ry)

                println(s"[runRuneAttack] Final runeCoords result: $runeCoords")

                runeCoords match {
                  case Some((rx, ry)) =>
                    println(s"[runRuneAttack] Creating mouse actions for rune at ($rx, $ry)")
                    val mouseActions = List(
                      MoveMouse(rx, ry, metaId),
                      RightButtonPress(rx, ry, metaId),
                      RightButtonRelease(rx, ry, metaId),
                      MoveMouse(mx, my, metaId)
                    )
                    println(s"[runRuneAttack] Mouse actions created: $mouseActions")

                    val newAT = at.copy(
                      attackRuneMode = "engaged",
                      lastEngageAttackActionTime = currentTime
                    )
                    println(s"[runRuneAttack] Updating attackRuneMode to 'engaged'")

                    val newState = state.copy(autoTarget = newAT)
                    Some(newState -> MKTask("runRuneAttack - free_phase", MKActions(mouseActions, Nil)))

                  case None =>
                    println("[runRuneAttack] Could not find rune coordinates - returning None")
                    None
                }

              case "engaged" =>
                println(s"[runRuneAttack] Processing 'engaged' mode, crosshairActive=$isCrosshairActive")


                // Check if crosshair is active - if not, something went wrong
                if (!isCrosshairActive) {
                  println(s"[runRuneAttack] Crosshair not active")

                    // Check if too soon since last action
                  val timeSinceLastAction = currentTime - at.lastEngageAttackActionTime
                  if (timeSinceLastAction < 1500L) {
                    println(s"[runRuneAttack] Lets wait for crosshair → NoOp (${timeSinceLastAction}ms < 1500 ms)")
                    return Some((state, MKTask("NoOp", MKActions(Nil, Nil))))
                  } else {
                    val newAT = at.copy(attackRuneMode = "free")
                    val newState = state.copy(autoTarget = newAT)
                    Some(newState -> MKTask("runRuneAttack - crosshair_reset", MKActions.empty))
                  }
                } else {
                  println(s"[runRuneAttack] Crosshair active - proceeding with left-click at creature position ($mx, $my)")

                  val timeSinceLastRuneUsage = currentTime - at.lastRuneUseTime
                  if (timeSinceLastRuneUsage < at.runeUseCooldown) {
                    println(s"[runRuneAttack] Still on attack rune cooldown → NoOp (${timeSinceLastRuneUsage} ms < ${at.runeUseCooldown} ms)")
                    return Some((state, MKTask("NoOp", MKActions(Nil, Nil))))
                  }
                  println(s"[runRuneAttack]  attackTEST  (${timeSinceLastRuneUsage} ms > ${at.runeUseCooldown} ms)")
                  val metaId: String = (json \ "metaGeneratedId").asOpt[String].getOrElse("null")
                  // Phase 2: Left-click at creature's battle position
                  val mouseActions = List(
                    MoveMouse(mx, my, metaId),
                    LeftButtonPress(mx, my, metaId),
                    LeftButtonRelease(mx, my, metaId)
                  )
                  println(s"[runRuneAttack] Final attack mouse actions: $mouseActions")

                  val newAT = at.copy(
                    attackRuneMode = "free",
                    lastRuneUseTime = currentTime
                  )


                  val newState = state.copy(autoTarget = newAT)
                  Some(newState -> MKTask("runRuneAttack - engaged_phase", MKActions(mouseActions, Nil)))
                }

              case unknownMode =>
                println(s"[runRuneAttack] Unknown attackRuneMode: '$unknownMode' - resetting to 'free'")

                // Unknown mode, reset to free
                val newAT = at.copy(
                  attackRuneMode = "free",
                  lastEngageAttackActionTime = currentTime
                )
                val newState = state.copy(autoTarget = newAT)
                Some(newState -> MKTask("runRuneAttack - unknown_reset", MKActions.empty))
            }

          case None =>
            println(s"[runRuneAttack] Could not find creature battle position for ID ${at.chosenTargetId}")
            None
        }

      case None =>
        println(s"[runRuneAttack] Target creature not found - chosenTargetId=${at.chosenTargetId}")
        println(s"[runRuneAttack] Available battleInfo creature IDs: ${battleInfo.values.flatMap(v => (v \ "Id").asOpt[Int]).toList}")
        None
    }
  }

//  private def runRuneAttack(
//                             creatureData: CreatureInfo,
//                             json:         JsValue,
//                             state:        GameState,
//                             settings:     UISettings
//                           ): (GameState, MKTask) = {
//
//    val chosenId   = creatureData.id
//    val battleInfo = (json \ "battleInfo").as[Map[String, JsValue]]
//    val cfg        = effectiveRuneSettings(creatureData, battleInfo, settings)
//    val now        = System.currentTimeMillis()
//
//    println(s"[RuneAttack] settings: useBattle=${cfg.useRuneOnBattle}, useScreen=${cfg.useRuneOnScreen}")
//
//    // 1) Battle rune attack
//    val battleOpt: Option[(GameState, List[MouseAction])] =
//      if (cfg.useRuneOnBattle &&
//        now - state.autoTarget.lastRuneUseTime >= state.autoTarget.runeUseCooldown + state.autoTarget.runeUseRandomness
//      ) {
//        for {
//          runeStr  <- cfg.runeType
//          runeID   <- runeStr.split('.').lastOption.flatMap(_.toIntOption)
//          contName <- state.autoTarget.autoTargetContainerMapping.get(runeID)
//          slot     <- (0 until 4).find { i =>
//            (json \ "containersInfo" \ contName \ "items" \ s"slot$i" \ "itemId").asOpt[Int].contains(runeID)
//          }
//          invPanel <- (json \ "screenInfo" \ "inventoryPanelLoc").asOpt[JsObject]
//          panelKey <- invPanel.keys.find(_.contains(contName))
//          contents <- (invPanel \ panelKey \ "contentsPanel").asOpt[JsObject]
//          runeObj  <- (contents \ s"item$slot").asOpt[JsObject]
//          rx       <- (runeObj \ "x").asOpt[Int]
//          ry       <- (runeObj \ "y").asOpt[Int]
//          battleObj<- (json \ "screenInfo" \ "battlePanelLoc" \ s"$chosenId").asOpt[JsObject]
//          mx       <- (battleObj \ "PosX").asOpt[Int]
//          my       <- (battleObj \ "PosY").asOpt[Int]
//          if mx >= 2 && my >= 2
//        } yield {
//          println(s"[RuneAttack] runeStr: $runeStr, parsed runeID: $runeID")
//          println(s"[RuneAttack] containerMapping: ${state.autoTarget.autoTargetContainerMapping}")
//          println(s"[RuneAttack] container slot check: slot $slot")
//          println(s"[RuneAttack] screen positions: rune=($rx,$ry), target=($mx,$my)")
//
//          val actions = List(
//            MoveMouse(rx, ry),
//            RightButtonPress(rx, ry),
//            RightButtonRelease(rx, ry),
//            MoveMouse(mx, my),
//            LeftButtonPress(mx, my),
//            LeftButtonRelease(mx, my)
//          )
//
//          val rnd      = generateRandomDelay(state.autoTarget.runeUseTimeRange)
//          val newAT    = state.autoTarget.copy(lastRuneUseTime = now, runeUseRandomness = rnd)
//          val newState = state.copy(autoTarget = newAT)
//          (newState, actions)
//        }
//      } else None
//
//    val (afterBattleState, battleMouse) = battleOpt.getOrElse((state, List.empty))
//
//    // 2) Screen rune attack
//    val screenMouse: List[MouseAction] =
//      if (cfg.useRuneOnScreen &&
//        now - state.autoTarget.lastRuneUseTime >= state.autoTarget.runeUseCooldown + state.autoTarget.runeUseRandomness
//      ) {
//        println("[RuneAttack-SCREEN] Attempting screen rune attack")
//
//        (for {
//          runeStr  <- cfg.runeType
//          _ = println(s"[RuneAttack-SCREEN] runeType from cfg: $runeStr")
//
//          runeID   <- runeStr.split('.').lastOption.flatMap(_.toIntOption)
//          _ = println(s"[RuneAttack-SCREEN] parsed runeID: $runeID")
//
//          contName <- state.autoTarget.autoTargetContainerMapping.get(runeID)
//          _ = println(s"[RuneAttack-SCREEN] container name for runeID: $contName")
//
//          invPanel <- (json \ "screenInfo" \ "inventoryPanelLoc").asOpt[JsObject]
//          _ = println(s"[RuneAttack-SCREEN] invPanel keys: ${invPanel.keys}")
//
//          panelKey <- invPanel.keys.find(_.contains(contName))
//          _ = println(s"[RuneAttack-SCREEN] matched panelKey: $panelKey")
//
//          contents <- (invPanel \ panelKey \ "contentsPanel").asOpt[JsObject]
//          _ = println(s"[RuneAttack-SCREEN] contents keys: ${contents.keys}")
//
//          runeObj  <- (contents \ "item0").asOpt[JsObject]
//          rx       <- (runeObj \ "x").asOpt[Int]
//          ry       <- (runeObj \ "y").asOpt[Int]
//          _ = println(s"[RuneAttack-SCREEN] Rune screen position: ($rx,$ry)")
//
//          // Extract tile screen position
//          tileId   = s"${creatureData.posX}${creatureData.posY}${creatureData.posZ}"
//          tileRaw  = (json \ "areaInfo" \ "tiles" \ tileId)
//          _ = println(s"[RuneAttack-SCREEN] Looking for tileId: $tileId")
//
//          val mapPanel = (json \ "screenInfo" \ "mapPanelLoc").as[Map[String, JsObject]]
//          val screenTargetOpt = mapPanel.values.find(obj => (obj \ "id").asOpt[String].contains(tileId))
//
//          tx <- screenTargetOpt.flatMap(obj => (obj \ "x").asOpt[Int])
//          ty <- screenTargetOpt.flatMap(obj => (obj \ "y").asOpt[Int])
//          _ = println(s"[RuneAttack-SCREEN] Target screen position: ($tx,$ty)")
//
//        } yield {
//          val actions = List(
//            MoveMouse(rx, ry),
//            RightButtonPress(rx, ry),
//            RightButtonRelease(rx, ry),
//            MoveMouse(tx, ty),
//            LeftButtonPress(tx, ty),
//            LeftButtonRelease(tx, ty)
//          )
//          val rnd      = generateRandomDelay(state.autoTarget.runeUseTimeRange)
//          val newAT    = state.autoTarget.copy(lastRuneUseTime = now, runeUseRandomness = rnd)
//          val newState = state.copy(autoTarget = newAT)
//          return (newState, MKTask("runeAttack", MKActions(actions, Nil)))
//        }).getOrElse {
//          println("[RuneAttack-SCREEN] ❌ Missing data to cast rune on screen.")
//          List.empty
//        }
//      } else {
//        println("[RuneAttack-SCREEN] Skipped (either flag false or cooldown not ready)")
//        List.empty
//      }
//
//
//
//    val task = MKTask("runeAttack", MKActions(battleMouse ++ screenMouse, Nil))
//    (afterBattleState, task)
//  }



  private def effectiveRuneSettings(
                                     creature:    CreatureInfo,
                                     battleInfo:  Map[String, JsValue],
                                     settings:    UISettings
                                   ): CreatureSettings = {
    // 1) parse all settings into a Map[name -> CreatureSettings]
    val settingMap: Map[String, CreatureSettings] =
      settings.autoTargetSettings.creatureList
        .map(parseCreature)
        .map(cs => cs.name -> cs)
        .toMap

    // 2) find the raw CreatureSettings (or default)
    val base: CreatureSettings =
      settingMap.getOrElse(
        creature.name,
        CreatureSettings(
          name                   = creature.name,
          count                  = 1,
          hpFrom                 = 0,
          hpTo                   = 100,
          danger                 = 0,
          targetBattle           = false,
          targetScreen           = false,
          chase                  = false,
          keepDistance           = false,
          avoidWaves             = false,
          useRune                = false,
          runeType               = None,
          useRuneOnScreen        = false,
          useRuneOnBattle        = false,
          lootMonsterImmediately = false,
          lootMonsterAfterFight  = false,
          lureCreatureToTeam     = false
        )
      )

    // 3) count how many are in battle
    val sameCount = battleInfo.values.count { js =>
      (js \ "Name").asOpt[String].contains(creature.name)
    }
    println(s"[DEBUG] Found $sameCount of '${creature.name}' in battle; threshold = ${base.count}")

    // 4) only allow rune usages if count >= threshold
    base.copy(
      useRuneOnBattle = base.useRuneOnBattle && sameCount >= base.count,
      useRuneOnScreen = base.useRuneOnScreen && sameCount >= base.count
    )
  }


  private def getClosestTeamMemberPosition(
                                            json:            JsValue,
                                            blockerPosition: Vec,
                                            settings:        UISettings
                                          ): Option[Vec] = {
    // 1) pull battleInfo
    val battleInfo: Map[String, JsValue] =
      (json \ "battleInfo").as[Map[String, JsValue]]

    // 2) your configured team member names
    val teamList: Set[String] =
      settings.teamHuntSettings.teamMembersList.toSet

    println(s"[DEBUG] Team members to consider: $teamList")
    println(s"[DEBUG] Blocker at: $blockerPosition")

    // 3) collect all valid team‐member positions with their Chebyshev distance
    val candidates: List[(Vec, Int)] = battleInfo.toList.flatMap {
      case (_, data) =>
        val nameOpt   = (data \ "Name").asOpt[String]
        val isPlayer  = (data \ "IsPlayer").asOpt[Boolean].getOrElse(false)

        nameOpt.filter(name => isPlayer && teamList.contains(name)).map { name =>
          val x = (data \ "PositionX").asOpt[Int].getOrElse(0)
          val y = (data \ "PositionY").asOpt[Int].getOrElse(0)
          val pos = Vec(x, y)
          val dist = math.max(
            math.abs(x - blockerPosition.x),
            math.abs(y - blockerPosition.y)
          )
          println(s"[DEBUG] Candidate team member '$name' at $pos, distance = $dist")
          pos -> dist
        }
    }

    // 4) pick the one with minimum distance, if any
    val closestOpt: Option[Vec] =
      candidates.minByOption(_._2).map(_._1)

    println(s"[DEBUG] Closest team member position: $closestOpt")
    closestOpt
  }


  private object UnmarkPlayer extends Step {
    private val taskName = "UnmarkPlayer"

    override def run(
                      state: GameState,
                      json: JsValue,
                      settings: UISettings
                    ): Option[(GameState, MKTask)] = {
      val at = state.autoTarget
      val now = System.currentTimeMillis()
      println(s"[$taskName] stateAutoTarget: ${state.autoTarget.stateAutoTarget}")

      // Check throttle
      if (now - at.updateAttackChangeTime < at.updateAttackThrottleTime) {
        println(s"[$taskName] Holding unmarking action. Last change was recent")
        return Some(state -> NoOpTask)
      }

      // Only proceed if state is 'unmarking'
      if (at.stateAutoTarget != "unmarking") {
        return None
      }

      // Get the currently attacked player's ID
      val currentAttackId = (json \ "attackInfo" \ "Id").asOpt[Int]

      if (currentAttackId.isEmpty) {
        println(s"[$taskName] No attack target found")
        return Some(state -> NoOpTask)
      }

      val attackedId = currentAttackId.get
      println(s"[$taskName] Attempting to unmark player with ID: $attackedId")

      // Find the hotkey bound to "cancelattack"
      val hotkeysBinds = (json \ "hotkeysBinds").asOpt[Map[String, JsValue]].getOrElse(Map.empty)

      val cancelAttackKeyOpt = hotkeysBinds.collectFirst {
        case (key, value) if key.endsWith("_action") && value.asOpt[String].contains("cancelattack") =>
          key.replace("_action", "")
      }

      println(s"[$taskName] Found cancel attack key: $cancelAttackKeyOpt")
      val metaId: String = (json \ "metaGeneratedId").asOpt[String].getOrElse("null")

      val (keyboardActions, mouseActions) = cancelAttackKeyOpt match {
        case Some(hotkey) =>

          // Use keyboard action if hotkey is found
          val keys = parseHotkeyToActions(hotkey, metaId)
          println(s"[$taskName] Using keyboard action: $keys")
          (keys, Nil)

        case None =>
          // Fallback to mouse click if no hotkey found
          println(s"[$taskName] No 'cancelattack' hotkey found in bindings, falling back to mouse click")
          val clicks = getScreenPosition(attackedId, json) match {
            case Some((x, y)) =>
              println(s"[$taskName] Found screen position at ($x, $y)")
              List(
                MoveMouse(x, y, metaId),
                RightButtonPress(x, y, metaId),
                RightButtonRelease(x, y, metaId)
              )
            case None =>
              println(s"[$taskName] Could not find screen position for player $attackedId")
              Nil
          }
          (Nil, clicks)
      }

      if (keyboardActions.isEmpty && mouseActions.isEmpty) {
        println(s"[$taskName] No actions could be generated")
        return Some(state -> NoOpTask)
      }

      val updatedState = state.copy(autoTarget = at.copy(
        updateAttackChangeTime = now
      ))

      val task = MKTask(taskName, MKActions(mouseActions, keyboardActions))
      println(s"[$taskName] Unmarking task created with keyboard=${keyboardActions.nonEmpty}, mouse=${mouseActions.nonEmpty}")

      Some(updatedState -> task)
    }

    private def parseHotkeyToActions(hotkey: String, metaId: String): List[KeyboardAction] = {
      println(s"[$taskName] Parsing hotkey: $hotkey")

      if (hotkey.startsWith("Shift+")) {
        val baseKey = hotkey.replace("Shift+", "")
        println(s"[$taskName] Shift combination detected, base key: $baseKey")
        List(
          PressShift(metaId),
          GeneralKey(getKeyCode(baseKey), metaId),
          ReleaseShift(metaId)
        )
      } else {
        println(s"[$taskName] Single key: $hotkey")
        List(GeneralKey(getKeyCode(hotkey), metaId))
      }
    }

    private def getScreenPosition(targetId: Int, json: JsValue): Option[(Int, Int)] = {
      println(s"[$taskName] getScreenPosition: Looking for screen position of target $targetId")

      val battleInfo = (json \ "battleInfo").asOpt[Map[String, JsValue]].getOrElse(Map.empty)
      val tileIdOpt = battleInfo.values
        .find(obj => (obj \ "Id").asOpt[Int].contains(targetId))
        .flatMap { obj =>
          for {
            x <- (obj \ "PositionX").asOpt[Int]
            y <- (obj \ "PositionY").asOpt[Int]
            z <- (obj \ "PositionZ").asOpt[Int]
          } yield f"$x$y${z}%02d"
        }

      if (tileIdOpt.isEmpty) {
        println(s"[$taskName] getScreenPosition: Could not determine tileId for target $targetId")
        return None
      }

      val mapTiles = (json \ "screenInfo" \ "mapPanelLoc").asOpt[Map[String, JsObject]].getOrElse(Map.empty)

      val result = mapTiles.values.find(obj => (obj \ "id").asOpt[String] == tileIdOpt).flatMap { obj =>
        for {
          x <- (obj \ "x").asOpt[Int]
          y <- (obj \ "y").asOpt[Int]
        } yield (x, y)
      }

      if (result.isEmpty) {
        println(s"[$taskName] getScreenPosition: No screen position found for tileId ${tileIdOpt.get}")
      }

      result
    }
  }


  private object EngageMovement extends Step {
    private val taskName = "EngageMovement"

    override def run(state: GameState, json: JsValue, settings: UISettings): Option[(GameState, MKTask)] = {
      val at = state.autoTarget

      println(s"[EngageMovement] Activating with status: ${at.stateAutoTarget}")
      if (at.stateAutoTarget != "fight") return None

      val presentLoc = Vec(
        (json \ "characterInfo" \ "PositionX").as[Int],
        (json \ "characterInfo" \ "PositionY").as[Int]
      )
      println(s"[EngageMovement] presentLoc from JSON: $presentLoc")

      val battleInfo = (json \ "battleInfo").as[Map[String, JsValue]]
      val creatureOpt = findCreatureInfoById(at.chosenTargetId, battleInfo, settings)

      val (newState, task) = creatureOpt match {
        case Some(creature) =>
          runMovementToCreature(creature, presentLoc, json, state, settings)
        case None =>
          (state, NoOpTask)
      }

      Some(newState -> task)
    }
  }

  private def runMovementToCreature(
                                     creatureData: CreatureInfo,
                                     presentLoc: Vec,
                                     json: JsValue,
                                     state: GameState,
                                     settings: UISettings
                                   ): (GameState, MKTask) = {

    val baseState = state
    val csOpt = transformToObject(creatureData, settings.autoTargetSettings.creatureList)

    csOpt match {
      case None =>
        println(s"[Movement] no settings for ${creatureData.name}")
        (baseState, MKTask("empty", MKActions(Nil, Nil)))

      case Some(cs) =>
        val mode = cs.chase match {
          case true if cs.keepDistance => "lureCreatureToTeam"
          case true => "chase"
          case false if cs.keepDistance => "keepDistance"
          case _ => "free"
        }

        mode match {
          case "chase" =>
            println(s"[runMovementToCreature-chase] Processing chase for creature ${creatureData.id}")
            val (stateAfterChase, targetPos) = determineChaseTarget(
              Vec(creatureData.posX, creatureData.posY),
              presentLoc,
              state,
              System.currentTimeMillis(),
              creatureData.id
            )

            val (finalState, keys) = moveToTarget(targetPos, stateAfterChase, json)
            (finalState, MKTask("Chase", MKActions(Nil, keys)))

          case "keepDistance" =>
            println(s"[runMovementToCreature-chase] Processing keepDistance for creature ${creatureData.id}")
            val dist = math.max(
              math.abs(creatureData.posX - presentLoc.x),
              math.abs(creatureData.posY - presentLoc.y)
            )

            if (dist < 3) {
              val gs1 = generateSubwaypointsToEscape(
                creatureLocation = Vec(creatureData.posX, creatureData.posY),
                state = baseState,
                json = json
              )
              gs1.autoTarget.subWaypoints.headOption match {
                case Some(next) =>
                  val dirOpt = calculateDirection(presentLoc, next, gs1.characterInfo.lastDirection)
                  println(s"[runMovementToCreature] Calculated direction: $dirOpt from $presentLoc to $next")
                  val updatedChar = gs1.characterInfo.copy(
                    lastDirection = dirOpt,
                    presentCharLocation = presentLoc
                  )
                  val updatedAuto = gs1.autoTarget.copy(subWaypoints = gs1.autoTarget.subWaypoints.tail)
                  val updatedState = gs1.copy(characterInfo = updatedChar, autoTarget = updatedAuto)

                  val metaId: String = (json \ "metaGeneratedId").asOpt[String].getOrElse("null")
                  val keyboardActions = dirOpt.toList.map(d => keyboard.DirectionalKey(d, metaId))

                  (updatedState, MKTask("runMovementToCreature", MKActions(Nil, keyboardActions)))

                case None =>
                  println(s"[moveToTarget] No direction found from $presentLoc")
                  (gs1, NoOpTask)
              }
            } else {
              println("[Movement-keepDistance] safe distance")
              (baseState, NoOpTask)
            }

          case "lureCreatureToTeam" =>
            println(s"[runMovementToCreature-chase] Processing lureCreatureToTeam for creature ${creatureData.id}")
            val targetPos = getClosestTeamMemberPosition(json, Vec(creatureData.posX, creatureData.posY), settings)
              .getOrElse(Vec(creatureData.posX, creatureData.posY))

            val (newState, keys) = moveToTarget(targetPos, baseState, json)
            (newState, MKTask("LureToTeam", MKActions(Nil, keys)))

          case _ =>
            println("[Movement] no valid movement mode")
            (baseState, NoOpTask)
        }
    }
  }


  private def determineChaseTarget(
                                    creaturePos: Vec,
                                    playerPos: Vec,
                                    state: GameState,
                                    currentTime: Long,
                                    creatureId: Int
                                  ): (GameState, Vec) = {

    val at = state.autoTarget
    val historyRetentionMs = 1500L

    println(s"[Chase-Debug] Creature ID: $creatureId")

    // Get this creature's position history from the map
    val creatureHistory = at.creaturePositionHistory.getOrElse(creatureId, List.empty)
    println(s"[Chase-Debug] Current history size: ${creatureHistory.length}")
    println(s"[Chase-Debug] Current chase mode: ${at.chaseMode}")

    val workingHistory = creatureHistory

    // Analyze movement pattern and determine chase strategy
    val updatedChaseMode = if (workingHistory.size >= 2) {
      // Use the analyzeChaseMode method here
      val updatedMode = analyzeChaseMode(
        creaturePos,
        playerPos,
        workingHistory.map(_._1),
        at.chaseMode
      )
      updatedMode
    } else {
      println(s"[Chase-Debug] Not enough history (${workingHistory.size}), defaulting to chase_to")
      "chase_to"
    }

    println(s"[Chase] Mode: $updatedChaseMode, Character position: $playerPos, Current creature: $creaturePos")


    // Update the map with this creature's updated history
    val updatedHistoryMap = at.creaturePositionHistory.updated(creatureId, workingHistory)

    val updatedAT = at.copy(
      creaturePositionHistory = updatedHistoryMap,
      chaseMode = updatedChaseMode
    )

    (state.copy(autoTarget = updatedAT), creaturePos)
  }

  private def analyzeChaseMode(
                                currentCreaturePos: Vec,
                                characterPos: Vec,
                                positionHistory: List[Vec],
                                currentChaseMode: String  // Add current mode parameter
                              ): String = {

    val subtaskName = "analyzeChaseMode"
    val reachableDistance = 1 // Only immediate adjacent tiles are reachable (8 surrounding tiles)

    val currentDistance = chebyshevDistance(currentCreaturePos, characterPos)

    // If already in chase_after mode, STAY in chase_after mode (don't switch back)
    if (currentChaseMode == "chase_after") {
      println(s"[$subtaskName] Maintaining chase_after mode, distance: $currentDistance")
      "chase_after"
    } else {
      // Currently in chase_to mode, check if creature is running away
      if (positionHistory.size >= 2) {
        val previousPos = positionHistory(1)
        val prevDistance = chebyshevDistance(previousPos, characterPos)

        println(s"[$subtaskName] Previous distance: $prevDistance, Current distance: $currentDistance")

        if (currentDistance > prevDistance && currentDistance > reachableDistance) {
          println(s"[$subtaskName] Creature is running away! Distance increased from $prevDistance to $currentDistance, switching to chase_after")
          "chase_after"
        } else {
          println(s"[$subtaskName] Creature not running away, staying in chase_to mode")
          "chase_to"
        }
      } else {
        println(s"[$subtaskName] Not enough history (${positionHistory.size}), staying in chase_to")
        "chase_to"
      }
    }
  }


  private def analyzeCreatureMovement(
                                       creaturePos: Vec,
                                       state: GameState,
                                       currentTime: Long
                                     ): (String, Option[Vec], Vec) = {
    // Get the current creature's history from the map
    val creatureId = state.autoTarget.chosenTargetId
    val history = state.autoTarget.creaturePositionHistory.getOrElse(creatureId, List.empty)

    val isWithinAttackRange = chebyshevDistance(
      Vec(state.characterInfo.presentCharLocation.x, state.characterInfo.presentCharLocation.y),
      creaturePos
    ) <= 1

    println(s"[Chase-Analysis] Creature at $creaturePos, within attack range: $isWithinAttackRange")
    println(s"[Chase-Analysis] Position history size: ${history.length}")

    val result = if (isWithinAttackRange) {
      // Check for any movement immediately - don't wait for history to build up
      if (history.length >= 2) {
        val lastPos = history(1)._1  // Get the Vec from the tuple
        val vec = Vec(creaturePos.x - lastPos.x, creaturePos.y - lastPos.y)

        vec match {
          case Vec(0, 0) =>
            ("chase_after", None, creaturePos) // Creature is stationary, stay close
          case _ =>
            val predicted = Vec(creaturePos.x + vec.x, creaturePos.y + vec.y)
            ("chase_after", Some(vec), predicted) // Creature is moving, predict next position
        }
      } else {
        ("chase_after", None, creaturePos) // Not enough history, stay close
      }
    } else {
      ("chase_to", None, creaturePos) // Too far, chase directly
    }

    result
  }

  private def chebyshevDistance(pos1: Vec, pos2: Vec): Int = {
    val distance = math.max(math.abs(pos1.x - pos2.x), math.abs(pos1.y - pos2.y))
    println(s"[Chase-Distance] Distance between $pos1 and $pos2 = $distance")
    distance
  }

  private def executeMovementStep(state: GameState, json: JsValue): MKTask = {
    val px = (json \ "characterInfo" \ "PositionX").as[Int]
    val py = (json \ "characterInfo" \ "PositionY").as[Int]
    val presentLoc = Vec(px, py)

    // Use autoTarget subWaypoints (generated by generateSubwaypointsToCreature)
    state.autoTarget.subWaypoints.headOption match {
      case Some(next) =>
        val dirOpt = calculateDirection(presentLoc, next, state.characterInfo.lastDirection)
        println(s"[Chase] Moving from $presentLoc to $next, direction: $dirOpt")

        val metaId: String = (json \ "metaGeneratedId").asOpt[String].getOrElse("null")

        dirOpt match {
          case Some(direction) =>
            val key = DirectionalKey(direction, metaId)
            MKTask("chase_movement", MKActions(Nil, List(key)))
          case None =>
            MKTask("empty", MKActions(Nil, Nil))
        }
      case None =>
        println("[Chase] No waypoints available")
        MKTask("empty", MKActions(Nil, Nil))
    }
  }




  private object PrepareToLoot extends Step {
    private val taskName = "PrepareToLoot"

    def run(state: GameState, json: JsValue, settings: UISettings): Option[(GameState, MKTask)] = {
      val currentTime = System.currentTimeMillis()
      val autoTarget = state.autoTarget

      // Check if not in fight state
      if (autoTarget.stateAutoTarget != "fight") {
        return None
      }

      // Check throttling similar to autoLoot
      if (autoTarget.targetActionThrottle > currentTime - autoTarget.lastTargetActionTime) {
        println(s"[$taskName] Too soon since last action → NoOp")
        return Some(state -> NoOpTask)
      }

      // Get the current target ID
      val targetId = autoTarget.chosenTargetId
      if (targetId == 0) {
        return None
      }

      // Find the creature in battleInfo
      val battleInfo = (json \ "battleInfo").as[Map[String, JsValue]]
      val creatureOpt = battleInfo.values.find { creatureData =>
        (creatureData \ "Id").asOpt[Int].contains(targetId)
      }

      creatureOpt match {
        case Some(creatureData) =>
          val creatureName = (creatureData \ "Name").asOpt[String].getOrElse("")
          val healthPercent = (creatureData \ "HealthPercent").asOpt[Int].getOrElse(100)
          val isMonster = (creatureData \ "IsMonster").asOpt[Boolean].getOrElse(false)

          if (!isMonster || creatureName.isEmpty) {
            return None
          }

          // Check if creature has looting settings
          settings.autoTargetSettings.creatureList
            .map(parseCreature)
            .find(_.name.equalsIgnoreCase(creatureName)) match {

            case Some(creatureSettings) =>
              // Check if creature has any looting settings
              val hasLootSettings = creatureSettings.lootMonsterImmediately || creatureSettings.lootMonsterAfterFight

              if (!hasLootSettings) {
                return None
              }

              // For immediate looting or after fight looting with no monsters in battle
              val shouldPrepareLoot = if (creatureSettings.lootMonsterImmediately) {
                true
              } else if (creatureSettings.lootMonsterAfterFight) {
                // Check if there are other monsters in battle
                val hasOtherMonsters = battleInfo.values.exists { otherCreature =>
                  val otherId = (otherCreature \ "Id").asOpt[Int].getOrElse(0)
                  val otherIsMonster = (otherCreature \ "IsMonster").asOpt[Boolean].getOrElse(false)
                  otherIsMonster && otherId != targetId
                }
                !hasOtherMonsters
              } else {
                false
              }

              if (!shouldPrepareLoot) {
                return None
              }

              // Check if creature has less than 60% HP
              if (healthPercent < 20) {
                println(s"[$taskName] Creature $creatureName has $healthPercent% HP, preparing to loot")
                val metaId: String = (json \ "metaGeneratedId").asOpt[String].getOrElse("null")
                // Find creature position using similar logic to TargetFocusing
                getScreenPosition(targetId, json) match {
                  case Some((x, y)) =>
                    val mouseActions = List(MoveMouse(x, y, metaId))

                    val newState = state.copy(autoTarget = autoTarget.copy(
                      lastTargetActionTime = currentTime
                    ))

                    Some((newState, MKTask("prepare to loot", MKActions(mouse = mouseActions, keyboard = Nil))))

                  case None =>
                    println(s"[$taskName] Creature location not found")
                    None
                }
              } else {
                None
              }

            case None =>
              println(s"[$taskName] No looting settings found for creature: $creatureName")
              None
          }

        case None =>
          None
      }
    }

    private def getScreenPosition(chosenId: Int, json: JsValue): Option[(Int, Int)] = {
      val battleInfo = (json \ "battleInfo").asOpt[Map[String, JsValue]].getOrElse(Map.empty)
      val tileIdOpt = battleInfo.values
        .find(obj => (obj \ "Id").asOpt[Int].contains(chosenId))
        .flatMap { obj =>
          for {
            x <- (obj \ "PositionX").asOpt[Int]
            y <- (obj \ "PositionY").asOpt[Int]
            z <- (obj \ "PositionZ").asOpt[Int]
          } yield f"$x$y${z}%02d"
        }

      val mapTiles = (json \ "screenInfo" \ "mapPanelLoc").asOpt[Map[String, JsObject]].getOrElse(Map.empty)
      mapTiles.values.find(obj => (obj \ "id").asOpt[String] == tileIdOpt).flatMap { obj =>
        for {
          x <- (obj \ "x").asOpt[Int]
          y <- (obj \ "y").asOpt[Int]
        } yield (x, y)
      }
    }

    private def getCreaturePosition(chosenId: Int, json: JsValue): Option[(Int, Int)] = {
      // Try battle panel first
      val battlePanelResult = for {
        posObj <- (json \ "screenInfo" \ "battlePanelLoc" \ chosenId.toString).asOpt[JsObject]
        x <- (posObj \ "PosX").asOpt[Int]
        y <- (posObj \ "PosY").asOpt[Int]
      } yield (x, y)

      battlePanelResult.orElse {
        // Fallback to screen UI
        val battleInfo = (json \ "battleInfo").as[Map[String, JsValue]]
        val tileIdOpt = battleInfo.values
          .find(obj => (obj \ "Id").asOpt[Int].contains(chosenId))
          .flatMap { obj =>
            for {
              x <- (obj \ "PositionX").asOpt[Int]
              y <- (obj \ "PositionY").asOpt[Int]
              z <- (obj \ "PositionZ").asOpt[Int]
            } yield {
              val zStr = f"$z%02d"
              s"$x$y$zStr"
            }
          }

        val mapTiles = (json \ "screenInfo" \ "mapPanelLoc").as[Map[String, JsObject]]
        mapTiles.values
          .find(obj => (obj \ "id").asOpt[String] == tileIdOpt)
          .flatMap { obj =>
            for {
              x <- (obj \ "x").asOpt[Int]
              y <- (obj \ "y").asOpt[Int]
            } yield (x, y)
          }
      }
    }
  }

//  private def runMovementToCreature(
//                                     creatureData: CreatureInfo,
//                                     presentLoc:   Vec,
//                                     json:         JsValue,
//                                     state:        GameState,
//                                     settings:     UISettings
//                                   ): (GameState, MKTask) = {
//
//    val baseState = state
//
//    val csOpt = transformToObject(creatureData, settings.autoTargetSettings.creatureList)
//
//    csOpt match {
//      case None =>
//        println(s"[Movement] no settings for ${creatureData.name}")
//        (baseState, NoOpTask)
//
//      case Some(cs) =>
//        val mode =
//          if (cs.chase) "chase"
//          else if (cs.keepDistance) "keepDistance"
//          else if (cs.lureCreatureToTeam) "lureCreatureToTeam"
//          else "none"
//
//
//        mode match {
//          case "chase" =>
//            val target = Vec(creatureData.posX, creatureData.posY)
//            val (newState, keys) = moveToTarget(target, baseState, json)
//            (newState, MKTask("Chase", MKActions(Nil, keys)))
//
//          case "keepDistance" =>
//            val dist = math.max(
//              math.abs(creatureData.posX - presentLoc.x),
//              math.abs(creatureData.posY - presentLoc.y)
//            )
//
//            if (dist < 3) {
//              val gs1 = generateSubwaypointsToEscape(
//                creatureLocation = Vec(creatureData.posX, creatureData.posY),
//                state = baseState,
//                json = json
//              )
//              gs1.autoTarget.subWaypoints.headOption match {
//                case Some(next) =>
//                  val dirOpt = calculateDirection(presentLoc, next, gs1.characterInfo.lastDirection)
//                  println(s"[runMovementToCreature] Calculated direction: $dirOpt from $presentLoc to $next")
//                  val updatedChar = gs1.characterInfo.copy(
//                    lastDirection = dirOpt,
//                    presentCharLocation = presentLoc
//                  )
//                  val updatedAuto = gs1.autoTarget.copy(subWaypoints = gs1.autoTarget.subWaypoints.tail)
//                  val updatedState = gs1.copy(characterInfo = updatedChar, autoTarget = updatedAuto)
//                  val keyboardActions = dirOpt.toList.map(DirectionalKey(_))
//                  (updatedState, MKTask("runMovementToCreature", MKActions(Nil, keyboardActions)))
//
//                case None =>
//                  println(s"[moveToTarget] No direction found from $presentLoc")
//                  (gs1, NoOpTask)
//              }
//            } else {
//              println("[Movement-keepDistance] safe distance")
//              (baseState, NoOpTask)
//            }
//
//          case "lureCreatureToTeam" =>
//            val dist = math.max(
//              math.abs(creatureData.posX - presentLoc.x),
//              math.abs(creatureData.posY - presentLoc.y)
//            )
//            val targetPos =
//              if (dist > 5) Vec(creatureData.posX, creatureData.posY)
//              else getClosestTeamMemberPosition(json, presentLoc, settings).getOrElse(presentLoc)
//
//            val (newState, keys) = moveToTarget(targetPos, baseState, json)
//            (newState, MKTask("lureCreatureToTeam", MKActions(Nil, keys)))
//
//          case _ =>
//            (baseState, NoOpTask)
//        }
//    }
//  }


  private def generateSubwaypointsToCreature(
                                              targetLocation: Vec,
                                              state:          GameState,
                                              json:           JsValue
                                            ): GameState = {
    println("[generateSubwaypointsToCreature] Starting.")

    // 1) pull all tiles and build grid
    val tiles = (json \ "areaInfo" \ "tiles").as[Map[String, JsObject]]
    val xs = tiles.keys.map(_.substring(0, 5).toInt)
    val ys = tiles.keys.map(_.substring(5, 10).toInt)
    val gridBounds @ (minX, minY, maxX, maxY) = (xs.min, ys.min, xs.max, ys.max)

    val (grid, (offX, offY)) = createBooleanGrid(tiles, minX, minY)
    println(s"[generateSubwaypointsToCreature] Grid bounds = $gridBounds, offset = ($offX,$offY)")

    // 2) get character position
    val presentLoc = Vec(
      (json \ "characterInfo" \ "PositionX").as[Int],
      (json \ "characterInfo" \ "PositionY").as[Int]
    )

    println(s"[generateSubwaypointsToCreature] Character: $presentLoc → Target: $targetLocation")

    // 3) grid bounds warning
    if (
      targetLocation.x < minX || targetLocation.x > maxX ||
        targetLocation.y < minY || targetLocation.y > maxY
    ) {
      printInColor(ANSI_BLUE,
        s"[WARNING] Target $targetLocation is outside grid $gridBounds — attempting A* anyway")
    }

    // 4) run A* pathfinding
    val rawPath =
      if (presentLoc != targetLocation)
        aStarSearch(presentLoc, targetLocation, grid, offX, offY)
      else {
        println("[generateSubwaypointsToCreature] Already at creature's location.")
        Nil
      }

    val filteredPath = rawPath.filterNot(_ == presentLoc)
    println(s"[generateSubwaypointsToCreature] Final path: $filteredPath")

    // 5) debug visualization with printGridCreatures
    printGridCreatures(
      grid = grid,
      gridBounds = gridBounds,
      path = rawPath,
      charPos = presentLoc,
      waypointPos = targetLocation,
      creaturePositions = List(targetLocation)
    )

    // 6) update autoTarget substate
    val newAutoTarget = state.autoTarget.copy(
      subWaypoints = filteredPath
    )

    state.copy(autoTarget = newAutoTarget)
  }


  private def generateSubwaypointsToEscape(
                                            creatureLocation: Vec,
                                            state:            GameState,
                                            json:             JsValue
                                          ): GameState = {
    println("[DEBUG] Generating subwaypoints to escape creature.")

    // 1) build the grid
    val tiles: Map[String, JsObject] =
      (json \ "areaInfo" \ "tiles").as[Map[String, JsObject]]
    val xs   = tiles.keys.map(_.substring(0,5).toInt)
    val ys   = tiles.keys.map(_.substring(5,10).toInt)
    val gridBounds @ (minX, minY, maxX, maxY) = (xs.min, ys.min, xs.max, ys.max)
    println(s"[DEBUG] Grid Bounds: $gridBounds")

    val (grid, (offX, offY)) = createBooleanGrid(tiles, minX, minY)

    // 2) our current character location
    val presentLoc: Vec = state.characterInfo.presentCharLocation

    // 3) pick “safe” walkable tiles in the 2–4 tile ring
    val minSafe = 2
    val maxSafe = 4
    val potentialTiles: List[Vec] =
      getWalkableTiles(presentLoc, minSafe, maxSafe, grid, offX, offY)
    println(s"[DEBUG] Potential walkable tiles: $potentialTiles")

    // 4) from those, keep only those > minSafe from the creature, sort by farthest, take 4
    val farthestTiles: List[Vec] = potentialTiles
      .filter(_.distanceTo(creatureLocation) > minSafe)
      .sortBy(tile => -tile.distanceTo(creatureLocation))
      .take(4)
    println(s"[DEBUG] Farthest tiles: $farthestTiles")

    // 5) test A* path existence for each (no memo needed for just 4)
    val validTiles: List[Vec] = farthestTiles.filter { tile =>
      val path = aStarSearch(presentLoc, tile, grid, offX, offY)
      println(s"[DEBUG] Path to $tile: $path")
      path.nonEmpty
    }
    println(s"[DEBUG] Valid tiles: $validTiles")

    // 6) choose an escape target (or stay put)
    val escapeTarget: Vec =
      if (validTiles.nonEmpty) validTiles.maxBy(_.distanceTo(creatureLocation))
      else presentLoc
    println(s"[DEBUG] Chosen escape tile: $escapeTarget")

    // 7) compute the escape path, dropping current loc
    val rawPath     = aStarSearch(presentLoc, escapeTarget, grid, offX, offY)
    val filteredPath = rawPath.filterNot(_ == presentLoc)
    println(s"[DEBUG] Filtered escape path: $filteredPath")

    // 8) optional visualization
    printGridCreatures(grid, gridBounds, filteredPath, presentLoc, escapeTarget, List(creatureLocation))

    // 9) build new caveBot substate
    val newCaveBotState = state.caveBot.copy(
      subWaypoints           = filteredPath,
      gridBoundsState        = gridBounds,
      gridState              = grid,
      currentWaypointLocation= escapeTarget
    )

    // 10) return updated GameState
    state.copy(caveBot = newCaveBotState)
  }

  def transformToObject(creatureData: CreatureInfo, creatureSettingsList: Seq[String]): Option[CreatureSettings] = {
    // Debug: Print the creatureSettingsList
    println(s"[DEBUG] CreatureSettingsList: $creatureSettingsList")

    // Parse each creature setting description into CreatureSettings objects
    val parsedSettings = creatureSettingsList.map(parseCreature)

    // Debug: print the parsed creature settings for comparison
    parsedSettings.foreach { setting =>
      println(s"[DEBUG] Parsed Creature: ${setting.name}, Keep Distance: ${setting.keepDistance}")
    }

    // Find the matching creature settings by name (case-insensitive)
    parsedSettings.find(_.name.equalsIgnoreCase(creatureData.name)) match {
      case Some(creatureSettings) =>
        println(s"[DEBUG] Found specific settings for ${creatureData.name}")
        Some(creatureSettings)
      case None =>
        // If no exact match is found, look for settings with the name "All"
        println(s"[DEBUG] No specific settings found for ${creatureData.name}, looking for 'All' settings")
        parsedSettings.find(_.name.equalsIgnoreCase("All")) match {
          case Some(allSettings) =>
            println(s"[DEBUG] Found 'All' settings, applying to ${creatureData.name}")
            Some(allSettings)
          case None =>
            println(s"[DEBUG] No 'All' settings found, no action for ${creatureData.name}")
            None
        }
    }
  }


  private object HandleAttackBackpacks extends Step {
    private val taskName = "HandleAttackBackpacks"

    def run(state: GameState, json: JsValue, settings: UISettings): Option[(GameState, MKTask)] = {
      val at       = state.autoTarget
      val cont     = at.currentAutoAttackContainerName
      val metaId: String = (json \ "metaGeneratedId").asOpt[String].getOrElse("null")

      at.stateAutoTarget match {

        // 1) remove the (now empty) backpack
        case "remove_backpack" =>
          val maybeChar = for {
            mpObj <- (json \ "screenInfo" \ "mapPanelLoc" \ "8x6").asOpt[JsObject]
            cx    <- (mpObj \ "x").asOpt[Int]
            cy    <- (mpObj \ "y").asOpt[Int]
          } yield (cx, cy)

          val removeSeq = for {
            (cx, cy) <- maybeChar
            invObj   <- (json \ "screenInfo" \ "inventoryPanelLoc").asOpt[JsObject]
            key      <- invObj.keys.find(_.contains(cont))
            item0    <- (invObj \ key \ "contentsPanel" \ "item0").asOpt[JsObject]
            ex       <- (item0 \ "x").asOpt[Int]
            ey       <- (item0 \ "y").asOpt[Int]
          } yield List(
            MoveMouse(ex, ey, metaId),
            LeftButtonPress(ex, ey, metaId),
            MoveMouse(cx, cy, metaId),
            LeftButtonRelease(cx, cy, metaId)
          )

          val actions = removeSeq.getOrElse(Nil)
          val newAT = at.copy(stateAutoTarget = "open_new_backpack")
          Some(state.copy(autoTarget = newAT) ->
            MKTask(s"$taskName - remove_backpack", MKActions(actions, Nil)))

        // 2) open the next backpack
        case "open_new_backpack" =>
          val openSeq = for {
            invObj <- (json \ "screenInfo" \ "inventoryPanelLoc").asOpt[JsObject]
            key    <- invObj.keys.find(_.contains(cont))
            item0  <- (invObj \ key \ "contentsPanel" \ "item0").asOpt[JsObject]
            x      <- (item0 \ "x").asOpt[Int]
            y      <- (item0 \ "y").asOpt[Int]
          } yield List(
            MoveMouse(x, y, metaId),
            RightButtonPress(x, y, metaId),
            RightButtonRelease(x, y, metaId)
          )

          val actions    = openSeq.getOrElse(Nil)
          val newAT = at.copy(stateAutoTarget = "verifying")
          Some(state.copy(autoTarget = newAT) ->
            MKTask(s"$taskName - open_new_backpack", MKActions(actions, Nil)))

        // 3) verify there’s now a rune inside, or rotate to next container
        case "verifying" =>
          // find which rune ID belongs here
          val thisRuneOpt = at.autoTargetContainerMapping.find(_._2 == cont).map(_._1)
          // check if it’s present
          val hasRune = thisRuneOpt.exists { runeId =>
            (json \ "containersInfo" \ cont \ "items").asOpt[JsObject]
              .exists(_.values.exists(i => (i \ "itemId").asOpt[Int].contains(runeId)))
          }

          if (hasRune) {
            printInColor(ANSI_BLUE, s"[HandleAttackBackpacks] '$cont' now has runes → READY")
            val newAT = at.copy(stateAutoTarget = "ready")
            Some(state.copy(autoTarget = newAT) ->
              MKTask(s"$taskName - verifying", MKActions.empty))
          } else {
            // find next container in mapping that still has its rune
            val nextOpt = at.autoTargetContainerMapping.collectFirst {
              case (rid, c2) if c2 != cont &&
                (json \ "containersInfo" \ c2 \ "items").asOpt[JsObject]
                  .exists(_.values.exists(i => (i \ "itemId").asOpt[Int].contains(rid))) =>
                c2
            }

            nextOpt match {
              case Some(nextCont) =>
                printInColor(ANSI_BLUE, s"[HandleAttackBackpacks] switching to next container '$nextCont'")
                val newAT = at.copy(
                  currentAutoAttackContainerName = nextCont,
                  stateAutoTarget       = "remove_backpack"
                )
                Some(state.copy(autoTarget = newAT) ->
                  MKTask(s"$taskName - verifying", MKActions.empty))

              case None =>
                printInColor(ANSI_BLUE, s"[HandleAttackBackpacks] no more rune containers → READY")
                val newAT = at.copy(stateAutoTarget = "ready")
                Some(state.copy(autoTarget = newAT) ->
                  MKTask(s"$taskName - verifying", MKActions.empty))
            }
          }

        case _ =>
          None
      }
    }
  }

  private object GetAttackInfo extends Step {
    private val taskName = "GetAttackInfo"

    override def run(
                      state:    GameState,
                      json:     JsValue,
                      settings: UISettings
                    ): Option[(GameState, MKTask)] = {

      val targetId = state.autoTarget.chosenTargetId
      println(s"[$taskName] stateAutoTarget: ${state.autoTarget.stateAutoTarget}")
      // Skip if no target is chosen
      if (targetId == 0) {
        return None
      }

      // look up position from battleInfo using chosenTargetId
      val battleInfo = (json \ "battleInfo").as[Map[String, JsValue]]
      val creatureOpt = battleInfo.values
        .find(creatureData => (creatureData \ "Id").asOpt[Int].contains(targetId))

      creatureOpt match {
        case Some(creatureData) =>
          val targetName = (creatureData \ "Name").asOpt[String].getOrElse("Unknown")
          val x = (creatureData \ "PositionX").asOpt[Int].getOrElse(0)
          val y = (creatureData \ "PositionY").asOpt[Int].getOrElse(0)
          val z = (creatureData \ "PositionZ").asOpt[Int].getOrElse(0)
          val currentPos = Vec(x, y)
          val currentTime = System.currentTimeMillis()

          printInColor(ANSI_BLUE,
            s"[GetAttackInfo] Current target: $targetName ($targetId) at [$x,$y,$z]")

          // Update creature position history - preserve old position when creature hasn't moved
          val currentHistory = state.autoTarget.creaturePositionHistory.getOrElse(targetId, List.empty)

          val updatedHistory = currentHistory match {
            case (lastPos, lastTime) :: tail if lastPos == currentPos =>
              // Same position - update timestamp but keep old position in history
              println(s"[GetAttackInfo] Creature hasn't moved, updating timestamp only")
              (currentPos, currentTime) :: tail
            case (lastPos, lastTime) :: tail =>
              // Different position - add new entry and keep previous as history
              println(s"[GetAttackInfo] Creature moved from $lastPos to $currentPos")
              (currentPos, currentTime) :: List((lastPos, lastTime))
            case Nil =>
              // Empty history - add first entry
              println(s"[GetAttackInfo] First position recorded: $currentPos")
              List((currentPos, currentTime))
          }

          val updatedHistoryMap = state.autoTarget.creaturePositionHistory.updated(targetId, updatedHistory)

          println(s"[GetAttackInfo] Position history for creature $targetId: ${updatedHistory.map(_._1)}")

          val newCaveBotState = state.caveBot.copy(stateHunting = "stop")
          val newAutoTargetState = state.autoTarget.copy(
            lastTargetName  = targetName,
            lastTargetPos   = (x, y, z),
            creatureTarget  = targetId,
            creaturePositionHistory = updatedHistoryMap
          )

          val newState = state.copy(
            caveBot = newCaveBotState,
            autoTarget = newAutoTargetState
          )

          Some(newState -> MKTask(taskName, MKActions.empty))

        case None =>
          // Target not found in battleInfo, possibly dead or out of range
          printInColor(ANSI_BLUE, s"[GetAttackInfo] Target $targetId not found in battleInfo")
          None
      }
    }
  }

  private def extractInfoAndSortMonstersFromBattle(json: JsValue, settings: UISettings): List[CreatureInfo] = {
    // Extract battleInfo from the JSON
    val battleInfo = (json \ "battleInfo").as[Map[String, JsValue]]

    // Transform creatureList from settings into JSON
    val targetMonstersJsons = transformToJSON(settings.autoTargetSettings.creatureList)

    // Check if we are targeting all creatures
    val targetAllCreatures = targetMonstersJsons.exists { creatureJson =>
      (creatureJson \ "name").as[String].equalsIgnoreCase("All")
    }

    // Map creature name to CreatureSettings
    val creatureSettingsMap: Map[String, CreatureSettings] = targetMonstersJsons.map { creatureJson =>
      val creature =  creatureJson.as[CreatureSettings]
      (creature.name, creature)
    }.toMap

    // Extract and map battle targets from battleInfo
    val battleTargets: List[CreatureInfo] = battleInfo.flatMap { case (_, battleData) =>
      val isMonster = (battleData \ "IsMonster").asOpt[Boolean].getOrElse(false)
      val isPlayer = (battleData \ "IsPlayer").asOpt[Boolean].getOrElse(false)

      if ((isMonster || targetAllCreatures) && !isPlayer) {
        val creatureName = (battleData \ "Name").as[String]

        // Retrieve creature settings or use default settings
        val creatureSettings = creatureSettingsMap.getOrElse(creatureName, CreatureSettings(
          name = creatureName,
          count = 0,
          hpFrom = 0,
          hpTo = 100,
          danger = 0,
          targetBattle = false,
          targetScreen = false,
          chase = false,
          keepDistance = false,
          avoidWaves = false,
          useRune = false,
          runeType = None,
          useRuneOnScreen = false,
          useRuneOnBattle = false,
          lootMonsterImmediately = false,  // new field
          lootMonsterAfterFight = false,   // new field
          lureCreatureToTeam = false       // new field
        ))

        Some(CreatureInfo(
          id = (battleData \ "Id").as[Int],
          name = creatureName,
          healthPercent = (battleData \ "HealthPercent").as[Int],
          isShootable = (battleData \ "IsShootable").as[Boolean],
          isMonster = isMonster,
          danger = creatureSettings.danger,           // Set danger level from settings
          keepDistance = creatureSettings.keepDistance, // Set keep distance from settings
          isPlayer = isPlayer,
          posX = (battleData \ "PositionX").as[Int],
          posY = (battleData \ "PositionY").as[Int],
          posZ = (battleData \ "PositionZ").as[Int],
          lootMonsterImmediately = creatureSettings.lootMonsterImmediately,  // new field
          lootMonsterAfterFight = creatureSettings.lootMonsterAfterFight,    // new field
          lureCreatureToTeam = creatureSettings.lureCreatureToTeam           // new field
        ))
      } else {
        None
      }
    }.toList

    // Sort creatures by danger (descending) and healthPercent (ascending)
    battleTargets.sortBy(monster => (monster.danger * -1, monster.healthPercent))
  }

  def transformToJSON(creaturesData: Seq[String]): List[JsValue] = {
    creaturesData.map(description => {
      val creatureSettings = parseCreature(description)

      Json.toJson(creatureSettings) // Convert creature settings to JsValue
    }).toList
  }

  // Function to map each rune ID to its corresponding container name
  def getRuneContainerMapping(json: JsObject, settings: UISettings): Map[Int, String] = {
    // Extract rune IDs from the settings
    val runeIds = settings.autoTargetSettings.creatureList.flatMap(extractRuneIdFromSetting).toSet

    // Create an empty map to store rune ID and corresponding container name
    var runeContainerMap = Map[Int, String]()

    // Iterate over all containers
    (json \ "containersInfo").asOpt[JsObject].foreach { containersInfo =>
      containersInfo.fields.foreach { case (containerName, containerDetails) =>
        val items = (containerDetails \ "items").asOpt[JsObject].getOrElse(Json.obj())

        // Find the rune items in the container and map them to the container name
        items.fields.foreach {
          case (_, itemInfo) =>
            val itemId = (itemInfo \ "itemId").asOpt[Int].getOrElse(-1)
            val itemSubType = (itemInfo \ "itemSubType").asOpt[Int].getOrElse(-1)

            // Check if the item is a rune (match with runeIds and itemSubType)
            if (runeIds.contains(itemId) && itemSubType == 1) {
              runeContainerMap += (itemId -> containerName)  // Map rune ID to container name
            }
        }
      }
    }

    runeContainerMap
  }

  def extractRuneIdFromSetting(entry: String): Option[Int] = {
    entry.split(", ").flatMap {
      case setting if setting.startsWith("Rune Type:") =>
        setting.split("\\.").lastOption.flatMap(num => Try(num.toInt).toOption)
      case _ => None
    }.headOption
  }

  case class CreatureSettings(
                               name: String,
                               count: Int,
                               hpFrom: Int,
                               hpTo: Int,
                               danger: Int,
                               targetBattle: Boolean,
                               targetScreen: Boolean,
                               chase: Boolean,
                               keepDistance: Boolean,
                               avoidWaves: Boolean,
                               useRune: Boolean,
                               runeType: Option[String],
                               useRuneOnScreen: Boolean,
                               useRuneOnBattle: Boolean,
                               lootMonsterImmediately: Boolean,   // new field
                               lootMonsterAfterFight: Boolean,    // new field
                               lureCreatureToTeam: Boolean        // new field
                             )
  object CreatureSettings {
    // this derives a Reads and Writes for you automatically
    implicit val format: OFormat[CreatureSettings] = Json.format[CreatureSettings]
  }

  def parseCreature(description: String): CreatureSettings = {
    val parts = description.split(", ")
    val name = parts(0).substring("Name: ".length)
    val count = parts(1).split(": ")(1).toInt
    val hpRange = parts(2).split(": ")(1).split("-").map(_.toInt)
    val danger = parts(3).split(": ")(1).toInt
    val targetBattle = parts(4).split(": ")(1).equalsIgnoreCase("yes")
    val targetScreen = parts(5).split(": ")(1).equalsIgnoreCase("yes")
    val lootMonsterImmediately = parts(6).split(": ")(1).equalsIgnoreCase("yes")
    val lootMonsterAfterFight = parts(7).split(": ")(1).equalsIgnoreCase("yes")
    val chase = parts(8).split(": ")(1).equalsIgnoreCase("yes")
    val keepDistance = parts(9).split(": ")(1).equalsIgnoreCase("yes")
    val lureCreatureToTeam = parts(10).split(": ")(1).equalsIgnoreCase("yes")
    val avoidWaves = parts(11).split(": ")(1).equalsIgnoreCase("yes")
    val useRune = parts(12).split(": ")(1).equalsIgnoreCase("yes")
    val runeType = if (parts.length > 13 && parts(13).split(": ").length > 1) Some(parts(13).split(": ")(1)) else None
    val useRuneOnScreen = if (parts.length > 14) parts(14).split(": ")(1).equalsIgnoreCase("yes") else false
    val useRuneOnBattle = if (parts.length > 15) parts(15).split(": ")(1).equalsIgnoreCase("yes") else false

    // Debug: Print parsed values for keepDistance and chase
    //    println(s"[DEBUG] Parsing Creature: $name, Chase: $chase, Keep Distance: $keepDistance, Lure to Team: $lureCreatureToTeam")

    CreatureSettings(
      name, count, hpRange(0), hpRange(1), danger, targetBattle, targetScreen, chase, keepDistance,
      avoidWaves, useRune, runeType, useRuneOnScreen, useRuneOnBattle, lootMonsterImmediately, lootMonsterAfterFight, lureCreatureToTeam
    )
  }


  def checkPathToTarget(monster: CreatureInfo, presentCharLocation: Vec, json: JsValue): Boolean = {
    println(s"[DEBUG] Checking path to target: ${monster.name} at position (${monster.posX}, ${monster.posY}, ${monster.posZ})")

    // Parse tiles to determine the grid bounds and create a boolean grid
    val tiles = (json \ "areaInfo" \ "tiles").as[Map[String, JsObject]]
    val xs = tiles.keys.map(_.substring(0, 5).trim.toInt)
    val ys = tiles.keys.map(_.substring(5, 10).trim.toInt)
    val gridBounds = (xs.min, ys.min, xs.max, ys.max)
    println(s"[DEBUG] GridBounds: $gridBounds")

    val (grid, (min_x, min_y)) = createBooleanGrid(tiles, xs.min, ys.min)

    // Convert the monster's position to Vec
    val monsterPosition = Vec(monster.posX, monster.posY)
    println(s"[DEBUG] Monster Position: $monsterPosition")
    println(s"[DEBUG] Character Location: $presentCharLocation")

    // Check if the character is already at the target location
    if (presentCharLocation == monsterPosition) {
      println(s"[DEBUG] Character is already at the monster's location.")
      return true
    }

    // Perform A* search to check for a valid path
    val path = aStarSearch(presentCharLocation, monsterPosition, grid, min_x, min_y)

    if (path.nonEmpty) {
//      println(s"[DEBUG] Path found to monster: ${monster.name}. Path: $path")
//      printGrid(grid, gridBounds, path, presentCharLocation, monsterPosition)
      true
    } else {
      println(s"[DEBUG] No path found to monster: ${monster.name}.")
      false
    }
  }

  private def createBooleanGrid(tiles: Map[String, JsObject], minX: Int, minY: Int): (Array[Array[Boolean]], (Int, Int)) = {
    val xs = tiles.keys.map(_.substring(0, 5).toInt) // Remove .trim
    val ys = tiles.keys.map(_.substring(5, 10).toInt) // Remove .trim
    val maxX = xs.max
    val maxY = ys.max

    val width = maxX - minX + 1
    val height = maxY - minY + 1
    val grid = Array.ofDim[Boolean](height, width)

    tiles.foreach { case (key, tileObj) =>
      val x = key.substring(0, 5).toInt // Remove .trim
      val y = key.substring(5, 10).toInt // Remove .trim
      val isWalkable = (tileObj \ "isWalkable").asOpt[Boolean].getOrElse(false)

      val gridX = x - minX
      val gridY = y - minY
      if (gridY >= 0 && gridY < height && gridX >= 0 && gridX < width) {
        grid(gridY)(gridX) = isWalkable
      }
    }

    (grid, (minX, minY))
  }

//  aStarSearch(presentLoc, actualTarget, modifiedGrid, offX, offY)
  def aStarSearch(start: Vec, goal: Vec, grid: Array[Array[Boolean]], min_x: Int, min_y: Int): List[Vec] = {
//    println(s"Starting aStarSearch with start=$start, goal=$goal, min_x=$min_x, min_y=$min_y")

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
//        println("Path found: " + (start :: path).mkString(" -> "))
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

//    println(s"Frontier contains: ${frontier.mkString(", ")}")

//    println("Path not found")
    List()
  }

  def heuristic(a: Vec, b: Vec): Int = {
    Math.abs(a.x - b.x) + Math.abs(a.y - b.y)
  }

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

  // Helper function to check if the current position is adjacent to the goal (including diagonal)
  def isAdjacent(current: Vec, goal: Vec): Boolean = {
    val dx = math.abs(current.x - goal.x)
    val dy = math.abs(current.y - goal.y)
    (dx <= 1 && dy <= 1) // If the current position is adjacent (1 tile away in any direction, including diagonals)
  }


  // Get the target settings for the chosen creature or for "All"
  def getTargetBattle(creatureName: String, targetMonstersJsons: Seq[JsValue]): Option[Boolean] = {
    targetMonstersJsons
      .find(creatureJson => (creatureJson \ "name").as[String].equalsIgnoreCase(creatureName))
      .map(creatureJson => (creatureJson \ "targetBattle").as[Boolean])
  }

//  def findCreatureInfoById(creatureId: Long, battleInfo: Map[String, JsValue]): Option[CreatureInfo] = {
//    battleInfo.collectFirst {
//      case (_, creatureData) if (creatureData \ "Id").as[Long] == creatureId =>
//        val id = (creatureData \ "Id").as[Int]
//        val name = (creatureData \ "Name").as[String]
//        val healthPercent = (creatureData \ "HealthPercent").as[Int]
//        val isShootable = (creatureData \ "IsShootable").as[Boolean]
//        val isMonster = (creatureData \ "IsMonster").as[Boolean]
//        val danger = (creatureData \ "Danger").asOpt[Int].getOrElse(0)
//        val keepDistance = (creatureData \ "keepDistance").asOpt[Boolean].getOrElse(false)
//        val lootMonsterImmediately = (creatureData \ "lootMonsterImmediately").asOpt[Boolean].getOrElse(false) // new field
//        val lootMonsterAfterFight = (creatureData \ "lootMonsterAfterFight").asOpt[Boolean].getOrElse(false) // new field
//        val lureCreatureToTeam = (creatureData \ "lureCreatureToTeam").asOpt[Boolean].getOrElse(false) // new field
//        val isPlayer = (creatureData \ "IsPlayer").as[Boolean]
//        val posX = (creatureData \ "PositionX").as[Int]
//        val posY = (creatureData \ "PositionY").as[Int]
//        val posZ = (creatureData \ "PositionZ").as[Int]
//
//        CreatureInfo(id, name, healthPercent, isShootable, isMonster, danger, keepDistance, isPlayer, posX, posY, posZ, lootMonsterImmediately, lootMonsterAfterFight, lureCreatureToTeam)
//    }
//  }


  private object RefillAmmo extends Step {
    private val taskName = "RefillAmmo"

    override def run(
                      state: GameState,
                      json: JsValue,
                      settings: UISettings
                    ): Option[(GameState, MKTask)] = {
      val at = state.autoTarget

      // --- SPEAR REFILL FEATURE ---
      if (at.isUsingSpears == "true") {
        val spearIds = utils.StaticGameInfo.Items.SpearsIds
        val spearsInHand = (json \ "EqInfo" \ "6" \ "itemCount").asOpt[Int].getOrElse(0)
        if (spearsInHand < 4) {
          val charX = (json \ "characterInfo" \ "PositionX").asOpt[Int].getOrElse(0)
          val charY = (json \ "characterInfo" \ "PositionY").asOpt[Int].getOrElse(0)
          val charZ = (json \ "characterInfo" \ "PositionZ").asOpt[Int].getOrElse(0)
          val tiles = (json \ "areaInfo" \ "tiles").asOpt[Map[String, JsObject]].getOrElse(Map.empty)
          val mapPanelLoc = (json \ "screenInfo" \ "mapPanelLoc").asOpt[Map[String, JsObject]].getOrElse(Map.empty)
          def tileId(x: Int, y: Int, z: Int): String = f"$x%05d$y%05d$z%02d"

          val searchTiles = for {
            dx <- -1 to 1
            dy <- -1 to 1
          } yield tileId(charX + dx, charY + dy, charZ)

          val foundSpear = searchTiles.collectFirst {
            case id if tiles.get(id).exists { tile =>
              val items = (tile \ "items").asOpt[JsObject].getOrElse(Json.obj())
              items.values.exists(item => spearIds.contains((item \ "id").asOpt[Int].getOrElse(-1)))
            } => id
          }

          foundSpear.flatMap { spearTileId =>
            val tileObj = tiles.get(spearTileId)
            val items = tileObj.flatMap(t => (t \ "items").asOpt[JsObject]).getOrElse(Json.obj())
            val spearItemOpt = items.values.find(item => spearIds.contains((item \ "id").asOpt[Int].getOrElse(-1)))
            val tileIndexOpt = tileObj.flatMap(t => (t \ "index").asOpt[String])
            val metaId: String = (json \ "metaGeneratedId").asOpt[String].getOrElse("null")
            val (screenX, screenY) = tileIndexOpt.flatMap(idx =>
              mapPanelLoc.get(idx).flatMap(obj =>
                for {
                  x <- (obj \ "x").asOpt[Int]
                  y <- (obj \ "y").asOpt[Int]
                } yield (x, y)
              )
            ).getOrElse((0, 0))
            val eqPanel = (json \ "screenInfo" \ "equipmentPanelLoc" \ "6").asOpt[JsObject]
            val (handX, handY) = eqPanel.flatMap(obj =>
              for {
                x <- (obj \ "x").asOpt[Int]
                y <- (obj \ "y").asOpt[Int]
              } yield (x, y)
            ).getOrElse((0, 0))

            if (spearItemOpt.isDefined && screenX != 0 && screenY != 0 && handX != 0 && handY != 0) {
              val actions = List(
                MoveMouse(screenX, screenY, metaId),
                LeftButtonPress(screenX, screenY, metaId),
                MoveMouse(handX, handY, metaId),
                LeftButtonRelease(handX, handY, metaId)
              )
              return Some(state -> MKTask("RefillSpearFromGround", MKActions(actions, Nil)))
            } else None
          }
        }
      }
      // --- END SPEAR REFILL FEATURE ---

      // --- ORIGINAL AMMO REFILL LOGIC ---
      if (at.isUsingAmmo != "true") return None

      val containerInfo = (json \ "containersInfo").asOpt[JsObject].getOrElse(Json.obj())
      val stacksCount = containerInfo.fields.flatMap {
        case (_, contData) =>
          (contData \ "items").asOpt[JsObject].toList.flatMap(_.fields).collect {
            case (_, slotData)
              if ((slotData \ "itemId").asOpt[Int].contains(at.ammoId)) => 1
          }
      }.sum

      val atWithCounts = at.copy(
        AttackSuppliesLeftMap = at.AttackSuppliesLeftMap.updated(at.ammoId, stacksCount)
      )

      val ammoCount = (json \ "EqInfo" \ "10" \ "itemCount").asOpt[Int].getOrElse(0)
      val metaId: String = (json \ "metaGeneratedId").asOpt[String].getOrElse("null")

      println(s"[RefillAmmo] AmmoId=${at.ammoId}, totalStacks=$stacksCount")
      println(s"[RefillAmmo] Current ammo in slot 10: $ammoCount")
      println(s"[RefillAmmo] Refill threshold = ${atWithCounts.ammoCountForNextResupply}")

      if (stacksCount > 0 && ammoCount < atWithCounts.ammoCountForNextResupply) {
        val maybeSourceCont = containerInfo.fields.collectFirst {
          case (contName, contData)
            if ((contData \ "items").asOpt[JsObject]
              .exists(_.values.exists(i => (i \ "itemId").asOpt[Int].contains(at.ammoId)))) =>
            contName
        }

        val maybeCoords = for {
          cont     <- maybeSourceCont
          invPanel <- (json \ "screenInfo" \ "inventoryPanelLoc").asOpt[JsObject]
          slotKey  <- invPanel.keys.find(_.contains(cont))
          sx       <- (invPanel \ slotKey \ "x").asOpt[Int]
          sy       <- (invPanel \ slotKey \ "y").asOpt[Int]
          eqPanel  <- (json \ "screenInfo" \ "equipmentPanelLoc" \ "10").asOpt[JsObject]
          ex       <- (eqPanel \ "x").asOpt[Int]
          ey       <- (eqPanel \ "y").asOpt[Int]
        } yield (sx, sy, ex, ey)

        maybeCoords.map { case (sx, sy, ex, ey) =>
          val actions = List(
            MoveMouse(sx, sy, metaId),
            RightButtonPress(sx, sy, metaId),
            RightButtonRelease(sx, sy, metaId),
            MoveMouse(ex, ey, metaId),
            LeftButtonPress(ex, ey, metaId),
            LeftButtonRelease(ex, ey, metaId)
          )
          val newAT = atWithCounts
          val newState = state.copy(autoTarget = newAT)
          newState -> MKTask(taskName, MKActions(actions, Nil))
        }.orElse {
          Some(state.copy(autoTarget = atWithCounts) -> NoOpTask)
        }
      } else {
        println("[RefillAmmo] Ammo sufficient, no refill.")
        if (state.autoTarget.AttackSuppliesLeftMap.getOrElse(at.ammoId, -1) != stacksCount) {
          Some(state.copy(autoTarget = atWithCounts) -> NoOpTask)
        } else None
      }
    }
  }

  private def findCreatureInfoById(
                                    creatureId: Long,
                                    battleInfo: Map[String, JsValue],
                                    settings:   UISettings
                                  ): Option[CreatureInfo] = {
    // 1) parse all your CreatureSettings into a Map[name -> CreatureSettings]
    val settingsMap: Map[String, CreatureSettings] =
      settings.autoTargetSettings.creatureList
        .map(parseCreature)
        .map(cs => cs.name -> cs)
        .toMap

    // 2) find the matching battle entry
    battleInfo.values
      .find(js => (js \ "Id").asOpt[Long].contains(creatureId))
      .map { data =>
        // 3) extract JSON fields
        val id            = (data \ "Id").as[Int]
        val name          = (data \ "Name").as[String]
        val hpPercent     = (data \ "HealthPercent").as[Int]
        val isShootable   = (data \ "IsShootable").as[Boolean]
        val isMonster     = (data \ "IsMonster").as[Boolean]
        val isPlayer      = (data \ "IsPlayer").as[Boolean]
        val posX          = (data \ "PositionX").as[Int]
        val posY          = (data \ "PositionY").as[Int]
        val posZ          = (data \ "PositionZ").as[Int]

        // 4) look up settings for this name, or default
        val cs = settingsMap.getOrElse(
          name,
          CreatureSettings(
            name                   = name,
            count                  = 0,
            hpFrom                 = 0,
            hpTo                   = 100,
            danger                 = 0,
            targetBattle           = false,
            targetScreen           = false,
            chase                  = false,
            keepDistance           = false,
            avoidWaves             = false,
            useRune                = false,
            runeType               = None,
            useRuneOnScreen        = false,
            useRuneOnBattle        = false,
            lootMonsterImmediately = false,
            lootMonsterAfterFight  = false,
            lureCreatureToTeam     = false
          )
        )

        // 5) assemble CreatureInfo
        CreatureInfo(
          id                      = id,
          name                    = name,
          healthPercent           = hpPercent,
          isShootable             = isShootable,
          isMonster               = isMonster,
          danger                  = cs.danger,
          keepDistance            = cs.keepDistance,
          isPlayer                = isPlayer,
          posX                    = posX,
          posY                    = posY,
          posZ                    = posZ,
          lootMonsterImmediately  = cs.lootMonsterImmediately,
          lootMonsterAfterFight   = cs.lootMonsterAfterFight,
          lureCreatureToTeam      = cs.lureCreatureToTeam
        )
      }
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

  // Function to get potential walkable targets within a safe distance range
  def getWalkableTiles(charPos: Vec, minDistance: Int, maxDistance: Int, grid: Array[Array[Boolean]], minX: Int, minY: Int): List[Vec] = {
    val targets = for {
      x <- charPos.x - maxDistance to charPos.x + maxDistance
      y <- charPos.y - maxDistance to charPos.y + maxDistance
      pos = Vec(x, y)
      if charPos.distanceTo(pos) >= minDistance && charPos.distanceTo(pos) <= maxDistance
      if isTileWalkable(pos, grid, minX, minY)
    } yield pos
    targets.toList
  }

  // Utility function to check if a tile is walkable
  def isTileWalkable(pos: Vec, grid: Array[Array[Boolean]], minX: Int, minY: Int): Boolean = {
    val x = pos.x - minX
    val y = pos.y - minY
    if (x >= 0 && y >= 0 && x < grid(0).length && y < grid.length) {
      grid(y)(x)
    } else false
  }



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

  // Adjusted function to use the x and y values of Vec as min and max
  def generateRandomDelay(timeRange: (Int, Int)): Long = {
//    val rand = new Random
    val (minDelay, maxDelay) = timeRange
    minDelay
//    + (rand.nextLong() % (maxDelay - minDelay + 1))
  }

//  def moveToTarget(target: Vec, state0: GameState, json: JsValue): (GameState, List[KeyboardAction]) = {
//    val gs1 = generateSubwaypointsToCreature(target, state0, json)
//
//    val px = (json \ "characterInfo" \ "PositionX").as[Int]
//    val py = (json \ "characterInfo" \ "PositionY").as[Int]
//    val presentLoc = Vec(px, py)
//
//    gs1.autoTarget.subWaypoints.headOption match {
//      case Some(next) =>
//        val dirOpt = calculateDirection(presentLoc, next, gs1.characterInfo.lastDirection)
//        println(s"[runMovementToCreature-chase] Calculated direction: $dirOpt from $presentLoc to $next")
//        val updatedChar = gs1.characterInfo.copy(
//          lastDirection = dirOpt,
//          presentCharLocation = presentLoc
//        )
//        val updatedAuto = gs1.autoTarget.copy(subWaypoints = gs1.autoTarget.subWaypoints.tail)
//        val updatedState = gs1.copy(characterInfo = updatedChar, autoTarget = updatedAuto)
//        val keyboardActions = dirOpt.toList.map(DirectionalKey(_))
//        (updatedState, keyboardActions)
//
//      case None =>
//        val currentTime = System.currentTimeMillis()
//        val at = gs1.autoTarget
//
//        // Get current creature position from lastTargetPos
//        val (creatureX, creatureY, _) = at.lastTargetPos
//        val creaturePos = Vec(creatureX, creatureY)
//
//        // Check if we need to track creature movement detection
//        val shouldTrackMovement = at.lastKnownCreaturePosition.isEmpty || at.lastKnownCreaturePosition.get != creaturePos
//
//        if (shouldTrackMovement) {
//          // Creature has moved - reset throttle but don't move yet
//          val newThrottle = RandomUtils.between(1500, 5000).toLong
//          val updatedState = gs1.copy(autoTarget = at.copy(
//            lastRandomMovementTime = currentTime,
//            randomMovementThrottle = newThrottle,
//            lastKnownCreaturePosition = Some(creaturePos)
//          ))
//
//          println(s"[moveToTarget - chase] Creature moved to $creaturePos, resetting throttle (will consider moving in ${newThrottle}ms)")
//          return (updatedState, Nil)
//        }
//
//        // Check throttling - only move if enough time has passed since throttle reset
//        if (currentTime - at.lastRandomMovementTime < at.randomMovementThrottle) {
//          println(s"[moveToTarget - chase] Random movement throttled, waiting ${at.randomMovementThrottle - (currentTime - at.lastRandomMovementTime)}ms")
//          return (gs1, Nil)
//        }
//
//        // Generate intelligent movement direction
//        generateHumanLikeDirection(presentLoc, creaturePos, gs1, json) match {
//          case Some(direction) =>
//            val keyboardAction = DirectionalKey(direction)
//
//            val updatedState = gs1.copy(autoTarget = at.copy(
//              lastKnownCreaturePosition = Some(creaturePos)
//            ))
//
//            println(s"[moveToTarget - chase] No path found, using intelligent movement: $direction")
//            (updatedState, List(keyboardAction))
//
//          case None =>
//            println(s"[moveToTarget - chase] No valid movement directions available")
//            (gs1, Nil)
//        }
//    }
//  }
  def moveToTarget(target: Vec, state0: GameState, json: JsValue): (GameState, List[KeyboardAction]) = {
    val chaseMode = state0.autoTarget.chaseMode
    val creatureId = state0.autoTarget.chosenTargetId

    // Use different path generation based on chase mode
    val gs1 = if (chaseMode == "chase_after") {
      println(s"[moveToTarget] Using predictive pathfinding for chase_after mode")
      generateSubwaypointsToCreatureWithBlocking(target, state0, json, creatureId)
    } else {
      println(s"[moveToTarget] Using normal pathfinding for chase_to mode")
      generateSubwaypointsToCreature(target, state0, json)
    }

    val px = (json \ "characterInfo" \ "PositionX").as[Int]
    val py = (json \ "characterInfo" \ "PositionY").as[Int]
    val presentLoc = Vec(px, py)

    gs1.autoTarget.subWaypoints.headOption match {
      case Some(next) =>
        val dirOpt = calculateDirection(presentLoc, next, gs1.characterInfo.lastDirection)
        println(s"[moveToTarget] Calculated direction: $dirOpt from $presentLoc to $next")
        val updatedChar = gs1.characterInfo.copy(
          lastDirection = dirOpt,
          presentCharLocation = presentLoc
        )
        val updatedAuto = gs1.autoTarget.copy(subWaypoints = gs1.autoTarget.subWaypoints.tail)
        val updatedState = gs1.copy(characterInfo = updatedChar, autoTarget = updatedAuto)

        val metaId: String = (json \ "metaGeneratedId").asOpt[String].getOrElse("null")
        val keyboardActions = dirOpt.toList.map(d => keyboard.DirectionalKey(d, metaId))

        (updatedState, keyboardActions)

      case None =>
        handleNarrowCorridorCase(presentLoc, target, gs1, json)
    }
  }

  def generateSubwaypointsToCreatureWithBlocking(target: Vec, state: GameState, json: JsValue, creatureId: Int): GameState = {
    val at = state.autoTarget

    // Get movement vector from creature's position history
    val movementVector = at.creaturePositionHistory.get(creatureId) match {
      case Some(history) if history.length >= 2 =>
        val current = history.head._1
        val previous = history(1)._1
        println(s"[generateSubwaypointsToCreatureWithBlocking] Previous: $previous, Current: $current")
        val vector = Vec(current.x - previous.x, current.y - previous.y)
        println(s"[generateSubwaypointsToCreatureWithBlocking] Movement vector: $vector")
        Some(vector)
      case _ =>
        println(s"[generateSubwaypointsToCreatureWithBlocking] Insufficient history for creature $creatureId")
        None
    }

    val finalTarget = movementVector match {
      case Some(vector) =>
        val predictedPos = Vec(target.x + vector.x, target.y + vector.y)
        println(s"[generateSubwaypointsToCreatureWithBlocking] Predicted position: $predictedPos")

        // Use areaInfo/tiles instead of screenInfo/mapPanelLoc
        val tiles = (json \ "areaInfo" \ "tiles").as[Map[String, JsObject]]
        val xs = tiles.keys.map(_.substring(0, 5).trim.toInt)
        val ys = tiles.keys.map(_.substring(5, 10).trim.toInt)
        val minX = xs.min
        val maxX = xs.max
        val minY = ys.min
        val maxY = ys.max
        println(s"[generateSubwaypointsToCreatureWithBlocking] Grid bounds = ($minX,$minY,$maxX,$maxY), offset = ($minX,$minY)")

        // Create boolean grid using the existing method
        val (grid, (offX, offY)) = createBooleanGrid(tiles, minX, minY)

        // Check if predicted position is walkable
        val gridX = predictedPos.x - offX
        val gridY = predictedPos.y - offY

        if (predictedPos.x >= minX && predictedPos.x <= maxX &&
          predictedPos.y >= minY && predictedPos.y <= maxY &&
          gridY >= 0 && gridY < grid.length &&
          gridX >= 0 && gridX < grid(0).length &&
          grid(gridY)(gridX)) {
          println(s"[generateSubwaypointsToCreatureWithBlocking] Predicted position walkable, using: $predictedPos")
          predictedPos
        } else {
          println(s"[generateSubwaypointsToCreatureWithBlocking] Predicted position not walkable, using current: $target")
          target
        }
      case None =>
        println(s"[generateSubwaypointsToCreatureWithBlocking] No movement vector, using current position: $target")
        target
    }

    generateSubwaypointsToCreature(finalTarget, state, json)
  }

  private def handleNarrowCorridorCase(
                                        presentLoc: Vec,
                                        target: Vec,
                                        state: GameState,
                                        json: JsValue
                                      ): (GameState, List[KeyboardAction]) = {

    println(s"[handleNarrowCorridorCase] No path found, checking for narrow corridor movement")

    // In narrow corridors, follow the creature directly
    val directions = List(
      ("north", Vec(0, -1)),
      ("south", Vec(0, 1)),
      ("east", Vec(1, 0)),
      ("west", Vec(-1, 0)),
      ("northeast", Vec(1, -1)),
      ("northwest", Vec(-1, -1)),
      ("southeast", Vec(1, 1)),
      ("southwest", Vec(-1, 1))
    )

    val walkablePositions = extractWalkablePositions(json)

    // Find direction that gets us closer to target and is walkable
    val bestDirection = directions
      .map { case (name, dir) =>
        val nextPos = Vec(presentLoc.x + dir.x, presentLoc.y + dir.y)
        val distance = chebyshevDistance(nextPos, target)
        val isWalkable = walkablePositions.contains(nextPos)
        (name, dir, nextPos, distance, isWalkable)
      }
      .filter(_._5) // Only walkable positions
      .sortBy(_._4) // Sort by distance to target
      .headOption

    bestDirection match {
      case Some((dirName, _, _, _, _)) =>
        println(s"[handleNarrowCorridorCase] Moving $dirName towards target")

        val metaId: String = (json \ "metaGeneratedId").asOpt[String].getOrElse("null")
        val keyboardAction = DirectionalKey(dirName, metaId)

        (state, List(keyboardAction))

      case None =>
        println(s"[handleNarrowCorridorCase] No valid movement directions available")
        (state, Nil)
    }
  }

  private def extractWalkablePositions(json: JsValue): Set[Vec] = {
    val tiles = (json \ "areaInfo" \ "tiles").as[Map[String, JsObject]]

    tiles.flatMap { case (key, tileObj) =>
      val isWalkable = (tileObj \ "walkable").asOpt[Boolean].getOrElse(false)
      if (isWalkable) {
        val x = key.substring(0, 5).toInt
        val y = key.substring(5, 10).toInt
        Some(Vec(x, y))
      } else {
        None
      }
    }.toSet
  }


  private def blockOnlyCreaturePosition(
                                         grid: Array[Array[Boolean]],
                                         creaturePos: Vec,
                                         offX: Int,
                                         offY: Int
                                       ): Array[Array[Boolean]] = {
    val modifiedGrid = grid.map(_.clone) // Deep copy

    val gridX = creaturePos.x - offX
    val gridY = creaturePos.y - offY

    // Block only the creature's exact position
    if (gridY >= 0 && gridY < modifiedGrid.length && gridX >= 0 && gridX < modifiedGrid(0).length) {
      modifiedGrid(gridY)(gridX) = false // Block creature's position only
      println(s"[blockOnlyCreaturePosition] Blocked creature at grid position ($gridX, $gridY)")
    }

    modifiedGrid
  }

  private def findBestAdjacentPosition(
                                        creaturePos: Vec,
                                        characterPos: Vec,
                                        grid: Array[Array[Boolean]],
                                        offX: Int,
                                        offY: Int
                                      ): Vec = {
    // Get all adjacent positions around the creature
    val adjacentPositions = List(
      Vec(creaturePos.x - 1, creaturePos.y),     // Left
      Vec(creaturePos.x + 1, creaturePos.y),     // Right
      Vec(creaturePos.x, creaturePos.y - 1),     // Up
      Vec(creaturePos.x, creaturePos.y + 1),     // Down
      Vec(creaturePos.x - 1, creaturePos.y - 1), // Up-Left
      Vec(creaturePos.x + 1, creaturePos.y - 1), // Up-Right
      Vec(creaturePos.x - 1, creaturePos.y + 1), // Down-Left
      Vec(creaturePos.x + 1, creaturePos.y + 1)  // Down-Right
    )

    // Filter walkable positions
    val walkableAdjacent = adjacentPositions.filter { pos =>
      val gridX = pos.x - offX
      val gridY = pos.y - offY
      gridY >= 0 && gridY < grid.length &&
        gridX >= 0 && gridX < grid(0).length &&
        grid(gridY)(gridX)
    }

    // Choose the closest walkable position to character, or fallback
    val bestPosition = walkableAdjacent
      .sortBy(_.distanceTo(characterPos))
      .headOption
      .getOrElse(creaturePos) // Fallback to creature position if no adjacent walkable

    println(s"[findBestAdjacentPosition] Best adjacent position: $bestPosition from options: $walkableAdjacent")
    bestPosition
  }

  private def blockCreaturePosition(
                                     grid: Array[Array[Boolean]],
                                     creaturePos: Vec,
                                     offX: Int,
                                     offY: Int
                                   ): Array[Array[Boolean]] = {
    val modifiedGrid = grid.map(_.clone) // Deep copy

    val gridX = creaturePos.x - offX
    val gridY = creaturePos.y - offY

    // Block creature's current position and adjacent tiles for better pathfinding
    if (gridY >= 0 && gridY < modifiedGrid.length && gridX >= 0 && gridX < modifiedGrid(0).length) {
      modifiedGrid(gridY)(gridX) = false // Block creature's position

      // Optionally block adjacent tiles to force wider pathfinding
      val adjacentOffsets = List((-1, 0), (1, 0), (0, -1), (0, 1))
      adjacentOffsets.foreach { case (dx, dy) =>
        val adjX = gridX + dx
        val adjY = gridY + dy
        if (adjY >= 0 && adjY < modifiedGrid.length && adjX >= 0 && adjX < modifiedGrid(0).length) {
          // Only block if it was originally walkable (don't unblock walls)
          if (grid(adjY)(adjX)) {
            modifiedGrid(adjY)(adjX) = false
          }
        }
      }

      println(s"[blockCreaturePosition] Blocked creature at grid position ($gridX, $gridY)")
    }

    modifiedGrid
  }

  private def generateTacticalMovement(
                                        characterPos: Vec,
                                        creaturePos: Vec,
                                        state: GameState,
                                        json: JsValue
                                      ): (GameState, List[KeyboardAction]) = {
    println(s"[generateTacticalMovement] Character: $characterPos, Creature: $creaturePos")

    val tiles = (json \ "areaInfo" \ "tiles").as[Map[String, JsObject]]
    val xs = tiles.keys.map(_.substring(0, 5).toInt)
    val ys = tiles.keys.map(_.substring(5, 10).toInt)
    val gridBounds @ (minX, minY, maxX, maxY) = (xs.min, ys.min, xs.max, ys.max)
    val (grid, (offX, offY)) = createBooleanGrid(tiles, minX, minY)

    // Find tactical positions around the creature (flanking positions)
    val tacticalPositions = generateFlankingPositions(creaturePos, characterPos)
      .filter(pos => isWalkable(pos, grid, offX, offY, gridBounds))
      .sortBy(_.distanceTo(characterPos)) // Prefer closer positions

    println(s"[generateTacticalMovement] Available tactical positions: $tacticalPositions")

    tacticalPositions.headOption match {
      case Some(tacticalPos) =>
        val direction = calculateOptimalDirection(characterPos, tacticalPos, creaturePos)
        val metaId: String = (json \ "metaGeneratedId").asOpt[String].getOrElse("null")

        direction match {
          case Some(dir) =>
            val keyboardAction = DirectionalKey(dir, metaId)
            println(s"[generateTacticalMovement] Moving tactically: $dir towards $tacticalPos")
            (state, List(keyboardAction))
          case None =>
            println(s"[generateTacticalMovement] No valid tactical direction found")
            (state, Nil)
        }
      case None =>
        println(s"[generateTacticalMovement] No tactical positions available")
        (state, Nil)
    }
  }

  private def generateFlankingPositions(creaturePos: Vec, characterPos: Vec): List[Vec] = {
    // Generate positions that allow attacking from sides rather than head-on
    val flankingOffsets = List(
      Vec(-1, -1), Vec(-1, 1), Vec(1, -1), Vec(1, 1), // Diagonal positions
      Vec(-2, 0), Vec(2, 0), Vec(0, -2), Vec(0, 2),   // Extended orthogonal
      Vec(-1, 0), Vec(1, 0), Vec(0, -1), Vec(0, 1)    // Adjacent orthogonal
    )

    flankingOffsets.map(offset => Vec(creaturePos.x + offset.x, creaturePos.y + offset.y))
      .filter(_ != characterPos) // Don't include current position
  }

  private def calculateOptimalDirection(
                                         currentPos: Vec,
                                         targetPos: Vec,
                                         creaturePos: Vec
                                       ): Option[String] = {
    val deltaX = targetPos.x - currentPos.x
    val deltaY = targetPos.y - currentPos.y

    // Prefer moves that don't directly approach the creature head-on
    val direction = chooseDirection(deltaX, deltaY)

    // Validate that this move doesn't put us in a worse position
    direction.filter { dir =>
      val nextPos = getNextPosition(currentPos, dir)
      val currentDist = currentPos.distanceTo(creaturePos)
      val nextDist = nextPos.distanceTo(creaturePos)

      // Allow moves that maintain or slightly reduce distance, but avoid getting too close
      nextDist >= 1.0 && nextDist <= currentDist + 1.0
    }
  }

  private def getNextPosition(currentPos: Vec, direction: String): Vec = {
    direction match {
      case "MoveUp" => Vec(currentPos.x, currentPos.y - 1)
      case "MoveDown" => Vec(currentPos.x, currentPos.y + 1)
      case "MoveLeft" => Vec(currentPos.x - 1, currentPos.y)
      case "MoveRight" => Vec(currentPos.x + 1, currentPos.y)
      case "MoveUpLeft" => Vec(currentPos.x - 1, currentPos.y - 1)
      case "MoveUpRight" => Vec(currentPos.x + 1, currentPos.y - 1)
      case "MoveDownLeft" => Vec(currentPos.x - 1, currentPos.y + 1)
      case "MoveDownRight" => Vec(currentPos.x + 1, currentPos.y + 1)
      case _ => currentPos
    }
  }

  private def generateHumanLikeDirection(
                                          characterPos: Vec,
                                          creaturePos: Vec,
                                          state: GameState,
                                          json: JsValue
                                        ): Option[String] = {
    println(s"[generateHumanLikeDirection] Character: $characterPos, Creature: $creaturePos")

    // 1) Build grid from tiles (same as generateSubwaypointsToCreature)
    val tiles = (json \ "areaInfo" \ "tiles").as[Map[String, JsObject]]
    val xs = tiles.keys.map(_.substring(0, 5).toInt)
    val ys = tiles.keys.map(_.substring(5, 10).toInt)
    val gridBounds @ (minX, minY, maxX, maxY) = (xs.min, ys.min, xs.max, ys.max)

    val (grid, (offX, offY)) = createBooleanGrid(tiles, minX, minY)

    // 2) Check current position relative to creature
    val dx = creaturePos.x - characterPos.x
    val dy = creaturePos.y - characterPos.y

    // If already orthogonal (only horizontal or only vertical difference), don't move
    val isOrthogonal = (dx == 0 && dy != 0) || (dy == 0 && dx != 0)
    if (isOrthogonal) {
      println(s"[generateHumanLikeDirection] Already orthogonal to creature, no movement needed")
      return None
    }

    println(s"[generateHumanLikeDirection] Currently diagonal to creature (dx=$dx, dy=$dy), need to move")

    // 3) Generate movement options that go "around" the creature (never away)
    val movementOptions = List(
      ("MoveUp", Vec(characterPos.x, characterPos.y - 1)),
      ("MoveDown", Vec(characterPos.x, characterPos.y + 1)),
      ("MoveLeft", Vec(characterPos.x - 1, characterPos.y)),
      ("MoveRight", Vec(characterPos.x + 1, characterPos.y))
    ).filter { case (_, newPos) =>
      // Only allow moves that don't increase distance to creature
      val newDx = creaturePos.x - newPos.x
      val newDy = creaturePos.y - newPos.y
      val currentDistance = math.abs(dx) + math.abs(dy)
      val newDistance = math.abs(newDx) + math.abs(newDy)

      newDistance <= currentDistance // Never move away (maintain or reduce distance)
    }

    // 4) Filter by walkability
    val walkableOptions = movementOptions.filter { case (_, pos) =>
      isWalkable(pos, grid, offX, offY, gridBounds)
    }

    println(s"[generateHumanLikeDirection] Walkable movement options: ${walkableOptions.map(_._1)}")

    if (walkableOptions.isEmpty) {
      println(s"[generateHumanLikeDirection] No valid movement options available")
      return None
    }

    // 5) Prioritize moves that make us orthogonal
    val orthogonalMoves = walkableOptions.filter { case (_, newPos) =>
      val newDx = creaturePos.x - newPos.x
      val newDy = creaturePos.y - newPos.y
      (newDx == 0 && newDy != 0) || (newDy == 0 && newDx != 0) // Results in orthogonal position
    }

    val chosenDirection = if (orthogonalMoves.nonEmpty) {
      // Prefer moves that achieve orthogonal positioning
      val chosen = orthogonalMoves(RandomUtils.between(0, orthogonalMoves.length - 1))._1
      println(s"[generateHumanLikeDirection] Choosing orthogonal move: $chosen")
      chosen
    } else {
      // Fallback to any valid move that doesn't increase distance
      val chosen = walkableOptions(RandomUtils.between(0, walkableOptions.length - 1))._1
      println(s"[generateHumanLikeDirection] Choosing distance-maintaining move: $chosen")
      chosen
    }

    Some(chosenDirection)
  }

  private def isWalkable(pos: Vec, grid: Array[Array[Boolean]], offX: Int, offY: Int, gridBounds: (Int, Int, Int, Int)): Boolean = {
    val (minX, minY, maxX, maxY) = gridBounds

    // Check if position is within grid bounds
    if (pos.x < minX || pos.x > maxX || pos.y < minY || pos.y > maxY) {
      return false
    }

    // Convert world coordinates to grid coordinates
    // The grid is indexed as grid[y][x], where:
    // - y corresponds to (pos.y - offY)
    // - x corresponds to (pos.x - offX)
    val gridX = pos.x - offX
    val gridY = pos.y - offY

    // Check grid bounds - grid is [height][width]
    if (gridY < 0 || gridY >= grid.length || gridX < 0 || gridX >= grid(0).length) {
      return false
    }

    // Check if tile is walkable - correct indexing: grid[y][x]
    grid(gridY)(gridX)
  }


//  private def isWalkable(pos: Vec, grid: Array[Array[Boolean]], offX: Int, offY: Int, gridBounds: (Int, Int, Int, Int)): Boolean = {
//    val (minX, minY, maxX, maxY) = gridBounds
//
//    // Check if position is within grid bounds
//    if (pos.x < minX || pos.x > maxX || pos.y < minY || pos.y > maxY) {
//      return false
//    }
//
//    // Convert world coordinates to grid coordinates
//    val gridX = pos.x - offX
//    val gridY = pos.y - offY
//
//    // Check grid bounds
//    if (gridX < 0 || gridX >= grid.length || gridY < 0 || gridY >= grid(0).length) {
//      return false
//    }
//
//    // Check if tile is walkable (true = walkable)
//    grid(gridX)(gridY)
//  }

  case class CreatureInfo(
                           id: Int,
                           name: String,
                           healthPercent: Int,
                           isShootable: Boolean,
                           isMonster: Boolean,
                           danger: Int,
                           keepDistance: Boolean,
                           isPlayer: Boolean,
                           posX: Int,
                           posY: Int,
                           posZ: Int,
                           lootMonsterImmediately: Boolean,   // new field
                           lootMonsterAfterFight: Boolean,    // new field
                           lureCreatureToTeam: Boolean        // new field
                         )


  case class Creature(
                       name: String,
                       count: Int,
                       danger: Int,
                       targetBattle: Boolean,
                       loot: Boolean,
                       chase: Boolean,
                       keepDistance: Boolean,
                       avoidWaves: Boolean,
                       useRune: Boolean,
                       useRuneOnScreen: Boolean,
                       useRuneOnBattle: Boolean
                     )

}

