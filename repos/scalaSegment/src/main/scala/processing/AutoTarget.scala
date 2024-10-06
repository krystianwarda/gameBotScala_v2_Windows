package processing
import mouse.FakeAction
import play.api.libs.json.Format.GenericFormat
import play.api.libs.json.JsValue
import play.api.libs.json.OFormat.oFormatFromReadsAndOWrites
import play.api.libs.json._
import processing.AutoHeal.{noRunesInBpGoUp, openNewBackpack, removeEmptyBackpack}
import processing.CaveBot.{Vec, aStarSearch, calculateDirection, createBooleanGrid, executeWhenNoMonstersOnScreen, printGrid}
import processing.Process.{extractOkButtonPosition, generateRandomDelay, performMouseActionSequance, timeToRetry}
import processing.TeamHunt.followTeamMember
import userUI.SettingsUtils.UISettings
import utils.consoleColorPrint.{ANSI_GREEN, ANSI_RED, printInColor}
import utils.consoleColorPrint._

import java.lang.System.currentTimeMillis
import scala.collection.immutable.Seq
import scala.util.{Random, Try}
import scala.util.matching.Regex

case class CreatureInfo(
                         id: Int,
                         name: String,
                         healthPercent: Int,
                         isShootable: Boolean,
                         isMonster: Boolean,
                         danger: Int,
                         keepDistance: Boolean,
                         isPlayer: Boolean,
                         posX: Int,
                         posY: Int,
                         posZ: Int,
                         lootMonsterImmediately: Boolean,   // new field
                         lootMonsterAfterFight: Boolean,    // new field
                         lureCreatureToTeam: Boolean        // new field
                       )

// energy ring 3051
// life ring 3052
// roh 3098

object AutoTarget {

