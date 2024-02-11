package processing

import akka.actor.{Actor, ActorRef}
import play.api.libs.json.{JsNumber, JsObject, JsValue, Json}
import player.Player
import mouse.{ActionCompleted, ActionTypes, Mouse, MouseAction}
import main.scala.MainApp
import main.scala.MainApp.mouseMovementActorRef
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


//class JsonProcessorActor(var mouseMovementActor: ActorRef) extends Actor {
class JsonProcessorActor(mouseMovementActor: ActorRef, actionStateManager: ActorRef) extends Actor {
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
      println(s"Processor initialized with player: ${p.characterName} and settings: ${s.autoHeal}")

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

      if (currentSettings.runeMaker) {
        val currentTime = System.currentTimeMillis()
        val slot6Position = (json \ "screenInfo" \ "inventoryPanelLoc" \ "inventoryWindow" \ "contentsPanel" \ "slot6").as[JsObject]
        val slot6X = (slot6Position \ "x").as[Int]
        val slot6Y = (slot6Position \ "y").as[Int]
        (json \ "EqInfo" \ "6" \ "itemId").asOpt[Int] match {
          case Some(3160) =>
            println("Already made rune in hand.")
            if (currentSettings.mouseMovements) {
              findBackpackPosition(json).foreach { backpackSlotForBlank =>
                val endX = (backpackSlotForBlank \ "x").as[Int]
                val endY = (backpackSlotForBlank \ "y").as[Int]
                println(s"Moving mouse from: x:$endX, y:$endY to x:$slot6X, y:$slot6Y")
                val actions = Seq(
                  MouseAction(slot6X, slot6Y, "move"),
                  MouseAction(slot6X, slot6Y, "pressLeft"),
                  MouseAction(endX, endY, "move"),
                  MouseAction(endX, endY, "releaseLeft"),

                )

                actionStateManager ! MouseMoveCommand(actions, currentSettings.mouseMovements)
                println(s"Rune in hand - Sent MouseMoveCommand to ActionStateManager: $actions")
              }
            } else {

              findBackpackPosition(json).foreach { backPosition =>

                val backPositionJson = Json.toJson(backPosition) // This should now work

                val moveCommand = Json.obj(
                  "__command" -> "moveBlankRuneBack",
                  "backPosition" -> backPositionJson // Correctly inserts the JSON representation
                )

                sendJson(moveCommand)
                println("Command to move the item back sent to TCP server.")
              }



            }

          case None =>
            println("Nothing in hand.")
            if (!currentSettings.mouseMovements) {
              sendJson(Json.obj("__command" -> "setBlankRune"))
              println("Rune making command sent to TCP server.")
            } else {

              findBackpackSlotForBlank(json).foreach { backpackSlotForBlank =>
                val endX = (backpackSlotForBlank \ "x").as[Int]
                val endY = (backpackSlotForBlank \ "y").as[Int]
                println(s"Moving mouse from: x:$endX, y:$endY to x:$slot6X, y:$slot6Y")
                val actions = Seq(
                  MouseAction(endX, endY, "move"),
                  MouseAction(endX, endY, "pressLeft"),

                  MouseAction(slot6X, slot6Y, "move"),
                  MouseAction(slot6X, slot6Y, "releaseLeft"),

                )

                actionStateManager ! MouseMoveCommand(actions, currentSettings.mouseMovements)
                println(s"Nothing in hand - Sent MouseMoveCommand to ActionStateManager: $actions")
              }
            }

          case Some(3147) =>
            println("Blank rune in hand.")
            (json \ "characterInfo" \ "Mana").asOpt[Int] match {
              case Some(mana) if mana > 300 =>
                if (currentTime - lastSpellCastTime >= spellCastInterval) {
                  lastSpellCastTime = currentTime
                  if (!currentSettings.mouseMovements) {
                    sendJson(Json.obj("__command" -> "sayText", "text" -> "adura vita"))
                    println("Say command for 'adura gran' sent to TCP server.")
                  } else {
                    val robot = new Robot()
                    val spell = "adura vita"
                    spell.foreach { char =>
                      val keyCode = KeyEvent.getExtendedKeyCodeForChar(char.toUpper)
                      robot.keyPress(keyCode)
                      robot.keyRelease(keyCode)
                      Thread.sleep(50) // Small delay between key presses
                    }
                    // Press and release the 'Enter' key after typing the spell
                    robot.keyPress(KeyEvent.VK_ENTER)
                    robot.keyRelease(KeyEvent.VK_ENTER)
                    println("Spell 'adura vita' typed.")
                  }
                } else {
                  println("Spell cast interval not met. Waiting for the next run.")
                }
              case _ =>
                println("Mana is not higher than 300. Rune making cannot proceed.")
            }
        }
      } else {
        println("RuneMaker setting is disabled.")
      }



