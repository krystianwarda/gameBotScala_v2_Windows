// SwingActor.scala
//package src.main.scala.userUI/*/

import MainApp.jsonProcessorActorRef
import akka.actor.{Actor, ActorRef, ActorSystem, Props}

import scala.swing._
import scala.swing.event._
import java.awt.event._
import player.Player

import java.awt.Dimension
import scala.concurrent.ExecutionContext.Implicits.global
import javax.swing.{ImageIcon, JLabel, JPanel}
import java.awt.{GridBagConstraints, GridBagLayout, Insets}
import scala.runtime.BoxesRunTime.add
import scala.swing.GridBagPanel.Fill
import scala.swing.ListView.Renderer

class UIAppActor(playerClassList: List[Player],
                 jsonProcessorActorRef: ActorRef,
                 periodicFunctionActorRef: ActorRef,
                 thirdProcessActorRef: ActorRef) extends Actor {

  // Store the current player. For simplicity, let's use the first player from the list.
  // You may want to update this based on your application's logic.
  private val currentPlayer: Option[Player] = playerClassList.headOption

  Swing.onEDT {
    new SwingApp(playerClassList, self, jsonProcessorActorRef, periodicFunctionActorRef, thirdProcessActorRef).visible = true
  }

  def receive: Receive = {
    case StartActors(settings) =>
      // Forward settings to JsonProcessorActor
      // Ensure currentPlayer is not None before sending the message
      currentPlayer.foreach { player =>
        jsonProcessorActorRef ! InitializeProcessor(player, settings)
      }
      // Use the settings to start or configure other actors
      println(s"Received settings: $settings")
    // Additional logic for handling StartActors message
  }

  // ... other methods ...
}

// Define the StartActors message
case class StartActors(settings: UISettings)

// Define the UISettings case class
case class UISettings(autoHeal: Boolean, runeMaker: Boolean, fishing: Boolean, mouseMovements: Boolean, caveBot: Boolean /*, other settings */)


