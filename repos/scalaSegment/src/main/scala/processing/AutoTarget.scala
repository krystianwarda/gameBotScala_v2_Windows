package processing
import mouse.FakeAction
import play.api.libs.json.JsValue
import play.api.libs.json._
import processing.CaveBot.executeWhenNoMonstersOnScreen
import userUI.SettingsUtils.UISettings

object AutoTarget {
  def computeAutoTargetActions(json: JsValue, settings: UISettings, currentState: ProcessorState): ((Seq[FakeAction], Seq[Log]), ProcessorState) = {
//    println("Performing computeCaveBotActions action.")
    var actions: Seq[FakeAction] = Seq.empty
    var logs: Seq[Log] = Seq.empty
    var updatedState = currentState // Initialize updatedState
    val presentCharLocationZ = (json \ "characterInfo" \ "PositionZ").as[Int]

    if (settings.autoTargetSettings.enabled) {

      // After handling monsters, update chase mode if necessary
      val chaseModeUpdateResult = updateChaseModeIfNecessary(json, actions, logs)
      actions = chaseModeUpdateResult._1
      logs = chaseModeUpdateResult._2

      val presentCharLocationZ = (json \ "characterInfo" \ "PositionZ").as[Int]
      if ((settings.caveBotSettings.enabled && updatedState.caveBotLevelsList.contains(presentCharLocationZ)) || (!settings.caveBotSettings.enabled)) {

        (json \ "battleInfo").validate[Map[String, JsValue]] match {
          case JsSuccess(battleInfo, _) =>
            val hasMonsters = battleInfo.exists { case (_, creature) =>
              (creature \ "IsMonster").asOpt[Boolean].getOrElse(false)
            }
            if (hasMonsters) {
              val result = executeWhenMonstersOnScreen(json, settings, updatedState, actions, logs)
              actions = result._1._1
              logs = result._1._2
              updatedState = result._2
            } else {
              //            println("Skipping actions due to absence of monsters.")
            }
          case JsError(_) =>
          //          println("battleInfo is null or invalid format. Skipping processing.")
        }
      }
    }
    ((actions, logs), updatedState)
  }

  def executeWhenMonstersOnScreen(json: JsValue, settings: UISettings, initialState: ProcessorState, initialActions: Seq[FakeAction], initialLogs: Seq[Log]): ((Seq[FakeAction], Seq[Log]), ProcessorState) = {
    println("Performing executeWhenMonstersOnScreen.")
    val startTime = System.nanoTime()
    var actions = initialActions
    var logs = initialLogs
    var updatedState = initialState


    // Safely attempt to extract the attacked creature's target ID
    (json \ "attackInfo" \ "Id").asOpt[Int] match {
      case Some(attackedCreatureTarget) =>
        println(s"attackInfo is not null, target Id: $attackedCreatureTarget")
        updatedState = updatedState.copy(creatureTarget = attackedCreatureTarget)
      case None =>
        println("attackInfo is null or not present or Id is not an Int")
        updatedState = updatedState.copy(creatureTarget = 0)

        // Extract monsters, their IDs, and Names
        val monsters: Seq[(Long, String)] = (json \ "battleInfo").as[JsObject].values.flatMap { creature =>
          val isMonster = (creature \ "IsMonster").as[Boolean]
          if (isMonster) {
            Some((creature \ "Id").as[Long], (creature \ "Name").as[String])
          } else None
        }.toSeq

        // Printing the entire list of monsters with IDs and names
        monsters.foreach { case (id, name) =>
          println(s"Monster ID: $id, Name: $name")
        }

        // Filter and sort based on priority list
        val priorityList = settings.autoTargetSettings.creaturePriorityList
        var sortedMonsters = monsters
          .filter { case (_, name) => priorityList.contains(name) }
          .sortBy { case (_, name) => priorityList.indexOf(name) }

        // Filter monsters with ID >= 5
        sortedMonsters = sortedMonsters.filter { case (id, _) => id >= 5 }

        // Sort the filtered list based on Y-coordinate in ascending order
        sortedMonsters = sortedMonsters.sortBy { case (id, _) =>
          val battleCreaturePosition = (json \ "screenInfo" \ "battlePanelLoc" \ id.toString).asOpt[JsObject]
          battleCreaturePosition match {
            case Some(pos) => (pos \ "PosY").as[Int]
            case None => Int.MaxValue // If position information is not available, put it at the end
          }
        }

        // Take top 4 elements
        val topFourMonsters = sortedMonsters.take(4)

        // Sort the top 4 monsters based on priority
        val sortedTopFourMonsters = topFourMonsters.sortBy { case (_, name) => priorityList.indexOf(name) }

        sortedTopFourMonsters.headOption match {
          case Some((id, name)) =>
            println(s"Attack creature name: $name, and id: $id")
            val battleCreaturePosition = (json \ "screenInfo" \ "battlePanelLoc" \ id.toString).asOpt[JsObject]

            battleCreaturePosition match {
              case Some(pos) =>
                val battleCreaturePositionX = (pos \ "PosX").as[Int]
                val battleCreaturePositionY = (pos \ "PosY").as[Int]
                println(s"Attack creature on battle positionX: $battleCreaturePositionX, and positionY: $battleCreaturePositionY")
                val actionsSeq = Seq(
                  MouseAction(battleCreaturePositionX, battleCreaturePositionY, "move"),
                  MouseAction(battleCreaturePositionX, battleCreaturePositionY, "pressLeft"),
                  MouseAction(battleCreaturePositionX, battleCreaturePositionY, "releaseLeft")
                )
                actions = actions :+ FakeAction("useMouse", None, Some(MouseActions(actionsSeq)))
              case None =>
                println(s"No position information available for monster ID $id")
            }
          case None =>
            println("No monsters found to attack.")
        }

    }


    val endTime = System.nanoTime()
    val duration = (endTime - startTime) / 1e6d
    println(f"Processing executeWhenNoMonstersOnScreen took $duration%.3f ms")
    ((actions, logs), updatedState)
  }


  def updateChaseModeIfNecessary(json: JsValue, initialActions: Seq[FakeAction], initialLogs: Seq[Log]): (Seq[FakeAction], Seq[Log]) = {
    var actions = initialActions
    var logs = initialLogs

    val currentChaseMode = (json \ "characterInfo" \ "ChaseMode").as[Int]
    if (currentChaseMode == 0) {
      val chaseModeBoxPosition = (json \ "screenInfo" \ "inventoryPanelLoc" \ "inventoryWindow" \ "contentsPanel" \ "chaseModeBox").as[JsObject]
      val chaseModeBoxX = (chaseModeBoxPosition \ "x").as[Int]
      val chaseModeBoxY = (chaseModeBoxPosition \ "y").as[Int]

      val actionsSeq = Seq(
        MouseAction(chaseModeBoxX, chaseModeBoxY, "move"),
        MouseAction(chaseModeBoxX, chaseModeBoxY, "pressLeft"),
        MouseAction(chaseModeBoxX, chaseModeBoxY, "releaseLeft"),
      )

      logs :+= Log("Move mouse to switch to follow chase mode.")
      actions :+= FakeAction("useMouse", None, Some(MouseActions(actionsSeq)))
    }

    (actions, logs)
  }
}

