package processing
import mouse.FakeAction
import play.api.libs.json.Format.GenericFormat
import play.api.libs.json.JsValue
import play.api.libs.json.OFormat.oFormatFromReadsAndOWrites
import play.api.libs.json._
import processing.AutoHeal.{openNewBackpack, removeEmptyBackpack}
import processing.CaveBot.executeWhenNoMonstersOnScreen
import processing.Process.{generateRandomDelay, performMouseActionSequance, timeToRetry}
import userUI.SettingsUtils.UISettings
import utils.consoleColorPrint.{ANSI_GREEN, ANSI_RED, printInColor}
import utils.consoleColorPrint._

import scala.util.{Random, Try}
import scala.util.matching.Regex



object AutoTarget {
  def computeAutoTargetActions(json: JsValue, settings: UISettings, currentState: ProcessorState): ((Seq[FakeAction], Seq[Log]), ProcessorState) = {
//    println("Performing computeAutoTargetActions action.")

    val startTime = System.nanoTime()

    var actions: Seq[FakeAction] = Seq.empty
    var logs: Seq[Log] = Seq.empty
    var updatedState = currentState // Initialize updatedState
    val presentCharLocationZ = (json \ "characterInfo" \ "PositionZ").as[Int]
    val currentTime = System.currentTimeMillis()
    printInColor(ANSI_RED, f"[DEBUG] computeAutoTargetActions process started with status:${updatedState.stateHunting}")


    if (settings.autoTargetSettings.enabled && updatedState.stateHunting == "free" && !updatedState.gmDetected) {

      // resuply ammo
      updatedState.isUsingAmmo match {
        case "not_set" =>
          val ammoIdsList = List(3446, 3447)
          val slot10Info = (json \ "EqInfo" \ "10").as[JsObject]
          val ammoIdOpt = (slot10Info \ "itemId").asOpt[Int]

          ammoIdOpt match {
            case Some(ammoId) if ammoIdsList.contains(ammoId) =>
              if (ammoIdsList.contains(ammoId)) {
                val randomCountResuply = Random.nextInt(41) + 40 // Random number between 40 and 80
                updatedState = updatedState.copy(
                  isUsingAmmo = "true",
                  ammoId = ammoId,
                  ammoCountForNextResupply = randomCountResuply
                )
              } else {
                updatedState = updatedState.copy(isUsingAmmo = "false")
              }
            case _ =>
              println("Nothing in arrow slot...")
              updatedState = updatedState.copy(isUsingAmmo = "false")
          }



        case "false" =>
        // Do nothing and execute further code

        case "true" =>
          val slot10Info = (json \ "EqInfo" \ "10").as[JsObject]
          val ammoCount = (slot10Info \ "itemCount").as[Int]
          println(s"ammoCountForNextResupply: ${updatedState.ammoCountForNextResupply}")
          if (ammoCount < updatedState.ammoCountForNextResupply) {
            // Finding ammoId in bps slots
            val containerInfo = (json \ "containersInfo").as[JsObject]

            val foundSlot = containerInfo.fields.view.flatMap {
              case (containerName, containerData) =>
                val items = (containerData \ "items").as[JsObject]
                // Sorting keys to ensure we start checking from slot1 onwards
                val sortedSlots = items.fields.sortBy(_._1.stripPrefix("slot").toInt)
                sortedSlots.collectFirst {
                  case (slotId, slotData) if (slotData \ "itemId").as[Int] == updatedState.ammoId =>
                    // Mapping slotId to match itemN format in screenInfo
                    val screenSlotId = "item" + slotId.drop(4) // Assuming slotId format is "slotN"
                    (containerName, screenSlotId)
                }
            }.headOption

            foundSlot match {
              case Some((containerName, screenSlotId)) =>
                println(s"Found ammoId in $containerName at $screenSlotId")
                // Extracting screen x and y position based on container name and slot id from inventoryPanelLoc
                val screenInfo = (json \ "screenInfo" \ "inventoryPanelLoc").as[JsObject]
                screenInfo.fields.collectFirst {
                  case (key, value) if key.contains(containerName) =>
                    val (ammoX, ammoY) = ((value \ "contentsPanel" \ screenSlotId \ "x").as[Int], (value \ "contentsPanel" \ screenSlotId \ "y").as[Int])
                    println(s"Screen position of $screenSlotId in $key: x = $ammoX, y = $ammoY")


                    // Retrieve position of slot10 from a different location in screenInfo
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

                    // Check and update retry status based on time elapsed
                    if (updatedState.ammoResuplyDelay == 0 || timeToRetry(updatedState.ammoResuplyDelay, updatedState.retryMidDelay)) {
                      actions = actions ++ performMouseActionSequance(actionsSeq)
                      logs = logs :+ Log("Resuppling ammo")
                      updatedState = updatedState.copy(ammoResuplyDelay = currentTime) // Set to current time after action
                    } else {
                      println(s"Waiting to retry resupling ammo. Time left: ${(updatedState.retryMidDelay - (currentTime - updatedState.ammoResuplyDelay)) / 1000} seconds")
                    }


                    // Updating state with new ammo count
                    updatedState = updatedState.copy(
                      ammoCountForNextResupply = scala.util.Random.nextInt(41) + 40 // Recalculate after ammoCount falls below randomCountResupply
                    )
                }


              case None =>
                println("No slot with specified ammoId found.")
            }


          }


        case _ => // handle unexpected cases or errors
      }
      // resuply ammo end




      if (updatedState.statusOfAttackRune == "verifying") {
        // Step 2: Verify if the newly opened backpack contains UH runes
        logs = logs :+ Log(s"Verifying if container ${updatedState.attackRuneContainerName} contains attack runes...")

        // Check for UH runes in the new backpack
        (json \ "containersInfo" \ updatedState.attackRuneContainerName).asOpt[JsObject].foreach { containerInfo =>
          val items = (containerInfo \ "items").asOpt[JsObject].getOrElse(Json.obj())

          // Extract rune IDs from the creature list (you should ensure that runeIds is accessible in this scope)
          val runeIds = settings.autoTargetSettings.creatureList.flatMap(extractRuneIdFromSetting).toSet

          val containsUHRunes = items.fields.exists {
            case (_, itemInfo) =>
              val itemId = (itemInfo \ "itemId").asOpt[Int].getOrElse(-1)
              val itemSubType = (itemInfo \ "itemSubType").asOpt[Int].getOrElse(-1)
              // Check if itemId is in the runeIds set and if itemSubType matches 1
              runeIds.contains(itemId) && itemSubType == 1
          }

          // If UH runes are found, set the status to 'ready'
          if (containsUHRunes) {
            logs = logs :+ Log(s"Runes found in ${updatedState.attackRuneContainerName}.")
            updatedState = updatedState.copy(statusOfAttackRune = "ready")
          } else {
            logs = logs :+ Log(s"No Runes found in ${updatedState.attackRuneContainerName}.")
          }
        }
      }


      if (updatedState.statusOfAttackRune == "open_new_backpack") {
        // Step 1: Open the new backpack
        val result = openNewBackpack(updatedState.attackRuneContainerName, json, actions, logs, updatedState)
        actions ++= result._1._1
        logs ++= result._1._2
        updatedState = result._2

        // Change status to 'verifying' to delay UH runes check to the next loop
        updatedState = updatedState.copy(statusOfAttackRune = "verifying")
      }


      if (updatedState.statusOfAttackRune == "remove_backpack") {
        val result = removeEmptyBackpack(updatedState.attackRuneContainerName, json, actions, logs, updatedState)
        actions ++= result._1._1
        logs ++= result._1._2
        updatedState = result._2
      }

      // Assuming the correct structure of the JSON object and that the JSON parsing is appropriate:
      if (updatedState.attackRuneContainerName == "not_set") {
//        logs = logs :+ Log("Checking for attack Rune container...")

        // Extract rune IDs from the creature list
        val creatureList = settings.autoTargetSettings.creatureList
//        println(s"Creature List: $creatureList")
        val runeIds = creatureList.flatMap(extractRuneIdFromSetting).toSet
//        println(s"Extracted Rune IDs: $runeIds")

        // Accessing and checking containersInfo
        (json \ "containersInfo").asOpt[JsObject].foreach { containersInfo =>
          val attackRuneContainerName = containersInfo.fields.collectFirst {
            case (containerName, containerDetails) if
              (containerDetails \ "items").asOpt[JsObject].exists { items =>
                items.fields.exists {
                  case (_, itemDetails) =>
                    runeIds.contains((itemDetails \ "itemId").asOpt[Int].getOrElse(-1))
                }
              } => containerName
          }

          attackRuneContainerName match {
            case Some(containerName) =>
              logs = logs :+ Log(s"Found attack Rune in $containerName.")
              updatedState = updatedState.copy(attackRuneContainerName = containerName)
            case None =>
//              logs = logs :+ Log("Attack Rune not found in any container.")
          }
        }
      }
      // setting attackRuneContainerEnd


      val presentCharLocationZ = (json \ "characterInfo" \ "PositionZ").as[Int]

      // new logic

      // detect monster
        // case1 no attack, because wrong floor
        // case2 check if monsters if pathable (list of monsters - sort by danger, hp)
              //
              // check if aleardy attacking (maybe switch is needed)
                //
                //no attacking -> is mark on battle needed
                    //no -> is shootable
                        //yes -> check monster and shoot rune
                        //no -> pass
                    //yes -> mark on battle

//      chosenTargetId: Int = 0
//      chosenTargetName: String = ""
//      lastChosenTargetPos: (Int, Int, Int) = (0, 0, 0)

      if ((settings.caveBotSettings.enabled &&
        updatedState.caveBotLevelsList.contains(presentCharLocationZ) &&
        settings.autoTargetSettings.enabled) ||
        (!settings.caveBotSettings.enabled && settings.autoTargetSettings.enabled)) {
        println("Start autotarget")

        val battleTargets: Seq[(Long, String, Boolean, Boolean)] = (json \ "battleInfo").as[JsObject].values.flatMap { creature =>
          val id = (creature \ "Id").as[Long]
          val name = (creature \ "Name").as[String]
          println("Gate1")
          val isMonster = (creature \ "IsMonster").as[Boolean]
          println("Gate2")
          val isPlayer = (creature \ "IsPlayer").as[Boolean]

          // Check if attacking players is allowed or if the creature is not a player
          if (updatedState.attackPlayers || isPlayer == "false") {
            Some((id, name, isMonster, isPlayer))
          } else {
            None // Exclude player creatures if updatedState.attackPlayers is false
          }
        }.toSeq

        if (!(updatedState.chosenTargetId == 0)) {
          // target not chosen - select target

          // Use the existing creatureList from settings
          val targetMonstersJsons = transformToJSON(settings.autoTargetSettings.creatureList)

          // Checking if 'All' creatures are targeted
          val targetAllCreatures = targetMonstersJsons.exists { json =>
            (json \ "name").as[String].equalsIgnoreCase("All")
          }

          // Now use Reads from the Creature object
          val creatureDangerMap = targetMonstersJsons.map { json =>
            val creature = Json.parse(json.toString()).as[Creature](Creature.reads)
            (creature.name, creature.danger)
          }.toMap

          // Filter and sort based on the danger level, sorting by descending danger
          var sortedMonsters = if (targetAllCreatures) {
            battleTargets // If targeting all, skip the danger level filtering
          } else {
            battleTargets
              .filter { case (_, name, _, _) => creatureDangerMap.contains(name) }
              .sortBy { case (_, name, _ ,_) => -creatureDangerMap(name) } // Descending order of danger
          }

          // target on battle or on the screen
          


          // Further filtering based on IDs or other conditions should be adjusted based on whether "All" is targeted
          sortedMonsters = if (targetAllCreatures) {
            sortedMonsters.filter { case (id, _, _ , _) => id >= 5 }
          } else {
            sortedMonsters
              .filter { case (id, _, _ , _) => id >= 5 }
              .sortBy { case (id, _, _ , _) =>
                val battleCreaturePosition = (json \ "screenInfo" \ "battlePanelLoc" \ id.toString).asOpt[JsObject]
                battleCreaturePosition.flatMap(pos => (pos \ "PosY").asOpt[Int]).getOrElse(Int.MaxValue)
              }
          }

          var topFourMonsters = sortedMonsters.take(4)
            .sortBy { case (_, name, _ , _) => -creatureDangerMap.getOrElse(name, 0) }




        } else {
          // target selected - consider chaning target, check if freezed or proceed
        }



        // is targetCHosen?
            // yes -> should target change?
                  // no -> proceed
                  // yes -> switch target
            // no

      }


      if ((settings.caveBotSettings.enabled && updatedState.caveBotLevelsList.contains(presentCharLocationZ)) || (!(settings.caveBotSettings.enabled) && settings.autoTargetSettings.enabled)) {
        println(s"AutoTarget is ON, status ${updatedState.stateHunting}")
        (json \ "battleInfo").validate[Map[String, JsValue]] match {
          case JsSuccess(battleInfo, _) => 1
            println("gate3")
            val hasMonsters = battleInfo.exists { case (_, creature) =>
              (creature \ "IsMonster").asOpt[Boolean].getOrElse(false)
            }
            println("gate4")
            if (hasMonsters) {
              val result = executeWhenMonstersOnScreen(json, settings, updatedState, actions, logs)
              actions = result._1._1
              logs = result._1._2
              updatedState = result._2
            } else {
              //            println("Skipping actions due to absence of monsters.")
            }
          case JsError(_) =>
          //          println("battleInfo is null or invalid

        }
      }
    } else if (settings.autoTargetSettings.enabled && updatedState.stateHunting == "attacking") {
      // Safely attempt to extract the attacked creature's target ID
      println(s"AutoTarget is ON, (ELSE IF) status ${updatedState.stateHunting}")


      (json \ "attackInfo" \ "Id").asOpt[Int] match {
        case Some(attackedCreatureTarget) =>
          println(s"Targeting creature id: $attackedCreatureTarget")
          val tempTargetFreezeHealthPoints = (json \ "attackInfo" \ "HealthPercent").as[Int]


          // chase target if requested in UI settings
          val currentChaseMode = (json \ "characterInfo" \ "ChaseMode").as[Int]
          val attackInfoPart = (json \ "attackInfo")
          val tempTargetName = (json \ "attackInfo" \ "Name").as[String]


          // Print out the entire settings list for verification
          println("Auto Target Settings List:")
          settings.autoTargetSettings.creatureList.foreach(println)

          // Attempt to find specific settings for the creature or fallback to 'All'
          val targetCreatureSettings = settings.autoTargetSettings.creatureList.find(_.toLowerCase.contains(tempTargetName.toLowerCase))
            .orElse(settings.autoTargetSettings.creatureList.find(_.contains("All")))

          println(s"Settings for target $tempTargetName: $targetCreatureSettings")

          // Determine if chasing is enabled for this creature
          val chaseEnabled = targetCreatureSettings match {
            case Some(settingsString) =>
              // Split the string by comma and refine checking for the chase status
              val chaseSetting = settingsString.split(",").map(_.trim).find(_.startsWith("Chase:"))
              chaseSetting match {
                case Some(setting) => setting.split(":")(1).trim.equalsIgnoreCase("yes")
                case None =>
                  println("Chase setting not found, defaulting to false.")
                  false
              }
            case None =>
              println("No settings found for target, defaulting to settings for 'All'.")
              false
          }

          // Log current chase mode and whether chasing is enabled
          println(s"currentChaseMode: $currentChaseMode")
          println(s"chaseEnabled: $chaseEnabled")


          // change chase if necessary
          if (!(currentChaseMode == 1) && chaseEnabled) {
            println("inside chase change")
            if (updatedState.chaseSwitchStatus >= updatedState.retryAttemptsMid) {

              updatedState = updatedState.copy(chaseSwitchStatus = 0)

              if (currentChaseMode == 0) {
                printInColor(ANSI_RED, "[DEBUG] Changing the chase mode.")
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
          //end chaange chase




          // start anti-freeze
          if (tempTargetFreezeHealthPoints < updatedState.targetFreezeHealthPoints) {
            updatedState = updatedState.copy(targetFreezeHealthStatus = 0)

          } else if (updatedState.targetFreezeHealthStatus >= updatedState.retryAttemptsLong && updatedState.targetFreezeHealthPoints == tempTargetFreezeHealthPoints) {
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
          // end anti-freeze


          val targetName = (json \ "attackInfo" \ "Name").asOpt[String].getOrElse("Unknown")
          printInColor(ANSI_RED, f"[DEBUG] computeAutoTargetActions process started. Status:${updatedState.stateHunting}, attacking: $targetName")
          val xPos = (json \ "attackInfo" \ "Position" \ "x").asOpt[Int].getOrElse(0)
          val yPos = (json \ "attackInfo" \ "Position" \ "y").asOpt[Int].getOrElse(0)
          val zPos = (json \ "attackInfo" \ "Position" \ "z").asOpt[Int].getOrElse(0)

          updatedState = updatedState.copy(lastTargetName = targetName)
          updatedState = updatedState.copy(targetFreezeHealthPoints = tempTargetFreezeHealthPoints)
          updatedState = updatedState.copy(lastTargetPos = (xPos, yPos, zPos))
          updatedState = updatedState.copy(creatureTarget = attackedCreatureTarget)


          println(settings.autoTargetSettings.creatureList)
          // Fire runes at target
          val targetSettings = settings.autoTargetSettings.creatureList.find(_.contains(targetName))

          // Check if specific settings for the target exist, otherwise use "All" settings if available
          val effectiveSettings = targetSettings.orElse {
            val allSettings = settings.autoTargetSettings.creatureList.find(_.contains("All"))
            if (allSettings.isEmpty) println(s"No specific or 'All' settings found for $targetName.")
            allSettings
          }

          println(effectiveSettings)
          effectiveSettings match {
            case Some(creatureSettings) =>
              val useRune = creatureSettings.contains("Use Rune: Yes")
              extractRuneID(creatureSettings) match {
                case Some(runeID) =>
                  println(s"Extracted Rune ID: $runeID")
                  val currentTime = System.currentTimeMillis()
                  if (useRune && currentTime - updatedState.lastRuneUseTime > (updatedState.runeUseCooldown + updatedState.runeUseRandomness)) {
                    val runeAvailability = (1 to 4).flatMap { slot =>
                      (json \ "containersInfo" \ updatedState.attackRuneContainerName \ "items" \ s"slot$slot" \ "itemId").asOpt[Int].map(itemId => (itemId, slot))
                    }.find(_._1 == runeID)

                    runeAvailability match {
                      case Some((_, slot)) =>
                        val inventoryPanelLoc = (json \ "screenInfo" \ "inventoryPanelLoc").as[JsObject]
                        val containerKey = inventoryPanelLoc.keys.find(_.contains(updatedState.attackRuneContainerName)).getOrElse("")
                        val contentsPath = inventoryPanelLoc \ containerKey \ "contentsPanel"
                        val runeScreenPos = (contentsPath \ s"item$slot").asOpt[JsObject].map { item =>
                          (item \ "x").asOpt[Int].getOrElse(-1) -> (item \ "y").asOpt[Int].getOrElse(-1)
                        }.getOrElse((-1, -1))

                        val monsterPos = (json \ "screenInfo" \ "battlePanelLoc" \ s"$attackedCreatureTarget").asOpt[JsObject].map { monster =>
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
                          val newRandomDelay = generateRandomDelay(updatedState.runeUseTimeRange)
                          updatedState = updatedState.copy(
                            lastRuneUseTime = currentTime,
                            runeUseRandomness = newRandomDelay
                          )
                          println("Actions executed: Monster was in a valid position.")
                        } else {
                          println("No actions executed: Monster position indicates it is no longer on the screen or too close to the edge.")
                        }
                      case None =>
                        println("Rune is not available in the first four slots of the specified backpack.")
                    }
                  } else {
                    println("Rune cannot be used yet due to cooldown.")
                  }
                case None =>
                  println("Invalid or missing rune ID in settings.")
              }
            case None =>
              println(s"No settings found for $targetName.")
          }





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

        println(s"Creature $targetName ($attackedCreatureTarget) in game position $xPos, $yPos, $zPos")


        updatedState = updatedState.copy(
          lastTargetName = targetName,
          lastTargetPos = (xPos, yPos, zPos),
          creatureTarget = attackedCreatureTarget
        )


      case None =>

        if (updatedState.stateHunting == "free") {

          updatedState = updatedState.copy(creatureTarget = 0)
          updatedState = updatedState.copy(alreadyLootedIds = List(), lootingStatus=0,extraWidowLootStatus=0, lootingRestryStatus=0, targetFreezeHealthPoints=0)

          // Extract monsters, their IDs, and Names
          val monsters: Seq[(Long, String)] = (json \ "battleInfo").as[JsObject].values.flatMap { creature =>
            println("gate5")
            val isMonster = (creature \ "IsMonster").as[Boolean]
            println("gate6")
            if (isMonster) {
              Some((creature \ "Id").as[Long], (creature \ "Name").as[String])
            } else None
          }.toSeq

          // Use the existing creatureList from settings
          val jsonResult = transformToJSON(settings.autoTargetSettings.creatureList)
//          println(Json.prettyPrint(Json.toJson(jsonResult)))


          // Checking if 'All' creatures are targeted
          val targetAllCreatures = jsonResult.exists { json =>
            (json \ "name").as[String].equalsIgnoreCase("All")
          }

          // Now use Reads from the Creature object
          val creatureDangerMap = jsonResult.map { json =>
            val creature = Json.parse(json.toString()).as[Creature](Creature.reads)
            (creature.name, creature.danger)
          }.toMap

          // create a list of monsters to be looted
          if (updatedState.monstersListToLoot.isEmpty) {
            val monstersWithLoot = if (targetAllCreatures) {
              // Take all monster names if targeting "All" and convert to List
              monsters.map(_._2).toList
            } else {
              // Parse JSON to List of Creature objects and filter to get names of creatures with loot
              jsonResult.flatMap { json =>
                Json.parse(json.toString()).validate[Creature] match {
                  case JsSuccess(creature, _) if creature.loot => Some(creature.name)
                  case _ => None
                }
              }.toList // Ensure the result is a List
            }
            // Assuming updatedState is being updated within a case class or similar context
            updatedState = updatedState.copy(monstersListToLoot = monstersWithLoot)
          }


          // Filter and sort based on the danger level, sorting by descending danger
          var sortedMonsters = if (targetAllCreatures) {
            monsters // If targeting all, skip the danger level filtering
          } else {
            monsters
              .filter { case (_, name) => creatureDangerMap.contains(name) }
              .sortBy { case (_, name) => -creatureDangerMap(name) } // Descending order of danger
          }

          // Further filtering based on IDs or other conditions should be adjusted based on whether "All" is targeted
          sortedMonsters = if (targetAllCreatures) {
            sortedMonsters.filter { case (id, _) => id >= 5 }
          } else {
            sortedMonsters
              .filter { case (id, _) => id >= 5 }
              .sortBy { case (id, _) =>
                val battleCreaturePosition = (json \ "screenInfo" \ "battlePanelLoc" \ id.toString).asOpt[JsObject]
                battleCreaturePosition.flatMap(pos => (pos \ "PosY").asOpt[Int]).getOrElse(Int.MaxValue)
              }
          }


          var topFourMonsters = sortedMonsters.take(4)
            .sortBy { case (_, name) => -creatureDangerMap.getOrElse(name, 0) }

          printInColor(ANSI_BLUE, f"[DEBUG] Before reshuffling monster list: ${updatedState.targetFreezeHealthStatus} / ${updatedState.retryAttemptsLong}")

          if (updatedState.targetFreezeHealthStatus >= updatedState.retryAttemptsLong) {
            println(s"[DEBUG] Pre Reshuffling monster list: $topFourMonsters")


            // Remove monster with targetFreezeCreatureId from the list
            val filteredMonsters = topFourMonsters.filter { case (id, _) => id != updatedState.targetFreezeCreatureId }
            if (filteredMonsters.isEmpty) {
              println("[DEBUG] No monsters left after removing the target freeze creature.")
            }

            // Shuffle the filtered list and then sort by descending danger
            val shuffledMonsters = scala.util.Random.shuffle(filteredMonsters)
              .sortBy { case (_, name) => -creatureDangerMap.getOrElse(name, 0) } // Safe access with default if key not found


            //            // Remove monster with targetFreezeCreatureId from the list
//            val filteredMonsters = topFourMonsters.filter { case (id, _) => id != updatedState.targetFreezeCreatureId }
//
//
//            // Shuffle the filtered list
//            val shuffledMonsters = scala.util.Random.shuffle(filteredMonsters)
//              .sortBy { case (_, name) => -creatureDangerMap(name) }

            println(s"[DEBUG] After Reshuffling monster list: $shuffledMonsters")

            // Update topFourMonsters with the shuffled list
            topFourMonsters = shuffledMonsters
            updatedState = updatedState.copy(targetFreezeHealthStatus = 0)
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


    val endTime = System.nanoTime()
    val duration = (endTime - startTime) / 1e9d
//    println(f"[INFO] Processing executeWhenNoMonstersOnScreen took $duration%.6f seconds")
    ((actions, logs), updatedState)
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

}

