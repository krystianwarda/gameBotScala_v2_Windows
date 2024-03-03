package userUI

import akka.actor.ActorRef
import player.Player

import java.awt.GridBagLayout
import javax.swing.JPanel
import scala.swing.Component

class AutoResponderBot(player: Player, uiAppActor: ActorRef, jsonProcessorActor: ActorRef) {

  val autoResponderTab: Component = Component.wrap(new JPanel(new GridBagLayout) {

  })
}
