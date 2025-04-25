package processing

import cats.effect.{IO, Ref}
import play.api.libs.json.{JsObject, JsValue}
import utils.SettingsUtils.UISettings
import mouse._
import keyboard._
import processing.Fishing.{extractItemInfoOpt, extractStackSize}
import processing.Process.{extractOkButtonPosition, timeToRetry}
import utils.GameState
import cats.syntax.all._
import processing.HealingFeature.{DangerLevelHealing, HandleBackpacks, SelfHealing, SetUpSupplies, Steps, TeamHealing}
import utils.ProcessingUtils.{MKActions, Step}

object FishingFeature {



  def run(json: JsValue, settings: UISettings, state: GameState):
  (GameState, List[MouseAction], List[KeyboardAction]) =
    if (!settings.fishingSettings.enabled) (state, Nil, Nil)
    else Steps.runFirst(json, settings, state)

  private object Steps {
    // ordered list of steps
    val all: List[Step] = List(
      CheckForOkButton,
      HandleFish,
      UseFishingRod
    )


    def runFirst(json: JsValue, settings: UISettings, start: GameState)
    : (GameState, List[MouseAction], List[KeyboardAction]) = {

      // We'll carry along (currentState, maybeActions)
      // as we fold through the steps.
      @annotation.tailrec
      def loop(remaining: List[Step],
               currentState: GameState,
               carriedActions: MKActions): (GameState, MKActions) = remaining match {

        // 1) If we've already gotten actions, stop immediately.
        case _ if carriedActions.mouse.nonEmpty || carriedActions.keyboard.nonEmpty =>
          (currentState, carriedActions)

        // 2) No more steps? return what we have so far.
        case Nil =>
          (currentState, MKActions(Nil, Nil))

        // 3) Try the next step
        case step :: rest =>
          step.run(currentState, json, settings) match {
            // a) Step wants to update state *and* emits actions:
            case Some((newState, actions)) if actions.mouse.nonEmpty || actions.keyboard.nonEmpty =>
              // we break and return immediately
              (newState, actions)

            // b) Step updates state but has no actions: carry on
            case Some((newState, MKActions(Nil, Nil))) =>
              loop(rest, newState, MKActions(Nil, Nil))

            // c) Step does nothing: carry on with same state
            case None =>
              loop(rest, currentState, MKActions(Nil, Nil))
          }
      }

      // run the loop
      val (finalState, MKActions(m,k)) = loop(all, start, MKActions(Nil, Nil))
      (finalState, m, k)
    }
  }

  private object CheckForOkButton extends Step {
    def run(
             state:    GameState,
             json:     JsValue,
             settings: UISettings
           ): Option[(GameState, MKActions)] = {
      // current timestamp
      val now = System.currentTimeMillis()

      // Try to extract an Ok button position
      extractOkButtonPosition(json).flatMap { case (posX, posY) =>
        // how long we last clicked “Ok”
        val lastSent  = state.fishing.lastFishingCommandSent
        // minimum milliseconds between clicks
        val retryMid  = 3000

        // if first time (0) or delay expired
        if (lastSent == 0 || (now - lastSent) >= retryMid) {
          // build our three‐step click
          val mouseSeq = List(
            MoveMouse(posX, posY),
            LeftButtonPress(posX, posY),
            LeftButtonRelease(posX, posY)
          )

          // update both General and Fishing sub‐states
          val newGeneral = state.general.copy(
            lastActionCommand   = Some("pressOKButton"),
            lastActionTimestamp = Some(now)
          )
          val newFishing = state.fishing.copy(
            lastFishingCommandSent = now
          )

          val newState = state.copy(
            general = newGeneral,
            fishing = newFishing
          )

          Some((newState, MKActions(mouseSeq, Nil)))
        } else {
          // too soon → skip this step, let next step run
          None
        }
      }
    }
  }

