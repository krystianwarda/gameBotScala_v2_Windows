package userUI

object SettingsUtils {
  import play.api.libs.json.{Format, Json}

  // Define the nested case classes
  case class HealingSettings(
                              enabled: Boolean,
                              lightHealSpell: String,
                              lightHealHealth: Int,
                              lightHealMana: Int,
                              strongHealSpell: String,
                              strongHealHealth: Int,
                              strongHealMana: Int,
                              ihHealHealth: Int,
                              ihHealMana: Int,
                              uhHealHealth: Int,
                              uhHealMana: Int,
                              hPotionHealHealth: Int,
                              hPotionHealMana: Int,
                              mPotionHealManaMin: Int,
                            )

  case class RuneMakingSettings(
                                 enabled: Boolean,
                                 selectedSpell: String,
                                 requiredMana: Int
                               )

  case class ProtectionZoneSettings(
                                     enabled: Boolean,
                                     playerOnScreenAlert: Boolean,
                                     escapeToProtectionZone: Boolean
                                   )

  case class FishingSettings(
                                     enabled: Boolean,
                                   )

  // Define implicit Format instances for the nested case classes
  implicit val healingSettingsFormat: Format[HealingSettings] = Json.format[HealingSettings]
  implicit val runeMakingSettingsFormat: Format[RuneMakingSettings] = Json.format[RuneMakingSettings]
  implicit val protectionZoneSettingsFormat: Format[ProtectionZoneSettings] = Json.format[ProtectionZoneSettings]
  implicit val fishingSettingsFormat: Format[FishingSettings] = Json.format[FishingSettings]

  // Now define the UISettings case class
  case class UISettings(
                         healingSettings: HealingSettings,
                         runeMakingSettings: RuneMakingSettings,
                         protectionZoneSettings: ProtectionZoneSettings,
                         fishingSettings: FishingSettings,
                         mouseMovements: Boolean,
                         caveBot: Boolean
                         // Add other settings or groups of settings as needed
                       )

  // Finally, define the implicit Format instance for UISettings
  implicit val uISettingsFormat: Format[UISettings] = Json.format[UISettings]

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

