package userUI

import play.api.libs.functional.syntax.toFunctionalBuilderOps
import play.api.libs.json.{JsPath, Reads, Writes}

import javax.swing.{DefaultListModel, JList}
import scala.Function.unlift
import play.api.libs.json._
import play.api.libs.functional.syntax._

import javax.swing.{DefaultListModel, JList}
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.io.Source
import scala.util.{Failure, Success, Try}

// Helper methods to convert between JList and Seq

object SettingsUtils {
  import play.api.libs.json.{Format, Json}

  // Define the nested case classes
  case class HealingSettings(
                              enabled: Boolean,
                              spellsHeal: List[HealingSpellsSettings],
                              ihHealHealth: Int,
                              ihHealMana: Int,
                              uhHealHealth: Int,
                              uhHealMana: Int,
                              hPotionHealHealth: Int,
                              hPotionHealMana: Int,
                              mPotionHealManaMin: Int,
                              friendsHeal: List[HealingFriendsSettings]
                            )
  case class HealingSpellsSettings(
                                    lightHealSpell: String,
                                    lightHealHealth: Int,
                                    lightHealMana: Int,
                                    lightHealHotkeyEnabled: Boolean,
                                    lightHealHotkey: String,
                                    strongHealSpell: String,
                                    strongHealHealth: Int,
                                    strongHealMana: Int,
                                    strongHealHotkeyEnabled: Boolean,
                                    strongHealHotkey: String,
                                   )
  case class HealingFriendsSettings(
                                     friend1HealSpell: String,
                                     friend1Name: String,
                                     friend1HealHealth: Int,
                                     friend1HealMana: Int,
                                     friend1HealHotkeyEnabled: Boolean,
                                     friend1HealHotkey: String,
                                     friend2HealSpell: String,
                                     friend2Name: String,
                                     friend2HealHealth: Int,
                                     friend2HealMana: Int,
                                     friend2HealHotkeyEnabled: Boolean,
                                     friend2HealHotkey: String,
                                     friend3HealSpell: String,
                                     friend3Name: String,
                                     friend3HealHealth: Int,
                                     friend3HealMana: Int,
                                     friend3HealHotkeyEnabled: Boolean,
                                     friend3HealHotkey: String
                                   )

  case class HotkeysSettings(
                              enabled: Boolean,
                              hF1Field: String,
                              hF2Field: String,
                              hF3Field: String,
                              hF4Field: String,
                              hF5Field: String,
                              hF6Field: String,
                              hF7Field: String,
                              hF8Field: String,
                              hF9Field: String,
                              hF10Field: String,
                              hF11Field: String,
                              hF12Field: String,
                            )

  case class RuneMakingSettings(
                                 enabled: Boolean,
                                 selectedSpell: String,
                                 requiredMana: Int
                               )

  case class AutoResponderSettings(
                                 enabled: Boolean,
                               )

  case class ProtectionZoneSettings(
                                     enabled: Boolean,
                                     playerOnScreenAlert: Boolean,
                                     escapeToProtectionZone: Boolean,
                                     ignoredCreatures: mutable.Buffer[String],
                                     selectedRectangles: Seq[String] = Seq.empty
                                   )

  case class FishingSettings(
                                     enabled: Boolean,
                                     selectedRectangles: Seq[String] = Seq.empty
                                   )

  case class CaveBotSettings(
                              enabled: Boolean,
                              waypointsList: Seq[String],
                              gridInfoList: Seq[String]
                            )

  case class TeamHuntSettings(
                              enabled: Boolean,
                              followBlocker: Boolean,
                              blockerName: String,
                            )


  case class AutoLootSettings(
                              enabled: Boolean,
                              lootList: Seq[String],
                            )

  case class AutoTargetSettings(
                              enabled: Boolean,
                              creatureList: Seq[String],
                              targetMonstersOnBattle: Boolean,
                            )

  case class TrainingSettings(
                              enabled: Boolean,
                              pickAmmunition: Boolean,
                              refillAmmunition: Boolean,
                              doNotKillTarget: Boolean,
                              switchAttackModeToEnsureDamage: Boolean,
                              switchWeaponToEnsureDamage: Boolean,
                            )