  def computeAutoTargetActions(json: JsValue, settings: UISettings, currentState: ProcessorState): ((Seq[FakeAction], Seq[Log]), ProcessorState) = {
//    println("Performing computeAutoTargetActions action.")

    val startTime = System.nanoTime()

    var actions: Seq[FakeAction] = Seq.empty
    var logs: Seq[Log] = Seq.empty
    var updatedState = currentState // Initialize updatedState
    val presentCharLocationZ = (json \ "characterInfo" \ "PositionZ").as[Int]
    val currentTime = System.currentTimeMillis()
    printInColor(ANSI_RED, f"[DEBUG] computeAutoTargetActions process started with status:${updatedState.cavebot.stateHunting}")
//    println(s"Begin suppliesLeftMap: ${updatedState.suppliesLeftMap}")
//    println(s"teamMembersList: ${settings.teamHuntSettings.teamMembersList}")

    println(s"lastTargetName in autotarget: ${updatedState.lastTargetName}")
    val attackInfo = (json \ "attackInfo").as[JsObject]
    println(s"attackInfo in autotarget: ${attackInfo}")
    // Assuming the JSON is already parsed and you have the structure similar to:


    // Safely attempt to extract the attacked creature's target ID
    (json \ "attackInfo" \ "Id").asOpt[Int] match {
      case Some(attackedCreatureTarget) =>
        updatedState = updatedState.copy(
          cavebot = updatedState.cavebot.copy(
            stateHunting = "attacking"
          )
        )
        println(s"attackInfo is not null, target Id: $attackedCreatureTarget")
        val targetName = (json \ "attackInfo" \ "Name").asOpt[String].getOrElse("Unknown")

        val xPos = (json \ "attackInfo" \ "Position" \ "x").asOpt[Int].getOrElse(0)
        val yPos = (json \ "attackInfo" \ "Position" \ "y").asOpt[Int].getOrElse(0)
        val zPos = (json \ "attackInfo" \ "Position" \ "z").asOpt[Int].getOrElse(0)

        println(s"Creature $targetName ($attackedCreatureTarget) in game position $xPos, $yPos, $zPos")


        updatedState = updatedState.copy(
          lastTargetName = targetName,
          lastTargetPos = (xPos, yPos, zPos),
          creatureTarget = attackedCreatureTarget
        )
        println(s"lastTargetName set to ${updatedState.lastTargetName}")
      case None => // nothing
    }

    if (settings.autoTargetSettings.enabled && updatedState.cavebot.stateHunting == "free" && !updatedState.gmDetected) {



      // Extracting and using the position of the "Ok" button
      extractOkButtonPosition(json) match {
        case Some((posX, posY)) =>
          // Now use this stored current time for all time checks
          val timeExtraWindowLoot = updatedState.currentTime - updatedState.lastExtraWindowLoot
          if (timeExtraWindowLoot >= updatedState.longTimeLimit) {
            val actionsSeq = Seq(
              MouseAction(posX, posY, "move"),
              MouseAction(posX, posY, "pressLeft"),
              MouseAction(posX, posY, "releaseLeft")
            )
            printInColor(ANSI_RED, "[DEBUG] Clicking OK button to close the window.")
            actions = actions :+ FakeAction("useMouse", None, Some(MouseActions(actionsSeq)))

            // Update the extraWindowLootStatus with the current time to mark this execution
            updatedState = updatedState.copy(lastExtraWindowLoot = updatedState.currentTime)
          } else {
            printInColor(ANSI_RED, f"[DEBUG] Not enough time has passed since the last execution: ${updatedState.lastExtraWindowLoot}ms ago.")
          }

        case None => // Do nothing if the "Ok" button is not found
      }

      println(s"updatedState.isUsingAmmo: ${updatedState.isUsingAmmo}")
      // resupply ammo
      updatedState.isUsingAmmo match {
        case "not_set" =>
          val ammoIdsList = List(3446, 3447)
          val slot10Info = (json \ "EqInfo" \ "10").as[JsObject]
          val ammoIdOpt = (slot10Info \ "itemId").asOpt[Int]
          println(s"ammoIdOpt: ${ammoIdOpt}")
          ammoIdOpt match {
            case Some(ammoId) if ammoIdsList.contains(ammoId) =>
              val randomCountResuply = Random.nextInt(41) + 40 // Random number between 40 and 80
              println(s"randomCountResuply: ${randomCountResuply}")
              updatedState = updatedState.copy(
                isUsingAmmo = "true",
                ammoId = ammoId,
                ammoCountForNextResupply = randomCountResuply
              )
            case _ =>
              println("Nothing in arrow slot...")
              updatedState = updatedState.copy(isUsingAmmo = "false")
          }

        case "false" =>
        // Do nothing and execute further code

        case "true" =>
          // Finding ammoId in bps slots
          val containerInfo = (json \ "containersInfo").as[JsObject]

          // Count total stacks of the current ammoId in the containers, with added checks
          val stacksCount = containerInfo.fields.view.flatMap {
            case (_, containerData) =>
              (containerData \ "items").asOpt[JsObject] match {
                case Some(items) =>
                  items.fields.collect {
                    case (_, slotData) if (slotData \ "itemId").asOpt[Int].contains(updatedState.ammoId) => 1
                  }
                case None => Seq.empty
              }
          }.sum

          println(s"Total stacks found for ammoId ${updatedState.ammoId}: $stacksCount")
          updatedState = updatedState.copy(
            suppliesLeftMap = updatedState.suppliesLeftMap.updated(updatedState.ammoId, stacksCount)
          )

          // Handle the case where no ammo is found
          if (stacksCount == 0) {
            println("No ammo available in containers or slot10. Cannot proceed with resupply.")
//            updatedState = updatedState.copy(isUsingAmmo = "false")
            // Optionally, you could add logic here to handle the system state when no ammo is available.
          } else {
            // Now handle the slot10Info logic
            val slot10Info = (json \ "EqInfo" \ "10").as[JsObject]
            val ammoCountOpt = (slot10Info \ "itemCount").asOpt[Int]

            // Determine the ammo count, treating it as 0 if missing and ammo is available in containers
            val ammoCount = ammoCountOpt.getOrElse(0)

            println(s"ammoCountForNextResupply: ${updatedState.ammoCountForNextResupply}")

            if (ammoCount < updatedState.ammoCountForNextResupply) {
              val resultResupplyAmmo = resupplyAmmo(json, updatedState, containerInfo, actions, logs)

              actions = resultResupplyAmmo._1._1
              logs = resultResupplyAmmo._1._2
              updatedState = resultResupplyAmmo._2
            }
          }

        case _ => // handle unexpected cases or errors
      }
      // resupply ammo end




      // check supplies in containers
      val resultProcessSuppliesState = processSuppliesState(json, settings, actions, logs, updatedState)
      actions = resultProcessSuppliesState._1._1
      logs = resultProcessSuppliesState._1._2
      updatedState = resultProcessSuppliesState._2
      // end check supplies in containers


      val presentCharLocationZ = (json \ "characterInfo" \ "PositionZ").as[Int]

      // new logi1
      // Start autotarget

      // extract monsters list from battle - sort by danger, hp

      // check if updatedState.chosenTargetId has been chosen (if (!(updatedState.chosenTargetId == 0)) {)
      // if yes, check if the target has to be changed
      // if no proceed to caclulateSubwaypoints
      // if yes, reset updatedState.chosenTargetId back to 0.
      // if no, proceed
      // we need to check if a first creature from monsters list is pathable, (caclulateSubwaypoints)
      // if not it should be removed and the function of checking if the monster is pathable should be reapeated (caclulateSubwaypoints)
      // when a first creature for the list has a path, save it

      // ATTACKING SECTION
      // check if marking should be done in battle or not
      // if yes, mark the monster on battle
      // if not proceed

      // RUNE SECTION
      // check if on this creature rune should be used
      // if yes check where should the rune be used and check if creature is shootable
      // if on battle than use rune on monster on battle
      // execute function shootRuneOnBattle
      // if on screen than use rune on monster on screen
      // execute function shootRuneOnScreen

      // MOVEMENT SECTION
      // follow subwwaypoints




      if (
      // CaveBot is enabled, present Z level is in the list, and autotargeting is enabled
        (settings.caveBotSettings.enabled &&
          updatedState.caveBotLevelsList.contains(presentCharLocationZ) &&
          settings.autoTargetSettings.enabled) ||

          // CaveBot is disabled, but autotargeting is enabled
          (!settings.caveBotSettings.enabled && settings.autoTargetSettings.enabled) ||

          // Team hunting is enabled, followBlocker is true, and Z levels match
          (settings.teamHuntSettings.enabled &&
            settings.teamHuntSettings.followBlocker &&
            updatedState.lastBlockerPosZ == presentCharLocationZ)
      ) {
//        println("Start autotarget")

//        // Process the hunting state
//        val processResultStateHunting = processStateTargeting(json, settings, updatedState)
//        actions ++= processResultStateHunting.actions
//        logs ++= processResultStateHunting.logs
//        updatedState = processResultStateHunting.updatedState
//
//
//
//        def processStateTargeting(json: JsValue, settings: UISettings, currentState: ProcessorState): ProcessResult = {
//          var updatedState = currentState
//
//          updatedState.stateTargeting match {
//
//            case "attacking" =>
//              println("Attacking target.")
//              val ProcessResult(actions, logs, newState) = handleAttackingTarget(json, settings, updatedState)
//              ProcessResult(actions, logs, newState)
//
//            case "target chosen" =>
//              println("Target has been chosen.")
//              val ProcessResult(actions, logs, newState) = handleTargetChosen(json, settings, updatedState)
//              ProcessResult(actions, logs, newState)
//
//            case "searching for target" =>
//              println("Searching for a new target.")
//              val ProcessResult(actions, logs, newState) = handleSearchingForTarget(json, settings, updatedState)
//              ProcessResult(actions, logs, newState)
//
//            case "target acquired" =>
//              println("Target acquired.")
//              val ProcessResult(actions, logs, newState) = handleTargetAcquired(json, settings, updatedState)
//              ProcessResult(actions, logs, newState)
//
//            case "free" =>
//              println("No target, system is idle.")
//              ProcessResult(Seq(), Seq(), updatedState)
//
//            case _ =>
//              println("Unknown state, doing nothing.")
//              ProcessResult(Seq(), Seq(), updatedState)
//          }
//        }




        val sortedMonstersInfo = extractInfoAndSortMonstersFromBattle(json, settings)
        println(s"chosenTargetId: ${updatedState.chosenTargetId}")
        println(s"sortedMonstersInfo: $sortedMonstersInfo")


        val firstCreatureOpt = sortedMonstersInfo.headOption // Get the first creature in the list, if it exists
        firstCreatureOpt.foreach { firstCreature =>
          // Check if the first creature has keepDistance set to true and its ID is different from the current chosen target
          if (firstCreature.keepDistance && firstCreature.id != updatedState.chosenTargetId) {
            // Reset the chosenTargetId
            updatedState = updatedState.copy(chosenTargetId = 0)
            println("[DEBUG] Resetting chosenTargetId because the first creature keeps distance and has a different ID.")
          }
        }



        // Check if the last attacked creature is dead
        val lastAttackedCreatureInfo = (json \ "lastAttackedCreatureInfo").as[JsObject]
        val lastAttackedId = (lastAttackedCreatureInfo \ "LastAttackedId").asOpt[Int].getOrElse(0) // Assuming ID is a Long
        val isLastAttackedCreatureDead = (lastAttackedCreatureInfo \ "IsDead").as[Boolean]

        // Check if the current chosenTargetId exists in the sortedMonstersInfo list
        val isTargetInSortedMonsters = sortedMonstersInfo.exists(monster => monster.id == updatedState.chosenTargetId)

        // Reset chosenTargetId if last attacked creature is dead or the current target is not in the sorted list
        if ((isLastAttackedCreatureDead && lastAttackedId == updatedState.chosenTargetId) || !isTargetInSortedMonsters) {
//          println(s"Either the last attacked creature is dead or the current target is not in sortedMonstersInfo. Resetting chosenTargetId to 0.")
          updatedState = updatedState.copy(chosenTargetId = 0, chosenTargetName = "")
        }

        if (sortedMonstersInfo.isEmpty) {
//          println(s"No Monsters on battle, ${updatedState.chosenTargetId}")
        } else {
          println(s"Monsters detected, chosenTargetId: ${updatedState.chosenTargetId}")
          if (updatedState.chosenTargetId == 0) {
            println("chosenTargetId is not chosen")

            // Limit potentialTargets to the first 5 elements from sortedMonstersInfo
            var potentialTargets = sortedMonstersInfo.take(5)

            // Loop through potential targets until a path is found or the list is empty
            var pathFound = false
            val presentCharLocationX = (json \ "characterInfo" \ "PositionX").as[Int]
            val presentCharLocationY = (json \ "characterInfo" \ "PositionY").as[Int]
            val presentCharLocation = Vec(presentCharLocationX, presentCharLocationY)

            while (potentialTargets.nonEmpty && !pathFound) {
              val monster = potentialTargets.head
              val isPathable = checkPathToTarget(monster, presentCharLocation, json)

              if (isPathable) {
                println("Path from character to creature found.")
                updatedState = updatedState.copy(chosenTargetId = monster.id, chosenTargetName = monster.name)
                println(s"Target chosen: ${monster.name} with ID ${monster.id}")
                pathFound = true
              } else {
                println(s"No path found for monster: ${monster.name} with ID: ${monster.id}")
                potentialTargets = potentialTargets.tail // Remove the monster if no path is found
              }
            }

            // If after looping through the targets no path was found, print "No available targets"
            if (!pathFound) {
              println("No available targets.")
            }
          } else {
            println(s"chosenTargetId is chosen - name: ${updatedState.chosenTargetName}, id: ${updatedState.chosenTargetId}")
            val battleInfo = (json \ "battleInfo").as[Map[String, JsValue]]


            // MARKING TARGET ON BATTLE SECTION
            (json \ "attackInfo" \ "Id").asOpt[Int] match {
              case Some(attackId) if attackId == updatedState.chosenTargetId =>
                // If the Id is the same as chosenTargetId, leave a placeholder
                println(s"Still attacking chosenTargetId: ${updatedState.chosenTargetId}")

              case _ =>
                // If the Id is None or different from chosenTargetId, leave another placeholder
                println(s"Attack Id is either None or different from chosenTargetId: ${updatedState.chosenTargetId}")
//                println(settings.autoTargetSettings.creatureList)
                val targetMonstersJsons = transformToJSON(settings.autoTargetSettings.creatureList)


                // Retrieve the battle targeting settings
                val targetBattle = getTargetBattle(updatedState.chosenTargetName, targetMonstersJsons)
                val targetBattleForAll = getTargetBattle("All", targetMonstersJsons).getOrElse(false)

                // Apply battle targeting logic:
                // 1. Target the chosen creature if its `targetBattle` is true.
                // 2. Otherwise, check if the "All" setting is true and the creature is not already in battle.
                val shouldTargetOnBattle = targetBattle.getOrElse(false) ||
                  (targetBattle.isEmpty && targetBattleForAll && !battleInfo.exists(_._2 \ "Name" == updatedState.chosenTargetName))

                if (shouldTargetOnBattle) {
                  println(s"Targeting on battle for ${updatedState.chosenTargetName}")
                  val resultTargetOnBattle = targetOnBattle(updatedState.chosenTargetId, json, actions, logs, updatedState)

                  actions = resultTargetOnBattle._1._1
                  logs = resultTargetOnBattle._1._2
                  updatedState = resultTargetOnBattle._2

                } else {
                  println(s"Not targeting on battle for ${updatedState.chosenTargetName}")
                }

            }

            val creatureInfo = findCreatureInfoById(updatedState.chosenTargetId, battleInfo)
            val presentCharLocationX = (json \ "characterInfo" \ "PositionX").as[Int]
            val presentCharLocationY = (json \ "characterInfo" \ "PositionY").as[Int]
            val presentCharLocation = Vec(presentCharLocationX, presentCharLocationY)

            // movement section
            creatureInfo match {
              case Some(creatureData) =>

                val resultEngageCreature = engageCreature(
                  creatureData,
                  presentCharLocation,
                  json,
                  actions,
                  logs,
                  updatedState,
                  settings,
                )

                actions = resultEngageCreature._1._1
                logs = resultEngageCreature._1._2
                updatedState = resultEngageCreature._2
              case None =>
                println(s"Creature with ID ${updatedState.chosenTargetId} not found in battleInfo")
            }

            // shootrune section
            // Step 1: Transform creatureList into a list of JSON objects (name -> danger, keepDistance, etc.)
            val targetMonstersJsons = transformToJSON(settings.autoTargetSettings.creatureList)
            println(s"targetMonstersJsons: ${targetMonstersJsons}")

            // Step 2: Check if at least one creature has useRune set to true
            val hasRuneTarget = targetMonstersJsons.exists { monsterJson =>
              (monsterJson \ "useRune").asOpt[Boolean].getOrElse(false)
            }

            if (hasRuneTarget) {
              // Find the current targeted creature by its ID
              val creatureInfoOpt = findCreatureInfoById(updatedState.chosenTargetId, battleInfo)

              creatureInfoOpt match {
                case Some(creatureData) =>
                  // Check if the targeted creature is shootable
                  if (creatureData.isShootable) {
                    // Search for the creature's JSON entry in targetMonstersJsons by matching the name
                    val matchingCreatureJsonOpt = targetMonstersJsons.find { monsterJson =>
                      (monsterJson \ "name").as[String] == creatureData.name
                    }

                    // If no exact match for creatureData.name, look for the "All" entry
                    val allCreatureJsonOpt = targetMonstersJsons.find { monsterJson =>
                      (monsterJson \ "name").as[String] == "All"
                    }

                    matchingCreatureJsonOpt match {
                      case Some(matchingCreatureJson) =>
                        // Check if the creature's JSON entry has useRune set to true
                        val useRune = (matchingCreatureJson \ "useRune").asOpt[Boolean].getOrElse(false)
                        if (useRune) {
                          val resultshootRuneOnTarget = shootRuneOnTarget(creatureData, targetMonstersJsons, json, updatedState, settings, actions, logs)

                          actions = resultshootRuneOnTarget._1._1
                          logs = resultshootRuneOnTarget._1._2
                          updatedState = resultshootRuneOnTarget._2
                        } else {
                          println(s"Creature ${creatureData.name} does not have useRune set to true.")
                        }

                      case None =>
                        // No exact match, check if "All" is available
                        allCreatureJsonOpt match {
                          case Some(allCreatureJson) =>
                            // Apply the same settings as 'All' to the targeted creature
                            val useRune = (allCreatureJson \ "useRune").asOpt[Boolean].getOrElse(false)
                            if (useRune) {
                              val resultshootRuneOnTarget = shootRuneOnTarget(creatureData, targetMonstersJsons, json, updatedState, settings, actions, logs)

                              actions = resultshootRuneOnTarget._1._1
                              logs = resultshootRuneOnTarget._1._2
                              updatedState = resultshootRuneOnTarget._2

                              println(s"Applied 'All' settings to ${creatureData.name}.")
                            } else {
                              println(s"Creature 'All' does not have useRune set to true.")
                            }

                          case None =>
                            println(s"No matching creature found in targetMonstersJsons for creature: ${creatureData.name}")
                        }
                    }

                  } else {
                    println(s"Targeted creature ${creatureData.name} is not shootable.")
                  }

                case None =>
                  println(s"Creature with ID ${updatedState.chosenTargetId} not found in battleInfo.")
              }
            } else {
              println("No creatures in the creature list have useRune set to true.")
            }

          }
        }
      }
    }

    val endTime = System.nanoTime()
    val duration = (endTime - startTime) / 1e9d
    printInColor(ANSI_GREEN, f"[INFO] Processing computeAutoTargetActions took $duration%.6f seconds")
    ((actions, logs), updatedState)
  }




//  def executeWhenMonstersOnScreen(json: JsValue, settings: UISettings, initialState: ProcessorState, initialActions: Seq[FakeAction], initialLogs: Seq[Log]): ((Seq[FakeAction], Seq[Log]), ProcessorState) = {
////    println("Performing executeWhenMonstersOnScreen.")
//    val startTime = System.nanoTime()
//    var actions = initialActions
//    var logs = initialLogs
//    var updatedState = initialState
//
//
//    // Safely attempt to extract the attacked creature's target ID
//    (json \ "attackInfo" \ "Id").asOpt[Int] match {
//      case Some(attackedCreatureTarget) =>
//        updatedState = updatedState.copy(stateHunting = "attacking")
//        println(s"attackInfo is not null, target Id: $attackedCreatureTarget")
//        val targetName = (json \ "attackInfo" \ "Name").asOpt[String].getOrElse("Unknown")
//
//        val xPos = (json \ "attackInfo" \ "Position" \ "x").asOpt[Int].getOrElse(0)
//        val yPos = (json \ "attackInfo" \ "Position" \ "y").asOpt[Int].getOrElse(0)
//        val zPos = (json \ "attackInfo" \ "Position" \ "z").asOpt[Int].getOrElse(0)
//
//        println(s"Creature $targetName ($attackedCreatureTarget) in game position $xPos, $yPos, $zPos")
//
//
//        updatedState = updatedState.copy(
//          lastTargetName = targetName,
//          lastTargetPos = (xPos, yPos, zPos),
//          creatureTarget = attackedCreatureTarget
//        )
//
//
//      case None =>
//
//        if (updatedState.stateHunting == "free") {
//
//          updatedState = updatedState.copy(creatureTarget = 0)
//          updatedState = updatedState.copy(alreadyLootedIds = List(), lootingStatus=0,extraWidowLootStatus=0, lootingRestryStatus=0, targetFreezeHealthPoints=0)
//
//          // Extract monsters, their IDs, and Names
//          val monsters: Seq[(Int, String)] = (json \ "battleInfo").as[JsObject].values.flatMap { creature =>
//            println("gate5")
//            val isMonster = (creature \ "IsMonster").as[Boolean]
//            println("gate6")
//            if (isMonster) {
//              Some((creature \ "Id").as[Int], (creature \ "Name").as[String])
//            } else None
//          }.toSeq
//
//
//
//
//          // Filter and sort based on the danger level, sorting by descending danger
//          var sortedMonsters = if (targetAllCreatures) {
//            monsters // If targeting all, skip the danger level filtering
//          } else {
//            monsters
//              .filter { case (_, name) => creatureDangerMap.contains(name) }
//              .sortBy { case (_, name) => -creatureDangerMap(name) } // Descending order of danger
//          }
//
//          // Further filtering based on IDs or other conditions should be adjusted based on whether "All" is targeted
//          sortedMonsters = if (targetAllCreatures) {
//            sortedMonsters.filter { case (id, _) => id >= 5 }
//          } else {
//            sortedMonsters
//              .filter { case (id, _) => id >= 5 }
//              .sortBy { case (id, _) =>
//                val battleCreaturePosition = (json \ "screenInfo" \ "battlePanelLoc" \ id.toString).asOpt[JsObject]
//                battleCreaturePosition.flatMap(pos => (pos \ "PosY").asOpt[Int]).getOrElse(Int.MaxValue)
//              }
//          }
//
//
//          var topFourMonsters = sortedMonsters.take(4)
//            .sortBy { case (_, name) => -creatureDangerMap.getOrElse(name, 0) }
//
//          printInColor(ANSI_BLUE, f"[DEBUG] Before reshuffling monster list: ${updatedState.targetFreezeHealthStatus} / ${updatedState.retryAttemptsLong}")
//
//          if (updatedState.targetFreezeHealthStatus >= updatedState.retryAttemptsLong) {
//            println(s"[DEBUG] Pre Reshuffling monster list: $topFourMonsters")
//
//
//            // Remove monster with targetFreezeCreatureId from the list
//            val filteredMonsters = topFourMonsters.filter { case (id, _) => id != updatedState.targetFreezeCreatureId }
//            if (filteredMonsters.isEmpty) {
//              println("[DEBUG] No monsters left after removing the target freeze creature.")
//            }
//
//            // Shuffle the filtered list and then sort by descending danger
//            val shuffledMonsters = scala.util.Random.shuffle(filteredMonsters)
//              .sortBy { case (_, name) => -creatureDangerMap.getOrElse(name, 0) } // Safe access with default if key not found
//
//
//            //            // Remove monster with targetFreezeCreatureId from the list
////            val filteredMonsters = topFourMonsters.filter { case (id, _) => id != updatedState.targetFreezeCreatureId }
////
////
////            // Shuffle the filtered list
////            val shuffledMonsters = scala.util.Random.shuffle(filteredMonsters)
////              .sortBy { case (_, name) => -creatureDangerMap(name) }
//
//            println(s"[DEBUG] After Reshuffling monster list: $shuffledMonsters")
//
//            // Update topFourMonsters with the shuffled list
//            topFourMonsters = shuffledMonsters
//            updatedState = updatedState.copy(targetFreezeHealthStatus = 0)
//          }
//
//          println(s"topFourMonsters: ${topFourMonsters}")
//          // Process the highest priority target
//          topFourMonsters.headOption match {
//            case Some((id, name)) =>
//
//              val battleCreaturePosition = (json \ "screenInfo" \ "battlePanelLoc" \ id.toString).asOpt[JsObject]
//              println(s"battleCreaturePosition: ${battleCreaturePosition}")
//              battleCreaturePosition match {
//                case Some(pos) =>
//                  val battleCreaturePositionX = (pos \ "PosX").asOpt[Int].getOrElse(0)
//                  val battleCreaturePositionY = (pos \ "PosY").asOpt[Int].getOrElse(0)
//
//                  if (updatedState.retryStatus >= updatedState.retryAttempts) {
//                    printInColor(ANSI_RED, f"[DEBUG] Attack creature name: $name, and id: $id ")
//                    updatedState = updatedState.copy(lastTargetName = name)
//
//                    // Perform the mouse actions
//                    val actionsSeq = Seq(
//                      MouseAction(battleCreaturePositionX, battleCreaturePositionY, "move"),
//                      MouseAction(battleCreaturePositionX, battleCreaturePositionY, "pressLeft"),
//                      MouseAction(battleCreaturePositionX, battleCreaturePositionY, "releaseLeft")
//                    )
//                    actions = actions :+ FakeAction("useMouse", None, Some(MouseActions(actionsSeq)))
//
//                    // Reset the state and retryStatus
//                    updatedState = updatedState.copy(retryStatus = 0,targetFreezeHealthStatus = 0, targetFreezeCreatureId=0)
//
//                  } else {
//                    printInColor(ANSI_RED, f"[DEBUG] Retrying... (Attempt ${updatedState.retryStatus + 1})")
//                    updatedState = updatedState.copy(retryStatus = updatedState.retryStatus + 1)
//                  }
//
//                case None =>
//                  println(s"No position information available for monster ID $id")
//              }
//            case None =>
//              println("No monsters found to attack.")
//          }
//        } else {
//          println(s"Autotarget condition is met, but state is not ready: ${updatedState.stateHunting}")
//        }
//    }
//
//
//    val endTime = System.nanoTime()
//    val duration = (endTime - startTime) / 1e9d
////    println(f"[INFO] Processing executeWhenNoMonstersOnScreen took $duration%.6f seconds")
//    ((actions, logs), updatedState)
//  }
//


