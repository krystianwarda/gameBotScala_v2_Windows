package userUI

import akka.actor.ActorRef
import player.Player

import java.awt.GridBagLayout
import scala.swing.Component
import akka.actor.ActorRef
import akka.pattern.ask
import akka.util.Timeout
import main.scala.MainApp.functionExecutorActorRef
import play.api.libs.json.JsValue
import player.Player

import scala.util.{Failure, Success}
import java.awt.{Dimension, GridBagConstraints, GridBagLayout, Insets}
import javax.swing.{DefaultComboBoxModel, DefaultListModel, JButton, JComboBox, JFileChooser, JLabel, JList, JOptionPane, JPanel, JScrollPane, ListSelectionModel, SwingUtilities, filechooser}
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.swing.{CheckBox, Component, TextField}
import scala.util.parsing.json.JSON
import utils.ExecuteFunction

import scala.concurrent.ExecutionContext.Implicits.global
import java.io.{BufferedWriter, File, FileWriter}


class AutoLootBot(player: Player, uiAppActor: ActorRef, jsonProcessorActor: ActorRef) {

  // save & load buttons
  val loadButton = new JButton("Load")
  val saveButton = new JButton("Save")
  val lootSettingsName = new TextField("not saved", 30)

  // loot settings
  val lootListModel = new DefaultListModel[String]()
  val lootList = new JList[String](lootListModel)
  val lootMonsterAfterKillCheckbox = new CheckBox("Loot monster immediately after kill.")
  val killMonstersFirstThanLootCheckbox = new CheckBox("Kill monsters first than loot.")
  val lootId = new TextField("Loot ID", 4)
  val lootbagId = new TextField("Lootbag ID", 2)
  val lootName = new TextField("Loot Name", 15)
  val addLootButton = new JButton("Add")
  val deleteLootButton = new JButton("Delete")
  val convertGoldToPlatinumCheckbox = new CheckBox("Convert gold coins to platinum.")

  // loot
  addLootButton.addActionListener(_ => createLoot())
  deleteLootButton.addActionListener(_ => deleteLoot())


  val autoLootTab: Component = Component.wrap(new javax.swing.JPanel(new GridBagLayout) {


    val c = new GridBagConstraints()
    c.insets = new Insets(2, 2, 2, 2)
    c.fill = GridBagConstraints.HORIZONTAL // This will make components take up full width
    c.gridwidth = 1

    // Waypoint Label and List
    c.gridy = 0 // Adjust gridy for subsequent components
    c.gridx = 0
    add(new JLabel("Save&Load"), c)
    c.gridx = 1
    add(loadButton, c)
    c.gridx = 2
    add(saveButton, c)


    c.gridy = 1 // Adjust gridy for subsequent components
    c.gridx = 0
    add(new JLabel("Settings name"), c)
    c.gridx = 1
    c.gridwidth = 3
    add(lootSettingsName.peer, c)

    c.gridwidth = 1
    c.gridy = 2
    c.gridx = 0


    // Loot
    c.fill = GridBagConstraints.HORIZONTAL
    c.gridwidth = 4
    c.gridx = 0
    add(convertGoldToPlatinumCheckbox.peer, c)
    c.gridy += 1
    add(killMonstersFirstThanLootCheckbox.peer, c)
    c.gridy += 1
    add(lootMonsterAfterKillCheckbox.peer, c)

    c.gridwidth = 4
    c.gridx = 0
    c.gridy += 1
    add(new JScrollPane(lootList), c)

    c.gridwidth = 1
    c.gridx = 0
    c.gridy += 1
    add(lootId.peer, c)
    c.gridx += 1
    add(lootbagId.peer, c)
    c.gridx += 1
    c.gridwidth = 2
    add(lootName.peer, c)

    c.gridy += 1
    c.gridx = 0
    c.gridwidth = 1
    add(addLootButton, c)
    c.gridx += 1
    add(deleteLootButton, c)
    c.gridwidth = 1
  })


  def createLoot(): Unit = {
    val lootIdText = lootId.text
    val lootbagIdText = lootbagId.text
    val lootNameText = lootName.text

    // Validate input, perform necessary actions
    // For example, add the loot to the loot list
    val lootDisplayString = s"$lootIdText, $lootbagIdText, $lootNameText"
    lootListModel.addElement(lootDisplayString)
  }

  def deleteLoot(): Unit = {
    // Get the index of the selected item in the loot list
    val selectedIndex = lootList.getSelectedIndex
    if (selectedIndex != -1) { // Check if an item is selected
      // Remove the selected item from the loot list model
      lootListModel.remove(selectedIndex)
    }
  }

  loadButton.addActionListener(_ => {
    val userAppDataPath = System.getenv("APPDATA")
    val realeraWaypointsPath = new java.io.File(userAppDataPath, "Realera\\autoloots")
    val chooser = new JFileChooser(realeraWaypointsPath)
    chooser.setDialogTitle("Load")

    val result = chooser.showOpenDialog(null)
    if (result == JFileChooser.APPROVE_OPTION) {
      val selectedFile = chooser.getSelectedFile
      loadLootSettingsFromFile(selectedFile.getAbsolutePath)

      // Update the waypointName field with the name of the loaded file, excluding the extension
      val fileNameWithoutExtension = selectedFile.getName.split("\\.").dropRight(1).mkString(".")
      lootSettingsName.text = fileNameWithoutExtension
    }
  })

  def loadLootSettingsFromFile(filePath: String): Unit = {
    // Clear existing model
    lootListModel.clear()
    // Assuming you have a method to read loot settings from file
    val lootSettings = scala.io.Source.fromFile(filePath).getLines()
    lootSettings.foreach(lootListModel.addElement)
  }


  saveButton.addActionListener(_ => {
    val userAppDataPath = System.getenv("APPDATA")
    val realeraWaypointsPath = new java.io.File(userAppDataPath, "Realera\\autoloots")
    realeraWaypointsPath.mkdirs()

    val fileName = lootSettingsName.text.trim
    val safeFileName = if (fileName.isEmpty) "autoloots" else fileName
    val file = new File(realeraWaypointsPath, safeFileName + ".txt")

    saveAutoLootsToFile(file.getAbsolutePath)
  })


  def saveAutoLootsToFile(filePath: String): Unit = {
    val writer = new java.io.PrintWriter(filePath)
    try {
      for (i <- 0 until lootListModel.size()) {
        writer.println(lootListModel.getElementAt(i))
      }
    } finally {
      writer.close()
    }
  }

}
