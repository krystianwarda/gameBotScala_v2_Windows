//package processing
//
//import main.scala.MainApp.autoResponderManagerRef
//import mouse.FakeAction
//import play.api.libs.json.JsValue
//import utils.SettingsUtils.UISettings
//import utils.consoleColorPrint.{ANSI_GREEN, ANSI_RED, printInColor}
//import processing.CaveBot.Vec
//import keyboard.{CancelAlert, KeyAction, UpdateAlertStory}
//
//import scala.collection.immutable.Seq
//import play.api.libs.json._
//import processing.Process.{handleRetryStatus, performMouseActionSequance}
//import keyboard.CustomKeyAction
//
//import scala.util.Random
//import java.text.SimpleDateFormat
//import java.util.Date
//import keyboard.ComboKeyAction
//import utils.ProcessorState
//
//import scala.collection.mutable
//
//
//
//object GMDetector {
//  def computeGMDetectorActions(json: JsValue, settings: UISettings, currentState: ProcessorState): ((Seq[FakeAction], Seq[Log]), ProcessorState) = {
//    var actions: Seq[FakeAction] = Seq.empty
//    var logs: Seq[Log] = Seq.empty
//    var updatedState = currentState // Initialize updatedState
//    val startTime = System.nanoTime()
//    val currentTime = System.currentTimeMillis()
////    println(s"GM detected: ${updatedState.gmDetected}")
//
//
//
//    if (updatedState.gmDetected ) {
//
//      if (currentTime - updatedState.gmDetectedTime >= 45000 ) {
//        updatedState = updatedState.copy(
//          gmDetected = false,
//          lastTargetPos = (0, 0, 0),
//          creatureTarget = 0,
//          gmDetectedTime = 0
//        )
//      }
//
//      if (updatedState.preparedAnswer.contains("bye")) {
//        println(s"Conversation has ended.")
//        updatedState = updatedState.copy(
//          gmDetected = false,
//          lastTargetPos = (0, 0, 0),
//          creatureTarget = 0
//        )
//      }
//
//      if (updatedState.pendingMessages.nonEmpty) {
//        updatedState = updatedState.copy(GMlastDialogueTime = currentTime)
//      } else if (currentTime - updatedState.GMlastDialogueTime >= 25000 && updatedState.GMlastDialogueTime != 0 ){
//        println(s"Saying bye bye to GM")
//        // Format the current time for the message
//        val dateFormatter = new SimpleDateFormat("HH:mm")
//        val formattedTime = dateFormatter.format(new Date(currentTime))
//
//        // Construct a JSON message for the game master alert
//        val messageString = s"""{"mode":"command", "from": "me", "text": "Write farewell word and add smiley face divided with '|'. ", "time": "$formattedTime"}"""
//        val messageValue: JsValue = Json.parse(messageString)
//
//        updatedState = updatedState.copy(
//          pendingMessages = updatedState.pendingMessages :+ (messageValue, currentTime),
//          gmDetected = false,
//          lastTargetPos = (0, 0, 0),
//          creatureTarget = 0,
//          dialogueHistory = mutable.Buffer.empty,
//        )
//
//      }
//
//
//      // This condition checks if it's the right time to react or if it's the initial state (characterLastRotationTime == 0)
//      if (updatedState.characterLastRotationTime == 0 || (currentTime - updatedState.characterLastRotationTime >= updatedState.gmWaitTime)) {
//
//        val directions = Seq("MoveUp", "MoveDown", "MoveLeft", "MoveRight")
//        val randomDirections = Random.shuffle(directions).take(Random.nextInt(2) + 4)
//        val comboActionDetail = ComboKeyActions("Ctrl", randomDirections)
//        val comboAction = FakeAction("pressMultipleKeys", None, Some(comboActionDetail))
//
//        actions = actions :+ comboAction
//
//        updatedState = updatedState.copy(characterLastRotationTime = currentTime)
//      } else {
//        println(s"currentTime: $currentTime")
//        println(s"updatedState.characterLastRotationTime: ${updatedState.characterLastRotationTime}")
//        println(s"updatedState.gmWaitTime: ${updatedState.gmWaitTime}")
//      }
//
//    } else {
//
//      val presentCharLocationX = (json \ "characterInfo" \ "PositionX").as[Int]
//      val presentCharLocationY = (json \ "characterInfo" \ "PositionY").as[Int]
//      val presentCharLocationZ = (json \ "characterInfo" \ "PositionZ").as[Int]
//      val presentCharLocation = Vec(presentCharLocationX, presentCharLocationY)
//
//      updatedState = updatedState.copy(presentCharLocation = presentCharLocation, presentCharZLocation = presentCharLocationZ)
//
//
//      // Using state variables
//      val lastTargetPos = currentState.lastTargetPos
//      val lastTargetId = currentState.creatureTarget
//
//
//      //    println(s"Extracted positions: X=$presentCharLocationX, Y=$presentCharLocationY, Z=$presentCharLocationZ")
//
//      // Define and check if attackInfo exists
//      val attackInfoExists = (json \ "attackInfo").toOption.isDefined && !(json \ "attackInfo").as[JsObject].keys.isEmpty
//      //    println(s"Attack info exists: $attackInfoExists")
//
//
//      // Handle LastAttackedId and IsDead parsing
//      val lastAttackedCreatureInfo = (json \ "lastAttackedCreatureInfo").asOpt[Map[String, JsValue]]
//      val isDead = lastAttackedCreatureInfo.flatMap(info => info.get("IsDead").map(_.as[Boolean])).getOrElse(false)
//      val lastAttackedId = lastAttackedCreatureInfo.flatMap { info =>
//        info.get("LastAttackedId") match {
//          case Some(JsString("None")) =>
//            //          println("[INFO] LastAttackedId is 'None', using default ID 0")
//            Some(0L)
//          case Some(JsNumber(id)) =>
//            Some(id.toLong)
//          case _ =>
//            //          println("[ERROR] Invalid or missing LastAttackedId, defaulting to 0")
//            Some(0L)
//        }
//      }.getOrElse(0L)
//
//      val battleInfoResult = (json \ "battleInfo").validate[Map[String, JsValue]]
////      print(s"battleInfo : ${battleInfoResult}")
//      // Further error handling for battleInfo
//      // Further error handling for battleInfo
//      val creatureInBattle = battleInfoResult.fold(
//        _ => {
//          println("Failed to parse battleInfo, invalid JSON structure.")
//          false
//        },
//        battleInfo => battleInfo.values.exists { jsValue =>
//          (jsValue \ "Id").asOpt[Long].contains(lastAttackedId)
//        }
//      )
//
//      // Main logic
////      println(s"lastTargetId: ${lastTargetId}")
////      println(s"lastTargetId: ${lastAttackedId}")
////      println(s"attackInfoExists: ${attackInfoExists}")
////      println(s"creatureInBattle: ${creatureInBattle}")
////      println(s"isDead: ${isDead}")
//      if (lastTargetId == lastAttackedId && !attackInfoExists && isDead == false && !creatureInBattle && lastTargetId != 0) {
//        val distanceX = Math.abs(presentCharLocationX - lastTargetPos._1)
//        val distanceY = Math.abs(presentCharLocationY - lastTargetPos._2)
//        if (distanceX <= 7 && distanceY <= 6 && presentCharLocationZ == lastTargetPos._3) {
//          println("Monster has disappeared from both battle info and visual range. Sending alert ")
//
//          println(s"GM detected changed to true")
//          // Format the current time for the message
//          val dateFormatter = new SimpleDateFormat("HH:mm")
//          val formattedTime = dateFormatter.format(new Date(currentTime))
//
//          // Construct a JSON message for the game master alert
//          val messageString = s"""{"mode":"command", "from": "me", "text": "Game master alert apeared!", "time": "$formattedTime"}"""
//          val messageValue: JsValue = Json.parse(messageString)
//
//          updatedState = updatedState.copy(
//            gmDetected = true,
//            gmDetectedTime = currentTime,
//            pendingMessages = updatedState.pendingMessages :+ (messageValue, currentTime)
//          )
//        } else {
//          //        println("Monster is outside of the defined proximity range.")
//        }
//      }
//    }
//
//
//    val endTime = System.nanoTime()
//    val duration = (endTime - startTime) / 1e9d
//    printInColor(ANSI_GREEN, f"[INFO] Processing computeGMDetectorActions took $duration%.6f seconds")
//
//    ((actions, logs), updatedState)
//  }
//}
