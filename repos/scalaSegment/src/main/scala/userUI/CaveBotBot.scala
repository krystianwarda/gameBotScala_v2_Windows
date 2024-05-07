package userUI

import akka.actor.ActorRef
import akka.pattern.ask
import akka.util.Timeout
import main.scala.MainApp.{functionExecutorActorRef, periodicFunctionActorRef}
import play.api.libs.json.JsValue
import player.Player
import play.api.libs.json._

import scala.util.{Failure, Success}
import java.awt.{Color, Dimension, GridBagConstraints, GridBagLayout, GridLayout, Insets}
import javax.swing.{BorderFactory, DefaultComboBoxModel, DefaultListModel, JButton, JComboBox, JFileChooser, JLabel, JList, JOptionPane, JPanel, JScrollPane, ListSelectionModel, ScrollPaneConstants, SwingUtilities, filechooser}
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.swing.{CheckBox, Component, TextField}
import scala.util.parsing.json.JSON
import utils.ExecuteFunction

import scala.concurrent.ExecutionContext.Implicits.global
import java.io.{BufferedWriter, File, FileWriter}
import javax.swing.event.{ListSelectionEvent, ListSelectionListener}

class GridTile(val accessible: Boolean, val color: Color) {
  override def toString: String = s"Accessible: $accessible, Color: $color"
}

class GridInfo(val grid: Array[Array[GridTile]]) {
  def defaultGrid: Array[Array[GridTile]] = Array.fill(13, 17)(new GridTile(false, Color.WHITE))

  // Added a utility method to get a specific tile's accessibility
  def isTileAccessible(x: Int, y: Int): Boolean = grid(y)(x).accessible
  def fromTileData(indexStr: String, isWalkable: Boolean): GridInfo = {
    val grid = defaultGrid
    val coords = indexStr.split("x").map(_.toIntOption).flatten
    if (coords.length == 2) {
      val x = coords(0)
      val y = coords(1)
      if (x >= 0 && x < 17 && y >= 0 && y < 13) {
        grid(y)(x) = new GridTile(isWalkable, if (isWalkable) Color.GREEN else Color.RED)
        return new GridInfo(grid)
      }
    }
    println(s"Invalid coordinates provided: $indexStr")
    new GridInfo(grid) // Return a default grid in case of invalid coordinates
  }
  def updateTile(x: Int, y: Int, isWalkable: Boolean): Unit = {
    if (x >= 0 && x < grid(0).length && y >= 0 && y < grid.length) {
      grid(y)(x) = new GridTile(isWalkable, if (isWalkable) Color.GREEN else Color.RED)
    } else {
      println(s"Invalid tile coordinates: $x, $y")
    }
  }
}




class CaveBotBot(player: Player, uiAppActor: ActorRef, jsonProcessorActor: ActorRef) {
  // Debug flag
  val gridInfo = GridInfo.getSingletonGrid
  private val debugEnabled = true

  // Method to optionally print debug messages
  private def debug(msg: => String): Unit = {
    if (debugEnabled) println(msg)
  }

  // Define list model for waypoints
  val placementDropdown = new JComboBox[String]()
  val directions = Array("center", "top", "top right", "right", "down right", "down", "down left", "left", "top left")
  placementDropdown.setModel(new DefaultComboBoxModel(directions))

