package processing

import akka.actor.{Actor, ActorRef}
import play.api.libs.json.{JsNumber, JsObject, JsValue, Json}
import player.Player
import mouse.Mouse
import main.scala.MainApp
import userUI.SettingsUtils.UISettings

import java.awt.{Robot, Toolkit}
import java.io.{DataInputStream, DataOutputStream, IOException}
import java.net.{InetAddress, Socket, SocketException}
import java.nio.{ByteBuffer, ByteOrder}
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.Duration
import scala.util.Random
// import userUI.UIAppActor
//import src.main.scala.Main.JsonData


case class cpResult(message: String, additionalData: Option[JsValue] = None)

trait CommandProcessor {
  def execute(json: JsValue, player: Player, settings: UISettings): cpResult
}

case class InitializeProcessor(player: Player, settings: UISettings)
// Create a placeholder for MouseMovementActor
case object ConnectToServer


class JsonProcessorActor(mouseMovementActor: ActorRef) extends Actor {
  // Mutable state
  var player: Option[Player] = None
  var settings: Option[UISettings] = None

  import context.dispatcher

  // Add a lastFishingCommandSent variable
  private var lastFishingCommandSent: Long = 0
  private val fishingCommandInterval: Long = 5 // 10 seconds interval
  private var reconnectAttempts = 0
  private val maxReconnectAttempts = 5 // Maximum number of reconnection attempts
  private var lastSpellCastTime: Long = 10000
  private val spellCastInterval: Long = 30000 // 5 seconds


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


  private def handleErrorStatus(json: JsValue): Unit = {
    println("Error in processing json.")
  }

  private def handleOkStatus(json: JsValue): Unit = {
    settings.foreach { currentSettings =>
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

      if (currentSettings.runeMaker) {
        val currentTime = System.currentTimeMillis()

        // Check if mana is higher than 300
        (json \ "characterInfo" \ "Mana").asOpt[Int] match {
          case Some(mana) if mana > 300 =>
            // Correctly access slot 6 data from "EqInfo"
            (json \ "EqInfo" \ "6" \ "itemId").asOpt[Int] match {
              case Some(itemId) =>
                itemId match {
                  case 3147 => // Blank rune ID
                    if (currentTime - lastSpellCastTime >= spellCastInterval) {
                      lastSpellCastTime = currentTime // Update the last spell cast time
                      val sayCommand = Json.obj("__command" -> "sayText", "text" -> "adura gran")
                      sendJson(sayCommand)
                      println("Say command for 'adori vuita vis' sent to TCP server.")
                      // Break the function after sayText command
                    } else {
                      println("Spell cast interval not met. Waiting for the next run.")
                    }

                  case _ => // Item in hand is not a blank rune
                    // Consider moving the item back
                    findBackpackPosition(json).foreach { backPosition =>
                      val moveCommand = Json.obj("__command" -> "moveBlankRuneBack", "backPosition" -> backPosition)
                      sendJson(moveCommand)
                      println("Command to move the item back sent to TCP server.")
                    }
                    return // Break the function after moveBlankRuneBack command
                }

              case None =>
                // Slot 6 is empty, put a blank rune
                sendJson(Json.obj("__command" -> "setBlankRune"))
                println("Rune making command sent to TCP server.")
                return // Break the function after setting a blank rune
            }

          case _ =>
            println("Mana is not higher than 300. Rune making cannot proceed.")
            return // Break the function if mana is not higher than 300
        }

      } else {
        println("RuneMaker setting is disabled.")
      }


      println("End off HandleOkStatus.")
    }
  }

  def findBackpackPosition(json: JsValue): Option[JsObject] = {
    (json \ "containersInfo").asOpt[JsObject].flatMap { containersInfo =>
      containersInfo.value.headOption.flatMap { case (_, containerInfo) =>
        val posX = (containerInfo \ "items" \ "slot1" \ "posX").asOpt[Int]
        val posY = (containerInfo \ "items" \ "slot1" \ "posY").asOpt[Int]
        val posZ = (containerInfo \ "items" \ "slot1" \ "posZ").asOpt[Int]
        (posX, posY, posZ) match {
          case (Some(x), Some(y), Some(z)) => Some(Json.obj("x" -> x, "y" -> y, "z" -> z))
          case _ => None
        }
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
                      val robotInstance = new Robot()

                      val arrowsLoc = (json \ "screenInfo" \ "inventoryPanelLoc" \ "arrows").asOpt[JsObject]
                      val arrowsX = arrowsLoc.flatMap(loc => (loc \ "x").asOpt[Int]).getOrElse(0)
                      val arrowsY = arrowsLoc.flatMap(loc => (loc \ "y").asOpt[Int]).getOrElse(0)

                      // Calculate target tile key
                      val targetTileScreenKey = s"${8 + deltaX}x${6 + deltaY}"
                      val targetTileScreenX = (json \ "screenInfo" \ "mapPanelLoc" \ targetTileScreenKey \ "x").asOpt[Int].getOrElse(0)
                      val targetTileScreenY = (json \ "screenInfo" \ "mapPanelLoc" \ targetTileScreenKey \ "y").asOpt[Int].getOrElse(0)

                      println(s"Target Tile Screen X: $targetTileScreenX, Y: $targetTileScreenY") // Print the target tile screen coordinates

                      Mouse.mouseMoveSmooth(robotInstance, Some((arrowsX, arrowsY)))
                      Mouse.rightClick(robotInstance, Some((arrowsX, arrowsY)))
                      Mouse.mouseMoveSmooth(robotInstance, Some((targetTileScreenX, targetTileScreenY)))
                      Mouse.leftClick(robotInstance, Some((targetTileScreenX, targetTileScreenY)))
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
          scheduleReconnection()
      }
    } else {
      println("Maximum reconnection attempts reached. Stopping reconnection attempts.")
    }
  }



  def scheduleReconnection(): Unit = {
    val delay = Random.nextInt(10000) + 5000 // Random delay between 5 to 10 seconds
    context.system.scheduler.scheduleOnce(Duration(delay, TimeUnit.MILLISECONDS))(connectToServer())
  }

}