  private object HandleFish extends Step {
    def run(
             state:    GameState,
             json:     JsValue,
             settings: UISettings
           ): Option[(GameState, MKActions)] = {
      import scala.util.Random

      val fishId = 3578

      // helper to find one item slot's screen coordinates
      def findOneItem(container: String, slot: String) =
        extractItemInfoOpt(json, container, "contentsPanel", slot.replace("slot", "item"))

      val throwOpt: Option[(GameState, MKActions)] = (for {
        // each of these is now a Seq[_]
        ci        <- (json \ "containersInfo").asOpt[JsObject].toSeq
        (cname, cd) <- ci.fields.toSeq
        items     <- (cd \ "items").asOpt[JsObject].toSeq
        (slot, it) <- items.fields.toSeq
        id        <- (it \ "itemId").asOpt[Int].toSeq        if id == fishId
        cnt       <- (it \ "itemCount").asOpt[Int].toSeq     if cnt > 10
        info      <- findOneItem(cname, slot).toSeq
        x = (info \ "x").as[Int]
        y = (info \ "y").as[Int]

        // build your target Seq[(Int,Int)] similarly
        target    <- {
          val rects = settings.fishingSettings.fishThrowoutRectangles.toList
          if (rects.nonEmpty) {
            val rnd = scala.util.Random.shuffle(rects).head
            for {
              tx <- (json \ "screenInfo" \ "mapPanelLoc" \ rnd \ "x").asOpt[Int].toSeq
              ty <- (json \ "screenInfo" \ "mapPanelLoc" \ rnd \ "y").asOpt[Int].toSeq
            } yield (tx, ty)
          } else {
            for {
              mp <- (json \ "screenInfo" \ "mapPanelLoc" \ "8x6").asOpt[JsObject].toSeq
              tx <- (mp   \ "x").asOpt[Int].toSeq
              ty <- (mp   \ "y").asOpt[Int].toSeq
            } yield (tx, ty)
          }
        }
      } yield {
        val (tx, ty) = target
        val seq = List(
          MoveMouse(x, y),
          RightButtonPress(x, y),
          RightButtonRelease(x, y),
          MoveMouse(tx, ty),
          LeftButtonPress(tx, ty),
          LeftButtonRelease(tx, ty)
        )
        (state, MKActions(seq, Nil))
      }).headOption

      // helper: collect slots by predicate
      def collectSlots(pred: JsObject => Boolean): List[(String, String)] =
        (json \ "containersInfo").asOpt[JsObject].toList
          .flatMap(_.fields.toList)
          .flatMap { case (cname, cd) =>
            (cd \ "items").asOpt[JsObject].toList
              .flatMap(_.fields.collect {
                case (slot, itObj: JsObject) if pred(itObj) => (cname, slot)
              })
          }

      // 2) MERGE TWO SINGLES (count == 1)
      val mergeSinglesOpt: Option[(GameState, MKActions)] = {
        val singles = collectSlots { it =>
          (it \ "itemId").asOpt[Int].contains(fishId) &&
            (it \ "itemCount").asOpt[Int].contains(1)
        }
        if (singles.size >= 2) {
          val List((c1, s1), (c2, s2)) = singles.sortBy(_._2).take(2)
          for {
            i1 <- findOneItem(c1, s1)
            i2 <- findOneItem(c2, s2)
          } yield {
            val x1 = (i1 \ "x").as[Int]; val y1 = (i1 \ "y").as[Int]
            val x2 = (i2 \ "x").as[Int]; val y2 = (i2 \ "y").as[Int]
            val seq = List(
              MoveMouse(x1, y1),
              LeftButtonPress(x1, y1),
              MoveMouse(x2, y2),
              LeftButtonRelease(x2, y2)
            )
            (state, MKActions(seq, Nil))
          }
        } else None
      }

      // 3) MERGE SINGLE ONTO STACK
      val mergeOntoStackOpt: Option[(GameState, MKActions)] = {
        val singles = collectSlots { it =>
          (it \ "itemId").asOpt[Int].contains(fishId) &&
            (it \ "itemCount").asOpt[Int].contains(1)
        }
        val stacks = collectSlots { it =>
          (it \ "itemId").asOpt[Int].contains(fishId) &&
            (it \ "itemCount").asOpt[Int].exists(c => c > 1 && c < 100)
        }
        if (singles.size == 1 && stacks.nonEmpty) {
          val (c1, s1) = singles.head
          val (c2, s2) = stacks.head
          for {
            i1 <- findOneItem(c1, s1)
            i2 <- findOneItem(c2, s2)
          } yield {
            val x1 = (i1 \ "x").as[Int]; val y1 = (i1 \ "y").as[Int]
            val x2 = (i2 \ "x").as[Int]; val y2 = (i2 \ "y").as[Int]
            val seq = List(
              MoveMouse(x1, y1),
              LeftButtonPress(x1, y1),
              MoveMouse(x2, y2),
              LeftButtonRelease(x2, y2)
            )
            (state, MKActions(seq, Nil))
          }
        } else None
      }

      // 4) MERGE TWO STACKS
      val mergeStacksOpt: Option[(GameState, MKActions)] = {
        val stacks = collectSlots { it =>
          (it \ "itemId").asOpt[Int].contains(fishId) &&
            (it \ "itemCount").asOpt[Int].exists(c => c > 1 && c < 100)
        }
        if (stacks.size >= 2) {
          val List((c1, s1), (c2, s2)) = stacks.sortBy(_._2).take(2)
          for {
            i1 <- findOneItem(c1, s1)
            i2 <- findOneItem(c2, s2)
          } yield {
            val x1 = (i1 \ "x").as[Int]; val y1 = (i1 \ "y").as[Int]
            val x2 = (i2 \ "x").as[Int]; val y2 = (i2 \ "y").as[Int]
            val seq = List(
              MoveMouse(x1, y1),
              LeftButtonPress(x1, y1),
              MoveMouse(x2, y2),
              LeftButtonRelease(x2, y2)
            )
            (state, MKActions(seq, Nil))
          }
        } else None
      }

      // stop at the first non‑empty
      throwOpt
        .orElse(mergeSinglesOpt)
        .orElse(mergeOntoStackOpt)
        .orElse(mergeStacksOpt)
    }
  }




