package userUI

import akka.actor.ActorRef
import player.Player

import scala.swing._
import java.awt.{Dimension, GridBagConstraints, GridBagLayout, Insets}
import javax.swing.event.{ListSelectionEvent, ListSelectionListener}
import javax.swing.{DefaultListModel, JButton, JComboBox, JLabel, JList, JOptionPane, JPanel, JScrollPane, JTextField}
import scala.collection.mutable
import scala.collection.mutable.ListBuffer

class AutoTargetBot(player: Player, uiAppActor: ActorRef, jsonProcessorActor: ActorRef) {

  val targetMonstersOnBattleCheckbox = new CheckBox("Target monsters on battle list")

  val loadButton = new JButton("Load")
  val saveButton = new JButton("Save")
  val autoTargetSettingsName = new TextField("not saved", 30)

  // UI Components for creature priority


  val addCreatureButton = new Button("Add")
  val removeCreatureButton = new Button("Remove")
  val increasePriorityButton = new Button("Increase Priority")
  val decreasePriorityButton = new Button("Decrease Priority")
  val creatureListModel = new DefaultListModel[String]()
  val creatureList = new JList[String](creatureListModel)
  val creatureScrollPane = new JScrollPane(creatureList)
  val creaturePriorityList: ListBuffer[String] = ListBuffer.empty



  val addCreatureLabel = new Label("Creature name:")
  val creatureNameTextField = new TextField(20)

  val healthRange = new Label("HP% range:")
  val healthRangeFrom = new TextField("0", 12)
  val healthRangeTo = new TextField("100", 12)
  val healthRangetext = new Label(" to ")


  // monster count
  val creatureCountLabel = new Label("Count:")
  val creaturesCount = Array(0, 1, 2, 3, 4, 5, 6, 7, 8)
  val creaturesCountDropdown = new JComboBox(creaturesCount.map(_.asInstanceOf[Object]))

  // Danger level
  val creatureDangerLabel = new Label("Danger lvl:")
  val creatureDanger = Array(1, 2, 3, 4, 5, 6, 7, 8)
  val creatureDangerDropdown = new JComboBox(creatureDanger.map(_.asInstanceOf[Object]))




