package processing

import mouse.FakeAction
import play.api.libs.json.{JsObject, JsValue, Json}
import processing.CaveBot.Vec
import processing.Process.performMouseActionSequance
import userUI.SettingsUtils

import scala.collection.immutable.Seq
import scala.collection.mutable

object ChatReader {
  def computeChatReaderActions(json: JsValue, settings: SettingsUtils.UISettings, currentState: ProcessorState): ((Seq[FakeAction], Seq[Log]), ProcessorState) = {
    println(s"computeChatReaderActions activated.")
    val startTime = System.nanoTime()
    var actions: Seq[FakeAction] = Seq()
    var logs: Seq[Log] = Seq()
    var updatedState = currentState
    val currentTime = System.currentTimeMillis()

//    if (currentTime - updatedState.lastChatReaderAction > updatedState.retryMidDelay) {
//      updatedState = updatedState.copy(lastChatReaderAction = currentTime)
//      // Extracting textTabsInfo from the JSON
//      val textTabsInfoJson = (json \ "textTabsInfo").asOpt[JsObject]
//      val basicTabsList = List("Default", "Server Log")
//
//      var additionalTabsList: List[String] = List()
//      if (settings.teamHuntSettings.youAreBlocker) {
//        additionalTabsList = additionalTabsList :+ "Loot Channel"
//      }
//
//
//      if (!updatedState.inParty) {
//        // Extract battle information from the JSON
//        val battleInfo = (json \ "battleInfo").asOpt[JsObject].getOrElse(Json.obj())
//
//        // Check if battleInfo is not empty and if there is at least one creature with isPartyMember = true
//        val isPartyMemberInBattle = battleInfo.keys.exists { creatureKey =>
//          (battleInfo \ creatureKey \ "isPartyMember").asOpt[Boolean].getOrElse(false)
//        }
//
//        // If there is a party member in battle, open "Party Channel" and set inParty to true
//        if (isPartyMemberInBattle) {
//          additionalTabsList = additionalTabsList :+ "Party Channel"
//
//          // Update the state to reflect that the player is now in a party
//          updatedState = updatedState.copy(inParty = true)
//        }
//      }
//
//      // Assume chatDesiredTabsList is part of updatedState
//      val currentChatTabs = updatedState.chatDesiredTabsList
//
//      // Merge basicTabsList and additionalTabsList, and filter out duplicates
//      val newTabsToAdd = (basicTabsList ++ additionalTabsList).filterNot(currentChatTabs.contains)
//
//      // Update the chatDesiredTabsList by adding only the new tabs and ensuring no duplicates
//      val updatedChatTabsList = (currentChatTabs ++ newTabsToAdd).distinct
//
//      // Update the state with the new list
//      updatedState = updatedState.copy(chatDesiredTabsList = updatedChatTabsList)
//
//
//      // Extract the name of the currently focused tab
//      val focusedTabName = textTabsInfoJson.flatMap { tabsInfo =>
//        tabsInfo.fields.find { case (_, tabInfo) =>
//          (tabInfo \ "isFocused").asOpt[Boolean].getOrElse(false)
//        }.map { case (_, tabInfo) =>
//          (tabInfo \ "tabName").as[String]
//        }
//      }.getOrElse("None")
//
//      println(s"chatReaderStatus: ${updatedState.chatReaderStatus}, chatDesiredTab: ${updatedState.chatDesiredTab}, chatAction: ${updatedState.chatAction}, focusedTab: $focusedTabName, chatDesiredTabsList: ${updatedState.chatDesiredTabsList}")
//
//      // Process based on the current chatReaderStatus
//      updatedState.chatReaderStatus match {
//        case "not_ready" =>
//          textTabsInfoJson.foreach { tabsInfo =>
//            // List of currently opened tabs by name
//            val openedTabs = tabsInfo.fields.map { case (_, tabInfo) =>
//              (tabInfo \ "tabName").as[String]
//            }
//
//            // Identify tabs that need to be closed (not in the desired tabs list)
//            val tabsToClose = openedTabs.filterNot(updatedState.chatDesiredTabsList.contains)
//
//            // If there are any tabs to close, initiate closing
//            if (tabsToClose.nonEmpty) {
//              // Focus on the first tab to close and set up to close it
//              val tabToClose = tabsToClose.head
//              updatedState = updatedState.copy(chatDesiredTab = tabToClose, chatReaderStatus = "refocusing", chatAction = "close")
//              return ((actions, logs), updatedState)
//            }
//
//            // If no tabs need closing, check if all desired tabs are open
//            val missingTabs = updatedState.chatDesiredTabsList.diff(openedTabs)
//
//            // If there are any tabs to open, initiate opening
//            if (missingTabs.nonEmpty) {
//              // Focus on the first tab to open and set up to open it
//              val tabToOpen = missingTabs.head
//              actions = actions ++ openTab() // Placeholder function to open the tab
//              updatedState = updatedState.copy(chatReaderStatus = "open_window", chatAction = tabToOpen) // Stay in "not_ready" until all tabs are handled
//              return ((actions, logs), updatedState)
//            }
//
//            // If all desired tabs are open and no tabs need to be closed, set status to ready
//            if (focusedTabName == "Server Log") {
//              updatedState = updatedState.copy(chatReaderStatus = "ready")
//            } else {
//              // If "Server Log" is not focused, focus on it
//              updatedState = updatedState.copy(chatDesiredTab = "Server Log", chatReaderStatus = "refocusing")
//            }
//
//            return ((actions, logs), updatedState)
//          }
//
//        case "open_window" =>
//
//          val extraWindowInfo = (json \ "screenInfo" \ "extraWindowLoc").asOpt[JsObject].getOrElse(Json.obj())
//          if (!extraWindowInfo.keys.contains(updatedState.chatAction)) {
//            actions = actions ++ openTab()
//          } else {
//            // Extract tab (updatedState.chatAction) position
//            val tabInfo = (extraWindowInfo \ updatedState.chatAction).asOpt[JsObject].getOrElse(Json.obj())
//            val tabVec = Vec(
//              (tabInfo \ "posX").asOpt[Int].getOrElse(0),
//              (tabInfo \ "posY").asOpt[Int].getOrElse(0)
//            )
//
//            // Extract Open button position
//            val openButtonInfo = (extraWindowInfo \ "Open").asOpt[JsObject].getOrElse(Json.obj())
//            val openButtonVec = Vec(
//              (openButtonInfo \ "posX").asOpt[Int].getOrElse(0),
//              (openButtonInfo \ "posY").asOpt[Int].getOrElse(0)
//            )
//
//            // Call clickAndOpenChannel with the extracted tab and open button positions
//            actions = actions ++ clickAndOpenChannel(updatedState.chatAction, tabVec, openButtonVec)
//            updatedState = updatedState.copy(chatReaderStatus = "not_ready", chatAction = "")
//          }
//
//          return ((actions, logs), updatedState)
//
//        case "ready" =>
//          textTabsInfoJson.foreach { tabsInfo =>
//            // Extract the current chatDesiredTabsList
//            val currentChatTabs = updatedState.chatDesiredTabsList
//
//            // Loop through tabs to check for unread messages
//            tabsInfo.fields.foreach { case (tabId, tabInfo) =>
//              val hasUnread = (tabInfo \ "hasUnread").asOpt[Boolean].getOrElse(false)
//              if (hasUnread) {
//                val tabName = (tabInfo \ "tabName").asOpt[String].getOrElse("Unknown")
//                println(s"Unread message found in tab: $tabName")
//
//                val spyLevelInfo = (json \ "spyLevelInfo").asOpt[JsObject].getOrElse(Json.obj())
//
//                // Get characterInfo's posZ for comparison
//                val characterPosZ = (json \ "characterInfo" \ "PositionZ").asOpt[Int].getOrElse(-1)
//
//
//                println(s"characterPosZ: ${characterPosZ}")
//                println(s"spyLevelInfo: ${spyLevelInfo}")
//
//                val isPlayerOnTheScreen: Boolean = spyLevelInfo.values.exists { playerJson =>
//                  val playerPosZ = (playerJson \ "PositionZ").asOpt[Int].getOrElse(-1)
//                  val isPlayer = (playerJson \ "IsPlayer").asOpt[Boolean].getOrElse(false)
//                  val isPartyMember = (playerJson \ "IsPartyMember").asOpt[Boolean].getOrElse(false)
//
//                  playerPosZ == characterPosZ && isPlayer && !isPartyMember
//                }
//                println(s"isPlayerOnTheScreen: ${isPlayerOnTheScreen}")
//
//
//                // here check if there is a
//                if (tabName != "Default") {
//                  updatedState = updatedState.copy(chatDesiredTab = tabName, chatReaderStatus = "refocusing")
//                } else if (settings.autoResponderSettings.enabled && tabName == "Default" && isPlayerOnTheScreen) {
//                  updatedState = updatedState.copy(chatDesiredTab = tabName, chatReaderStatus = "refocusing")
//                }
//
//                // Return immediately after finding the first unread message
//                return ((actions, logs), updatedState)
//              }
//            }
//          }
//
//          // Assume chatDesiredTabsList is part of updatedState
//          val currentChatTabs = updatedState.chatDesiredTabsList
//          // Merge basicTabsList and additionalTabsList, and filter out duplicates
//          val newTabsToAdd = (basicTabsList ++ additionalTabsList).filterNot(currentChatTabs.contains)
//
//          // Update the chatDesiredTabsList by adding only the new tabs
//          if (newTabsToAdd.nonEmpty) {
//            val updatedChatTabsList = (currentChatTabs ++ newTabsToAdd).distinct
//            // Update the state and mark chatReaderStatus as "not_ready" since no unread messages were found
//            updatedState = updatedState.copy(chatReaderStatus = "not_ready")
//          }
//
//          // If no unread message is found, return the current state as is
//          return ((actions, logs), updatedState)
//
//
//        case "refocusing" =>
//          // Check if the focused tab is equal to the chatDesiredTab
//          if (focusedTabName == updatedState.chatDesiredTab) {
//            // Focused on the correct tab, log the event
//            logs = logs :+ Log(s"Focused on tab: ${updatedState.chatDesiredTab}")
//            updatedState = updatedState.copy(chatReaderStatus = "action")
//
////            // Check if there is a pending action
//            if (updatedState.chatAction != "") {
//              // If there is a pending action, change status to "action"
//              updatedState = updatedState.copy(chatReaderStatus = "action")
//            } else {
//              // If no pending action, change status to "not_ready"
//              updatedState = updatedState.copy(chatReaderStatus = "action", chatAction="probe")
//            }
//
//            return ((actions, logs), updatedState)
//          } else {
//            // If not focused on the desired tab, call moveToNextTab and return immediately
//            actions = actions ++ moveToNextTab()
//            return ((actions, logs), updatedState)
//          }
//
//
//        case "action" =>
//          // If chatAction is "close", call closeTab, reset to "not_ready" state, and return immediately
//          if (updatedState.chatAction == "close") {
//            actions = actions ++ closeTab()
//            updatedState = updatedState.copy(chatReaderStatus = "not_ready", chatAction = "")
//            return ((actions, logs), updatedState)
//          } else if (updatedState.chatAction == "probe") {
//
//            val resultActionOnTab = actionOnTab(focusedTabName, json, updatedState, actions, logs)
//            actions = resultActionOnTab._1._1
//            logs = resultActionOnTab._1._2
//            updatedState = resultActionOnTab._2
//
//          } else if (updatedState.chatAction == "set") {
//            // Check the focused tab
//            if (focusedTabName == "Server Log") {
//              // If focused on "Server Log", change status to "not_ready"
//              updatedState = updatedState.copy(chatReaderStatus = "not_ready")
//            } else {
//              // If focused on a different tab, set chatDesiredTab to "Server Log" and status to "refocusing"
//              updatedState = updatedState.copy(chatDesiredTab = "Server Log", chatReaderStatus = "refocusing")
//            }
//
//            return ((actions, logs), updatedState)
//          } else {
//
//          }
//
//
//        case _ =>
//          logs = logs :+ Log("Unhandled status in chatReaderStatus")
//          return ((actions, logs), updatedState)
//      }
//
//
//    }

    ((actions, logs), updatedState)
  }

