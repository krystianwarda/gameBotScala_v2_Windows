package userUI

import akka.actor.ActorRef
import player.Player

import java.awt.{GridBagConstraints, GridBagLayout}
import javax.swing.JPanel
import scala.swing.{CheckBox, Component}
import javax.swing._
import java.awt._


class TrainingBot(player: Player, uiAppActor: ActorRef, jsonProcessorActor: ActorRef) {
  // TrainerTab with Scala Swing CheckBoxes
  val pickAmmunitionCheckbox = new CheckBox("Pick ammunition")
  val refillAmmunitionCheckbox = new CheckBox("Refill ammunition")
  val doNotKillTargetCheckbox = new CheckBox("Do not kill target")
  val switchWeaponToEnsureDamageCheckbox = new CheckBox("Switch weapon to ensure damage")

  val trainerTab: Component = Component.wrap(new JPanel(new GridBagLayout) {
    val c = new GridBagConstraints()

    c.insets = new Insets(5, 5, 5, 5) // Margin around components
    c.gridx = 0 // X position
    c.gridy = GridBagConstraints.RELATIVE // Y position automatically adjusted
    c.anchor = GridBagConstraints.NORTHWEST // Anchor northwest to align components

    // Adding checkboxes to the panel
    add(pickAmmunitionCheckbox.peer, c)
    add(refillAmmunitionCheckbox.peer, c)
    add(doNotKillTargetCheckbox.peer, c)
    add(switchWeaponToEnsureDamageCheckbox.peer, c)
  })
}


// 2889 // krew pelna
// 2890 // krew po czasie
// 45 sekund