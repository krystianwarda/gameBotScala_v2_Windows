//package processing
//
//import play.api.libs.json.JsValue
//import utils.GameState
//import utils.ProcessingUtils.{MKTask, Step}
//import utils.SettingsUtils.UISettings
//
//object EquipmentManagementFeature {
//
//  def run(
//           json:     JsValue,
//           settings: UISettings,
//           state:    GameState
//         ): (GameState, List[MKTask]) = {
//
//    val (s, maybeTask) = Steps.runFirst(json, settings, state)
//    (s, maybeTask.toList)
//  }
//
//  private object Steps {
//
//    // ordered list of steps
//    val allSteps: List[Step] = List(
//      BackpackSupplies,
//      AmmoSupplies,
//    )
//
//
//    def runFirst(
//                  json:     JsValue,
//                  settings: UISettings,
//                  startState:    GameState
//                ): (GameState, Option[MKTask]) = {
//      @annotation.tailrec
//      def loop(
//                remaining: List[Step],
//                current:   GameState
//              ): (GameState, Option[MKTask]) = remaining match {
//        case Nil => (current, None)
//        case step :: rest =>
//          step.run(current, json, settings) match {
//            case Some((newState, task)) =>
//              (newState, Some(task))
//            case None =>
//              loop(rest, current)
//          }
//      }
//
//      loop(allSteps, startState)
//    }
//  }
//
//}
