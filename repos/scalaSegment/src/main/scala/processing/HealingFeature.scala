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

  def computeHealingFeature(
                             json: JsValue,
                             settingsRef: Ref[IO, UISettings],
                             stateRef: Ref[IO, GameState]
                           ): IO[(List[MouseAction], List[KeyboardAction])] = for {
    settings <- settingsRef.get
    result <- if (!settings.healingSettings.enabled) {
      IO(println("Healing disabled in computeHealingFeature")).as((Nil, Nil))
    } else {
      stateRef.get.flatMap { state =>
        val (newState, mouseActs, keyActs) = Steps.runFirst(json, settings, state)
        stateRef.set(newState).as((mouseActs, keyActs))
      }
    }
  } yield result

  private object Steps {
    // ordered list of steps
    val all: List[Step] = List(
      SetUpSupplies,
      HandleBackpacks,
      DangerLevelHealing,
      SelfHealing,
      TeamHealing
    )

    def runFirst(json: JsValue, settings: UISettings, state: GameState): (GameState, List[MouseAction], List[KeyboardAction]) =
      all.iterator
        .flatMap(_.run(state, json, settings))
        .map { case (s, a) => (s, a.mouse, a.keyboard) }
        .toSeq
        .headOption
        .getOrElse((state, Nil, Nil))
  }

  private object SetUpSupplies extends Step {
    def run(
             state:    GameState,
             json:     JsValue,
             settings: UISettings
           ): Option[(GameState, MKActions)] = {

      val ah        = state.autoHeal
      val container = ah.healingRuneContainerName
      val status    = ah.statusOfAutoheal

      // === 1) Discover which container holds UH runes ===
      if (container == "not_set") {
        // scan all containers for itemId == 3160
        val foundOpt: Option[String] = (json \ "containersInfo").asOpt[JsObject]
          .flatMap { info =>
            info.fields.collectFirst {
              case (name, details)
                if (details \ "items").asOpt[JsObject]
                  .exists(_.values.exists(item =>
                    (item \ "itemId").asOpt[Int].contains(3160)
                  ))
              => name
            }
          }

        // set the container name (or leave "not_set") and bump to "ready"
        val newState = state.copy(autoHeal = ah.copy(
          healingRuneContainerName   = foundOpt.getOrElse(container),
          statusOfAutoheal  = "ready"
        ))

        Some((newState, MKActions(Nil, Nil)))

        // === 2) If we know the container and are “ready”, check freeSpace & hasParent ===
      } else if (container != "not_set" && status == "ready") {
        val infoOpt = (json \ "containersInfo" \ container).asOpt[JsObject]
        val free    = infoOpt.flatMap(ci => (ci \ "freeSpace").asOpt[Int])
        val parent  = infoOpt.flatMap(ci => (ci \ "hasParent").asOpt[Boolean])

        // only when it’s a full backpack with a parent
        if (free.contains(20) && parent.contains(true)) {
          // build the “go up one level” click‑sequence
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
          // bump into the remove_backpack phase
          val newState = state.copy(autoHeal = ah.copy(
            statusOfAutoheal = "remove_backpack"
          ))

          Some((newState, MKActions(actions, Nil)))

        } else {
          // nothing to do yet
          None
        }

      } else {
        // not our phase
        None
      }
    }
  }


  private object HandleBackpacks extends Step {
    def run(
             state:    GameState,
             json:     JsValue,
             settings: UISettings
           ): Option[(GameState, MKActions)] =
      state.autoHeal.statusOfAutoheal match {

        case "remove_backpack" =>
          // 1) character position as Option[(x,y)]
          val maybeCharLoc: Option[(Int,Int)] =
            for {
              mp <- (json \ "screenInfo" \ "mapPanelLoc" \ "8x6").asOpt[JsObject]
              x  <- (mp \ "x").asOpt[Int]
              y  <- (mp \ "y").asOpt[Int]
            } yield (x, y)

          // 2) build the four‑step remove sequence if everything is present
          val maybeActions: Option[List[MouseAction]] = for {
            (cx, cy)   <- maybeCharLoc
            invObj     <- (json \ "screenInfo" \ "inventoryPanelLoc").asOpt[JsObject]
            matching   <- invObj.keys.find(_.contains(state.autoHeal.healingRuneContainerName))
            item0Obj   <- (invObj \ matching \ "contentsPanel" \ "item0").asOpt[JsObject]
            emptyX     <- (item0Obj \ "x").asOpt[Int]
            emptyY     <- (item0Obj \ "y").asOpt[Int]
          } yield List(
            MoveMouse(emptyX, emptyY),
            LeftButtonPress(emptyX, emptyY),
            MoveMouse(cx,       cy),
            LeftButtonRelease(cx,       cy)
          )

          // 3) fold: if we found them, use them; if not, return no actions
          val mouseActions: List[MouseAction] =
            maybeActions.fold(List.empty[MouseAction])(identity)

          // 4) bump the auto‑heal status
          val newAutoHeal = state.autoHeal.copy(
            statusOfAutoheal = "open_new_backpack"
          )
          val newState = state.copy(autoHeal = newAutoHeal)

          // 5) return only the actions we built (and no keyboard actions)
          Some((
            newState,
            MKActions(mouseActions, Nil)
          ))

        case "open_new_backpack" =>
          // find the new‑backpack slot and build a right‑click sequence
          val maybeOpenSeq: Option[List[MouseAction]] = for {
            invObj   <- (json \ "screenInfo" \ "inventoryPanelLoc").asOpt[JsObject]
            matching <- invObj.keys.find(_.contains(state.autoHeal.healingRuneContainerName))
            item0Obj <- (invObj \ matching \ "contentsPanel" \ "item0").asOpt[JsObject]
            x        <- (item0Obj \ "x").asOpt[Int]
            y        <- (item0Obj \ "y").asOpt[Int]
          } yield List(
            MoveMouse(x, y),
            RightButtonPress(x, y),
            RightButtonRelease(x, y)
          )

          val openActions = maybeOpenSeq.getOrElse(Nil)

          // bump to “verifying” so the next loop can check runes
          val newState2 = state.copy(
            autoHeal = state.autoHeal.copy(statusOfAutoheal = "verifying")
          )

          Some((newState2, MKActions(openActions, Nil)))

        case "verifying" =>
          // Look up the container info, then check items for UH runes (id=3160, subtype=1)
          val containsUH: Boolean = (for {
            containerInfo <- (json \ "containersInfo" \ state.autoHeal.healingRuneContainerName).asOpt[JsObject]
            itemsObj      <- (containerInfo \ "items").asOpt[JsObject]
          } yield {
            itemsObj.fields.exists { case (_, itemInfo) =>
              (itemInfo \ "itemId").asOpt[Int].contains(3160) &&
                (itemInfo \ "itemSubType").asOpt[Int].contains(1)
            }
          }).getOrElse(false)

          // If found → ready, else stay in verifying
          val nextStatus = if (containsUH) "ready" else "verifying"

          val newState3 = state.copy(
            autoHeal = state.autoHeal.copy(statusOfAutoheal = nextStatus)
          )

          // no mouse or keyboard actions for verification
          Some((newState3, MKActions(Nil, Nil)))


        case _ =>
          None
      }
  }



  private object DangerLevelHealing extends Step {
    def run(
             state:    GameState,
             json:     JsValue,
             settings: UISettings
           ): Option[(GameState, MKActions)] =
      state.autoTarget.dangerLevelHealing match {

        case "high" =>
          // 1) Read current crosshair flag from JSON
          val isActiveFromJson: Boolean =
            (json \ "characterInfo" \ "IsCrosshairActive")
              .asOpt[Boolean]
              .getOrElse(false)

          // 2) Update state with that flag
          val state1: GameState =
            state.copy(
              autoHeal = state.autoHeal.copy(
                healingCrosshairActive = isActiveFromJson
              )
            )

          // 3) Count how many of each creature are in battle
          val battleCreatures: Map[String, Int] =
            (json \ "battleInfo").asOpt[JsObject]
              .map(_.fields.toSeq.map(_._2))
              .getOrElse(Seq.empty)
              .flatMap(obj =>
                (obj \ "Name").asOpt[String].toList
              )
              .groupBy(identity)
              .view
              .mapValues(_.size)
              .toMap

          // 4) Does any “danger creature” still meet its threshold?
          val dangerStill: Boolean =
            state1.autoTarget.dangerCreaturesList.exists { cr =>
              val seen = battleCreatures.getOrElse(cr.name, 0)
              if (cr.count == 0) seen >= 1 else seen >= cr.count
            }

          // 5) Build an “activate crosshair” sequence iff we’re in danger and not already active
          val activateSeq: Option[List[MouseAction]] =
            if (!isActiveFromJson && dangerStill)
              for {
                mp      <- (json \ "screenInfo" \ "mapPanelLoc" \ "8x6").asOpt[JsObject]
                tx      <- (mp   \ "x").asOpt[Int]
                ty      <- (mp   \ "y").asOpt[Int]
                runePos <- findItemInContainerSlot14(json, state1, 3160, 1).headOption
                rx       = (runePos \ "x").as[Int]
                ry       = (runePos \ "y").as[Int]
              } yield List(
                MoveMouse(rx, ry),
                RightButtonPress(rx, ry),
                RightButtonRelease(rx, ry),
                MoveMouse(tx, ty)
              )
            else None

          // 6) Build a “deactivate crosshair” sequence iff we’re safe but it’s still active
          val deactivateSeq: Option[List[MouseAction]] =
            if (isActiveFromJson && !dangerStill)
              Some(List(RightButtonRelease(0, 0)))
            else
              None

          // 7) Choose which actions to run (activation has priority)
          val mouseActions: List[MouseAction] =
            activateSeq.orElse(deactivateSeq).getOrElse(Nil)

          // 8) Compute the new crosshair‐active flag
          val newCrosshairActive: Boolean =
            activateSeq.isDefined || (deactivateSeq.isDefined match {
              case true  => false
              case false => isActiveFromJson
            })

          // 9) Update the state and return
          val newState = state1.copy(
            autoHeal = state1.autoHeal.copy(
              healingCrosshairActive = newCrosshairActive
            )
          )

          Some((newState, MKActions(mouseActions, Nil)))

        case _ =>
          None
      }
  }
  private object TeamHealing extends Step {
    def run(
             state:    GameState,
             json:     JsValue,
             settings: UISettings
           ): Option[(GameState, MKActions)] = {

      val ah   = state.autoHeal
      val now  = System.currentTimeMillis()

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
            val kbActs = List(TextType(spell))

            // update timestamp & mark busy
            val newAuto = ah.copy(
              lastHealUseTime     = now,
              stateHealingWithRune = "busy"
            )
            val newState = state.copy(autoHeal = newAuto)

            Some((newState, MKActions(Nil, kbActs)))

          // no friend needs healing right now
          case None =>
            None
        }

      } else {
        None
      }
    }
  }


  private object SelfHealing extends Step {
    def run(
             state:    GameState,
             json:     JsValue,
             settings: UISettings
           ): Option[(GameState, MKActions)] = {
      val ah  = state.autoHeal
      val now = System.currentTimeMillis()

      // only run if we're “ready” and not already healing
      if (ah.statusOfAutoheal == "ready" && ah.stateHealingWithRune == "free") {

        // 1) Grab health and mana %
        val healthPercent= (json \ "characterInfo" \ "HealthPercent").asOpt[Int].getOrElse(100)
        val mana         = (json \ "characterInfo" \ "Mana").         asOpt[Int].getOrElse(0)
        val manaMax      = (json \ "characterInfo" \ "ManaMax").      asOpt[Int].getOrElse(1)
        val manaPercent  = (mana.toDouble / manaMax * 100).toInt

        // helper to find a single item slot
        def findOne(id: Int, count: Int) =
          findItemInContainerSlot14(json, state, id, count).headOption

        // --- 2) UH rune branch ---
        val uhBranch: Option[(List[MouseAction], List[KeyboardAction], AutoHealState)] =
          if (
            settings.healingSettings.uhHealHealthPercent > 0 &&
              healthPercent <= settings.healingSettings.uhHealHealthPercent &&
              mana >= settings.healingSettings.uhHealMana &&
              (now - ah.lastHealUseTime) > (ah.healingUseCooldown + ah.healUseRandomness)
          ) {
            for {
              runePos <- findOne(3160, 1)
              mpObj   <- (json \ "screenInfo" \ "mapPanelLoc" \ "8x6").asOpt[JsObject]
              tx      <- (mpObj   \ "x").asOpt[Int]
              ty      <- (mpObj   \ "y").asOpt[Int]
              rx      =  (runePos \ "x").as[Int]
              ry      =  (runePos \ "y").as[Int]
            } yield {
              // crosshair‐active changes the sequence
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
              val newRandom = generateRandomDelay(ah.strongHeal.strongHealUseTimeRange)
              val newAh = ah.copy(
                stateHealingWithRune = "healing",
                lastHealUseTime      = now,
                healUseRandomness    = newRandom
              )
              (mouseSeq, Nil, newAh)
            }
          } else None

        // --- 3) IH rune branch ---
        val ihBranch: Option[(List[MouseAction], List[KeyboardAction], AutoHealState)] =
          if (
            settings.healingSettings.ihHealHealthPercent > 0 &&
              healthPercent <= settings.healingSettings.ihHealHealthPercent &&
              mana >= settings.healingSettings.ihHealMana
          ) {
            for {
              runePos <- findOne(3152, 1)
              mpObj   <- (json \ "screenInfo" \ "mapPanelLoc" \ "8x6").asOpt[JsObject]
              tx      <- (mpObj   \ "x").asOpt[Int]
              ty      <- (mpObj   \ "y").asOpt[Int]
              rx      =  (runePos \ "x").as[Int]
              ry      =  (runePos \ "y").as[Int]
            } yield {
              val seq = List(
                MoveMouse(rx, ry),
                RightButtonPress(rx, ry),
                RightButtonRelease(rx, ry),
                MoveMouse(tx, ty),
                LeftButtonPress(tx, ty),
                LeftButtonRelease(tx, ty)
              )
              val newAh = ah.copy(
                lastHealUseTime      = now,
                stateHealingWithRune = "healing"
              )
              (seq, Nil, newAh)
            }
          } else None

        // --- 4) HP potion branch ---
        val hpBranch: Option[(List[MouseAction], List[KeyboardAction], AutoHealState)] =
          if (
            settings.healingSettings.hPotionHealHealthPercent > 0 &&
              healthPercent <= settings.healingSettings.hPotionHealHealthPercent &&
              mana >= settings.healingSettings.hPotionHealMana
          ) {
            for {
              potionPos <- findOne(2874, 10)
              mpObj     <- (json \ "screenInfo" \ "mapPanelLoc" \ "8x6").asOpt[JsObject]
              tx        <- (mpObj   \ "x").asOpt[Int]
              ty        <- (mpObj   \ "y").asOpt[Int]
              px        =  (potionPos \ "x").as[Int]
              py        =  (potionPos \ "y").as[Int]
            } yield {
              val seq = List(
                MoveMouse(px, py),
                RightButtonPress(px, py),
                RightButtonRelease(px, py),
                MoveMouse(tx, ty),
                LeftButtonPress(tx, ty),
                LeftButtonRelease(tx, ty)
              )
              val newAh = ah.copy(
                lastHealUseTime      = now,
                stateHealingWithRune = "healing"
              )
              (seq, Nil, newAh)
            }
          } else None

        // --- 5) MP potion branch ---
        val mpBranch: Option[(List[MouseAction], List[KeyboardAction], AutoHealState)] =
          if (
            settings.healingSettings.mPotionHealManaMin > 0 &&
              mana >= settings.healingSettings.mPotionHealManaMin
          ) {
            for {
              potionPos <- findOne(2874, 7)
              mpObj     <- (json \ "screenInfo" \ "mapPanelLoc" \ "8x6").asOpt[JsObject]
              tx        <- (mpObj   \ "x").asOpt[Int]
              ty        <- (mpObj   \ "y").asOpt[Int]
              px        =  (potionPos \ "x").as[Int]
              py        =  (potionPos \ "y").as[Int]
            } yield {
              val seq = List(
                MoveMouse(px, py),
                RightButtonPress(px, py),
                RightButtonRelease(px, py),
                MoveMouse(tx, ty),
                LeftButtonPress(tx, ty),
                LeftButtonRelease(tx, ty)
              )
              val newAh = ah.copy(
                lastHealUseTime      = now,
                stateHealingWithRune = "healing"
              )
              (seq, Nil, newAh)
            }
          } else None

        // --- 6) Strong heal spell branch ---
        val strongBranch: Option[(List[MouseAction], List[KeyboardAction], AutoHealState)] = {
          val cfgOpt = settings.healingSettings.spellsHealSettings.headOption
          cfgOpt.flatMap { cfg =>
            if (
              cfg.strongHealSpell.length > 1 &&
                cfg.strongHealHealthPercent > 0 &&
                healthPercent <= cfg.strongHealHealthPercent &&
                mana >= cfg.strongHealMana
            ) {
              val kb: KeyboardAction =
                if (cfg.strongHealHotkeyEnabled)
                  PressKey.fromKeyString(cfg.strongHealHotkey)
                else
                  TextType(cfg.strongHealSpell)

              val newAh = ah.copy(
                lastHealUseTime      = now,
                stateHealingWithRune = "healing"
              )
              Some((Nil, List(kb), newAh))
            } else None
          }
        }

        // --- 7) Light heal spell branch ---
        val lightBranch: Option[(List[MouseAction], List[KeyboardAction], AutoHealState)] = {
          val cfgOpt = settings.healingSettings.spellsHealSettings.headOption
          cfgOpt.flatMap { cfg =>
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

              // only fire when the combined cooldown + randomness + lowDelay has elapsed
              if ((now - ah.lastHealUseTime) > (ah.healingUseCooldown + ah.healUseRandomness + lowDelay)) {
                val kb: KeyboardAction =
                  if (cfg.lightHealHotkeyEnabled)
                    PressKey.fromKeyString(cfg.lightHealHotkey)
                  else
                    TextType(cfg.lightHealSpell)

                val newRandom = generateRandomDelay(ah.lightHeal.lightHealDelayTimeRange)

                val newLightHeal = state.autoHeal.lightHeal.copy(
                  lightHealDelayTime = 0
                )

                // reset lowHealDelayTime once fired
                val newAh = ah.copy(
                  stateHealingWithRune = "healing",
                  lastHealUseTime      = now,
                  healUseRandomness    = newRandom,
                  lightHeal = newLightHeal
                )

                Some((Nil, List(kb), newAh))
              } else None
            } else None
          }
        }

        // chain them in priority order
        val chosen = uhBranch
          .orElse(ihBranch)
          .orElse(hpBranch)
          .orElse(mpBranch)
          .orElse(strongBranch)
          .orElse(lightBranch)

        chosen.map { case (mActs, kActs, newAh) =>
          val newState = state.copy(autoHeal = newAh)
          (newState, MKActions(mActs, kActs))
        }

      } else {
        None
      }
    }
  }




}






