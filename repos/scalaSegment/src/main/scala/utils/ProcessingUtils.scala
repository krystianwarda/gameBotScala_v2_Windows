package utils

import keyboard.KeyboardAction
import mouse.MouseAction
import play.api.libs.json.JsValue
import utils.SettingsUtils.UISettings

object ProcessingUtils {


  // encapsulate mouse+keyboard lists
  case class MKActions(mouse: List[MouseAction], keyboard: List[KeyboardAction])
  object MKActions {
    val empty = MKActions(Nil, Nil)
  }

  case class MKTask(
                           taskName: String,
                           actions: MKActions,
                           metaGeneratedId: Option[String] = None
                         )

  val NoOpTask = MKTask("NoOp", MKActions.empty)

  trait Step {
    def run(
             state:    GameState,
             json:     JsValue,
             settings: UISettings
           ): Option[(GameState, MKTask)]
  }

}