  // Define implicit Format instances for the nested case classes

  implicit val healingSettingsFormat: Format[HealingSettings] = Json.format[HealingSettings]
  implicit val healingSpellsSettingsFormat: Format[HealingSpellsSettings] = Json.format[HealingSpellsSettings]
  implicit val healingFriendsSettingsFormat: Format[HealingFriendsSettings] = Json.format[HealingFriendsSettings]
  implicit val runeMakingSettingsFormat: Format[RuneMakingSettings] = Json.format[RuneMakingSettings]
  implicit val protectionZoneSettingsFormat: Format[ProtectionZoneSettings] = Json.format[ProtectionZoneSettings]
  implicit val autoResponderSettingsFormat: Format[AutoResponderSettings] = Json.format[AutoResponderSettings]
  implicit val fishingSettingsFormat: Format[FishingSettings] = Json.format[FishingSettings]
  implicit val trainingSettingsFormat: Format[TrainingSettings] = Json.format[TrainingSettings]
  implicit val rectangleSettingsFormat: Format[RectangleSettings] = Json.format[RectangleSettings]
  implicit val autoTargetSettingsFormat: Format[AutoTargetSettings] = Json.format[AutoTargetSettings]
  implicit val caveBotSettingsFormat: Format[CaveBotSettings] = Json.format[CaveBotSettings]
  implicit val autoLootSettingsFormat: Format[AutoLootSettings] = Json.format[AutoLootSettings]
  implicit val teamHuntSettingsFormat: Format[TeamHuntSettings] = Json.format[TeamHuntSettings]
  implicit val hotkeysSettingsFormat: Format[HotkeysSettings] = Json.format[HotkeysSettings]

  // Now define the UISettings case class
  case class UISettings(
                         healingSettings: HealingSettings,
                         runeMakingSettings: RuneMakingSettings,
                         hotkeysSettings: HotkeysSettings,
                         protectionZoneSettings: ProtectionZoneSettings,
                         fishingSettings: FishingSettings,
                         autoResponderSettings: AutoResponderSettings,
                         trainingSettings: TrainingSettings,
                         mouseMovements: Boolean,
                         caveBotSettings: CaveBotSettings,
                         autoLootSettings: AutoLootSettings,
                         autoTargetSettings: AutoTargetSettings,
                         teamHuntSettings: TeamHuntSettings,
                         // Add other settings or groups of settings as needed
                       )

  // Finally, define the implicit Format instance for UISettings
  implicit val uISettingsFormat: Format[UISettings] = Json.format[UISettings]

  def saveSettingsToFile(settings: UISettings, filePath: String): Unit = {
    val explicitWrites = Json.writes[UISettings] // Or any other specific Format[Writes] instance
    val jsonString = Json.toJson(settings)(explicitWrites).toString()

    val file = new java.io.File(filePath)
    val pw = new java.io.PrintWriter(file)
    try pw.write(jsonString)
    finally pw.close()
  }


  // Now, ensure your load and save functions are correct
  def loadSettingsFromFile(filePath: String): Option[UISettings] = {
    Try {
      val jsonString = Source.fromFile(filePath).mkString
      Json.parse(jsonString).validate[UISettings] match {
        case JsSuccess(settings, _) => Some(settings)
        case JsError(errors) =>
          println(s"JSON parsing errors: $errors")
          None
      }
    }.recover {
      case ex: Exception =>
        println(s"Error reading settings from file: ${ex.getMessage}")
        None
    }.get
  }

  // Converts JList[String] to Seq[String]
  def jListToSeq(jList: JList[String]): Seq[String] = {
    val listModel = jList.getModel
    val size = listModel.getSize
    val buffer = ArrayBuffer[String]()
    for (i <- 0 until size) {
      buffer += listModel.getElementAt(i)
    }
    buffer.toSeq
  }

  def seqToJList(seq: Seq[String], jList: JList[String]): Unit = {
    val model = new DefaultListModel[String]()
    seq.foreach(model.addElement)
    jList.setModel(model)
  }




}

