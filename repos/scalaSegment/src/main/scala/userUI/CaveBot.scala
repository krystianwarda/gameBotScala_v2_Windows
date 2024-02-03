package userUI

import akka.actor.ActorRef
import player.Player

import scala.swing._
import java.awt.{Dimension, GridBagConstraints, GridBagLayout, Insets}
import javax.swing.{JLabel, JPanel}

class CaveBot(player: Player, uiAppActor: ActorRef, jsonProcessorActor: ActorRef) {
  // The AutoHeal tab component
  val caveBotTab: Component = Component.wrap(new JPanel(new GridBagLayout) {
    val c = new GridBagConstraints()
  })
}
