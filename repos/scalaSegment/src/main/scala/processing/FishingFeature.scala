package processing

import cats.effect.{IO, Ref}
import play.api.libs.json.{JsObject, JsValue}
import utils.SettingsUtils.UISettings
import mouse._
import keyboard._
import processing.Fishing.{extractItemInfoOpt, extractStackSize}
import processing.Process.{extractOkButtonPosition, handleRetryStatusOption, timeToRetry}
import utils.GameState
import cats.syntax.all._
import processing.HealingFeature.{DangerLevelHealing, HandleBackpacks, SelfHealing, SetUpSupplies, Steps, TeamHealing}
import utils.ProcessingUtils.{MKActions, Step}

object FishingFeature {



  def computeFishingFeature(
                             json: JsValue,
                             settingsRef: Ref[IO, UISettings],
                             stateRef: Ref[IO, GameState]
                           ): IO[(List[MouseAction], List[KeyboardAction])] = for {
    settings <- settingsRef.get
    result <- if (!settings.fishingSettings.enabled) {
      IO(println("Fishing disabled in computeFishingFeature")).as((Nil, Nil))
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
      CheckForOkButton,
      HandleFish,
      UseFishingRod
    )

    def runFirst(json: JsValue, settings: UISettings, state: GameState): (GameState, List[MouseAction], List[KeyboardAction]) =
      all.iterator
        .flatMap(_.run(state, json, settings))
        .map { case (s, a) => (s, a.mouse, a.keyboard) }
        .toSeq
        .headOption
        .getOrElse((state, Nil, Nil))
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
      val fishId  = 3578
      val fishing = state.fishing
      val now     = System.currentTimeMillis()

      // helper to find one item slot
      def findOneItem(container: String, slot: String) =
        extractItemInfoOpt(json, container, "contentsPanel", slot.replace("slot","item"))

      // 1) THROW OUT BIG STACKS (itemCount > 10)
      val throwOpt: Option[(GameState, MKActions)] = for {
        ci            <- (json \ "containersInfo").asOpt[JsObject]
        (cname, cd)   <- ci.fields.toSeq
        items         <- (cd \ "items").asOpt[JsObject]
        (slot, it)    <- items.fields
        id            <- (it \ "itemId").asOpt[Int]    if id == fishId
        count         <- (it \ "itemCount").asOpt[Int] if count > 10
        info          <- findOneItem(cname, slot)
        x             =  (info \ "x").as[Int]
        y             =  (info \ "y").as[Int]
        // pick a drop target
        target        <- {
          val rects = settings.fishingSettings.fishThrowoutRectangles.toList
          if (rects.nonEmpty) {
            val rnd = scala.util.Random.shuffle(rects).head
            for {
              tx <- (json \ "screenInfo" \ "mapPanelLoc" \ rnd \ "x").asOpt[Int]
              ty <- (json \ "screenInfo" \ "mapPanelLoc" \ rnd \ "y").asOpt[Int]
            } yield (tx, ty)
          } else {
            for {
              mp <- (json \ "screenInfo" \ "mapPanelLoc" \ "8x6").asOpt[JsObject]
              tx <- (mp   \ "x").asOpt[Int]
              ty <- (mp   \ "y").asOpt[Int]
            } yield (tx, ty)
          }
        }
        // check retry
        if fishing.retryThroughoutFishesStatus == 0 ||
          handleRetryStatusOption(fishing.retryThroughoutFishesStatus, fishing.retryAttemptsLong) == 0
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
        val newRetry = if (fishing.retryThroughoutFishesStatus == 0) 1
        else handleRetryStatusOption(fishing.retryThroughoutFishesStatus, fishing.retryAttemptsLong)
        val newFishing = fishing.copy(retryThroughoutFishesStatus = newRetry)
        (state.copy(fishing = newFishing), MKActions(seq, Nil))
      }

      // helper to collect slots by predicate
      def collectSlots(pred: JsObject => Boolean): List[(String,String)] =
        (json \ "containersInfo").asOpt[JsObject].toList
          .flatMap(_.fields.toList)
          .flatMap { case (cname, cd) =>
            (cd \ "items").asOpt[JsObject].toList
              .flatMap(_.fields.collect {
                case (slot, it) if pred(it.as[JsObject]) => (cname, slot)
              })
          }

      // 2) MERGE SINGLE FISHES (two slots with count==1)
      val mergeSinglesOpt: Option[(GameState, MKActions)] = {
        val singles = collectSlots(it =>
          (it \ "itemId").asOpt[Int].contains(fishId) &&
            (it \ "itemCount").asOpt[Int].contains(1)
        )
        if (singles.size >= 2) {
          val List((c1,s1),(c2,s2)) = singles.sortBy(_._2).take(2)
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
            val newRetry = if (fishing.retryMergeFishStatus == 0) 1
            else handleRetryStatus(fishing.retryMergeFishStatus, fishing.retryAttemptsLong)
            val newFishing = fishing.copy(retryMergeFishStatus = newRetry)
            (state.copy(fishing = newFishing), MKActions(seq, Nil))
          }
        } else None
      }