  case class CreatureSettings(
                               name: String,
                               count: Int,
                               hpFrom: Int,
                               hpTo: Int,
                               danger: Int,
                               targetBattle: Boolean,
                               loot: Boolean,
                               chase: Boolean,
                               keepDistance: Boolean,
                               avoidWaves: Boolean,
                               useRune: Boolean,
                               runeType: Option[String],
                               useRuneOnScreen: Boolean,
                               useRuneOnBattle: Boolean,
                               lootMonsterImmediately: Boolean,   // new field
                               lootMonsterAfterFight: Boolean,    // new field
                               lureCreatureToTeam: Boolean        // new field
    )


  def parseCreature(description: String): CreatureSettings = {
    val parts = description.split(", ")
    val name = parts(0).substring("Name: ".length)
    val count = parts(1).split(": ")(1).toInt
    val hpRange = parts(2).split(": ")(1).split("-").map(_.toInt)
    val danger = parts(3).split(": ")(1).toInt
    val targetBattle = parts(4).split(": ")(1).equalsIgnoreCase("yes")
    val loot = parts(5).split(": ")(1).equalsIgnoreCase("yes")

    val lootMonsterImmediately = parts(6).split(": ")(1).equalsIgnoreCase("yes")
    val lootMonsterAfterFight = parts(7).split(": ")(1).equalsIgnoreCase("yes")
    val chase = parts(8).split(": ")(1).equalsIgnoreCase("yes")
    val keepDistance = parts(9).split(": ")(1).equalsIgnoreCase("yes")
    val lureCreatureToTeam = parts(10).split(": ")(1).equalsIgnoreCase("yes")
    val avoidWaves = parts(11).split(": ")(1).equalsIgnoreCase("yes")
    val useRune = parts(12).split(": ")(1).equalsIgnoreCase("yes")
    val runeType = if (parts.length > 13 && parts(13).split(": ").length > 1) Some(parts(13).split(": ")(1)) else None
    val useRuneOnScreen = if (parts.length > 14) parts(14).split(": ")(1).equalsIgnoreCase("yes") else false
    val useRuneOnBattle = if (parts.length > 15) parts(15).split(": ")(1).equalsIgnoreCase("yes") else false

    // Debug: Print parsed values for keepDistance and chase
//    println(s"[DEBUG] Parsing Creature: $name, Chase: $chase, Keep Distance: $keepDistance, Lure to Team: $lureCreatureToTeam")

    CreatureSettings(
      name, count, hpRange(0), hpRange(1), danger, targetBattle, loot, chase, keepDistance,
      avoidWaves, useRune, runeType, useRuneOnScreen, useRuneOnBattle, lootMonsterImmediately, lootMonsterAfterFight, lureCreatureToTeam
    )
  }



  def transformToObject(creatureData: CreatureInfo, creatureSettingsList: Seq[String]): Option[CreatureSettings] = {
    // Debug: Print the creatureSettingsList
    println(s"[DEBUG] CreatureSettingsList: $creatureSettingsList")

    // Parse each creature setting description into CreatureSettings objects
    val parsedSettings = creatureSettingsList.map(parseCreature)

    // Debug: print the parsed creature settings for comparison
    parsedSettings.foreach { setting =>
      println(s"[DEBUG] Parsed Creature: ${setting.name}, Keep Distance: ${setting.keepDistance}")
    }

    // Find the matching creature settings by name (case-insensitive)
    parsedSettings.find(_.name.equalsIgnoreCase(creatureData.name)) match {
      case Some(creatureSettings) => Some(creatureSettings)
      case None =>
        // If no exact match is found, look for settings with the name "All"
        parsedSettings.find(_.name.equalsIgnoreCase("All"))
    }
  }

  def transformToJSON(creaturesData: Seq[String]): List[JsValue] = {
    creaturesData.map(description => {
      val creatureSettings = parseCreature(description)

      // Debug: Print parsed creature settings before converting to JSON
//      println(s"[DEBUG] Parsed CreatureSettings: $creatureSettings")

      Json.toJson(creatureSettings) // Convert creature settings to JsValue
    }).toList
  }


