package processing

import cats.effect.{IO, Ref}
import keyboard.{KeyboardAction, PressKey, TextType, TypeText}
import mouse._
import play.api.libs.json.{JsObject, JsValue}
import processing.Process.generateRandomDelay
import utils.{AutoHealState, GameState}
import utils.SettingsUtils.UISettings
import cats.syntax.all._
import processing.Process.{findItemInContainerSlot14, generateRandomDelay}
import utils.ProcessingUtils._


object HealingFeature {

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
      DangerLevelHealing,
      SelfHealing,
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

      // only MP/HP
      val toCheck = List(
        ah.healingMPContainerName -> 2874,
        ah.healingHPContainerName -> 2874
      ).filter(_._1.nonEmpty).filterNot(_._1 == "not_set")

      toCheck.collectFirst {
        case (name, id)
          if !(json \ "containersInfo" \ name \ "items").asOpt[JsObject]
            .exists(_.values.exists(item => (item \ "itemId").asOpt[Int].contains(id)))
        => name
      } match {
        case Some(emptyPotContainer) =>
          println(s"[$taskName] '$emptyPotContainer' out of potions → removing it")
          val upSeqOpt = for {
            invObj   <- (json \ "screenInfo" \ "inventoryPanelLoc").asOpt[JsObject]
            panelKey <- invObj.keys.find(_.contains(emptyPotContainer))
            upBtn    <- (invObj \ panelKey \ "upButton").asOpt[JsObject]
            x        <- (upBtn \ "x").asOpt[Int]
            y        <- (upBtn \ "y").asOpt[Int]
          } yield List(
            MoveMouse(x, y),
            LeftButtonPress(x, y),
            LeftButtonRelease(x, y)
          )

          val actions     = upSeqOpt.getOrElse(Nil)
          val updatedAH   = ah.copy(
            healingRuneContainerName = emptyPotContainer,
            statusOfAutoheal         = "remove_backpack"
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

      val ah        = state.autoHeal
      val container = ah.healingRuneContainerName
      val status    = ah.statusOfAutoheal
      println("start SetUpSupplies")

      if (container.isEmpty || container == "not_set") {
        val containers = (json \ "containersInfo").asOpt[JsObject]
          .map(_.fields)
          .getOrElse(Nil)

        println(s"  [SetUpSupplies] found ${containers.size} containers:")
        containers.foreach { case (name, details) =>
          val ids = (details \ "items").asOpt[JsObject]
            .map(_.values.flatMap(item => (item \ "itemId").asOpt[Int])).getOrElse(Seq.empty)
          println(s"    - $name contains IDs: ${ids.mkString(",")}")
        }

        // find UH rune container
        val uhContainerOpt = containers.collectFirst {
          case (name, details)
            if (details \ "items").asOpt[JsObject]
              .exists(_.values.exists(item => (item \ "itemId").asOpt[Int].contains(3160)))
          => name
        }
        // find MP potion container
        val mpContainerOpt = containers.collectFirst {
          case (name, details)
            if (details \ "items").asOpt[JsObject]
              .exists(_.values.exists(item => (item \ "itemId").asOpt[Int].contains(2874)))
          => name
        }

        val newUH = uhContainerOpt.getOrElse {
          println("    → no UH runes found in any container, but marking ready anyway")
          "not_set"
        }
        val newMP = mpContainerOpt.getOrElse {
          println("    → no MP potions found in any container (you won’t be able to heal MP)")
          "not_set"
        }

        println(s"  [SetUpSupplies] selecting UH='$newUH', MP='$newMP'")

        val updatedState = state.copy(autoHeal = ah.copy(
          healingRuneContainerName = newUH,
          healingMPContainerName   = newMP,
          statusOfAutoheal         = "ready"
        ))

        println(s"  → SetUpSupplies: UH container='$newUH', MP container='$newMP', status='ready'")
        return Some(updatedState -> MKTask(taskName, MKActions.empty))

      } else if (container != "not_set" && status == "ready") {
        val infoOpt = (json \ "containersInfo" \ container).asOpt[JsObject]
        val free    = infoOpt.flatMap(ci => (ci \ "freeSpace").asOpt[Int])
        val parent  = infoOpt.flatMap(ci => (ci \ "hasParent").asOpt[Boolean])

        println(s"  [SetUpSupplies] checking backpack '$container': freeSpace=$free, hasParent=$parent")

        // only when it’s a full backpack with a parent
        if (free.contains(20) && parent.contains(true)) {
          val upSeqOpt: Option[List[MouseAction]] = for {
            invObj   <- (json \ "screenInfo" \ "inventoryPanelLoc").asOpt[JsObject]
            matching <- invObj.keys.find(_.contains(container))
            upBtn    <- (invObj \ matching \ "upButton").asOpt[JsObject]
            x        <- (upBtn \ "x").asOpt[Int]
            y        <- (upBtn \ "y").asOpt[Int]
          } yield List(
            MoveMouse(x, y),
            LeftButtonPress(x, y),
            LeftButtonRelease(x, y)
          )

          val actions = upSeqOpt.getOrElse(Nil)
          println(s"  [SetUpSupplies] going up from '$container', actions = $actions")

          val updatedState = state.copy(autoHeal = ah.copy(
            statusOfAutoheal = "remove_backpack"
          ))

          return Some(updatedState -> MKTask(taskName, MKActions(actions, Nil)))

        } else {
          println(s"  [SetUpSupplies] nothing to do in '$container' yet")
          None
        }

      } else {
        println("  [SetUpSupplies] not in setup phase, passing through")
        None
      }
    }
  }

