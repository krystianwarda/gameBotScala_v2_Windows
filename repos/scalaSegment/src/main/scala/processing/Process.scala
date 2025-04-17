package processing

import keyboard.KeyAction
import play.api.libs.json.JsObject
import mouse.{ActionCompleted, ActionTypes, FakeAction, ItemInfo, MouseUtils, MouseMoveCommand, MouseMovementSettings}
import play.api.libs.json.{JsNumber, JsObject, JsValue, Json}
import processing.CaveBot.{Vec, aStarSearch, createBooleanGrid, printGrid}

import scala.util.Random
import play.api.libs.json._

import java.time.Instant
import scala.io.Source
import java.awt.Toolkit
import com.sun.speech.freetts.Voice
import com.sun.speech.freetts.VoiceManager
import utils.ProcessorState
import utils.consoleColorPrint.{ANSI_BLUE, printInColor}

import java.awt.Robot
import java.awt.Rectangle
import java.awt.Toolkit
import java.io.File
import javax.imageio.ImageIO


object Process {

  // Function to find the screen position of an item in container slots 1-4 with both itemId and itemSubType matching
  // Function to check if JSON is empty
  def isJsonEmpty(json: JsValue): Boolean = json match {
    case JsObject(fields) if fields.isEmpty => true
    case _ => false
  }


  def captureScreen(): File = {
    val screenSize = Toolkit.getDefaultToolkit.getScreenSize
    val captureRect = new Rectangle(screenSize)
    val screenFullImage = new Robot().createScreenCapture(captureRect)
    val tempFile = File.createTempFile("screenshot_", ".png")
    ImageIO.write(screenFullImage, "png", tempFile)
    tempFile
  }

  // Function to check if JSON is not empty
  def isJsonNotEmpty(json: JsValue): Boolean = !isJsonEmpty(json)

  import com.sun.speech.freetts.VoiceManager
  System.setProperty("freetts.voices", "com.sun.speech.freetts.en.us.cmu_us_kal.KevinVoiceDirectory")



//  def generateNoise(message: String): Unit = {
//    println("generateNoise activated.")
//
//    val voiceName = "kevin" // Adjust this name based on the output of listAvailableVoices
//
//    val voiceManager = VoiceManager.getInstance()
//    val voice = voiceManager.getVoice(voiceName)
//
//    if (voice != null) {
//      voice.allocate()
//      try {
//        voice.speak(message)
//      } finally {
//        voice.deallocate()
//      }
//    } else {
//      println("Voice not found.")
//    }
//  }

