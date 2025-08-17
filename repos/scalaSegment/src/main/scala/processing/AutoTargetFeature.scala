package processing

import cats.implicits.{catsSyntaxAlternativeGuard, toFunctorOps}
import keyboard.{DirectionalKey, KeyboardAction}
import play.api.libs.json.{JsObject, JsValue, Json, OFormat}
import utils.{GameState, StaticGameInfo}
import utils.ProcessingUtils.{MKActions, MKTask, NoOpTask, Step}
import utils.SettingsUtils.UISettings
import utils.consoleColorPrint.{ANSI_BLUE, printInColor}

import scala.util.Random
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
      GetAttackInfo,
      SetUpAttackSupplies,
      CheckAttackSupplies,
      HandleAttackBackpacks,
      RefillAmmo,
      TargetMarking,
      EngageAttack,
      EngageMovement
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
        case Nil => (currentState, acc)

        case step :: rest =>
          step.run(currentState, json, settings) match {
            case Some((newState, NoOpTask)) =>
              // passive step: just update state, continue
              loop(rest, newState, acc)

            case Some((newState, task)) =>
              // active step: collect task, continue
              loop(rest, newState, acc :+ task)

            case None =>
              loop(rest, currentState, acc)
          }
      }

      loop(allSteps, startState, Nil)
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
        return None
      }

      val currentTime = System.currentTimeMillis()
      val killedCreatures = (json \ "lastKilledCreatures").asOpt[JsObject].getOrElse(Json.obj())

      // Skip placeholder-only case
      if (killedCreatures.fields.size == 1 && {
        val only = killedCreatures.fields.head._2
        (only \ "None").asOpt[Boolean].contains(true)
      }) {
        return None
      }

      var updatedState = state
      var shouldStopAutoTarget = false

      killedCreatures.fields.foreach { case (creatureId, creatureInfo) =>
        val name = (creatureInfo \ "Name").asOpt[String].getOrElse("<unknown>")
        val x = (creatureInfo \ "X").asOpt[Int].getOrElse(0)
        val y = (creatureInfo \ "Y").asOpt[Int].getOrElse(0)
        val z = (creatureInfo \ "Z").asOpt[Int].getOrElse(0)
        val isDead = (creatureInfo \ "IsDead").asOpt[Boolean].getOrElse(false)

        if (isDead) {
          // Check if this creature has loot settings
          settings.autoTargetSettings.creatureList
            .map(parseCreature)
            .find(_.name.equalsIgnoreCase(name)) match {

            case Some(cs) if cs.lootMonsterImmediately =>
              println(s"[$taskName] Creature $name died with immediate loot setting - stopping AutoTarget")

              // Add to loot queue
              val tileKey = generatePositionKey(x, y, z)
              updatedState = updatedState.copy(
                autoLoot = updatedState.autoLoot.copy(
                  carcassToLootImmediately = updatedState.autoLoot.carcassToLootImmediately :+ (tileKey, currentTime, creatureId)
                ),
                autoTarget = updatedState.autoTarget.copy(
                  stateAutoTarget = "stop",
                  chosenTargetId = 0,
                )
              )


            case Some(cs) if cs.lootMonsterAfterFight =>
              println(s"[$taskName] Creature $name died with post-fight loot setting")
              val tileKey = generatePositionKey(x, y, z)
              updatedState = updatedState.copy(autoLoot = updatedState.autoLoot.copy(
                carcassToLootAfterFight = updatedState.autoLoot.carcassToLootAfterFight :+ (tileKey, currentTime, creatureId)
              ))

            case _ =>
              println(s"[$taskName] Creature $name died with no loot settings")
          }
        }
      }

      Some((updatedState, NoOpTask))
    }

    private def generatePositionKey(x: Int, y: Int, z: Int): String = f"$x$y$z"
    
    private def findCreatureSettings(creatureId: Int, json: JsValue, settings: UISettings): Option[CreatureSettings] = {
      println(s"[$taskName] Looking for settings for creature ID: $creatureId")
      
      // First, try to get creature name from lastAttackedCreatureInfo (more reliable when creature is dead)
      val creatureNameOpt = (json \ "lastAttackedCreatureInfo" \ "Name").asOpt[String]
        .orElse {
          // Fallback: try to get from battle info
          val battleInfo = (json \ "battleInfo").asOpt[Map[String, JsValue]].getOrElse(Map.empty)
          battleInfo.values.collectFirst {
            case creatureData if (creatureData \ "Id").asOpt[Int].contains(creatureId) =>
              (creatureData \ "Name").as[String]
          }
        }
        
      println(s"[$taskName] Found creature name: $creatureNameOpt")
      
      creatureNameOpt.flatMap { name =>
        // Parse settings to find matching creature
        val settingMap: Map[String, CreatureSettings] = settings.autoTargetSettings.creatureList
          .map(parseCreature)  // Parse each string into CreatureSettings
          .map(cs => cs.name.toLowerCase -> cs)  // Use lowercase for case-insensitive matching
          .toMap
          
        println(s"[$taskName] Available settings: ${settingMap.keys}")
        
        val result = settingMap.get(name.toLowerCase).orElse(settingMap.get("all"))
        println(s"[$taskName] Matched settings for '$name': $result")
        result
      }
    }
    
    private def queueCreatureForLooting(state: GameState, json: JsValue, creatureId: Int, settings: CreatureSettings): GameState = {
      println(s"[$taskName] Queuing creature $creatureId for looting")
      
      // Try to get position from lastAttackedCreatureInfo first (more reliable)
      val positionOpt = (json \ "lastAttackedCreatureInfo").asOpt[JsObject].flatMap { info =>
        for {
          x <- (info \ "LastPositionX").asOpt[Int]
          y <- (info \ "LastPositionY").asOpt[Int]
          z <- (info \ "LastPositionZ").asOpt[Int]
        } yield (x, y, z)
      }.orElse {
        // Fallback: try battle info
        val battleInfo = (json \ "battleInfo").asOpt[Map[String, JsValue]].getOrElse(Map.empty)
        battleInfo.values.collectFirst {
          case creatureData if (creatureData \ "Id").asOpt[Int].contains(creatureId) =>
            val posX = (creatureData \ "PositionX").as[Int]
            val posY = (creatureData \ "PositionY").as[Int] 
            val posZ = (creatureData \ "PositionZ").as[Int]
            (posX, posY, posZ)
        }
      }
      
      positionOpt match {
        case Some((posX, posY, posZ)) =>
          val currentTime = System.currentTimeMillis()
          val tileKey = f"$posX$posY$posZ"
          
          println(s"[$taskName] Creature position: ($posX, $posY, $posZ), tileKey: $tileKey")
          
          if (settings.lootMonsterImmediately) {
            println(s"[$taskName] Adding to immediate loot queue")
            state.copy(autoLoot = state.autoLoot.copy(
              carcassToLootImmediately = state.autoLoot.carcassToLootImmediately :+ (tileKey, currentTime, creatureId.toString)
            ))
          } else {
            println(s"[$taskName] Adding to after-fight loot queue")
            state.copy(autoLoot = state.autoLoot.copy(
              carcassToLootAfterFight = state.autoLoot.carcassToLootAfterFight :+ (tileKey, currentTime, creatureId.toString)
            ))
          }
          
        case None =>
          println(s"[$taskName] ⚠️ Could not find position for creature $creatureId")
          state
      }
    }
  }


  private object UpdateAttackStatus extends Step {
    private val taskName = "UpdateAttackStatus"
    private val markRetryCooldownMs = 1000L


    override def run(state: GameState, json: JsValue, settings: UISettings): Option[(GameState, MKTask)] = {

      if (state.autoTarget.stateAutoTarget == "stop") {
        return Some(state -> NoOpTask)
      }
      val battleInfo = (json \ "battleInfo").asOpt[Map[String, JsValue]].getOrElse(Map.empty)

      // Check if there are any creatures in battle (not players)
      val hasCreatures = battleInfo.values.exists { creature =>
        (creature \ "IsMonster").asOpt[Boolean].getOrElse(false)
      }

      if (!hasCreatures) {
        val updatedCaveBot = state.caveBot.copy(stateHunting = "free")
        val updatedState = state.copy(caveBot = updatedCaveBot)
        return Some(updatedState -> NoOpTask)
      }

      val currentAttackId = (json \ "attackInfo" \ "Id").asOpt[Int]

      if (currentAttackId.contains(state.autoTarget.chosenTargetId) &&
        state.autoTarget.chosenTargetId != 0) {
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

      println(s"[UpdateAttackStatus] Looking for a new target")

      val parsedSettings = settings.autoTargetSettings.creatureList.map(parseCreature)
      val settingNamesLower = parsedSettings.map(_.name.toLowerCase).toSet
      val considerAll = settingNamesLower.contains("all")

      val filteredCreatures = battleInfo.values.toList.filter { idx =>
        val name = (idx \ "Name").asOpt[String].map(_.toLowerCase)
        val isCreature = (idx \ "IsMonster").asOpt[Boolean].getOrElse(false)
        considerAll && isCreature || name.exists(settingNamesLower.contains)
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

      sortedCreatures.find { creature =>
        val targetPos = Vec(creature.posX, creature.posY)
        val hasPath = generateSubwaypointsToCreature(targetPos, state, json).autoTarget.subWaypoints.nonEmpty
        characterPos.manhattanDistance(targetPos) <= 1 || hasPath
      } match {
        case Some(chosen) =>
          println(s"[UpdateAttackStatus] New target selected: ${chosen.name} (${chosen.id})")

          val updatedAutoTargetState = state.autoTarget.copy(chosenTargetId = chosen.id, chosenTargetName = chosen.name, stateAutoTarget = "fight")
          val updatedCaveBotState = state.caveBot.copy(stateHunting = "stop")

          val updatedState = state.copy(
            autoTarget = updatedAutoTargetState,
            caveBot = updatedCaveBotState
          )

          return Some(updatedState -> NoOpTask)
        case None =>
          println(s"[UpdateAttackStatus] No target selected. Resetting the cavebot state.")
          val updatedCaveBot = state.caveBot.copy(stateHunting = "free")
          val updatedState = state.copy(caveBot = updatedCaveBot)
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

      val at = state.autoTarget

      // only on first pass (mapping empty & still in "init")
      if (at.autoTargetContainerMapping.nonEmpty || at.stateAutoTarget != "not_set")
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
            val rnd = scala.util.Random.nextInt(41) + 40
            println(s"[SetUpAttackSupplies] Detected ammo ID $id → using ammo; next refill count = $rnd")
            ("true", id, rnd)
          case _ =>
            println("[SetUpAttackSupplies] No valid ammo in slot → isUsingAmmo = false")
            ("false", at.ammoId, at.ammoCountForNextResupply)
        }

        println(s"[SetUpAttackSupplies] Rune-container mapping: $mapping")
        println(s"[SetUpAttackSupplies] First container: $firstCont")
        println(s"[SetUpAttackSupplies] Final ammoId: $ammoIdVal, next refill threshold: $ammoCountVal")

        // 3) assemble new AutoTargetState
        val newAT = at.copy(
          autoTargetContainerMapping     = mapping,
          currentAutoAttackContainerName = firstCont,
          stateAutoTarget                = "free",
          isUsingAmmo                    = isUsingAmmoStr,
          ammoId                         = ammoIdVal,
          ammoCountForNextResupply       = ammoCountVal
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
//      println(s"[CheckAttackRunes] Checking rune supplies in: ${at.autoTargetContainerMapping}")
//      println(s"stateAutoTarget: ${at.stateAutoTarget }")
      if (at.stateAutoTarget != "free") return None

      // find the first rune whose container is now empty
      at.autoTargetContainerMapping.collectFirst {
        case (runeId, cont)
          if !(json \ "containersInfo" \ cont \ "items").asOpt[JsObject]
            .exists(_.values.exists(i => (i \ "itemId").asOpt[Int].contains(runeId))) =>
          (runeId, cont)
      } match {
        case Some((runeId, cont)) =>
          printInColor(ANSI_BLUE, s"[CheckAttackRunes] '$cont' is out of rune $runeId → removing backpack")

          // build the “up backpack” click
          val upSeqOpt = for {
            invObj   <- (json \ "screenInfo" \ "inventoryPanelLoc").asOpt[JsObject]
            key      <- invObj.keys.find(_.contains(cont))
            upBtn    <- (invObj \ key \ "upButton").asOpt[JsObject]
            x        <- (upBtn \ "x").asOpt[Int]
            y        <- (upBtn \ "y").asOpt[Int]
          } yield List(
            MoveMouse(x, y),
            LeftButtonPress(x, y),
            LeftButtonRelease(x, y)
          )

          val actions = upSeqOpt.getOrElse(Nil)
          val newAT = at.copy(
            currentAutoAttackContainerName = cont,
            stateAutoTarget       = "remove_backpack"
          )
          Some(state.copy(autoTarget = newAT) -> MKTask(taskName, MKActions(actions, Nil)))

        case None =>
//          println("[CheckAttackRunes] All containers still have their runes.")
          None
      }
    }
  }


  private object TargetMarking extends Step {
    private val taskName = "TargetMarking"
    private val markRetryIntervalMs = 1500L // Retry marking every 7 seconds

    override def run(state: GameState, json: JsValue, settings: UISettings): Option[(GameState, MKTask)] = {

      val at = state.autoTarget
      val now = System.currentTimeMillis()
      val currentAttackId = (json \ "attackInfo" \ "Id").asOpt[Int]


      val shouldProceed =  at.stateAutoTarget == "fight" && // true
        !isTargetCurrentlyMarked(at.chosenTargetId, json) && // true
        at.chosenTargetId != 0 && // true
        (now - at.lastTargetMarkCommandSend >= markRetryIntervalMs) // true

      if (!shouldProceed) {
        val targetCreatureId = (json \ "attackInfo" \ "Id").asOpt[Int]
        val timeSinceLastMark = now - at.lastTargetMarkCommandSend
        return None
      }

      println(s"[TargetMarking] Entered function.")

      val chosenId = at.chosenTargetId
      val chosenName = at.chosenTargetName


      println(s"[TargetMarking] chosenId=$chosenId, chosenName=$chosenName, currentTarget=$currentAttackId")

      // Look up creature settings
      println(s"[TargetMarking] looking up settings for creature '$chosenName'")
      val creatureSettingsOpt = settings.autoTargetSettings.creatureList
        .map(parseCreature)
        .find(_.name.equalsIgnoreCase(chosenName))

      println(s"[TargetMarking] settings lookup result=$creatureSettingsOpt")

      val (markOnBattle, markOnScreen) = creatureSettingsOpt match {
        case Some(cs) => (cs.targetBattle, cs.targetScreen)
        case None => (false, false)
      }

      println(s"[TargetMarking] markOnBattle=$markOnBattle, markOnScreen=$markOnScreen")

      if (!markOnBattle && !markOnScreen) {
        println(s"[TargetMarking] creature $chosenName not configured for marking")
        return None
      }


      // Choose marking method
      val useScreen = if (markOnBattle && markOnScreen) {
        val choice = Random.nextBoolean()
        println(s"[TargetMarking] both methods available, randomly choosing ${if (choice) "SCREEN" else "BATTLE"} mark for $chosenName")
        choice
      } else markOnScreen

      // Generate click actions (but keep them commented out for simulation)
      val clickActions = if (useScreen) {
        println(s"[TargetMarking] markOnScreenUI() called for id=$chosenId")
        markOnScreenUI(chosenId, json)
      } else {
        println(s"[TargetMarking] markOnBattleUI() called for id=$chosenId")
        markOnBattleUI(chosenId, json)
      }

      println(s"[TargetMarking] clickActions generated: $clickActions")

      if (clickActions.nonEmpty) {
        val newAutoTarget = at.copy(
          lastTargetMarkCommandSend = now,
          lastMarkingAttemptedId = chosenId
        )
        println(s"[TargetMarking] updated autoTarget: $newAutoTarget")

        val task = MKTask(taskName, MKActions(clickActions, Nil))
        Some(state.copy(autoTarget = newAutoTarget) -> task)
      } else {
        println(s"[TargetMarking] failed to generate click actions for $chosenId")
        None
      }
    }

    private def isTargetCurrentlyMarked(targetId: Int, json: JsValue): Boolean = {
      (json \ "attackInfo" \ "Id").asOpt[Int].contains(targetId)
    }

    private def markOnBattleUI(chosenId: Int, json: JsValue): List[MouseAction] = {
      println(s"[TargetMarking] markOnBattleUI() called for id=$chosenId")
      val result = for {
        posObj <- (json \ "screenInfo" \ "battlePanelLoc" \ chosenId.toString).asOpt[JsObject]
          x <- (posObj \"PosX").asOpt[Int]
        y <- (posObj \"PosY").asOpt[Int]
      } yield {
        println(s"[TargetMarking] battle UI position x=$x, y=$y")
        List(
          MoveMouse(x, y),
          LeftButtonPress(x, y),
          LeftButtonRelease(x, y)
        )
      }
      val actions = result.getOrElse {
        println(s"[TargetMarking] failed to find battle UI position for $chosenId")
        Nil
      }
      println(s"[TargetMarking] markOnBattleUI actions: $actions")
      actions
    }

    private def markOnScreenUI(chosenId: Int, json: JsValue): List[MouseAction] = {
      println(s"[TargetMarking] markOnScreenUI() called for id=$chosenId")
      val battleInfo = (json \"battleInfo").as[Map[String, JsValue]]
      val tileIdOpt = battleInfo.values
        .find(obj => (obj \ "Id").asOpt[Int].contains(chosenId))
        .flatMap { obj =>
          for {
            x <- (obj \ "PositionX").asOpt[Int]
            y <- (obj \ "PositionY").asOpt[Int]
            z <- (obj \ "PositionZ").asOpt[Int]
          } yield {
            // pad Z to 2 digits if needed
            val zStr = f"$z%02d"
            s"$x$y$zStr"
          }
        }


      println(s"[TargetMarking] computed tileIdOpt=$tileIdOpt")

      val mapTiles = (json \"screenInfo" \ "mapPanelLoc").as[Map[String, JsObject]]
      val targetOpt = mapTiles.values.find(obj => (obj \"id").asOpt[String] == tileIdOpt)
      println(s"[TargetMarking] found map panel tile object: $targetOpt")

      val actions = targetOpt.flatMap { obj =>
        for {
          x <- (obj \"x").asOpt[Int]
          y <- (obj \"y").asOpt[Int]
        } yield {
          println(s"[TargetMarking] screen UI position x=$x, y=$y")
          List(
            MoveMouse(x, y),
            RightButtonPress(x, y),
            RightButtonRelease(x, y)
          )
        }
      }.getOrElse {
        println(s"[TargetMarking] failed to find screen position for $chosenId")
        Nil
      }
      println(s"[TargetMarking] markOnScreenUI actions: $actions")
      actions
    }

  }



  private object EngageAttack extends Step {
    private val taskName = "AttackStep"

    override def run(state: GameState, json: JsValue, settings: UISettings): Option[(GameState, MKTask)] = {

      if ((state.autoTarget.stateAutoTarget == "stop") || state.autoTarget.chosenTargetId == 0) return None

      println(s"[$taskName] Entered function.")

      val at = state.autoTarget
      val chosenId = at.chosenTargetId
      val presentLoc = Vec(
        (json \ "characterInfo" \ "PositionX").as[Int],
        (json \ "characterInfo" \ "PositionY").as[Int]
      )
      val battleInfo = (json \ "battleInfo").as[Map[String, JsValue]]
      val creatureOpt = findCreatureInfoById(chosenId, battleInfo, settings)

      if (creatureOpt.isEmpty)
        println(s"[$taskName] Chosen target ID $chosenId not found in battleInfo.")

//      val (st1, moveTask) = creatureOpt match {
//        case Some(creature) => runMovementToCreature(creature, presentLoc, json, state, settings)
//        case None => (state, MKTask("empty", MKActions(Nil, Nil)))
//      }

//      val moveMouse = moveTask.actions.mouse
//      val moveKeys = moveTask.actions.keyboard

      val st2 = state
      val trapMouse = Nil
      val trapKeys = Nil

      val (st3, runeTask) = creatureOpt match {
        case Some(creature) => runRuneAttack(creature, json, st2, settings)
        case None => (st2, MKTask("empty", MKActions(Nil, Nil)))
      }

      val runeMouse = runeTask.actions.mouse
      val runeKeys = runeTask.actions.keyboard

      val allMouse = trapMouse ++ runeMouse
      val allKeys = trapKeys ++ runeKeys
      val task = MKTask(taskName, MKActions(allMouse, allKeys))

      Some(st3 -> task)
    }
  }

  private def runRuneAttack(
                             creatureData: CreatureInfo,
                             json:         JsValue,
                             state:        GameState,
                             settings:     UISettings
                           ): (GameState, MKTask) = {

    val chosenId   = creatureData.id
    val battleInfo = (json \ "battleInfo").as[Map[String, JsValue]]
    val cfg        = effectiveRuneSettings(creatureData, battleInfo, settings)
    val now        = System.currentTimeMillis()

    println(s"[RuneAttack] settings: useBattle=${cfg.useRuneOnBattle}, useScreen=${cfg.useRuneOnScreen}")

    // 1) Battle rune attack
    val battleOpt: Option[(GameState, List[MouseAction])] =
      if (cfg.useRuneOnBattle &&
        now - state.autoTarget.lastRuneUseTime >= state.autoTarget.runeUseCooldown + state.autoTarget.runeUseRandomness
      ) {
        for {
          runeStr  <- cfg.runeType
          runeID   <- runeStr.split('.').lastOption.flatMap(_.toIntOption)
          contName <- state.autoTarget.autoTargetContainerMapping.get(runeID)
          slot     <- (0 until 4).find { i =>
            (json \ "containersInfo" \ contName \ "items" \ s"slot$i" \ "itemId").asOpt[Int].contains(runeID)
          }
          invPanel <- (json \ "screenInfo" \ "inventoryPanelLoc").asOpt[JsObject]
          panelKey <- invPanel.keys.find(_.contains(contName))
          contents <- (invPanel \ panelKey \ "contentsPanel").asOpt[JsObject]
          runeObj  <- (contents \ s"item$slot").asOpt[JsObject]
          rx       <- (runeObj \ "x").asOpt[Int]
          ry       <- (runeObj \ "y").asOpt[Int]
          battleObj<- (json \ "screenInfo" \ "battlePanelLoc" \ s"$chosenId").asOpt[JsObject]
          mx       <- (battleObj \ "PosX").asOpt[Int]
          my       <- (battleObj \ "PosY").asOpt[Int]
          if mx >= 2 && my >= 2
        } yield {
          println(s"[RuneAttack] runeStr: $runeStr, parsed runeID: $runeID")
          println(s"[RuneAttack] containerMapping: ${state.autoTarget.autoTargetContainerMapping}")
          println(s"[RuneAttack] container slot check: slot $slot")
          println(s"[RuneAttack] screen positions: rune=($rx,$ry), target=($mx,$my)")

          val actions = List(
            MoveMouse(rx, ry),
            RightButtonPress(rx, ry),
            RightButtonRelease(rx, ry),
            MoveMouse(mx, my),
            LeftButtonPress(mx, my),
            LeftButtonRelease(mx, my)
          )
          val rnd      = generateRandomDelay(state.autoTarget.runeUseTimeRange)
          val newAT    = state.autoTarget.copy(lastRuneUseTime = now, runeUseRandomness = rnd)
          val newState = state.copy(autoTarget = newAT)
          (newState, actions)
        }
      } else None

    val (afterBattleState, battleMouse) = battleOpt.getOrElse((state, List.empty))

    // 2) Screen rune attack
    val screenMouse: List[MouseAction] =
      if (cfg.useRuneOnScreen &&
        now - state.autoTarget.lastRuneUseTime >= state.autoTarget.runeUseCooldown + state.autoTarget.runeUseRandomness
      ) {
        println("[RuneAttack-SCREEN] Attempting screen rune attack")

        (for {
          runeStr  <- cfg.runeType
          _ = println(s"[RuneAttack-SCREEN] runeType from cfg: $runeStr")

          runeID   <- runeStr.split('.').lastOption.flatMap(_.toIntOption)
          _ = println(s"[RuneAttack-SCREEN] parsed runeID: $runeID")

          contName <- state.autoTarget.autoTargetContainerMapping.get(runeID)
          _ = println(s"[RuneAttack-SCREEN] container name for runeID: $contName")

          invPanel <- (json \ "screenInfo" \ "inventoryPanelLoc").asOpt[JsObject]
          _ = println(s"[RuneAttack-SCREEN] invPanel keys: ${invPanel.keys}")

          panelKey <- invPanel.keys.find(_.contains(contName))
          _ = println(s"[RuneAttack-SCREEN] matched panelKey: $panelKey")

          contents <- (invPanel \ panelKey \ "contentsPanel").asOpt[JsObject]
          _ = println(s"[RuneAttack-SCREEN] contents keys: ${contents.keys}")

          runeObj  <- (contents \ "item0").asOpt[JsObject]
          rx       <- (runeObj \ "x").asOpt[Int]
          ry       <- (runeObj \ "y").asOpt[Int]
          _ = println(s"[RuneAttack-SCREEN] Rune screen position: ($rx,$ry)")

          // Extract tile screen position
          tileId   = s"${creatureData.posX}${creatureData.posY}${creatureData.posZ}"
          tileRaw  = (json \ "areaInfo" \ "tiles" \ tileId)
          _ = println(s"[RuneAttack-SCREEN] Looking for tileId: $tileId")

          val mapPanel = (json \ "screenInfo" \ "mapPanelLoc").as[Map[String, JsObject]]
          val screenTargetOpt = mapPanel.values.find(obj => (obj \ "id").asOpt[String].contains(tileId))

          tx <- screenTargetOpt.flatMap(obj => (obj \ "x").asOpt[Int])
          ty <- screenTargetOpt.flatMap(obj => (obj \ "y").asOpt[Int])
          _ = println(s"[RuneAttack-SCREEN] Target screen position: ($tx,$ty)")

        } yield {
          val actions = List(
            MoveMouse(rx, ry),
            RightButtonPress(rx, ry),
            RightButtonRelease(rx, ry),
            MoveMouse(tx, ty),
            LeftButtonPress(tx, ty),
            LeftButtonRelease(tx, ty)
          )
          val rnd      = generateRandomDelay(state.autoTarget.runeUseTimeRange)
          val newAT    = state.autoTarget.copy(lastRuneUseTime = now, runeUseRandomness = rnd)
          val newState = state.copy(autoTarget = newAT)
          return (newState, MKTask("runeAttack", MKActions(actions, Nil)))
        }).getOrElse {
          println("[RuneAttack-SCREEN] ❌ Missing data to cast rune on screen.")
          List.empty
        }
      } else {
        println("[RuneAttack-SCREEN] Skipped (either flag false or cooldown not ready)")
        List.empty
      }



    val task = MKTask("runeAttack", MKActions(battleMouse ++ screenMouse, Nil))
    (afterBattleState, task)
  }



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


  private object EngageMovement extends Step {
    private val taskName = "EngageMovement"

    override def run(state: GameState, json: JsValue, settings: UISettings): Option[(GameState, MKTask)] = {
      val at = state.autoTarget

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
                                     presentLoc:   Vec,
                                     json:         JsValue,
                                     state:        GameState,
                                     settings:     UISettings
                                   ): (GameState, MKTask) = {

    val baseState = state

    val csOpt = transformToObject(creatureData, settings.autoTargetSettings.creatureList)

    csOpt match {
      case None =>
        println(s"[Movement] no settings for ${creatureData.name}")
        (baseState, NoOpTask)

      case Some(cs) =>
        val mode =
          if (cs.chase) "chase"
          else if (cs.keepDistance) "keepDistance"
          else if (cs.lureCreatureToTeam) "lureCreatureToTeam"
          else "none"


        mode match {
          case "chase" =>
            val target = Vec(creatureData.posX, creatureData.posY)
            val (newState, keys) = moveToTarget(target, baseState, json)
            (newState, MKTask("Chase", MKActions(Nil, keys)))

          case "keepDistance" =>
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
                  val keyboardActions = dirOpt.toList.map(DirectionalKey(_))
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
            val dist = math.max(
              math.abs(creatureData.posX - presentLoc.x),
              math.abs(creatureData.posY - presentLoc.y)
            )
            val targetPos =
              if (dist > 5) Vec(creatureData.posX, creatureData.posY)
              else getClosestTeamMemberPosition(json, presentLoc, settings).getOrElse(presentLoc)

            val (newState, keys) = moveToTarget(targetPos, baseState, json)
            (newState, MKTask("lureCreatureToTeam", MKActions(Nil, keys)))

          case _ =>
            (baseState, NoOpTask)
        }
    }
  }


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
      case Some(creatureSettings) => Some(creatureSettings)
      case None =>
        // If no exact match is found, look for settings with the name "All"
        parsedSettings.find(_.name.equalsIgnoreCase("All"))
    }
  }


  private object HandleAttackBackpacks extends Step {
    private val taskName = "HandleAttackBackpacks"

    def run(state: GameState, json: JsValue, settings: UISettings): Option[(GameState, MKTask)] = {
      val at       = state.autoTarget
      val cont     = at.currentAutoAttackContainerName

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
            MoveMouse(ex, ey),
            LeftButtonPress(ex, ey),
            MoveMouse(cx, cy),
            LeftButtonRelease(cx, cy)
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
            MoveMouse(x, y),
            RightButtonPress(x, y),
            RightButtonRelease(x, y)
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
      // try to pull the attacked creature's ID
      (json \ "attackInfo" \ "Id").asOpt[Int].map { targetId =>
        // switch into attacking state

        // pull name & position safely
        val targetName = (json \ "attackInfo" \ "Name").asOpt[String].getOrElse("Unknown")
        val xPos = (json \ "attackInfo" \ "Position" \ "x").asOpt[Int].getOrElse(0)
        val yPos = (json \ "attackInfo" \ "Position" \ "y").asOpt[Int].getOrElse(0)
        val zPos = (json \ "attackInfo" \ "Position" \ "z").asOpt[Int].getOrElse(0)

        printInColor(ANSI_BLUE,
          s"[GetAttackInfo] Current target: $targetName ($targetId) at [$xPos,$yPos,$zPos]")
        val newCaveBotState = state.caveBot.copy(stateHunting = "stop")
        val newAutoTargetState = state.autoTarget.copy(
          lastTargetName  = targetName,
          lastTargetPos   = (xPos, yPos, zPos),
          creatureTarget  = targetId
        )

        // build the updated GameState
        val newState = state.copy(
          caveBot = newCaveBotState,
          autoTarget = newAutoTargetState
        )

        // return a no-action task to stop any further Steps this tick
        newState -> MKTask(taskName, MKActions.empty)
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
      println(s"[DEBUG] Path found to monster: ${monster.name}. Path: $path")
      printGrid(grid, gridBounds, path, presentCharLocation, monsterPosition)
      true
    } else {
      println(s"[DEBUG] No path found to monster: ${monster.name}.")
      false
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

    println("Path not found")
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
                      state:    GameState,
                      json:     JsValue,
                      settings: UISettings
                    ): Option[(GameState, MKTask)] = {
      val at = state.autoTarget

      // only if we’re set to use ammo
      if (at.isUsingAmmo != "true") return None

      // 1) count total stacks of this ammoId across all backpacks
      val containerInfo = (json \ "containersInfo").asOpt[JsObject].getOrElse(Json.obj())
      val stacksCount = containerInfo.fields.flatMap {
        case (_, contData) =>
          (contData \ "items").asOpt[JsObject].toList.flatMap(_.fields).collect {
            case (_, slotData)
              if ( (slotData \ "itemId").asOpt[Int].contains(at.ammoId) ) => 1
          }
      }.sum

      // update suppliesLeftMap
      val atWithCounts = at.copy(
        AttackSuppliesLeftMap = at.AttackSuppliesLeftMap.updated(at.ammoId, stacksCount)
      )

      // 2) check current arrow count in equipment slot 10
      val ammoCount = (json \ "EqInfo" \ "10" \ "itemCount").asOpt[Int].getOrElse(0)

      println(s"[RefillAmmo] AmmoId=${at.ammoId}, totalStacks=$stacksCount")
      println(s"[RefillAmmo] Current ammo in slot 10: $ammoCount")
      println(s"[RefillAmmo] Refill threshold = ${atWithCounts.ammoCountForNextResupply}")
      // 3) only refill if we have stacks and are below threshold
      if (stacksCount > 0 && ammoCount < atWithCounts.ammoCountForNextResupply) {
        // pick the first backpack containing arrows
        val maybeSourceCont = containerInfo.fields.collectFirst {
          case (contName, contData)
            if ( (contData \ "items").asOpt[JsObject]
              .exists(_.values.exists(i => (i \ "itemId").asOpt[Int].contains(at.ammoId))) ) =>
            contName
        }

        // screen coords for backpack slot & eq slot
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
            MoveMouse(sx, sy),
            RightButtonPress(sx, sy),
            RightButtonRelease(sx, sy),
            MoveMouse(ex, ey),
            LeftButtonPress(ex, ey),
            LeftButtonRelease(ex, ey)
          )
          // leave stateAutoTarget as “ready” so future refills can occur
          val newAT = atWithCounts
          val newState = state.copy(autoTarget = newAT)
          newState -> MKTask(taskName, MKActions(actions, Nil))
        }.orElse {
          // coords missing? just update the counts in state, no clicks
          Some(state.copy(autoTarget = atWithCounts) -> NoOpTask)
        }
      } else {
        println("[RefillAmmo] Ammo sufficient, no refill.")
        // no refill needed—just persist the updated counts if they changed
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
    val rand = new Random
    val (minDelay, maxDelay) = timeRange
    minDelay + (rand.nextLong() % (maxDelay - minDelay + 1))
  }

  def moveToTarget(target: Vec, state0: GameState, json: JsValue): (GameState, List[KeyboardAction]) = {
    val gs1 = generateSubwaypointsToCreature(target, state0, json)

    val px = (json \ "characterInfo" \ "PositionX").as[Int]
    val py = (json \ "characterInfo" \ "PositionY").as[Int]
    val presentLoc = Vec(px, py)

    gs1.autoTarget.subWaypoints.headOption match {
      case Some(next) =>
        val dirOpt = calculateDirection(presentLoc, next, gs1.characterInfo.lastDirection)
        println(s"[runMovementToCreature-chase] Calculated direction: $dirOpt from $presentLoc to $next")
        val updatedChar = gs1.characterInfo.copy(
          lastDirection = dirOpt,
          presentCharLocation = presentLoc
        )
        val updatedAuto = gs1.autoTarget.copy(subWaypoints = gs1.autoTarget.subWaypoints.tail)
        val updatedState = gs1.copy(characterInfo = updatedChar, autoTarget = updatedAuto)
        val keyboardActions = dirOpt.toList.map(DirectionalKey(_))
        (updatedState, keyboardActions)

      case None =>
        println(s"[moveToTarget - chase] No direction found from $presentLoc")
        (gs1, Nil)
    }
  }

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

