package processing

import mouse.FakeAction
import play.api.libs.json.JsValue
import userUI.SettingsUtils
import play.api.libs.json._


object CaveBot {
  def computeCaveBotActions(json: JsValue, settings: SettingsUtils.UISettings): (Seq[FakeAction], Seq[Log]) = {
    var actions: Seq[FakeAction] = Seq.empty
    var logs: Seq[Log] = Seq.empty
    println("Performing computeCaveBotActions action.")

    if (settings.caveBotSettings.enabled) {
      println("caveBotSettings enabled.")

      // Define a function to execute when there are no monsters or battleInfo is null
      def executeWhenNoMonstersOrInvalidBattleInfo(): Unit = {
        println("Proceeding with actions because either no monster is in battle or battleInfo is null.")
        val presentCharLocationX = (json \ "characterInfo" \ "PositionX").as[Int]
        val presentCharLocationY = (json \ "characterInfo" \ "PositionY").as[Int]
        val presentCharLocationZ = (json \ "characterInfo" \ "PositionZ").as[Int]

        println(s"Type of waypointsList: ${settings.caveBotSettings.waypointsList.getClass}")

        // Convert JList model to Scala collection
        val waypointsModel = settings.caveBotSettings.waypointsList.getModel
        val waypointsCount = waypointsModel.getSize
        println(s"Number of waypoints: $waypointsCount")

        // Use asScala to convert Java enumeration to Scala collection
        for (index <- 0 until waypointsCount) {
          val waypoint = waypointsModel.getElementAt(index)
          println(waypoint)
        }
      }

      // Safely attempt to parse battleInfo as a map
      val battleInfoResult = (json \ "battleInfo").validate[Map[String, JsValue]]

      battleInfoResult match {
        case JsSuccess(battleInfo, _) =>
          val hasMonsters = battleInfo.exists { case (_, creature) =>
            (creature \ "IsMonster").asOpt[Boolean].getOrElse(false)
          }
          if (!hasMonsters) {
            executeWhenNoMonstersOrInvalidBattleInfo()
          } else {
            println("Skipping actions due to presence of monsters.")
          }
        case JsError(_) =>
          executeWhenNoMonstersOrInvalidBattleInfo()
      }
    }

    (actions, logs)
  }
}