  object CreatureSettings {
    implicit val writes: Writes[CreatureSettings] = Json.writes[CreatureSettings]
    implicit val reads: Reads[CreatureSettings] = Json.reads[CreatureSettings]
  }



  // Transform to JSON function



  // Define a helper function to extract the rune ID from a string

  // Helper function to extract rune IDs
  def extractRuneIdFromSetting(entry: String): Option[Int] = {
    entry.split(", ").flatMap {
      case setting if setting.startsWith("Rune Type:") =>
        setting.split("\\.").lastOption.flatMap(num => Try(num.toInt).toOption)
      case _ => None
    }.headOption
  }
  // Define a helper function to safely extract rune IDs from various rune type formats
  // Define a helper function to safely extract rune IDs from various rune type formats
  def extractRuneID(settings: String): Option[Int] = {
    val runeTypePattern: Regex = "Rune Type: ([A-Z]+)\\.(\\d+)".r

    settings.split(", ").flatMap {
      case runeTypePattern(_, id) => Some(id.toInt)
      case _ => None
    }.headOption
  }


  // Function to extract battleInfo and sort by danger, healthPercent, and keepDistance
  def extractInfoAndSortMonstersFromBattle(json: JsValue, settings: UISettings): List[CreatureInfo] = {
    // Extract battleInfo from the JSON
    val battleInfo = (json \ "battleInfo").as[Map[String, JsValue]]

    // Transform creatureList from settings into JSON
    val targetMonstersJsons = transformToJSON(settings.autoTargetSettings.creatureList)

    // Check if we are targeting all creatures
    val targetAllCreatures = targetMonstersJsons.exists { creatureJson =>
      (creatureJson \ "name").as[String].equalsIgnoreCase("All")
    }

    // Map creature name to CreatureSettings
    val creatureSettingsMap: Map[String, CreatureSettings] = targetMonstersJsons.map { creatureJson =>
      val creature = creatureJson.as[CreatureSettings]
      (creature.name, creature)
    }.toMap

    // Extract and map battle targets from battleInfo
    val battleTargets: List[CreatureInfo] = battleInfo.flatMap { case (_, battleData) =>
      val isMonster = (battleData \ "IsMonster").asOpt[Boolean].getOrElse(false)
      val isPlayer = (battleData \ "IsPlayer").asOpt[Boolean].getOrElse(false)

      if ((isMonster || targetAllCreatures) && !isPlayer) {
        val creatureName = (battleData \ "Name").as[String]

        // Retrieve creature settings or use default settings
        val creatureSettings = creatureSettingsMap.getOrElse(creatureName, CreatureSettings(
          name = creatureName,
          count = 0,
          hpFrom = 0,
          hpTo = 100,
          danger = 0,
          targetBattle = false,
          loot = false,
          chase = false,
          keepDistance = false,
          avoidWaves = false,
          useRune = false,
          runeType = None,
          useRuneOnScreen = false,
          useRuneOnBattle = false,
          lootMonsterImmediately = false,  // new field
          lootMonsterAfterFight = false,   // new field
          lureCreatureToTeam = false       // new field
        ))

        Some(CreatureInfo(
          id = (battleData \ "Id").as[Int],
          name = creatureName,
          healthPercent = (battleData \ "HealthPercent").as[Int],
          isShootable = (battleData \ "IsShootable").as[Boolean],
          isMonster = isMonster,
          danger = creatureSettings.danger,           // Set danger level from settings
          keepDistance = creatureSettings.keepDistance, // Set keep distance from settings
          isPlayer = isPlayer,
          posX = (battleData \ "PositionX").as[Int],
          posY = (battleData \ "PositionY").as[Int],
          posZ = (battleData \ "PositionZ").as[Int],
          lootMonsterImmediately = creatureSettings.lootMonsterImmediately,  // new field
          lootMonsterAfterFight = creatureSettings.lootMonsterAfterFight,    // new field
          lureCreatureToTeam = creatureSettings.lureCreatureToTeam           // new field
        ))
      } else {
        None
      }
    }.toList

    // Sort creatures by danger (descending) and healthPercent (ascending)
    battleTargets.sortBy(monster => (monster.danger * -1, monster.healthPercent))
  }


  def checkPathToTarget(monster: CreatureInfo, presentCharLocation: Vec, json: JsValue): Boolean = {
    println(s"[DEBUG] Checking path to target: ${monster.name} at position (${monster.posX}, ${monster.posY}, ${monster.posZ})")

    // Parse tiles to determine the grid bounds and create a boolean grid
    val tiles = (json \ "areaInfo" \ "tiles").as[Map[String, JsObject]]
    val xs = tiles.keys.map(_.substring(0, 5).trim.toInt)
    val ys = tiles.keys.map(_.substring(5, 10).trim.toInt)
    val gridBounds = (xs.min, ys.min, xs.max, ys.max)
    println(s"[DEBUG] GridBounds: $gridBounds")

    val (grid, (min_x, min_y)) = createBooleanGrid(tiles, xs.min, ys.min)

    // Convert the monster's position to Vec
    val monsterPosition = Vec(monster.posX, monster.posY)
    println(s"[DEBUG] Monster Position: $monsterPosition")
    println(s"[DEBUG] Character Location: $presentCharLocation")

    // Check if the character is already at the target location
    if (presentCharLocation == monsterPosition) {
      println(s"[DEBUG] Character is already at the monster's location.")
      return true
    }

    // Perform A* search to check for a valid path
    val path = aStarSearch(presentCharLocation, monsterPosition, grid, min_x, min_y)

    if (path.nonEmpty) {
      println(s"[DEBUG] Path found to monster: ${monster.name}. Path: $path")
      printGrid(grid, gridBounds, path, presentCharLocation, monsterPosition)
      true
    } else {
      println(s"[DEBUG] No path found to monster: ${monster.name}.")
      false
    }
  }
  def findCreatureInfoById(creatureId: Long, battleInfo: Map[String, JsValue]): Option[CreatureInfo] = {
    battleInfo.collectFirst {
      case (_, creatureData) if (creatureData \ "Id").as[Long] == creatureId =>
        val id = (creatureData \ "Id").as[Int]
        val name = (creatureData \ "Name").as[String]
        val healthPercent = (creatureData \ "HealthPercent").as[Int]
        val isShootable = (creatureData \ "IsShootable").as[Boolean]
        val isMonster = (creatureData \ "IsMonster").as[Boolean]
        val danger = (creatureData \ "Danger").asOpt[Int].getOrElse(0)
        val keepDistance = (creatureData \ "keepDistance").asOpt[Boolean].getOrElse(false)
        val lootMonsterImmediately = (creatureData \ "lootMonsterImmediately").asOpt[Boolean].getOrElse(false)  // new field
        val lootMonsterAfterFight = (creatureData \ "lootMonsterAfterFight").asOpt[Boolean].getOrElse(false)    // new field
        val lureCreatureToTeam = (creatureData \ "lureCreatureToTeam").asOpt[Boolean].getOrElse(false)          // new field
        val isPlayer = (creatureData \ "IsPlayer").as[Boolean]
        val posX = (creatureData \ "PositionX").as[Int]
        val posY = (creatureData \ "PositionY").as[Int]
        val posZ = (creatureData \ "PositionZ").as[Int]

        CreatureInfo(id, name, healthPercent, isShootable, isMonster, danger, keepDistance, isPlayer, posX, posY, posZ, lootMonsterImmediately, lootMonsterAfterFight, lureCreatureToTeam)
    }
  }


  // Get the target settings for the chosen creature or for "All"
  def getTargetBattle(creatureName: String, targetMonstersJsons: Seq[JsValue]): Option[Boolean] = {
    targetMonstersJsons
      .find(creatureJson => (creatureJson \ "name").as[String].equalsIgnoreCase(creatureName))
      .map(creatureJson => (creatureJson \ "targetBattle").as[Boolean])
  }


  def targetOnBattle(
                      targetId: Int,
                      json: JsValue,
                      initialActions: Seq[FakeAction],
                      initialLogs: Seq[Log],
                      currentState: ProcessorState
                    ): ((Seq[FakeAction], Seq[Log]), ProcessorState) = {


    var actions: Seq[FakeAction] = initialActions
    var logs: Seq[Log] = initialLogs
    var updatedState = currentState
    val currentTime = currentTimeMillis()


    // Try to extract the creature's battle position from the JSON
    val battleCreaturePosition = (json \ "screenInfo" \ "battlePanelLoc" \ targetId.toString).asOpt[JsObject]
    println(s"battleCreaturePosition: ${battleCreaturePosition}")

    battleCreaturePosition match {
      case Some(pos) =>
        // Extract the position coordinates, defaulting to 0 if not found
        val battleCreaturePositionX = (pos \ "PosX").asOpt[Int].getOrElse(0)
        val battleCreaturePositionY = (pos \ "PosY").asOpt[Int].getOrElse(0)

        // Check if retry limit has been reached
        if (currentTime - updatedState.lastTargetMarkCommandSend > 2000) {
          println(s"Selecting creature on battle")
          // Unselect the creature and perform mouse actions
//          logs = logs :+ Log(f"[DEBUG] Unselecting creature id: $targetId error") // log the unselection

          // Sequence of mouse actions
          val actionsSeq = Seq(
            MouseAction(battleCreaturePositionX, battleCreaturePositionY, "move"),
            MouseAction(battleCreaturePositionX, battleCreaturePositionY, "pressLeft"),
            MouseAction(battleCreaturePositionX, battleCreaturePositionY, "releaseLeft")
          )

          // Add the mouse actions to the actions queue
          actions = actions :+ FakeAction("useMouse", None, Some(MouseActions(actionsSeq)))

          // Reset the state and retry status
          updatedState = updatedState.copy(
//            stateHunting = "free",
            lastTargetMarkCommandSend = currentTime,
            targetFreezeCreatureId = targetId
          )
        }

      case None =>
        // Handle the case where no position information is available
        logs = logs :+ Log(s"No position information available for monster ID $targetId warning")
    }

    // Return the tuple of (actions, logs) and updated state
    ((actions, logs), updatedState)
  }

