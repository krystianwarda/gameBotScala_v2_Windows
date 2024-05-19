package processing
import mouse.FakeAction
import play.api.libs.json.Format.GenericFormat
import play.api.libs.json.JsValue
import play.api.libs.json.OFormat.oFormatFromReadsAndOWrites
import play.api.libs.json._
import processing.CaveBot.executeWhenNoMonstersOnScreen
import userUI.SettingsUtils.UISettings
import utils.consoleColorPrint.{ANSI_GREEN, ANSI_RED, printInColor}
import utils.consoleColorPrint._

object AutoTarget {
  def computeAutoTargetActions(json: JsValue, settings: UISettings, currentState: ProcessorState): ((Seq[FakeAction], Seq[Log]), ProcessorState) = {
//    println("Performing computeCaveBotActions action.")
    val startTime = System.nanoTime()

    var actions: Seq[FakeAction] = Seq.empty
    var logs: Seq[Log] = Seq.empty
    var updatedState = currentState // Initialize updatedState
    val presentCharLocationZ = (json \ "characterInfo" \ "PositionZ").as[Int]

//    printInColor(ANSI_RED, f"[DEBUG] computeAutoTargetActions process started with status:${updatedState.stateHunting}")

    if (settings.autoTargetSettings.enabled && updatedState.stateHunting == "free") {

      // After handling monsters, update chase mode if necessary
      val (newUpdatedState, newActions, newLogs) = updateChaseModeIfNecessary(json, updatedState, actions, logs)
      updatedState = newUpdatedState // update the state
      actions = newActions // update actions
      logs = newLogs // update logs

      val presentCharLocationZ = (json \ "characterInfo" \ "PositionZ").as[Int]
      if ((settings.caveBotSettings.enabled && updatedState.caveBotLevelsList.contains(presentCharLocationZ)) || !(settings.caveBotSettings.enabled)) {
        println(s"AutoTarget is ON, status ${updatedState.stateHunting}")
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
    } else if (settings.autoTargetSettings.enabled && updatedState.stateHunting == "attacking") {
      // Safely attempt to extract the attacked creature's target ID
      println(s"AutoTarget is ON, (ELSE) status ${updatedState.stateHunting}")
      // After handling monsters, update chase mode if necessary

      val (newUpdatedState, newActions, newLogs) = updateChaseModeIfNecessary(json, updatedState, actions, logs)
      updatedState = newUpdatedState // update the state
      actions = newActions // update actions
      logs = newLogs // update logs


      (json \ "attackInfo" \ "Id").asOpt[Int] match {
        case Some(attackedCreatureTarget) =>
//          println(s"Targeting creature id: $attackedCreatureTarget")
          val tempTargetFreezeHealthPoints = (json \ "attackInfo" \ "HealthPercent").as[Int]

          if (updatedState.targetFreezeHealthStatus >= updatedState.retryAttemptsLong && updatedState.targetFreezeHealthPoints == tempTargetFreezeHealthPoints) {
            printInColor(ANSI_BLUE, f"[DEBUG] Changing target due to health freeze")

            val battleCreaturePosition = (json \ "screenInfo" \ "battlePanelLoc" \ attackedCreatureTarget.toString).asOpt[JsObject]
            battleCreaturePosition match {
              case Some(pos) =>
                val battleCreaturePositionX = (pos \ "PosX").asOpt[Int].getOrElse(0)
                val battleCreaturePositionY = (pos \ "PosY").asOpt[Int].getOrElse(0)

                if (updatedState.retryStatus >= updatedState.retryAttempts) {
                  printInColor(ANSI_RED, f"[DEBUG] Unselecting creature id: $attackedCreatureTarget ")

                  // Perform the mouse actions
                  val actionsSeq = Seq(
                    MouseAction(battleCreaturePositionX, battleCreaturePositionY, "move"),
                    MouseAction(battleCreaturePositionX, battleCreaturePositionY, "pressLeft"),
                    MouseAction(battleCreaturePositionX, battleCreaturePositionY, "releaseLeft")
                  )
                  actions = actions :+ FakeAction("useMouse", None, Some(MouseActions(actionsSeq)))

                  // Reset the state and retryStatus
                  updatedState = updatedState.copy(stateHunting = "free", retryStatus = 0, targetFreezeCreatureId=attackedCreatureTarget)
                } else {
                  printInColor(ANSI_RED, f"[DEBUG] Retrying... (Attempt ${updatedState.retryStatus + 1})")
                  updatedState = updatedState.copy(retryStatus = updatedState.retryStatus + 1)
                }

              case None =>
                println(s"No position information available for monster ID $attackedCreatureTarget")
            }


          } else if (updatedState.targetFreezeHealthPoints == tempTargetFreezeHealthPoints) {
            printInColor(ANSI_BLUE, f"[DEBUG] Target is freezed. (Attempt ${updatedState.targetFreezeHealthStatus + 1})")
            updatedState = updatedState.copy(targetFreezeHealthStatus = updatedState.targetFreezeHealthStatus + 1)
          }

          val targetName = (json \ "attackInfo" \ "Name").asOpt[String].getOrElse("Unknown")
          printInColor(ANSI_RED, f"[DEBUG] computeAutoTargetActions process started. Status:${updatedState.stateHunting}, attacking: $targetName")
          val xPos = (json \ "attackInfo" \ "Position" \ "x").asOpt[Int].getOrElse(0)
          val yPos = (json \ "attackInfo" \ "Position" \ "y").asOpt[Int].getOrElse(0)
          val zPos = (json \ "attackInfo" \ "Position" \ "z").asOpt[Int].getOrElse(0)

//          printInColor(ANSI_RED, f"[DEBUG] Updating position of attacked creature $targetName in game position $xPos, $yPos, $zPos")

          updatedState = updatedState.copy(lastTargetName = targetName)
          updatedState = updatedState.copy(targetFreezeHealthPoints = tempTargetFreezeHealthPoints)
          updatedState = updatedState.copy(lastTargetPos = (xPos, yPos, zPos))
          updatedState = updatedState.copy(creatureTarget = attackedCreatureTarget)

        case None =>
          println(s"Attack Info is empty. Switching to free")
          updatedState = updatedState.copy(stateHunting = "free")
      }
    }

    val endTime = System.nanoTime()
    val duration = (endTime - startTime) / 1e9d
    printInColor(ANSI_GREEN, f"[INFO] Processing computeAutoTargetActions took $duration%.6f seconds")
    ((actions, logs), updatedState)
  }

  def executeWhenMonstersOnScreen(json: JsValue, settings: UISettings, initialState: ProcessorState, initialActions: Seq[FakeAction], initialLogs: Seq[Log]): ((Seq[FakeAction], Seq[Log]), ProcessorState) = {
//    println("Performing executeWhenMonstersOnScreen.")
    val startTime = System.nanoTime()
    var actions = initialActions
    var logs = initialLogs
    var updatedState = initialState


    // Safely attempt to extract the attacked creature's target ID
    (json \ "attackInfo" \ "Id").asOpt[Int] match {
      case Some(attackedCreatureTarget) =>
        updatedState = updatedState.copy(stateHunting = "attacking")
        println(s"attackInfo is not null, target Id: $attackedCreatureTarget")
        val targetName = (json \ "attackInfo" \ "Name").asOpt[String].getOrElse("Unknown")

        val xPos = (json \ "attackInfo" \ "Position" \ "x").asOpt[Int].getOrElse(0)
        val yPos = (json \ "attackInfo" \ "Position" \ "y").asOpt[Int].getOrElse(0)
        val zPos = (json \ "attackInfo" \ "Position" \ "z").asOpt[Int].getOrElse(0)

        println(s"Creature $targetName in game position $xPos, $yPos, $zPos")

        updatedState = updatedState.copy(lastTargetName = targetName)
        updatedState = updatedState.copy(lastTargetPos = (xPos, yPos, zPos))
        updatedState = updatedState.copy(creatureTarget = attackedCreatureTarget)


      case None =>

        if (updatedState.stateHunting == "free") {

          updatedState = updatedState.copy(creatureTarget = 0)
          updatedState = updatedState.copy(alreadyLootedIds = List(), lootingStatus=0,extraWidowLootStatus=0, lootingRestryStatus=0, targetFreezeHealthPoints=0)

          // Extract monsters, their IDs, and Names
          val monsters: Seq[(Long, String)] = (json \ "battleInfo").as[JsObject].values.flatMap { creature =>
            val isMonster = (creature \ "IsMonster").as[Boolean]
            if (isMonster) {
              Some((creature \ "Id").as[Long], (creature \ "Name").as[String])
            } else None
          }.toSeq

          // Use the existing creatureList from settings
          val jsonResult = transformToJSON(settings.autoTargetSettings.creatureList)
          //          println(Json.prettyPrint(Json.toJson(jsonResult)))

          // Now use Reads from the Creature object
          val creatureDangerMap = jsonResult.map { json =>
            val creature = Json.parse(json.toString()).as[Creature](Creature.reads)
            (creature.name, creature.danger)
          }.toMap

          // create a list of monsters to be looted
          if (updatedState.monstersListToLoot.isEmpty) {
            // Parse JSON to List of Creature objects and filter to get names of creatures with loot
            val monstersWithLoot = jsonResult.flatMap { json =>
              Json.parse(json.toString()).validate[Creature] match {
                case JsSuccess(creature, _) if creature.loot => Some(creature.name)
                case _ => None
              }
            }
            // Assuming updatedState is being updated within a case class or similar context
            updatedState = updatedState.copy(monstersListToLoot = monstersWithLoot)
          }


          // Filter and sort based on the danger level, sorting by descending danger
          var sortedMonsters = monsters
            .filter { case (_, name) => creatureDangerMap.contains(name) }
            .sortBy { case (_, name) => -creatureDangerMap(name) } // Descending order of danger

          // Filter monsters with ID >= 5
          sortedMonsters = sortedMonsters.filter { case (id, _) => id >= 5 }


          // Sort based on danger and position
          sortedMonsters = sortedMonsters.sortBy { case (_, name) => -creatureDangerMap(name) }

          // Further filter and sort
          sortedMonsters = sortedMonsters
            .filter { case (id, _) => id >= 5 }
            .sortBy { case (id, _) =>
              val battleCreaturePosition = (json \ "screenInfo" \ "battlePanelLoc" \ id.toString).asOpt[JsObject]
              battleCreaturePosition.flatMap(pos => (pos \ "PosY").asOpt[Int]).getOrElse(Int.MaxValue)
            }

          var topFourMonsters = sortedMonsters.take(4)
            .sortBy { case (_, name) => -creatureDangerMap(name) }

          printInColor(ANSI_BLUE, f"[DEBUG] Before reshuffling monster list: ${updatedState.targetFreezeHealthStatus} / ${updatedState.retryAttemptsLong}")

          if (updatedState.targetFreezeHealthStatus >= updatedState.retryAttemptsLong) {
            println(s"[DEBUG] Reshuffling monster list: $topFourMonsters")

            // Remove monster with targetFreezeCreatureId from the list
            val filteredMonsters = topFourMonsters.filter { case (id, _) => id != updatedState.targetFreezeCreatureId }

            // Shuffle the filtered list
            val shuffledMonsters = scala.util.Random.shuffle(filteredMonsters)
              .sortBy { case (_, name) => -creatureDangerMap(name) }

            println(s"[DEBUG] Reshuffling monster list: $shuffledMonsters")

            // Update topFourMonsters with the shuffled list
            topFourMonsters = shuffledMonsters

          }


          // Process the highest priority target
          topFourMonsters.headOption match {
            case Some((id, name)) =>

              val battleCreaturePosition = (json \ "screenInfo" \ "battlePanelLoc" \ id.toString).asOpt[JsObject]
              battleCreaturePosition match {
                case Some(pos) =>
                  val battleCreaturePositionX = (pos \ "PosX").asOpt[Int].getOrElse(0)
                  val battleCreaturePositionY = (pos \ "PosY").asOpt[Int].getOrElse(0)

                  if (updatedState.retryStatus >= updatedState.retryAttempts) {
                    printInColor(ANSI_RED, f"[DEBUG] Attack creature name: $name, and id: $id ")
                    updatedState = updatedState.copy(lastTargetName = name)

                    // Perform the mouse actions
                    val actionsSeq = Seq(
                      MouseAction(battleCreaturePositionX, battleCreaturePositionY, "move"),
                      MouseAction(battleCreaturePositionX, battleCreaturePositionY, "pressLeft"),
                      MouseAction(battleCreaturePositionX, battleCreaturePositionY, "releaseLeft")
                    )
                    actions = actions :+ FakeAction("useMouse", None, Some(MouseActions(actionsSeq)))

                    // Reset the state and retryStatus
                    updatedState = updatedState.copy(retryStatus = 0,targetFreezeHealthStatus = 0, targetFreezeCreatureId=0)

                  } else {
                    printInColor(ANSI_RED, f"[DEBUG] Retrying... (Attempt ${updatedState.retryStatus + 1})")
                    updatedState = updatedState.copy(retryStatus = updatedState.retryStatus + 1)
                  }

                case None =>
                  println(s"No position information available for monster ID $id")
              }
            case None =>
              println("No monsters found to attack.")
          }
        } else {
          println(s"Autotarget condition is met, but state is not ready: ${updatedState.stateHunting}")
        }
    }



//        // Filter and sort based on the danger level, sorting by descending danger
//        var sortedMonsters = monsters
//          .filter { case (_, name) => creatureDangerMap.contains(name) }
//          .sortBy { case (_, name) => -creatureDangerMap(name) } // Note the negative sign for descending order
//
//        // Filter monsters with ID >= 5
//        sortedMonsters = sortedMonsters.filter { case (id, _) => id >= 5 }
//
//        // Sort the filtered list based on Y-coordinate in ascending order
//        sortedMonsters = sortedMonsters.sortBy { case (id, _) =>
//          val battleCreaturePosition = (json \ "screenInfo" \ "battlePanelLoc" \ id.toString).asOpt[JsObject]
//          battleCreaturePosition match {
//            case Some(pos) => (pos \ "PosY").as[Int]
//            case None => Int.MaxValue // If position information is not available, put it at the end
//          }
//        }
//
//        // Take top 4 elements
//        val topFourMonsters = sortedMonsters.take(4)
//
//        // Re-sort the top 4 monsters based on danger level, again in descending order
//        val sortedTopFourMonsters = topFourMonsters.sortBy { case (_, name) => -creatureDangerMap(name) }
//
//        // Process the highest priority target
//        sortedTopFourMonsters.headOption match {
//          case Some((id, name)) =>
//            println(s"Attack creature name: $name, and id: $id")
//            val battleCreaturePosition = (json \ "screenInfo" \ "battlePanelLoc" \ id.toString).asOpt[JsObject]
//
//            battleCreaturePosition match {
//              case Some(pos) =>
//                val battleCreaturePositionX = (pos \ "PosX").as[Int]
//                val battleCreaturePositionY = (pos \ "PosY").as[Int]
//                println(s"Attack creature on battle positionX: $battleCreaturePositionX, and positionY: $battleCreaturePositionY")
//                val actionsSeq = Seq(
//                  MouseAction(battleCreaturePositionX, battleCreaturePositionY, "move"),
//                  MouseAction(battleCreaturePositionX, battleCreaturePositionY, "pressLeft"),
//                  MouseAction(battleCreaturePositionX, battleCreaturePositionY, "releaseLeft")
//                )
//                actions = actions :+ FakeAction("useMouse", None, Some(MouseActions(actionsSeq)))
//              case None =>
//                println(s"No position information available for monster ID $id")
//            }
//          case None =>
//            println("No monsters found to attack.")
//        }
//    }

    val endTime = System.nanoTime()
    val duration = (endTime - startTime) / 1e9d
//    println(f"[INFO] Processing executeWhenNoMonstersOnScreen took $duration%.6f seconds")
    ((actions, logs), updatedState)
  }


  def updateChaseModeIfNecessary(
                                  json: JsValue,
                                  initialState: ProcessorState,
                                  initialActions: Seq[FakeAction],
                                  initialLogs: Seq[Log]
                                ): (ProcessorState, Seq[FakeAction], Seq[Log]) = {
    var actions = initialActions
    var logs = initialLogs
    var updatedState = initialState
    val currentChaseMode = (json \ "characterInfo" \ "ChaseMode").as[Int]
    if (!(currentChaseMode == 1)) {
      if (updatedState.chaseSwitchStatus >= updatedState.retryAttempts) {
        printInColor(ANSI_RED, "[DEBUG] Changing the chase mode.")
        updatedState = updatedState.copy(chaseSwitchStatus = 0)

        if (currentChaseMode == 0) {
          val chaseModeBoxPosition = (json \ "screenInfo" \ "inventoryPanelLoc" \ "inventoryWindow" \ "contentsPanel" \ "chaseModeBox").as[JsObject]
          val chaseModeBoxX = (chaseModeBoxPosition \ "x").as[Int]
          val chaseModeBoxY = (chaseModeBoxPosition \ "y").as[Int]

          val actionsSeq = Seq(
            MouseAction(chaseModeBoxX, chaseModeBoxY, "move"),
            MouseAction(chaseModeBoxX, chaseModeBoxY, "pressLeft"),
            MouseAction(chaseModeBoxX, chaseModeBoxY, "releaseLeft")
          )

          logs :+= Log("Move mouse to switch to follow chase mode.")
          actions :+= FakeAction("useMouse", None, Some(MouseActions(actionsSeq)))
        }
      } else {
        printInColor(ANSI_RED, "[DEBUG] Looping until changing chase mode!")
        updatedState = updatedState.copy(chaseSwitchStatus = updatedState.chaseSwitchStatus + 1)
      }
    }

    (updatedState, actions, logs)
  }


  def updateChaseModeIfNecessaryOld(json: JsValue, initialState: ProcessorState, initialActions: Seq[FakeAction], initialLogs: Seq[Log]): (Seq[FakeAction], Seq[Log]) = {
    var actions = initialActions
    var logs = initialLogs
    var updatedState = initialState

    if (updatedState.chaseSwitchStatus >= updatedState.retryAttempts) {
      printInColor(ANSI_BLUE, f"[WRONG FLOOR] Making a slow move.")
//      updatedState = updatedState.copy(chaseSwitchStatus = 0)
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
    } else {
      printInColor(ANSI_BLUE, f"[WRONG FLOOR] Move hold - force slow walk")
      updatedState = updatedState.copy(chaseSwitchStatus = updatedState.chaseSwitchStatus + 1)
    }

    (actions, logs)
  }


  // Define the Creature case class along with its companion object
  case class Creature(
                       name: String,
                       count: Int,
                       hpFrom: Int,
                       hpTo: Int,
                       danger: Int,
                       targetBattle: Boolean,
                       loot: Boolean,
                     )

  object Creature {
    implicit val writes: Writes[Creature] = Json.writes[Creature]
    implicit val reads: Reads[Creature] = Json.reads[Creature]
  }

  // Parsing function
  def parseCreature(description: String): Creature = {
    val parts = description.split(", ")
    val name = parts(0)
    val count = parts(1).split(": ")(1).toInt
    val hpRange = parts(2).split(": ")(1).split("-").map(_.toInt)
    val danger = parts(3).split(": ")(1).toInt
    val targetBattle = parts(4).split(": ")(1).equalsIgnoreCase("yes")
    val loot = parts(5).split(": ")(1).equalsIgnoreCase("yes")
    Creature(name, count, hpRange(0), hpRange(1), danger, targetBattle, loot)
  }

  // Transform to JSON function
  def transformToJSON(creaturesData: Seq[String]): List[JsValue] = {
    creaturesData.map(description => Json.toJson(parseCreature(description))).toList
  }


}

