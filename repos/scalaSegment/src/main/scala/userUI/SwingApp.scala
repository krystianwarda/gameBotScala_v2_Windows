package userUI
import cats.effect.{IO, Ref}

import java.awt.GridBagLayout
import java.awt.GridBagConstraints
import processing.{ConnectToServer, InitializeProcessor}
import main.scala.MainApp.{autoResponderManagerRef, jsonProcessorActorRef, periodicFunctionActorRef, uiAppActorRef}
import play.api.libs.json.{Format, Json}
import utils.SettingsUtils

import javax.swing.{DefaultComboBoxModel, DefaultListModel, JComboBox, JList}
//import userUI.SettingsUtils.{HealingSettings, ProtectionZoneSettings, RuneMakingSettings, UISettings, saveSettingsToFile}
import utils.SettingsUtils._
import scala.swing.{Component, Dialog, FileChooser, Insets}
import akka.actor.ActorRef
import main.scala.MainApp
import player.Player
import utils.StartMouseMovementActor

import java.awt.Dimension
import scala.swing.{BoxPanel, Button, CheckBox, ComboBox, Label, MainFrame, Orientation, TabbedPane, TextField}
import scala.swing.event.{ButtonClicked, SelectionChanged}
import utils.StartSpecificPeriodicFunction
import cats.effect.unsafe.implicits.global


case class UpdateSettings(settings: UISettings)


