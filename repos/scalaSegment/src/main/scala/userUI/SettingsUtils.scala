package userUI


object SettingsUtils {
  import play.api.libs.json.Json

  // Define the UISettings case class here if it's not already defined elsewhere
  case class UISettings(autoHeal: Boolean, runeMaker: Boolean, fishing: Boolean, mouseMovements: Boolean, caveBot: Boolean, protectionZone: Boolean /*, other settings */)

  // Implicit Json format for UISettings
  implicit val format = Json.format[UISettings]

  def saveSettingsToFile(settings: UISettings, filePath: String): Unit = {
    val jsonString = Json.toJson(settings).toString()
    val file = new java.io.File(filePath)
    val pw = new java.io.PrintWriter(file)
    try pw.write(jsonString)
    finally pw.close()
  }

  def loadSettingsFromFile(filePath: String): Option[UISettings] = {
    try {
      val source = scala.io.Source.fromFile(filePath)
      val jsonString = try source.mkString finally source.close()
      Some(Json.parse(jsonString).as[UISettings])
    } catch {
      case e: Exception => None
    }
  }
}