  def moveToNextTab(): Seq[FakeAction] = {
    var actions: Seq[FakeAction] = Seq()
    actions = actions :+ FakeAction("pressKeys", None, Some(PushTheButtons(Seq("TAB"))))
    println("Moving to next tab")
    actions
  }

  def closeTab(): Seq[FakeAction] = {
    var actions: Seq[FakeAction] = Seq()
    actions = actions :+ FakeAction("pressKeys", None, Some(PushTheButtons(Seq("Ctrl", "E"))))
    println("Closing tab")
    actions
  }


  def actionOnTab(
                   focusedTabName: String,
                   json: JsValue,
                   initialState: ProcessorState,
                   initialActions: Seq[FakeAction],
                   initialLogs: Seq[Log],
                 ): ((Seq[FakeAction], Seq[Log]), ProcessorState) = {
    var actions: Seq[FakeAction] = initialActions
    var logs: Seq[Log] = initialLogs
    var updatedState = initialState

//    // Placeholder logic for closing a tab
//    if (focusedTabName == "Party Channel") {
//
//      // find messages which are not in dialogueHistoryPartyTab and extract new message
//
//    } else {
//
//    }
    updatedState = updatedState.copy(chatReaderStatus = "not_ready", chatAction = "")
    println("Action on tab")
    ((actions, logs), updatedState)
  }

