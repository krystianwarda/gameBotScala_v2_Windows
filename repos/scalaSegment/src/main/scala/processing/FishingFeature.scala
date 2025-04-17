package processing

import cats.effect.{IO, Ref}
import play.api.libs.json.{JsObject, JsValue}
import utils.SettingsUtils.UISettings
import mouse._
import keyboard._
import processing.Fishing.extractItemInfoOpt
import processing.Process.{extractOkButtonPosition, timeToRetry}
import utils.GameState
import cats.syntax.all._

object FishingFeature {

  def computeFishingFeature(
                             json: JsValue,
                             settingsRef: Ref[IO, UISettings],
                             stateRef: Ref[IO, GameState]
                           ): IO[(List[MouseAction], List[KeyboardAction])] = {
    val fishId = 3578
    for {
      settings <- settingsRef.get
      result <- {
        if (!settings.fishingSettings.enabled) {
          IO {
            println("Fishing disabled in computeFishingFeature")
            (List.empty[MouseAction], List.empty[KeyboardAction])
          }
        } else {
          stateRef.modify { state =>
            val now = System.currentTimeMillis()

            extractOkButtonPosition(json) match {
              case Some((posX, posY)) =>
                val (updatedState, actions) = executePressOkButton(posX, posY, state, now)
                (updatedState, (actions, List.empty[KeyboardAction]))

              case None =>
                var updatedState = state
                var actions: List[MouseAction] = List.empty

                // Safely extract containersInfo as a JsObject if present
                (json \ "containersInfo").asOpt[JsObject].foreach { containersInfo =>
                  println("Iterate over containers to find single fish items")

                  containersInfo.fields.foreach { case (containerName, containerDetails) =>
                    println(s"Checking container: $containerName")
                    (containerDetails \ "items").asOpt[JsObject].foreach { items =>
                      items.fields.foreach {
                        case (slot, itemDetails) =>
                          val itemId = (itemDetails \ "itemId").asOpt[Int]
                          val itemCount = (itemDetails \ "itemCount").asOpt[Int]
                          println(s"Checking item in slot $slot: itemId=$itemId, itemCount=$itemCount")
                          if (itemId.contains(fishId) && itemCount.exists(_ > 10)) {
                            val itemSlot = slot.replace("slot", "item")
                            extractItemInfoOpt(json, containerName, "contentsPanel", itemSlot).foreach { itemScreenInfo =>
                              val result = executeMoveFishToStack(json, settings, updatedState, now, itemScreenInfo)
                              updatedState = result._1
                              actions = result._2
                            }
                          }
                      }
                    }
                  }
                }

                if (actions.nonEmpty)
                  (updatedState, (actions, List.empty[KeyboardAction]))
                else {
                  val fallback = executeFishing(json, settings, state, now)
                  (fallback._1, (fallback._2, List.empty[KeyboardAction]))
                }
            }
          }
        }
      }
    } yield result
  }

  def executePressOkButton(
                            posX: Int,
                            posY: Int,
                            state: GameState,
                            now: Long
                          ): (GameState, List[MouseAction]) = {
    val retryDelay = state.fishing.lastFishingCommandSent
    val retryMidDelay = 3000

    if (retryDelay == 0 || timeToRetry(retryDelay, retryMidDelay)) {
      val actions = List(
        MoveMouse(posX, posY),
        LeftButtonPress(posX, posY),
        LeftButtonRelease(posX, posY)
      )

      val updatedGeneral = state.general.copy(
        lastActionCommand   = Some("pressOKButton"),
        lastActionTimestamp = Some(now)
      )

      val updatedState = state.copy(
        general = updatedGeneral,
        fishing = state.fishing.copy(lastFishingCommandSent = now)
      )

      (updatedState, actions)
    } else {
      println(s"Waiting to retry merging fishes. Time left: ${(retryMidDelay - (now - retryDelay)) / 1000}s left")
      (state, List.empty)
    }
  }

