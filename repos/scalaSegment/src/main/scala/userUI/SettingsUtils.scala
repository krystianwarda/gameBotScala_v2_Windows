package userUI

import play.api.libs.functional.syntax.toFunctionalBuilderOps
import play.api.libs.json.{JsPath, Reads, Writes}
import play.api.libs.json._

import javax.swing.{DefaultListModel, JList}
import scala.Function.unlift
import play.api.libs.json._
import play.api.libs.functional.syntax._
import play.api.libs.json.Format.GenericFormat
import play.api.libs.json.OFormat.oFormatFromReadsAndOWrites

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
                              enabled: Boolean = false,
                              spellsHeal: List[HealingSpellsSettings] = List.empty,
                              ihHealHealth: Int = 0,
                              ihHealMana: Int = 0,
                              uhHealHealth: Int = 0,
                              uhHealMana: Int = 0,
                              hPotionHealHealth: Int = 0,
                              hPotionHealMana: Int = 0,
                              mPotionHealManaMin: Int = 0,
                              friendsHeal: List[HealingFriendsSettings] = List.empty
                            )


  case class HealingSpellsSettings(
                                    lightHealSpell: String = "",
                                    lightHealHealth: Int = 0,
                                    lightHealMana: Int = 0,
                                    lightHealHotkeyEnabled: Boolean = false,
                                    lightHealHotkey: String = "",
                                    strongHealSpell: String = "",
                                    strongHealHealth: Int = 0,
                                    strongHealMana: Int = 0,
                                    strongHealHotkeyEnabled: Boolean = false,
                                    strongHealHotkey: String = ""
                                  )

  case class HealingFriendsSettings(
                                     friend1HealSpell: String = "",
                                     friend1Name: String = "",
                                     friend1HealHealth: Int = 0,
                                     friend1HealMana: Int = 0,
                                     friend1HealHotkeyEnabled: Boolean = false,
                                     friend1HealHotkey: String = "",
                                     friend2HealSpell: String = "",
                                     friend2Name: String = "",
                                     friend2HealHealth: Int = 0,
                                     friend2HealMana: Int = 0,
                                     friend2HealHotkeyEnabled: Boolean = false,
                                     friend2HealHotkey: String = "",
                                     friend3HealSpell: String = "",
                                     friend3Name: String = "",
                                     friend3HealHealth: Int = 0,
                                     friend3HealMana: Int = 0,
                                     friend3HealHotkeyEnabled: Boolean = false,
                                     friend3HealHotkey: String = ""
                                   )


  case class HotkeysSettings(
                              enabled: Boolean = false,
                              hF1Field: String = "",
                              hF2Field: String = "",
                              hF3Field: String = "",
                              hF4Field: String = "",
                              hF5Field: String = "",
                              hF6Field: String = "",
                              hF7Field: String = "",
                              hF8Field: String = "",
                              hF9Field: String = "",
                              hF10Field: String = "",
                              hF11Field: String = "",
                              hF12Field: String = ""
                            )

  case class RuneMakingSettings(
                                 enabled: Boolean = false,
                                 makeRunes: Boolean = false,
                                 selectedSpell: String = "",
                                 requiredMana: Int = 0,
                                 stackConjuredAmmo: Boolean = false,
                               )

  case class AutoResponderSettings(
                                    enabled: Boolean = false
                                  )

  case class ProtectionZoneSettings(
                                     enabled: Boolean = false,
                                     playerOnScreenAlert: Boolean = false,
                                     escapeToProtectionZone: Boolean = false,
                                     ignoredCreatures: Seq[String] = Seq.empty,
                                     selectedRectangles: Seq[String] = Seq.empty
                                   )

  case class FishingSettings(
                              enabled: Boolean = false,
                              selectedRectangles: Seq[String] = Seq.empty,
                              fishThrowoutRectangles: Seq[String] = Seq.empty
                            )

  case class CaveBotSettings(
                              enabled: Boolean = false,
                              waypointsList: Seq[String] = Seq.empty,
                              gridInfoList: Seq[String] = Seq.empty
                            )

  case class TeamHuntSettings(
                               enabled: Boolean = false,
                               followBlocker: Boolean = false,
                               blockerName: String = "",
                               youAreBlocker: Boolean = false,
                               waitForTeam: Boolean = false,
                               chaseMonsterAndExetaRes: Boolean = false,
                               teamMembersList: List[String] = List()
                             )



  case class AutoLootSettings(
                               enabled: Boolean = false,
                               lootList: Seq[String] = Seq.empty
                             )

  case class AutoTargetSettings(
                                 enabled: Boolean = false,
                                 creatureList: Seq[String] = Seq.empty,
                               )


  case class TrainingSettings(
                               enabled: Boolean = false,
                               pickAmmunition: Boolean = false,
                               refillAmmunition: Boolean = false,
                               doNotKillTarget: Boolean = false,
                               switchAttackModeToEnsureDamage: Boolean = false,
                               switchWeaponToEnsureDamage: Boolean = false
                             )


  // Define implicit Format instances for the nested case classes
  implicit val healingSpellsSettingsFormat: Format[HealingSpellsSettings] = Json.format[HealingSpellsSettings]
  implicit val healingFriendsSettingsFormat: Format[HealingFriendsSettings] = Json.format[HealingFriendsSettings]
  implicit val healingSettingsFormat: Format[HealingSettings] = Json.format[HealingSettings]
  implicit val runeMakingSettingsFormat: Format[RuneMakingSettings] = Json.format[RuneMakingSettings]
  implicit val hotkeysSettingsFormat: Format[HotkeysSettings] = Json.format[HotkeysSettings]
  implicit val protectionZoneSettingsFormat: Format[ProtectionZoneSettings] = Json.format[ProtectionZoneSettings]
  implicit val autoResponderSettingsFormat: Format[AutoResponderSettings] = Json.format[AutoResponderSettings]
  implicit val fishingSettingsFormat: Format[FishingSettings] = Json.format[FishingSettings]
  implicit val trainingSettingsFormat: Format[TrainingSettings] = Json.format[TrainingSettings]
  implicit val caveBotSettingsFormat: Format[CaveBotSettings] = Json.format[CaveBotSettings]
  implicit val autoLootSettingsFormat: Format[AutoLootSettings] = Json.format[AutoLootSettings]
  implicit val autoTargetSettingsFormat: Format[AutoTargetSettings] = Json.format[AutoTargetSettings]
  implicit val teamHuntSettingsFormat: Format[TeamHuntSettings] = Json.format[TeamHuntSettings]
  implicit val uiSettingsFormat: Format[UISettings] = Json.format[UISettings]

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



/*  def saveSettingsToFile(settings: UISettings, filePath: String): Unit = {
    val explicitWrites = Json.writes[UISettings] // Or any other specific Format[Writes] instance
    val jsonString = Json.toJson(settings)(explicitWrites).toString()

    val file = new java.io.File(filePath)
    val pw = new java.io.PrintWriter(file)
    try pw.write(jsonString)
    finally pw.close()
  }*/

  def saveSettingsToFile(settings: UISettings, filePath: String): Unit = {
    println(s"Debug: Saving settings to file at path: $filePath")

    println("Debug: All Writes defined successfully")

    // Convert settings to JSON
    val jsonSettings = Json.toJson(settings)
    println(s"Debug: Intermediate JSON settings: $jsonSettings")

    val jsonString = jsonSettings.toString()
    println(s"Debug: jsonString created: $jsonString")

    val file = new java.io.File(filePath)
    val pw = new java.io.PrintWriter(file)
    try {
      pw.write(jsonString)
      println("Debug: jsonString successfully written to file")
    } finally {
      pw.close()
      println("Debug: PrintWriter closed")
    }
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