      // 3) MERGE SINGLE ONTO STACK
      val mergeOntoStackOpt: Option[(GameState, MKActions)] = {
        val singles = collectSlots(it =>
          (it \ "itemId").asOpt[Int].contains(fishId) &&
            (it \ "itemCount").asOpt[Int].contains(1)
        )
        val stacks  = collectSlots(it =>
          (it \ "itemId").asOpt[Int].contains(fishId) &&
            (it \ "itemCount").asOpt[Int].exists(cnt => cnt > 1 && cnt < 100)
        )
        if (singles.size == 1 && stacks.nonEmpty) {
          val (c1,s1) = singles.head
          val (c2,s2) = stacks.head
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
            val newRetry = if (fishing.retryMergeFishStatus == 0) 1
            else handleRetryStatus(fishing.retryMergeFishStatus, fishing.retryAttemptsLong)
            val newFishing = fishing.copy(retryMergeFishStatus = newRetry)
            (state.copy(fishing = newFishing), MKActions(seq, Nil))
          }
        } else None
      }

      // 4) MERGE TWO STACKS
      val mergeStacksOpt: Option[(GameState, MKActions)] = {
        val stacks = collectSlots(it =>
          (it \ "itemId").asOpt[Int].contains(fishId) &&
            (it \ "itemCount").asOpt[Int].exists(cnt => cnt > 1 && cnt < 100)
        )
        if (stacks.size >= 2) {
          val List((c1,s1),(c2,s2)) =
            stacks.sortBy { case (_, slot) => extractStackSize(slot) }.take(2)
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
            val newRetry = if (fishing.retryMergeFishStatus == 0) 1
            else handleRetryStatus(fishing.retryMergeFishStatus, fishing.retryAttemptsLong)
            val newFishing = fishing.copy(retryMergeFishStatus = newRetry)
            (state.copy(fishing = newFishing), MKActions(seq, Nil))
          }
        } else None
      }

      // chain in priority order
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

        val fishingState = state.fishing

        // decide whether to cast or just update retry status
        val (newRetryStatus, actions) =
          if (fishingState.retryFishingStatus == 0) {
            // first attempt → cast
            val seq = List(
              MoveMouse(arrowsX, arrowsY),
              RightButtonPress(arrowsX, arrowsY),
              RightButtonRelease(arrowsX, arrowsY),
              MoveMouse(targetX,  targetY),
              LeftButtonPress(targetX,  targetY),
              LeftButtonRelease(targetX,  targetY)
            )
            (fishingState.retryFishingStatus + 1, seq)
          } else {
            // not first attempt → compute next status, no actions
            val next = handleRetryStatus(
              fishingState.retryFishingStatus,
              fishingState.fishingRetryAttempts
            )
            (next, Nil)
          }

        // build new GameState with updated fishing substate
        val newFishing = fishingState.copy(retryFishingStatus = newRetryStatus)
        val newState   = state.copy(fishing = newFishing)

        Some((newState, MKActions(actions, Nil)))
      } else {
        // no tiles selected → skip
        None
      }
    }
  }