class SwingApp(playerClassList: List[Player],
               uiAppActor: ActorRef,
               jsonProcessorActor: ActorRef,
               periodicFunctionActor: ActorRef,
               thirdProcessActor: ActorRef) extends MainFrame {

  title = "TibiaYBB - Younger Brother Bot"
  preferredSize = new Dimension(600, 300)
  var runningBot = false


  // Update the collectSettingsFromUI method to return UISettings
  def collectSettingsFromUI(): UISettings = {
    // Collect settings from various UI components and return them
    UISettings(
      autoHeal = autoHealCheckbox.selected,
      runeMaker = runeMakerCheckbox.selected,
      fishing = fishingCheckbox.selected,
      mouseMovements = mouseMovementsCheckbox.selected,
      caveBot = caveBotCheckbox.selected,
      // ...other settings
    )
  }

  val currentPlayer: Player = playerClassList.head
  val runButton = new Button("RUN") {
    reactions += {
      case ButtonClicked(_) =>
        val currentSettings = collectSettingsFromUI()

        // Sending the settings to the UIAppActor
        uiAppActor ! StartActors(currentSettings)

        // Additionally, sending the settings to other relevant actors
        jsonProcessorActor ! StartActors(currentSettings)
        periodicFunctionActor ! StartActors(currentSettings)

        // Initializing the processor with the player and settings
        jsonProcessorActorRef ! InitializeProcessor(currentPlayer, currentSettings)

      // Additional UI logic can be added here if needed
    }
  }

//  val runButton = new Button("RUN") {
//    reactions += {
//      case ButtonClicked(_) =>
//        val currentSettings = collectSettingsFromUI()
//        jsonProcessorActor ! StartActors(currentSettings)
//        periodicFunctionActor ! StartActors(currentSettings)
//        jsonProcessorActorRef ! InitializeProcessor(currentPlayer, currentSettings)
//      // Additional UI logic
//    }
//  }

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

  val exampleNames = playerClassList.map(_.characterName)
  val exampleMap = playerClassList.map(e => e.characterName -> e).toMap

  val exampleDropdown = new ComboBox(exampleNames)

  val exampleLabel = new Label()
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


  // Initialize components here similar to the second snippet...
  val autoHealCheckbox = new CheckBox("Auto Heal")
  val runeMakerCheckbox = new CheckBox("Rune Maker")
  val trainingCheckbox = new CheckBox("Training")
  val caveBotCheckbox = new CheckBox("Cave Bot")
  val protectionZoneCheckbox = new CheckBox("Protection Zone")
  val fishingCheckbox = new CheckBox("Fishing")
  val mouseMovementsCheckbox = new CheckBox("Mouse Movements")
  // ...and other fields and buttons as in the second snippet

  // Define UI behavior and event handling here, similar to the second snippet...
  listenTo(autoHealCheckbox, runeMakerCheckbox, trainingCheckbox, caveBotCheckbox,
    protectionZoneCheckbox, fishingCheckbox, mouseMovementsCheckbox,
    exampleDropdown.selection)
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

    case SelectionChanged(`exampleDropdown`) =>
      println("Dropdown selection changed")
    // Add logic for when the selection in exampleDropdown changes
  }

  // Layout your UI components here
  contents = new TabbedPane {
    pages += new TabbedPane.Page("Main", new BoxPanel(Orientation.Vertical) {
      // Add components to the "Main" tab
      contents += exampleDropdown
      contents += autoHealCheckbox
      contents += runeMakerCheckbox
      contents += trainingCheckbox
      contents += caveBotCheckbox
      contents += fishingCheckbox
      contents += runButton

      // ...and other components as in the second snippet
    })

    pages += new TabbedPane.Page("Auto Heal", Component.wrap(new JPanel(new GridBagLayout) {
      val lightHealLabel = new JLabel("LoSpell")
      val lightHealHealthLabel = new JLabel("Health")
      val lightHealManaLabel = new JLabel("Mana")
      val strongHealLabel = new JLabel("HiSpell")
      val strongHealHealthLabel = new JLabel("Health")
      val strongHealManaLabel = new JLabel("Mana")
      val ihHealLabel = new JLabel("IH rune")
      val ihHealHealthLabel = new JLabel("Health")
      val ihHealManaLabel = new JLabel("Mana")
      val uhHealLabel = new JLabel("UH rune")
      val uhHealHealthLabel = new JLabel("Health")
      val uhHealManaLabel = new JLabel("Mana")
      val hPotionHealLabel = new JLabel("H potion")
      val hPotionHealHealthLabel = new JLabel("Health")
      val hPotionHealManaLabel = new JLabel("Mana")
      val mPotionHealLabel = new JLabel("M potion")
      val mPotionHealManaMinLabel = new JLabel("Mana")


      val c = new GridBagConstraints()
      c.insets = new Insets(5, 5, 5, 5)

      // Define the preferred width for each text field
      val spellFieldWidth = 150
      val healthManaFieldWidth = 70

      // First row
      c.gridx = 0
      c.gridy = 0
      add(lightHealLabel, c)

      c.gridx = 1
      c.gridwidth = 2
      c.fill = GridBagConstraints.HORIZONTAL
      lightHealSpellField.peer.setPreferredSize(new Dimension(spellFieldWidth, lightHealSpellField.peer.getPreferredSize.height))
      add(lightHealSpellField.peer, c)

      c.gridx = 3
      c.gridwidth = 1
      c.fill = GridBagConstraints.NONE
      add(lightHealHealthLabel, c)

      c.gridx = 4
      c.gridwidth = 1
      c.fill = GridBagConstraints.HORIZONTAL
      lightHealHealthField.peer.setPreferredSize(new Dimension(healthManaFieldWidth, lightHealHealthField.peer.getPreferredSize.height))
      add(lightHealHealthField.peer, c)

      c.gridx = 5
      c.gridwidth = 1
      c.fill = GridBagConstraints.HORIZONTAL
      lightHealManaLabel.setPreferredSize(new Dimension(healthManaFieldWidth, lightHealManaLabel.getPreferredSize.height))
      add(lightHealManaLabel, c)

      c.gridx = 6
      c.gridwidth = 1
      c.fill = GridBagConstraints.HORIZONTAL
      lightHealManaField.peer.setPreferredSize(new Dimension(healthManaFieldWidth, lightHealManaField.peer.getPreferredSize.height))
      add(lightHealManaField.peer, c)

      // Second row
      c.gridx = 0
      c.gridy = 1
      add(strongHealLabel, c)

      c.gridx = 1
      c.gridwidth = 2
      c.fill = GridBagConstraints.HORIZONTAL
      strongHealSpellField.peer.setPreferredSize(new Dimension(spellFieldWidth, strongHealSpellField.peer.getPreferredSize.height))
      add(strongHealSpellField.peer, c)

      c.gridx = 3
      c.gridwidth = 1
      c.fill = GridBagConstraints.NONE
      add(strongHealHealthLabel, c)

      c.gridx = 4
      c.gridwidth = 1
      c.fill = GridBagConstraints.HORIZONTAL
      strongHealHealthField.peer.setPreferredSize(new Dimension(healthManaFieldWidth, strongHealHealthField.peer.getPreferredSize.height))
      add(strongHealHealthField.peer, c)

      c.gridx = 5
      c.gridwidth = 1
      c.fill = GridBagConstraints.HORIZONTAL
      strongHealManaLabel.setPreferredSize(new Dimension(healthManaFieldWidth, strongHealManaLabel.getPreferredSize.height))
      add(strongHealManaLabel, c)

      c.gridx = 6
      c.gridwidth = 1
      c.fill = GridBagConstraints.HORIZONTAL
      strongHealManaField.peer.setPreferredSize(new Dimension(healthManaFieldWidth, strongHealManaField.peer.getPreferredSize.height))
      add(strongHealManaField.peer, c)

      c.gridx = 0
      c.gridy = 2
      add(ihHealLabel, c)

      c.gridx = 3
      c.gridwidth = 1
      c.fill = GridBagConstraints.NONE
      add(ihHealHealthLabel, c)

      c.gridx = 4
      c.gridwidth = 1
      c.fill = GridBagConstraints.HORIZONTAL
      ihHealHealthField.peer.setPreferredSize(new Dimension(healthManaFieldWidth, ihHealHealthField.peer.getPreferredSize.height))
      add(ihHealHealthField.peer, c)

      c.gridx = 5
      c.gridwidth = 1
      c.fill = GridBagConstraints.HORIZONTAL
      ihHealManaLabel.setPreferredSize(new Dimension(healthManaFieldWidth, ihHealManaLabel.getPreferredSize.height))
      add(ihHealManaLabel, c)

      c.gridx = 6
      c.gridwidth = 1
      c.fill = GridBagConstraints.HORIZONTAL
      ihHealManaField.peer.setPreferredSize(new Dimension(healthManaFieldWidth, ihHealManaField.peer.getPreferredSize.height))
      add(ihHealManaField.peer, c)

      c.gridx = 0
      c.gridy = 3
      add(uhHealLabel, c)

      c.gridx = 3
      c.gridwidth = 1
      c.fill = GridBagConstraints.NONE
      add(uhHealHealthLabel, c)

      c.gridx = 4
      c.gridwidth = 1
      c.fill = GridBagConstraints.HORIZONTAL
      uhHealHealthField.peer.setPreferredSize(new Dimension(healthManaFieldWidth, uhHealHealthField.peer.getPreferredSize.height))
      add(uhHealHealthField.peer, c)

      c.gridx = 5
      c.gridwidth = 1
      c.fill = GridBagConstraints.HORIZONTAL
      uhHealManaLabel.setPreferredSize(new Dimension(healthManaFieldWidth, uhHealManaLabel.getPreferredSize.height))
      add(uhHealManaLabel, c)

      c.gridx = 6
      c.gridwidth = 1
      c.fill = GridBagConstraints.HORIZONTAL
      uhHealManaField.peer.setPreferredSize(new Dimension(healthManaFieldWidth, uhHealManaField.peer.getPreferredSize.height))
      add(uhHealManaField.peer, c)

      c.gridx = 0
      c.gridy = 4
      add(hPotionHealLabel, c)

      c.gridx = 3
      c.gridwidth = 1
      c.fill = GridBagConstraints.NONE
      add(hPotionHealHealthLabel, c)

      c.gridx = 4
      c.gridwidth = 1
      c.fill = GridBagConstraints.HORIZONTAL
      hPotionHealHealthField.peer.setPreferredSize(new Dimension(healthManaFieldWidth, hPotionHealHealthField.peer.getPreferredSize.height))
      add(hPotionHealHealthField.peer, c)

      c.gridx = 5
      c.gridwidth = 1
      c.fill = GridBagConstraints.HORIZONTAL
      hPotionHealManaLabel.setPreferredSize(new Dimension(healthManaFieldWidth, hPotionHealManaLabel.getPreferredSize.height))
      add(hPotionHealManaLabel, c)

      c.gridx = 6
      c.gridwidth = 1
      c.fill = GridBagConstraints.HORIZONTAL
      hPotionHealManaField.peer.setPreferredSize(new Dimension(healthManaFieldWidth, hPotionHealManaField.peer.getPreferredSize.height))
      add(hPotionHealManaField.peer, c)


      c.gridx = 0
      c.gridy = 5
      add(mPotionHealLabel, c)

      c.gridx = 5
      c.gridwidth = 1
      c.fill = GridBagConstraints.NONE
      add(mPotionHealManaMinLabel, c)

      c.gridx = 6
      c.gridwidth = 1
      c.fill = GridBagConstraints.HORIZONTAL
      mPotionHealManaMinField.peer.setPreferredSize(new Dimension(healthManaFieldWidth, mPotionHealManaMinField.peer.getPreferredSize.height))
      add(mPotionHealManaMinField.peer, c)


      // Button row
      c.gridy = 6
      c.gridx = 3
      c.gridwidth = 2
      add(updateButton.peer, c)

    }))

    pages += new TabbedPane.Page("Cave Bot", new GridBagPanel {
      // Add components to the "Cave Bot" tab
      // ...similar layout as in the second snippet
    })

    pages += new TabbedPane.Page("Rune Maker", new GridBagPanel {
      // Add components to the "Cave Bot" tab
      // ...similar layout as in the second snippet
    })

    pages += new TabbedPane.Page("Training", new GridBagPanel {
      // Add components to the "Cave Bot" tab
      // ...similar layout as in the second snippet
    })

    pages += new TabbedPane.Page("Protection Zone", new GridBagPanel {
      // Add components to the "Cave Bot" tab
      // ...similar layout as in the second snippet
    })




  }

  // Initialize UI state here if necessary
  // ...similar to the second snippet
}
