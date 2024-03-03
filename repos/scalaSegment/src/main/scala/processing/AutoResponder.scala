package processing

import mouse.FakeAction
import play.api.libs.json.{JsValue, Json}
import userUI.SettingsUtils
object AutoResponder {


//  "textChatInfo":{
//      "focusedTabInfo":{
  //      "1":{
  //      "from":"server",
  //      "mode":"Silver",
  //      "text":"Your stream is currently disabled.",
  //      "time":"21:40"
  //      }
  //    },
//  "textTabsInfo":{
//    "1":{
//        "hasUnread":"false",
//        "isFocused":"true",
//        "tabName":"Default"
//        },
//    "2":{
//        "hasUnread":"false",
//        "isFocused":"false",
//        "tabName":"Server Log"},
//        "3":{"hasUnread":"false",
//        "isFocused":"false",
//        "tabName":"Loot Channel"}}}}

  def computeAutoRespondActions(json: JsValue, settings: SettingsUtils.UISettings): (Seq[FakeAction], Seq[Log]) = {

    var actions: Seq[FakeAction] = Seq()
    var logs: Seq[Log] = Seq()

    if (settings.autoResponderSettings.enabled) {


      // default chat
      val isDefaultChatFocused = (json \ "textChatInfo" \ "textTabsInfo" \ "1" \ "isFocused").asOpt[String].getOrElse(0)


      //private message to be added


    }

    (actions, logs)
  }
}