  def openTab(): Seq[FakeAction] = {
    var actions: Seq[FakeAction] = Seq()
    actions = actions :+ FakeAction("pressKeys", None, Some(PushTheButtons(Seq("Ctrl", "O"))))
    println("Opening tab")
    actions
  }

  def clickAndOpenChannel(channelToClick: String, tabVec: Vec, openButtonVec: Vec): Seq[FakeAction] = {
    println(s"Clicking tab: ${channelToClick}")
    val actionsSeq = Seq(
      MouseAction(tabVec.x, tabVec.y, "move"),
      MouseAction(tabVec.x, tabVec.y, "pressLeft"),
      MouseAction(tabVec.x, tabVec.y, "releaseLeft"),
      MouseAction(openButtonVec.x, openButtonVec.y, "move"),
      MouseAction(openButtonVec.x, openButtonVec.y, "pressLeft"),
      MouseAction(openButtonVec.x, openButtonVec.y, "releaseLeft")
    )
    performMouseActionSequance(actionsSeq)
  }


}


// channels to stay open (Default, Server Log, Loot Channel)

// if in party open Party Channel

// process of closing channel is to press tab (switching - no so fast at lease one per second)  until isFocused=true, and than pressing ctrl+E

// if some chat is closed we need function which
// ctrl + O (OPEN channels window)
// confirm the channels window is open

// if its chat with other player you type a name and press enter
// if its specific channel you need to click on specific name and than press enter

// if there is chat with "hasUnread":true, you need to use tab to until this chat isFocused=True and hasUnread:false

// lets make a placeholder of following functions which later on we will fill with logic:
// moveToNextTab function
// closeTab function
// openChatsWindow
// openSpecificChatFromList


//  def openChatsWindow(): Unit = {
//    // Placeholder logic for opening the chat window
//    logs = logs :+ Log("openChatsWindow called")
//  }
//
//  def openSpecificChatFromList(chatName: String): Unit = {
//    // Placeholder logic for opening a specific chat from the list
//    logs = logs :+ Log(s"openSpecificChatFromList called for $chatName")
//  }
