package processing


import play.api.libs.json.{JsValue, Json}
import userUI.SettingsUtils
import userUI.SettingsUtils.UISettings
import mouse.{ActionCompleted, ActionTypes, FakeAction, ItemInfo, Mouse, MouseMoveCommand, MouseMovementSettings}
import play.api.libs.json.{JsNumber, JsObject, JsValue, Json}
import processing.Process.findItemInContainerSlot14
import utils.consoleColorPrint._

import scala.collection.immutable.Seq

object AutoHeal {

  def computeHealingActions(json: JsValue, settings: SettingsUtils.UISettings, currentState: ProcessorState): ((Seq[FakeAction], Seq[Log]), ProcessorState) = {
    val startTime = System.nanoTime()
    var actions: Seq[FakeAction] = Seq()
    var logs: Seq[Log] = Seq()
    var updatedState = currentState
    val currentTime = System.currentTimeMillis()

    if (settings.healingSettings.enabled) {

      if (updatedState.statusOfRuneAutoheal == "verifying") {
        // Step 2: Verify if the newly opened backpack contains UH runes
        logs = logs :+ Log(s"Verifying if container ${updatedState.uhRuneContainerName} contains UH runes...")

        // Check for UH runes in the new backpack
        (json \ "containersInfo" \ updatedState.uhRuneContainerName).asOpt[JsObject].foreach { containerInfo =>
          val items = (containerInfo \ "items").asOpt[JsObject].getOrElse(Json.obj())

          val containsUHRunes = items.fields.exists {
            case (_, itemInfo) =>
              val itemId = (itemInfo \ "itemId").asOpt[Int].getOrElse(-1)
              val itemSubType = (itemInfo \ "itemSubType").asOpt[Int].getOrElse(-1)
              itemId == 3160 && itemSubType == 1
          }

          // If UH runes are found, set the status to 'ready'
          if (containsUHRunes) {
            logs = logs :+ Log(s"UH Runes found in ${updatedState.uhRuneContainerName}.")
            updatedState = updatedState.copy(statusOfRuneAutoheal = "ready")
          } else {
            logs = logs :+ Log(s"No UH Runes found in ${updatedState.uhRuneContainerName}.")
          }
        }
      }

      if (updatedState.statusOfRuneAutoheal == "open_new_backpack") {
        // Step 1: Open the new backpack
        val result = openNewBackpack(updatedState.uhRuneContainerName, json, actions, logs, updatedState)
        actions = result._1._1
        logs = result._1._2
        updatedState = result._2
        // Change status to 'verifying' to delay UH runes check to the next loop
        updatedState = updatedState.copy(statusOfRuneAutoheal = "verifying")
      }


      if (updatedState.statusOfRuneAutoheal == "remove_backpack") {
        val result = removeEmptyBackpack(updatedState.uhRuneContainerName, json, actions, logs, updatedState)
        actions = result._1._1
        logs = result._1._2
        updatedState = result._2
        updatedState = updatedState.copy(statusOfRuneAutoheal = "open_new_backpack")
      }

      if (updatedState.uhRuneContainerName == "not_set") {
        logs = logs :+ Log(s"Checking for UH Rune container..")
        val containersInfoOpt = (json \ "containersInfo").asOpt[JsObject]

        containersInfoOpt.foreach { containersInfo =>
          val uhRuneContainer = containersInfo.fields.collectFirst {
            case (containerName, containerDetails) if (containerDetails \ "items").asOpt[JsObject].exists(_.values.exists(item =>
              (item \ "itemId").asOpt[Int].contains(3160))) => containerName
          }

          updatedState = uhRuneContainer match {
            case Some(containerName) =>
              logs = logs :+ Log(s"Found UH Rune in $containerName.")
              updatedState = updatedState.copy(uhRuneContainerName = containerName, statusOfRuneAutoheal = "ready")
              updatedState
            case None =>
              logs = logs :+ Log("UH Rune not found in any container.")
              updatedState // No change if uhRune is not found
          }
        }
      }

      if (updatedState.uhRuneContainerName != "not_set" && updatedState.statusOfRuneAutoheal == "ready") {
        logs = logs :+ Log(s"UH Rune container set to ${updatedState.uhRuneContainerName}. Checking for free space and parent...")
        (json \ "containersInfo" \ updatedState.uhRuneContainerName).asOpt[JsObject].foreach { containerInfo =>
          val freeSpace = (containerInfo \ "freeSpace").asOpt[Int]
          val hasParent = (containerInfo \ "hasParent").asOpt[Boolean]


          if (freeSpace.contains(20) && hasParent.contains(true)) {
            val result = noRunesInBpGoUp(updatedState.uhRuneContainerName, json, actions, logs, updatedState)
            actions = result._1._1
            logs = result._1._2
            updatedState = result._2
            updatedState = updatedState.copy(statusOfRuneAutoheal = "remove_backpack")
          }

        }
      }


//    } else if (settings.healingSettings.spellsHeal.length > 1 &&
//      settings.healingSettings.spellsHeal(1).strongHealHealth > 0 &&
//      health <= settings.healingSettings.spellsHeal(1).strongHealHealth &&
//      mana >= settings.healingSettings.spellsHeal(1).strongHealMana) {
//      println(s"settings.healingSettings.spellsHeal.length: ${settings.healingSettings.spellsHeal.length}")
//
//      if (settings.healingSettings.spellsHeal.nonEmpty) {
//        println(s"settings.healingSettings.spellsHeal.head.lightHealSpell: ${settings.healingSettings.spellsHeal.head.lightHealSpell}")
//        println(s"settings.healingSettings.spellsHeal.head.lightHealSpell.length: ${settings.healingSettings.spellsHeal.head.lightHealSpell.length}")
//        println(s"settings.healingSettings.spellsHeal.head.lightHealHealth: ${settings.healingSettings.spellsHeal.head.lightHealHealth}")
//        println(s"settings.healingSettings.spellsHeal.head.lightHealMana: ${settings.healingSettings.spellsHeal.head.lightHealMana}")
//
//
//      }

//      else if (settings.healingSettings.spellsHeal.head.lightHealSpell.length > 1 &&
//        settings.healingSettings.spellsHeal.head.lightHealHealth > 0 &&
//        health <= settings.healingSettings.spellsHeal.head.lightHealHealth &&
//        mana >= settings.healingSettings.spellsHeal.head.lightHealMana) {
//      else if (settings.healingSettings.spellsHeal.head.lightHealSpell.length > 1 &&
//        settings.healingSettings.spellsHeal.head.lightHealHealth > 0 &&
//        health <= settings.healingSettings.spellsHeal.head.lightHealHealth &&
//        mana >= settings.healingSettings.spellsHeal.head.lightHealMana) {


//      if (settings.healingSettings.spellsHeal.length > 1) {
//        val strongHeal = settings.healingSettings.spellsHeal(0)
//        println(s"settings.healingSettings.spellsHeal(0).strongHealHealth: ${strongHeal.strongHealHealth}")
//        println(s"settings.healingSettings.spellsHeal(0).strongHealMana: ${strongHeal.strongHealMana}")
//      } else {
//        println("No spell found at index 1 in spellsHeal list.")
//      }


//      println(s"updatedState.statusOfRuneAutoheal: ${updatedState.statusOfRuneAutoheal} || Should be ready")
//      println(s"updatedState.stateHealingWithRune: ${updatedState.stateHealingWithRune} || Should be free")
      if (((currentState.currentTime - currentState.lastHealingTime) >= updatedState.healingSpellCooldown) && (updatedState.statusOfRuneAutoheal == "ready") && (updatedState.stateHealingWithRune == "free")) {
//        println(s"Inside healing function")
        val health = (json \ "characterInfo" \ "Health").as[Int]
        val mana = (json \ "characterInfo" \ "Mana").as[Int]
//        println(s"health: ${health}")
//        println(s"mana: ${mana}")

        val friend1HealthPercentage = if (
          settings.healingSettings.friendsHealSettings.head.friend1HealSpell.length > 1 &&
            settings.healingSettings.friendsHealSettings.head.friend1Name.length > 1 &&
            settings.healingSettings.friendsHealSettings.head.friend1HealHealth > 0
        ) {
          (json \ "spyLevelInfo").as[JsObject].value.collectFirst {
            case (_, playerInfo) if (playerInfo \ "Name").as[String] == settings.healingSettings.friendsHealSettings.head.friend1Name => (playerInfo \ "HealthPercent").as[Int]
          }.getOrElse(100)
        } else {
          100
        }

        val friend2HealthPercentage = if (
          settings.healingSettings.friendsHealSettings.head.friend2HealSpell.length > 1 &&
            settings.healingSettings.friendsHealSettings.head.friend2Name.length > 1 &&
            settings.healingSettings.friendsHealSettings.head.friend2HealHealth > 0
        ) {
          (json \ "spyLevelInfo").as[JsObject].value.collectFirst {
            case (_, playerInfo) if (playerInfo \ "Name").as[String] == settings.healingSettings.friendsHealSettings.head.friend2Name => (playerInfo \ "HealthPercent").as[Int]
          }.getOrElse(100)
        } else {
          100
        }

        val friend3HealthPercentage = if (
          settings.healingSettings.friendsHealSettings.head.friend3HealSpell.length > 1 &&
            settings.healingSettings.friendsHealSettings.head.friend3Name.length > 1 &&
            settings.healingSettings.friendsHealSettings.head.friend3HealHealth > 0
        ) {
          (json \ "spyLevelInfo").as[JsObject].value.collectFirst {
            case (_, playerInfo) if (playerInfo \ "Name").as[String] == settings.healingSettings.friendsHealSettings.head.friend3Name => (playerInfo \ "HealthPercent").as[Int]
          }.getOrElse(100)
        } else {
          100
        }

        // UH RUNE 3160
        if (settings.healingSettings.uhHealHealth > 0 && health <= settings.healingSettings.uhHealHealth && mana <= settings.healingSettings.uhHealMana) {
          logs = logs :+ Log("I need to use UH!")
          if (settings.mouseMovements) {
            logs = logs :+ Log("use UH with mouse")
            findItemInContainerSlot14(json, updatedState, 3160, 1).foreach { runePosition =>
              val runeX = (runePosition \ "x").as[Int]
              val runeY = (runePosition \ "y").as[Int]

              // Extracting target position from the mapPanelLoc in the JSON
              val mapTarget = (json \ "screenInfo" \ "mapPanelLoc" \ "8x6").as[JsObject]
              val targetX = (mapTarget \ "x").as[Int]
              val targetY = (mapTarget \ "y").as[Int]

              if (updatedState.healingRestryStatus  == 0) {
                printInColor(ANSI_RED, f"[DEBUG] HEAL")

                val actionsSeq = Seq(
                  MouseAction(runeX, runeY, "move"),
                  MouseAction(runeX, runeY, "pressRight"), // Right-click on the runemichal
                  MouseAction(runeX, runeY, "releaseRight"), // Release right-click on the rune
                  MouseAction(targetX, targetY, "move"), // Move to target position
                  MouseAction(targetX, targetY, "pressLeft"), // Press left at target position
                  MouseAction(targetX, targetY, "releaseLeft") // Release left at target position
                )
                // Update the last healing time right after scheduling the action
                updatedState = updatedState.copy(lastHealingTime = currentState.currentTime, stateHealingWithRune = "healing")
                actions = actions :+ FakeAction("useMouse", Some(ItemInfo(3160, None)), Some(MouseActions(actionsSeq)))
//                logs = logs :+ Log(s"Using item 3160 at position ($runeX, $runeY) - Actions: $actionsSeq")

                updatedState = updatedState.copy(healingRestryStatus = updatedState.healingRestryStatus + 1)
              } else if (updatedState.healingRestryStatus < updatedState.healingRetryAttempts) {
                printInColor(ANSI_RED, f"[DEBUG] Refrain from healing. Loop without action (Attempt ${updatedState.healingRestryStatus + 1})")
                updatedState = updatedState.copy(healingRestryStatus = updatedState.healingRestryStatus + 1)
              } else if (updatedState.healingRestryStatus >= updatedState.healingRetryAttempts) {
                printInColor(ANSI_RED, f"[DEBUG] Next loop, heal will be available. Reseting healingRestryStatus. (Attempt ${updatedState.healingRestryStatus + 1})")
                updatedState = updatedState.copy(healingRestryStatus = 0)
              }



            }
          } else {
            logs = logs :+ Log("use UH with function")
            actions = actions :+ FakeAction("useOnYourselfFunction", Some(ItemInfo(3160, None)), None)
            // Update the last healing time when using function as well
            updatedState = updatedState.copy(lastHealingTime = currentState.currentTime)
          }
        }

        // IH RUNE 3152
        else if (settings.healingSettings.ihHealHealth > 0 && health <= settings.healingSettings.ihHealHealth && mana <= settings.healingSettings.ihHealMana) {
          logs = logs :+ Log("I need to use IH!")
          if (settings.mouseMovements) {
            logs = logs :+ Log("use IH with mouse")
            findItemInContainerSlot14(json, updatedState, 3152, 1).foreach { runePosition =>
              val runeX = (runePosition \ "x").as[Int]
              val runeY = (runePosition \ "y").as[Int]

              // Extracting target position from the mapPanelLoc in the JSON
              val mapTarget = (json \ "screenInfo" \ "mapPanelLoc" \ "8x6").as[JsObject]
              val targetX = (mapTarget \ "x").as[Int]
              val targetY = (mapTarget \ "y").as[Int]

              val actionsSeq = Seq(
                MouseAction(runeX, runeY, "move"),
                MouseAction(runeX, runeY, "pressRight"), // Right-click on the rune
                MouseAction(runeX, runeY, "releaseRight"), // Release right-click on the rune
                MouseAction(targetX, targetY, "move"), // Move to target position
                MouseAction(targetX, targetY, "pressLeft"), // Press left at target position
                MouseAction(targetX, targetY, "releaseLeft") // Release left at target position
              )
              actions = actions :+ FakeAction("useMouse", Some(ItemInfo(3152, None)), Some(MouseActions(actionsSeq)))
              logs = logs :+ Log(s"Using item 3152 at position ($runeX, $runeY) - Actions: $actionsSeq")

            }
          } else {
            logs = logs :+ Log("use IH with function")
            actions = actions :+ FakeAction("useOnYourselfFunction", Some(ItemInfo(3152, None)), None)
          }
        }

        // HP Potion 2874, 10
        else if (settings.healingSettings.hPotionHealHealth > 0 && health <= settings.healingSettings.hPotionHealHealth && mana >= settings.healingSettings.hPotionHealMana) {
          logs = logs :+ Log("I need to use HP!")
          if (settings.mouseMovements) {
            logs = logs :+ Log("use HP with mouse")
            findItemInContainerSlot14(json, updatedState, 2874, 10).foreach { runePosition =>
              val runeX = (runePosition \ "x").as[Int]
              val runeY = (runePosition \ "y").as[Int]

              // Extracting target position from the mapPanelLoc in the JSON
              val mapTarget = (json \ "screenInfo" \ "mapPanelLoc" \ "8x6").as[JsObject]
              val targetX = (mapTarget \ "x").as[Int]
              val targetY = (mapTarget \ "y").as[Int]

              val actionsSeq = Seq(
                MouseAction(runeX, runeY, "move"),
                MouseAction(runeX, runeY, "pressRight"), // Right-click on the rune
                MouseAction(runeX, runeY, "releaseRight"), // Release right-click on the rune
                MouseAction(targetX, targetY, "move"), // Move to target position
                MouseAction(targetX, targetY, "pressLeft"), // Press left at target position
                MouseAction(targetX, targetY, "releaseLeft") // Release left at target position
              )
              // Assuming ActionDetail can wrap mouse actions
              actions = actions :+ FakeAction("useMouse", Some(ItemInfo(2874, Option(10))), Some(MouseActions(actionsSeq)))
              logs = logs :+ Log(s"Using item 2874, Option(10) at position ($runeX, $runeY) - Actions: $actionsSeq")
            }
          } else {
            logs = logs :+ Log("use HP with function")
            actions = actions :+ FakeAction("useOnYourselfFunction", Some(ItemInfo(2874, Option(10))), None)
          }
        }

        // MP Potion 2874, 7
        else if (settings.healingSettings.mPotionHealManaMin > 0 && mana <= settings.healingSettings.mPotionHealManaMin) {
          logs = logs :+ Log("I need to use MP!")
          if (settings.mouseMovements) {
            logs = logs :+ Log("use MP with mouse")
            findItemInContainerSlot14(json, updatedState, 2874, 7).foreach { runePosition =>
              val runeX = (runePosition \ "x").as[Int]
              val runeY = (runePosition \ "y").as[Int]

              // Extracting target position from the mapPanelLoc in the JSON
              val mapTarget = (json \ "screenInfo" \ "mapPanelLoc" \ "8x6").as[JsObject]
              val targetX = (mapTarget \ "x").as[Int]
              val targetY = (mapTarget \ "y").as[Int]

              val actionsSeq = Seq(
                MouseAction(runeX, runeY, "move"),
                MouseAction(runeX, runeY, "pressRight"), // Right-click on the rune
                MouseAction(runeX, runeY, "releaseRight"), // Release right-click on the rune
                MouseAction(targetX, targetY, "move"), // Move to target position
                MouseAction(targetX, targetY, "pressLeft"), // Press left at target position
                MouseAction(targetX, targetY, "releaseLeft") // Release left at target position
              )

              actions = actions :+ FakeAction("useMouse", Some(ItemInfo(2874, Option(7))), Some(MouseActions(actionsSeq)))
              logs = logs :+ Log(s"Using item 2874, Option(7) at position ($runeX, $runeY) - Actions: $actionsSeq")
            }
          } else {
            logs = logs :+ Log("use MP with function")
            actions = actions :+ FakeAction("useOnYourselfFunction", Some(ItemInfo(2874, Option(7))), None)
          }
        }
        else if (settings.healingSettings.spellsHealSettings.head.strongHealSpell.length > 1 &&
          settings.healingSettings.spellsHealSettings.head.strongHealHealth > 0 &&
          health <= settings.healingSettings.spellsHealSettings.head.strongHealHealth &&
          mana >= settings.healingSettings.spellsHealSettings.head.strongHealMana) {
          println(s"Inside strong heal section")

          if (settings.mouseMovements) {

            if (settings.healingSettings.spellsHealSettings.head.strongHealHotkeyEnabled) {
              val hotkeyHeal = settings.healingSettings.spellsHealSettings.head.strongHealHotkey
              logs = logs :+ Log(s"use hotkey for strong healing spell: ${hotkeyHeal}")
              actions = actions :+ FakeAction("pressKey", None, Some(PushTheButton(hotkeyHeal)))
            } else {
              logs = logs :+ Log("use keyboard for strong healing spell")
              actions = actions :+ FakeAction("typeText", None, Some(KeyboardText(settings.healingSettings.spellsHealSettings.head.strongHealSpell)))
            }
          } else {
//            logs = logs :+ Log("use function for strong healing spell")
//            actions = actions :+ FakeAction("sayText", None, Some(KeyboardText(spellText)))
          }
        }

        else if (settings.healingSettings.spellsHealSettings.head.lightHealSpell.length > 1 &&
          settings.healingSettings.spellsHealSettings.head.lightHealHealth > 0 &&
          health <= settings.healingSettings.spellsHealSettings.head.lightHealHealth &&
          mana >= settings.healingSettings.spellsHealSettings.head.lightHealMana) {
          println(s"Inside light heal section")

          if (settings.mouseMovements) {

            if (settings.healingSettings.spellsHealSettings.head.lightHealHotkeyEnabled) {
              val hotkeyHeal = settings.healingSettings.spellsHealSettings.head.lightHealHotkey
              logs = logs :+ Log(s"use hotkey for light healing spell: ${hotkeyHeal}")
              actions = actions :+ FakeAction("pressKey", None, Some(PushTheButton(hotkeyHeal)))
            } else {
              logs = logs :+ Log("use keyboard for light healing spell")
              actions = actions :+ FakeAction("typeText", None, Some(KeyboardText(settings.healingSettings.spellsHealSettings.head.lightHealSpell)))
            }
          } else {

            //            logs = logs :+ Log("use function for strong healing spell")
            //            actions = actions :+ FakeAction("sayText", None, Some(KeyboardText(spellText)))
          }
        }
        else if (settings.healingSettings.friendsHealSettings.head.friend1HealSpell.length > 1 &&
          settings.healingSettings.friendsHealSettings.head.friend1Name.length > 1 &&
          settings.healingSettings.friendsHealSettings.head.friend1HealHealth > 0 &&
          friend1HealthPercentage <= settings.healingSettings.friendsHealSettings.head.friend1HealHealth &&
          mana >= settings.healingSettings.friendsHealSettings.head.friend1HealMana) {
          println(s"Inside friend1 heal section")

          if (settings.mouseMovements) {

            if (settings.healingSettings.friendsHealSettings.head.friend1HealHotkeyEnabled) {
              val hotkeyHeal = settings.healingSettings.friendsHealSettings.head.friend1HealHotkey
              logs = logs :+ Log(s"use hotkey for friend healing spell: ${hotkeyHeal}")
              actions = actions :+ FakeAction("pressKey", None, Some(PushTheButton(hotkeyHeal)))
            } else {
              val mergedString = settings.healingSettings.friendsHealSettings.head.friend1HealSpell + settings.healingSettings.friendsHealSettings.head.friend1Name
              logs = logs :+ Log("use keyboard for light healing spell")
              actions = actions :+ FakeAction("typeText", None, Some(KeyboardText(mergedString)))
            }
          } else {

            //            logs = logs :+ Log("use function for strong healing spell")
            //            actions = actions :+ FakeAction("sayText", None, Some(KeyboardText(spellText)))
          }
        }

        else if (settings.healingSettings.friendsHealSettings.head.friend2HealSpell.length > 1 &&
          settings.healingSettings.friendsHealSettings.head.friend2Name.length > 1 &&
          settings.healingSettings.friendsHealSettings.head.friend2HealHealth > 0 &&
          friend2HealthPercentage <= settings.healingSettings.friendsHealSettings.head.friend2HealHealth &&
          mana >= settings.healingSettings.friendsHealSettings.head.friend2HealMana) {
          println(s"Inside friend2 heal section")

          if (settings.mouseMovements) {

            if (settings.healingSettings.friendsHealSettings.head.friend2HealHotkeyEnabled) {
              val hotkeyHeal = settings.healingSettings.friendsHealSettings.head.friend2HealHotkey
              logs = logs :+ Log(s"use hotkey for friend healing spell: ${hotkeyHeal}")
              actions = actions :+ FakeAction("pressKey", None, Some(PushTheButton(hotkeyHeal)))
            } else {
              val mergedString = settings.healingSettings.friendsHealSettings.head.friend2HealSpell + settings.healingSettings.friendsHealSettings.head.friend2Name
              logs = logs :+ Log("use keyboard for light healing spell")
              actions = actions :+ FakeAction("typeText", None, Some(KeyboardText(mergedString)))
            }
          } else {

            //            logs = logs :+ Log("use function for strong healing spell")
            //            actions = actions :+ FakeAction("sayText", None, Some(KeyboardText(spellText)))
          }
        }

        else if (settings.healingSettings.friendsHealSettings.head.friend3HealSpell.length > 1 &&
          settings.healingSettings.friendsHealSettings.head.friend3Name.length > 1 &&
          settings.healingSettings.friendsHealSettings.head.friend3HealHealth > 0 &&
          friend3HealthPercentage <= settings.healingSettings.friendsHealSettings.head.friend3HealHealth &&
          mana >= settings.healingSettings.friendsHealSettings.head.friend3HealMana) {
          println(s"Inside friend3 heal section")

          if (settings.mouseMovements) {

            if (settings.healingSettings.friendsHealSettings.head.friend3HealHotkeyEnabled) {
              val hotkeyHeal = settings.healingSettings.friendsHealSettings.head.friend3HealHotkey
              logs = logs :+ Log(s"use hotkey for friend healing spell: ${hotkeyHeal}")
              actions = actions :+ FakeAction("pressKey", None, Some(PushTheButton(hotkeyHeal)))
            } else {
              val mergedString = settings.healingSettings.friendsHealSettings.head.friend3HealSpell + settings.healingSettings.friendsHealSettings.head.friend3Name
              logs = logs :+ Log("use keyboard for light healing spell")
              actions = actions :+ FakeAction("typeText", None, Some(KeyboardText(mergedString)))
            }
          } else {

            //            logs = logs :+ Log("use function for strong healing spell")
            //            actions = actions :+ FakeAction("sayText", None, Some(KeyboardText(spellText)))
          }
        }


      }
    }
    val endTime = System.nanoTime()
    val duration = (endTime - startTime) / 1e9d
    printInColor(ANSI_GREEN, f"[INFO] Processing computeAutoHealActions took $duration%.6f seconds")

    ((actions, logs), updatedState)
  }

