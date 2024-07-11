package processing

import mouse.FakeAction
import play.api.libs.json.JsValue
import userUI.SettingsUtils.UISettings
import utils.consoleColorPrint.{ANSI_GREEN, ANSI_RED, printInColor}
import processing.CaveBot.{Vec}
import scala.collection.immutable.Seq
import play.api.libs.json._

object GMDetector {
  def computeGMDetectorActions(json: JsValue, settings: UISettings, currentState: ProcessorState): ((Seq[FakeAction], Seq[Log]), ProcessorState) = {
    var actions: Seq[FakeAction] = Seq.empty
    var logs: Seq[Log] = Seq.empty
    var updatedState = currentState // Initialize updatedState
    val startTime = System.nanoTime()
//    println("Start computeGMDetectorActions.")
    // Extracting player's current position
    val presentCharLocationX = (json \ "characterInfo" \ "PositionX").as[Int]
    val presentCharLocationY = (json \ "characterInfo" \ "PositionY").as[Int]
    val presentCharLocationZ = (json \ "characterInfo" \ "PositionZ").as[Int]
    val presentCharLocation = Vec(presentCharLocationX, presentCharLocationY)

    updatedState = updatedState.copy(presentCharLocation = presentCharLocation, presentCharZLocation = presentCharLocationZ)


    // Using state variables
    val lastTargetPos = currentState.lastTargetPos
    val lastTargetId = currentState.creatureTarget


//    println(s"Extracted positions: X=$presentCharLocationX, Y=$presentCharLocationY, Z=$presentCharLocationZ")

    // Define and check if attackInfo exists
    val attackInfoExists = (json \ "attackInfo").toOption.isDefined && !(json \ "attackInfo").as[JsObject].keys.isEmpty
//    println(s"Attack info exists: $attackInfoExists")


    // Handle LastAttackedId and IsDead parsing
    val lastAttackedCreatureInfo = (json \ "lastAttackedCreatureInfo").asOpt[Map[String, JsValue]]
    val isDead = lastAttackedCreatureInfo.flatMap(info => info.get("IsDead").map(_.as[Boolean])).getOrElse(false)
    val lastAttackedId = lastAttackedCreatureInfo.flatMap { info =>
      info.get("LastAttackedId") match {
        case Some(JsString("None")) =>
//          println("[INFO] LastAttackedId is 'None', using default ID 0")
          Some(0L)
        case Some(JsNumber(id)) =>
          Some(id.toLong)
        case _ =>
//          println("[ERROR] Invalid or missing LastAttackedId, defaulting to 0")
          Some(0L)
      }
    }.getOrElse(0L)

    val battleInfoResult = (json \ "battleInfo").validate[Map[String, JsValue]]

    // Further error handling for battleInfo
    val creatureInBattle = battleInfoResult.fold(
      _ => {
        println("Failed to parse battleInfo, invalid JSON structure.")
        false
      },
      battleInfo => battleInfo.get("creatures").exists(_.as[Seq[Long]].contains(lastAttackedId))
    )

//    println(s"Checking conditions for lastTargetId: $lastTargetId vs lastAttackedId: $lastAttackedId")
//    println(s"Attack info exists: $attackInfoExists, Creature is dead: $isDead, Creature in battle: $creatureInBattle")

    // Main logic
    if (lastTargetId == lastAttackedId && !attackInfoExists && !isDead && !creatureInBattle) {
      val distanceX = Math.abs(presentCharLocationX - lastTargetPos._1)
      val distanceY = Math.abs(presentCharLocationY - lastTargetPos._2)
      if (distanceX <= 7 && distanceY <= 6 && presentCharLocationZ == lastTargetPos._3) {
        println("Monster has disappeared from both battle info and visual range.")
        updatedState = updatedState.copy(gmDetected = true)
      } else {
//        println("Monster is outside of the defined proximity range.")
      }
    }



    val endTime = System.nanoTime()
    val duration = (endTime - startTime) / 1e9d
    printInColor(ANSI_GREEN, f"[INFO] Processing computeGMDetectorActions took $duration%.6f seconds")

    ((actions, logs), updatedState)
  }
}
