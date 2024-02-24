package processing

import akka.actor.{Actor, ActorRef}
import play.api.libs.json.{JsNumber, JsObject, JsValue, Json, Writes}
import player.Player
import mouse.{ActionCompleted, ActionTypes, FakeAction, ItemInfo, Mouse, TextCommand}
import keyboard.TypeText
import processing.ActionDetail
import main.scala.MainApp
import main.scala.MainApp.mouseMovementActorRef
import processing.AutoHeal.computeHealingActions
import processing.Fishing.computeFishingActions
import processing.Process.{detectPlayersAndMonsters, findBackpackPosition, findBackpackSlotForBlank, findRats, isPlayerDetected}
import processing.ProtectionZone.computeProtectionZoneActions
import processing.RuneMaker.computeRuneMakingActions
import processing.Training.computeTrainingActions
import userUI.SettingsUtils
import userUI.SettingsUtils.UISettings

import java.awt.event.{InputEvent, KeyEvent}
import java.awt.{Robot, Toolkit}
import java.io.{DataInputStream, DataOutputStream, IOException}
import java.net.{InetAddress, Socket, SocketException}
import java.nio.{ByteBuffer, ByteOrder}
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.Duration
import scala.util.Random
// import userUI.UIAppActor
//import src.main.scala.Main.JsonData
import mouse.{MouseMoveCommand, MouseMovementSettings}

case class cpResult(message: String, additionalData: Option[JsValue] = None)

trait CommandProcessor {
  def execute(json: JsValue, player: Player, settings: UISettings): cpResult
}

case class InitializeProcessor(player: Player, settings: UISettings)
// Create a placeholder for MouseMovementActor
case object ConnectToServer
// Define action types and their priorities



case class ProcessorState(
                           lastFishingCommandSent: Long = 0,
                           lastSpellCastTime: Long = 0,
                           lastRuneMakingTime: Long = 0,
                           lastMoveTime: Long = 0,
                           lastTrainingCommandSend: Long = 0,
                           lastProtectionZoneCommandSend: Long = 0,
                           settings: Option[UISettings]
                         )
case class UpdateSettings(settings: UISettings)

// Adjust the Action class to include the new field

case class Log(message: String)

case class BackPosition(x: Int, y: Int, z: Int)

object BackPosition {
  implicit val writes: Writes[BackPosition] = Json.writes[BackPosition]
}

//class JsonProcessorActor(var mouseMovementActor: ActorRef) extends Actor {
class JsonProcessorActor(mouseMovementActor: ActorRef, actionStateManager: ActorRef, actionKeyboardManager: ActorRef) extends Actor {
  // Mutable state
  var player: Option[Player] = None
  var settings: Option[UISettings] = None
  var state: ProcessorState = ProcessorState(settings = None)
//  var state: ProcessorState = ProcessorState()
//  var state: ProcessorState = ProcessorState()

  // Initialize lastFishingCommandTime to a value ensuring the first check will always pass
  private val fishingCommandInterval: Long = 1000
  private var lastFishingCommandTime: Long = System.currentTimeMillis() - fishingCommandInterval
  private val trainingCommandInterval: Long = 1000
  private var lastTrainingCommandTime: Long = System.currentTimeMillis() - fishingCommandInterval

  private var reconnectAttempts = 0
  private val maxReconnectAttempts = 5 // Maximum number of reconnection attempts
  private val spellCastInterval: Long = 10000 // 5 seconds
  private var lastSpellCastTime: Long = System.currentTimeMillis() - spellCastInterval

  var mouseState: String = "free"

  private var reconnecting = false
  val serverAddress = InetAddress.getByName("127.0.0.1")
  val port = 9997
  var socket: Option[Socket] = None
  var out: Option[DataOutputStream] = None
  var in: Option[DataInputStream] = None
  var isInitialized = false // Add a flag to track initialization