  private object HandleHealingBackpacks extends Step {
    private val taskName = "HandleBackpacks"

    def run(
             state: GameState,
             json:  JsValue,
             settings: UISettings
           ): Option[(GameState, MKTask)] = {

      state.autoHeal.statusOfAutoheal match {

        case "remove_backpack" =>
          val subTaskName = "remove_backpack"
          println("start remove_backpack")
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
            matching <- invObj.keys.find(_.contains(state.autoHeal.healingRuneContainerName))
            item0Obj <- (invObj \ matching \ "contentsPanel" \ "item0").asOpt[JsObject]
            emptyX <- (item0Obj \ "x").asOpt[Int]
            emptyY <- (item0Obj \ "y").asOpt[Int]
          } yield List(
            MoveMouse(emptyX, emptyY),
            LeftButtonPress(emptyX, emptyY),
            MoveMouse(cx, cy),
            LeftButtonRelease(cx, cy)
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
          // find the new‑backpack slot and build a right‑click sequence
          val maybeOpenSeq: Option[List[MouseAction]] = for {
            invObj <- (json \ "screenInfo" \ "inventoryPanelLoc").asOpt[JsObject]
            matching <- invObj.keys.find(_.contains(state.autoHeal.healingRuneContainerName))
            item0Obj <- (invObj \ matching \ "contentsPanel" \ "item0").asOpt[JsObject]
            x <- (item0Obj \ "x").asOpt[Int]
            y <- (item0Obj \ "y").asOpt[Int]
          } yield List(
            MoveMouse(x, y),
            RightButtonPress(x, y),
            RightButtonRelease(x, y)
          )

          val mouseActions = maybeOpenSeq.getOrElse(Nil)

          // bump to “verifying” so the next loop can check runes
          val updatedState = state.copy(
            autoHeal = state.autoHeal.copy(statusOfAutoheal = "verifying")
          )

          return Some(updatedState -> MKTask(s"$taskName - $subTaskName", MKActions(mouseActions, Nil)))

        case "verifying" =>
          val sub = "verifying"
          val ah  = state.autoHeal
          val curr = ah.healingRuneContainerName
          println(s"[$taskName] [$sub] verifying container='$curr'")

          // 1) pick the right itemId for this container
          val currId =
            if (curr == ah.healingMPContainerName || curr == ah.healingHPContainerName) 2874
            else 3160

          // 2) check for any of that item left
          val rem = (json \ "containersInfo" \ curr \ "items").asOpt[JsObject]
            .exists(_.values.exists(item =>
              (item \ "itemId").asOpt[Int].contains(currId)
            ))
          println(s"[$taskName] [$sub] container '$curr' hasRemaining($currId)=$rem")

          if (rem) {
            // items still here → done
            println(s"[$taskName] [$sub] items still present, switching to READY")
            val newState = state.copy(autoHeal = ah.copy(statusOfAutoheal = "ready"))
            return Some(newState -> MKTask(s"$taskName - $sub", MKActions.empty))
          }

          // 3) find next container in UH→MP→HP order
          val nextOpt =
            if (curr != ah.healingMPContainerName &&
              (json \ "containersInfo" \ ah.healingMPContainerName \ "items").asOpt[JsObject]
                .exists(_.values.exists(item => (item \ "itemId").asOpt[Int].contains(2874)))
            ) Some(ah.healingMPContainerName)
            else if (curr != ah.healingHPContainerName &&
              (json \ "containersInfo" \ ah.healingHPContainerName \ "items").asOpt[JsObject]
                .exists(_.values.exists(item => (item \ "itemId").asOpt[Int].contains(2874)))
            ) Some(ah.healingHPContainerName)
            else None

          nextOpt match {
            case Some(nextCont) =>
              println(s"[$taskName] [$sub] switching to next container='$nextCont'")
              val updatedAH = ah.copy(
                healingRuneContainerName = nextCont,
                statusOfAutoheal         = "remove_backpack"
              )
              return Some(
                state.copy(autoHeal = updatedAH) ->
                  MKTask(s"$taskName - $sub", MKActions.empty)
              )

            case None =>
              // no more containers with items → we're done
              println(s"[$taskName] [$sub] no more containers, switching to READY")
              val doneState = state.copy(autoHeal = ah.copy(statusOfAutoheal = "ready"))
              return Some(doneState -> MKTask(s"$taskName - $sub", MKActions.empty))
          }

        case _ =>
          None
      }
    }
  }

