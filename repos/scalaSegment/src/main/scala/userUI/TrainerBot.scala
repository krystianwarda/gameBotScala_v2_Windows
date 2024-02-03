package userUI

import akka.actor.ActorRef
import player.Player

import java.awt.{GridBagConstraints, GridBagLayout}
import javax.swing.JPanel
import scala.swing.Component

class TrainerBot(player: Player, uiAppActor: ActorRef, jsonProcessorActor: ActorRef) {

  val trainerTab: Component = Component.wrap(new JPanel(new GridBagLayout) {
    val c = new GridBagConstraints()
  })
}
