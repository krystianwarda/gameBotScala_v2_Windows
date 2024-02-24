package userUI

import akka.actor.ActorRef
import player.Player

import scala.swing._
import java.awt.{Dimension, GridBagConstraints, GridBagLayout, Insets}
import javax.swing.{JLabel, JPanel}
import scala.swing._
import scala.swing.event._
import java.awt.{GridBagConstraints, GridBagLayout, Insets}
import scala.swing.MenuBar.NoMenuBar.{listenTo, reactions}
import scala.swing._
import scala.swing.event._

class ProtectionZoneBot(player: Player, uiAppActor: ActorRef, jsonProcessorActor: ActorRef) {

  // Existing checkbox
  val playerOnScreenAlertCheckbox = new CheckBox("Player on the screen alert")

  // New checkbox for "Escape to protection zone"
  val escapeToProtectionZoneCheckbox = new CheckBox("Escape to protection zone")

  // List to hold strings
  var stringsList: List[String] = List()

  // Label for input field
  val inputFieldLabel = new Label("Add creature to ignore:")

  // Text field for input
  val inputTextField = new TextField(20)

  // Button to add string
  val addButton = new Button("Add")

  // Button to remove string
  val removeButton = new Button("Remove")

  // ListView to display and select strings
  val listView = new ListView(stringsList) {
    selection.intervalMode = ListView.IntervalMode.Single
  }

  val protectionZoneTab: Component = Component.wrap(new JPanel(new GridBagLayout) {
    val c = new GridBagConstraints()
    c.insets = new Insets(5, 5, 5, 5)
    c.gridx = 0
    c.gridy = GridBagConstraints.RELATIVE
    c.anchor = GridBagConstraints.NORTHWEST

    // Adding existing checkboxes
    add(playerOnScreenAlertCheckbox.peer, c)
    add(escapeToProtectionZoneCheckbox.peer, c)

    // Adding label for input field
    add(inputFieldLabel.peer, c)

    // Adjust grid for input field
    c.fill = GridBagConstraints.HORIZONTAL
    add(inputTextField.peer, c)

    // Reset fill for buttons
    c.fill = GridBagConstraints.NONE
    add(addButton.peer, c)
    add(removeButton.peer, c)

    // Adding ListView inside a ScrollPane for display
    c.fill = GridBagConstraints.BOTH
    c.weightx = 1.0
    c.weighty = 1.0
    add(new ScrollPane(listView).peer, c)
  })

  listenTo(addButton, removeButton)
  reactions += {
    case ButtonClicked(`addButton`) =>
      if (inputTextField.text.nonEmpty && !stringsList.contains(inputTextField.text)) {
        stringsList ::= inputTextField.text // Prepend to list
        updateListView()
        inputTextField.text = "" // Clear input field
      }

    case ButtonClicked(`removeButton`) =>
      listView.selection.items.foreach { item =>
        stringsList = stringsList.filterNot(_ == item) // Remove selected item
        updateListView()
      }
  }

  def setIgnoredCreatures(creatures: List[String]): Unit = {
    stringsList = creatures
    updateListView()
  }
  def getIgnoredCreatures: List[String] = stringsList
  private def updateListView(): Unit = {
    listView.listData = stringsList
  }
}
