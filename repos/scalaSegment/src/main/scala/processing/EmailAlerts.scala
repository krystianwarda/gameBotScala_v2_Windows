package processing

import keyboard.ApplicationSetup.system.startTime
import mouse.FakeAction
import play.api.libs.json.{JsObject, JsValue}
import userUI.SettingsUtils.UISettings
import utils.EmailUtils
import utils.consoleColorPrint.{ANSI_GREEN, printInColor}

object EmailAlerts {
  def computeEmailAlertsActions(json: JsValue, settings: UISettings, currentState: ProcessorState): ((Seq[FakeAction], Seq[Log]), ProcessorState) = {
    var actions: Seq[FakeAction] = Seq.empty
    var logs: Seq[Log] = Seq.empty
    var updatedState = currentState // Initialize updatedState

    if (settings.emailAlertsSettings.enabled) {
      val presentCharLocationZ = (json \ "characterInfo" \ "PositionZ").as[Int]
      println(s" email: ${settings.emailAlertsSettings.email}, recipient: ${settings.emailAlertsSettings.recipient}")


      // Extract the "spyLevelInfo" object from the JSON
      val creatures = (json \ "spyLevelInfo").as[JsObject]

      creatures.fields.foreach { case (_, creatureInfo) =>
        val isNpc = (creatureInfo \ "IsNpc").as[Boolean]
        val positionZ = (creatureInfo \ "PositionZ").as[Int]
        if (!isNpc && positionZ == presentCharLocationZ) {
          val name = (creatureInfo \ "Name").as[String]
          val currentTime = System.currentTimeMillis()

          // Send an email if lastEmailAlertTime was more than 30 seconds ago
          if ((currentTime - updatedState.lastEmailAlertTime) >= 30000) {
            val emailSent = EmailUtils.sendEmail(
              settings.emailAlertsSettings.email,
              settings.emailAlertsSettings.password,
              settings.emailAlertsSettings.recipient,
              "Creature Alert!",
              s"Alert: A $name has been spotted on your level!"
            )
            if (emailSent) {
              logs :+= Log(s"Email alert sent for creature: $name at level $positionZ")
              updatedState = updatedState.copy(lastEmailAlertTime = currentTime)
            } else {
              logs :+= Log("Failed to send email alert.")
            }
          }
        }

      }
    }
    val endTime = System.nanoTime()
    val duration = (endTime - startTime) / 1e9d
    printInColor(ANSI_GREEN, f"[INFO] Processing computeEmailAlertsActions took $duration%.6f seconds")

    ((actions, logs), updatedState)
  }
}
