package userUI

import akka.actor.ActorRef
import player.Player

import java.awt.{GridBagConstraints, GridBagLayout, Insets}
import javax.swing.{JButton, JLabel, JPanel, JPasswordField}
import scala.swing.{Component, TextField}
//import scala.swing._
import javax.swing._
//import scala.swing.{CheckBox, Component, TextField}

class EmailAlertsBot(player: Player, uiAppActor: ActorRef, jsonProcessorActor: ActorRef) {

  val emailAlertsTab: Component = Component.wrap(new JPanel(new GridBagLayout) {
    val c = new GridBagConstraints()
    c.insets = new Insets(5, 5, 5, 5)
    c.gridx = 0
    c.gridy = 0
    c.fill = GridBagConstraints.HORIZONTAL

    // Email field
    add(new JLabel("Email address:"), c)
    val emailField = new TextField(20)
    c.gridx += 1
    add(emailField.peer, c)

    // Reset grid for the next row
    c.gridx = 0
    c.gridy += 1

    // Password field
    add(new JLabel("Password:"), c)
    val passwordField = new JPasswordField(20)
    c.gridx += 1
    add(passwordField, c)

    // Reset grid for the next row
    c.gridx = 0
    c.gridy += 1

    // Recipient email field
    add(new JLabel("Recipient Email:"), c)
    val recipientField = new TextField(20)
    c.gridx += 1
    add(recipientField.peer, c)

    // Reset grid for the next row
    c.gridx = 0
    c.gridy += 1

    // Save button
    val saveButton = new JButton("Save Settings")
    c.gridwidth = 2
    add(saveButton, c)

    saveButton.addActionListener(_ => {
      // Here, implement the action to save these settings, possibly sending them to a settings manager or actor
      // Example:
      // uiAppActor ! UpdateEmailSettings(emailField.text, new String(passwordField.password), recipientField.text)
    })
  })
}
