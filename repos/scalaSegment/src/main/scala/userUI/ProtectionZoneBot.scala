package userUI

import akka.actor.ActorRef
import player.Player

import scala.swing._
import java.awt.{Dimension, GridBagConstraints, GridBagLayout, Insets}
import javax.swing.{JLabel, JPanel}


class ProtectionZoneBot(player: Player, uiAppActor: ActorRef, jsonProcessorActor: ActorRef) {

  val playerOnScreenAlertCheckbox = new CheckBox("Player on the screen alert")
  val protectionZoneTab: Component = Component.wrap(new JPanel(new GridBagLayout) {
    val c = new GridBagConstraints()
    c.insets = new Insets(5,5, 5, 5)
    c.gridx = 0
    c.gridy = 0
    c.anchor = GridBagConstraints.NORTHWEST
    add(playerOnScreenAlertCheckbox.peer, c)
  })
}