  def receive: Receive = {
    case MainApp.JsonData(json) =>
      println("JsonProcessorActor received JSON: " + json)

      // Check if JSON contains more than just "__status":"ok"
      if ((json \ "__status").asOpt[String].contains("ok") && json.as[JsObject].keys.size == 1) {
        // JSON only contains the status ok, handle accordingly
        println("Received simple status update, not processing further.")
      } else {
        // JSON contains additional information, process it
        val startTime = System.nanoTime()
        val newState = processJson(json, state)
        state = newState
        val endTime = System.nanoTime()
        val duration = (endTime - startTime) / 1e6d
        println(f"Processing JSON took $duration%.3f ms")
      }
//      val startTime = System.nanoTime() // Capture start time
//      println("JsonProcessorActor received JSON: " + json)
//      val newState = processJson(json, state)
//      state = newState // Critical: Ensure the state is updated here
//      val endTime = System.nanoTime() // Capture end time
//      val duration = (endTime - startTime) / 1e6d // Calculate duration in milliseconds
//      println(f"Processing JSON took $duration%.3f ms") // Print the duration

    case InitializeProcessor(p, s) =>
      // Update state with new settings
      state = state.copy(settings = Some(s))
      println(s"Processor initialized with player: ${p.characterName} and settings: $s")


    case ActionCompleted(actionType) =>
      println("Action completed.")

    case msg =>
      println(s"JsonProcessorActor received an unhandled message type: $msg")

  }

  private def processJson(json: JsValue, currentState: ProcessorState): ProcessorState = {
    (json \ "__status").asOpt[String] match {
      case Some("ok") => handleOkStatus(json, currentState)
      case Some("error") => handleErrorStatus(json); currentState
      case _ => println("Unknown status"); currentState
    }
  }


  private def handleErrorStatus(json: JsValue): Unit = {
    println("Error in processing json.")
  }

  private def handleOkStatus(json: JsValue, initialState: ProcessorState): ProcessorState = {
    // Sequentially apply action handlers, each updating the state based on its own logic
    val afterFishingState = performFishing(json, initialState)
    val afterHealingState = performAutoHealing(json, afterFishingState)
    val afterRuneMakingState = performRuneMaking(json, afterHealingState)
    val afterTrainingState = performTraining(json, afterRuneMakingState)
    val afterProtectionZoneState = performProtectionZone(json, afterTrainingState)
    // The final state after all updates
    afterProtectionZoneState
  }

  def performProtectionZone(json: JsValue, currentState: ProcessorState): ProcessorState = {
    val currentTime = System.currentTimeMillis()
    println("performProtectionZone activated.")
    currentState.settings.flatMap { settings =>
      if (settings.protectionZoneSettings.enabled) {
        println("Performing protection zone action.")
        val (actions, logs) = computeProtectionZoneActions(json, settings)
        executeActionsAndLogs(actions, logs, Some(settings))
        Some(currentState.copy(lastProtectionZoneCommandSend = currentTime))
      } else None
      //      if (settings.fishingSettings.enabled && currentTime - currentState.lastTrainingCommandSend >= 2000) {
      //        println("Performing training action.")
      //        val (actions, logs) = computeTrainingActions(json, settings)
      //        executeActionsAndLogs(actions, logs, Some(settings))
      //        Some(currentState.copy(lastTrainingCommandSend = currentTime))
      //      } else None
    }.getOrElse(currentState)
  }

  def performTraining(json: JsValue, currentState: ProcessorState): ProcessorState = {
    val currentTime = System.currentTimeMillis()
    println("performTraining activated.")
    currentState.settings.flatMap { settings =>
      if (settings.trainingSettings.enabled) {
        println("Performing training action.")
        val (actions, logs) = computeTrainingActions(json, settings)
        executeActionsAndLogs(actions, logs, Some(settings))
        Some(currentState.copy(lastTrainingCommandSend = currentTime))
      } else None
//      if (settings.fishingSettings.enabled && currentTime - currentState.lastTrainingCommandSend >= 2000) {
//        println("Performing training action.")
//        val (actions, logs) = computeTrainingActions(json, settings)
//        executeActionsAndLogs(actions, logs, Some(settings))
//        Some(currentState.copy(lastTrainingCommandSend = currentTime))
//      } else None
    }.getOrElse(currentState)
  }

  def performFishing(json: JsValue, currentState: ProcessorState): ProcessorState = {
    val currentTime = System.currentTimeMillis()
    currentState.settings.flatMap { settings =>
      if (settings.fishingSettings.enabled && currentTime - currentState.lastFishingCommandSent >= 2000) {
        println("Performing fishing action.")
        val (actions, logs) = computeFishingActions(json, settings)
        executeActionsAndLogs(actions, logs, Some(settings))
        Some(currentState.copy(lastFishingCommandSent = currentTime))
      } else None
    }.getOrElse(currentState)
  }

