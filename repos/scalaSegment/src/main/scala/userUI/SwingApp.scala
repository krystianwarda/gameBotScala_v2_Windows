package userUI

import processing.{ConnectToServer, InitializeProcessor}
import main.scala.MainApp.{jsonProcessorActorRef, periodicFunctionActorRef}
import userUI.SettingsUtils.UISettings

import java.awt.{GridBagConstraints, GridBagLayout}
import scala.swing.{Component, Dialog, FileChooser, Insets}
import akka.actor.ActorRef
import main.scala.MainApp
import player.Player
import userUI.SettingsUtils.UISettings
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
  val autoHealBot = new AutoHealBot(currentPlayer, uiAppActor, jsonProcessorActor)
  val caveBot = new CaveBot(currentPlayer, uiAppActor, jsonProcessorActor)
  val runeMaker = new RuneMaker(currentPlayer, uiAppActor, jsonProcessorActor)
  val trainerBot = new TrainerBot(currentPlayer, uiAppActor, jsonProcessorActor)
  val protectionZoneBot = new ProtectionZoneBot(currentPlayer, uiAppActor, jsonProcessorActor)

  val exampleNames = playerClassList.map(_.characterName)
  val exampleMap = playerClassList.map(e => e.characterName -> e).toMap

  val exampleDropdown = new ComboBox(exampleNames)
  val exampleLabel = new Label()

  // Update the collectSettingsFromUI method to return UISettings
  def collectSettingsFromUI(): UISettings = {
    // Collect settings from various UI components and return them
    val selectedSpell = runeMaker.spellComboBox.selection.item
    // Attempt to parse manaTextField's text to an Int, default to 0 (or another sensible default) if parsing fails
    val requiredMana = try {
      runeMaker.manaTextField.text.toInt
    } catch {
      case _: NumberFormatException => 0
    }

    UISettings(
      autoHeal = autoHealCheckbox.selected,
      runeMaker = runeMakerCheckbox.selected,
      fishing = fishingCheckbox.selected,
      mouseMovements = mouseMovementsCheckbox.selected,
      caveBot = caveBotCheckbox.selected,
      protectionZone = protectionZoneCheckbox.selected,
      playerOnScreenAlert = protectionZoneBot.playerOnScreenAlertCheckbox.selected,
      selectedSpell = selectedSpell,
      requiredMana = requiredMana,
      // ...other settings
    )
  }

  val saveButton = new Button("Save Settings") {
    reactions += {
      case ButtonClicked(_) =>
        val settings = collectSettingsFromUI()
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
        SettingsUtils.loadSettingsFromFile("settings.json").foreach { settings =>
          applySettingsToUI(settings)
        }
    }
  }

  def applySettingsToUI(settings: UISettings): Unit = {
    autoHealCheckbox.selected = settings.autoHeal
    runeMakerCheckbox.selected = settings.runeMaker
    fishingCheckbox.selected = settings.fishing
    mouseMovementsCheckbox.selected = settings.mouseMovements
    caveBotCheckbox.selected = settings.caveBot
    protectionZoneCheckbox.selected = settings.protectionZone
    protectionZoneBot.playerOnScreenAlertCheckbox.selected = settings.playerOnScreenAlert
    // Update other UI components as needed
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
        jsonProcessorActorRef ! MainApp.StartActors(currentSettings)
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
  val fishingCheckbox = new CheckBox("Fishing")
  val mouseMovementsCheckbox = new CheckBox("Mouse Movements")
  val protectionZoneCheckbox = new CheckBox("Protection Zone")
  // ...and other fields and buttons as in the second snippet

  // Define UI behavior and event handling here, similar to the second snippet...
  listenTo(autoHealCheckbox, runeMakerCheckbox, trainingCheckbox, caveBotCheckbox,
    protectionZoneCheckbox, fishingCheckbox, mouseMovementsCheckbox, protectionZoneCheckbox
  )
  //  exampleDropdown.selection
  reactions += {
    case ButtonClicked(`autoHealCheckbox`) =>
      println("Auto Heal Checkbox clicked")
    // Add logic for when autoHealCheckbox is clicked

    case ButtonClicked(`runeMakerCheckbox`) =>
      println("Rune Maker Checkbox clicked")
    // Add logic for when runeMakerCheckbox is clicked

    case ButtonClicked(`trainingCheckbox`) =>
      println("Training Checkbox clicked")
    // Add logic for when trainingCheckbox is clicked


    case ButtonClicked(`caveBotCheckbox`) =>
      println("Cave Bot Checkbox clicked")
    // Add logic for when caveBotCheckbox is clicked

    case ButtonClicked(`protectionZoneCheckbox`) =>
      println("Protection Zone Checkbox clicked")
    // Add logic for when protectionZoneCheckbox is clicked

    case ButtonClicked(`protectionZoneCheckbox`) =>
      println("Protection Zone Checkbox clicked")
    // Add logic for when caveBotCheckbox is clicked

    case ButtonClicked(`fishingCheckbox`) =>
      println("Fishing Checkbox clicked")
    // Add logic for when caveBotCheckbox is clicked

    case ButtonClicked(`mouseMovementsCheckbox`) =>
      println("Mouse Movements Checkbox clicked")
    // Add logic for when caveBotCheckbox is clicked

    case ButtonClicked(`protectionZoneCheckbox`) =>
      println("Protection Zone Checkbox clicked")
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
        caveBotCheckbox, fishingCheckbox, mouseMovementsCheckbox,
        protectionZoneCheckbox)

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

    pages += new TabbedPane.Page("Auto Heal", autoHealBot.autoHealTab)

    pages += new TabbedPane.Page("Cave Bot", caveBot.caveBotTab)

    pages += new TabbedPane.Page("Rune Maker", runeMaker.runeMakerTab)

    pages += new TabbedPane.Page("Trainer", trainerBot.trainerTab)

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
  // Initialize UI state here if necessary
  // ...similar to the second snippet
}

