package processing

import keyboard.ApplicationSetup.system.startTime
import mouse.FakeAction
import play.api.libs.json.{JsObject, JsValue}
import userUI.SettingsUtils.UISettings
//import utils.EmailUtils
import utils.consoleColorPrint.{ANSI_GREEN, printInColor}

object EmailAlerts {
  def computeEmailAlertsActions(json: JsValue, settings: UISettings, currentState: ProcessorState): ((Seq[FakeAction], Seq[Log]), ProcessorState) = {
    var actions: Seq[FakeAction] = Seq.empty
    var logs: Seq[Log] = Seq.empty
    var updatedState = currentState
    val currentTime = System.currentTimeMillis()

    if (settings.emailAlertsSettings.enabled) {
      val presentCharLocationZ = (json \ "characterInfo" \ "PositionZ").as[Int]

      val creatures = (json \ "spyLevelInfo").as[JsObject]
      creatures.fields.foreach { case (_, creatureInfo) =>
        val name = (creatureInfo \ "Name").as[String]
        val isNpc = (creatureInfo \ "IsNpc").as[Boolean]
        val positionZ = (creatureInfo \ "PositionZ").as[Int]
        if (!isNpc && positionZ == presentCharLocationZ && name != "") {
//          if ((currentTime - updatedState.lastEmailAlertTime) >= 30000) {
//            val emailSent = EmailUtils.sendEmail(
//              settings.emailAlertsSettings.emailAlert,
//              settings.emailAlertsSettings.recipientAlert,
//              "Creature Alert!",
//              s"Alert: A $name has been spotted on your level!"
//            )
//            if (emailSent) {
//              logs :+= Log(s"Email alert sent for creature: $name at level $positionZ")
//              updatedState = updatedState.copy(lastEmailAlertTime = currentTime)
//            } else {
//              logs :+= Log("Failed to send email alert.")
//            }
//          }
        }
      }
    }
    val endTime = System.nanoTime()
    val duration = (endTime - startTime) / 1e9d
    printInColor(ANSI_GREEN, f"[INFO] Processing computeEmailAlertsActions took $duration%.6f seconds")

    ((actions, logs), updatedState)
  }
}