  def noRunesInBpGoUp(
                       containerName: String,
                       json: JsValue,
                       initialActions: Seq[FakeAction],
                       initialLogs: Seq[Log],
                       currentState: ProcessorState
                     ): ((Seq[FakeAction], Seq[Log]), ProcessorState) = {

    // Initializing mutable variables with passed values
    var actions = initialActions
    var logs = initialLogs
    var updatedState = currentState

    logs = logs :+ Log(s"Container $containerName has 20 free spaces and a parent. Finding upButton...")


    // Iterate over all containers in the "inventoryPanelLoc" to find the one that contains "containerName" as a substring
    (json \ "screenInfo" \ "inventoryPanelLoc").asOpt[JsObject].foreach { inventoryPanelLoc =>
      // Find the container whose name contains the substring "containerName"
      val matchingContainerOpt = inventoryPanelLoc.keys.find(_.contains(containerName))

      matchingContainerOpt.foreach { matchingContainer =>
        // Extract the upButton coordinates for the matching container
        (inventoryPanelLoc \ matchingContainer \ "upButton").asOpt[JsObject].foreach { upButtonCoords =>
          val targetX = (upButtonCoords \ "x").asOpt[Int].getOrElse(0)
          val targetY = (upButtonCoords \ "y").asOpt[Int].getOrElse(0)

          // Adding mouse actions for clicking the upButton
          val actionsSeq = Seq(
            MouseAction(targetX, targetY, "move"),
            MouseAction(targetX, targetY, "pressLeft"),
            MouseAction(targetX, targetY, "releaseLeft")
          )

          actions = actions :+ FakeAction("useMouse", None, Some(MouseActions(actionsSeq)))

          logs = logs :+ Log(s"Simulated click on upButton for $containerName.")
        }
      }
    }





    // Return the updated actions, logs, and state
    ((actions, logs), updatedState)
  }


