package processing

import mouse.FakeAction
import play.api.libs.json.{JsObject, JsValue, Json}
import userUI.SettingsUtils

import scala.collection.mutable
object AutoResponder {

  def computeAutoRespondActions(json: JsValue, settings: SettingsUtils.UISettings): (Seq[FakeAction], Seq[Log]) = {

    var actions: Seq[FakeAction] = Seq()
    var logs: Seq[Log] = Seq()

    if (settings.autoResponderSettings.enabled) {


      // default chat
      val isDefaultChatFocused = (json \ "textChatInfo" \ "textTabsInfo" \ "1" \ "isFocused").asOpt[String].getOrElse(0)
      val localCharName = (json \ "characterInfo" \ "Name").asOpt[String].getOrElse(0)

      if (isDefaultChatFocused == "true") {
        // Correctly access the messages in the focused tab
        val messages = (json \ "textChatInfo" \ "focusedTabInfo").as[JsObject].values.toSeq
        // Extract the ignored creatures list from setting
        val ignoredCreaturesList: Seq[String] = settings.protectionZoneSettings.ignoredCreatures

        val relevantMessages = messages.filter { messageJson =>
          val from = (messageJson \ "from").asOpt[String].getOrElse("")
          !from.equals("server") && !from.equals(localCharName) && !ignoredCreaturesList.contains(from)
        }

        // Check if there are any relevant messages
        if (relevantMessages.nonEmpty) {
          actions = actions :+ FakeAction("autoResponderFunction", None, Some(ListOfJsons(relevantMessages)))
        } else {
          //  No message found
        }

      } else {
        // To be coded later - channel switch
      }

    }

    (actions, logs)
  }
}


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