  def engageCreature(
                      creatureData: CreatureInfo, // Data about the creature
                      presentCharLocation: Vec, // The character's current location
                      json: JsValue, // The game state JSON
                      initialActions: Seq[FakeAction],
                      initialLogs: Seq[Log],
                      currentState: ProcessorState, // The current state of the character
                      settings: UISettings // The settings that include creature settings
                    ): ((Seq[FakeAction], Seq[Log]), ProcessorState) = {

    var actions: Seq[FakeAction] = initialActions
    var logs: Seq[Log] = initialLogs
    var updatedState = currentState
    var mode = "none" // Default mode in case no match is found

    // Convert settings to a CreatureSettings object for the targeted creature
    val targetedCreatureSettings = transformToObject(creatureData, settings.autoTargetSettings.creatureList)

    println(s"settings.teamHuntSettings.teamMembersList): ${settings.teamHuntSettings.teamMembersList}")
    println(s"targetedCreatureSettings: ${targetedCreatureSettings}")
    println(s"settings.autoTargetSettings.creatureList: ${settings.autoTargetSettings.creatureList}")

    targetedCreatureSettings match {
      case Some(creatureSettings) =>
        // Determine the mode based on the settings
        if (creatureSettings.chase) {
          mode = "chase"
        } else if (creatureSettings.keepDistance) {
          mode = "keepDistance"
        } else if (creatureSettings.lureCreatureToTeam) {
          mode = "lureCreatureToTeam"
        } else {
          mode = "none"
          logs :+= Log("[DEBUG] No engagement mode selected.")
        }

        logs :+= Log(s"[DEBUG] Engagement mode for ${creatureData.name} set to: $mode")

        // Call the engage logic with the determined mode
        mode match {
          case "chase" =>
            // Move toward the creature
            println(s"[DEBUG] Engaging creature in chase mode.")
            updatedState = generateSubwaypointsToCreature(Vec(creatureData.posX, creatureData.posY), updatedState, json)

            if (updatedState.subWaypoints.nonEmpty) {
              val nextWaypoint = updatedState.subWaypoints.head
              val direction = calculateDirection(presentCharLocation, nextWaypoint, updatedState.lastDirection)
              logs :+= Log(f"[DEBUG] Calculated Next Direction (Chase): $direction")

              updatedState = updatedState.copy(lastDirection = direction)

              direction.foreach { dir =>
                actions = actions :+ FakeAction("pressKey", None, Some(PushTheButton(dir)))
                logs :+= Log(s"Moving toward the creature in direction: $dir")
              }

              updatedState = updatedState.copy(subWaypoints = updatedState.subWaypoints.tail)
            }

          case "keepDistance" =>
            // Try to maintain distance from the creature
            println(s"[DEBUG] Engaging creature in keepDistance mode.")
            val requiredDistance = 3

            val chebyshevDistance = Math.max(
              Math.abs(creatureData.posX - presentCharLocation.x),
              Math.abs(creatureData.posY - presentCharLocation.y)
            )

            if (chebyshevDistance < requiredDistance) {
              // Run away from the creature
              logs :+= Log("[DEBUG] Creature is too close, running away.")

              updatedState = generateSubwaypointsToEscape(Vec(creatureData.posX, creatureData.posY), updatedState, json)

              if (updatedState.subWaypoints.nonEmpty) {
                val nextWaypoint = updatedState.subWaypoints.head
                val direction = calculateDirection(presentCharLocation, nextWaypoint, updatedState.lastDirection)
                logs :+= Log(f"[DEBUG] Calculated Next Direction (Escape): $direction")

                updatedState = updatedState.copy(lastDirection = direction)

                direction.foreach { dir =>
                  actions = actions :+ FakeAction("pressKey", None, Some(PushTheButton(dir)))
                  logs :+= Log(s"Running away from the creature in direction: $dir")
                }

                updatedState = updatedState.copy(subWaypoints = updatedState.subWaypoints.tail)
              }
            } else {
              logs :+= Log("[DEBUG] Safe distance maintained, no action needed.")
            }


          case "lureCreatureToTeam" =>
            println(s"[DEBUG] Engaging creature in Lure to Team mode.")
            val requiredDistance = 5
            val chebyshevDistance = Math.max(
              Math.abs(creatureData.posX - presentCharLocation.x),
              Math.abs(creatureData.posY - presentCharLocation.y)
            )

            if (chebyshevDistance > requiredDistance) {
              // Approach the creature first until within 5 tiles
              logs :+= Log("[DEBUG] Creature is far, approaching it.")
              updatedState = generateSubwaypointsToCreature(Vec(creatureData.posX, creatureData.posY), updatedState, json)

              if (updatedState.subWaypoints.nonEmpty) {
                val nextWaypoint = updatedState.subWaypoints.head
                val direction = calculateDirection(presentCharLocation, nextWaypoint, updatedState.lastDirection)
                logs :+= Log(f"[DEBUG] Calculated Next Direction (Approach): $direction")

                updatedState = updatedState.copy(lastDirection = direction)

                direction.foreach { dir =>
                  actions = actions :+ FakeAction("pressKey", None, Some(PushTheButton(dir)))
                  logs :+= Log(s"Approaching the creature in direction: $dir")
                }

                updatedState = updatedState.copy(subWaypoints = updatedState.subWaypoints.tail)
              }
            } else {
              // Creature is within 5 tiles, move towards the team
              logs :+= Log("[DEBUG] Creature is close, moving toward the team.")

              val defaultVec = presentCharLocation
              val teamMemberPos = getClosestTeamMemberPosition(json, presentCharLocation, settings)
              println(s"teamMemberPos: ${teamMemberPos}")
              // Use getOrElse to provide a default Vec if teamMemberPos is None
              updatedState = generateSubwaypointsToCreature(teamMemberPos.getOrElse(defaultVec), updatedState, json)

              println(s"updatedState.subWaypoints: ${updatedState.subWaypoints}")
              if (updatedState.subWaypoints.nonEmpty) {
                val nextWaypoint = updatedState.subWaypoints.head
                val direction = calculateDirection(presentCharLocation, nextWaypoint, updatedState.lastDirection)
                logs :+= Log(f"[DEBUG] Calculated Next Direction (Lure to Team): $direction")

                updatedState = updatedState.copy(lastDirection = direction)

                direction.foreach { dir =>
                  actions = actions :+ FakeAction("pressKey", None, Some(PushTheButton(dir)))
                  logs :+= Log(s"Moving toward the team in direction: $dir with creature.")
                }

                updatedState = updatedState.copy(subWaypoints = updatedState.subWaypoints.tail)
              }
            }


          case _ =>
            logs :+= Log("[DEBUG] No valid engagement mode, no action taken.")
        }




      case None =>
        logs :+= Log(s"[DEBUG] No matching settings found for creature: ${creatureData.name}")
    }

    ((actions, logs), updatedState)
  }


  def generateSubwaypointsToCreature(targetLocation: Vec, initialState: ProcessorState, json: JsValue): ProcessorState = {
    println("[DEBUG] Generating subwaypoints to creature.")
    var updatedState = initialState

    // Parse the game state to create the boolean grid
    val tiles = (json \ "areaInfo" \ "tiles").as[Map[String, JsObject]]
    val xs = tiles.keys.map(_.substring(0, 5).trim.toInt)
    val ys = tiles.keys.map(_.substring(5, 10).trim.toInt)
    val gridBounds = (xs.min, ys.min, xs.max, ys.max)

    val (grid, (min_x, min_y)) = createBooleanGrid(tiles, xs.min, ys.min)

    // Get the character's current location
    val presentCharLocation = updatedState.presentCharLocation
    println(s"[DEBUG] Character location: $presentCharLocation")
    println(s"[DEBUG] targetLocation: $targetLocation")
    // Use A* search to calculate the path to the target location (creature)
    var newPath: List[Vec] = List()

    if (presentCharLocation != targetLocation) {
      newPath = aStarSearch(presentCharLocation, targetLocation, grid, min_x, min_y)
      println(f"[DEBUG] Path to creature: $newPath")
    } else {
      println("[DEBUG] Already at creature's location.")
    }

    // Remove the presentCharLocation from the newPath if it exists
    val filteredPath = newPath.filterNot(loc => loc == presentCharLocation)

    updatedState.copy(
      subWaypoints = filteredPath,
      gridBoundsState = gridBounds,
      gridState = grid,
      currentWaypointLocation = targetLocation,
      presentCharLocation = presentCharLocation
    )
  }

  def generateSubwaypointsToEscape(creatureLocation: Vec, initialState: ProcessorState, json: JsValue): ProcessorState = {
    println("[DEBUG] Generating subwaypoints to escape creature.")
    var updatedState = initialState

    // Parse the game state to create the boolean grid
    val tiles = (json \ "areaInfo" \ "tiles").as[Map[String, JsObject]]
    val xs = tiles.keys.map(_.substring(0, 5).trim.toInt)
    val ys = tiles.keys.map(_.substring(5, 10).trim.toInt)
    val gridBounds = (xs.min, ys.min, xs.max, ys.max)

    println(s"[DEBUG] Grid Bounds: $gridBounds")

    val (grid, (min_x, min_y)) = createBooleanGrid(tiles, xs.min, ys.min)

    // Get the character's current location
    val presentCharLocation = updatedState.presentCharLocation

    // Calculate the distance between the creature and the character
    val distanceToCreature = presentCharLocation.distanceTo(creatureLocation)
    println(s"[DEBUG] Distance to creature: $distanceToCreature")

    // Define the maximum and minimum safe distances
    val minSafeDistance = 2
    val maxSafeDistance = 4

    // Get a list of walkable tiles around the character within a certain range
    val potentialTiles = getWalkableTiles(presentCharLocation, minSafeDistance, maxSafeDistance, grid, min_x, min_y)
    println(s"[DEBUG] Potential walkable tiles: $potentialTiles")

    // Memoize the paths to avoid recalculating similar ones
    val pathMemo = scala.collection.mutable.Map[Vec, List[Vec]]()

    // Helper function for A* with memoization
    def findPathToTile(tile: Vec): List[Vec] = {
      if (!pathMemo.contains(tile)) {
        val path = aStarSearch(presentCharLocation, tile, grid, min_x, min_y)
        pathMemo(tile) = path
      }
      pathMemo(tile)
    }

    // **NEW CODE**: Sort the tiles based on distance from the creature and take only the farthest 4 tiles
    val farthestTiles = potentialTiles
      .filter(_.distanceTo(creatureLocation) > minSafeDistance) // filter out close tiles
      .sortBy(-_.distanceTo(creatureLocation))                  // sort by farthest
      .take(4)                                                  // only take the top 4

    println(s"[DEBUG] Farthest tiles for escape calculation: $farthestTiles")

    // Filter out tiles that don't have a clear path using memoized A* results
    val validTiles = farthestTiles.filter(tile => {
      val path = findPathToTile(tile)
      val pathExists = path.nonEmpty
      println(s"[DEBUG] Path to tile $tile exists: $pathExists. Path: $path")
      pathExists // Only consider tiles where a valid path exists
    })

    println(s"[DEBUG] Valid tiles after A* filtering: $validTiles")

    // If no valid tiles are found, stay in place
    var escapeTarget: Vec = presentCharLocation
    if (validTiles.nonEmpty) {
      // Choose the farthest valid tile from the creature that has a clear path
      escapeTarget = validTiles.maxBy(_.distanceTo(creatureLocation))
      println(s"[DEBUG] Chosen escape tile: $escapeTarget")
    } else {
      println("[DEBUG] No valid tiles found, staying in place.")
    }

    // Use memoized A* search to calculate the escape path
    val escapePath = findPathToTile(escapeTarget)
    println(f"[DEBUG] Escape path: $escapePath")

    val filteredPath = escapePath.filterNot(loc => loc == presentCharLocation)
    println(s"[DEBUG] Filtered escape path (excluding present location): $filteredPath")

    printGridCreatures(grid, gridBounds, filteredPath, presentCharLocation, escapeTarget, List(creatureLocation))

    updatedState.copy(
      subWaypoints = filteredPath,
      gridBoundsState = gridBounds,
      gridState = grid,
      currentWaypointLocation = escapeTarget,
      presentCharLocation = presentCharLocation
    )
  }


