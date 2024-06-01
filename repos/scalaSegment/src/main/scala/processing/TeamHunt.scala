package processing

import mouse.FakeAction
import play.api.libs.json._
import userUI.SettingsUtils.UISettings
import utils.consoleColorPrint.{ANSI_RED, printInColor}
import processing.CaveBot.Vec

import java.lang.System.currentTimeMillis
import utils.consoleColorPrint._

object TeamHunt {
  def computeTeamHuntActions(json: JsValue, settings: UISettings, currentState: ProcessorState): ((Seq[FakeAction], Seq[Log]), ProcessorState) = {
    printInColor(ANSI_RED, f"[DEBUG] Performing computeTeamHuntActions action")
    var actions: Seq[FakeAction] = Seq.empty
    var logs: Seq[Log] = Seq.empty
    var updatedState = currentState // Initialize updatedState

    val startTime = System.nanoTime()

    if (settings.teamHuntSettings.enabled) {

      val presentCharLocationX = (json \ "characterInfo" \ "PositionX").as[Int]
      val presentCharLocationY = (json \ "characterInfo" \ "PositionY").as[Int]
      val presentCharLocation = Vec(presentCharLocationX, presentCharLocationY)

      if (settings.teamHuntSettings.followBlocker) {
        val blockerName = settings.teamHuntSettings.blockerName

        // Extracting the "battleInfo" object from the root JSON
        (json \ "battleInfo").validate[JsObject] match {
          case JsSuccess(battleInfo, _) =>
            // Iterating over each entry in the "battleInfo" object
            val maybeBlocker = battleInfo.value.find {
              case (_, jsValue) =>
                (jsValue \ "Name").asOpt[String].contains(blockerName)
            }

            maybeBlocker match {
              case Some((_, blockerInfo)) =>
                val posX = (blockerInfo \ "PositionX").as[Int]
                val posY = (blockerInfo \ "PositionY").as[Int]
                println(s"Blocker Position: X=$posX, Y=$posY")
              case None =>
                println("Blocker not on the screen")
            }

          case JsError(errors) =>
            println("Battle Info empty: " + errors)
        }

      }


//      if (updatedState.subWaypoints.nonEmpty) {
//        val nextWaypoint = updatedState.subWaypoints.head
//        val direction = calculateDirection(presentCharLocation, nextWaypoint, updatedState.lastDirection)
//        printInColor(ANSI_RED, f"[DEBUG] Calculated Next Direction: $direction")
//        updatedState = updatedState.copy(lastDirection = direction)
//
//        direction.foreach { dir =>
//          actions = actions :+ FakeAction("pressKey", None, Some(PushTheButton(dir)))
//          logs :+= Log(s"Moving closer to the subWaypoint in direction: $dir")
//        }
//      }

    }
    val endTime = System.nanoTime()
    val duration = (endTime - startTime) / 1e9d
    printInColor(ANSI_GREEN, f"[INFO] Processing computeTeamHuntActions took $duration%.6f seconds")

    ((actions, logs), updatedState)
  }
}
