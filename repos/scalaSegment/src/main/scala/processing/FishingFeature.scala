package processing

import cats.effect.{IO, Ref}
import play.api.libs.json.{JsObject, JsValue}
import userUI.SettingsUtils.UISettings
import mouse._
import keyboard._
import cats.syntax.all._
import processing.Process.{extractOkButtonPosition, handleRetryStatus, performMouseActionSequance, timeToRetry}
import utils.consoleColorPrint.{ANSI_GREEN, printInColor}
object FishingFeature {

  def computeFishingFeature(
                             json: JsValue,
                             settingsRef: Ref[IO, UISettings],
                             stateRef: Ref[IO, GameState]
                           ): IO[List[MouseAction]] = {

    for {
      settings <- settingsRef.get
      result <- {
        if (!settings.fishingSettings.enabled) {
          IO {
            println("Fishing disabled in computeFishingFeature")
            List.empty[MouseAction]
          }
        } else {

          stateRef.modify { state =>
            val now = System.currentTimeMillis()

            extractOkButtonPosition(json) match {
              case Some((posX, posY)) =>
                val retryDelay = state.timestamps.getOrElse("retryMergeFishDelay", 0L)
                val retryMidDelay = 3000

                if (retryDelay == 0 || timeToRetry(retryDelay, retryMidDelay)) {
                  val actions = List(
                    MoveMouse(posX, posY),
                    LeftButtonPress(posX, posY),
                    LeftButtonRelease(posX, posY)
                  )
                  val updatedState = state.copy(
                    timestamps = state.timestamps.updated("retryMergeFishDelay", now),
                    lastAction = Some("mergeFishOk")
                  )
                  // ✅ Early exit by returning state + actions, everything else skipped
                  (updatedState, actions)
                } else {
                  println(s"Waiting to retry merging fishes. Time left: ${(retryMidDelay - (now - retryDelay)) / 1000}s left")
                  (state, List.empty)
                }

              case None =>

                // Continue with normal fishing logic
                val retryTime = state.timestamps.getOrElse("mergeFish", 0L)
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

                val (updatedState, actions) = (maybeRodXY, maybeTileXY) match {
                  case (Some((rodX, rodY)), Some((tileX, tileY))) if (now - retryTime) > delay =>
                    val castActions = List(
                      MoveMouse(rodX, rodY),
                      RightButtonPress(rodX, rodY),
                      RightButtonRelease(rodX, rodY),
                      MoveMouse(tileX, tileY),
                      LeftButtonPress(tileX, tileY),
                      LeftButtonRelease(tileX, tileY)
                    )
                    val newState = state.copy(
                      timestamps = state.timestamps.updated("mergeFish", now),
                      lastAction = Some("fishingCast")
                    )
                    (newState, castActions)

                  case (Some(_), Some(_)) =>
                    println(s"[Fishing] Waiting to retry: ${(delay - (now - retryTime)) / 1000}s left")
                    (state, List.empty)

                  case _ =>
                    println("[Fishing] Rod or tile missing")
                    (state, List.empty)
                }

                (updatedState, actions)
            }
          }


        }
      }
    } yield result
  }
}