  def removeEmptyBackpack(
                           containerName: String,
                           json: JsValue,
                           initialActions: Seq[FakeAction],
                           initialLogs: Seq[Log],
                           currentState: ProcessorState
                         ): ((Seq[FakeAction], Seq[Log]), ProcessorState) = {

    // Initialize mutable variables from input parameters
    var actions = initialActions
    var logs = initialLogs
    var updatedState = currentState

    logs = logs :+ Log(s"Removing empty backpack for container $containerName.")

    val presentCharLocation = (json \ "screenInfo" \ "mapPanelLoc" \ "8x6").as[JsObject]
    val presentCharLocationX = (presentCharLocation \ "x").as[Int]
    val presentCharLocationY = (presentCharLocation \ "y").as[Int]

    // Iterate over all containers in the "inventoryPanelLoc" to find the one that contains "containerName" as a substring
    (json \ "screenInfo" \ "inventoryPanelLoc").asOpt[JsObject].foreach { inventoryPanelLoc =>
      // Find the container whose name contains the substring "containerName"
      val matchingContainerOpt = inventoryPanelLoc.keys.find(_.contains(containerName))

      matchingContainerOpt.foreach { matchingContainer =>
        // Extract the upButton coordinates for the matching container
        val emptyBPPosition = (inventoryPanelLoc \ matchingContainer \ "contentsPanel" \ "item0").as[JsObject]
        val emptyBPX = (emptyBPPosition \ "x").as[Int]
        val emptyBPY = (emptyBPPosition \ "y").as[Int]

        // Create mouse actions for removing the backpack
        val actionsSeq = Seq(
          MouseAction(emptyBPX, emptyBPY, "move"),
          MouseAction(emptyBPX, emptyBPY, "pressLeft"),
          MouseAction(presentCharLocationX, presentCharLocationY, "move"),
          MouseAction(presentCharLocationX, presentCharLocationY, "releaseLeft")
        )

        actions = actions :+ FakeAction("useMouse", None, Some(MouseActions(actionsSeq)))
        logs = logs :+ Log(s"Removing backpack at position ($emptyBPX, $emptyBPY). Actions: $actionsSeq")

        // Update state to indicate the next action is opening a new backpack

        }
      }

    // Return the updated actions, logs, and state
    ((actions, logs), updatedState)
  }


