package processing

import cats.syntax.all._
import cats.effect.{IO, Ref}
import keyboard.{KeyboardAction, KeyboardActionManager}
import mouse._
import utils.SettingsUtils.UISettings
import mouse.MouseActionManager
import utils.GameState
import cats.effect.IO
import cats.implicits._
import play.api.libs.json.JsValue
import cats.syntax.all._

sealed trait BotAction {
  def id: String
}


class FunctionalJsonConsumer(
                              stateRef: Ref[IO, GameState],
                              settingsRef: Ref[IO, UISettings],
                              mouseManager: MouseActionManager,
                              keyboardManager: KeyboardActionManager
                            ) {

  def runPipeline(json: JsValue): IO[(List[MouseAction], List[KeyboardAction])] =
    for {
      fishingRes <- FishingFeature.computeFishingFeature(json, settingsRef, stateRef)
      healingRes <- HealingFeature.computeHealingFeature(json, settingsRef, stateRef)

      // now destructure in pure definitions
      (fishingMouse, fishingKeyboard) = fishingRes
      (healingMouse,  healingKeyboard)  = healingRes
    } yield (
      fishingMouse  ++ healingMouse,
      fishingKeyboard ++ healingKeyboard
    )



  def process(json: JsValue): IO[Unit] =
    for {
      result <- runPipeline(json)
      (mouseActions, keyboardActions) = result
      _ <- executeMouseActions(mouseActions)
      _ <- executeKeyboardActions(keyboardActions)
    } yield ()



  def executeKeyboardActions(actions: List[KeyboardAction]): IO[Unit] =
    keyboardManager.enqueueTask(actions)

  def executeMouseActions(actions: List[MouseAction]): IO[Unit] =
    mouseManager.enqueueTask(actions)

}
