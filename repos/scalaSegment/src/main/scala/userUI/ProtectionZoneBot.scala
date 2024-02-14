package userUI

import akka.actor.ActorRef
import player.Player

import scala.swing._
import java.awt.{Dimension, GridBagConstraints, GridBagLayout, Insets}
import javax.swing.{JLabel, JPanel}


class ProtectionZoneBot(player: Player, uiAppActor: ActorRef, jsonProcessorActor: ActorRef) {

  // Existing checkbox
  val playerOnScreenAlertCheckbox = new CheckBox("Player on the screen alert")

  // New checkbox for "Escape to protection zone"
  val escapeToProtectionZoneCheckbox = new CheckBox("Escape to protection zone")

  val protectionZoneTab: Component = Component.wrap(new JPanel(new GridBagLayout) {
    val c = new GridBagConstraints()
    c.insets = new Insets(5, 5, 5, 5)
    c.gridx = 0
    c.gridy = GridBagConstraints.RELATIVE // Use RELATIVE to automatically adjust the position
    c.anchor = GridBagConstraints.NORTHWEST

    // Adding existing checkbox
    add(playerOnScreenAlertCheckbox.peer, c)

    // Adding new checkbox
    add(escapeToProtectionZoneCheckbox.peer, c)
  })
}