  def findItemInContainerSlot14(json: JsValue, updatedState: ProcessorState, itemId: Int, itemSubType: Int): Option[JsObject] = {
    // Access the specific container information using updatedState
    val containerInfo = (json \ "containersInfo" \ updatedState.uhRuneContainerName).as[JsObject]
    println(s"Container Info: $containerInfo") // Log the container information for debugging

//    val screenInfoPath = (json \ "screenInfo" \ "inventoryPanelLoc" \ updatedState.uhRuneContainerName \ "contentsPanel").as[JsObject]
    val inventoryPanelLoc = (json \ "screenInfo" \ "inventoryPanelLoc").as[JsObject]
    val containerKey = inventoryPanelLoc.keys.find(_.contains(updatedState.uhRuneContainerName)).getOrElse("")
    val containerScreenInfo = (inventoryPanelLoc \ containerKey \ "contentsPanel").as[JsObject]


    // Iterate over slots 1 to 4 to find the item
    val itemInContainer = (0 until 4).flatMap { slotIndex =>
      (containerInfo \ "items" \ s"slot$slotIndex").asOpt[JsObject].flatMap { slotValue =>
        for {
          id <- (slotValue \ "itemId").asOpt[Int]
          subType <- (slotValue \ "itemSubType").asOpt[Int] if id == itemId && subType == itemSubType
        } yield {
          println(s"Found in container slot $slotIndex: $slotValue") // Log for debugging
          slotValue
        }
      }
    }.headOption

    println(s"Item in Container: $itemInContainer") // Debugging log

    // If the item exists in containerInfo, then look for its screen position in screenInfo
    val result = itemInContainer.flatMap { _ =>
      // Direct mapping of slot index to screen position based on the assumption slot indexes directly correlate
      (0 until 4).flatMap { slotIndex =>
        containerScreenInfo.fields.collectFirst {
          case (itemName, itemPos) if itemName.endsWith(s"item$slotIndex") =>
            Json.obj("x" -> (itemPos \ "x").as[Int], "y" -> (itemPos \ "y").as[Int])
        }
      }.headOption
    }

    println(s"Result: $result") // Debugging log
    result
  }
  def detectPlayersAndMonsters(json: JsValue): Boolean = {
    val currentCharName = (json \ "characterInfo" \ "Name").as[String]
    val spyLevelInfo = (json \ "spyLevelInfo").as[JsObject]

    spyLevelInfo.values.exists { entity =>
      val isPlayer = (entity \ "IsPlayer").as[Boolean]
      val isMonster = (entity \ "IsMonster").as[Boolean]
      val name = (entity \ "Name").as[String]

      (isPlayer || isMonster) && name != currentCharName
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
              (json \ "screenInfo" \ "inventoryPanelLoc" \ containerName \ "contentsPanel" \ slotName).asOpt[JsObject].map { screenPos =>
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

  // Helper function to check for players in battleInfo
  def isPlayerDetected(battleInfo: JsObject): Boolean = {
    battleInfo.fields.exists {
      case (_, creature) => (creature \ "IsPlayer").asOpt[Boolean].getOrElse(false)
    }
  }

  def findRats(battleInfo: JsValue, mouseMovement: Boolean): Seq[Long] = {
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

  // Adjusted function to use the x and y values of Vec as min and max
  def generateRandomDelay(timeRange: (Int, Int)): Long = {
    val rand = new Random
    val (minDelay, maxDelay) = timeRange
    minDelay + (rand.nextLong() % (maxDelay - minDelay + 1))
  }


  // Function to safely extract posX and posY if the 'Ok' button exists
  def extractOkButtonPosition(json: JsValue): Option[(Int, Int)] = {
    (json \ "screenInfo" \ "extraWindowLoc").validate[JsObject] match {
      case JsSuccess(extraWindowLoc, _) =>
        (extraWindowLoc \ "Ok").validate[JsObject] match {
          case JsSuccess(okButton, _) =>
            for {
              posX <- (okButton \ "posX").validate[Int].asOpt
              posY <- (okButton \ "posY").validate[Int].asOpt
            } yield (posX, posY)
          case _ => None // No 'Ok' button or invalid format
        }
      case _ => None // No 'extraWindowLoc' or it's not an object
    }
  }

  def handleRetryStatus(currentRetryStatus: Int, retryAttemptsMid: Int): Int = {
    if (currentRetryStatus >= retryAttemptsMid) {
      println(s"Resetting retry status.")
      0  // Reset only if the current status reaches or exceeds the maximum allowed attempts
    } else {
      currentRetryStatus + 1  // Increment the retry status if it hasn't reached the maximum yet
    }
  }

  def timeToRetry(lastAttemptTime: Long, retryMidDelay: Long): Boolean = {
    val currentTime = System.currentTimeMillis()
    (currentTime - lastAttemptTime) >= retryMidDelay
  }

  def updateRetryStatusBasedOnTime(lastAttemptTime: Long, retryMidDelay: Long): Boolean = {
    val currentTime = Instant.now.toEpochMilli
    val timeSinceLastAttempt = currentTime - lastAttemptTime
    timeSinceLastAttempt >= retryMidDelay
  }


  // Function to load spells from a file
  def loadSpellsFromFile(filePath: String): List[String] = {
    Source.fromFile(filePath).getLines.toList
  }

  def loadTextFromFile(filePath: String): String = {
    try {
      Source.fromFile(filePath).getLines.mkString("\n")
    } catch {
      case e: Exception =>
        println(s"Failed to read $filePath: ${e.getMessage}")
        ""
    }
  }
  def performMouseActionSequance(actionsSeq: Seq[MouseAction]): Seq[FakeAction] = {
    Seq(FakeAction("useMouse", None, Some(MouseActions(actionsSeq))))
  }

  def addMouseAction(command: String, itemData: Option[ItemInfo], updatedState: ProcessorState, actionsSeq: Seq[MouseAction]): FakeAction = {
    val modifiedSeq = if (updatedState.healingCrosshairActive) {
      println("Have to unclick crosshair.")
      MouseAction(0, 0, "pressRight") +: actionsSeq
    } else {
      actionsSeq
    }
    FakeAction(command, itemData, Some(MouseActions(modifiedSeq)))
  }


  def generateSubwaypointsToGamePosition(currentWaypointLocation: Vec, initialState: ProcessorState, json: JsValue): ProcessorState = {
    println("[DEBUG] Generating subwaypoints for current waypoint")
    var updatedState = initialState
    // Parse tiles to determine the grid bounds and create a boolean grid
    val tiles = (json \ "areaInfo" \ "tiles").as[Map[String, JsObject]]
    val xs = tiles.keys.map(_.substring(0, 5).trim.toInt)
    val ys = tiles.keys.map(_.substring(5, 10).trim.toInt)
    val gridBounds = (xs.min, ys.min, xs.max, ys.max)
    println(s"[DEBUG] GridBounds: $gridBounds")

    val (grid, (min_x, min_y)) = createBooleanGrid(tiles, xs.min, ys.min)

    // Determine current waypoint location
    println(s"[DEBUG] Current Waypoint: $currentWaypointLocation")

    // Determine character's current location and perform A* search
    val presentCharLocation = updatedState.presentCharLocation
    println(s"[DEBUG] Character location: $presentCharLocation")
    var newPath: List[Vec] = List()

    if (presentCharLocation != currentWaypointLocation) {
      // Make sure to include min_x and min_y when calling aStarSearch
      newPath = aStarSearch(presentCharLocation, currentWaypointLocation, grid, min_x, min_y)
      printInColor(ANSI_BLUE, f"[WAYPOINTS] Path: $newPath.")
    } else {
      println("[DEBUG] Current location matches the current waypoint, moving to the next waypoint.")
    }

    // Remove the presentCharLocation from the newPath if it exists
    val filteredPath = newPath.filterNot(loc => loc == presentCharLocation)

    println(s"[DEBUG] Path: ${filteredPath.mkString(" -> ")}")
    println(s"[DEBUG] Char loc: $presentCharLocation")
    println(s"[DEBUG] Waypoint loc: $currentWaypointLocation")


    if (presentCharLocation != currentWaypointLocation) {
      printGrid(grid, gridBounds, filteredPath, updatedState.presentCharLocation, currentWaypointLocation)
      // Locations are different, update state accordingly
      updatedState.copy(
        subWaypoints = filteredPath,
        gridBoundsState = gridBounds,
        gridState = grid,
        currentWaypointLocation = currentWaypointLocation,
        presentCharLocation = presentCharLocation
      )
    } else {
      println(s"[DEBUG] presentCharLocation &  Char loc are the same.")
      updatedState
    }
  }

}