  val autoTargetTab: Component = Component.wrap(new JPanel(new GridBagLayout) {
    val c = new GridBagConstraints()
    c.insets = new Insets(5, 5, 5, 5)
    // Target monsters checkbox
    c.gridx = 0
    c.gridy = 0
    c.fill = GridBagConstraints.HORIZONTAL

    // Waypoint Label and List
    add(new JLabel("Save&Loading"), c)
    c.gridx +=1
    add(loadButton, c)
    c.gridx += 1
    add(saveButton, c)
    
    c.gridx = 0
    c.gridy += 1
    add(new JLabel("Settings name: "), c)
    c.gridx += 1
    c.gridwidth = 3
    add(autoTargetSettingsName.peer, c)
    c.gridwidth = 1

    c.gridy += 1
    c.gridx = 0
    add(addCreatureButton.peer, c)
    c.gridx += 1
    add(removeCreatureButton.peer, c)

    c.gridx += 1
    add(increasePriorityButton.peer, c)

    c.gridx += 1
    add(decreasePriorityButton.peer, c)


    c.gridwidth = 4
    c.gridx = 0
    c.gridy += 1
    c.fill = GridBagConstraints.HORIZONTAL
    c.gridheight = 4
    add(creatureScrollPane, c)
    c.gridheight = 1


    // Settings
    c.gridy = 0
    c.gridx = 4
    c.gridwidth = 1
    add(addCreatureLabel.peer, c)
    c.gridx += 1
    c.gridwidth = 3
    add(creatureNameTextField.peer, c)
    c.gridwidth = 1
    c.gridx = 4
    c.gridy += 1
    add(creatureCountLabel.peer, c)
    c.gridx += 1
    c.gridwidth = 3
    add(creaturesCountDropdown, c)
    c.gridy += 1
    c.gridx = 4
    c.gridwidth = 1
    add(healthRange.peer, c)
    c.gridx += 1
    add(healthRangeFrom.peer, c)
    c.gridx += 1
    add(healthRangetext.peer, c)
    c.gridx += 1
    add(healthRangeTo.peer, c)
    c.gridx = 4
    c.gridy += 1
    add(creatureDangerLabel.peer, c)
    c.gridx += 1
    c.gridwidth = 3
    add(creatureDangerDropdown, c)



    c.gridwidth = 1
    c.gridy += 1
    c.gridwidth = 4
    add(targetMonstersOnBattleCheckbox.peer, c)




    // Event listeners for the buttons
    addCreatureButton.peer.addActionListener(_ => {
      val creatureName = creatureNameTextField.text.trim
      val count = creaturesCountDropdown.getSelectedItem.toString
      val hpRangeFrom = healthRangeFrom.text.trim
      val hpRangeTo = healthRangeTo.text.trim
      val dangerLevel = creatureDangerDropdown.getSelectedItem.toString
      val targetOnBattle = if(targetMonstersOnBattleCheckbox.selected) "Yes" else "No"

      // Concatenate all information into a single string
      val creatureInfo = s"$creatureName, Count: $count, HP: $hpRangeFrom-$hpRangeTo, Danger: $dangerLevel, Target in Battle: $targetOnBattle"

      if (creatureName.nonEmpty && !creatureListModel.contains(creatureInfo)) {
        creatureListModel.addElement(creatureInfo)
        // Resetting the input fields after adding a creature might be a good UX practice
        creatureNameTextField.text = ""
        healthRangeFrom.text = "0"
        healthRangeTo.text = "100"
        creaturesCountDropdown.setSelectedIndex(0) // Reset to default value if needed
        creatureDangerDropdown.setSelectedIndex(0) // Reset to default value if needed
        targetMonstersOnBattleCheckbox.selected = false // Reset to default if needed
      }
    })


    def updateFieldsWithSelectedCreature(creatureInfo: String): Unit = {
      // Example parsing logic based on the provided file format
      val parts = creatureInfo.split(", ")
      if (parts.length >= 4) { // Quick validation
        val name = parts(0)
        val count = parts(1).substring("Count: ".length).toInt
        val hpParts = parts(2).substring("HP: ".length).split("-")
        val danger = parts(3).substring("Danger: ".length).toInt
        val targetInBattle = parts(4).substring("Target in Battle: ".length).equalsIgnoreCase("Yes")

        creatureNameTextField.text = name
        creaturesCountDropdown.setSelectedItem(count.asInstanceOf[Object]) // Ensure this matches the type in the dropdown
        healthRangeFrom.text = hpParts(0)
        healthRangeTo.text = hpParts(1)
        creatureDangerDropdown.setSelectedItem(danger.asInstanceOf[Object]) // Ensure this matches the type in the dropdown
        targetMonstersOnBattleCheckbox.selected = targetInBattle
      }
    }

    creatureList.addListSelectionListener(new ListSelectionListener() {
      override def valueChanged(e: ListSelectionEvent): Unit = {
        if (!e.getValueIsAdjusting && creatureList.getSelectedIndex != -1) {
          val selectedValue = creatureList.getSelectedValue.toString
          updateFieldsWithSelectedCreature(selectedValue)
        }
      }
    })



    removeCreatureButton.peer.addActionListener(_ => {
      val selectedIndices = creatureList.getSelectedIndices
      if (selectedIndices.nonEmpty) {
        selectedIndices.reverse.foreach { index =>
          creaturePriorityList.remove(index)
          creatureListModel.remove(index)
        }
      }
    })

    increasePriorityButton.peer.addActionListener(_ => {
      val selectedIndex = creatureList.getSelectedIndex
      if (selectedIndex > 0) {
        val item = creaturePriorityList.remove(selectedIndex)
        creaturePriorityList.insert(selectedIndex - 1, item)
        updateCreatureList()
        creatureList.setSelectedIndex(selectedIndex - 1)
      }
    })

    decreasePriorityButton.peer.addActionListener(_ => {
      val selectedIndex = creatureList.getSelectedIndex
      if (selectedIndex < creaturePriorityList.size - 1 && selectedIndex != -1) {
        val item = creaturePriorityList.remove(selectedIndex)
        creaturePriorityList.insert(selectedIndex + 1, item)
        updateCreatureList()
        creatureList.setSelectedIndex(selectedIndex + 1)
      }
    })
  })

  // Updates the JList based on the current state of creaturePriorityList
  private def updateCreatureList(): Unit = {
    creatureListModel.removeAllElements()
    creaturePriorityList.foreach(creatureListModel.addElement)
  }