  private object DangerLevelHealing extends Step {
    private val taskName = "DangerLevelHealing"
    def run(
             state:    GameState,
             json:     JsValue,
             settings: UISettings
           ): Option[(GameState, MKTask)] = {

      val armed = state.autoHeal.dangerLevelHealing
      val isActiveFromJson = (json \ "characterInfo" \ "IsCrosshairActive")
        .asOpt[Boolean].getOrElse(false)

      // sync our state
      val synced = state.copy(autoHeal = state.autoHeal.copy(
        healingCrosshairActive = isActiveFromJson
      ))

      // === explicitly type these as Option[List[MouseAction]] ===
      val activateSeq: Option[List[MouseAction]] =
        if (armed && !isActiveFromJson) {
          // The for-comprehension here already returns Option[List[MouseAction]]
          for {
            mp      <- (json \ "screenInfo" \ "mapPanelLoc" \ "8x6").asOpt[JsObject]
            tx      <- (mp   \ "x").asOpt[Int]
            ty      <- (mp   \ "y").asOpt[Int]
            runePos <- findItemInContainerSlot14(json, synced, 3160, 1).headOption
            rx       = (runePos \ "x").as[Int]
            ry       = (runePos \ "y").as[Int]
          } yield List(
            MoveMouse(rx, ry),
            RightButtonPress(rx, ry),
            RightButtonRelease(rx, ry),
            MoveMouse(tx, ty)
          )
        } else {
          // now this is unambiguously the same type
          Option.empty[List[MouseAction]]
        }

      val deactivateSeq: Option[List[MouseAction]] =
        if (!armed && isActiveFromJson) {
          println("[DangerLevelHealing] deactivating crosshair")
          Some(List(RightButtonRelease(0, 0)))
        } else {
          Option.empty[List[MouseAction]]
        }

      // now orElse, isDefined, etc. will compile
      val mouseActionsOpt = activateSeq.orElse(deactivateSeq)

      mouseActionsOpt match {
        case Some(mouseActions) if mouseActions.nonEmpty =>
          println(s"[DangerLevelHealing] will emit: $mouseActions")

          // determine the new crosshair‐active state
          val newActive = activateSeq.isDefined

          val newState = synced.copy(autoHeal = synced.autoHeal.copy(
            healingCrosshairActive = newActive
          ))
          Some(newState -> MKTask(taskName, MKActions(mouseActions, Nil)))

        case _ =>
          println("[DangerLevelHealing] no crosshair actions, passing through")
          None
      }
    }
  }