  def openNewBackpack(
                       containerName: String,
                       json: JsValue,
                       initialActions: Seq[FakeAction],
                       initialLogs: Seq[Log],
                       currentState: ProcessorState
                     ): ((Seq[FakeAction], Seq[Log]), ProcessorState) = {

    // Initialize mutable variables from input parameters
    var actions = initialActions
    var logs = initialLogs
    var updatedState = currentState

    (json \ "screenInfo" \ "inventoryPanelLoc").asOpt[JsObject].foreach { inventoryPanelLoc =>
      // Find the container whose name contains the substring "containerName"
      val matchingContainerOpt = inventoryPanelLoc.keys.find(_.contains(containerName))

      matchingContainerOpt.foreach { matchingContainer =>
        // Extract the upButton coordinates for the matching container
//        (inventoryPanelLoc \ matchingContainer \ "upButton").asOpt[JsObject].foreach { upButtonCoords =>
        val newBpPosition = (inventoryPanelLoc \ matchingContainer \ "contentsPanel" \ "item0").as[JsObject]
        val newBpPositionX = (newBpPosition \ "x").as[Int]
        val newBpPositionY = (newBpPosition \ "y").as[Int]

        logs = logs :+ Log(s"Opening new backpack at position ($newBpPositionX, $newBpPositionY).")

        // Create mouse actions for opening the new backpack
        val actionsSeq = Seq(
          MouseAction(newBpPositionX, newBpPositionY, "move"),
          MouseAction(newBpPositionX, newBpPositionY, "pressRight"),
          MouseAction(newBpPositionX, newBpPositionY, "releaseRight")
        )

        actions = actions :+ FakeAction("useMouse", None, Some(MouseActions(actionsSeq)))

        // Update state to indicate the next action is checking for runes

      }
    }
    // Return the updated actions, logs, and state
    ((actions, logs), updatedState)
  }


}
