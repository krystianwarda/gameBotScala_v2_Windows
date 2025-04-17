package processing

import cats.effect.{IO, Ref}
import keyboard.{KeyboardAction, PressKey, TextType, TypeText}
import mouse._
import play.api.libs.json.JsValue
import processing.Process.generateRandomDelay
import utils.GameState
import utils.SettingsUtils.UISettings
import cats.syntax.all._

object HealingFeature {

  def computeHealingFeature(
                             json: JsValue,
                             settingsRef: Ref[IO, UISettings],
                             stateRef: Ref[IO, GameState]
                           ): IO[(List[MouseAction], List[KeyboardAction])] = {
    for {
      settings <- settingsRef.get
      result <- {
        if (!settings.healingSettings.enabled) {
          IO {
            println("Healing disabled in computeHealingFeature")
            (List.empty[MouseAction], List.empty[KeyboardAction])
          }
        } else {
          stateRef.modify { state =>
            val healthPercent = (json \ "characterInfo" \ "healthPercent").asOpt[Int].getOrElse(100)
            val mana = (json \ "characterInfo" \ "mana").asOpt[Int].getOrElse(0)
            val now = System.currentTimeMillis()

            val healSettingsOpt = settings.healingSettings.spellsHealSettings.headOption

            val (updatedState, actions) = healSettingsOpt match {
              case Some(healSettings) if shouldLightHeal(settings, healthPercent, mana) =>
                val cooldownReady = now - state.autoHeal.lastHealUseTime > (
                  state.autoHeal.healingUseCooldown +
                    state.autoHeal.runeUseRandomness +
                    state.autoHeal.lightHeal.lightHealDelayTime
                  )

                if (state.autoHeal.lightHeal.lightHealDelayTime == 0) {
                  val newDelay = generateRandomDelay(state.autoHeal.lightHeal.lightHealDelayTimeRange)
                  val updatedLightHeal = state.autoHeal.lightHeal.copy(
                    lightHealDelayTime = newDelay
                  )
                  val updatedAutoHeal = state.autoHeal.copy(
                    lightHeal = updatedLightHeal,
                    lastHealUseTime = now
                  )
                  val updated = state.copy(autoHeal = updatedAutoHeal)

                  (updated, (List.empty[MouseAction], List.empty[KeyboardAction]))

                } else if (cooldownReady) {
                  val keyboardActions: List[KeyboardAction] =
                    if (healSettings.lightHealHotkeyEnabled)
                      List(PressKey.fromKeyString(healSettings.lightHealHotkey))
                    else
                      List(TextType(healSettings.lightHealSpell))

                  val newDelay = generateRandomDelay(state.autoHeal.lightHeal.lightHealDelayTimeRange)
                  val updatedLightHeal = state.autoHeal.lightHeal.copy(
                    lightHealDelayTime = newDelay
                  )
                  val updatedAutoHeal = state.autoHeal.copy(
                    lightHeal = updatedLightHeal,
                    lastHealUseTime = now
                  )
                  val updated = state.copy(autoHeal = updatedAutoHeal)

                  (updated, (List.empty[MouseAction], keyboardActions))

                } else {
                  println("Healing cannot be used yet due to cooldown.")
                  (state, (List.empty[MouseAction], List.empty[KeyboardAction]))
                }

              case _ =>
                (state, (List.empty[MouseAction], List.empty[KeyboardAction]))
            }

            (updatedState, actions)
          }
        }
      }
    } yield result
  }

  private def shouldLightHeal(
                               settings: UISettings,
                               healthPercent: Int,
                               mana: Int
                             ): Boolean = {
    settings.healingSettings.spellsHealSettings.headOption.exists { spell =>
      spell.lightHealSpell.nonEmpty &&
        spell.lightHealHealthPercent > 0 &&
        healthPercent <= spell.lightHealHealthPercent &&
        mana >= spell.lightHealMana
    }
  }
}
