package processing

import mouse.FakeAction
import play.api.libs.json.{JsObject, JsValue, Json}
import processing.Process.{extractOkButtonPosition, findBackpackPosition, findBackpackSlotForBlank}
import mouse.{ActionCompleted, ActionTypes, FakeAction, ItemInfo, MouseUtils, MouseMoveCommand, MouseMovementSettings}
import utils.SettingsUtils

object RuneMaker {

  def computeRuneMakingActions(json: JsValue, settings: SettingsUtils.UISettings): (Seq[FakeAction], Seq[Log]) = {
    val mana = (json \ "characterInfo" \ "Mana").as[Int]
    var actions: Seq[FakeAction] = Seq()
    var logs: Seq[Log] = Seq()
    var onGround = true
    val arrowId = 3446

    if (settings.runeMakingSettings.enabled) {

      if (settings.runeMakingSettings.stackConjuredAmmo) {

        // Check for extra window and press 'Ok' if it exists
        extractOkButtonPosition(json) match {
          case Some((posX, posY)) =>
            val actionsSeq = Seq(
              MouseAction(posX, posY, "move"),
              MouseAction(posX, posY, "pressLeft"),
              MouseAction(posX, posY, "releaseLeft")
            )
            println("[DEBUG] Closing object movement window.")
            actions = actions :+ FakeAction("useMouse", None, Some(MouseActions(actionsSeq)))
            return (actions, logs) // Exit early to handle only one action per function call
          case None => // No 'Ok' button or invalid format
        }

        // Accessing and checking containersInfo
        (json \ "containersInfo").asOpt[JsObject].foreach { containersInfo =>
          println("Iterate over containers to find arrows")

          // Collect slots of arrows and find stacks
          val arrowSlots = containersInfo.fields.flatMap { case (containerName, containerDetails) =>
            (containerDetails \ "items").asOpt[JsObject].toSeq.flatMap { items =>
              items.fields.collect {
                case (slot, itemDetails) if (itemDetails \ "itemId").asOpt[Int].contains(arrowId) =>
                  (containerName, slot, (itemDetails \ "itemCount").asOpt[Int].getOrElse(0))
              }
            }
          }

          // Process arrows
          arrowSlots.foreach { case (containerName, slot, itemCount) =>
            if (itemCount == 100) {
              println(s"Found full stack of arrows in slot: $slot")
              val itemSlot = slot.replace("slot", "item")
              val itemScreenInfoOpt = (json \ "screenInfo" \ "inventoryPanelLoc" \ s"$containerName bag" \ "contentsPanel" \ itemSlot).asOpt[JsObject]

              itemScreenInfoOpt.foreach { itemScreenInfo =>
                val (x, y) = ((itemScreenInfo \ "x").as[Int], (itemScreenInfo \ "y").as[Int])
                println(s"Screen coordinates for item: x=$x, y=$y")

                if (onGround) {
                  val presentCharLocation = (json \ "screenInfo" \ "mapPanelLoc" \ "8x6").as[JsObject]
                  val presentCharLocationX = (presentCharLocation \ "x").as[Int]
                  val presentCharLocationY = (presentCharLocation \ "y").as[Int]
                  println(s"Throwing item on the ground at: x=$presentCharLocationX, y=$presentCharLocationY")

                  // Throw item on the ground
                  val actionsSeq = Seq(
                    MouseAction(x, y, "move"),
                    MouseAction(presentCharLocationX, presentCharLocationY, "pressLeft"),
                    MouseAction(presentCharLocationX, presentCharLocationY, "move"),
                    MouseAction(presentCharLocationX, presentCharLocationY, "releaseLeft")
                  )
                  actions = actions :+ FakeAction("useMouse", None, Some(MouseActions(actionsSeq)))
                } else {
                  // Placeholder action when onGround is false
                  println("onGround is false, leaving placeholder action.")
                  val actionsSeq = Seq(
                    MouseAction(x, y, "move"),
                    MouseAction(x, y, "pressRight"),
                    MouseAction(x, y, "releaseRight")
                  )
                  actions = actions :+ FakeAction("useMouse", None, Some(MouseActions(actionsSeq)))
                }
                return (actions, logs) // Exit early to handle only one action per function call
              }
            }
          }

          // Stack smaller stacks of arrows
          val smallerStacks = arrowSlots.filter { case (_, _, count) => count > 0 && count < 100 }
          if (smallerStacks.size > 1) {
            val (containerName1, slot1, count1) = smallerStacks.head
            val (containerName2, slot2, count2) = smallerStacks(1)
            if (count1 + count2 <= 100) {
              println(s"Stacking arrows from slot $slot1 to slot $slot2")
              val itemSlot1 = slot1.replace("slot", "item")
              val itemSlot2 = slot2.replace("slot", "item")

              val itemScreenInfoOpt1 = (json \ "screenInfo" \ "inventoryPanelLoc" \ s"$containerName1 bag" \ "contentsPanel" \ itemSlot1).asOpt[JsObject]
              val itemScreenInfoOpt2 = (json \ "screenInfo" \ "inventoryPanelLoc" \ s"$containerName2 bag" \ "contentsPanel" \ itemSlot2).asOpt[JsObject]

              (itemScreenInfoOpt1, itemScreenInfoOpt2) match {
                case (Some(itemScreenInfo1), Some(itemScreenInfo2)) =>
                  val (x1, y1) = ((itemScreenInfo1 \ "x").as[Int], (itemScreenInfo1 \ "y").as[Int])
                  val (x2, y2) = ((itemScreenInfo2 \ "x").as[Int], (itemScreenInfo2 \ "y").as[Int])
                  println(s"Screen coordinates for stacking: x1=$x1, y1=$y1, x2=$x2, y2=$y2")

                  // Stack arrows
                  val actionsSeq = Seq(
                    MouseAction(x1, y1, "move"),
                    MouseAction(x1, y1, "pressLeft"),
                    MouseAction(x2, y2, "move"),
                    MouseAction(x2, y2, "releaseLeft")
                  )
                  actions = actions :+ FakeAction("useMouse", None, Some(MouseActions(actionsSeq)))
                  return (actions, logs) // Exit early to handle only one action per function call
                case _ =>
                  println("Could not find screen info for both arrow stacks.")
              }
            }
          }
        }
      }

      if (settings.runeMakingSettings.makeRunes) {
        val currentTime = System.currentTimeMillis()
        val slot6Position = (json \ "screenInfo" \ "inventoryPanelLoc" \ "inventoryWindow" \ "contentsPanel" \ "slot6").as[JsObject]
        val slot6X = (slot6Position \ "x").as[Int]
        val slot6Y = (slot6Position \ "y").as[Int]
        (json \ "EqInfo" \ "6" \ "itemId").asOpt[Int] match {
          case Some(3160) =>
            println("Already made rune in hand.")
            if (settings.mouseMovements) {

              // Example adjustment for using findBackpackPosition
              val backpackPositionResult: Option[(FakeAction, Log)] = findBackpackPosition(json).map { backpackSlotForBlank =>
                val endX = (backpackSlotForBlank \ "x").as[Int]
                val endY = (backpackSlotForBlank \ "y").as[Int]
                val log = Log(s"Moving mouse from: x:$endX, y:$endY to x:$slot6X, y:$slot6Y")
                val action = FakeAction("useMouse", Some(ItemInfo(3160, None)), Some(MouseActions(Seq(MouseAction(endX, endY, "move")))))
                (action, log)
              }

              backpackPositionResult.foreach { case (action, log) =>
                actions = actions :+ action
                logs = logs :+ log
              }

            } else {

              findBackpackPosition(json).foreach { backPosition =>
                val backPositionJson = Json.toJson(backPosition)
                logs = logs :+ Log("use UH with function")
                actions = actions :+ FakeAction("moveBlankRuneBackFunction", None, Some(JsonActionDetails(backPositionJson)))
              }

              // Return accumulated actions and logs

            }

          case None =>
            println("Nothing in hand.")
            if (settings.mouseMovements) {
              findBackpackSlotForBlank(json).foreach { backpackSlotForBlank =>
                println(backpackSlotForBlank)
                val endX = (backpackSlotForBlank \ "x").as[Int]
                val endY = (backpackSlotForBlank \ "y").as[Int]
                println(s"Moving mouse from: x:$endX, y:$endY to x:$slot6X, y:$slot6Y")
                val actionsSeq = Seq(
                  MouseAction(endX, endY, "move"),
                  MouseAction(endX, endY, "pressLeft"),
                  MouseAction(slot6X, slot6Y, "move"),
                  MouseAction(slot6X, slot6Y, "releaseLeft"),
                )
                logs = logs :+ Log("Move blank rune in the hand with mouse.")
                actions = actions :+ FakeAction("useMouse", Some(ItemInfo(3147, Option(1))), Some(MouseActions(actionsSeq)))
              }
            } else {
              logs = logs :+ Log("Rune setting command sent to TCP server.")
              actions = actions :+ FakeAction("setBlankRuneFunction", None, None)
            }

          case Some(3147) =>
            println("Blank rune in hand.")
            (json \ "characterInfo" \ "Mana").asOpt[Int] match {
              case Some(mana) if mana > 300 =>
                val spellText = settings.runeMakingSettings.selectedSpell
                if (settings.mouseMovements) {
                  logs = logs :+ Log("use keyboard for rune spell")
                  actions = actions :+ FakeAction("typeText", None, Some(KeyboardText(spellText)))
                } else {
                  logs = logs :+ Log("use function for rune spell")
                  actions = actions :+ FakeAction("sayText", None, Some(KeyboardText(spellText)))
                }

              case _ =>
                println("Mana is not higher than 300. Rune making cannot proceed.")
            }
        }
      }

    } else {
      //  placeholder - nothing should happen
    }
    (actions, logs)
  }
}
