package processing

import cats.effect.{IO, Ref}
import play.api.libs.json.{JsObject, JsSuccess, JsValue, Json}
import utils.SettingsUtils.UISettings
import mouse._
import keyboard._
import processing.Fishing.{extractItemInfoOpt, extractStackSize}
import processing.Process.{extractOkButtonPosition, timeToRetry}
import utils.GameState
import cats.syntax.all._
import processing.HealingFeature.{DangerLevelHealing, HandleBackpacks, SelfHealing, SetUpSupplies, Steps, TeamHealing}
import utils.ProcessingUtils.{MKActions, MKTask, Step}

object FishingFeature {


  def run(json: JsValue, settings: UISettings, state: GameState):
  (GameState, List[MKTask]) =
    if (!settings.fishingSettings.enabled) (state, Nil)
    else {
      val (s, maybeTask) = Steps.runFirst(json, settings, state)
      (s, maybeTask.toList)
    }

  private object Steps {
    // ordered list of steps
    val allSteps: List[Step] = List(
      CheckForOkButton,
      HandleFish,
      UseFishingRod
    )


    def runFirst(
                  json:     JsValue,
                  settings: UISettings,
                  startState:    GameState
                ): (GameState, Option[MKTask]) = {
      @annotation.tailrec
      def loop(
                remaining: List[Step],
                current:   GameState
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

  private object UseFishingRod extends Step {
    private val taskName = "UseFishingRod"

    def run(
             state:    GameState,
             json:     JsValue,
             settings: UISettings
           ): Option[(GameState, MKTask)] = {
      import scala.util.Random

      // DEBUG: entry
      println(s"[UseFishingRod] Entered step; state.general=${state.general}, state.fishing=${state.fishing}")

      val rects = settings.fishingSettings.selectedRectangles.toList
      // DEBUG: show what rectangles we see
      println(s"[UseFishingRod] selectedRectangles = $rects")

      // only run if there are tiles selected
      if (rects.isEmpty) {
        println("[UseFishingRod] No selected rectangles → skipping UseFishingRod")
        None
      } else {
        // pick one at random
        val randomTileId = Random.shuffle(rects).head
        println(s"[UseFishingRod] Picked tile ID = $randomTileId")

        // look up coords, defaulting to 0
        val targetX = (json \ "screenInfo" \ "mapPanelLoc" \ randomTileId \ "x").asOpt[Int].getOrElse {
          println(s"[UseFishingRod] WARNING: targetX missing for $randomTileId, defaulting to 0")
          0
        }
        val targetY = (json \ "screenInfo" \ "mapPanelLoc" \ randomTileId \ "y").asOpt[Int].getOrElse {
          println(s"[UseFishingRod] WARNING: targetY missing for $randomTileId, defaulting to 0")
          0
        }
        println(s"[UseFishingRod] target coords = ($targetX, $targetY)")

        val arrowsX = (json \ "screenInfo" \ "inventoryPanelLoc" \ "inventoryWindow" \ "contentsPanel" \ "slot10" \ "x")
          .asOpt[Int].getOrElse {
            println("[UseFishingRod] WARNING: arrowsX missing, defaulting to 0")
            0
          }
        val arrowsY = (json \ "screenInfo" \ "inventoryPanelLoc" \ "inventoryWindow" \ "contentsPanel" \ "slot10" \ "y")
          .asOpt[Int].getOrElse {
            println("[UseFishingRod] WARNING: arrowsY missing, defaulting to 0")
            0
          }
        println(s"[UseFishingRod] arrow coords = ($arrowsX, $arrowsY)")

        // Always emit the same fishing‐rod sequence
        val mouseActions = List(
          MoveMouse(arrowsX, arrowsY),
          RightButtonPress(arrowsX, arrowsY),
          RightButtonRelease(arrowsX, arrowsY),
          MoveMouse(targetX,  targetY),
          LeftButtonPress(targetX,  targetY),
          LeftButtonRelease(targetX,  targetY)
        )
        println(s"[UseFishingRod] Emitting MKTask with actions: $mouseActions")

        Some(state -> MKTask(taskName, MKActions(mouseActions, Nil)))
      }
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


  private object HandleFish extends Step {
    private val taskName = "HandleFish"

    def run(
             state:    GameState,
             json:     JsValue,
             settings: UISettings
           ): Option[(GameState, MKTask)] = {
      val fishId = 3578

      // helper to find one item slot's screen coordinates
      def findOneItem(container: String, slot: String) =
        extractItemInfoOpt(json, container, "contentsPanel", slot.replace("slot", "item"))


      // ——— 1) THROW OUT if count > 10 ———
      val throwOpt: Option[(GameState, MKTask)] = for {
        // 1a) grab the top‐level containersInfo object
        ciObj          <- (json \ "containersInfo").asOpt[JsObject]

        // 1b) pick the first container (or you could .find(...) on fields)
        (cname, cdVal) <- ciObj.fields.headOption

        // 1c) make sure that container value is an object
        cdObj          <- cdVal.asOpt[JsObject]

        // 1d) dig into its "items" field
        itemsObj       <- (cdObj \ "items").asOpt[JsObject]

        // 1e) find the *first* slot whose id==fishId && count>10
        (slot, itObj)  <- itemsObj.fields.find { case (_, it) =>
          (it \ "itemId").asOpt[Int].contains(fishId) &&
            (it \ "itemCount").asOpt[Int].exists(_ > 10)
        }

        // 1f) get that slot’s screen‐coords
        info           <- findOneItem(cname, slot)
        x              <- (info \ "x").asOpt[Int]
        y              <- (info \ "y").asOpt[Int]

        // 1g) choose a random throw‐out rectangle, or fallback to "8x6"
        target         <- {
          // try the configured rectangles first
          val primary = settings.fishingSettings.fishThrowoutRectangles
            .headOption
            .flatMap { rnd =>
              (json \ "screenInfo" \ "mapPanelLoc" \ rnd).asOpt[JsObject]
                .flatMap(mp => for {
                  tx <- (mp \ "x").asOpt[Int]
                  ty <- (mp \ "y").asOpt[Int]
                } yield (tx, ty))
            }
          // fallback
          val fallback = (json \ "screenInfo" \ "mapPanelLoc" \ "8x6")
            .asOpt[JsObject]
            .flatMap(mp => for {
              tx <- (mp \ "x").asOpt[Int]
              ty <- (mp \ "y").asOpt[Int]
            } yield (tx, ty))

          // end up with an Option[(Int,Int)]
          primary.orElse(fallback)
        }
      } yield {
        val (tx, ty) = target
        val subTask  = "throwOut"
        val mouseSeq = List(
          MoveMouse(x, y),
          LeftButtonPress(x, y),
          MoveMouse(tx, ty),
          LeftButtonRelease(tx, ty),
        )

        // no state‐change here, so keep `state` as‐is
        state -> MKTask(s"$taskName - $subTask", MKActions(mouseSeq, Nil))
      }

      // slot collector (unchanged)
      def collectSlots(pred: JsObject => Boolean): List[(String, String)] =
        (json \ "containersInfo").asOpt[JsObject].toList
          .flatMap(_.fields.toList)
          .flatMap { case (cname, cd) =>
            (cd \ "items").asOpt[JsObject].toList
              .flatMap(_.fields.collect {
                case (slot, itObj: JsObject) if pred(itObj) => (cname, slot)
              })
          }

      // 2) MERGE TWO SINGLES
      val mergeSinglesOpt: Option[(GameState, MKTask)] = {
        val subTask = "mergeTwoSingleFishes"
        val singles = collectSlots(it =>
          (it \ "itemId").asOpt[Int].contains(fishId) &&
            (it \ "itemCount").asOpt[Int].contains(1)
        )
        if (singles.size >= 2) {
          val List((c1, s1), (c2, s2)) = singles.sortBy(_._2).take(2)
          for {
            i1 <- findOneItem(c1, s1)
            i2 <- findOneItem(c2, s2)
          } yield {
            val x1 = (i1 \ "x").asOpt[Int].getOrElse(0)
            val y1 = (i1 \ "y").asOpt[Int].getOrElse(0)
            val x2 = (i2 \ "x").asOpt[Int].getOrElse(0)
            val y2 = (i2 \ "y").asOpt[Int].getOrElse(0)
            val mouseActions = List(
              MoveMouse(x1, y1),
              LeftButtonPress(x1, y1),
              MoveMouse(x2, y2),
              LeftButtonRelease(x2, y2)
            )
            state -> MKTask(s"$taskName - $subTask", MKActions(mouseActions, Nil))
          }
        } else None
      }

      // 3) MERGE SINGLE ONTO STACK
      val mergeOntoStackOpt: Option[(GameState, MKTask)] = {
        val subTask = "mergeSingleToStack"
        val singles = collectSlots(it =>
          (it \ "itemId").asOpt[Int].contains(fishId) &&
            (it \ "itemCount").asOpt[Int].contains(1)
        )
        val stacks = collectSlots(it =>
          (it \ "itemId").asOpt[Int].contains(fishId) &&
            (it \ "itemCount").asOpt[Int].exists(c => c > 1 && c < 100)
        )
        if (singles.size == 1 && stacks.nonEmpty) {
          val (c1, s1) = singles.head
          val (c2, s2) = stacks.head
          for {
            i1 <- findOneItem(c1, s1)
            i2 <- findOneItem(c2, s2)
          } yield {
            val x1 = (i1 \ "x").asOpt[Int].getOrElse(0)
            val y1 = (i1 \ "y").asOpt[Int].getOrElse(0)
            val x2 = (i2 \ "x").asOpt[Int].getOrElse(0)
            val y2 = (i2 \ "y").asOpt[Int].getOrElse(0)
            val mouseActions = List(
              MoveMouse(x1, y1),
              LeftButtonPress(x1, y1),
              MoveMouse(x2, y2),
              LeftButtonRelease(x2, y2)
            )
            state -> MKTask(s"$taskName - $subTask", MKActions(mouseActions, Nil))
          }
        } else None
      }

      // 4) MERGE TWO STACKS
      val mergeStacksOpt: Option[(GameState, MKTask)] = {
        val subTask = "mergeStacks"
        val stacks = collectSlots(it =>
          (it \ "itemId").asOpt[Int].contains(fishId) &&
            (it \ "itemCount").asOpt[Int].exists(c => c > 1 && c < 100)
        )
        if (stacks.size >= 2) {
          val List((c1, s1), (c2, s2)) = stacks.sortBy(_._2).take(2)
          for {
            i1 <- findOneItem(c1, s1)
            i2 <- findOneItem(c2, s2)
          } yield {
            val x1 = (i1 \ "x").asOpt[Int].getOrElse(0)
            val y1 = (i1 \ "y").asOpt[Int].getOrElse(0)
            val x2 = (i2 \ "x").asOpt[Int].getOrElse(0)
            val y2 = (i2 \ "y").asOpt[Int].getOrElse(0)
            val mouseActions = List(
              MoveMouse(x1, y1),
              LeftButtonPress(x1, y1),
              MoveMouse(x2, y2),
              LeftButtonRelease(x2, y2)
            )
            state -> MKTask(s"$taskName - $subTask", MKActions(mouseActions, Nil))
          }
        } else None
      }

      // stop at the first non-empty
      throwOpt
        .orElse(mergeSinglesOpt)
        .orElse(mergeOntoStackOpt)
        .orElse(mergeStacksOpt)
    }
  }




}
