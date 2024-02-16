package processing

import mouse.FakeAction
import play.api.libs.json.{JsObject, JsValue, Json}
import processing.Process.{findBackpackPosition, findBackpackSlotForBlank}
import userUI.SettingsUtils
import mouse.{ActionCompleted, ActionTypes, FakeAction, ItemInfo, Mouse, MouseMoveCommand, MouseMovementSettings}
object RuneMaker {

  def computeRuneMakingActions(json: JsValue, settings: SettingsUtils.UISettings): (Seq[FakeAction], Seq[Log]) = {
    val mana = (json \ "characterInfo" \ "Mana").as[Int]
    var actions: Seq[FakeAction] = Seq()
    var logs: Seq[Log] = Seq()

    if (settings.runeMakingSettings.enabled) {
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
    } else {
      //  placeholder - nothing should happen
    }
    (actions, logs)
  }
}
