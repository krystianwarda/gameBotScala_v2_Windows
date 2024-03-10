package processing

import mouse.FakeAction
import play.api.libs.json.JsValue
import userUI.SettingsUtils

object CaveBot {
  def computeCaveBotActions(json: JsValue, settings: SettingsUtils.UISettings): (Seq[FakeAction], Seq[Log]) = {
    var actions: Seq[FakeAction] = Seq.empty
    var logs: Seq[Log] = Seq.empty
    println("Performing computeCaveBotActions action.")

    if (settings.caveBotSettings.enabled) {
      // Convert JList model to Scala collection
      val waypointsModel = settings.caveBotSettings.waypointsList.getModel
      val waypointsCount = waypointsModel.getSize
      println(s"Number of waypoints: $waypointsCount")

      // Use asScala to convert Java enumeration to Scala collection
      for (index <- 0 until waypointsCount) {
        val waypoint = waypointsModel.getElementAt(index)
        println(waypoint)
      }
    } else None

    (actions, logs)
  }
}