  // Updates creaturePriorityList and refreshes the UI to reflect the new list
  def setTargetPriority(creatures: JList[String]): Unit = {
    creaturePriorityList.clear()

    // Convert JList to Scala Iterable and append all elements
    for (i <- 0 until creatures.getModel.getSize) {
      creaturePriorityList += creatures.getModel.getElementAt(i)
    }

    updateCreatureList()
  }


  def saveAutoTargetsToFile(filePath: String): Unit = {
    val writer = new java.io.PrintWriter(filePath)
    try {
      for (i <- 0 until creatureListModel.size()) { // Adjust if using a different model
        writer.println(creatureListModel.get(i))
      }
    } finally {
      writer.close()
    }
  }

  def loadAutoTargetsFromFile(filePath: String): Unit = {
    creatureListModel.clear() // Clear existing entries

    val creatureSettings = scala.io.Source.fromFile(filePath).getLines()
    creatureSettings.foreach(creatureListModel.addElement) // Add each line to the model
  }


  def resetAutoTargetsAndName(): Unit = {
    // Assuming creatureListModel is the model for waypoints; adjust if it's different
    creatureListModel.clear()
    autoTargetSettingsName.text = "not saved"
  }

  saveButton.addActionListener(_ => {
    val userAppDataPath = System.getenv("APPDATA")
    val initialSavePath = new java.io.File(userAppDataPath, "Realera\\autotargets")
    initialSavePath.mkdirs() // Ensure the directory exists

    val chooser = new javax.swing.JFileChooser(initialSavePath)
    chooser.setDialogTitle("Save Auto Targets")
    chooser.setDialogType(javax.swing.JFileChooser.SAVE_DIALOG)
//    chooser.setSelectedFile(new java.io.File("autotargets.txt")) // Suggest a default file name

    val result = chooser.showSaveDialog(null)

    if (result == javax.swing.JFileChooser.APPROVE_OPTION) {
      val selectedFile = chooser.getSelectedFile
      val filePath = selectedFile.getAbsolutePath

      // Ensure the file has the correct extension
      val filePathWithExtension = if (!filePath.toLowerCase.endsWith(".txt")) filePath + ".txt" else filePath

      try {
        saveAutoTargetsToFile(filePathWithExtension)
        // Optionally, display a success message
        // javax.swing.JOptionPane.showMessageDialog(null, "Auto targets saved successfully!", "Success", javax.swing.JOptionPane.INFORMATION_MESSAGE)
      } catch {
        case e: Exception =>
          // Optionally, handle and display error messages
          javax.swing.JOptionPane.showMessageDialog(null, "Failed to save auto targets: " + e.getMessage, "Error", javax.swing.JOptionPane.ERROR_MESSAGE)
      }
    }
  })


  loadButton.addActionListener(_ => {
    val userAppDataPath = System.getenv("APPDATA")
    val autoTargetsPath = new java.io.File(userAppDataPath, "Realera\\autotargets")
    val chooser = new javax.swing.JFileChooser(autoTargetsPath)
    chooser.setDialogTitle("Load Auto Targets")
    chooser.setFileSelectionMode(javax.swing.JFileChooser.FILES_ONLY)
    chooser.setAcceptAllFileFilterUsed(false)
    chooser.addChoosableFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Text files", "txt"))

    val result = chooser.showOpenDialog(null)
    if (result == javax.swing.JFileChooser.APPROVE_OPTION) {
      val selectedFile = chooser.getSelectedFile
      val filePath = selectedFile.getAbsolutePath

      try {
        loadAutoTargetsFromFile(filePath)

        // Update the autoTargetSettingsName field with the name of the loaded file, excluding the extension
        val fileNameWithoutExtension = selectedFile.getName.split("\\.").dropRight(1).mkString(".")
        autoTargetSettingsName.text = fileNameWithoutExtension
        // Optionally, display a success message
        // javax.swing.JOptionPane.showMessageDialog(null, "Auto targets loaded successfully!", "Success", javax.swing.JOptionPane.INFORMATION_MESSAGE)
      } catch {
        case e: Exception =>
          // Optionally, handle and display error messages
          javax.swing.JOptionPane.showMessageDialog(null, "Failed to load auto targets: " + e.getMessage, "Error", javax.swing.JOptionPane.ERROR_MESSAGE)
      }
    }
  })


}