  // Function to get potential walkable targets within a safe distance range
  def getWalkableTiles(charPos: Vec, minDistance: Int, maxDistance: Int, grid: Array[Array[Boolean]], minX: Int, minY: Int): List[Vec] = {
    val targets = for {
      x <- charPos.x - maxDistance to charPos.x + maxDistance
      y <- charPos.y - maxDistance to charPos.y + maxDistance
      pos = Vec(x, y)
      if charPos.distanceTo(pos) >= minDistance && charPos.distanceTo(pos) <= maxDistance
      if isTileWalkable(pos, grid, minX, minY)
    } yield pos
    targets.toList
  }

  // Utility function to check if a tile is walkable
  def isTileWalkable(pos: Vec, grid: Array[Array[Boolean]], minX: Int, minY: Int): Boolean = {
    val x = pos.x - minX
    val y = pos.y - minY
    if (x >= 0 && y >= 0 && x < grid(0).length && y < grid.length) {
      grid(y)(x)
    } else false
  }


  // Utility function to find the closest walkable tile
  def findClosestWalkableTile(pos: Vec, grid: Array[Array[Boolean]], minX: Int, minY: Int): Vec = {
    val neighbors = List(
      Vec(pos.x + 1, pos.y),
      Vec(pos.x - 1, pos.y),
      Vec(pos.x, pos.y + 1),
      Vec(pos.x, pos.y - 1)
    )

    neighbors.find(n => isTileWalkable(n, grid, minX, minY)).getOrElse(pos)
  }



  def transformToObjectOld(creatureData: CreatureInfo, creatureSettingsList: Seq[String]): Option[CreatureSettings] = {
    // Parse each creature setting description into CreatureSettings objects
    val parsedSettings = creatureSettingsList.map(parseCreature)

    // Find the matching creature settings by name
    parsedSettings.find(_.name.equalsIgnoreCase(creatureData.name)) match {
      case Some(creatureSettings) => Some(creatureSettings)
      case None =>
        // If no exact match is found, look for settings with the name "All"
        parsedSettings.find(_.name.equalsIgnoreCase("All"))
    }
  }


  def printGridCreatures(grid: Array[Array[Boolean]], gridBounds: (Int, Int, Int, Int), path: List[Vec], charPos: Vec, waypointPos: Vec, creaturePositions: List[Vec]): Unit = {
    val (min_x, min_y, maxX, maxY) = gridBounds

    // ANSI escape codes for colors
    val red = "\u001B[31m" // Non-walkable
    val green = "\u001B[32m" // Walkable
    val gold = "\u001B[33m" // Character position
    val pink = "\u001B[35m" // Path
    val lightBlue = "\u001B[34m" // Waypoint
    val cyan = "\u001B[36m" // Creature
    val reset = "\u001B[0m" // Reset to default

    // Calculate offset for mapping game coordinates to grid indices
    val offsetX = min_x
    val offsetY = min_y
    val width = maxX - min_x + 1
    val height = maxY - min_y + 1

    // Ensure the grid dimensions match expected size based on bounds
    require(grid.length >= height && grid(0).length >= width, "Grid size does not match the provided bounds.")

    for (y <- 0 until height) {
      for (x <- 0 until width) {
        // Translate grid indices back to game coordinates
        val gameX = x + offsetX
        val gameY = y + offsetY
        val cellVec = Vec(gameX, gameY)

        // Determine which symbol and color to use for the current cell
        val symbol = (cellVec, path.contains(cellVec), cellVec == charPos, cellVec == waypointPos, creaturePositions.contains(cellVec), grid(y)(x)) match {
          case (_, _, true, _, _, _) => s"${gold}[P]$reset" // Character position
          case (_, _, _, true, _, _) => s"${lightBlue}[T]$reset" // Waypoint position
          case (_, true, _, _, _, _) => s"${pink}[W]$reset" // Path position
          case (_, _, _, _, true, _) => s"${cyan}[C]$reset" // Creature position
          case (_, _, _, _, _, true) => s"${green}[O]$reset" // Walkable tile
          case _ => s"${red}[X]$reset" // Non-walkable tile
        }

        print(symbol)
      }
      println() // New line after each row
    }
  }


  def shootRuneOnTarget(
                         creature: CreatureInfo,
                         targetCreatureSettings: List[JsValue],
                         json: JsValue,
                         currentState: ProcessorState,
                         settings: UISettings,
                         initialActions: Seq[FakeAction],
                         initialLogs: Seq[Log]
                       ): ((Seq[FakeAction], Seq[Log]), ProcessorState) = {

    var actions: Seq[FakeAction] = initialActions
    var logs: Seq[Log] = initialLogs
    var updatedState = currentState

    println(s"Shooting rune on target: ${creature.name}")

    // Step 1: Select the creature settings from targetCreatureSettings based on creature.name
    val creatureSettingsOpt = targetCreatureSettings.find { setting =>
      (setting \ "name").as[String] == creature.name
    }

    // If no specific settings found, try finding the "All" settings
    val allCreatureSettingsOpt = targetCreatureSettings.find { setting =>
      (setting \ "name").as[String] == "All"
    }

    creatureSettingsOpt match {
      case Some(creatureSettingsJson) =>
        // Proceed with the original logic for shooting rune based on creature's specific settings
        var resultsprocessRuneShooting =  processRuneShooting(creature, creatureSettingsJson, targetCreatureSettings, json, currentState, settings, actions, logs)
        actions = resultsprocessRuneShooting._1._1
        logs = resultsprocessRuneShooting._1._2
        updatedState = resultsprocessRuneShooting._2

      case None =>
        allCreatureSettingsOpt match {
          case Some(allCreatureSettingsJson) =>
            // "All" found, shoot rune on all creatures in targetCreatureSettings
            val useRuneOnBattle = (allCreatureSettingsJson \ "useRuneOnBattle").asOpt[Boolean].getOrElse(false)
            val useRuneOnScreen = (allCreatureSettingsJson \ "useRuneOnScreen").asOpt[Boolean].getOrElse(false)

            if (useRuneOnBattle || useRuneOnScreen) {
              targetCreatureSettings.foreach { targetCreatureSetting =>
                val creatureName = (targetCreatureSetting \ "name").asOpt[String].getOrElse("Unknown")
                println(s"Shooting rune on creature: $creatureName due to 'All' setting.")
                // Apply rune shooting for each creature in the list
                var resultsprocessRuneShooting = processRuneShooting(
                  creature.copy(name = creatureName),
                  targetCreatureSetting,
                  targetCreatureSettings,
                  json,
                  updatedState,
                  settings,
                  actions,
                  logs
                )
                actions = resultsprocessRuneShooting._1._1
                logs = resultsprocessRuneShooting._1._2
                updatedState = resultsprocessRuneShooting._2


              }
            } else {
              println("'All' creature found but useRune is not enabled.")
            }

          case None =>
            println(s"No settings found for creature: ${creature.name}, and no 'All' setting found.")
        }
    }

    ((actions, logs), updatedState)
  }

  // Helper function to process rune shooting logic based on creature settings


