package processing

import keyboard.{KeyboardAction, PressKey}
import play.api.libs.json.{JsObject, JsValue, Json}
import processing.CaveBot.Vec
import utils.{GameState, StaticGameInfo}
import utils.ProcessingUtils.{MKActions, MKTask, Step}
import utils.SettingsUtils.UISettings
import utils.consoleColorPrint.{ANSI_BLUE, printInColor}

import scala.collection.mutable
import scala.util.Try
import scala.util.chaining.scalaUtilChainingOps
import keyboard.KeyboardAction
import mouse.{LeftButtonPress, LeftButtonRelease, MoveMouse, RightButtonPress, RightButtonRelease}


object AutoTargetFeature {

  def run(json: JsValue, settings: UISettings, state: GameState):
  (GameState, List[MKTask]) =
    if (!settings.autoTargetSettings.enabled) (state, Nil)
    else {
      val (s, maybeTask) = Steps.runFirst(json, settings, state)
      (s, maybeTask.toList)
    }

  private object Steps {
    // ordered list of steps
    val allSteps: List[Step] = List(
      GetAttackInfo,
      SetUpAttackSupplies,
      CheckAttackSupplies,
      HandleAttackBackpacks,
      RefillAmmo,
      EngageAttack
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

  private object EngageAttack extends Step {
    private val taskName = "AttackStep"

    override def run(
                      state: GameState,
                      json: JsValue,
                      settings: UISettings
                    ): Option[(GameState, MKTask)] = {


      state.autoTarget.statusOfAutoTarget match {

        case "fight" =>
          val subTaskName = "attackingTarget"
          println("Attacking target.")

          val (newState, mouseActions, keyboardActions) = processFighting(json, settings, state)
          newState -> MKTask(s"$taskName - $subTaskName", MKActions(mouseActions, keyboardActions))

        case "target chosen" =>
          val subTaskName = "targetChosen"
          println("Target has been chosen.")

          val (newState, mouseActions, keyboardActions) = processTargetChosen(json, settings, state)
          newState -> MKTask(s"$taskName - $subTaskName", MKActions(mouseActions, keyboardActions))


        case "target acquired" =>
          val subTaskName = "targetAcquired"
          println("Target acquired.")

          val (newState, mouseActions, keyboardActions) = processTargetAcquired(json, settings, state)
          newState -> MKTask(s"$taskName - $subTaskName", MKActions(mouseActions, keyboardActions))

        case "free" =>
          val subTaskName = "free"
          println("No target, system is idle.")

          val (newState, mouseActions, keyboardActions) = processFreeState(json, settings, state)
          newState -> MKTask(s"$taskName - $subTaskName", MKActions(mouseActions, keyboardActions))

        case _ =>
          None


      }
    }
  }

  private def processFighting(
                               json:     JsValue,
                               settings: UISettings,
                               state:    GameState
                             ): (GameState, Seq[MouseAction], Seq[KeyboardAction]) = {
    // common data
    val at                       = state.autoTarget
    val chosenId                 = at.chosenTargetId
    val presentLoc = Vec(
      (json \ "characterInfo" \ "PositionX").as[Int],
      (json \ "characterInfo" \ "PositionY").as[Int]
    )
    val battleInfo = (json \ "battleInfo").as[Map[String, JsValue]]
    val creatureOpt = findCreatureInfoById(chosenId, battleInfo)

    // 1) Movement sub‐step
    val (st1, moveMouse, moveKeys) = creatureOpt match {
      case Some(creature) =>
        // engageCreature must be refactored to return (GameState, Seq[MouseAction], Seq[KeyboardAction])
        val (newSt, mActions, kActions) =
          runMovementToCreature(creature, presentLoc, json, state, settings)
        (newSt, mActions, kActions)
      case None =>
        println(s"[Fight] no creature to move to")
        (state, Seq.empty, Seq.empty)
    }

    // 2) Trap‐placement sub‐step (placeholder)
    // you could insert real logic here
    val (st2, trapMouse, trapKeys) = {
      println("[Fight] trap sub‐step (placeholder)")
      (st1, Seq.empty[MouseAction], Seq.empty[KeyboardAction])
    }

    // 3) Rune‐attack sub‐step
    val (st3, runeMouse, runeKeys) = creatureOpt match {
      case Some(creature) =>
        // similarly, refactor shootRuneOnTarget to return (GameState, Seq[MouseAction], Seq[KeyboardAction])
        val (newSt, mActions, kActions) =
          runRuneAttack(creature, json, st2, settings)
        (newSt, mActions, kActions)
      case None =>
        println("[Fight] no creature to shoot rune on")
        (st2, Seq.empty, Seq.empty)
    }

    // 4) merge into one
    val allMouse    = moveMouse   ++ trapMouse   ++ runeMouse
    val allKeyboard = moveKeys    ++ trapKeys    ++ runeKeys

    (st3, allMouse, allKeyboard)
  }



  private def processTargetChosen(
                                     json:     JsValue,
                                     settings: UISettings,
                                     state:    GameState
                                   ): (GameState, Seq[MouseAction], Seq[KeyboardAction]) = {
    val at = state.autoTarget
    val chosenId = at.chosenTargetId
    val chosenName = at.chosenTargetName

    // 1) Are we already marked on battle?
    val currentAttackId = (json \ "attackInfo" \ "Id").asOpt[Int]
    val alreadyMarked = currentAttackId.contains(chosenId)

    // 2) Should we target on battle per settings?
    val battleInfo = (json \ "battleInfo").as[Map[String, JsValue]]
    val targetJsons = transformToJSON(settings.autoTargetSettings.creatureList)
    val thisBattleOpt = getTargetBattle(chosenName, targetJsons)
    val battleAll = getTargetBattle("All", targetJsons).getOrElse(false)
    val inBattleList = battleInfo.exists { case (_, bd) =>
      (bd \ "Name").asOpt[String].contains(chosenName)
    }
    val shouldTarget = thisBattleOpt.getOrElse(false) ||
      (thisBattleOpt.isEmpty && battleAll && !inBattleList)

    // 3) Build click actions if needed
    val now = System.currentTimeMillis()
    val clickActions: Seq[MouseAction] =
      if (!alreadyMarked && shouldTarget) {
        println(s"[EngageAttack] marking $chosenName ($chosenId) on battle")
        (for {
          posObj <- (json \ "screenInfo" \ "battlePanelLoc" \ chosenId.toString)
            .asOpt[JsObject]
          x <- (posObj \ "PosX").asOpt[Int]
          y <- (posObj \ "PosY").asOpt[Int]
        } yield Seq(
          MouseAction(x, y, "move"),
          MouseAction(x, y, "pressLeft"),
          MouseAction(x, y, "releaseLeft")
        )).getOrElse {
          println(s"[EngageAttack] WARNING: no battlePanelLoc for ID $chosenId")
          Seq.empty
        }
      } else {
        if (alreadyMarked) println(s"[EngageAttack] already marked on battle")
        else println(s"[EngageAttack] not targeting on battle for $chosenName")
        Seq.empty
      }

    // 4) Transition to fight state and record timestamp if we clicked
    val newAT = at.copy(
      statusOfAutoTarget = "fight",
      lastTargetMarkCommandSend = if (clickActions.nonEmpty) now else at.lastTargetMarkCommandSend,
      targetFreezeCreatureId = chosenId
    )

    val newState = state.copy(autoTarget = newAT)
    (newState, clickActions, Seq.empty)
  }


  private def processFreeState(
                                json:     JsValue,
                                settings: UISettings,
                                state:    GameState
                              ): (GameState, Seq[MouseAction], Seq[KeyboardAction]) = {

    // 1) extract & sort monsters
    val sorted = extractInfoAndSortMonstersFromBattle(json, settings)
    println(s"[EngageAttack-free] sortedMonsters = $sorted")

    val at0 = state.autoTarget

    // 2) reset if top monster has keepDistance
    val at1 = sorted.headOption.fold(at0) { first =>
      if (first.keepDistance && first.id != at0.chosenTargetId) {
        println("[EngageAttack-free] resetting chosenTargetId due to keepDistance")
        at0.copy(chosenTargetId = 0, chosenTargetName = "")
      } else at0
    }

    // 3) reset if last attacked is dead or current target dropped from battle
    val lastInfo = (json \ "lastAttackedCreatureInfo").asOpt[JsObject].getOrElse(Json.obj())
    val lastId   = (lastInfo \ "LastAttackedId").asOpt[Int].getOrElse(0)
    val isDead   = (lastInfo \ "IsDead").asOpt[Boolean].getOrElse(false)
    val inList   = sorted.exists(_.id == at1.chosenTargetId)

    val at2 = if ((isDead && lastId == at1.chosenTargetId) || !inList) {
      println("[EngageAttack-free] resetting chosenTargetId (dead or gone)")
      at1.copy(chosenTargetId = 0, chosenTargetName = "")
    } else at1

    // 4) pick new target by path if still none chosen
    val at3 = if (at2.chosenTargetId == 0 && sorted.nonEmpty) {
      val presentLoc = Vec(
        (json \ "characterInfo" \ "PositionX").as[Int],
        (json \ "characterInfo" \ "PositionY").as[Int]
      )

      sorted
        .take(5)
        .find(mon => checkPathToTarget(mon, presentLoc, json))
        .fold {
          println("[EngageAttack-free] no pathable target found")
          at2
        } { monster =>
          println(s"[EngageAttack-free] new target chosen: ${monster.name} (${monster.id})")
          at2.copy(
            chosenTargetId     = monster.id,
            chosenTargetName   = monster.name,
            statusOfAutoTarget = "target chosen"
          )
        }
    } else at2

    // 5) return updated state, no actions
    (state.copy(autoTarget = at3), Seq.empty, Seq.empty)
  }


  private object HandleAttackBackpacks extends Step {
    private val taskName = "HandleAttackBackpacks"

    def run(state: GameState, json: JsValue, settings: UISettings): Option[(GameState, MKTask)] = {
      val at       = state.autoTarget
      val cont     = at.currentAutoAttackContainerName

      at.statusOfAutoTarget match {

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
          val newAT = at.copy(statusOfAutoTarget = "open_new_backpack")
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
          val newAT = at.copy(statusOfAutoTarget = "verifying")
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
            val newAT = at.copy(statusOfAutoTarget = "ready")
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
                  statusOfAutoTarget       = "remove_backpack"
                )
                Some(state.copy(autoTarget = newAT) ->
                  MKTask(s"$taskName - verifying", MKActions.empty))

              case None =>
                printInColor(ANSI_BLUE, s"[HandleAttackBackpacks] no more rune containers → READY")
                val newAT = at.copy(statusOfAutoTarget = "ready")
                Some(state.copy(autoTarget = newAT) ->
                  MKTask(s"$taskName - verifying", MKActions.empty))
            }
          }

        case _ =>
          None
      }
    }
  }


  private object SetUpAttackSupplies extends Step {
    private val taskName = "SetUpAttackSupplies"

    def run(state: GameState, json: JsValue, settings: UISettings):
    Option[(GameState, MKTask)] = {

      val at = state.autoTarget

      // only on first pass (mapping empty & still in "init")
      if (at.autoTargetContainerMapping.nonEmpty || at.statusOfAutoTarget != "init")
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

        // 3) assemble new AutoTargetState
        val newAT = at.copy(
          autoTargetContainerMapping     = mapping,
          currentAutoAttackContainerName = firstCont,
          statusOfAutoTarget             = "ready",
          isUsingAmmo                    = isUsingAmmoStr,
          ammoId                         = ammoIdVal,
          ammoCountForNextResupply       = ammoCountVal
        )

        // 4) update GameState and emit a marker task
        val newState = state.copy(autoTarget = newAT)
        Some(newState -> MKTask(taskName, MKActions.empty))
      }
    }
  }



  private object CheckAttackSupplies extends Step {
    private val taskName = "CheckAttackRunes"
    def run(state: GameState, json: JsValue, settings: UISettings): Option[(GameState, MKTask)] = {
      val at = state.autoTarget
      if (at.statusOfAutoTarget != "ready") return None

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
            statusOfAutoTarget       = "remove_backpack"
          )
          Some(state.copy(autoTarget = newAT) -> MKTask(taskName, MKActions(actions, Nil)))

        case None =>
          None
      }
    }
  }


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
          // leave statusOfAutoTarget as “ready” so future refills can occur
          val newAT = atWithCounts
          val newState = state.copy(autoTarget = newAT)
          newState -> MKTask(taskName, MKActions(actions, Nil))
        }.orElse {
          // coords missing? just update the counts in state, no clicks
          Some(state.copy(autoTarget = atWithCounts) -> MKTask(taskName, MKActions.empty))
        }
      } else {
        // no refill needed—just persist the updated counts if they changed
        if (state.autoTarget.AttackSuppliesLeftMap.getOrElse(at.ammoId, -1) != stacksCount) {
          Some(state.copy(autoTarget = atWithCounts) -> MKTask(taskName, MKActions.empty))
        } else None
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
          s"[GetAttackInfo] New target: $targetName ($targetId) at [$xPos,$yPos,$zPos]")
        val newCaveBotState = state.caveBot.copy(stateHunting = "attacking")
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
          loot = false,
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
                               loot: Boolean,
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

  def parseCreature(description: String): CreatureSettings = {
    val parts = description.split(", ")
    val name = parts(0).substring("Name: ".length)
    val count = parts(1).split(": ")(1).toInt
    val hpRange = parts(2).split(": ")(1).split("-").map(_.toInt)
    val danger = parts(3).split(": ")(1).toInt
    val targetBattle = parts(4).split(": ")(1).equalsIgnoreCase("yes")
    val loot = parts(5).split(": ")(1).equalsIgnoreCase("yes")

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
      name, count, hpRange(0), hpRange(1), danger, targetBattle, loot, chase, keepDistance,
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

    println("Final Grid state:")
    grid.foreach(row => println(row.mkString(" ")))

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

  def findCreatureInfoById(creatureId: Long, battleInfo: Map[String, JsValue]): Option[CreatureInfo] = {
    battleInfo.collectFirst {
      case (_, creatureData) if (creatureData \ "Id").as[Long] == creatureId =>
        val id = (creatureData \ "Id").as[Int]
        val name = (creatureData \ "Name").as[String]
        val healthPercent = (creatureData \ "HealthPercent").as[Int]
        val isShootable = (creatureData \ "IsShootable").as[Boolean]
        val isMonster = (creatureData \ "IsMonster").as[Boolean]
        val danger = (creatureData \ "Danger").asOpt[Int].getOrElse(0)
        val keepDistance = (creatureData \ "keepDistance").asOpt[Boolean].getOrElse(false)
        val lootMonsterImmediately = (creatureData \ "lootMonsterImmediately").asOpt[Boolean].getOrElse(false) // new field
        val lootMonsterAfterFight = (creatureData \ "lootMonsterAfterFight").asOpt[Boolean].getOrElse(false) // new field
        val lureCreatureToTeam = (creatureData \ "lureCreatureToTeam").asOpt[Boolean].getOrElse(false) // new field
        val isPlayer = (creatureData \ "IsPlayer").as[Boolean]
        val posX = (creatureData \ "PositionX").as[Int]
        val posY = (creatureData \ "PositionY").as[Int]
        val posZ = (creatureData \ "PositionZ").as[Int]

        CreatureInfo(id, name, healthPercent, isShootable, isMonster, danger, keepDistance, isPlayer, posX, posY, posZ, lootMonsterImmediately, lootMonsterAfterFight, lureCreatureToTeam)
    }
  }
}