  def performAutoHealing(json: JsValue, currentState: ProcessorState): ProcessorState = {
    val currentTime = System.currentTimeMillis()
    currentState.settings.flatMap { settings =>
      if (settings.healingSettings.enabled && currentTime - currentState.lastSpellCastTime >= 1000) {
        println("Performing healing action.")
        val (actions, logs) = computeHealingActions(json, settings)
        executeActionsAndLogs(actions, logs, Some(settings))
        Some(currentState.copy(lastSpellCastTime = currentTime))
      } else None
    }.getOrElse(currentState)
  }

  def performRuneMaking(json: JsValue, currentState: ProcessorState): ProcessorState = {
    val currentTime = System.currentTimeMillis()
    currentState.settings.flatMap { settings =>
      if (settings.runeMakingSettings.enabled && currentTime - currentState.lastRuneMakingTime >= 3000) {
        println("Performing runemaking action.")
        val (actions, logs) = computeRuneMakingActions(json, settings)
        executeActionsAndLogs(actions, logs, Some(settings))
        Some(currentState.copy(lastRuneMakingTime = currentTime))
      } else None
    }.getOrElse(currentState)
  }

  def executeActionsAndLogs(actions: Seq[FakeAction], logs: Seq[Log], settingsOption: Option[SettingsUtils.UISettings]): Unit = {
    logs.foreach(log => println(log.message))

    settingsOption.foreach { settings =>
      actions.foreach {

        case FakeAction("useOnYourselfFunction", Some(itemInfo), None) =>
          // Handle function-based actions: sendJson with itemInfo for specific use
          sendJson(Json.obj("__command" -> "useOnYourself", "itemInfo" -> Json.toJson(itemInfo)))

        case FakeAction("useMouse", _, Some(actionDetail: MouseActions)) =>
          // Handle mouse sequence actions: mouse movement to ActionStateManager
          println("Fake action - use mouse")
          actionStateManager ! MouseMoveCommand(actionDetail.actions, settings.mouseMovements)

        case FakeAction("typeText", _, Some(actionDetail: KeyboardText)) =>
          println("Fake action - use keyboard - type text")
          // Direct the text to ActionKeyboardManager for typing
          actionKeyboardManager ! TypeText(actionDetail.text)

        case FakeAction("sayText", _, Some(actionDetail: KeyboardText)) =>
          // Function sayText for spells to TCP
          sendJson(Json.obj("__command" -> "sayText", "text" -> actionDetail.text))

        case FakeAction("moveBlankRuneBackFunction", None, Some(backPosition)) =>
          // Assuming backPosition is of type JsObject or has been correctly converted to JsObject
          sendJson(Json.obj("__command" -> "moveBlankRuneBack", "backPosition" -> backPosition))

        case FakeAction("setBlankRuneFunction", None, None) =>
          // Handle function-based actions: sendJson with itemInfo for specific use
          sendJson(Json.obj("__command" -> "setBlankRune"))

        case FakeAction("fishingFunction", None, Some(positionInfo)) =>
          sendJson(Json.obj("__command" -> "useFishingRod", "tilePosition" -> positionInfo))

        case FakeAction("switchAttackModeFunction", None, Some(attackModeInfo)) =>
          sendJson(Json.obj("__command" -> "switchAttackMode", "attackMode" -> attackModeInfo))

        case _ =>
          println("Unhandled action or log error")
      }
    }
  }


  //  private def handleOkStatus(json: JsValue, currentState: ProcessorState): ProcessorState = {
//    currentState.settings match {
//      case Some(settings) if settings.fishingSettings.enabled =>
//        val currentTime = System.currentTimeMillis()
//        // Check if sufficient time has passed since the last fishing command
//        if (currentTime - currentState.lastFishingCommandSent >= 2000) {
//          println("Performing fishing action.")
//          val (actions, logs) = computeFishingActions(json, settings)
//          executeActionsAndLogs(actions, logs, Some(settings))
//          currentState.copy(lastFishingCommandSent = currentTime)
//        } else {
//          println("Throttling fishing action due to insufficient delay since the last command.")
//          currentState // Return the current state unmodified if the delay interval has not passed
//        }
//      case _ => currentState // Return the current state if fishing is not enabled or settings are undefined
//    }
//  }



