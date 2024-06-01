package userUI

import akka.actor.ActorRef
import player.Player

import java.awt.{Color, Dimension, GridBagConstraints, GridBagLayout, GridLayout, Insets}
import javax.swing.{BorderFactory, DefaultComboBoxModel, DefaultListModel, JButton, JCheckBox, JComboBox, JFileChooser, JLabel, JList, JOptionPane, JPanel, JScrollPane, JTextField, ListSelectionModel, ScrollPaneConstants, SwingUtilities, filechooser}
import scala.swing.{CheckBox, Component, Label, TextField}

class TeamHuntBot (player: Player, uiAppActor: ActorRef, jsonProcessorActor: ActorRef) {

  val followBlockerCheckbox = new CheckBox("Follow blocker")
  val blockerNameLabel = new Label("Blocker name:")
  val blockerName = new TextField(32)

  val teamHuntTab: Component = Component.wrap(new javax.swing.JPanel(new GridBagLayout) {
    val c = new GridBagConstraints()
    c.insets = new Insets(2, 2, 2, 2)
    c.fill = GridBagConstraints.HORIZONTAL // This will make components take up full width
    c.gridwidth = 1

    // Checkbox for Follow blocker
    c.gridy = 0 // Adjust gridy for the checkbox
    c.gridx = 0
    add(followBlockerCheckbox.peer, c)

    // Label for Blocker name
    c.gridy = 1 // Adjust gridy for the label
    c.gridx = 0
    add(blockerNameLabel.peer, c)

    // Text field for Blocker name input, limited to 32 characters
    c.gridy = 1 // Same row as the label
    c.gridx = 1
    add(blockerName.peer, c)
  })

}