  def processRuneShooting(
                           creature: CreatureInfo,
                           creatureSettingsJson: JsValue,
                           targetCreatureSettings: List[JsValue],
                           json: JsValue,
                           currentState: ProcessorState,
                           settings: UISettings,
                           initialActions: Seq[FakeAction],
                           initialLogs: Seq[Log]
                         ): ((Seq[FakeAction], Seq[Log]), ProcessorState) = {

    var actions: Seq[FakeAction] = initialActions
    var logs: Seq[Log] = initialLogs
    var updatedState = currentState

    val shootOnBattle = (creatureSettingsJson \ "useRuneOnBattle").asOpt[Boolean].getOrElse(false)
    val shootOnScreen = (creatureSettingsJson \ "useRuneOnScreen").asOpt[Boolean].getOrElse(false)

    if (shootOnBattle) {
      // Placeholder for shooting rune in battle
      println(s"Shooting rune on ${creature.name} in battle.")
      val targetSettingsOpt = settings.autoTargetSettings.creatureList.find(_.contains(creature.name))

      targetSettingsOpt match {
        case Some(targetSettings) =>
          extractRuneID(targetSettings) match {
            case Some(runeID) =>
//              println(s"Extracted Rune ID: $runeID")
//              println(s"suppliesContainerMap: ${updatedState.suppliesContainerMap}")
              val currentTime = System.currentTimeMillis()
              if (currentTime - updatedState.lastRuneUseTime > (updatedState.runeUseCooldown + updatedState.runeUseRandomness.toLong)) {

                // Find the container that has the runeID using suppliesContainerMap
                updatedState.suppliesContainerMap.get(runeID) match {
                  case Some(containerName) =>
                    // Check rune availability in the container
                    val runeAvailability = (0 to 3).flatMap { slot =>
                      (json \ "containersInfo" \ containerName \ "items" \ s"slot$slot" \ "itemId").asOpt[Int].map(itemId => (itemId, slot))
                    }.find(_._1 == runeID)

                    runeAvailability match {
                      case Some((_, slot)) =>
                        val inventoryPanelLoc = (json \ "screenInfo" \ "inventoryPanelLoc").as[JsObject]
                        val containerKey = inventoryPanelLoc.keys.find(_.contains(containerName)).getOrElse("")
                        val contentsPath = inventoryPanelLoc \ containerKey \ "contentsPanel"
                        val runeScreenPos = (contentsPath \ s"item$slot").asOpt[JsObject].map { item =>
                          (item \ "x").asOpt[Int].getOrElse(-1) -> (item \ "y").asOpt[Int].getOrElse(-1)
                        }.getOrElse((-1, -1))

                        val monsterPos = (json \ "screenInfo" \ "battlePanelLoc" \ s"${creature.id}").asOpt[JsObject].map { monster =>
                          ((monster \ "PosX").as[Int], (monster \ "PosY").as[Int])
                        }.getOrElse((-1, -1))

                        println(s"Rune position on screen: X=${runeScreenPos._1}, Y=${runeScreenPos._2}")
                        println(s"Monster position on battle screen: X=${monsterPos._1}, Y=${monsterPos._2}")

                        // Check if the monster's position is valid (not -1 or less than 2)
                        if (monsterPos._1 >= 2 && monsterPos._2 >= 2) {
                          // Define actions sequence here and ensure they are triggered correctly
                          val actionsSeq = Seq(
                            MouseAction(runeScreenPos._1, runeScreenPos._2, "move"),
                            MouseAction(runeScreenPos._1, runeScreenPos._2, "pressRight"),
                            MouseAction(runeScreenPos._1, runeScreenPos._2, "releaseRight"),
                            MouseAction(monsterPos._1, monsterPos._2, "move"),
                            MouseAction(monsterPos._1, monsterPos._2, "pressLeft"),
                            MouseAction(monsterPos._1, monsterPos._2, "releaseLeft")
                          )
                          actions = actions :+ FakeAction("useMouse", None, Some(MouseActions(actionsSeq)))

                          // Generate a new random delay using updatedState.runeUseTimeRange
                          val newRandomDelay = generateRandomDelay(updatedState.runeUseTimeRange)

                          updatedState = updatedState.copy(
                            lastRuneUseTime = currentTime,
                            runeUseRandomness = newRandomDelay
                          )

                          println("Actions executed: Monster was in a valid position.")
                        } else {
                          println("Battle is closed or creature not on battle")
                        }
                      case None =>
                        println("Rune is not available in the first four slots of the specified backpack.")
                    }
                  case None =>
                    println(s"No container found for rune ID $runeID in suppliesContainerMap.")
                }
              } else {
                println("Rune cannot be used yet due to cooldown.")
              }
            case None =>
              println("Invalid or missing rune ID in settings.")
          }
        case None =>
          println(s"No settings found for creature: ${creature.name}")
      }

    }

    if (shootOnScreen) {
      // Placeholder for shooting rune on screen
      println(s"Shooting rune on ${creature.name} on screen.")
    }

    ((actions, logs), updatedState)
  }

//
//
//  def shootRuneOnTargetOld(
//                         creature: CreatureInfo,
//                         targetCreatureSettings: List[JsValue],
//                         json: JsValue,
//                         currentState: ProcessorState,
//                         settings: UISettings,
//                         initialActions: Seq[FakeAction],
//                         initialLogs: Seq[Log]
//                       ): ((Seq[FakeAction], Seq[Log]), ProcessorState) = {
//
//    var actions: Seq[FakeAction] = initialActions
//    var logs: Seq[Log] = initialLogs
//    var updatedState = currentState
//
//    println(s"Shooting rune on target: ${creature.name}")
//
//    // Step 1: Select the creature settings from targetCreatureSettings based on creature.name
//    val creatureSettingsOpt = targetCreatureSettings.find { setting =>
//      (setting \ "name").as[String] == creature.name
//    }
//
//    creatureSettingsOpt match {
//      case Some(creatureSettingsJson) =>
//        // Step 2: Parse settings for shoot on battle or shoot on screen
//        val shootOnBattle = (creatureSettingsJson \ "useRuneOnBattle").as[Boolean]
//        val shootOnScreen = (creatureSettingsJson \ "useRuneOnScreen").as[Boolean]
//
//        if (shootOnBattle) {
//          // Placeholder for shooting rune in battle
//          println(s"Shooting rune on ${creature.name} in battle.")
//          val targetSettingsOpt = settings.autoTargetSettings.creatureList.find(_.contains(creature.name))
//
//          targetSettingsOpt match {
//            case Some(targetSettings) =>
//              extractRuneID(targetSettings) match {
//                case Some(runeID) =>
//                  println(s"Extracted Rune ID: $runeID")
//                  val currentTime = System.currentTimeMillis()
//                  if (currentTime - updatedState.lastRuneUseTime > (updatedState.runeUseCooldown + updatedState.runeUseRandomness)) {
//                    val runeAvailability = (0 to 3).flatMap { slot =>
//                      (json \ "containersInfo" \ updatedState.attackRuneContainerName \ "items" \ s"slot$slot" \ "itemId").asOpt[Int].map(itemId => (itemId, slot))
//                    }.find(_._1 == runeID)
//
//                    println(s"Attack rune container: ${updatedState.attackRuneContainerName}")
//                    runeAvailability match {
//                      case Some((_, slot)) =>
//                        val inventoryPanelLoc = (json \ "screenInfo" \ "inventoryPanelLoc").as[JsObject]
//                        val containerKey = inventoryPanelLoc.keys.find(_.contains(updatedState.attackRuneContainerName)).getOrElse("")
//                        val contentsPath = inventoryPanelLoc \ containerKey \ "contentsPanel"
//                        val runeScreenPos = (contentsPath \ s"item$slot").asOpt[JsObject].map { item =>
//                          (item \ "x").asOpt[Int].getOrElse(-1) -> (item \ "y").asOpt[Int].getOrElse(-1)
//                        }.getOrElse((-1, -1))
//
//                        val monsterPos = (json \ "screenInfo" \ "battlePanelLoc" \ s"${creature.id}").asOpt[JsObject].map { monster =>
//                          ((monster \ "PosX").as[Int], (monster \ "PosY").as[Int])
//                        }.getOrElse((-1, -1))
//
//                        println(s"Rune position on screen: X=${runeScreenPos._1}, Y=${runeScreenPos._2}")
//                        println(s"Monster position on battle screen: X=${monsterPos._1}, Y=${monsterPos._2}")
//
//                        // Check if the monster's position is valid (not -1 or less than 2)
//                        if (monsterPos._1 >= 2 && monsterPos._2 >= 2) {
//                          // Define actions sequence here and ensure they are triggered correctly
//                          val actionsSeq = Seq(
//                            MouseAction(runeScreenPos._1, runeScreenPos._2, "move"),
//                            MouseAction(runeScreenPos._1, runeScreenPos._2, "pressRight"),
//                            MouseAction(runeScreenPos._1, runeScreenPos._2, "releaseRight"),
//                            MouseAction(monsterPos._1, monsterPos._2, "move"),
//                            MouseAction(monsterPos._1, monsterPos._2, "pressLeft"),
//                            MouseAction(monsterPos._1, monsterPos._2, "releaseLeft")
//                          )
//                          actions = actions :+ FakeAction("useMouse", None, Some(MouseActions(actionsSeq)))
//                          val newRandomDelay = generateRandomDelay(updatedState.runeUseTimeRange)
//                          updatedState = updatedState.copy(
//                            lastRuneUseTime = currentTime,
//                            runeUseRandomness = newRandomDelay
//                          )
//                          println("Actions executed: Monster was in a valid position.")
//                        } else {
//                          println("Battle is closed or creature not on battle")
//                        }
//                      case None =>
//                        println("Rune is not available in the first four slots of the specified backpack.")
//                    }
//                  } else {
//                    println("Rune cannot be used yet due to cooldown.")
//                  }
//                case None =>
//                  println("Invalid or missing rune ID in settings.")
//              }
//            case None =>
//              println(s"No settings found for creature: ${creature.name}")
//          }
//
//        }
//
//        if (shootOnScreen) {
//          // Placeholder for shooting rune on screen
//          println(s"Shooting rune on ${creature.name} on screen.")
//
//
//        }
//
//      case None =>
//        println(s"No settings found for creature: ${creature.name}")
//    }
//
//    ((actions, logs), updatedState)
//  }
//



  // Helper function to check if a container has attack runes
  def checkForRunesInContainer(containerName: String, json: JsObject, runeIds: Set[Int]): Boolean = {
    (json \ "containersInfo" \ containerName).asOpt[JsObject].exists { containerInfo =>
      val items = (containerInfo \ "items").asOpt[JsObject].getOrElse(Json.obj())
      items.fields.exists {
        case (_, itemInfo) =>
          val itemId = (itemInfo \ "itemId").asOpt[Int].getOrElse(-1)
          val itemSubType = (itemInfo \ "itemSubType").asOpt[Int].getOrElse(-1)
          runeIds.contains(itemId) && itemSubType == 1
      }
    }
  }

  // Helper function to handle opening a new backpack
  def handleOpenBackpack(containerName: String, json: JsObject, actions: Seq[FakeAction], logs: Seq[Log], updatedState: ProcessorState): ((Seq[FakeAction], Seq[Log]), ProcessorState) = {
    val result = openNewBackpack(containerName, json, actions, logs, updatedState)
    val newActions = result._1._1
    val newLogs = result._1._2
    val newState = result._2.copy(statusOfAttackRune = "verifying")
    ((newActions, newLogs), newState)
  }

  // Helper function to handle removing a backpack
  def handleRemoveBackpack(containerName: String, json: JsObject, actions: Seq[FakeAction], logs: Seq[Log], updatedState: ProcessorState): ((Seq[FakeAction], Seq[Log]), ProcessorState) = {
    val result = removeEmptyBackpack(containerName, json, actions, logs, updatedState)
    val newActions = result._1._1
    val newLogs = result._1._2
    val newState = result._2.copy(statusOfAttackRune = "open_new_backpack")
    ((newActions, newLogs), newState)
  }

