package processing

import keyboard.{KeyboardAction, PressKey}
import play.api.libs.json.{JsObject, JsValue}
import processing.CaveBot.Vec
import utils.{GameState, StaticGameInfo}
import utils.ProcessingUtils.{MKActions, MKTask, Step}
import utils.SettingsUtils.UISettings
import utils.consoleColorPrint.{ANSI_BLUE, printInColor}

import scala.collection.mutable
import scala.util.Try
import scala.util.chaining.scalaUtilChainingOps
import keyboard.KeyboardAction
import mouse.{LeftButtonPress, LeftButtonRelease, MoveMouse}


object AutoTargetFeature {

  def run(json: JsValue, settings: UISettings, state: GameState):
  (GameState, List[MKTask]) =
    if (!settings.autoTargetSettings.enabled) (state, Nil)
    else {
      val (s, maybeTask) = Steps.runFirst(json, settings, state)
      (s, maybeTask.toList)
    }

  private object Steps {
    // ordered list of steps
    val allSteps: List[Step] = List(
      GetAttackInfo,

    )


    def runFirst(
                  json: JsValue,
                  settings: UISettings,
                  startState: GameState
                ): (GameState, Option[MKTask]) = {
      @annotation.tailrec
      def loop(
                remaining: List[Step],
                current: GameState
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

  private object GetAttackInfo extends Step {
    private val taskName = "GetAttackInfo"

    override def run(
                      state:    GameState,
                      json:     JsValue,
                      settings: UISettings
                    ): Option[(GameState, MKTask)] = {
      // try to pull the attacked creature's ID
      (json \ "attackInfo" \ "Id").asOpt[Int].map { targetId =>
        // switch into attacking state

        // pull name & position safely
        val targetName = (json \ "attackInfo" \ "Name").asOpt[String].getOrElse("Unknown")
        val xPos = (json \ "attackInfo" \ "Position" \ "x").asOpt[Int].getOrElse(0)
        val yPos = (json \ "attackInfo" \ "Position" \ "y").asOpt[Int].getOrElse(0)
        val zPos = (json \ "attackInfo" \ "Position" \ "z").asOpt[Int].getOrElse(0)

        printInColor(ANSI_BLUE,
          s"[GetAttackInfo] New target: $targetName ($targetId) at [$xPos,$yPos,$zPos]")
        val newCaveBotState = state.caveBot.copy(stateHunting = "attacking")
        val newAutoTargetState = state.autoTarget.copy(
          lastTargetName  = targetName,
          lastTargetPos   = (xPos, yPos, zPos),
          creatureTarget  = targetId
        )

        // build the updated GameState
        val newState = state.copy(
          caveBot = newCaveBotState,
          autoTarget = newAutoTargetState
        )

        // return a no-action task to stop any further Steps this tick
        newState -> MKTask(taskName, MKActions.empty)
      }
    }
  }


}
