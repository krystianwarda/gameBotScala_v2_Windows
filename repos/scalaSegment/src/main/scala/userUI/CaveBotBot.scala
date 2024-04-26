package userUI

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

class CaveBotBot(player: Player, uiAppActor: ActorRef, jsonProcessorActor: ActorRef) {

  // Define list model for waypoints
  val placementDropdown = new JComboBox[String]()
  val directions = Array("center", "top", "top right", "right", "down right", "down", "down left", "left", "top left")
  placementDropdown.setModel(new DefaultComboBoxModel(directions))

  // Simplified for clarity: Directly manage waypoints with a DefaultListModel
  val waypointListModel = new DefaultListModel[String]()
  // Now, you can use this model in a JList
  val waypointsList = new JList[String](waypointListModel)



  val waypointName = new TextField("not saved", 30)
  // Add button to add waypoints
  val addWalkButton = new JButton("Walk")
  val addStairsButton = new JButton("Stairs")
  val addRopeButton = new JButton("Rope")
  val addLadderButton = new JButton("Ladder")
  val addShovelButton = new JButton("Shovel")
  val addLureButton = new JButton("Lure")
  val addStandButton = new JButton("Stand")
  val addRunButton = new JButton("Run")

  // Button to remove selected waypoint
  val removeButton = new JButton("Remove")
  val resetButton = new JButton("Reset")
  val loadButton = new JButton("Load")
  val saveButton = new JButton("Save")
  val moveUpButton = new JButton("Move Up")
  val moveDownButton = new JButton("Move Down")



  def createWaypoint(waypointType: String): Unit = {
    println("Creating waypoint...")
    implicit val timeout: Timeout = Timeout(5.seconds) // Define the timeout for the `ask` pattern

    // Correctly send the message and handle the response as a future
    val futureWaypoint: Future[JsValue] = (functionExecutorActorRef ? ExecuteFunction("getCharPos")).mapTo[JsValue]

    futureWaypoint.onComplete {
      case Success(json) =>
        // Check if the JSON response includes the keys 'x' and 'y'
        (json \ "x").asOpt[Int] match {
          case Some(positionX) =>
            val positionY = (json \ "y").asOpt[Int].getOrElse(0)
            val positionZ = (json \ "z").asOpt[Int].getOrElse(0)
            val placement = placementDropdown.getSelectedItem.toString

            val displayString = s"$waypointType, $placement, $positionX, $positionY, $positionZ"
            println(s"Waypoint created successfully: $displayString")
            // Assuming waypointListModel is a Swing component model or similar
            waypointListModel.addElement(displayString)

          case None =>
            println("Received JSON response without 'x' and 'y' coordinates. Processing differently...")
        }

      case Failure(e) =>
        // Handle any errors that occurred during the future operation
        println(s"Failed to create waypoint due to: $e")
    }
  }


  // Add action listener to the add button
  addWalkButton.addActionListener(_ => createWaypoint("walk"))
  removeButton.addActionListener(_ => removeSelectedWaypoint())
  addStairsButton.addActionListener(_ => createWaypoint("stairs"))
  addRopeButton.addActionListener(_ => createWaypoint("rope"))
  addLadderButton.addActionListener(_ => createWaypoint("lader"))
  addShovelButton.addActionListener(_ => createWaypoint("shovel"))
  addLureButton.addActionListener(_ => createWaypoint("lure"))
  addStandButton.addActionListener(_ => createWaypoint("stand"))
  addRunButton.addActionListener(_ => createWaypoint("run"))
  resetButton.addActionListener(_ => resetWaypointsAndName())
  moveUpButton.addActionListener(_ => moveSelectedWaypointUp())
  moveDownButton.addActionListener(_ => moveSelectedWaypointDown())


  // Add action listener to the remove button
  removeButton.addActionListener((_) => removeSelectedWaypoint())