  def processSuppliesState(
                              jsonValue: JsValue,
                              settings: UISettings,
                              initialActions: Seq[FakeAction],
                              initialLogs: Seq[Log],
                              currentState: ProcessorState
                            ): ((Seq[FakeAction], Seq[Log]), ProcessorState) = {

    var actions: Seq[FakeAction] = initialActions
    var logs: Seq[Log] = initialLogs
    var updatedState = currentState
    val json = jsonValue.as[JsObject]
    val currentTime = System.currentTimeMillis()
    // Extract rune IDs from the creature list in settings
    val runeIds = settings.autoTargetSettings.creatureList.flatMap(extractRuneIdFromSetting).toSet

    updatedState.statusOfAttackRune match {
      case "verifying" =>
        if (currentTime - updatedState.lastChangeOfstatusOfAttackRune >= updatedState.retryShortDelay) {
          val containerName = updatedState.suppliesContainerToHandle
          logs = logs :+ Log(s"Checking if container $containerName has runes and enough free space...")
          val containsRunes = checkForRunesInContainer(containerName, json, runeIds)
          val freeSpace = (json \ "containersInfo" \ containerName \ "freeSpace").asOpt[Int].getOrElse(0)

          if (containsRunes || freeSpace < 20) {
            // Set the container that needs attention in suppliesContainerToHandle
            logs = logs :+ Log(s"Container $containerName handled succefully.")
            updatedState = updatedState.copy(
              statusOfAttackRune = "ready",
              lastChangeOfstatusOfAttackRune = currentTime,
              suppliesContainerToHandle = "",
            )
          }
        }
        ((actions, logs), updatedState)

      case "open_new_backpack" =>
        // Handle the opening of a new backpack for the container that needs attention

        if (currentTime - updatedState.lastChangeOfstatusOfAttackRune >= updatedState.retryShortDelay) {

          val containerName = updatedState.suppliesContainerToHandle
          val containsRunes = checkForRunesInContainer(containerName, json, runeIds)
          val freeSpace = (json \ "containersInfo" \ containerName \ "freeSpace").asOpt[Int].getOrElse(0)
          val capacity = (json \ "containersInfo" \ containerName \ "capacity").asOpt[Int].getOrElse(0)
          if (freeSpace == capacity) {
            logs = logs :+ Log(s"Runes have finished.")
            val runeId = updatedState.suppliesContainerMap.find {
              case (id, name) => name == containerName
            }.map(_._1) // Get the runeId directly

            updatedState = updatedState.copy(
              suppliesLeftMap = updatedState.suppliesLeftMap.updated(runeId.get, 0),
              statusOfAttackRune = "ready",
            )

          } else {
            // Directly use suppliesContainerToHandle to locate the runeId
            val runeId = updatedState.suppliesContainerMap.find {
              case (id, name) => name == containerName
            }.map(_._1) // Get the runeId directly

            // Calculate how many BPs are left
            val bpsLeft = capacity - freeSpace

            // Update the suppliesLeftMap with the runeId and the calculated BPs left
            updatedState = updatedState.copy(
              suppliesLeftMap = updatedState.suppliesLeftMap.updated(runeId.get, bpsLeft)
            )

            println(s"suppliesLeftMap: ${updatedState.suppliesLeftMap}")

            logs = logs :+ Log(s"Opening new backpack for container $containerName...")
            val result = handleOpenBackpack(containerName, json, actions, logs, updatedState)
            actions = result._1._1
            logs = result._1._2
            updatedState = result._2.copy(
              statusOfAttackRune = "verifying",
              lastChangeOfstatusOfAttackRune = currentTime,
            )
          }
        }
        ((actions, logs), updatedState)

      case "remove_backpack" =>
        // Remove the current backpack and prepare to open a new one if needed
        if (currentTime - updatedState.lastChangeOfstatusOfAttackRune >= updatedState.retryShortDelay) {
          val containerName = updatedState.suppliesContainerToHandle
          logs = logs :+ Log(s"Removing backpack for container $containerName...")
          val result = handleRemoveBackpack(containerName, json, actions, logs, updatedState)
          actions = result._1._1
          logs = result._1._2
          updatedState = result._2.copy(
            statusOfAttackRune = "open_new_backpack",
            lastChangeOfstatusOfAttackRune = currentTime,
          )
        }
        ((actions, logs), updatedState)


      case "not_set" =>
        // Initialize suppliesContainerMap if it's not set
        val suppliesContainerMap = getRuneContainerMapping(json, settings)
        println(s"suppliesContainerMap: ${suppliesContainerMap}")
        if (suppliesContainerMap.nonEmpty) {
          logs = logs :+ Log("Found supplies containers with attack runes. Starting verification.")
          updatedState = updatedState.copy(
            suppliesContainerMap = suppliesContainerMap,
            statusOfAttackRune = "verifying"
          )
        } else {
          logs = logs :+ Log("No attack rune containers found.")
        }
        ((actions, logs), updatedState)


      case "move_back_to_parent_container" =>
        if (currentTime - updatedState.lastChangeOfstatusOfAttackRune >= updatedState.retryShortDelay) {
          val resultNoRunesInBpGoUp = noRunesInBpGoUp(updatedState.suppliesContainerToHandle, json, actions, logs, updatedState)
          actions = resultNoRunesInBpGoUp._1._1
          logs = resultNoRunesInBpGoUp._1._2
          updatedState = resultNoRunesInBpGoUp._2

          updatedState = updatedState.copy(
            statusOfAttackRune = "remove_backpack",
            lastChangeOfstatusOfAttackRune = currentTime,
          )
        }
        ((actions, logs), updatedState)



      case "ready" =>
        // Check each container for free space, and if any need attention, handle them
        updatedState.suppliesContainerMap.foreach { case (runeId, containerName) =>

          val freeSpace = (json \ "containersInfo" \ containerName \ "freeSpace").asOpt[Int].getOrElse(0)
          val capacity = (json \ "containersInfo" \ containerName \ "capacity").asOpt[Int].getOrElse(0)
          println(s"(ready) suppliesLeftMap: ${updatedState.suppliesLeftMap}")

          // Check if runeId has an entry in suppliesLeftMap
          if (!updatedState.suppliesLeftMap.contains(runeId)) {
            // If not, create a position in suppliesLeftMap for this rune with the number 999
            updatedState = updatedState.copy(
              suppliesLeftMap = updatedState.suppliesLeftMap.updated(runeId, 999)
            )
          }

          // Now proceed with checking the free space
          if (freeSpace >= capacity) {
            logs = logs :+ Log(s"Container $containerName does not have enough space.")

            // Check if suppliesLeftMap for this runeId has 0 value
            val bpsLeft = updatedState.suppliesLeftMap(runeId)
            println(s"BpLeft: ${bpsLeft}")
            println(s"state: ${updatedState.statusOfAttackRune}")
            if (bpsLeft == 0) {
              println(s"No runes left for runeId: $runeId")
            } else {
              // If suppliesLeftMap has a value > 0, proceed with handling
              println(s"No runes left for runeId, but there is bp available: $runeId")
              updatedState = updatedState.copy(
                statusOfAttackRune = "move_back_to_parent_container",
                suppliesContainerToHandle = containerName
              )
              return ((actions, logs), updatedState) // Break out and handle only one container per loop
            }
          }
        }

        // If all containers have enough space, remain in the ready state
        logs = logs :+ Log("All containers have enough space. Remaining in ready state.")
        ((actions, logs), updatedState)
    }
  }


  // Function to map each rune ID to its corresponding container name
  def getRuneContainerMapping(json: JsObject, settings: UISettings): Map[Int, String] = {
    // Extract rune IDs from the settings
    val runeIds = settings.autoTargetSettings.creatureList.flatMap(extractRuneIdFromSetting).toSet

    // Create an empty map to store rune ID and corresponding container name
    var runeContainerMap = Map[Int, String]()

    // Iterate over all containers
    (json \ "containersInfo").asOpt[JsObject].foreach { containersInfo =>
      containersInfo.fields.foreach { case (containerName, containerDetails) =>
        val items = (containerDetails \ "items").asOpt[JsObject].getOrElse(Json.obj())

        // Find the rune items in the container and map them to the container name
        items.fields.foreach {
          case (_, itemInfo) =>
            val itemId = (itemInfo \ "itemId").asOpt[Int].getOrElse(-1)
            val itemSubType = (itemInfo \ "itemSubType").asOpt[Int].getOrElse(-1)

            // Check if the item is a rune (match with runeIds and itemSubType)
            if (runeIds.contains(itemId) && itemSubType == 1) {
              runeContainerMap += (itemId -> containerName)  // Map rune ID to container name
            }
        }
      }
    }

    runeContainerMap
  }

  def resupplyAmmo(
                    json: JsValue,
                    updatedState: ProcessorState,
                    containerInfo: JsObject,
                    initialActions: Seq[FakeAction],
                    initialLogs: Seq[Log],
                  ): ((Seq[FakeAction], Seq[Log]), ProcessorState) = {

    var actions: Seq[FakeAction] = initialActions
    var logs: Seq[Log] = initialLogs
    val currentTime = System.currentTimeMillis()
    val foundSlot = containerInfo.fields.view.flatMap {
      case (containerName, containerData) =>
        (containerData \ "items").asOpt[JsObject].flatMap { items =>
          val sortedSlots = items.fields.sortBy(_._1.stripPrefix("slot").toInt)
          sortedSlots.collectFirst {
            case (slotId, slotData) if (slotData \ "itemId").as[Int] == updatedState.ammoId =>
              val screenSlotId = "item" + slotId.drop(4) // Assuming slotId format is "slotN"
              (containerName, screenSlotId)
          }
        }
    }.headOption

    foundSlot match {
      case Some((containerName, screenSlotId)) =>
        println(s"Found ammoId in $containerName at $screenSlotId")
        val screenInfo = (json \ "screenInfo" \ "inventoryPanelLoc").as[JsObject]
        screenInfo.fields.collectFirst {
          case (key, value) if key.contains(containerName) =>
            val (ammoX, ammoY) = ((value \ "contentsPanel" \ screenSlotId \ "x").as[Int], (value \ "contentsPanel" \ screenSlotId \ "y").as[Int])
            println(s"Screen position of $screenSlotId in $key: x = $ammoX, y = $ammoY")

            val slot10ScreenInfo = (json \ "screenInfo" \ "inventoryPanelLoc" \ "inventoryWindow" \ "contentsPanel" \ "slot10").as[JsObject]
            val (slot10X, slot10Y) = ((slot10ScreenInfo \ "x").as[Int], (slot10ScreenInfo \ "y").as[Int])
            println(s"Screen position of slot 10: x = $slot10X, y = $slot10Y")

            val actionsSeq = Seq(
              MouseAction(ammoX, ammoY, "move"),
              MouseAction(ammoX, ammoY, "pressCtrl"),
              MouseAction(ammoX, ammoY, "pressLeft"),
              MouseAction(slot10X, slot10Y, "move"),
              MouseAction(slot10X, slot10Y, "releaseLeft"),
              MouseAction(slot10X, slot10Y, "releaseCtrl")
            )

            if (updatedState.ammoResuplyDelay == 0 || timeToRetry(updatedState.ammoResuplyDelay, updatedState.retryMidDelay)) {
              actions = actions ++ performMouseActionSequance(actionsSeq)
              logs = logs :+ Log("Resupplying ammo")
              updatedState.copy(ammoResuplyDelay = currentTime, ammoCountForNextResupply = scala.util.Random.nextInt(41) + 40)
            } else {
              println(s"Waiting to retry resupplying ammo. Time left: ${(updatedState.retryMidDelay - (currentTime - updatedState.ammoResuplyDelay)) / 1000} seconds")
              updatedState
            }
        }.getOrElse(updatedState)

      case None =>
        println("No slot with specified ammoId found.")

    }
    ((actions, logs), updatedState)
  }

  def getClosestTeamMemberPosition(json: JsValue, blockerPosition: Vec, settings: UISettings): Option[Vec] = {
    // Extract battleInfo from the JSON
    val battleInfo = (json \ "battleInfo").as[Map[String, JsValue]]

    // Extract the list of team members from settings
    val teamMembersList = settings.teamHuntSettings.teamMembersList

    // Debug: Print the team members list
//    println(s"[DEBUG] teamMembersList: $teamMembersList")
//    println(s"[DEBUG] blockerPosition: $blockerPosition")

    // Initialize a variable to store the closest position and the smallest distance
    var closestPosition: Option[Vec] = None
    var smallestDistance: Int = Int.MaxValue

    // Iterate over the battleInfo entries
    battleInfo.foreach { case (key, data) =>
      val name = (data \ "Name").asOpt[String].getOrElse("Unknown")
      val isPlayer = (data \ "IsPlayer").asOpt[Boolean].getOrElse(false)

      // Debug: Print battle info entry
      println(s"[DEBUG] Processing battleInfo entry: Key: $key, Name: $name, IsPlayer: $isPlayer")

      // Check if the name is in the teamMembersList and the entity is a player
      if (teamMembersList.contains(name) && isPlayer) {
        val posX = (data \ "PositionX").as[Int]
        val posY = (data \ "PositionY").as[Int]

        // Calculate Chebyshev distance to the blocker
        val distance = Math.max(
          Math.abs(posX - blockerPosition.x),
          Math.abs(posY - blockerPosition.y)
        )

        // Debug: Print distance calculation
        println(s"[DEBUG] Team member found: $name, Position: ($posX, $posY), Distance: $distance")

        // If this team member is closer, update the closest position and smallest distance
        if (distance < smallestDistance) {
          smallestDistance = distance
          closestPosition = Some(Vec(posX, posY))

          // Debug: Print updated closest position
          println(s"[DEBUG] New closest team member: $name, Position: ($posX, $posY), Distance: $distance")
        }
      } else {
        // Debug: If the team member is not valid, print the reason
        if (!teamMembersList.contains(name)) println(s"[DEBUG] Name $name not in teamMembersList")
        if (!isPlayer) println(s"[DEBUG] Entity $name is not a player")
      }
    }

    // Debug: Print final closest position
    println(s"[DEBUG] Closest team member position: $closestPosition")

    // Return the closest position found
    closestPosition
  }


}