  private object UseFishingRod extends Step {
    def run(
             state:    GameState,
             json:     JsValue,
             settings: UISettings
           ): Option[(GameState, MKActions)] = {
      import scala.util.Random

      val rects = settings.fishingSettings.selectedRectangles.toList

      // only run if there are tiles selected
      if (rects.nonEmpty) {
        // pick one at random
        val randomTileId = Random.shuffle(rects).head

        // look up coords, defaulting to 0
        val targetX = (json \ "screenInfo" \ "mapPanelLoc" \ randomTileId \ "x").asOpt[Int].getOrElse(0)
        val targetY = (json \ "screenInfo" \ "mapPanelLoc" \ randomTileId \ "y").asOpt[Int].getOrElse(0)
        val arrowsX = (json \ "screenInfo" \ "inventoryPanelLoc" \ "inventoryWindow" \ "contentsPanel" \ "slot10" \ "x").asOpt[Int].getOrElse(0)
        val arrowsY = (json \ "screenInfo" \ "inventoryPanelLoc" \ "inventoryWindow" \ "contentsPanel" \ "slot10" \ "y").asOpt[Int].getOrElse(0)

        // Always emit the same fishing‐rod sequence
        val seq = List(
          MoveMouse(arrowsX, arrowsY),
          RightButtonPress(arrowsX, arrowsY),
          RightButtonRelease(arrowsX, arrowsY),
          MoveMouse(targetX,  targetY),
          LeftButtonPress(targetX,  targetY),
          LeftButtonRelease(targetX,  targetY)
        )

        // State is unchanged; let your MouseActionManager queue or drop duplicates
        Some((state, MKActions(seq, Nil)))
      } else {
        // no tiles selected → skip this step
        None
      }
    }
  }


}
