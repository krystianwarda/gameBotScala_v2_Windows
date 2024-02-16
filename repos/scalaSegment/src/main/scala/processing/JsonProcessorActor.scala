package processing

import akka.actor.{Actor, ActorRef}
import play.api.libs.json.{JsNumber, JsObject, JsValue, Json, Writes}
import player.Player
import mouse.{ActionCompleted, ActionTypes, FakeAction, ItemInfo, Mouse, TextCommand}
import keyboard.{TypeText}
import processing.ActionDetail
import main.scala.MainApp
import main.scala.MainApp.mouseMovementActorRef
import processing.AutoHeal.computeHealingActions
import processing.Process.{detectPlayersAndMonsters, findBackpackPosition, findBackpackSlotForBlank, findRats, isPlayerDetected}
import processing.RuneMaker.computeRuneMakingActions
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


// Define a sum type for action details

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


  import context.dispatcher

  // Add a lastFishingCommandSent variable
  private var lastFishingCommandSent: Long = 0
  private val fishingCommandInterval: Long = 5 // 10 seconds interval
  private var reconnectAttempts = 0
  private val maxReconnectAttempts = 5 // Maximum number of reconnection attempts
  private var lastSpellCastTime: Long = 50000
  private val spellCastInterval: Long = 10000 // 5 seconds
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
      processJson(json)

    case InitializeProcessor(p, s) =>
      player = Some(p)
      settings = Some(s)
      println(s"Processor initialized with player: ${p.characterName} and settings: TO BE MODIFIED")

    case ActionCompleted(actionType) => handleActionCompleted(actionType)
      // Handle action completion
      println("Action completed.")
    // Update any relevant state or perform follow-up actions here

    case _ => println("JsonProcessorActor received an unhandled message type.")
  }


  private def processJson(json: JsValue): Unit = {
    // First, check if the status is "ok" and if the 'msg' key is not present
    (json \ "__status").asOpt[String] match {
      case Some("ok") if (json \ "msg").isDefined => // If 'msg' key exists, do nothing or log
        println("Message key exists. Skipping handleOkStatus.")
      case Some("ok") => // If 'msg' key doesn't exist, proceed to handleOkStatus
        handleOkStatus(json)
      case Some("error") => // Handle error status as before
        handleErrorStatus(json)
      case _ => // Handle unknown status as before
        println("Unknown status")
    }
  }

  def handleActionCompleted(actionType: ActionTypes.Value): Unit = {
    // Logic to handle action completion, specific to JsonProcessorActor's responsibilities
    println(s"Action $actionType completed. JsonProcessorActor can now proceed with follow-up actions.")
    // Example: Checking if a specific action's completion triggers another process
    if (actionType == ActionTypes.Heal) {
      // Trigger any specific logic that should happen after healing
    }
  }

  private def handleErrorStatus(json: JsValue): Unit = {
    println("Error in processing json.")
  }

  private def handleOkStatus(json: JsValue): Unit = {
    settings.foreach { currentSettings =>

      performAutoHeal(json, currentSettings)

      performRuneMaking(json, currentSettings)




    }
  }

  def performRuneMaking(json: JsValue, settings: SettingsUtils.UISettings): Unit = {
    val (actions, logs) = computeRuneMakingActions(json, settings)
    executeActionsAndLogs(actions, logs, Some(settings))
  }

  def performAutoHeal(json: JsValue, settings: SettingsUtils.UISettings): Unit = {
    val (actions, logs) = computeHealingActions(json, settings)
    executeActionsAndLogs(actions, logs, Some(settings))
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
          actionStateManager ! MouseMoveCommand(actionDetail.actions, settings.mouseMovements)

        case FakeAction("typeText", _, Some(actionDetail: KeyboardText)) =>
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
          sendJson(Json.obj("__command" -> "setBlankRune" ))

        case _ =>
          println("Unhandled action or log error")
      }
    }
  }



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
                if (currentTime - lastFishingCommandSent > fishingCommandInterval && isValidTileId) {

                  // Calculate X and Y distance
                  val deltaX = Math.abs(charX - tileX)
                  val deltaY = Math.abs(charY - tileY)

                  if (deltaX >= 2 && deltaX <= 6 && deltaY == 0) {
                    if (!mouseMovement) {
                      val waterTileId = tileId
                      println(waterTileId)
                      sendFishingCommand(waterTileId)
                      lastFishingCommandSent = currentTime
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
                      lastFishingCommandSent = currentTime
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