  val caveBotTab: Component = Component.wrap(new javax.swing.JPanel(new GridBagLayout) {
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
    add(waypointName.peer, c)

    c.gridwidth = 1
    c.gridy = 2
    c.gridx = 0
    add(moveUpButton, c)
    c.gridx = 1
    add(moveDownButton, c)
    c.gridx = 2
    add(removeButton, c)
    c.gridx = 3
    add(resetButton, c)


    c.gridy = 3
    c.gridx = 0
    c.gridwidth = 4
    add(new JScrollPane(waypointsList), c)



    // Placement of point
    c.gridy = 4
    c.gridx = 0
    c.gridwidth = 2
    add(new JLabel("Placement"), c)
    c.gridx = 1
    add(new JScrollPane(placementDropdown), c)
    c.gridwidth = 1

    // Add and Remove Buttons
    c.gridy = 5
    c.gridx = 0
    add(addWalkButton, c)
    c.gridx = 1
    add(addStairsButton, c)
    c.gridx = 2
    add(addRopeButton, c)
    c.gridx = 3
    add(addLadderButton, c)
    c.gridy = 6
    c.gridx = 0
    add(addShovelButton, c)
    c.gridx = 1
    add(addLureButton, c)
    c.gridx = 2
    add(addStandButton, c)
    c.gridx = 3
    add(addRunButton, c)

  })


  loadButton.addActionListener(_ => {
    val userAppDataPath = System.getenv("APPDATA")
    val realeraWaypointsPath = new java.io.File(userAppDataPath, "Realera\\waypoints")
    val chooser = new JFileChooser(realeraWaypointsPath)
    chooser.setDialogTitle("Load")

    val result = chooser.showOpenDialog(null)
    if (result == JFileChooser.APPROVE_OPTION) {
      val selectedFile = chooser.getSelectedFile
      loadWaypointsFromFile(selectedFile.getAbsolutePath)

      // Update the waypointName field with the name of the loaded file, excluding the extension
      val fileNameWithoutExtension = selectedFile.getName.split("\\.").dropRight(1).mkString(".")
      waypointName.text = fileNameWithoutExtension
    }
  })


  def resetWaypointsAndName(): Unit = {
    // Clear the list model to remove all waypoints from the UI
    waypointListModel.clear()
    // Reset the waypoint name to its default value
    waypointName.text = "not saved"
  }

  def loadWaypointsFromFile(filePath: String): Unit = {
    // Clear existing model
    waypointListModel.clear()

    // Assuming you have a method to read waypoints from file
    val waypoints = scala.io.Source.fromFile(filePath).getLines()
    waypoints.foreach(waypointListModel.addElement)
  }


  saveButton.addActionListener(_ => {
    val userAppDataPath = System.getenv("APPDATA")
    val realeraWaypointsPath = new java.io.File(userAppDataPath, "Realera\\waypoints")
    realeraWaypointsPath.mkdirs()

    val fileName = waypointName.text.trim
    val safeFileName = if (fileName.isEmpty) "waypoints" else fileName
    val file = new File(realeraWaypointsPath, safeFileName + ".txt")

    saveWaypointsToFile(file.getAbsolutePath)
  })


  def saveWaypointsToFile(filePath: String): Unit = {
    val writer = new java.io.PrintWriter(filePath)
    try {
      for (i <- 0 until waypointListModel.size()) {
        writer.println(waypointListModel.get(i))
      }
    } finally {
      writer.close()
    }
  }


  def removeSelectedWaypoint(): Unit = {
    val selectedIndex = waypointsList.getSelectedIndex
    if (selectedIndex != -1) {
      waypointListModel.remove(selectedIndex)
    }
  }

  def moveSelectedWaypointUp(): Unit = {
    val selectedIndex = waypointsList.getSelectedIndex
    if (selectedIndex > 0) {
      val waypoint = waypointListModel.get(selectedIndex)
      waypointListModel.remove(selectedIndex)
      waypointListModel.add(selectedIndex - 1, waypoint)
      waypointsList.setSelectedIndex(selectedIndex - 1)
    }
  }

  def moveSelectedWaypointDown(): Unit = {
    val selectedIndex = waypointsList.getSelectedIndex
    if (selectedIndex < waypointListModel.getSize - 1 && selectedIndex >= 0) {
      val waypoint = waypointListModel.get(selectedIndex)
      waypointListModel.remove(selectedIndex)
      waypointListModel.add(selectedIndex + 1, waypoint)
      waypointsList.setSelectedIndex(selectedIndex + 1)
    }
  }

  def jListToScalaList(waypointsList: JList[String]): List[String] = {
    val model = waypointsList.getModel
    (for (i <- 0 until model.getSize) yield model.getElementAt(i)).toList
  }


}
