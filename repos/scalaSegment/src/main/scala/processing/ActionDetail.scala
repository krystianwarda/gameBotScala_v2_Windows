package processing

import play.api.libs.json.{JsValue, Json, Writes}

case class MouseAction(x: Int, y: Int, action: String)
case class PushTheButton(key: String) extends ActionDetail
case class PushTheButtons(key: String) extends ActionDetail

sealed trait ActionDetail

case class MouseActions(actions: Seq[MouseAction]) extends ActionDetail
case class KeyboardText(text: String) extends ActionDetail
case class JsonActionDetails(data: JsValue) extends ActionDetail
case class ListOfJsons(jsons: Seq[JsValue]) extends ActionDetail

case class ComboKeyActions(controlKey: String, arrowKeys: Seq[String]) extends ActionDetail
object ActionDetail {
  // Define Writes for MouseAction here, and make sure it's declared before mouseActionsWrites
  implicit val mouseActionWrites: Writes[MouseAction] = Json.writes[MouseAction]

  // Now that mouseActionWrites is in scope, this should work
  implicit val mouseActionsWrites: Writes[MouseActions] = Json.writes[MouseActions]

  implicit val keyboardTextWrites: Writes[KeyboardText] = Json.writes[KeyboardText]

  implicit val pushButtonWrites: Writes[PushTheButton] = new Writes[PushTheButton] {
    def writes(push: PushTheButton): JsValue = Json.obj(
      "action" -> "PushTheButton",
      "key" -> push.key
    )
  }

  implicit val pushButtonsWrites: Writes[PushTheButtons] = new Writes[PushTheButtons] {
    def writes(push: PushTheButtons): JsValue = Json.obj(
      "action" -> "PushTheButtons",
      "key" -> push.key
    )
  }

  implicit val comboKeyActionsWrites: Writes[ComboKeyActions] = new Writes[ComboKeyActions] {
    def writes(combo: ComboKeyActions): JsValue = Json.obj(
      "controlKey" -> combo.controlKey,
      "arrowKeys" -> Json.toJson(combo.arrowKeys)
    )
  }



  implicit val listOfJsonsWrites: Writes[ListOfJsons] = new Writes[ListOfJsons] {
    def writes(list: ListOfJsons): JsValue = Json.toJson(list.jsons)
  }

  // Aggregate Writes for ActionDetail
  implicit val writes: Writes[ActionDetail] = new Writes[ActionDetail] {
    def writes(detail: ActionDetail): JsValue = detail match {
      case m: MouseActions => Json.toJson(m)(mouseActionsWrites)
      case k: KeyboardText => Json.toJson(k)(keyboardTextWrites)
      case combo: ComboKeyActions => Json.toJson(combo)(comboKeyActionsWrites)
      case j: JsonActionDetails => j.data
      case l: ListOfJsons => Json.toJson(l)(listOfJsonsWrites)
      case push: PushTheButton => Json.toJson(push)(pushButtonWrites)
      case push: PushTheButtons => Json.toJson(push)(pushButtonsWrites)
    }
  }
}
