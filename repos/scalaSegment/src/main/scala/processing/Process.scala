package processing

import play.api.libs.json.JsObject
import mouse.{ActionCompleted, ActionTypes, Mouse}
import mouse.{MouseMoveCommand, MouseMovementSettings}
import play.api.libs.json.{JsNumber, JsObject, JsValue, Json}


object Process {
  // Function to find the screen position of an item in container slots 1-4 with both itemId and itemSubType matching
  def findItemInContainerSlot14(json: JsValue, itemId: Int, itemSubType: Int): Option[JsObject] = {
    val containersInfo = (json \ "containersInfo").as[JsObject]
    val screenInfo = (json \ "screenInfo" \ "inventoryPanelLoc").as[JsObject]

    // Iterate through each container in containersInfo
    containersInfo.fields.flatMap { case (containerKey, containerValue) =>
      (containerValue \ "items").as[JsObject].fields.flatMap { case (slotKey, slotValue) =>
        // Extract both itemId and itemSubType and check if they match the specified values
        for {
          id <- (slotValue \ "itemId").asOpt[Int]
          subType <- (slotValue \ "itemSubType").asOpt[Int]
          if id == itemId && subType == itemSubType && slotKey.matches("slot[1-4]")
          screenPosition <- (screenInfo \ containerKey \ "contentsPanel" \ slotKey).asOpt[JsObject]
          posX <- (screenPosition \ "x").asOpt[Int]
          posY <- (screenPosition \ "y").asOpt[Int]
        } yield Json.obj("x" -> posX, "y" -> posY)
      }
    }.headOption // Return the first match found
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
