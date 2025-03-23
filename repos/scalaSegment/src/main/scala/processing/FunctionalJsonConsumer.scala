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

case class AutoHealSettings(
                             enabled: Boolean = false,
                             healthThreshold: Int = 50,
                             spellHotkey: String = "F1"
                           )


case class MouseBotAction(id: String, actions: List[MouseAction]) extends BotAction
case class KeyboardBotAction(id: String, actions: List[KeyboardAction]) extends BotAction

class FunctionalJsonConsumer(
                              stateRef: Ref[IO, GameState],
                              settingsRef: Ref[IO, UISettings],
                              mouseManager: MouseActionManager
                            ) {

  def runPipeline(
                   json: JsValue,
                   state: GameState,
                   settings: UISettings
                 ): IO[(List[MouseAction], GameState)] = IO {
    val ((mouseActions, _logs), newState) =
      FishingFeature.computeFishingFeature(json, settings, state)
    (mouseActions, newState)
  }

  def executeMouseActions(actions: List[MouseAction]): IO[Unit] =
    mouseManager.enqueueTask(actions)

  def process(json: JsValue): IO[Unit] =
    for {
      settings <- settingsRef.get
      state <- stateRef.get
      result <- runPipeline(json, state, settings)
      (mouseActions, newState) = result
      _ <- stateRef.set(newState)
      _ <- executeMouseActions(mouseActions)
    } yield ()

}