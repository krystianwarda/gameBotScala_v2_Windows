package processing

import cats.effect.IO
import cats.syntax.all._
import cats.effect.{IO, Ref}
import cats.syntax.all._
import cats.effect.unsafe.implicits.global
import keyboard._
import mouse._
import play.api.libs.json.JsValue
import userUI.SettingsUtils.UISettings
//import userUI.SettingsUtils._
import mouse.MouseActionManager
import cats.syntax.all._

sealed trait BotAction {
  def id: String
}



case class GameState(
                           retryCounters: Map[String, Int] = Map.empty,
                           timestamps: Map[String, Long] = Map.empty,
                           flags: Map[String, Boolean] = Map.empty,
                           lastMousePos: Option[(Int, Int)] = None,
                           lastAction: Option[String] = None,
                           temporaryData: Map[String, String] = Map.empty
                         )
//case class UserSettings(
//                       fishingSettings: FishingSettings = FishingSettings(),
//                       autoHealSettings: AutoHealSettings = AutoHealSettings(),
//                       mouseEnabled: Boolean = true
//                     )

case class FishingSettings(
                            enabled: Boolean = false,
                            selectedRectangles: List[String] = List("8x6", "9x6", "10x6"),
                            fishThrowoutRectangles: List[String] = List("8x5")
                          )


class FunctionalJsonConsumer(
                              stateRef: Ref[IO, GameState],
                              settingsRef: Ref[IO, UISettings],
                              mouseManager: MouseActionManager
                            ) {


  def runPipeline(
                   json: JsValue,
                 ): IO[List[MouseAction]] =
    for {
      fishingActions <- FishingFeature.computeFishingFeature(json, settingsRef, `stateRef`)
//       healActions <- AutoHealFeature.computeHealingFeature(json, settingsRef, stateRef)
       combinedActions = fishingActions
    } yield combinedActions

  def executeMouseActions(actions: List[MouseAction]): IO[Unit] =
    mouseManager.enqueueTask(actions)

  def process(json: JsValue): IO[Unit] =
    for {
      mouseActions <- runPipeline(json)
      _ <- executeMouseActions(mouseActions)
    } yield ()
}
