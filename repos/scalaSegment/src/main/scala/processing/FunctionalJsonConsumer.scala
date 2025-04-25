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
      state0   <- stateRef.get
      settings <- settingsRef.get

      // 1) initial setup
      (state1, initMouse, initKeys) = InitialSetupFeature.run(json, settings, state0)

      // 2) fishing
      (state2, fishMouse, fishKeys) = FishingFeature.run(json, settings, state1)

      // 3) healing
      (state3, healMouse, healKeys) = HealingFeature.run(json, settings, state2)

      // 4) commit the final state once
      _ <- stateRef.set(state3)
    } yield (
      initMouse ++ fishMouse ++ healMouse,
      initKeys  ++ fishKeys  ++ healKeys
    )

  def process(json: JsValue): IO[Unit] =
    runPipeline(json).flatMap { case (m, k) =>
      for {
        _ <- executeMouseActions(m)
        _ <- executeKeyboardActions(k)
      } yield ()
    }


  def executeKeyboardActions(actions: List[KeyboardAction]): IO[Unit] =
    keyboardManager.enqueueTask(actions)

  def executeMouseActions(actions: List[MouseAction]): IO[Unit] =
    mouseManager.enqueueTask(actions)

}