//
//object HealingFeature {
//
//  def computeHealingFeature(
//                             json: JsValue,
//                             settingsRef: Ref[IO, UISettings],
//                             stateRef: Ref[IO, GameState]
//                           ): IO[(List[MouseAction], List[KeyboardAction])] = {
//    for {
//      settings <- settingsRef.get
//      result <- {
//        if (!settings.healingSettings.enabled) {
//          IO {
//            println("Healing disabled in computeHealingFeature")
//            (List.empty[MouseAction], List.empty[KeyboardAction])
//          }
//        } else {
//          stateRef.modify { state =>
//
//            val healthPercent = (json \ "characterInfo" \ "healthPercent").asOpt[Int].getOrElse(100)
//            val mana = (json \ "characterInfo" \ "mana").asOpt[Int].getOrElse(0)
//            val now = System.currentTimeMillis()
//
//
//
//
//
//            val healSettingsOpt = settings.healingSettings.spellsHealSettings.headOption
//
//            val (updatedState, actions) = healSettingsOpt match {
//              case Some(healSettings) if shouldLightHeal(settings, healthPercent, mana) =>
//                val cooldownReady = now - state.autoHeal.lastHealUseTime > (
//                  state.autoHeal.healingUseCooldown +
//                    state.autoHeal.runeUseRandomness +
//                    state.autoHeal.lightHeal.lightHealDelayTime
//                  )
//
//                if (state.autoHeal.lightHeal.lightHealDelayTime == 0) {
//                  val newDelay = generateRandomDelay(state.autoHeal.lightHeal.lightHealDelayTimeRange)
//                  val updatedLightHeal = state.autoHeal.lightHeal.copy(
//                    lightHealDelayTime = newDelay
//                  )
//                  val updatedAutoHeal = state.autoHeal.copy(
//                    lightHeal = updatedLightHeal,
//                    lastHealUseTime = now
//                  )
//                  val updated = state.copy(autoHeal = updatedAutoHeal)
//
//                  (updated, (List.empty[MouseAction], List.empty[KeyboardAction]))
//
//                } else if (cooldownReady) {
//                  val keyboardActions: List[KeyboardAction] =
//                    if (healSettings.lightHealHotkeyEnabled)
//                      List(PressKey.fromKeyString(healSettings.lightHealHotkey))
//                    else
//                      List(TextType(healSettings.lightHealSpell))
//
//                  val newDelay = generateRandomDelay(state.autoHeal.lightHeal.lightHealDelayTimeRange)
//                  val updatedLightHeal = state.autoHeal.lightHeal.copy(
//                    lightHealDelayTime = newDelay
//                  )
//                  val updatedAutoHeal = state.autoHeal.copy(
//                    lightHeal = updatedLightHeal,
//                    lastHealUseTime = now
//                  )
//                  val updated = state.copy(autoHeal = updatedAutoHeal)
//
//                  (updated, (List.empty[MouseAction], keyboardActions))
//
//                } else {
//                  println("Healing cannot be used yet due to cooldown.")
//                  (state, (List.empty[MouseAction], List.empty[KeyboardAction]))
//                }
//
//              case _ =>
//                (state, (List.empty[MouseAction], List.empty[KeyboardAction]))
//            }
//
//            (updatedState, actions)
//          }
//        }
//      }
//    } yield result
//  }
//
//  private def shouldLightHeal(
//                               settings: UISettings,
//                               healthPercent: Int,
//                               mana: Int
//                             ): Boolean = {
//    settings.healingSettings.spellsHealSettings.headOption.exists { spell =>
//      spell.lightHealSpell.nonEmpty &&
//        spell.lightHealHealthPercent > 0 &&
//        healthPercent <= spell.lightHealHealthPercent &&
//        mana >= spell.lightHealMana
//    }
//  }
//}
