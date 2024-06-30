package userUI

import akka.actor.ActorRef
import player.Player

import java.awt.{Color, Dimension, GridBagConstraints, GridBagLayout, Insets}
import javax.swing._
import scala.swing.MenuBar.NoMenuBar.{listenTo, reactions}
import scala.swing._
import scala.swing.event._

class TeamHuntBot(player: Player, uiAppActor: ActorRef, jsonProcessorActor: ActorRef) {

  val followBlockerCheckbox = new CheckBox("Follow blocker")
  val youAreBlocker = new CheckBox("You are a blocker")
  val blockerNameLabel = new Label("Blocker name:")
  val blockerName = new TextField(32)

  val chaseMonsterCheckbox = new CheckBox("Chase monster and use exeta res")
  val waitCheckbox = new CheckBox("Wait for the team")
  val teamMemberLabel = new Label("Team member name:")
  val nameTextField = new TextField(32)
  val addButton = new Button("Add")
  val removeButton = new Button("Remove")
  val teamMembersModel = new DefaultComboBoxModel[String]()
  val teamMembersDropdown = new ComboBox[String](Array[String]()) // Initially empty
  teamMembersDropdown.peer.setModel(teamMembersModel) // Set the custom model

  val teamHuntTab: Component = Component.wrap(new JPanel(new GridBagLayout) {
    val c = new GridBagConstraints()
    c.insets = new Insets(2, 2, 2, 2)
    c.fill = GridBagConstraints.HORIZONTAL

    // Setup the grid and add components
    c.gridy = 0
    c.gridx = 0
    c.gridwidth = 2
    add(followBlockerCheckbox.peer, c)

    c.gridy = 1
    c.gridx = 0
    c.gridwidth = 1
    add(blockerNameLabel.peer, c)
    c.gridx = 1
    add(blockerName.peer, c)

    c.gridy = 2
    c.gridx = 0
    c.gridwidth = 2
    add(youAreBlocker.peer, c)

    c.gridy = 3
    c.gridx = 0
    add(chaseMonsterCheckbox.peer, c)
    c.gridy = 4
    add(waitCheckbox.peer, c)

    c.gridy = 5
    c.gridx = 0
    c.gridwidth = 1
    add(teamMemberLabel.peer, c)
    c.gridx = 1
    add(nameTextField.peer, c)

    c.gridy = 6
    c.gridx = 0
    add(addButton.peer, c)
    c.gridx = 1
    add(removeButton.peer, c)

    c.gridy = 7
    c.gridx = 0
    c.gridwidth = 2
    add(teamMembersDropdown.peer, c)
  })

  listenTo(youAreBlocker, addButton, removeButton)
  reactions += {
    case ButtonClicked(`youAreBlocker`) =>
      setComponentsEnabled(youAreBlocker.selected)

    case ButtonClicked(`addButton`) if nameTextField.text.nonEmpty =>
      teamMembersModel.addElement(nameTextField.text)
      nameTextField.text = ""

    case ButtonClicked(`removeButton`) =>
      val selectedIndex = teamMembersDropdown.peer.getSelectedIndex
      if (selectedIndex != -1) {
        teamMembersModel.removeElementAt(selectedIndex)
      }
  }

  private def setComponentsEnabled(enabled: Boolean): Unit = {
    chaseMonsterCheckbox.enabled = enabled
    waitCheckbox.enabled = enabled
    nameTextField.enabled = enabled
    addButton.enabled = enabled
    removeButton.enabled = enabled
    teamMembersDropdown.enabled = enabled
  }
}
