package userUI

import akka.actor.ActorRef
import player.Player
import scala.swing._
import java.awt.{Dimension, GridBagConstraints, GridBagLayout, Insets}
import javax.swing.{JLabel, JPanel}


class RuneMakerBot(player: Player, uiAppActor: ActorRef, jsonProcessorActor: ActorRef) {

  val spellComboBox = new ComboBox(List("adori gran flam"))
  val manaTextField = new TextField(10) // Width set to 10

  val runeMakerTab: Component = Component.wrap(new JPanel(new GridBagLayout) {
    val c = new GridBagConstraints()
    c.insets = new Insets(5, 5, 5, 5)

    // Spell Label and ComboBox
    c.gridx = 0
    c.gridy = 0
    add(new Label("Spell").peer, c)
    c.gridx = 1
    add(spellComboBox.peer, c)

    // Mana Label and TextField
    c.gridx = 0
    c.gridy = 1
    add(new Label("Mana").peer, c)
    c.gridx = 1
    add(manaTextField.peer, c)
  })
}
