package userUI

import akka.actor.ActorRef
import player.Player
import scala.swing._
import java.awt.{Dimension, GridBagConstraints, GridBagLayout, Insets}
import javax.swing.{JLabel, JPanel}

class RuneMaker(player: Player, uiAppActor: ActorRef, jsonProcessorActor: ActorRef) {

  val runeMakerTab: Component = Component.wrap(new JPanel(new GridBagLayout) {
    val c = new GridBagConstraints()
  })
}