  // Simplified for clarity: Directly manage waypoints with a DefaultListModel
  val waypointListModel = new DefaultListModel[String]()
  // Now, you can use this model in a JList
  val waypointsList = new JList[String](waypointListModel)
  waypointsList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)

  val gridPlaceholder = createGridPlaceholder()
  val gridInfoListModel = new DefaultListModel[GridInfo]()

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
    implicit val timeout: Timeout = Timeout(5.seconds)

    val futureWaypoint: Future[JsValue] = (periodicFunctionActorRef ? "fetchLatestJson").mapTo[JsValue]

    futureWaypoint.onComplete {
      case Success(json) =>
        processJsonData(waypointType, json)
        SwingUtilities.invokeLater(() => {
          waypointsList.setSelectedIndex(waypointListModel.getSize() - 1)
//          updateGridPanelFromGridInfo()
        })
      case Failure(e) =>
        println(s"Failed to create waypoint due to: $e")
    }
  }

  def processJsonData(waypointType: String, json: JsValue): Unit = {
    println(s"Processing JSON data for $waypointType")
    val playerInfo = (json \ "characterInfo").asOpt[JsObject]
    playerInfo.foreach { infoJson =>
      val x = (infoJson \ "PositionX").as[Int]
      val y = (infoJson \ "PositionY").as[Int]
      val z = (infoJson \ "PositionZ").as[Int]
      val placement = placementDropdown.getSelectedItem.toString
      val displayString = s"$waypointType, $placement, $x, $y, $z"
      println(s"Player position: $displayString")
      waypointListModel.addElement(displayString)

      // Create a new GridInfo object for the current waypoint
      val newGridInfo = new GridInfo(GridInfo.defaultGrid)

      val tiles = (json \ "areaInfo" \ "tiles").as[JsObject]
      tiles.fields.foreach { case (key, value) =>
        val indexStr = (value \ "index").as[String]
        val isWalkableTile = (value \ "isWalkableTile").asOpt[Boolean].getOrElse(false)
        val isPathable = (value \ "isPathable").asOpt[Boolean].getOrElse(false)

        val isWalkable = isWalkableTile && isPathable

        val coords = indexStr.split("x").map(_.toInt)
        if (coords.length == 2) {
          val x = coords(0)
          val y = coords(1)
          if (x >= 0 && x < 17 && y >= 0 && y < 13) {
            println(s"Updating tile at X: $x, Y: $y to $isWalkable")
            newGridInfo.updateTile(x, y, isWalkable)
          } else {
            println(s"Skipping invalid tile index: $indexStr")
          }
        }
      }
      // Add the new GridInfo to the model
      gridInfoListModel.addElement(newGridInfo)
    }
    initialGridDisplay()  // Optionally update the display here or after a selection change
  }

  def initialGridDisplay(): Unit = {
    val selectedIndex = waypointsList.getSelectedIndex()
    if (selectedIndex >= 0 && selectedIndex < gridInfoListModel.getSize()) {
      val gridInfo = gridInfoListModel.get(selectedIndex)  // Fetch the grid info for the selected index
      val gridPanel = gridPlaceholder.getViewport.getView.asInstanceOf[JPanel]
      gridPanel.removeAll()
      gridPanel.setLayout(new GridLayout(13, 17))

      for (y <- 0 until 13) {
        for (x <- 0 until 17) {
          val cell = new JPanel()
          cell.setBorder(BorderFactory.createLineBorder(Color.black))
          // Check if it's the character's central position
          if (x == 8 && y == 6) {
            cell.setBackground(Color.YELLOW) // Set to yellow regardless of actual tile state
          } else {
            val tile = gridInfo.grid(y)(x)
            cell.setBackground(if (tile.accessible) Color.GREEN else Color.RED)
          }
          gridPanel.add(cell)
        }
      }
      gridPanel.revalidate()
      gridPanel.repaint()
      println("Grid updated for current selection.")
    } else {
      println("No grid data available to display.")
    }
  }

  def updateGridDisplayOld(): Unit = {
    val selectedIndex = waypointsList.getSelectedIndex()
    if (selectedIndex >= 0 && selectedIndex < gridInfoListModel.getSize()) {
      val gridInfo = gridInfoListModel.get(selectedIndex)
      val gridPanel = gridPlaceholder.getViewport.getView.asInstanceOf[JPanel]
      gridPanel.removeAll()
      gridPanel.setLayout(new GridLayout(13, 17))
      for (y <- 0 until 13) {
        for (x <- 0 until 17) {
          val cell = new JPanel()
          cell.setBorder(BorderFactory.createLineBorder(Color.black))
          val tile = gridInfo.grid(y)(x)
          cell.setBackground(if (tile.accessible) Color.GREEN else Color.RED)
          gridPanel.add(cell)
        }
      }
      gridPanel.revalidate()
      gridPanel.repaint()
    } else {
      println("No grid data available to display.")
    }
  }


  def createWaypointOld(waypointType: String): Unit = {
    println("Creating waypoint...")
    implicit val timeout: Timeout = Timeout(5.seconds)

    val futureWaypoint: Future[JsValue] = (periodicFunctionActorRef ? "fetchLatestJson").mapTo[JsValue]

    futureWaypoint.onComplete {
      case Success(json) =>
        processJsonData(waypointType, json)
      case Failure(e) =>
        println(s"Failed to create waypoint due to: $e")
    }
  }


  def convertListModelToList(model: DefaultListModel[GridInfo]): List[GridInfo] = {
    val list = List.newBuilder[GridInfo]
    for (i <- 0 until model.getSize) {
      list += model.get(i)
    }
    list.result()
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
    c.gridwidth = 2
    add(new JScrollPane(waypointsList), c)
    c.gridx = 2
    add(gridPlaceholder, c)


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


  waypointsList.addListSelectionListener(new ListSelectionListener {
    override def valueChanged(e: ListSelectionEvent): Unit = {
      if (!e.getValueIsAdjusting) {
        val selectedIndex = waypointsList.getSelectedIndex()
        println(s"Selection changed: updating grid for index $selectedIndex")
        if (selectedIndex >= 0 && selectedIndex < gridInfoListModel.getSize()) {
          val gridInfo = gridInfoListModel.get(selectedIndex)
          updateGridDisplay(gridInfo)  // Ensure this method uses the gridInfo to update the UI
        } else {
          println("No grid data available to display.")
        }
      }
    }
  })

  def updateGridDisplay(gridInfo: GridInfo): Unit = {
    val gridPanel = gridPlaceholder.getViewport.getView.asInstanceOf[JPanel]
    gridPanel.removeAll()
    gridPanel.setLayout(new GridLayout(13, 17))

    for (y <- 0 until 13) {
      for (x <- 0 until 17) {
        val cell = new JPanel()
        cell.setBorder(BorderFactory.createLineBorder(Color.black))
        // Check if it's the character's central position
        if (x == 8 && y == 6) {
          cell.setBackground(Color.YELLOW)  // Set to yellow regardless of actual tile state
        } else {
          val tile = gridInfo.grid(y)(x)
          cell.setBackground(if (tile.accessible) Color.GREEN else Color.RED)
        }
        gridPanel.add(cell)
      }
    }
    gridPanel.revalidate()
    gridPanel.repaint()
    println("Grid updated for current selection.")
  }


//  waypointsList.addListSelectionListener(new ListSelectionListener {
//    override def valueChanged(e: ListSelectionEvent): Unit = {
//      if (!e.getValueIsAdjusting) {
//        println(s"Selection changed: updating grid for index ${waypointsList.getSelectedIndex}")
//        SwingUtilities.invokeLater(() => {
//          updateGridPanelFromGridInfo()
//        })
//      }
//    }
//  })


  //  waypointsList.addListSelectionListener(new ListSelectionListener {
//    override def valueChanged(e: ListSelectionEvent): Unit = {
//      if (!e.getValueIsAdjusting) {
//        println(s"Selection changed: updating grid for index ${waypointsList.getSelectedIndex}")
//        updateGridPanelFromGridInfo() // Call to update the grid for the current selection
//      }
//    }
//  })


//  // Assuming each waypoint's associated grid JSON is stored or retrievable
//  waypointsList.addListSelectionListener(new ListSelectionListener {
//    override def valueChanged(e: ListSelectionEvent): Unit = {
//      if (!e.getValueIsAdjusting) {
//        println(s"Selection changed: updating grid for index ${waypointsList.getSelectedIndex}")
//        updateGridPanelFromGridInfo() // Update the grid to reflect the current selection
//      }
//    }
//  })


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


  // Ensure synchronized list operations
  def removeSelectedWaypoint(): Unit = {
    val selectedIndex = waypointsList.getSelectedIndex
    if (selectedIndex != -1) {
      waypointListModel.remove(selectedIndex)
      gridInfoListModel.remove(selectedIndex)
    }
  }

  def moveSelectedWaypointUp(): Unit = {
    val selectedIndex = waypointsList.getSelectedIndex
    if (selectedIndex > 0) {
      val waypoint = waypointListModel.get(selectedIndex)
      val gridInfo = gridInfoListModel.get(selectedIndex)
      waypointListModel.remove(selectedIndex)
      gridInfoListModel.remove(selectedIndex)
      waypointListModel.add(selectedIndex - 1, waypoint)
      gridInfoListModel.add(selectedIndex - 1, gridInfo)
      waypointsList.setSelectedIndex(selectedIndex - 1)
    }
  }


  def moveSelectedWaypointDown(): Unit = {
    val selectedIndex = waypointsList.getSelectedIndex
    if (selectedIndex < waypointListModel.getSize - 1 && selectedIndex >= 0) {
      val waypoint = waypointListModel.get(selectedIndex)
      val gridInfo = gridInfoListModel.get(selectedIndex)
      waypointListModel.remove(selectedIndex)
      gridInfoListModel.remove(selectedIndex)
      waypointListModel.add(selectedIndex + 1, waypoint)
      gridInfoListModel.add(selectedIndex + 1, gridInfo)
      waypointsList.setSelectedIndex(selectedIndex + 1)
    }
  }


  // Function to create a blank grid panel with a specified size
  def createGridPlaceholderOld(): JScrollPane = {
    val gridPanel = new JPanel(new GridLayout(13, 17)) // Updated to 13 rows and 17 columns
    gridPanel.setPreferredSize(new Dimension(510, 390)) // Updated dimensions to accommodate extra tiles
    for (_ <- 0 until 13 * 17) { // Updated count for the loop to fill the grid
      val cell = new JPanel()
      cell.setBorder(BorderFactory.createLineBorder(Color.lightGray))
      gridPanel.add(cell)
    }
    new JScrollPane(gridPanel, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED)
  }

  def createGridPlaceholder(): JScrollPane = {
    val gridPanel = new JPanel(new GridLayout(13, 17))  // Assuming 13x17 grid layout
    gridPanel.setPreferredSize(new Dimension(510, 390))
    for (_ <- 0 until 13 * 17) {
      val cell = new JPanel()
      cell.setBorder(BorderFactory.createLineBorder(Color.lightGray))
      cell.setBackground(Color.WHITE)  // Default color
      gridPanel.add(cell)
    }
    new JScrollPane(gridPanel, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED)
  }



  // Adjust your grid update logic:
  def updateGridPanelFromGridInfo(): Unit = {
    if (gridInfoListModel.getSize() == 0) {
      println("No grid data available to display.")
      return
    }

    val selectedIndex = waypointsList.getSelectedIndex()
    if (selectedIndex < 0 || selectedIndex >= gridInfoListModel.getSize()) {
      println("Invalid selection index.")
      return
    }

    val gridInfo = gridInfoListModel.get(selectedIndex)  // Ensure this index is valid
    val gridPanel = gridPlaceholder.getViewport.getView.asInstanceOf[JPanel]
    gridPanel.removeAll()
    gridPanel.setLayout(new GridLayout(13, 17))

    for (y <- 0 until 13) {
      for (x <- 0 until 17) {
        val cell = new JPanel()
        cell.setBorder(BorderFactory.createLineBorder(Color.black))
        val tile = gridInfo.grid(y)(x)
        cell.setBackground(if (tile.accessible) Color.GREEN else Color.RED)
        gridPanel.add(cell)
      }
    }

    gridPanel.revalidate()
    gridPanel.repaint()
  }

  def clearGrid(): Unit = {
    val gridPanel = gridPlaceholder.getViewport.getView.asInstanceOf[JPanel]
    gridPanel.removeAll()
    gridPanel.setLayout(new GridLayout(13, 17))
    debug("Clearing the grid...")

    for (_ <- 0 until 13 * 17) {
      val cell = new JPanel()
      cell.setBorder(BorderFactory.createLineBorder(Color.lightGray))
      cell.setBackground(Color.WHITE)
      gridPanel.add(cell)
    }
    gridPanel.revalidate()
    gridPanel.repaint()
    debug("Grid cleared.")
  }



  def updateGridPanelOld(gridPanel: JPanel, tiles: JsValue): Unit = {
    gridPanel.removeAll() // Clear previous cells

    // Parse tiles if it's a JsObject
    val tileObj = tiles.asOpt[JsObject].getOrElse {
      println("Unexpected JSON type.")
      return
    }

    // Prepare a map for indexed tiles, considering only valid coordinates
    val indexedTiles = tileObj.fields.flatMap { case (key, value) =>
      val indexStr = (value \ "index").asOpt[String].getOrElse("")
      val isWalkable = (value \ "isWalkableTile").asOpt[Boolean].getOrElse(false)
      val coords = indexStr.split("x").map(_.toInt)
      if (coords.length == 2 && coords(0) >= 0 && coords(0) <= 16 && coords(1) >= 0 && coords(1) <= 12)
        Some(((coords(0), coords(1)), isWalkable))
      else None
    }.toMap

    // Set the grid dimensions based on the new specified range
    val gridSizeX = 17 // updated to include two extra columns on the right
    val gridSizeY = 13 // updated to include two extra rows at the bottom

    // Fill the grid with tiles, creating panels as needed
    for (y <- 0 until gridSizeY) {
      for (x <- 0 until gridSizeX) {
        val key = (x, y)
        val isWalkable = indexedTiles.getOrElse(key, false)
        val panel = new JPanel()
        panel.setBorder(BorderFactory.createLineBorder(Color.black))

        // Check if the current tile is the player's location (8,6)
        val bgColor = if (x == 8 && y == 6) {
          Color.YELLOW // Set the color to yellow for the player's location
        } else if (!indexedTiles.contains(key)) {
          Color.WHITE // Default to white if no tile data
        } else if (isWalkable) {
          Color.GREEN
        } else {
          Color.BLUE
        }

        panel.setBackground(bgColor)
        gridPanel.add(panel)
      }
    }

    gridPanel.revalidate()
    gridPanel.repaint()
  }

  object GridInfo {
    def defaultGrid: Array[Array[GridTile]] = Array.fill(13, 17)(new GridTile(false, Color.WHITE))
    def getSingletonGrid: GridInfo = new GridInfo(defaultGrid)

    def fromTileData(indexStr: String, isWalkable: Boolean): GridInfo = {
      val grid = defaultGrid
      val coords = indexStr.split("x").map(_.toIntOption).flatten
      if (coords.length == 2) {
        val x = coords(0)
        val y = coords(1)
        if (x >= 0 && x < 17 && y >= 0 && y < 13) {
          grid(y)(x) = new GridTile(isWalkable, if (isWalkable) Color.GREEN else Color.RED)
          return new GridInfo(grid)
        }
      }
      println(s"Invalid coordinates provided: $indexStr")
      new GridInfo(grid) // Return a default grid in case of invalid coordinates
    }
  }



}