  def executeMoveFishToStack(
                              json: JsValue,
                              settings: UISettings,
                              state: GameState,
                              now: Long,
                              itemScreenInfo: JsObject
                            ): (GameState, List[MouseAction]) = {
    val retryTime = state.fishing.lastFishingCommandSent
    val delay = 3000

    if ((now - retryTime) < delay) {
      println(s"[MoveFish] Waiting to retry: ${(delay - (now - retryTime)) / 1000}s left")
      return (state, List.empty)
    }

    val itemLocX = (itemScreenInfo \ "x").asOpt[Int].getOrElse(0)
    val itemLocY = (itemScreenInfo \ "y").asOpt[Int].getOrElse(0)

    val (targetX, targetY) = settings.fishingSettings.fishThrowoutRectangles.headOption.flatMap { tileId =>
      for {
        tx <- (json \ "screenInfo" \ "mapPanelLoc" \ tileId \ "x").asOpt[Int]
        ty <- (json \ "screenInfo" \ "mapPanelLoc" \ tileId \ "y").asOpt[Int]
      } yield (tx, ty)
    }.getOrElse {
      val charLoc = (json \ "screenInfo" \ "mapPanelLoc" \ "8x6").head.as[JsObject]
      val x = (charLoc \ "x").as[Int]
      val y = (charLoc \ "y").as[Int]
      (x, y)
    }

    val actions = List(
      MoveMouse(itemLocX, itemLocY),
      LeftButtonPress(itemLocX, itemLocY),
      MoveMouse(targetX, targetY),
      LeftButtonRelease(targetX, targetY)
    )

    val updatedGeneral = state.general.copy(
      lastActionCommand   = Some("moveFishToStack"),
      lastActionTimestamp = Some(now)
    )


    val updatedState = state.copy(
      general = updatedGeneral,
      fishing = state.fishing.copy(lastFishingCommandSent = now)
    )

    (updatedState, actions)
  }

  def executeFishing(
                      json: JsValue,
                      settings: UISettings,
                      state: GameState,
                      now: Long
                    ): (GameState, List[MouseAction]) = {
    val retryTime = state.fishing.lastFishingCommandSent
    val delay = 3000

    val maybeRodXY = for {
      x <- (json \ "screenInfo" \ "inventoryPanelLoc" \ "inventoryWindow" \ "contentsPanel" \ "slot10" \ "x").asOpt[Int]
      y <- (json \ "screenInfo" \ "inventoryPanelLoc" \ "inventoryWindow" \ "contentsPanel" \ "slot10" \ "y").asOpt[Int]
    } yield (x, y)

    val maybeTileXY = settings.fishingSettings.selectedRectangles.headOption.flatMap { tileId =>
      for {
        x <- (json \ "screenInfo" \ "mapPanelLoc" \ tileId \ "x").asOpt[Int]
        y <- (json \ "screenInfo" \ "mapPanelLoc" \ tileId \ "y").asOpt[Int]
      } yield (x, y)
    }

    (maybeRodXY, maybeTileXY) match {
      case (Some((rodX, rodY)), Some((tileX, tileY))) if (now - retryTime) > delay =>
        val castActions = List(
          MoveMouse(rodX, rodY),
          RightButtonPress(rodX, rodY),
          RightButtonRelease(rodX, rodY),
          MoveMouse(tileX, tileY),
          LeftButtonPress(tileX, tileY),
          LeftButtonRelease(tileX, tileY)
        )

        val updatedGeneral = state.general.copy(
          lastActionCommand   = Some("fishingCast"),
          lastActionTimestamp = Some(now)
        )

        val updatedState = state.copy(
          general = updatedGeneral,
          fishing = state.fishing.copy(lastFishingCommandSent = now)
        )

        (updatedState, castActions)


      case (Some(_), Some(_)) =>
        println(s"[Fishing] Waiting to retry: ${(delay - (now - retryTime)) / 1000}s left")
        (state, List.empty)

      case _ =>
        println("[Fishing] Rod or tile missing")
        (state, List.empty)
    }
  }
}