  //  private def processJson(json: JsValue): Unit = {
  //    // First, check if the status is "ok" and if the 'msg' key is not present
  //    (json \ "__status").asOpt[String] match {
  //      case Some("ok") if (json \ "msg").isDefined => // If 'msg' key exists, do nothing or log
  //        println("Message key exists. Skipping handleOkStatus.")
  //      case Some("ok") => // If 'msg' key doesn't exist, proceed to handleOkStatus
  //        handleOkStatus(json)
  //      case Some("error") => // Handle error status as before
  //        handleErrorStatus(json)
  //      case _ => // Handle unknown status as before
  //        println("Unknown status")
  //    }
  //  }

//
//  def performRuneMaking(json: JsValue, settings: SettingsUtils.UISettings): Unit = {
//    val (actions, logs) = computeRuneMakingActions(json, settings)
//    executeActionsAndLogs(actions, logs, Some(settings))
//  }
//
//  def performAutoHeal(json: JsValue, settings: SettingsUtils.UISettings): Unit = {
//    val (actions, logs) = computeHealingActions(json, settings)
//    executeActionsAndLogs(actions, logs, Some(settings))
//  }

//
//  def performFishing(json: JsValue, settings: UISettings): Unit = {
//    println("Performing fishing action.")
//    val (actions, logs) = computeFishingActions(json, settings)
//    executeActionsAndLogs(actions, logs, Some(settings))
//  }




  // Assuming necessary imports and context are available

  private def activateFishing(json: JsValue, mouseMovement: Boolean): Unit = {
    (json \ "__status").asOpt[String].foreach { status =>
      if (status == "ok") {
        (json \ "characterInfo").asOpt[JsObject].foreach { characterInfo =>
          val charX = (characterInfo \ "PositionX").asOpt[Int].getOrElse(0)
          val charY = (characterInfo \ "PositionY").asOpt[Int].getOrElse(0)
          val charZ = (characterInfo \ "PositionZ").asOpt[Int].getOrElse(0)

          (json \ "areaInfo" \ "tiles").asOpt[JsObject].foreach { tiles =>
            tiles.fields.foreach {
              case (tileId, tileValues) =>
                val tileX = tileId.substring(0, 5).toInt
                val tileY = tileId.substring(5, 10).toInt
                val tileZ = tileId.substring(10, 12).toInt

                // Check if any value in tileValues matches the specified IDs
                val isValidTileId = tileValues.as[JsObject].values.exists {
                  case JsNumber(n) => List(619, 620, 621, 618).contains(n.toInt)
                  case _ => false
                }

                val currentTime = System.currentTimeMillis()
                if (currentTime - lastFishingCommandTime > fishingCommandInterval && isValidTileId) {

                  // Calculate X and Y distance
                  val deltaX = Math.abs(charX - tileX)
                  val deltaY = Math.abs(charY - tileY)

                  if (deltaX >= 2 && deltaX <= 6 && deltaY == 0) {
                    if (!mouseMovement) {
                      val waterTileId = tileId
                      println(waterTileId)
                      sendFishingCommand(waterTileId)
                      lastFishingCommandTime = currentTime
                    } else {

                      val arrowsLoc = (json \ "screenInfo" \ "inventoryPanelLoc" \ "arrows").asOpt[JsObject]
                      val arrowsX = arrowsLoc.flatMap(loc => (loc \ "x").asOpt[Int]).getOrElse(0)
                      val arrowsY = arrowsLoc.flatMap(loc => (loc \ "y").asOpt[Int]).getOrElse(0)

                      // Calculate target tile key
                      val targetTileScreenKey = s"${8 + deltaX}x${6 + deltaY}"
                      val targetTileScreenX = (json \ "screenInfo" \ "mapPanelLoc" \ targetTileScreenKey \ "x").asOpt[Int].getOrElse(0)
                      val targetTileScreenY = (json \ "screenInfo" \ "mapPanelLoc" \ targetTileScreenKey \ "y").asOpt[Int].getOrElse(0)

                      println(s"Target Tile Screen X: $targetTileScreenX, Y: $targetTileScreenY") // Print the target tile screen coordinates

/*                      Mouse.mouseMoveSmooth(robotInstance, Some((arrowsX, arrowsY)))
                      Mouse.rightClick(robotInstance, Some((arrowsX, arrowsY)))
                      Mouse.mouseMoveSmooth(robotInstance, Some((targetTileScreenX, targetTileScreenY)))
                      Mouse.leftClick(robotInstance, Some((targetTileScreenX, targetTileScreenY)))*/
                      lastFishingCommandTime = currentTime
                    }
                  }
                }
            }
          }
        }
      }
    }
  }