class SwingApp(playerClassList: List[Player],
               uiAppActor: ActorRef,
               jsonProcessorActor: ActorRef,
               periodicFunctionActor: ActorRef,
               thirdProcessActor: ActorRef,
               mainActorRef: ActorRef,
               settingsRef: Ref[IO, UISettings]) extends MainFrame {

  title = "TibiaYBB"
  preferredSize = new Dimension(600, 300)
  var runningBot = false

  // Initialize AutoHeal class
//  val mainBot = new MainBot(currentPlayer, uiAppActor, jsonProcessorActor)
  val autoHealBot = new AutoHealBot(uiAppActor, jsonProcessorActor, settingsRef)
  val hotkeysBot = new HotkeysBot(currentPlayer, uiAppActor, jsonProcessorActor)
  val caveBotBot = new CaveBotBot(currentPlayer, uiAppActor, jsonProcessorActor)
  val autoTargetBot = new AutoTargetBot(currentPlayer, uiAppActor, jsonProcessorActor)
  val autoLootBot = new AutoLootBot(currentPlayer, uiAppActor, jsonProcessorActor)
  val runeMakerBot = new RuneMakerBot(currentPlayer, uiAppActor, jsonProcessorActor)
  val fishingBot = new FishingBot(uiAppActor, jsonProcessorActor, settingsRef)

  val guardianBot = new GuardianBot(currentPlayer, uiAppActor, jsonProcessorActor)
  val trainingBot = new TrainingBot(currentPlayer, uiAppActor, jsonProcessorActor)
  val autoResponderBot = new AutoResponderBot(currentPlayer, uiAppActor, jsonProcessorActor)
  val teamHuntBot = new TeamHuntBot(currentPlayer, uiAppActor, jsonProcessorActor)


  val exampleNames = playerClassList.map(_.characterName)
  val exampleMap = playerClassList.map(e => e.characterName -> e).toMap

  val exampleDropdown = new ComboBox(exampleNames)
  val exampleLabel = new Label()

  def collectHealingSettings(): HealingSettings = {
    val spellsHeal = HealingSpellsSettings(
      lightHealSpell = autoHealBot.lightHealSpellField.text,
      lightHealHealthPercent = parseTextFieldToInt(autoHealBot.lightHealHealthField.text),
      lightHealMana = parseTextFieldToInt(autoHealBot.lightHealManaField.text),
      lightHealHotkeyEnabled = autoHealBot.lightHealHotkeyCheckbox.selected,
      lightHealHotkey = autoHealBot.lightHealHotkeyDropdown.getSelectedItem.toString,
      strongHealSpell = autoHealBot.strongHealSpellField.text,
      strongHealHealthPercent = parseTextFieldToInt(autoHealBot.strongHealHealthField.text),
      strongHealMana = parseTextFieldToInt(autoHealBot.strongHealManaField.text),
      strongHealHotkeyEnabled = autoHealBot.strongHealHotkeyCheckbox.selected,
      strongHealHotkey = autoHealBot.strongHealHotkeyDropdown.getSelectedItem.toString
    )

    val friendsHeal = HealingFriendsSettings(
      friend1HealSpell = autoHealBot.friend1HealSpellField.text,
      friend1Name = autoHealBot.friend1NameField.text,
      friend1HealHealthPercent = parseTextFieldToInt(autoHealBot.friend1HealHealthField.text),
      friend1HealMana = parseTextFieldToInt(autoHealBot.friend1HealManaField.text),
      friend1HealHotkeyEnabled = autoHealBot.friend1HealHotkeyCheckbox.selected,
      friend1HealHotkey = autoHealBot.friend1HealHotkeyDropdown.getSelectedItem.toString,
      friend2HealSpell = autoHealBot.friend2HealSpellField.text,
      friend2Name = autoHealBot.friend2NameField.text,
      friend2HealHealthPercent = parseTextFieldToInt(autoHealBot.friend2HealHealthField.text),
      friend2HealMana = parseTextFieldToInt(autoHealBot.friend2HealManaField.text),
      friend2HealHotkeyEnabled = autoHealBot.friend2HealHotkeyCheckbox.selected,
      friend2HealHotkey = autoHealBot.friend2HealHotkeyDropdown.getSelectedItem.toString,
      friend3HealSpell = autoHealBot.friend3HealSpellField.text,
      friend3Name = autoHealBot.friend3NameField.text,
      friend3HealHealthPercent = parseTextFieldToInt(autoHealBot.friend3HealHealthField.text),
      friend3HealMana = parseTextFieldToInt(autoHealBot.friend3HealManaField.text),
      friend3HealHotkeyEnabled = autoHealBot.friend3HealHotkeyCheckbox.selected,
      friend3HealHotkey = autoHealBot.friend3HealHotkeyDropdown.getSelectedItem.toString
    )

    HealingSettings(
      enabled = autoHealCheckbox.selected,
      spellsHealSettings = List(spellsHeal),
      ihHealHealthPercent = parseTextFieldToInt(autoHealBot.ihHealHealthField.text),
      ihHealMana = parseTextFieldToInt(autoHealBot.ihHealManaField.text),
      uhHealHealthPercent = parseTextFieldToInt(autoHealBot.uhHealHealthField.text),
      uhHealMana = parseTextFieldToInt(autoHealBot.uhHealManaField.text),
      hPotionHealHealthPercent = parseTextFieldToInt(autoHealBot.hPotionHealHealthField.text),
      hPotionHealMana = parseTextFieldToInt(autoHealBot.hPotionHealManaField.text),
      mPotionHealManaMin = parseTextFieldToInt(autoHealBot.mPotionHealManaMinField.text),
      friendsHealSettings = List(friendsHeal)
    )
  }


  def collectRuneMakingSettings(): RuneMakingSettings = RuneMakingSettings(

    enabled = runeMakerCheckbox.selected,
    makeRunes = runeMakerBot.makeRunesCheckbox.selected,
    selectedSpell = runeMakerBot.spellComboBox.selection.item,
    requiredMana = parseTextFieldToInt(runeMakerBot.manaTextField.text),
    stackConjuredAmmo = runeMakerBot.stackConjuredAmmoCheckbox.selected,
  )

  def collectHotkeysSettings(): HotkeysSettings = HotkeysSettings(
    enabled = hotkeysCheckbox.selected,
    hF1Field = hotkeysBot.hF1Field.text,
    hF2Field = hotkeysBot.hF2Field.text,
    hF3Field = hotkeysBot.hF3Field.text,
    hF4Field = hotkeysBot.hF4Field.text,
    hF5Field = hotkeysBot.hF5Field.text,
    hF6Field = hotkeysBot.hF6Field.text,
    hF7Field = hotkeysBot.hF7Field.text,
    hF8Field = hotkeysBot.hF8Field.text,
    hF9Field = hotkeysBot.hF9Field.text,
    hF10Field = hotkeysBot.hF10Field.text,
    hF11Field = hotkeysBot.hF11Field.text,
    hF12Field = hotkeysBot.hF12Field.text,
  )


  def collectGuardianSettings(): GuardianSettings = {

    val playerOnScreen = GuardianPlayerOnScreenSettings(
      playerOnScreenSound = guardianBot.playerOnScreenSoundCheckbox.selected,
      playerOnScreenMessage = guardianBot.playerOnScreenMessageCheckbox.selected,
      playerOnScreenDiscord = guardianBot.playerOnScreenDiscordCheckbox.selected,
      playerOnScreenLogout = guardianBot.playerOnScreenLogoutCheckbox.selected,
      playerOnScreenPz = guardianBot.playerOnScreenPzCheckbox.selected,
    )

    val playerDetected = GuardianPlayerDetectedSettings(
      playerDetectedSound = guardianBot.playerDetectedSoundCheckbox.selected,
      playerDetectedMessage = guardianBot.playerDetectedMessageCheckbox.selected,
      playerDetectedDiscord = guardianBot.playerDetectedDiscordCheckbox.selected,
      playerDetectedLogout = guardianBot.playerDetectedLogoutCheckbox.selected,
      playerDetectedPz = guardianBot.playerDetectedPzCheckbox.selected,
    )

    val playerAttacked = GuardianPlayerAttackedSettings(
      playerAttackedSound = guardianBot.playerAttackedSoundCheckbox.selected,
      playerAttackedMessage = guardianBot.playerAttackedMessageCheckbox.selected,
      playerAttackedDiscord = guardianBot.playerAttackedDiscordCheckbox.selected,
      playerAttackedLogout = guardianBot.playerAttackedLogoutCheckbox.selected,
      playerAttackedPz = guardianBot.playerAttackedPzCheckbox.selected,
    )

    val monsterOnScreen = GuardianMonsterOnScreenSettings(
      monsterOnScreenSound = guardianBot.monsterOnScreenSoundCheckbox.selected,
      monsterOnScreenMessage = guardianBot.monsterOnScreenMessageCheckbox.selected,
      monsterOnScreenDiscord = guardianBot.monsterOnScreenDiscordCheckbox.selected,
      monsterOnScreenLogout = guardianBot.monsterOnScreenLogoutCheckbox.selected,
      monsterOnScreenPz = guardianBot.monsterOnScreenPzCheckbox.selected,
    )

    val gmDetected = GuardianGMDetectedSettings(
      gmDetectedSound = guardianBot.gmDetectedSoundCheckbox.selected,
      gmDetectedMessage = guardianBot.gmDetectedMessageCheckbox.selected,
      gmDetectedDiscord = guardianBot.gmDetectedDiscordCheckbox.selected,
      gmDetectedLogout = guardianBot.gmDetectedLogoutCheckbox.selected,
      gmDetectedPz = guardianBot.gmDetectedPzCheckbox.selected,
    )

    val defaultMessage = GuardianDefaultMessageSettings(
      defaultMessageSound = guardianBot.defaultMessageSoundCheckbox.selected,
      defaultMessageMessage = guardianBot.defaultMessageMessageCheckbox.selected,
      defaultMessageDiscord = guardianBot.defaultMessageDiscordCheckbox.selected,
      defaultMessageLogout = guardianBot.defaultMessageLogoutCheckbox.selected,
      defaultMessagePz = guardianBot.defaultMessagePzCheckbox.selected,
    )

    val privateMessage = GuardianPrivateMessageSettings(
      privateMessageSound = guardianBot.privateMessageSoundCheckbox.selected,
      privateMessageMessage = guardianBot.privateMessageMessageCheckbox.selected,
      privateMessageDiscord = guardianBot.privateMessageDiscordCheckbox.selected,
      privateMessageLogout = guardianBot.privateMessageLogoutCheckbox.selected,
      privateMessagePz = guardianBot.privateMessagePzCheckbox.selected,
    )

    val lowCap = GuardianLowCapSettings(
      lowCapSound = guardianBot.lowCapSoundCheckbox.selected,
      lowCapMessage = guardianBot.lowCapMessageCheckbox.selected,
      lowCapDiscord = guardianBot.lowCapDiscordCheckbox.selected,
      lowCapLogout = guardianBot.lowCapLogoutCheckbox.selected,
      lowCapPz = guardianBot.lowCapPzCheckbox.selected,
    )

    val lowSupplies = GuardianLowSuppliesSettings(
      lowSuppliesSound = guardianBot.lowSuppliesSoundCheckbox.selected,
      lowSuppliesMessage = guardianBot.lowSuppliesMessageCheckbox.selected,
      lowSuppliesDiscord = guardianBot.lowSuppliesDiscordCheckbox.selected,
      lowSuppliesLogout = guardianBot.lowSuppliesLogoutCheckbox.selected,
      lowSuppliesPz = guardianBot.lowSuppliesPzCheckbox.selected,
    )

    GuardianSettings(
      enabled = guardianCheckbox.selected,
      ignoredCreatures = comboBoxToList(guardianBot.ignoredCreaturesDropdown.peer),
      discordWebhook = guardianBot.discordWebhookField.text,
      messageReceiverName = guardianBot.messageReceiverNameField.text,
      playerOnScreenSettings = List(playerOnScreen),
      playerDetectedSettings = List(playerDetected),
      playerAttackedSettings = List(playerAttacked),
      monsterOnScreenSettings = List(monsterOnScreen),
      gmDetectedSettings = List(gmDetected),
      defaultMessageSettings = List(defaultMessage),
      privateMessageSettings = List(privateMessage),
      lowCapSettings = List(lowCap),
      lowSuppliesSettings = List(lowSupplies)
    )
  }



  def collectFishingSettings(): FishingSettings = FishingSettings(
    enabled = fishingCheckbox.selected,
    selectedRectangles = fishingBot.selectedRectangles,
    fishThrowoutRectangles = fishingBot.fishThrowoutRectangles
  )

  def collectCaveBotSettings(): CaveBotSettings = CaveBotSettings(
    enabled = caveBotCheckbox.selected,
    waypointsList = jListToSeq(caveBotBot.waypointsList),
    gridInfoList = caveBotBot.getGridInfoList
  )

  def collectAutoLootSettings(): AutoLootSettings = AutoLootSettings(
    enabled = autoLootCheckbox.selected,
    lootList = jListToSeq(autoLootBot.lootList)
  )

  def collectAutoTargetSettings(): AutoTargetSettings = AutoTargetSettings(
    enabled = autoTargetCheckbox.selected,
    creatureList = jListToSeq(autoTargetBot.creatureList),
  )



  def collectAutoResponderSettings(): AutoResponderSettings = AutoResponderSettings(
    enabled = autoResponderCheckbox.selected,
    selectedStory = autoResponderBot.selectedStory,
    additionalStory = autoResponderBot.additionalStory.text,
  )

  def collectTeamHuntSettings(): TeamHuntSettings = TeamHuntSettings(
    enabled = teamHuntCheckbox.selected,
    followBlocker = teamHuntBot.followBlockerCheckbox.selected,
    blockerName = teamHuntBot.blockerName.text,
    youAreBlocker = teamHuntBot.youAreBlocker.selected,
    waitForTeam = teamHuntBot.waitCheckbox.selected,
    chaseMonsterAndExetaRes = teamHuntBot.chaseMonsterCheckbox.selected,
    teamMembersList = comboBoxToList(teamHuntBot.teamMembersDropdown.peer)  // Using the new function
  )





  def collectTrainingSettings(): TrainingSettings = TrainingSettings(
    enabled = trainingCheckbox.selected,
    pickAmmunition = trainingBot.pickAmmunitionCheckbox.selected,
    refillAmmunition = trainingBot.refillAmmunitionCheckbox.selected,
    doNotKillTarget = trainingBot.doNotKillTargetCheckbox.selected,
    switchAttackModeToEnsureDamage = trainingBot.switchAttackModeToEnsureDamageCheckbox.selected,
    switchWeaponToEnsureDamage = trainingBot.switchWeaponToEnsureDamageCheckbox.selected
  )


  def collectSettingsFromUI(): UISettings = UISettings(
    healingSettings = collectHealingSettings(),
    hotkeysSettings = collectHotkeysSettings(),
    runeMakingSettings = collectRuneMakingSettings(),
    guardianSettings = collectGuardianSettings(),
    fishingSettings = collectFishingSettings(),
    caveBotSettings = collectCaveBotSettings(),
    autoTargetSettings = collectAutoTargetSettings(),
    autoLootSettings = collectAutoLootSettings(),
    autoResponderSettings = collectAutoResponderSettings(),
    trainingSettings = collectTrainingSettings(),
    teamHuntSettings = collectTeamHuntSettings(),
    mouseMovements = mouseMovementsCheckbox.selected,
  )


  // Helper function to safely parse integer values from text fields
  def parseTextFieldToInt(text: String): Int = {
    text match {
      case s if s.trim.isEmpty => 0 // Handle empty fields
      case s => s.toIntOption.getOrElse(0) // Handle non-numeric input
    }
  }


  val saveButton = new Button("Save Settings") {
    reactions += {
      case ButtonClicked(_) =>
        println("Debug: Save button clicked")

        val settings = collectSettingsFromUI() // This collects current UI values
        println(s"Debug: Settings collected from UI: $settings")

        val chooser = new FileChooser(new java.io.File("C:\\MyLibraries\\botSettings"))
        chooser.title = "Save Settings"

        val result = chooser.showSaveDialog(null)
        println(s"Debug: FileChooser result: $result")

        if (result == FileChooser.Result.Approve) {
          val selectedFilePath = chooser.selectedFile.getAbsolutePath
          println(s"Debug: Selected file path: $selectedFilePath")

          saveSettingsToFile(settings, selectedFilePath)
        }
    }
  }


/*  val saveButton = new Button("Save Settings") {
    reactions += {
      case ButtonClicked(_) =>
        val settings = collectSettingsFromUI() // This collects current UI values
        val chooser = new FileChooser(new java.io.File("C:\\MyLibraries\\botSettings"))
        chooser.title = "Save Settings"
        val result = chooser.showSaveDialog(null)
        if (result == FileChooser.Result.Approve) {
          SettingsUtils.saveSettingsToFile(settings, chooser.selectedFile.getAbsolutePath)
        }
    }
  }*/

  val loadButton = new Button("Load Settings") {
    reactions += {
      case ButtonClicked(_) =>
        println("Load button clicked") // Confirm button press
        val chooser = new FileChooser(new java.io.File("C:\\MyLibraries\\botSettings"))
        chooser.title = "Load Settings"
        val result = chooser.showOpenDialog(null)
        println(s"FileChooser result: $result") // Check the result of the file chooser
        if (result == FileChooser.Result.Approve) {
          println(s"File chosen: ${chooser.selectedFile.getAbsolutePath}") // Log the path of the selected file
          SettingsUtils.loadSettingsFromFile(chooser.selectedFile.getAbsolutePath) match {
            case Some(settings) =>
              println("Settings loaded successfully, applying to UI...") // Confirm settings were loaded
              applySettingsToUI(settings)
            case None =>
              println("Failed to load settings from file.") // Indicate failure
          }
        } else {
          println("File selection was cancelled or failed.") // Log cancellation or failure
        }
    }
  }


  def setListModel(jList: JList[String], items: Seq[String]): Unit = {
    val model = new DefaultListModel[String]()
    items.foreach(model.addElement)
    jList.setModel(model)
  }


  def applySettingsToUI(settings: UISettings): Unit = {
    // General settings
    mouseMovementsCheckbox.selected = settings.mouseMovements

    // Apply individual settings modules
    applyHealingSettings(settings.healingSettings)
    applyCaveBotSettings(settings.caveBotSettings)
    applyAutoTargetSettings(settings.autoTargetSettings)
    applyAutoLootSettings(settings.autoLootSettings)
    applyAutoResponderSettings(settings.autoResponderSettings)
    applyRuneMakingSettings(settings.runeMakingSettings)
    applyGuardianSettings(settings.guardianSettings)
    applyFishingSettings(settings.fishingSettings)
    applyTeamHuntSettings(settings.teamHuntSettings)
    applyHotkeysSettings(settings.hotkeysSettings)
    applyTrainingSettings(settings.trainingSettings)
  }

  def setDropdownSelection(dropdown: JComboBox[String], value: String): Unit = {
    val index = autoHealBot.funcButtons.indexOf(value)
    if (index != -1) {
      dropdown.setSelectedIndex(index)
    }
  }

  def applyHealingSettings(healingSettings: HealingSettings): Unit = {
    autoHealCheckbox.selected = healingSettings.enabled

    // Assuming the list of spellsHeal contains exactly two elements: light and strong heal
    val spellsHeal= healingSettings.spellsHealSettings.headOption.getOrElse(
      HealingSpellsSettings("", 0, 0, false, "", "", 0, 0, false, "")
    )

    // Assuming the list of friendsHeal contains exactly one element
    val friendsHeal = healingSettings.friendsHealSettings.headOption.getOrElse(
      HealingFriendsSettings("", "", 0, 0, false, "", "", "", 0, 0, false, "", "", "", 0, 0, false, "")
    )

    // Light spell
    autoHealBot.lightHealSpellField.text = spellsHeal.lightHealSpell
    autoHealBot.lightHealHealthField.text = spellsHeal.lightHealHealthPercent.toString
    autoHealBot.lightHealManaField.text = spellsHeal.lightHealMana.toString
    autoHealBot.lightHealHotkeyCheckbox.selected = spellsHeal.lightHealHotkeyEnabled
    setDropdownSelection(autoHealBot.lightHealHotkeyDropdown, spellsHeal.lightHealHotkey)

    // Strong spell
    autoHealBot.strongHealSpellField.text = spellsHeal.strongHealSpell
    autoHealBot.strongHealHealthField.text = spellsHeal.strongHealHealthPercent.toString
    autoHealBot.strongHealManaField.text = spellsHeal.strongHealMana.toString
    autoHealBot.strongHealHotkeyCheckbox.selected = spellsHeal.strongHealHotkeyEnabled
    setDropdownSelection(autoHealBot.strongHealHotkeyDropdown, spellsHeal.strongHealHotkey)

    // Other healing settings
    autoHealBot.ihHealHealthField.text = healingSettings.ihHealHealthPercent.toString
    autoHealBot.ihHealManaField.text = healingSettings.ihHealMana.toString
    autoHealBot.uhHealHealthField.text = healingSettings.uhHealHealthPercent.toString
    autoHealBot.uhHealManaField.text = healingSettings.uhHealMana.toString
    autoHealBot.hPotionHealHealthField.text = healingSettings.hPotionHealHealthPercent.toString
    autoHealBot.hPotionHealManaField.text = healingSettings.hPotionHealMana.toString
    autoHealBot.mPotionHealManaMinField.text = healingSettings.mPotionHealManaMin.toString



    // Friend 1 settings
    autoHealBot.friend1HealSpellField.text = friendsHeal.friend1HealSpell
    autoHealBot.friend1NameField.text = friendsHeal.friend1Name
    autoHealBot.friend1HealHealthField.text = friendsHeal.friend1HealHealthPercent.toString
    autoHealBot.friend1HealManaField.text = friendsHeal.friend1HealMana.toString
    autoHealBot.friend1HealHotkeyCheckbox.selected = friendsHeal.friend1HealHotkeyEnabled
    setDropdownSelection(autoHealBot.friend1HealHotkeyDropdown, friendsHeal.friend1HealHotkey)

    // Friend 2 settings
    autoHealBot.friend2HealSpellField.text = friendsHeal.friend2HealSpell
    autoHealBot.friend2NameField.text = friendsHeal.friend2Name
    autoHealBot.friend2HealHealthField.text = friendsHeal.friend2HealHealthPercent.toString
    autoHealBot.friend2HealManaField.text = friendsHeal.friend2HealMana.toString
    autoHealBot.friend2HealHotkeyCheckbox.selected = friendsHeal.friend2HealHotkeyEnabled
    setDropdownSelection(autoHealBot.friend2HealHotkeyDropdown, friendsHeal.friend2HealHotkey)

    // Friend 3 settings
    autoHealBot.friend3HealSpellField.text = friendsHeal.friend3HealSpell
    autoHealBot.friend3NameField.text = friendsHeal.friend3Name
    autoHealBot.friend3HealHealthField.text = friendsHeal.friend3HealHealthPercent.toString
    autoHealBot.friend3HealManaField.text = friendsHeal.friend3HealMana.toString
    autoHealBot.friend3HealHotkeyCheckbox.selected = friendsHeal.friend3HealHotkeyEnabled
    setDropdownSelection(autoHealBot.friend3HealHotkeyDropdown, friendsHeal.friend3HealHotkey)
  }

  def applyCaveBotSettings(caveBotSettings: CaveBotSettings): Unit = {
    caveBotCheckbox.selected = caveBotSettings.enabled
    setListModel(caveBotBot.waypointsList, caveBotSettings.waypointsList)
    setListModelFromGridInfos(caveBotBot.gridInfoList, caveBotSettings.gridInfoList)
  }


  def applyTeamHuntSettings(teamHuntSettings: TeamHuntSettings): Unit = {
    teamHuntCheckbox.selected = teamHuntSettings.enabled
    teamHuntBot.followBlockerCheckbox.selected = teamHuntSettings.followBlocker
    teamHuntBot.blockerName.text = teamHuntSettings.blockerName
    teamHuntBot.youAreBlocker.selected = teamHuntSettings.youAreBlocker
    teamHuntBot.waitCheckbox.selected = teamHuntSettings.waitForTeam
    teamHuntBot.chaseMonsterCheckbox.selected = teamHuntSettings.chaseMonsterAndExetaRes
    setComboBoxModel(teamHuntBot.teamMembersDropdown.peer, teamHuntSettings.teamMembersList)
  }



  def applyHotkeysSettings(hotkeysSettings: HotkeysSettings): Unit = {
    // Apply the enabled state to the hotkeys checkbox
    hotkeysCheckbox.selected = hotkeysSettings.enabled

    // Set the text fields for each hotkey based on the settings
    hotkeysBot.hF1Field.text = hotkeysSettings.hF1Field
    hotkeysBot.hF2Field.text = hotkeysSettings.hF2Field
    hotkeysBot.hF3Field.text = hotkeysSettings.hF3Field
    hotkeysBot.hF4Field.text = hotkeysSettings.hF4Field
    hotkeysBot.hF5Field.text = hotkeysSettings.hF5Field
    hotkeysBot.hF6Field.text = hotkeysSettings.hF6Field
    hotkeysBot.hF7Field.text = hotkeysSettings.hF7Field
    hotkeysBot.hF8Field.text = hotkeysSettings.hF8Field
    hotkeysBot.hF9Field.text = hotkeysSettings.hF9Field
    hotkeysBot.hF10Field.text = hotkeysSettings.hF10Field
    hotkeysBot.hF11Field.text = hotkeysSettings.hF11Field
    hotkeysBot.hF12Field.text = hotkeysSettings.hF12Field
  }


  def applyAutoResponderSettings(autoResponderSettings: AutoResponderSettings): Unit = {
    autoResponderCheckbox.selected = autoResponderSettings.enabled
    autoResponderBot.selectedStory = autoResponderSettings.selectedStory
    autoResponderBot.additionalStory.text = autoResponderSettings.additionalStory
  }

  def applyAutoTargetSettings(autoTargetSettings: AutoTargetSettings): Unit = {
    autoTargetCheckbox.selected = autoTargetSettings.enabled
    setListModel(autoTargetBot.creatureList, autoTargetSettings.creatureList)
  }


  def applyAutoLootSettings(autoLootSettings: AutoLootSettings): Unit = {
    autoLootCheckbox.selected = autoLootSettings.enabled
    setListModel(autoLootBot.lootList, autoLootSettings.lootList)
  }


  def applyRuneMakingSettings(runeMakingSettings: RuneMakingSettings): Unit = {
    runeMakerCheckbox.selected = runeMakingSettings.enabled
    runeMakerBot.makeRunesCheckbox.selected = runeMakingSettings.makeRunes
    runeMakerBot.stackConjuredAmmoCheckbox.selected = runeMakingSettings.stackConjuredAmmo
    // Similarly, apply rune making specific settings here
  }

  def applyFishingSettings(fishingSettings: FishingSettings): Unit = {
    fishingCheckbox.selected = fishingSettings.enabled
    fishingBot.selectedRectangles = fishingSettings.selectedRectangles
    fishingBot.fishThrowoutRectangles = fishingSettings.fishThrowoutRectangles

  }


  def applyGuardianSettings(guardianSettings: GuardianSettings): Unit = {

    val playerOnScreen = guardianSettings.playerOnScreenSettings.headOption.getOrElse(
      GuardianPlayerOnScreenSettings(false, false, false, false, false)
    )

    val playerDetected = guardianSettings.playerDetectedSettings.headOption.getOrElse(
      GuardianPlayerDetectedSettings(false, false, false, false, false)
    )

    val playerAttacked = guardianSettings.playerAttackedSettings.headOption.getOrElse(
      GuardianPlayerAttackedSettings(false, false, false, false, false)
    )

    val monsterOnScreen = guardianSettings.monsterOnScreenSettings.headOption.getOrElse(
      GuardianMonsterOnScreenSettings(false, false, false, false, false)
    )

    val gmDetected = guardianSettings.gmDetectedSettings.headOption.getOrElse(
      GuardianGMDetectedSettings(false, false, false, false, false)
    )

    val defaultMessage = guardianSettings.defaultMessageSettings.headOption.getOrElse(
      GuardianDefaultMessageSettings(false, false, false, false, false)
    )

    val privateMessage = guardianSettings.privateMessageSettings.headOption.getOrElse(
      GuardianPrivateMessageSettings(false, false, false, false, false)
    )

    val lowCap = guardianSettings.lowCapSettings.headOption.getOrElse(
      GuardianLowCapSettings(false, false, false, false, false)
    )

    val lowSupplies = guardianSettings.lowSuppliesSettings.headOption.getOrElse(
      GuardianLowSuppliesSettings(false, false, false, false, false)
    )

    guardianCheckbox.selected = guardianSettings.enabled
//    setComboBoxModel(guardianBot.ignoredCreaturesDropdown.peer, guardianSettings.ignoredCreatures)
    guardianBot.discordWebhookField.text = guardianSettings.discordWebhook
    guardianBot.messageReceiverNameField.text = guardianSettings.messageReceiverName

    guardianBot.playerOnScreenSoundCheckbox.selected = playerOnScreen.playerOnScreenSound
    guardianBot.playerOnScreenMessageCheckbox.selected = playerOnScreen.playerOnScreenMessage
    guardianBot.playerOnScreenDiscordCheckbox.selected = playerOnScreen.playerOnScreenDiscord
    guardianBot.playerOnScreenLogoutCheckbox.selected = playerOnScreen.playerOnScreenLogout
    guardianBot.playerOnScreenPzCheckbox.selected = playerOnScreen.playerOnScreenPz

    guardianBot.playerDetectedSoundCheckbox.selected = playerDetected.playerDetectedSound
    guardianBot.playerDetectedMessageCheckbox.selected = playerDetected.playerDetectedMessage
    guardianBot.playerDetectedDiscordCheckbox.selected = playerDetected.playerDetectedDiscord
    guardianBot.playerDetectedLogoutCheckbox.selected = playerDetected.playerDetectedLogout
    guardianBot.playerDetectedPzCheckbox.selected = playerDetected.playerDetectedPz

    guardianBot.playerAttackedSoundCheckbox.selected = playerAttacked.playerAttackedSound
    guardianBot.playerAttackedMessageCheckbox.selected = playerAttacked.playerAttackedMessage
    guardianBot.playerAttackedDiscordCheckbox.selected = playerAttacked.playerAttackedDiscord
    guardianBot.playerAttackedLogoutCheckbox.selected = playerAttacked.playerAttackedLogout
    guardianBot.playerAttackedPzCheckbox.selected = playerAttacked.playerAttackedPz

    guardianBot.monsterOnScreenSoundCheckbox.selected = monsterOnScreen.monsterOnScreenSound
    guardianBot.monsterOnScreenMessageCheckbox.selected = monsterOnScreen.monsterOnScreenMessage
    guardianBot.monsterOnScreenDiscordCheckbox.selected = monsterOnScreen.monsterOnScreenDiscord
    guardianBot.monsterOnScreenLogoutCheckbox.selected = monsterOnScreen.monsterOnScreenLogout
    guardianBot.monsterOnScreenPzCheckbox.selected = monsterOnScreen.monsterOnScreenPz

    guardianBot.gmDetectedSoundCheckbox.selected = gmDetected.gmDetectedSound
    guardianBot.gmDetectedMessageCheckbox.selected = gmDetected.gmDetectedMessage
    guardianBot.gmDetectedDiscordCheckbox.selected = gmDetected.gmDetectedDiscord
    guardianBot.gmDetectedLogoutCheckbox.selected = gmDetected.gmDetectedLogout
    guardianBot.gmDetectedPzCheckbox.selected = gmDetected.gmDetectedPz

    guardianBot.defaultMessageSoundCheckbox.selected = defaultMessage.defaultMessageSound
    guardianBot.defaultMessageMessageCheckbox.selected = defaultMessage.defaultMessageMessage
    guardianBot.defaultMessageDiscordCheckbox.selected = defaultMessage.defaultMessageDiscord
    guardianBot.defaultMessageLogoutCheckbox.selected = defaultMessage.defaultMessageLogout
    guardianBot.defaultMessagePzCheckbox.selected = defaultMessage.defaultMessagePz

    guardianBot.privateMessageSoundCheckbox.selected = privateMessage.privateMessageSound
    guardianBot.privateMessageMessageCheckbox.selected = privateMessage.privateMessageMessage
    guardianBot.privateMessageDiscordCheckbox.selected = privateMessage.privateMessageDiscord
    guardianBot.privateMessageLogoutCheckbox.selected = privateMessage.privateMessageLogout
    guardianBot.privateMessagePzCheckbox.selected = privateMessage.privateMessagePz

    guardianBot.lowCapSoundCheckbox.selected = lowCap.lowCapSound
    guardianBot.lowCapMessageCheckbox.selected = lowCap.lowCapMessage
    guardianBot.lowCapDiscordCheckbox.selected = lowCap.lowCapDiscord
    guardianBot.lowCapLogoutCheckbox.selected = lowCap.lowCapLogout
    guardianBot.lowCapPzCheckbox.selected = lowCap.lowCapPz

    guardianBot.lowSuppliesSoundCheckbox.selected = lowSupplies.lowSuppliesSound
    guardianBot.lowSuppliesMessageCheckbox.selected = lowSupplies.lowSuppliesMessage
    guardianBot.lowSuppliesDiscordCheckbox.selected = lowSupplies.lowSuppliesDiscord
    guardianBot.lowSuppliesLogoutCheckbox.selected = lowSupplies.lowSuppliesLogout
    guardianBot.lowSuppliesPzCheckbox.selected = lowSupplies.lowSuppliesPz

  }

  def applyTrainingSettings(trainingSettings: TrainingSettings): Unit = {
    trainingCheckbox.selected = trainingSettings.enabled
    trainingBot.pickAmmunitionCheckbox.selected = trainingSettings.pickAmmunition
    trainingBot.refillAmmunitionCheckbox.selected = trainingSettings.refillAmmunition
    trainingBot.doNotKillTargetCheckbox.selected = trainingSettings.doNotKillTarget
    trainingBot.switchAttackModeToEnsureDamageCheckbox.selected = trainingSettings.switchAttackModeToEnsureDamage
    trainingBot.switchWeaponToEnsureDamageCheckbox.selected = trainingSettings.switchWeaponToEnsureDamage
  }


  def applyGeneralSettings(settings: UISettings): Unit = {
//    fishingCheckbox.selected = settings.fishingSettings.enabled
    mouseMovementsCheckbox.selected = settings.mouseMovements
  }


  val currentPlayer: Player = playerClassList.head




  val runButton = new Button("RUN") {
    reactions += {
      case ButtonClicked(_) =>
        val currentSettings = collectSettingsFromUI()

        // 1) overwrite the shared Ref with whatever’s in the UI right now
        import cats.effect.unsafe.implicits.global
        settingsRef.update(_ => currentSettings).unsafeRunSync()

        // 2) fire off your actors with the brand-new settings
        uiAppActor ! MainApp.StartActors(currentSettings)
        periodicFunctionActorRef ! MainApp.StartActors(currentSettings)
        jsonProcessorActorRef  ! InitializeProcessor(currentPlayer, currentSettings)
        autoResponderManagerRef ! UpdateSettings(currentSettings)
    }
  }


  val updateButton = new Button("Update Settings") {
    reactions += {
      case ButtonClicked(_) =>
        val updatedSettings = collectSettingsFromUI()
        // Send the updated settings to the actors
        println(s"Sending UpdateSettings to JsonProcessorActor: $updatedSettings")
        jsonProcessorActorRef ! MainApp.UpdateSettings(updatedSettings)
//        periodicFunctionActorRef ! MainApp.UpdateSettings(updatedSettings)
//        uiAppActorRef ! MainApp.UpdateSettings(updatedSettings)
//        mainActorRef ! MainApp.UpdateSettings(updatedSettings)

        println("Settings have been updated and sent to the actors.")
    }
  }


  // Initialize components here similar to the second snippet...
  val autoHealCheckbox = new CheckBox("Auto Heal")
  val hotkeysCheckbox = new CheckBox("Hotkeys")
  val runeMakerCheckbox = new CheckBox("Rune Maker")
  val trainingCheckbox = new CheckBox("Training")
  val caveBotCheckbox = new CheckBox("Cave Bot")
  val teamHuntCheckbox = new CheckBox("Team Hunt")
  val fishingCheckbox = new CheckBox("Fishing")
  val autoResponderCheckbox = new CheckBox("Auto Responder")
  val mouseMovementsCheckbox = new CheckBox("Mouse Movements")
  val guardianCheckbox = new CheckBox("Guardian")
  val autoTargetCheckbox = new CheckBox("Auto Target")
  val autoLootCheckbox = new CheckBox("Auto Loot")


  // ...and other fields and buttons as in the second snippet

  // Define UI behavior and event handling here, similar to the second snippet...
  listenTo(autoHealCheckbox, runeMakerCheckbox, trainingCheckbox, caveBotCheckbox,
    autoResponderCheckbox, guardianCheckbox, fishingCheckbox,
    mouseMovementsCheckbox, autoTargetCheckbox,
    autoLootCheckbox,teamHuntCheckbox,hotkeysCheckbox
  )

  reactions += {
    case ButtonClicked(`autoHealCheckbox`) =>
      println("Auto Heal Checkbox clicked")

    case ButtonClicked(`hotkeysCheckbox`) =>
      println("Hotkeys Checkbox clicked")

    case ButtonClicked(`runeMakerCheckbox`) =>
      println("Rune Maker Checkbox clicked")

    case ButtonClicked(`trainingCheckbox`) =>
      println("Training Checkbox clicked")

    case ButtonClicked(`caveBotCheckbox`) =>
      println("Cave Bot Checkbox clicked")

    case ButtonClicked(`teamHuntCheckbox`) =>
      println("Team Hunt Checkbox clicked")

    case ButtonClicked(`autoTargetCheckbox`) =>
      println("Auto Target Checkbox clicked")
    // Add logic for when caveBotCheckbox is clicked

    case ButtonClicked(`guardianCheckbox`) =>
      println("Guardian Checkbox clicked")

    case ButtonClicked(`autoResponderCheckbox`) =>
      println("Auto Responder Checkbox clicked")

    case ButtonClicked(`fishingCheckbox`) =>
      println("Fishing Checkbox clicked")

    case ButtonClicked(`mouseMovementsCheckbox`) =>
      println("Mouse Movements Checkbox clicked")

    case ButtonClicked(`autoLootCheckbox`) =>
      println("Auto Loot Checkbox clicked")


    case SelectionChanged(`exampleDropdown`) =>
      println("Dropdown selection changed")


    // Add logic for when the selection in exampleDropdown changes
  }




  // Layout your UI components here
  contents = new TabbedPane {
    pages += new TabbedPane.Page("Main", new Component {
      override lazy val peer: javax.swing.JPanel = new javax.swing.JPanel(new GridBagLayout())
      val c = new GridBagConstraints()
      c.insets = new Insets(5, 5, 5, 5)
      c.fill = GridBagConstraints.HORIZONTAL

      // Adding Checkboxes in the first column
      val checkBoxComponents = Seq(autoHealCheckbox, hotkeysCheckbox, runeMakerCheckbox, trainingCheckbox,
        caveBotCheckbox, teamHuntCheckbox, fishingCheckbox, mouseMovementsCheckbox,autoResponderCheckbox,
        guardianCheckbox, autoTargetCheckbox, autoLootCheckbox)

      for ((checkbox, idx) <- checkBoxComponents.zipWithIndex) {
        c.gridx = 0
        c.gridy = idx
        c.gridwidth = 1
        peer.add(checkbox.peer, c)
      }

      // Adding Buttons in the second column
      val buttonComponents = Seq(runButton, updateButton, saveButton, loadButton)

      for ((button, idx) <- buttonComponents.zipWithIndex) {
        c.gridx = 1
        c.gridy = idx
        c.gridwidth = 1
        peer.add(button.peer, c)
      }
    })

//    pages += new TabbedPane.Page("Main Tab", mainBot.mainTab)

    pages += new TabbedPane.Page("Auto Heal", autoHealBot.autoHealTab)

    pages += new TabbedPane.Page("Hotkeys", hotkeysBot.hotkeysTab)

    pages += new TabbedPane.Page("Cave Bot", caveBotBot.caveBotTab)

    pages += new TabbedPane.Page("Team Hunt", teamHuntBot.teamHuntTab)

    pages += new TabbedPane.Page("Auto Target", autoTargetBot.autoTargetTab)

    pages += new TabbedPane.Page("Auto Loot", autoLootBot.autoLootTab)

    pages += new TabbedPane.Page("Rune Maker", runeMakerBot.runeMakerTab)

    pages += new TabbedPane.Page("Trainer", trainingBot.trainerTab)

    pages += new TabbedPane.Page("Auto Responder", autoResponderBot.autoResponderTab)

    pages += new TabbedPane.Page("Fishing", fishingBot.fishingTab)

    pages += new TabbedPane.Page("Guardian", guardianBot.guardianTab)


  }



  def saveExample(): Unit = {
    val selectedName = exampleDropdown.selection.item
    val selectedExample = exampleMap(selectedName)
    val lightHealSpellVar = lightHealSpellField.text
    val lightHealHealthVar = lightHealHealthField.text.toInt
    val lightHealManaVar = lightHealManaField.text.toInt
    val strongHealSpellVar = strongHealSpellField.text
    val strongHealHealthVar = strongHealHealthField.text.toInt
    val strongHealManaVar = strongHealManaField.text.toInt
    val ihHealHealthVar = ihHealManaField.text.toInt
    val ihHealManaVar = ihHealManaField.text.toInt
    val uhHealHealthVar = uhHealHealthField.text.toInt
    val uhHealManaVar = uhHealManaField.text.toInt
    val hPotionHealHealthVar = hPotionHealHealthField.text.toInt
    val hPotionHealManaVar = hPotionHealManaField.text.toInt
    val mPotionHealManaMinVar = mPotionHealManaMinField.text.toInt

    selectedExample.updateAutoHeal(lightHealSpellVar, lightHealHealthVar, lightHealManaVar,
      strongHealSpellVar, strongHealHealthVar, strongHealManaVar,
      ihHealHealthVar, ihHealManaVar,
      uhHealHealthVar, uhHealManaVar,
      hPotionHealHealthVar, hPotionHealManaVar,
      mPotionHealManaMinVar)
  }


  def updateExample(): Unit = {
    val selectedName = exampleDropdown.selection.item
    val selectedExample = exampleMap(selectedName)
    exampleLabel.text = s"Name: ${selectedExample.characterName}, Level: ${selectedExample.charLevel}"
    lightHealSpellField.text = selectedExample.botLightHealSpell.toString
    lightHealHealthField.text = selectedExample.botLightHealHealth.toString
    lightHealManaField.text = selectedExample.botLightHealMana.toString
    strongHealSpellField.text = selectedExample.botStrongHealSpell.toString
    strongHealHealthField.text = selectedExample.botStrongHealHealth.toString
    strongHealManaField.text = selectedExample.botStrongHealMana.toString
    ihHealHealthField.text = selectedExample.botIhHealHealth.toString
    ihHealManaField.text = selectedExample.botIhHealMana.toString
    uhHealHealthField.text = selectedExample.botUhHealHealth.toString
    uhHealManaField.text = selectedExample.botUhHealMana.toString
    hPotionHealHealthField.text = selectedExample.botHPotionHealHealth.toString
    hPotionHealManaField.text = selectedExample.botHPotionHealMana.toString
    mPotionHealManaMinField.text = selectedExample.botMPotionHealManaMin.toString
  }


  val lightHealSpellField = new TextField()
  val lightHealHealthField = new TextField()
  val lightHealManaField = new TextField()
  val strongHealSpellField = new TextField()
  val strongHealHealthField = new TextField()
  val strongHealManaField = new TextField()
  val ihHealHealthField = new TextField()
  val ihHealManaField = new TextField()
  val uhHealHealthField = new TextField()
  val uhHealManaField = new TextField()
  val hPotionHealHealthField = new TextField()
  val hPotionHealManaField = new TextField()
  val mPotionHealManaMinField = new TextField()


//  val updateButton = new Button("Update") {
//    reactions += {
//      case ButtonClicked(_) =>
//        println("Update button clicked")
//        saveExample()
//        updateExample()
//    }
//  }

  def setListModelFromGridInfos(jList: JList[GridInfo], seq: Seq[String]): Unit = {
//    println(s"Setting list model from grid infos: $seq")
    val model = new DefaultListModel[GridInfo]()
    seq.foreach { serializedGrid =>
//      println(s"Deserializing grid: $serializedGrid")
      val gridInfo = new GridInfo(serializedGrid)
//      println(s"Deserialized GridInfo: ${gridInfo.serialize}")
      model.addElement(gridInfo)
    }
    jList.setModel(model)
//    println(s"Model set with ${model.getSize} grid infos.")
  }

  // Converts JComboBox items to Seq[String]
  def comboBoxToSeq(comboBox: JComboBox[String]): Seq[String] = {
    val model = comboBox.getModel
    val size = model.getSize
    (0 until size).map(i => model.getElementAt(i))
  }

  // Converts JComboBox items to List[String]
  def comboBoxToList(comboBox: JComboBox[String]): List[String] = {
    val model = comboBox.getModel
    val size = model.getSize
    (0 until size).map(i => model.getElementAt(i)).toList  // Directly converting to List
  }


  // Sets model for JComboBox from Seq[String]
  def setComboBoxModel(comboBox: JComboBox[String], items: Seq[String]): Unit = {
    val model = new DefaultComboBoxModel[String](items.toArray)
    comboBox.setModel(model)
  }


}

