package processing

import mouse.FakeAction
import play.api.libs.json.{JsObject, JsValue}
import userUI.SettingsUtils

object ChatReader {
  def computeChatReaderActions(json: JsValue, settings: SettingsUtils.UISettings, currentState: ProcessorState): ((Seq[FakeAction], Seq[Log]), ProcessorState) = {
    println(s"computeChatReaderActions activated.")
    val startTime = System.nanoTime()
    var actions: Seq[FakeAction] = Seq()
    var logs: Seq[Log] = Seq()
    var updatedState = currentState
    val currentTime = System.currentTimeMillis()

    // Extracting textTabsInfo from the JSON
    val textTabsInfoJson = (json \ "textTabsInfo").asOpt[JsObject]
    val basicTabsList = List("Default", "Server Log")
    var additionalTabsList: List[String] = List()
    val chatDesiredTabsList = basicTabsList ++ additionalTabsList

    // Extract the name of the currently focused tab
    val focusedTabName = textTabsInfoJson.flatMap { tabsInfo =>
      tabsInfo.fields.find { case (_, tabInfo) =>
        (tabInfo \ "isFocused").asOpt[Boolean].getOrElse(false)
      }.map { case (_, tabInfo) =>
        (tabInfo \ "tabName").as[String]
      }
    }.getOrElse("None")

    println(s"chatReaderStatus: ${updatedState.chatReaderStatus}, chatDesiredTab: ${updatedState.chatDesiredTab}, chatAction: ${updatedState.chatAction}, focusedTab: $focusedTabName")

    // Process based on the current chatReaderStatus
    updatedState.chatReaderStatus match {
      case "not_ready" =>
        textTabsInfoJson.foreach { tabsInfo =>
          // List of currently opened tabs by name
          val openedTabs = tabsInfo.fields.map { case (_, tabInfo) =>
            (tabInfo \ "tabName").as[String]
          }

          // Identify tabs that need to be closed (not in the desired tabs list)
          val tabsToClose = openedTabs.filterNot(chatDesiredTabsList.contains)

          // If there are any tabs to close, initiate closing
          if (tabsToClose.nonEmpty) {
            // Focus on the first tab to close and set up to close it
            val tabToClose = tabsToClose.head
            updatedState = updatedState.copy(chatDesiredTab = tabToClose, chatReaderStatus = "refocusing", chatAction = "close")
            return ((actions, logs), updatedState)
          }

          // If no tabs need closing, check if all desired tabs are open
          val missingTabs = chatDesiredTabsList.diff(openedTabs)

          // If there are any tabs to open, initiate opening
          if (missingTabs.nonEmpty) {
            // Focus on the first tab to open and set up to open it
            val tabToOpen = missingTabs.head
            actions = actions ++ openTab() // Placeholder function to open the tab
            updatedState = updatedState.copy(chatReaderStatus = "open_window") // Stay in "not_ready" until all tabs are handled
            return ((actions, logs), updatedState)
          }

          // If all desired tabs are open and no tabs need to be closed, set status to ready
          if (focusedTabName == "Server Log") {
            updatedState = updatedState.copy(chatReaderStatus = "ready")
          } else {
            // If "Server Log" is not focused, focus on it
            updatedState = updatedState.copy(chatDesiredTab = "Server Log", chatReaderStatus = "refocusing")
          }

          return ((actions, logs), updatedState)
        }

      case "open_window" =>

        // If no unread message found, return the current state as is
        return ((actions, logs), updatedState)

      case "ready" =>
        textTabsInfoJson.foreach { tabsInfo =>
          // Loop through tabs to check if there is any unread message
          tabsInfo.fields.foreach { case (tabId, tabInfo) =>
            val hasUnread = (tabInfo \ "hasUnread").asOpt[Boolean].getOrElse(false)
            if (hasUnread) {
              val tabName = (tabInfo \ "tabName").asOpt[String].getOrElse("Unknown")
              println(s"Unread message found in tab: $tabName")

              // Save this tab as chatDesiredTab, update status to refocusing, and return immediately
              updatedState = updatedState.copy(chatDesiredTab = tabName, chatReaderStatus = "refocusing")
              return ((actions, logs), updatedState)
            }
          }
        }
        // If no unread message found, return the current state as is
        return ((actions, logs), updatedState)




      case "refocusing" =>
        // Check if the focused tab is equal to the chatDesiredTab
        if (focusedTabName == updatedState.chatDesiredTab) {
          // Focused on the correct tab, log the event
          logs = logs :+ Log(s"Focused on tab: ${updatedState.chatDesiredTab}")

          // Check if there is a pending action
          if (updatedState.chatAction != "") {
            // If there is a pending action, change status to "action"
            updatedState = updatedState.copy(chatReaderStatus = "action")
          } else {
            // If no pending action, change status to "not_ready"
            updatedState = updatedState.copy(chatReaderStatus = "not_ready")
          }

          return ((actions, logs), updatedState)
        } else {
          // If not focused on the desired tab, call moveToNextTab and return immediately
          actions = actions ++ moveToNextTab()
          return ((actions, logs), updatedState)
        }


      case "action" =>
        // If chatAction is "close", call closeTab, reset to "not_ready" state, and return immediately
        if (updatedState.chatAction == "close") {
          actions = actions ++ closeTab()
          updatedState = updatedState.copy(chatReaderStatus = "not_ready", chatAction = "")
          return ((actions, logs), updatedState)
        } else if (updatedState.chatAction == "probe") {
          actionOnTab()
        } else if (updatedState.chatAction == "set") {
          // Check the focused tab
          if (focusedTabName == "Server Log") {
            // If focused on "Server Log", change status to "not_ready"
            updatedState = updatedState.copy(chatReaderStatus = "not_ready")
          } else {
            // If focused on a different tab, set chatDesiredTab to "Server Log" and status to "refocusing"
            updatedState = updatedState.copy(chatDesiredTab = "Server Log", chatReaderStatus = "refocusing")
          }

          return ((actions, logs), updatedState)
        } else {

        }


      case _ =>
        logs = logs :+ Log("Unhandled status in chatReaderStatus")
        return ((actions, logs), updatedState)
    }

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


  def actionOnTab(): Unit = {
    // Placeholder logic for closing a tab
    println("Action on tab")
  }

  def openTab(): Seq[FakeAction] = {
    var actions: Seq[FakeAction] = Seq()
    actions = actions :+ FakeAction("pressKeys", None, Some(PushTheButtons(Seq("Ctrl", "O"))))
    println("Opening tab")
    actions
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
