package userUI
import java.awt.GridBagLayout
import java.awt.GridBagConstraints
import processing.{ConnectToServer, InitializeProcessor}
import main.scala.MainApp.{jsonProcessorActorRef, periodicFunctionActorRef}
import play.api.libs.json.{Format, Json}

import javax.swing.{DefaultListModel, JList}
//import userUI.SettingsUtils.{HealingSettings, ProtectionZoneSettings, RuneMakingSettings, UISettings, saveSettingsToFile}
import userUI.SettingsUtils._
import scala.swing.{Component, Dialog, FileChooser, Insets}
import akka.actor.ActorRef
import main.scala.MainApp
import player.Player
import utils.StartMouseMovementActor

import java.awt.Dimension
import scala.swing.{BoxPanel, Button, CheckBox, ComboBox, Label, MainFrame, Orientation, TabbedPane, TextField}
import scala.swing.event.{ButtonClicked, SelectionChanged}


class SwingApp(playerClassList: List[Player],
               uiAppActor: ActorRef,
               jsonProcessorActor: ActorRef,
               periodicFunctionActor: ActorRef,
               thirdProcessActor: ActorRef,
               mainActorRef: ActorRef) extends MainFrame {

  title = "TibiaYBB - Younger Brother Bot"
  preferredSize = new Dimension(600, 300)
  var runningBot = false

  // Initialize AutoHeal class
  val mainBot = new MainBot(currentPlayer, uiAppActor, jsonProcessorActor)
  val autoHealBot = new AutoHealBot(currentPlayer, uiAppActor, jsonProcessorActor)
  val caveBotBot = new CaveBotBot(currentPlayer, uiAppActor, jsonProcessorActor)
  val autoTargetBot = new AutoTargetBot(currentPlayer, uiAppActor, jsonProcessorActor)
  val autoLootBot = new AutoLootBot(currentPlayer, uiAppActor, jsonProcessorActor)
  val runeMakerBot = new RuneMakerBot(currentPlayer, uiAppActor, jsonProcessorActor)
  val fishingBot = new FishingBot(currentPlayer, uiAppActor, jsonProcessorActor)
  val protectionZoneBot = new ProtectionZoneBot(currentPlayer, uiAppActor, jsonProcessorActor)
  val trainingBot = new TrainingBot(currentPlayer, uiAppActor, jsonProcessorActor)
  val autoResponderBot = new AutoResponderBot(currentPlayer, uiAppActor, jsonProcessorActor)
  val teamHuntBot = new TeamHuntBot(currentPlayer, uiAppActor, jsonProcessorActor)

  val exampleNames = playerClassList.map(_.characterName)
  val exampleMap = playerClassList.map(e => e.characterName -> e).toMap

  val exampleDropdown = new ComboBox(exampleNames)
  val exampleLabel = new Label()


  def collectHealingSettings(): HealingSettings = HealingSettings(
    enabled = autoHealCheckbox.selected,
    lightHealSpell = autoHealBot.lightHealSpellField.text,
    lightHealHealth = parseTextFieldToInt(autoHealBot.lightHealHealthField.text),
    lightHealMana = parseTextFieldToInt(autoHealBot.lightHealManaField.text),
    strongHealSpell = autoHealBot.strongHealSpellField.text,
    strongHealHealth = parseTextFieldToInt(autoHealBot.strongHealHealthField.text),
    strongHealMana = parseTextFieldToInt(autoHealBot.strongHealManaField.text),
    ihHealHealth = parseTextFieldToInt(autoHealBot.ihHealHealthField.text),
    ihHealMana = parseTextFieldToInt(autoHealBot.ihHealManaField.text),
    uhHealHealth = parseTextFieldToInt(autoHealBot.uhHealHealthField.text),
    uhHealMana = parseTextFieldToInt(autoHealBot.uhHealManaField.text),
    hPotionHealHealth = parseTextFieldToInt(autoHealBot.hPotionHealHealthField.text),
    hPotionHealMana = parseTextFieldToInt(autoHealBot.hPotionHealManaField.text),
    mPotionHealManaMin = parseTextFieldToInt(autoHealBot.mPotionHealManaMinField.text)
  )

  def collectRuneMakingSettings(): RuneMakingSettings = RuneMakingSettings(
    enabled = runeMakerCheckbox.selected,
    selectedSpell = runeMakerBot.spellComboBox.selection.item,
    requiredMana = parseTextFieldToInt(runeMakerBot.manaTextField.text)
  )


  def collectProtectionZoneSettings(): ProtectionZoneSettings = ProtectionZoneSettings(
    enabled = protectionZoneCheckbox.selected,
    playerOnScreenAlert = protectionZoneBot.playerOnScreenAlertCheckbox.selected,
    escapeToProtectionZone = protectionZoneBot.escapeToProtectionZoneCheckbox.selected,
    ignoredCreatures = protectionZoneBot.getIgnoredCreatures
  )

  def collectFishingSettings(): FishingSettings = FishingSettings(
    enabled = fishingCheckbox.selected,
    selectedRectangles = fishingBot.selectedRectangles
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
    targetMonstersOnBattle = autoTargetBot.targetMonstersOnBattleCheckbox.selected
  )


  def collectAutoResponderSettings(): AutoResponderSettings = AutoResponderSettings(
    enabled = autoResponderCheckbox.selected
  )

  def collectTeamHuntSettings(): TeamHuntSettings = TeamHuntSettings(
    enabled = teamHuntCheckbox.selected,
    followBlocker = teamHuntBot.followBlockerCheckbox.selected,
    blockerName = teamHuntBot.blockerName.text,
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
    runeMakingSettings = collectRuneMakingSettings(),
    protectionZoneSettings = collectProtectionZoneSettings(),
    fishingSettings = collectFishingSettings(),
    caveBotSettings = collectCaveBotSettings(),
    autoTargetSettings = collectAutoTargetSettings(),
    autoLootSettings = collectAutoLootSettings(),
    autoResponderSettings = collectAutoResponderSettings(),
    trainingSettings = collectTrainingSettings(),
    teamHuntSettings = collectTeamHuntSettings(),
    mouseMovements = mouseMovementsCheckbox.selected
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
        val settings = collectSettingsFromUI() // This collects current UI values
        val chooser = new FileChooser(new java.io.File("C:\\MyLibraries\\botSettings"))
        chooser.title = "Save Settings"
        val result = chooser.showSaveDialog(null)
        if (result == FileChooser.Result.Approve) {
          SettingsUtils.saveSettingsToFile(settings, chooser.selectedFile.getAbsolutePath)
        }
    }
  }

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

  }

  //   Detailed implementation for each settings group, example:
  def applyHealingSettings(healingSettings: HealingSettings): Unit = {
    autoHealCheckbox.selected = healingSettings.enabled
    autoHealBot.lightHealSpellField.text = healingSettings.lightHealSpell
    autoHealBot.lightHealHealthField.text = healingSettings.lightHealHealth.toString
    autoHealBot.lightHealManaField.text = healingSettings.lightHealMana.toString
    autoHealBot.strongHealSpellField.text = healingSettings.strongHealSpell
    autoHealBot.strongHealHealthField.text = healingSettings.strongHealHealth.toString
    autoHealBot.strongHealManaField.text = healingSettings.strongHealMana.toString
    autoHealBot.ihHealHealthField.text = healingSettings.ihHealHealth.toString
    autoHealBot.ihHealManaField.text = healingSettings.ihHealMana.toString
    autoHealBot.uhHealHealthField.text = healingSettings.uhHealHealth.toString
    autoHealBot.uhHealManaField.text = healingSettings.uhHealMana.toString
    autoHealBot.hPotionHealHealthField.text = healingSettings.hPotionHealHealth.toString
    autoHealBot.hPotionHealManaField.text = healingSettings.hPotionHealMana.toString
    autoHealBot.mPotionHealManaMinField.text = healingSettings.mPotionHealManaMin.toString
  }


  def applyCaveBotSettings(caveBotSettings: CaveBotSettings): Unit = {
    caveBotCheckbox.selected = caveBotSettings.enabled
    setListModel(caveBotBot.waypointsList, caveBotSettings.waypointsList)
    setListModelFromGridInfos(caveBotBot.gridInfoList, caveBotSettings.gridInfoList)
  }


  def applyAutoTargetSettings(autoTargetSettings: AutoTargetSettings): Unit = {
    autoTargetCheckbox.selected = autoTargetSettings.enabled
    setListModel(autoTargetBot.creatureList, autoTargetSettings.creatureList)
    autoTargetBot.targetMonstersOnBattleCheckbox.selected = autoTargetSettings.targetMonstersOnBattle
  }

  def applyAutoLootSettings(autoLootSettings: AutoLootSettings): Unit = {
    autoLootCheckbox.selected = autoLootSettings.enabled
    setListModel(autoLootBot.lootList, autoLootSettings.lootList)
  }


  def applyRuneMakingSettings(runeMakingSettings: RuneMakingSettings): Unit = {
    runeMakerCheckbox.selected = runeMakingSettings.enabled
    // Similarly, apply rune making specific settings here
  }

  def applyProtectionZoneSettings(protectionZoneSettings: ProtectionZoneSettings): Unit = {
    protectionZoneCheckbox.selected = protectionZoneSettings.enabled
    protectionZoneBot.playerOnScreenAlertCheckbox.selected = protectionZoneSettings.playerOnScreenAlert
    protectionZoneBot.escapeToProtectionZoneCheckbox.selected = protectionZoneSettings.escapeToProtectionZone
    protectionZoneBot.setIgnoredCreatures(protectionZoneSettings.ignoredCreatures)
    // Apply any additional protection zone settings
  }

  def applyTrainingSettings(trainingSettings: TrainingSettings): Unit = {
    trainingCheckbox.selected = trainingSettings.enabled
    trainingBot.pickAmmunitionCheckbox.selected = trainingSettings.pickAmmunition
    trainingBot.refillAmmunitionCheckbox.selected = trainingSettings.refillAmmunition
    trainingBot.doNotKillTargetCheckbox.selected = trainingSettings.doNotKillTarget
    trainingBot.switchAttackModeToEnsureDamageCheckbox.selected = trainingSettings.switchAttackModeToEnsureDamage
    trainingBot.switchWeaponToEnsureDamageCheckbox.selected = trainingSettings.switchWeaponToEnsureDamage
  }

  def applyAutoResponderSettings(autoResponderSettings: AutoResponderSettings): Unit = {
    autoResponderCheckbox.selected = autoResponderSettings.enabled
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

        // Send the settings to the UIAppActor
        uiAppActor ! MainApp.StartActors(currentSettings)

        // Additional logic based on settings
        if (currentSettings.mouseMovements) {
          mainActorRef ! StartMouseMovementActor
        }


        // Sending the settings to the JsonProcessorActor and PeriodicFunctionActor
//        jsonProcessorActorRef ! MainApp.StartActors(currentSettings)
        periodicFunctionActorRef ! MainApp.StartActors(currentSettings)

        // Sending the initialization message to JsonProcessorActor
        jsonProcessorActorRef ! InitializeProcessor(currentPlayer, currentSettings)

    }
  }


  // Initialize components here similar to the second snippet...
  val autoHealCheckbox = new CheckBox("Auto Heal")
  val runeMakerCheckbox = new CheckBox("Rune Maker")
  val trainingCheckbox = new CheckBox("Training")
  val caveBotCheckbox = new CheckBox("Cave Bot")
  val teamHuntCheckbox = new CheckBox("Team Hunt")
  val fishingCheckbox = new CheckBox("Fishing")
  val autoResponderCheckbox = new CheckBox("Auto Responder")
  val mouseMovementsCheckbox = new CheckBox("Mouse Movements")
  val protectionZoneCheckbox = new CheckBox("Protection Zone")
  val autoTargetCheckbox = new CheckBox("Auto Target")
  val autoLootCheckbox = new CheckBox("Auto Loot")

  // ...and other fields and buttons as in the second snippet

  // Define UI behavior and event handling here, similar to the second snippet...
  listenTo(autoHealCheckbox, runeMakerCheckbox, trainingCheckbox, caveBotCheckbox,
    autoResponderCheckbox, protectionZoneCheckbox, fishingCheckbox,
    mouseMovementsCheckbox, protectionZoneCheckbox, autoTargetCheckbox,
    autoLootCheckbox,teamHuntCheckbox,
  )
  //  exampleDropdown.selection
  reactions += {
    case ButtonClicked(`autoHealCheckbox`) =>
      println("Auto Heal Checkbox clicked")

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

    case ButtonClicked(`protectionZoneCheckbox`) =>
      println("Protection Zone Checkbox clicked")
    // Add logic for when protectionZoneCheckbox is clicked

    case ButtonClicked(`autoResponderCheckbox`) =>
      println("Auto Responder Checkbox clicked")
    // Add logic for when caveBotCheckbox is clicked

    case ButtonClicked(`fishingCheckbox`) =>
      println("Fishing Checkbox clicked")
    // Add logic for when caveBotCheckbox is clicked

    case ButtonClicked(`mouseMovementsCheckbox`) =>
      println("Mouse Movements Checkbox clicked")
    // Add logic for when caveBotCheckbox is clicked

    case ButtonClicked(`autoLootCheckbox`) =>
      println("Auto Loot Checkbox clicked")
    // Add logic for when caveBotCheckbox is clicked

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
      val checkBoxComponents = Seq(autoHealCheckbox, runeMakerCheckbox, trainingCheckbox,
        caveBotCheckbox, teamHuntCheckbox, fishingCheckbox, mouseMovementsCheckbox,autoResponderCheckbox,
        protectionZoneCheckbox, autoTargetCheckbox, autoLootCheckbox)

      for ((checkbox, idx) <- checkBoxComponents.zipWithIndex) {
        c.gridx = 0
        c.gridy = idx
        c.gridwidth = 1
        peer.add(checkbox.peer, c)
      }

      // Adding Buttons in the second column
      val buttonComponents = Seq(runButton, saveButton, loadButton)

      for ((button, idx) <- buttonComponents.zipWithIndex) {
        c.gridx = 1
        c.gridy = idx
        c.gridwidth = 1
        peer.add(button.peer, c)
      }
    })

    pages += new TabbedPane.Page("Main Tab", mainBot.mainTab)

    pages += new TabbedPane.Page("Auto Heal", autoHealBot.autoHealTab)

    pages += new TabbedPane.Page("Cave Bot", caveBotBot.caveBotTab)

    pages += new TabbedPane.Page("Team Hunt", teamHuntBot.teamHuntTab)

    pages += new TabbedPane.Page("Auto Target", autoTargetBot.autoTargetTab)

    pages += new TabbedPane.Page("Auto Loot", autoLootBot.autoLootTab)

    pages += new TabbedPane.Page("Rune Maker", runeMakerBot.runeMakerTab)

    pages += new TabbedPane.Page("Trainer", trainingBot.trainerTab)

    pages += new TabbedPane.Page("Auto Responder", autoResponderBot.autoResponderTab)

    pages += new TabbedPane.Page("Fishing", fishingBot.fishingTab)

    pages += new TabbedPane.Page("Protection Zone", protectionZoneBot.protectionZoneTab)

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


  val updateButton = new Button("Update") {
    reactions += {
      case ButtonClicked(_) =>
        println("Update button clicked")
        saveExample()
        updateExample()
    }
  }

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

}

