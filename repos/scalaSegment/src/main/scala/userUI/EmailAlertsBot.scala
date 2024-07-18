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

  val emailAlert = new TextField(20)
  val passwordAlert = new TextField(20)
  val recipientAlert = new TextField(20)


  val emailAlertsTab: Component = Component.wrap(new JPanel(new GridBagLayout) {
    val c = new GridBagConstraints()
    c.insets = new Insets(5, 5, 5, 5)
    c.gridx = 0
    c.gridy = 0
    c.fill = GridBagConstraints.HORIZONTAL

    // Email field
    add(new JLabel("Email address:"), c)
    c.gridx += 1
    add(emailAlert.peer, c)

    // Reset grid for the next row
    c.gridx = 0
    c.gridy += 1

    // Password field
    add(new JLabel("Password:"), c)
    c.gridx += 1
    add(passwordAlert.peer, c)

    // Reset grid for the next row
    c.gridx = 0
    c.gridy += 1

    // Recipient email field
    add(new JLabel("Recipient Email:"), c)

    c.gridx += 1
    add(recipientAlert.peer, c)

    // Reset grid for the next row
    c.gridx = 0
    c.gridy += 1

  })
}