  private def sendFishingCommand(waterTileId: String): Unit = {
    val x = waterTileId.substring(0, 5).toInt
    val y = waterTileId.substring(5, 10).toInt
    val zLength = waterTileId.length - 10
    val z = waterTileId.substring(10, 10 + zLength).toInt

    val jsonCommand = Json.obj("__command" -> "useFishingRod", "tilePosition" -> Json.obj("x" -> x, "y" -> y, "z" -> z))
    sendJson(jsonCommand)
    println(s"Fishing command sent for position: x=$x, y=$y, z=$z")
  }

  class RatAttackProcessor extends CommandProcessor {
    override def execute(json: JsValue, player: Player, settings: UISettings): cpResult = {
      // Pure function logic here
      cpResult("Rat attacked")
    }
  }
  private def attackOnRat(ratId: Long): Unit = {

    val command = Json.obj("__command" -> "targetAttack", "ratId" -> ratId)
    sendJson(command) // Directly send the command to the TCP server
  }

  def sendCommandToTcpServer(json: JsValue): Unit = {
    sendJson(json)
  }

  def sendJson(json: JsValue): Boolean = {
    // Improved socket check and reconnection logic
    if (socket.isEmpty || socket.exists(!_.isConnected)) {
      println("Socket is not connected. Attempting to reconnect.")
      connectToServer()
      return false
    }

    try {
      println("Sending JSON: " + json)
      out.flatMap { o =>
        val data = Json.stringify(json).getBytes("UTF-8")
        try {
          val lengthBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(data.length).array()
          o.write(lengthBytes)
          o.write(data)
          o.flush()
          println("JSON sent successfully.")
          Some(true)
        } catch {
          case e: IOException =>
            println(s"IOException during sending JSON: $e")
            e.printStackTrace()
            None
        }
      }.getOrElse(false)
    } catch {
      case e: SocketException =>
        println(s"SocketException when sending JSON: ${e.getMessage}")
        connectToServer()
        false
    }
  }

  override def preStart(): Unit = {
    super.preStart()
  }

  def connectToServer(): Unit = {
    if (reconnectAttempts < maxReconnectAttempts) {
      try {
        println("Attempting to connect to server...")
        val s = new Socket(serverAddress, port)
        socket = Some(s)
        out = Some(new DataOutputStream(s.getOutputStream))
        in = Some(new DataInputStream(s.getInputStream))
        reconnectAttempts = 0 // Reset the counter on successful connection
        println("Connected to server")
      } catch {
        case e: IOException =>
          println(s"Failed to connect to server: $e")
          reconnectAttempts += 1
//          scheduleReconnection()
      }
    } else {
      println("Maximum reconnection attempts reached. Stopping reconnection attempts.")
    }
  }

}



//// TO BE EDITED
//if (currentSettings.caveBot) {
//  // Assuming findRats is related to the Cavebot functionality
//  (json \ "battleInfo").asOpt[JsValue].foreach { battleInfo =>
//    findRats(battleInfo, currentSettings.mouseMovements).foreach(attackOnRat)
//  }
//}
//
//if (currentSettings.fishing) {
//  activateFishing(json, currentSettings.mouseMovements)
//}
//// New logic for player detection and sound alert
//if (currentSettings.protectionZoneSettings.enabled && currentSettings.protectionZoneSettings.playerOnScreenAlert) {
//  (json \ "battleInfo").asOpt[JsObject].foreach { battleInfo =>
//    if (isPlayerDetected(battleInfo)) {
//      // Trigger a beep sound as an alert
//      Toolkit.getDefaultToolkit().beep()
//      // Log or perform additional actions as needed
//      println("Player on the screen!")
//    }
//  }
//}


//// Use the function to make decisions
//if (currentSettings.protectionZoneSettings.escapeToProtectionZone) {
//  if (detectPlayersAndMonsters(json)) {
//    // Logic for when another player or monster is detected
//    if (currentSettings.mouseMovements) {
//      println("Player dected, move with keyboard.")
//    } else {
//      println("Player dected, move function.")
//    }
//  } else {
//    // Logic for when no other players or monsters are detected
//    // Placeholder to exit or skip the escapeToProtectionZone logic
//  }
//}