  private object TeamHealing extends Step {
    private val taskName = "TeamHealing"
    def run(
             state:    GameState,
             json:     JsValue,
             settings: UISettings
           ): Option[(GameState, MKTask)] = {

      val ah   = state.autoHeal
      val now  = System.currentTimeMillis()
      println("start TeamHealing")
      // only fire when cooldown elapsed, status="ready", and rune‐state="free"
      if ((now - ah.lastHealUseTime) >= ah.healingSpellCooldown &&
        ah.statusOfAutoheal == "ready" &&
        ah.stateHealingWithRune   == "free") {

        // helper to pull a friend’s health from spyLevelInfo
        def friendHp(name: String): Int =
          (for {
            spyObj <- (json \ "spyLevelInfo").asOpt[JsObject]
            pi     <- spyObj.value.collectFirst {
              case (_, p) if (p \ "Name").asOpt[String].contains(name) => p
            }
            hp     <- (pi \ "HealthPercent").asOpt[Int]
          } yield hp).getOrElse(100)

        // pull your single FriendsHealSettings (or skip entirely if none)
        settings.healingSettings.friendsHealSettings.headOption.flatMap { cfg =>
          // build a list of (name, spell, threshold) for enabled friends
          val candidates = List(
            (cfg.friend1Name, cfg.friend1HealSpell, cfg.friend1HealHealthPercent),
            (cfg.friend2Name, cfg.friend2HealSpell, cfg.friend2HealHealthPercent),
            (cfg.friend3Name, cfg.friend3HealSpell, cfg.friend3HealHealthPercent)
          ).collect {
            case (name, spell, pct)
              if name.nonEmpty && spell.nonEmpty && pct > 0 =>
              (name, spell, pct)
          }

          // find the first friend who’s below threshold
          candidates.collectFirst {
            case (name, spell, pct) if friendHp(name) < pct => spell
          }
        } match {

          // we found a spell to cast on a friend
          case Some(spell) =>
            // enqueue a TextType action
            val keyboardActions = List(TextType(spell))

            // update timestamp & mark busy
            val updatedAHState = ah.copy(
              lastHealUseTime     = now,
              stateHealingWithRune = "busy"
            )

            val updatedState = state.copy(autoHeal = updatedAHState)

            return Some(updatedState -> MKTask(taskName, MKActions(Nil, keyboardActions)))
          case None =>
            None
        }

      } else {
        None
      }
    }
  }

