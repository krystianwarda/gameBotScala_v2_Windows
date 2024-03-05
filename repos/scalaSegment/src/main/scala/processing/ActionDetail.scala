package processing

import play.api.libs.json.{JsValue, Json, Writes}

case class MouseAction(x: Int, y: Int, action: String)

sealed trait ActionDetail

case class MouseActions(actions: Seq[MouseAction]) extends ActionDetail

case class KeyboardText(text: String) extends ActionDetail

case class JsonActionDetails(data: JsValue) extends ActionDetail

case class ListOfJsons(jsons: Seq[JsValue]) extends ActionDetail

object ActionDetail {
  // Define Writes for MouseAction here
  implicit val mouseActionWrites: Writes[MouseAction] = Json.writes[MouseAction]

  // Now that mouseActionWrites is in scope, this should work
  implicit val mouseActionsWrites: Writes[MouseActions] = Json.writes[MouseActions]

  implicit val keyboardTextWrites: Writes[KeyboardText] = Json.writes[KeyboardText]


  implicit val listOfJsonsWrites: Writes[ListOfJsons] = new Writes[ListOfJsons] {
    def writes(list: ListOfJsons): JsValue = Json.toJson(list.jsons)
  }


  implicit val writes: Writes[ActionDetail] = new Writes[ActionDetail] {
    def writes(detail: ActionDetail): JsValue = detail match {
      case m: MouseActions => Json.toJson(m)(mouseActionsWrites)
      case k: KeyboardText => Json.toJson(k)(keyboardTextWrites)
      case j: JsonActionDetails => j.data
      case l: ListOfJsons => Json.toJson(l)(listOfJsonsWrites)
    }
  }
}
