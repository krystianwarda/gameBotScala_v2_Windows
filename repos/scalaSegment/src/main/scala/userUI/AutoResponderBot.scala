package userUI

import akka.actor.ActorRef
import player.Player

import java.awt.GridBagLayout
import javax.swing.JPanel
import scala.swing.{CheckBox, Component}
import akka.actor.ActorRef
import akka.pattern.ask
import akka.util.Timeout
import main.scala.MainApp.{functionExecutorActorRef, periodicFunctionActorRef}
import play.api.libs.json.JsValue
import player.Player
import play.api.libs.json._
import userUI.SettingsUtils.CaveBotSettings

import scala.util.{Failure, Success}
import java.awt.{Color, Dimension, GridBagConstraints, GridBagLayout, GridLayout, Insets}
import javax.swing.{BorderFactory, DefaultComboBoxModel, DefaultListModel, JButton, JComboBox, JFileChooser, JLabel, JList, JOptionPane, JPanel, JScrollPane, JTextField, ListSelectionModel, ScrollPaneConstants, SwingUtilities, filechooser}
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.swing.{CheckBox, Component, TextField}
import scala.util.parsing.json.JSON
import utils.ExecuteFunction

import java.awt.event.{ActionEvent, ActionListener}
import scala.concurrent.ExecutionContext.Implicits.global
import java.io.{BufferedWriter, File, FileWriter}
import javax.swing.event.{ListSelectionEvent, ListSelectionListener}

class AutoResponderBot(player: Player, uiAppActor: ActorRef, jsonProcessorActor: ActorRef) {




  val respondStoryDropdown = new JComboBox[String]()
  val storiesList = Array("training", "solo hunt", "team hunt", "fishing", "rune making")
  respondStoryDropdown.setModel(new DefaultComboBoxModel(storiesList))
  var selectedStory: String = storiesList(0)


  val additionalStory = new TextField(500)

  val autoResponderTab: Component = Component.wrap(new JPanel(new GridBagLayout) {
    val c = new GridBagConstraints()
    c.insets = new Insets(2, 2, 2, 2)
    c.fill = GridBagConstraints.HORIZONTAL // This ensures components take up full width

    // Add the autoResponderCheckbox
    c.gridx = 0
    c.gridy = 0
    c.gridwidth = 1 // Span across all columns
    add(new JLabel("Main story"), c)

    c.gridy = c.gridy + 1
    c.gridwidth = 1 // Increase to 3 columns
    add(respondStoryDropdown, c)

    // Add label for additional settings
    c.gridx = 0
    c.gridy = c.gridy + 1
    c.gridwidth = 1 // Span across all columns
    add(new JLabel("Additional user story"), c)

    // Add additionalRespondSettings text field
    c.gridx = 0
    c.gridy = c.gridy + 1
    c.gridwidth = 1 // Span across all columns
    c.gridheight = 5
    add(additionalStory.peer, c)
  })

  // Set up the ActionListener for the JComboBox
  respondStoryDropdown.addActionListener(new ActionListener {
    def actionPerformed(e: ActionEvent): Unit = {
      selectedStory = respondStoryDropdown.getSelectedItem.asInstanceOf[String]
    }
  })

}
