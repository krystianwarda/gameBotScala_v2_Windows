package processing


import play.api.libs.json.{JsValue, Json}
import userUI.SettingsUtils
import userUI.SettingsUtils.UISettings
import mouse.{ActionCompleted, ActionTypes, FakeAction, ItemInfo, Mouse, MouseMoveCommand, MouseMovementSettings}
import play.api.libs.json.{JsNumber, JsObject, JsValue, Json}
import processing.Process.findItemInContainerSlot14

object AutoHeal {

  def computeHealingActions(json: JsValue, settings: SettingsUtils.UISettings): (Seq[FakeAction], Seq[Log]) = {
    var actions: Seq[FakeAction] = Seq()
    var logs: Seq[Log] = Seq()

    if (settings.healingSettings.enabled) {
      val health = (json \ "characterInfo" \ "Health").as[Int]
      val mana = (json \ "characterInfo" \ "Mana").as[Int]
      // UH RUNE 3160
      if (settings.healingSettings.uhHealHealth > 0 && health <= settings.healingSettings.uhHealHealth && mana <= settings.healingSettings.uhHealMana) {
        logs = logs :+ Log("I need to use UH!")
        if (settings.mouseMovements) {
          logs = logs :+ Log("use UH with mouse")
          findItemInContainerSlot14(json, 3160, 1).foreach { runePosition =>
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

            actions = actions :+ FakeAction("useMouse", Some(ItemInfo(3160, None)), Some(MouseActions(actionsSeq)))
            logs = logs :+ Log(s"Using item 3160 at position ($runeX, $runeY) - Actions: $actionsSeq")
          }
        } else {
          logs = logs :+ Log("use UH with function")
          actions = actions :+ FakeAction("useOnYourselfFunction", Some(ItemInfo(3160, None)), None)
        }
      }

      // IH RUNE 3152
      else if (settings.healingSettings.ihHealHealth > 0 && health <= settings.healingSettings.ihHealHealth && mana <= settings.healingSettings.ihHealMana) {
        logs = logs :+ Log("I need to use IH!")
        if (settings.mouseMovements) {
          logs = logs :+ Log("use IH with mouse")
          findItemInContainerSlot14(json, 3152, 1).foreach { runePosition =>
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
          findItemInContainerSlot14(json, 2874, 10).foreach { runePosition =>
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
          findItemInContainerSlot14(json, 2874, 7).foreach { runePosition =>
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


      else if (settings.healingSettings.strongHealHealth > 0 && health <= settings.healingSettings.strongHealHealth && mana >= settings.healingSettings.strongHealMana) {
        val spellText = settings.healingSettings.strongHealSpell
        if (settings.mouseMovements) {
          logs = logs :+ Log("use keyboard for strong healing spell")
          actions = actions :+ FakeAction("typeText", None, Some(KeyboardText(spellText)))
        } else {
          logs = logs :+ Log("use function for strong healing spell")
          actions = actions :+ FakeAction("sayText", None, Some(KeyboardText(spellText)))
        }
      }

      else if (settings.healingSettings.lightHealHealth > 0 && health <= settings.healingSettings.lightHealHealth && mana >= settings.healingSettings.lightHealMana) {
        val spellText = settings.healingSettings.lightHealSpell
        if (settings.mouseMovements) {
          logs = logs :+ Log("use keyboard for light healing spell")
          actions = actions :+ FakeAction("typeText", None, Some(KeyboardText(spellText)))
        } else {
          logs = logs :+ Log("use function for light healing spell")
          actions = actions :+ FakeAction("sayText", None, Some(KeyboardText(spellText)))
        }
      }
    }
    (actions, logs)
  }


}
