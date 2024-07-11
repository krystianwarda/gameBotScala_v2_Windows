package processing

import mouse.FakeAction
import play.api.libs.json.{JsObject, JsValue, Json}
import userUI.SettingsUtils

import scala.collection.mutable



object AutoResponder {

  def computeAutoRespondActions(json: JsValue, settings: SettingsUtils.UISettings): (Seq[FakeAction], Seq[Log]) = {
    var actions: Seq[FakeAction] = Seq()
    var logs: Seq[Log] = Seq()

    // Debug: Check if the auto responder settings are enabled
    println(s"AutoResponder Enabled: ${settings.autoResponderSettings.enabled}")

    if (settings.autoResponderSettings.enabled) {
      // Debug: Parsing the focus status
      val isDefaultChatFocused = (json \ "textTabsInfo" \ "1" \ "isFocused").asOpt[Boolean].getOrElse(false)
      println(s"Default Chat Focused: $isDefaultChatFocused")

      val localCharName = (json \ "characterInfo" \ "Name").asOpt[String].getOrElse("")
      println(s"Local Character Name: $localCharName")

      if (isDefaultChatFocused) {
        // Debug: Fetching messages
        val messages = (json \ "focusedTabInfo").as[JsObject].values.toSeq.flatMap(_.asOpt[JsObject])
        println(s"Total Messages Fetched: ${messages.length}")

        val ignoredCreaturesList: Seq[String] = settings.protectionZoneSettings.ignoredCreatures
        println(s"Ignored Creatures: $ignoredCreaturesList")

        val relevantMessages = messages.filter { messageJson =>
          val from = (messageJson \ "from").asOpt[String].getOrElse("")
          println(s"Message From: $from") // Debug: Log each message's sender

          val relevant = !from.equals("server") && !from.equals(localCharName) && !ignoredCreaturesList.contains(from)
          println(s"Message from $from is relevant: $relevant")
          relevant
        }

        // Debug: Check if any messages are relevant
        println(s"Relevant Messages Found: ${relevantMessages.length}")
        if (relevantMessages.nonEmpty) {
          actions = actions :+ FakeAction("autoResponderFunction", None, Some(ListOfJsons(relevantMessages)))
          println("Action added for relevant messages.")
        } else {
          logs = logs :+ Log("No relevant messages found")
          println("No relevant messages found.")
        }
      } else {
        logs = logs :+ Log("Default chat is not focused")
        println("Default chat is not focused.")
      }
    } else {
      logs = logs :+ Log("AutoResponder is disabled")
      println("AutoResponder is disabled.")
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


