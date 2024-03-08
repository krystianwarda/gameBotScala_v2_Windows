package userUI

import akka.actor.ActorRef
import player.Player

import java.awt.{Dimension, GridBagConstraints, GridBagLayout, Insets}
import javax.swing.{DefaultListModel, JButton, JLabel, JList, JPanel, JScrollPane, ListSelectionModel}
import scala.swing.Component
import scala.util.parsing.json.JSON // Import JSON parsing library

class CaveBotBot(player: Player, uiAppActor: ActorRef, jsonProcessorActor: ActorRef) {

  // Define list model for waypoints
  val waypointListModel = new DefaultListModel[String]

  // Define scrollable list with the waypointListModel
  val waypointList = new JList[String](waypointListModel)
  waypointList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION) // Allow only single selection for removal

  // Add button to add waypoints
  val addButton = new JButton("Add")

  // Button to remove selected waypoint
  val removeButton = new JButton("Remove")

  // Define action to add waypoints
  def createWaypoint(): Unit = {
    // Example waypoint structure
    val waypoint = """{"waypointType":"walk", "PositionX":32681, "PositionY":31685, "PositionZ":3}"""

    // Parse the JSON and create a nicer display string including Z value
    JSON.parseFull(waypoint).map {
      case m: Map[String, Any] =>
        // Ensure values are formatted as integers and include Z value
        val displayString = f"${m("waypointType")}, ${m("PositionX").asInstanceOf[Double].toInt}, ${m("PositionY").asInstanceOf[Double].toInt}, ${m("PositionZ").asInstanceOf[Double].toInt}"
        waypointListModel.addElement(displayString)
      case _ => // Handle error or unexpected format
    }
  }

  // Add action listener to the add button
  addButton.addActionListener((_) => createWaypoint())

  // Define action to remove selected waypoint
  def removeSelectedWaypoint(): Unit = {
    val selectedIndex = waypointList.getSelectedIndex
    if (selectedIndex != -1) { // Check if an item is selected
      waypointListModel.remove(selectedIndex)
    }
  }

  // Add action listener to the remove button
  removeButton.addActionListener((_) => removeSelectedWaypoint())

  // Define layout for CaveBot tab using javax.swing.JPanel
  val caveBotTab: Component = Component.wrap(new javax.swing.JPanel(new GridBagLayout) {
    val c = new GridBagConstraints()
    c.insets = new Insets(5, 5, 5, 5)

    // Waypoint Label and List
    c.gridx = 0
    c.gridy = 0
    add(new JLabel("Waypoints"), c)
    c.gridx = 1
    add(new JScrollPane(waypointList), c)

    // Add Button
    c.gridx = 0
    c.gridy = 1
    add(addButton, c)

    // Remove Button
    c.gridx = 1
    c.gridy = 1
    add(removeButton, c)
  })
}
