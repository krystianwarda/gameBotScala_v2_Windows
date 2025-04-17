package processing

import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpMethods, HttpRequest, StatusCodes, Uri}
import akka.http.scaladsl.model.headers.{Authorization, GenericHttpCredentials}
import akka.http.scaladsl.unmarshalling.Unmarshal
import keyboard.Gpt3ApiClient.{alertStory, apiKey, endpoint, initialStory, storiesMap}
import mouse.FakeAction
import play.api.libs.json.{JsObject, JsValue, Json}
import utils.SettingsUtils.UISettings
import keyboard.RequestAnswer

import scala.collection.mutable
import scala.concurrent.{Await, Future}
import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import keyboard.ApplicationSetup.system
import keyboard.Gpt3ApiClient.{alertStory, storiesMap}
import keyboard.KeyboardActionTypes.TypeText
import keyboard.AutoResponderCommand
import main.scala.MainApp.{actionKeyboardManagerRef, autoResponderManagerRef}
import play.api.libs.json.{JsValue, Json}
import processing.Process.loadSpellsFromFile
import utils.SettingsUtils.UISettings
import userUI.UpdateSettings
import utils.{ProcessorState, SettingsUtils}

import scala.collection.mutable
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._
import scala.io.Source

case class AnalyzeMessage(json: JsValue)
case object CheckPendingResponses
case class AutoResponderCommand(messages: Seq[JsValue])
case class TextResponse(key: String, responseText: String)
case class UpdateAlertStory(alertType: String)
case object CancelAlert


// updatedState.dialogueHistory: mutable.Buffer[JsValue] = mutable.Buffer.empty
// updatedState.respondedMessages: mutable.Set[String] = mutable.Set.empty
// updatedState.pendingResponses: mutable.Queue[(String, Long)] = mutable.Queue.empty

object AutoResponder {
  // Load substrings from external files
  val spellList = loadSpellsFromFile("src/main/scala/extraFiles/spellsList.txt")

  def computeAutoRespondActions(json: JsValue, settings: SettingsUtils.UISettings, currentState: ProcessorState): ((Seq[FakeAction], Seq[Log]), ProcessorState) = {
    var actions: Seq[FakeAction] = Seq()
    var logs: Seq[Log] = Seq()
    var updatedState = currentState
    val currentTime = System.currentTimeMillis()

    // Debug: Check if the auto responder settings are enabled
//    println(s"AutoResponder Enabled: ${settings.autoResponderSettings.enabled}")

    if (settings.autoResponderSettings.enabled) {
      // Debug: Parsing the focus status
      val isDefaultChatFocused = (json \ "textTabsInfo" \ "1" \ "isFocused").asOpt[Boolean].getOrElse(false)
//      println(s"Default Chat Focused: $isDefaultChatFocused")

      val localCharName = (json \ "characterInfo" \ "Name").asOpt[String].getOrElse("")
//      println(s"Local Character Name: $localCharName")

      if (isDefaultChatFocused) {
        // Debug: Fetching messages
        val messages = (json \ "focusedTabInfo").as[JsObject].values.toSeq.flatMap(_.asOpt[JsObject])
//        println(s"Total Messages Fetched: ${messages.length}")

        val historyMessages = updatedState.dialogueHistory.map { case (msgJson, _) =>
          (msgJson \ "from").as[String] -> (msgJson \ "text").as[String]
        }.toSet

        val pendingMessagesSet = updatedState.pendingMessages.map(msg => (msg._1 \ "from").as[String] -> (msg._1 \ "text").as[String]).toSet
        val playerName = (json \ "characterInfo" \ "Name").as[String]
        val relevantMessages = messages.filter { messageJson =>
          val from = (messageJson \ "from").asOpt[String].getOrElse("")
          val text = (messageJson \ "text").asOpt[String].getOrElse("")
          val messageIdentifier = (from, text)

          !from.equals("server") && from != playerName && !from.equals("Local Character Name") && !historyMessages.contains(messageIdentifier) && !pendingMessagesSet.contains(messageIdentifier) && !containsAnySpell(text, spellList)
        }

        // Debugging outputs
        println(s"historyMessages: $historyMessages")
        println(s"updatedState.pendingMessages: ${updatedState.pendingMessages}")
        println(s"messageRespondRequested: ${updatedState.messageRespondRequested}")
        println(s"relevantMessages.nonEmpty: ${relevantMessages.nonEmpty}")

        if (relevantMessages.nonEmpty && !updatedState.messageRespondRequested) {
          println(s"Relevant messages detected: ${messages.length}")
          if (updatedState.messageListenerTime == 0) {
            println(s"Start listening for messages for 2sec.")
            updatedState = updatedState.copy(messageListenerTime = currentTime)
          }

          relevantMessages.foreach { message =>
            println(s"Added following message to pendingMessages: ${message}")
            updatedState.pendingMessages.enqueue((message, currentTime))
          }
          println(s"All messages in pendingMessages: ${updatedState.pendingMessages}")
        }

        // Block adding new messages after 2 seconds
        if (currentTime - updatedState.messageListenerTime > 2000 && !updatedState.messageRespondRequested && updatedState.pendingMessages.nonEmpty) {
          println(s"All messages in pendingMessages: ${updatedState.pendingMessages}")
          // Directly use dialogueHistory as it already contains messages with metadata
          val historyWithMeta: Seq[(JsValue, String)] = updatedState.dialogueHistory.toSeq
          println(s"Requesting answer.")
          autoResponderManagerRef ! RequestAnswer(historyWithMeta, updatedState.pendingMessages, settings)

          updatedState = updatedState.copy(
            pendingMessages = mutable.Queue.empty,
            messageRespondRequested = true
          )

        }

      } else {
        println("Default chat is not focused.")
      }
    } else {
      println("AutoResponder is disabled.")
    }

    // Response Handling
    if (updatedState.messageRespondRequested && (System.currentTimeMillis() - updatedState.messageListenerTime > 2000)) {
      if (updatedState.preparedAnswer.nonEmpty) {

        logs = logs :+ Log(s"sending answer ${updatedState.preparedAnswer}")

        // Check if the preparedAnswer contains the symbol "|"
        val parts = updatedState.preparedAnswer.split("\\|")
        if (parts.length > 1) {
          // Send the first part now
          val currentPart = parts.head.trim
          actions = actions :+ FakeAction("typeText", None, Some(KeyboardText(currentPart)))

          // Prepare the remaining parts to be sent in the next iteration
          val remainingParts = parts.tail.mkString("|").trim
          updatedState = updatedState.copy(
            preparedAnswer = remainingParts, // Store remaining parts for the next cycle
            messageListenerTime = System.currentTimeMillis(), // Update time to reset delay calculation
            messageRespondRequested = true // Ensure that response is still requested
          )
        } else {
          // Send the entire message if there is no "|"
          actions = actions :+ FakeAction("typeText", None, Some(KeyboardText(updatedState.preparedAnswer)))
          // Clear the state after sending
          updatedState = updatedState.copy(
            preparedAnswer = "",
            messageListenerTime = 0,
            messageRespondRequested = false,
          )
        }
      }
    }


    ((actions, logs), updatedState)
  }

  def containsAnySpell(text: String, spells: List[String]): Boolean = {
    spells.exists(spell => text.contains(spell))
  }


}