  private object SelfHealing extends Step {
    private val taskName = "SelfHealing"
    def run(
             state:    GameState,
             json:     JsValue,
             settings: UISettings
           ): Option[(GameState, MKTask)] = {
      val ah = state.autoHeal
      val now = System.currentTimeMillis()
      println(s"[SelfHealing] run() called: status=${ah.statusOfAutoheal}, runeState=${ah.stateHealingWithRune}")

      // only if ready/free
      if (ah.statusOfAutoheal != "ready" || ah.stateHealingWithRune != "free")
        return None

      def findOne(id: Int, count: Int) =
        findItemInContainerSlot14(json, state, id, count).headOption

      val healthPercent = (json \ "characterInfo" \ "HealthPercent").asOpt[Int].getOrElse(100)
      val mana = (json \ "characterInfo" \ "Mana").asOpt[Int].getOrElse(0)
      val manaMax = (json \ "characterInfo" \ "ManaMax").asOpt[Int].getOrElse(1)


      // collect branches with a subName
      val uhBranch: Option[(String, List[MouseAction], List[KeyboardAction], AutoHealState)] = {
        val subTaskName = "uhHealing"
        if (
          settings.healingSettings.uhHealHealthPercent > 0 &&
            healthPercent <= settings.healingSettings.uhHealHealthPercent &&
            mana >= settings.healingSettings.uhHealMana &&
            (now - ah.lastHealUseTime) > (ah.healingUseCooldown + ah.healUseRandomness) &&
            ah.healingRuneContainerName != "not_set"
        ) {
          for {
            runePos <- findOne(3160, 1)
            mpObj <- (json \ "screenInfo" \ "mapPanelLoc" \ "8x6").asOpt[JsObject]
            tx <- (mpObj \ "x").asOpt[Int]
            ty <- (mpObj \ "y").asOpt[Int]
            rx = (runePos \ "x").as[Int]
            ry = (runePos \ "y").as[Int]
          } yield {
            val mouseSeq = if (ah.healingCrosshairActive) {
              List(
                MoveMouse(tx, ty),
                LeftButtonPress(tx, ty),
                LeftButtonRelease(tx, ty)
              )
            } else {
              List(
                MoveMouse(rx, ry),
                RightButtonPress(rx, ry),
                RightButtonRelease(rx, ry),
                MoveMouse(tx, ty),
                LeftButtonPress(tx, ty),
                LeftButtonRelease(tx, ty)
              )
            }
            val newAh = ah.copy(
              lastHealUseTime = now,
              healUseRandomness = generateRandomDelay(ah.strongHeal.strongHealUseTimeRange),
              stateHealingWithRune = "free"
            )
            (subTaskName, mouseSeq, Nil, newAh)
          }
        } else None
      }


      // --- 3) IH rune branch ---
      val ihBranch: Option[(String, List[MouseAction], List[KeyboardAction], AutoHealState)] = {
        val subTaskName = "ihHealing"
        if (
          settings.healingSettings.ihHealHealthPercent > 0 &&
            healthPercent <= settings.healingSettings.ihHealHealthPercent &&
            mana >= settings.healingSettings.ihHealMana
        ) {
          for {
            runePos <- findOne(3152, 1)
            mpObj <- (json \ "screenInfo" \ "mapPanelLoc" \ "8x6").asOpt[JsObject]
            tx <- (mpObj \ "x").asOpt[Int]
            ty <- (mpObj \ "y").asOpt[Int]
            rx = (runePos \ "x").as[Int]
            ry = (runePos \ "y").as[Int]
          } yield {
            val mouseSeq = List(
              MoveMouse(rx, ry),
              RightButtonPress(rx, ry),
              RightButtonRelease(rx, ry),
              MoveMouse(tx, ty),
              LeftButtonPress(tx, ty),
              LeftButtonRelease(tx, ty)
            )
            val newRandom = generateRandomDelay(ah.strongHeal.strongHealUseTimeRange)
            val newAh = ah.copy(
              lastHealUseTime = now,
              healUseRandomness = newRandom,
              stateHealingWithRune = "free" // <<< reset to free
            )
            (subTaskName, mouseSeq, Nil, newAh)
          }
        } else None
      }

      // --- 4) HP potion branch ---
      val hpBranch: Option[(String, List[MouseAction], List[KeyboardAction], AutoHealState)] = {
        val subTaskName = "hpHealing"
        if (
          settings.healingSettings.hPotionHealHealthPercent > 0 &&
            healthPercent <= settings.healingSettings.hPotionHealHealthPercent &&
            mana >= settings.healingSettings.hPotionHealMana
        ) {
          for {
            potionPos <- findOne(2874, 10)
            mpObj <- (json \ "screenInfo" \ "mapPanelLoc" \ "8x6").asOpt[JsObject]
            tx <- (mpObj \ "x").asOpt[Int]
            ty <- (mpObj \ "y").asOpt[Int]
            px = (potionPos \ "x").as[Int]
            py = (potionPos \ "y").as[Int]
          } yield {
            val mouseSeq = List(
              MoveMouse(px, py),
              RightButtonPress(px, py),
              RightButtonRelease(px, py),
              MoveMouse(tx, ty),
              LeftButtonPress(tx, ty),
              LeftButtonRelease(tx, ty)
            )
            val newRandom = generateRandomDelay(ah.strongHeal.strongHealUseTimeRange)
            val newAh = ah.copy(
              lastHealUseTime = now,
              healUseRandomness = newRandom,
              stateHealingWithRune = "free" // <<< reset to free
            )
            (subTaskName, mouseSeq, Nil, newAh)
          }
        } else None
      }


      // MP‐healing branch
      val mpBranch: Option[(String, List[MouseAction], List[KeyboardAction], AutoHealState)] = {
        val subTaskName = "mpHealing"
        println(s"[SelfHealing][$subTaskName] checking MP‐boost (mpBoost=${ah.mpBoost})")

        val mMin = settings.healingSettings.mPotionHealManaMin
        val mMax = settings.healingSettings.mPotionHealManaMax
        val boosting = ah.mpBoost

        // 1) If we were boosting but have reached mMax, turn off and skip
        if (boosting && mMax > 0 && mana >= mMax) {
          println(s"[SelfHealing][$subTaskName] reached max mana ($mana ≥ $mMax), stopping boost")
          val newAh = ah.copy(mpBoost = false)
          val updatedState = state.copy(autoHeal = newAh)
          return Some(updatedState -> MKTask(subTaskName, MKActions.empty))
        }

        // 2) Decide if we should boost now or continue
        val shouldStart = !boosting && mMin > 0 && mana <= mMin
        val shouldContinue = boosting && mMax > 0 && mana < mMax

        if (shouldStart || shouldContinue) {
          println(s"[SelfHealing][$subTaskName] BOOSTING (mana=$mana, mMin=$mMin, mMax=$mMax)")

          // find a potion in slots 0-3
          def findOneInFirstFour(containerName: String, itemId: Int): Option[(Int, Int)] = {
            val maybeSlot = (0 to 3).find { idx =>
              (json \ "containersInfo" \ containerName \ "items" \ s"slot$idx" \ "itemId")
                .asOpt[Int].contains(itemId)
            }
            println(s"[SelfHealing][$subTaskName] findOne slot = $maybeSlot")
            maybeSlot.flatMap { idx =>
              val itemKey = s"item$idx"
              for {
                invPanel <- (json \ "screenInfo" \ "inventoryPanelLoc").asOpt[JsObject]
                panelKey <- invPanel.keys.find(_.contains(containerName))
                cont     <- (invPanel \ panelKey \ "contentsPanel").asOpt[JsObject]
                posObj   <- (cont \ itemKey).asOpt[JsObject]
              } yield ((posObj \ "x").as[Int], (posObj \ "y").as[Int])
            }
          }

          findOneInFirstFour(ah.healingMPContainerName, 2874) match {
            case Some((px, py)) =>
              (json \ "screenInfo" \ "mapPanelLoc" \ "8x6").asOpt[JsObject] match {
                case Some(mpObj) =>
                  val tx = (mpObj \ "x").as[Int]
                  val ty = (mpObj \ "y").as[Int]
                  println(s"[SelfHealing][$subTaskName] using potion at ($px,$py) → target ($tx,$ty)")

                  val seq = List(
                    MoveMouse(px, py),
                    RightButtonPress(px, py),
                    RightButtonRelease(px, py),
                    MoveMouse(tx, ty),
                    LeftButtonPress(tx, ty),
                    LeftButtonRelease(tx, ty)
                  )
                  // mark boost = true
                  val newAh = ah.copy(
                    lastHealUseTime = now,
                    stateHealingWithRune = "free",
                    mpBoost = true
                  )
                  Some((subTaskName, seq, Nil, newAh))

                case None =>
                  println(s"[SelfHealing][$subTaskName] ERROR: mapPanelLoc[8x6] missing")
                  None
              }

            case None =>
              println(s"[SelfHealing][$subTaskName] SKIP: no MP potion in slots 0–3")
              None
          }
        } else {
          println(s"[SelfHealing][$subTaskName] not boosting (mana=$mana, mpBoost=$boosting)")
          None
        }
      }



      // --- 6) Strong heal spell branch ---
      val strongBranch: Option[(String, List[MouseAction], List[KeyboardAction], AutoHealState)] = {
        val subTaskName = "strongHealing"
        settings.healingSettings.spellsHealSettings.headOption.flatMap { cfg =>
          if (
            cfg.strongHealSpell.nonEmpty &&
              healthPercent <= cfg.strongHealHealthPercent &&
              mana >= cfg.strongHealMana
          ) {
            // build the one key-action we want
            val kb: KeyboardAction =
              if (cfg.strongHealHotkeyEnabled)
                PressKey.fromKeyString(cfg.strongHealHotkey)
              else
                TextType(cfg.strongHealSpell)

            // here’s your List of keyboard actions
            val keyboardSeq: List[KeyboardAction] = List(kb)

            val newRandom = generateRandomDelay(ah.strongHeal.strongHealUseTimeRange)
            val newAh = ah.copy(
              lastHealUseTime = now,
              healUseRandomness = newRandom,
              stateHealingWithRune = "free"
            )
            Some((subTaskName, Nil, keyboardSeq, newAh))
          } else None
        }
      }


      // --- 7) Light heal spell branch ---
      val lightBranch: Option[(String, List[MouseAction], List[KeyboardAction], AutoHealState)] = {
        val subTaskName = "lightHealing"
        println("Start light healing")
        settings.healingSettings.spellsHealSettings.headOption.flatMap { cfg =>
          println(s"    lightBranch: cfgOpt.isDefined=true")
          if (
            cfg.lightHealSpell.length > 1 &&
              cfg.lightHealHealthPercent > 0 &&
              healthPercent <= cfg.lightHealHealthPercent &&
              mana >= cfg.lightHealMana
          ) {
            // schedule the delay if needed
            val lowDelay =
              if (ah.lightHeal.lightHealDelayTime == 0)
                generateRandomDelay(ah.lightHeal.lightHealDelayTimeRange)
              else
                ah.lightHeal.lightHealDelayTime

            // only fire when cooldown + randomness + lowDelay has elapsed
            if ((now - ah.lastHealUseTime) > (ah.healingUseCooldown + ah.healUseRandomness + lowDelay)) {
              // build the one key-action we want
              val kb: KeyboardAction =
                if (cfg.lightHealHotkeyEnabled)
                  PressKey.fromKeyString(cfg.lightHealHotkey)
                else
                  TextType(cfg.lightHealSpell)

              // wrap it in a List
              val keyboardSeq: List[KeyboardAction] = List(kb)

              // pick a new random for next time
              val newRandom = generateRandomDelay(ah.lightHeal.lightHealDelayTimeRange)

              // reset any per-branch state
              val newAh = ah.copy(
                lastHealUseTime = now,
                healUseRandomness = newRandom,
                lightHeal = ah.lightHeal.copy(lightHealDelayTime = 0),
                stateHealingWithRune = "free"
              )

              Some((subTaskName, Nil, keyboardSeq, newAh))
            } else None
          } else None
        }
      }

      val chosen = uhBranch
        .orElse(ihBranch)
        .orElse(hpBranch)
        .orElse(mpBranch)
        .orElse(strongBranch)
        .orElse(lightBranch)

      // now they all have the same shape, so this pattern works:
      chosen.map { case (subName, mActs, kActs, newAh) =>
        val newState  = state.copy(autoHeal = newAh)
        val fullName  = s"$taskName - $subName"
        newState -> MKTask(fullName, MKActions(mActs, kActs))
      }
    }
  }
}
