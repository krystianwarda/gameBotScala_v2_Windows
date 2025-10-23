//package processing
//
//import cats.implicits.{catsSyntaxAlternativeGuard, toFunctorOps}
//import keyboard._
//import play.api.libs.json.{JsObject, JsValue, Json}
//import processing.AutoTargetFeature.aStarSearch
//import utils.ProcessingUtils.{MKActions, MKTask, NoOpTask, Step}
//import utils.SettingsUtils.UISettings
//import utils.{GameState, RandomUtils, StaticGameInfo}
//
//import scala.concurrent.duration.DurationInt
//
////import scala.util.Random
//import mouse._
//import processing.CaveBotFeature.Vec
//
//
//object TrainingFeature {
//
//  def run(json: JsValue, settings: UISettings, state: GameState): (GameState, List[MKTask]) =
//    (!settings.teamHuntSettings.enabled).guard[Option]
//      .as((state, Nil))
//      .getOrElse {
//        val (s, maybeTask) = Steps.runAll(json, settings, state)
//        (s, maybeTask.toList)
//      }
//
//  private object Steps {
//    // ordered list of steps
//    val allSteps: List[Step] = List(
////     ChaningFightMode,
//
//
//    )
//
//
//    def runAll(
//                  json: JsValue,
//                  settings: UISettings,
//                  startState: GameState
//                ): (GameState, Option[MKTask]) = {
//      @annotation.tailrec
//      def loop(remaining: List[Step], current: GameState): (GameState, Option[MKTask]) =
//        remaining match {
//          case Nil => (current, None)
//          case step :: rest =>
//            step.run(current, json, settings) match {
//              case Some((newState, task)) if task == NoOpTask =>
//                // ✅ keep the newState and keep going
//                loop(rest, newState)
//
//              case Some((newState, task)) =>
//                // ✅ return early with state AND task
//                (newState, Some(task))
//
//              case None =>
//                // ✅ no state change, continue with existing
//                loop(rest, current)
//            }
//        }
//
//      // ✅ always return the latest state, even if no task
//      loop(allSteps, startState)
//    }
//  }
//
//
//  private object CheckForUseButton extends Step {
//    private val taskName = "pressOkButton"
//
//    def run(state: GameState, json: JsValue, settings: UISettings): Option[(GameState, MKTask)] = {
//
//
//    }
//  }
//
//}
//