      // TO BE EDITED

      if (currentSettings.caveBot) {
        // Assuming findRats is related to the Cavebot functionality
        (json \ "battleInfo").asOpt[JsValue].foreach { battleInfo =>
          findRats(battleInfo, currentSettings.mouseMovements).foreach(attackOnRat)
        }
      }

      if (currentSettings.fishing) {
        activateFishing(json, currentSettings.mouseMovements)
      }
      // New logic for player detection and sound alert
      if (currentSettings.protectionZone && currentSettings.playerOnScreenAlert) {
        (json \ "battleInfo").asOpt[JsObject].foreach { battleInfo =>
          if (isPlayerDetected(battleInfo)) {
            // Trigger a beep sound as an alert
            Toolkit.getDefaultToolkit().beep()
            // Log or perform additional actions as needed
            println("Player on the screen!")
          }
        }
      }


      println("End off HandleOkStatus.")
    }
  }



  // Function to find the backpack slot for a blank rune
  def findBackpackSlotForBlank(json: JsValue): Option[JsObject] = {
    // Attempt to extract the container information
    val containerOpt = (json \ "containersInfo").asOpt[JsObject]
    println(s"Containers: $containerOpt") // Log the container information for debugging

    containerOpt.flatMap { containers =>
      // Log the attempt to iterate over containers
      println("Iterating over containers to find a blank rune slot...")
      containers.fields.collectFirst {
        case (containerName, containerInfo: JsObject) =>
          println(s"Checking container $containerName for blank runes...")
          (containerInfo \ "items").as[JsObject].fields.collectFirst {
            case (slotName, slotInfo: JsObject) if (slotInfo \ "itemId").asOpt[Int].contains(3147) =>
              println(s"Found blank rune in $containerName at slot $slotName")
              // Attempt to retrieve the screen position using the slot name
              (json \ "screenInfo" \ "inventoryPanelLoc" \ "container0" \ "contentsPanel" \ slotName).asOpt[JsObject].map { screenPos =>
                println(s"Screen position for blank rune: $screenPos")
                screenPos
              }
          }.flatten
      }.flatten
    }
  }




  def findBackpackPosition(json: JsValue): Option[JsObject] = {
    println(json \ "screenInfo" \ "inventoryPanelLoc" \ "container0" \ "contentsPanel" \ "slot1") // Check this specific part of the JSON
    (json \ "screenInfo" \ "inventoryPanelLoc").asOpt[JsObject].flatMap { inventoryPanelLoc =>
      // Assuming "slot1" is your target slot in the first container and you're interested in its screen position
      (inventoryPanelLoc \ "container0" \ "contentsPanel" \ "slot1").asOpt[JsObject].flatMap { slot1 =>
        for {
          posX <- (slot1 \ "x").asOpt[Int]
          posY <- (slot1 \ "y").asOpt[Int]
        } yield Json.obj("x" -> posX, "y" -> posY)
      }
    }
  }



  private def makeRune(json: JsValue): Unit = {
    settings.foreach { currentSettings =>
      if (currentSettings.runeMaker && System.currentTimeMillis() - lastSpellCastTime >= spellCastInterval) {
        lastSpellCastTime = System.currentTimeMillis()

        if (!currentSettings.mouseMovements) {
          // Send command to TCP server for setBlankRune if mouse movements are off
          sendJson(Json.obj("__command" -> "setBlankRune"))
        } else {
          // Placeholder for future mouse movement-based rune making
          // "//"
        }
      }
    }
  }
  // Helper function to check for players in battleInfo
  private def isPlayerDetected(battleInfo: JsObject): Boolean = {
    battleInfo.fields.exists {
      case (_, creature) => (creature \ "IsPlayer").asOpt[Boolean].getOrElse(false)
    }
  }
  private def findRats(battleInfo: JsValue, mouseMovement: Boolean): Seq[Long] = {
    battleInfo match {
      case obj: JsObject => obj.fields.flatMap {
        case (_, creature) =>
          val name = (creature \ "Name").asOpt[String]
          val id = (creature \ "Id").asOpt[Long]
          if (name.contains("Rat")) id else None
      }.toSeq
      case _ => Seq.empty
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



/*  def scheduleReconnection(): Unit = {
    val delay = Random.nextInt(10000) + 5000 // Random delay between 5 to 10 seconds
    context.system.scheduler.scheduleOnce(Duration(delay, TimeUnit.MILLISECONDS))(connectToServer())
  }*/

}