//
//  def computeFishingFeature(
//                             json: JsValue,
//                             settingsRef: Ref[IO, UISettings],
//                             stateRef: Ref[IO, GameState]
//                           ): IO[(List[MouseAction], List[KeyboardAction])] = {
//    val fishId = 3578
//    for {
//      settings <- settingsRef.get
//      result <- {
//        if (!settings.fishingSettings.enabled) {
//          IO {
//            println("Fishing disabled in computeFishingFeature")
//            (List.empty[MouseAction], List.empty[KeyboardAction])
//          }
//        } else {
//          stateRef.modify { state =>
//            val now = System.currentTimeMillis()
//
//            extractOkButtonPosition(json) match {
//              case Some((posX, posY)) =>
//                val (updatedState, actions) = executePressOkButton(posX, posY, state, now)
//                (updatedState, (actions, List.empty[KeyboardAction]))
//
//              case None =>
//                var updatedState = state
//                var actions: List[MouseAction] = List.empty
//
//                // Safely extract containersInfo as a JsObject if present
//                (json \ "containersInfo").asOpt[JsObject].foreach { containersInfo =>
//                  println("Iterate over containers to find single fish items")
//
//                  containersInfo.fields.foreach { case (containerName, containerDetails) =>
//                    println(s"Checking container: $containerName")
//                    (containerDetails \ "items").asOpt[JsObject].foreach { items =>
//                      items.fields.foreach {
//                        case (slot, itemDetails) =>
//                          val itemId = (itemDetails \ "itemId").asOpt[Int]
//                          val itemCount = (itemDetails \ "itemCount").asOpt[Int]
//                          println(s"Checking item in slot $slot: itemId=$itemId, itemCount=$itemCount")
//                          if (itemId.contains(fishId) && itemCount.exists(_ > 10)) {
//                            val itemSlot = slot.replace("slot", "item")
//                            extractItemInfoOpt(json, containerName, "contentsPanel", itemSlot).foreach { itemScreenInfo =>
//                              val result = executeMoveFishToStack(json, settings, updatedState, now, itemScreenInfo)
//                              updatedState = result._1
//                              actions = result._2
//                            }
//                          }
//                      }
//                    }
//                  }
//                }
//
//                if (actions.nonEmpty)
//                  (updatedState, (actions, List.empty[KeyboardAction]))
//                else {
//                  val fallback = executeFishing(json, settings, state, now)
//                  (fallback._1, (fallback._2, List.empty[KeyboardAction]))
//                }
//            }
//          }
//        }
//      }
//    } yield result
//  }
//
//  def executePressOkButton(
//                            posX: Int,
//                            posY: Int,
//                            state: GameState,
//                            now: Long
//                          ): (GameState, List[MouseAction]) = {
//    val retryDelay = state.fishing.lastFishingCommandSent
//    val retryMidDelay = 3000
//
//    if (retryDelay == 0 || timeToRetry(retryDelay, retryMidDelay)) {
//      val actions = List(
//        MoveMouse(posX, posY),
//        LeftButtonPress(posX, posY),
//        LeftButtonRelease(posX, posY)
//      )
//
//      val updatedGeneral = state.general.copy(
//        lastActionCommand   = Some("pressOKButton"),
//        lastActionTimestamp = Some(now)
//      )
//
//      val updatedState = state.copy(
//        general = updatedGeneral,
//        fishing = state.fishing.copy(lastFishingCommandSent = now)
//      )
//
//      (updatedState, actions)
//    } else {
//      println(s"Waiting to retry merging fishes. Time left: ${(retryMidDelay - (now - retryDelay)) / 1000}s left")
//      (state, List.empty)
//    }
//  }
//
//  def executeMoveFishToStack(
//                              json: JsValue,
//                              settings: UISettings,
//                              state: GameState,
//                              now: Long,
//                              itemScreenInfo: JsObject
//                            ): (GameState, List[MouseAction]) = {
//    val retryTime = state.fishing.lastFishingCommandSent
//    val delay = 3000
//
//    if ((now - retryTime) < delay) {
//      println(s"[MoveFish] Waiting to retry: ${(delay - (now - retryTime)) / 1000}s left")
//      return (state, List.empty)
//    }
//
//    val itemLocX = (itemScreenInfo \ "x").asOpt[Int].getOrElse(0)
//    val itemLocY = (itemScreenInfo \ "y").asOpt[Int].getOrElse(0)
//
//    val (targetX, targetY) = settings.fishingSettings.fishThrowoutRectangles.headOption.flatMap { tileId =>
//      for {
//        tx <- (json \ "screenInfo" \ "mapPanelLoc" \ tileId \ "x").asOpt[Int]
//        ty <- (json \ "screenInfo" \ "mapPanelLoc" \ tileId \ "y").asOpt[Int]
//      } yield (tx, ty)
//    }.getOrElse {
//      val charLoc = (json \ "screenInfo" \ "mapPanelLoc" \ "8x6").head.as[JsObject]
//      val x = (charLoc \ "x").as[Int]
//      val y = (charLoc \ "y").as[Int]
//      (x, y)
//    }
//
//    val actions = List(
//      MoveMouse(itemLocX, itemLocY),
//      LeftButtonPress(itemLocX, itemLocY),
//      MoveMouse(targetX, targetY),
//      LeftButtonRelease(targetX, targetY)
//    )
//
//    val updatedGeneral = state.general.copy(
//      lastActionCommand   = Some("moveFishToStack"),
//      lastActionTimestamp = Some(now)
//    )
//
//
//    val updatedState = state.copy(
//      general = updatedGeneral,
//      fishing = state.fishing.copy(lastFishingCommandSent = now)
//    )
//
//    (updatedState, actions)
//  }
//
//  def executeFishing(
//                      json: JsValue,
//                      settings: UISettings,
//                      state: GameState,
//                      now: Long
//                    ): (GameState, List[MouseAction]) = {
//    val retryTime = state.fishing.lastFishingCommandSent
//    val delay = 3000
//
//    val maybeRodXY = for {
//      x <- (json \ "screenInfo" \ "inventoryPanelLoc" \ "inventoryWindow" \ "contentsPanel" \ "slot10" \ "x").asOpt[Int]
//      y <- (json \ "screenInfo" \ "inventoryPanelLoc" \ "inventoryWindow" \ "contentsPanel" \ "slot10" \ "y").asOpt[Int]
//    } yield (x, y)
//
//    val maybeTileXY = settings.fishingSettings.selectedRectangles.headOption.flatMap { tileId =>
//      for {
//        x <- (json \ "screenInfo" \ "mapPanelLoc" \ tileId \ "x").asOpt[Int]
//        y <- (json \ "screenInfo" \ "mapPanelLoc" \ tileId \ "y").asOpt[Int]
//      } yield (x, y)
//    }
//
//    (maybeRodXY, maybeTileXY) match {
//      case (Some((rodX, rodY)), Some((tileX, tileY))) if (now - retryTime) > delay =>
//        val castActions = List(
//          MoveMouse(rodX, rodY),
//          RightButtonPress(rodX, rodY),
//          RightButtonRelease(rodX, rodY),
//          MoveMouse(tileX, tileY),
//          LeftButtonPress(tileX, tileY),
//          LeftButtonRelease(tileX, tileY)
//        )
//
//        val updatedGeneral = state.general.copy(
//          lastActionCommand   = Some("fishingCast"),
//          lastActionTimestamp = Some(now)
//        )
//
//        val updatedState = state.copy(
//          general = updatedGeneral,
//          fishing = state.fishing.copy(lastFishingCommandSent = now)
//        )
//
//        (updatedState, castActions)
//
//
//      case (Some(_), Some(_)) =>
//        println(s"[Fishing] Waiting to retry: ${(delay - (now - retryTime)) / 1000}s left")
//        (state, List.empty)
//
//      case _ =>
//        println("[Fishing] Rod or tile missing")
//        (state, List.empty)
//    }
//  }
}
