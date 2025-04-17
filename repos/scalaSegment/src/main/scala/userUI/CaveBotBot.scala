package userUI

import akka.actor.ActorRef
import akka.pattern.ask
import akka.util.Timeout
import main.scala.MainApp.{functionExecutorActorRef, periodicFunctionActorRef}
import play.api.libs.json.JsValue
import player.Player
import play.api.libs.json._
import utils.SettingsUtils.CaveBotSettings

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

  def serialize: String = {
    grid.map(row => row.map(tile => s"${tile.accessible}:${tile.color.getRGB}").mkString(",")).mkString(";")
  }

  def this(serialized: String) = {
    this(serialized.split(";").map(row => row.split(",").map { cell =>
      val parts = cell.split(":")
      new GridTile(parts(0).toBoolean, new Color(parts(1).toInt))
    }))
  }


}


object CaveBotSettings {
  implicit val gridInfoWrites: Writes[GridInfo] = new Writes[GridInfo] {
    def writes(gridInfo: GridInfo): JsValue = JsString(gridInfo.serialize)
  }

  implicit val gridInfoReads: Reads[GridInfo] = JsPath.read[String].map(new GridInfo(_))

  implicit val caveBotSettingsFormat: Format[CaveBotSettings] = Json.format[CaveBotSettings]
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
  val waypointsList = new JList[String](waypointListModel)
  waypointsList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)


  val gridPlaceholder = createGridPlaceholder()

  val gridInfoListModel = new DefaultListModel[GridInfo]()
  val gridInfoList = new JList[GridInfo](gridInfoListModel)
  gridInfoList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)

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


  // Define fixed dimensions
  val fixedHeight = 200
  val waypointsListWidth = 200
  val gridPlaceholderWidth = 300

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

//    c.gridy = 3
//    c.gridx = 0
//    c.gridwidth = 2
//
//    // Set preferred size for waypointsList
//    val waypointsScrollPane = new JScrollPane(waypointsList)
//    waypointsScrollPane.setPreferredSize(new Dimension(waypointsListWidth, fixedHeight))
//    add(waypointsScrollPane, c)
//
//    c.gridx = 2
//
//    // Set preferred size for gridPlaceholder
//    val smallerGridPlaceholder = createGridPlaceholder()
//    smallerGridPlaceholder.setPreferredSize(new Dimension(gridPlaceholderWidth, fixedHeight))
//    add(smallerGridPlaceholder, c)


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

  // Method to convert gridInfoListModel to gridInfoList
  def getGridInfoList: Seq[String] = {
    (0 until gridInfoListModel.size()).map(i => gridInfoListModel.get(i).serialize)
  }

  // Method to load gridInfoList into gridInfoListModel
  def setGridInfoList(grids: Seq[String]): Unit = {
    gridInfoListModel.clear()
    grids.map(new GridInfo(_)).foreach(gridInfoListModel.addElement)
  }

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



  def resetWaypointsAndName(): Unit = {
    // Clear the waypoint list model
    waypointListModel.clear()
    // Clear the grid info list model
    gridInfoListModel.clear()
    // Reset the waypoint name to default
    waypointName.text = "not saved"
    // Optionally, reset the display of the grid to default state
    resetGridDisplay()
  }


  def resetGridDisplay(): Unit = {
    val gridPanel = gridPlaceholder.getViewport.getView.asInstanceOf[JPanel]
    gridPanel.removeAll()
    for (_ <- 0 until 13 * 17) {
      val cell = new JPanel()
      cell.setBorder(BorderFactory.createLineBorder(Color.lightGray))
      cell.setBackground(Color.WHITE) // Reset to default color
      gridPanel.add(cell)
    }
    gridPanel.revalidate()
    gridPanel.repaint()
  }

  def loadWaypointsFromFile(filePath: String): Unit = {
    val source = scala.io.Source.fromFile(filePath)
    val jsonString = try source.mkString finally source.close()
    val json = Json.parse(jsonString)

    val waypoints = (json \ "waypoints").as[Seq[String]]
    val grids = (json \ "grids").as[Seq[JsValue]].map(deserializeGridInfo)

    SwingUtilities.invokeLater(() => {
      waypointListModel.clear()
      gridInfoListModel.clear()
      waypoints.foreach(waypointListModel.addElement)
      grids.foreach(gridInfoListModel.addElement)
    })
  }

  def deserializeGridInfo(json: JsValue): GridInfo = {
    val gridArray = (json \ "grid").as[Array[Array[JsValue]]].map(row =>
      row.map(tile =>
        new GridTile(
          (tile \ "accessible").as[Boolean],
          new Color((tile \ "color").as[Int])
        )
      )
    )
    new GridInfo(gridArray)
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
    val waypoints = (0 until waypointListModel.size()).map(i => waypointListModel.get(i).toString)
    val grids = (0 until gridInfoListModel.size()).map(i => serializeGridInfo(gridInfoListModel.get(i)))

    val json = Json.obj(
      "waypoints" -> waypoints,
      "grids" -> grids
    )

    val writer = new BufferedWriter(new FileWriter(new File(filePath)))
    try {
      writer.write(Json.prettyPrint(json))
    } finally {
      writer.close()
    }
  }

  def serializeGridInfo(gridInfo: GridInfo): JsValue = {
    Json.obj(
      "grid" -> gridInfo.grid.map(row => row.map(tile => Json.obj(
        "accessible" -> tile.accessible,
        "color" -> tile.color.getRGB  // Storing the RGB value of the color
      )))
    )
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

  def createGridPlaceholder(): JScrollPane = {
    val gridPanel = new JPanel(new GridLayout(13, 17)) // Assuming 13x17 grid layout
    gridPanel.setPreferredSize(new Dimension(200, 140)) // Adjusted size for smaller cells
    for (_ <- 0 until 13 * 17) {
      val cell = new JPanel()
      cell.setPreferredSize(new Dimension(5, 5)) // Adjust cell size
      cell.setBorder(BorderFactory.createLineBorder(Color.lightGray))
      cell.setBackground(Color.WHITE) // Default color
      gridPanel.add(cell)
    }
    new JScrollPane(gridPanel, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED)
  }

  def createGridPlaceholderOld(): JScrollPane = {
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
