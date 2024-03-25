package processing

import play.api.libs.json.JsObject
import mouse.{ActionCompleted, ActionTypes, Mouse}
import mouse.{MouseMoveCommand, MouseMovementSettings}
import play.api.libs.json.{JsNumber, JsObject, JsValue, Json}


object Process {
  // Function to find the screen position of an item in container slots 1-4 with both itemId and itemSubType matching
  // Function to check if JSON is empty
  def isJsonEmpty(json: JsValue): Boolean = json match {
    case JsObject(fields) if fields.isEmpty => true
    case _ => false
  }

  // Function to check if JSON is not empty
  def isJsonNotEmpty(json: JsValue): Boolean = !isJsonEmpty(json)



  def findItemInContainerSlot14(json: JsValue, updatedState: ProcessorState, itemId: Int, itemSubType: Int): Option[JsObject] = {
    // Access the specific container information using updatedState
    val containerInfo = (json \ "containersInfo" \ updatedState.uhRuneContainerName).as[JsObject]
    println(s"Container Info: $containerInfo") // Log the container information for debugging

    val screenInfoPath = (json \ "screenInfo" \ "inventoryPanelLoc" \ updatedState.uhRuneContainerName \ "contentsPanel").as[JsObject]
    println(s"Screen Info Path: $screenInfoPath") // Log the screen info path for debugging

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
        screenInfoPath.fields.collectFirst {
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
}
