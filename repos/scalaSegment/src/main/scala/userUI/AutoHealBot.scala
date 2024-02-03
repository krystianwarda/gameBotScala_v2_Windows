package userUI


import akka.actor.ActorRef
import player.Player

import scala.swing._
import java.awt.{Dimension, GridBagConstraints, GridBagLayout, Insets}
import javax.swing.{JLabel, JPanel}


class AutoHealBot(player: Player, uiAppActor: ActorRef, jsonProcessorActor: ActorRef) {
  // UI components specific to AutoHeal
  val lightHealSpellField = new TextField()
  val lightHealHealthField = new TextField()
  val lightHealManaField = new TextField()
  val strongHealSpellField = new TextField()
  val strongHealHealthField = new TextField()
  val strongHealManaField = new TextField()
  val ihHealHealthField = new TextField()
  val ihHealManaField = new TextField()
  val uhHealHealthField = new TextField()
  val uhHealManaField = new TextField()
  val hPotionHealHealthField = new TextField()
  val hPotionHealManaField = new TextField()
  val mPotionHealManaMinField = new TextField()

  // The AutoHeal tab component
  val autoHealTab: Component = Component.wrap(new JPanel(new GridBagLayout) {
    val c = new GridBagConstraints()
    c.insets = new Insets(5, 5, 5, 5)

    // Define the preferred width for each text field
    val spellFieldWidth = 150
    val healthManaFieldWidth = 70

    // Add components to the layout
    // Light Heal
    addComponent(new Label("LoSpell"), 0, 0)
    addTextField(lightHealSpellField, 1, 0, spellFieldWidth)
    addComponent(new Label("Health"), 3, 0)
    addTextField(lightHealHealthField, 4, 0, healthManaFieldWidth)
    addComponent(new Label("Mana"), 5, 0)
    addTextField(lightHealManaField, 6, 0, healthManaFieldWidth)

    // Strong Heal
    addComponent(new Label("HiSpell"), 0, 1)
    addTextField(strongHealSpellField, 1, 1, spellFieldWidth)
    addComponent(new Label("Health"), 3, 1)
    addTextField(strongHealHealthField, 4, 1, healthManaFieldWidth)
    addComponent(new Label("Mana"), 5, 1)
    addTextField(strongHealManaField, 6, 1, healthManaFieldWidth)

    // IH Rune
    c.gridx = 0
    c.gridy = 2
    c.gridwidth = 1
    c.fill = GridBagConstraints.NONE
    addComponent(new Label("IH Rune"), 0, 2)

    // Empty column for alignment
    c.gridx = 1
    c.gridy = 2
    c.gridwidth = 1
    c.fill = GridBagConstraints.NONE
    addComponent(new Label(""), 1, 2)

    c.gridx = 3
    c.gridy = 2
    c.gridwidth = 1
    c.fill = GridBagConstraints.NONE
    addComponent(new Label("Health"), 3, 2)

    c.gridx = 4
    c.gridy = 2
    c.gridwidth = 1
    c.fill = GridBagConstraints.HORIZONTAL
    ihHealHealthField.peer.setPreferredSize(new Dimension(healthManaFieldWidth, ihHealHealthField.peer.getPreferredSize.height))
    addComponent(ihHealHealthField, 4, 2)

    c.gridx = 5
    c.gridy = 2
    c.gridwidth = 1
    c.fill = GridBagConstraints.NONE
    addComponent(new Label("Mana"), 5, 2)

    c.gridx = 6
    c.gridy = 2
    c.gridwidth = 1
    c.fill = GridBagConstraints.HORIZONTAL
    ihHealManaField.peer.setPreferredSize(new Dimension(healthManaFieldWidth, ihHealManaField.peer.getPreferredSize.height))
    addComponent(ihHealManaField, 6, 2)

    // UH Rune
    c.gridx = 0
    c.gridy = 3
    addComponent(new Label("UH Rune"), 0, 3)

    // Empty column for alignment
    c.gridx = 1
    c.gridy = 3
    addComponent(new Label(""), 1, 3)

    c.gridx = 3
    c.gridy = 3
    addComponent(new Label("Health"), 3, 3)

    c.gridx = 4
    c.gridy = 3
    c.fill = GridBagConstraints.HORIZONTAL
    uhHealHealthField.peer.setPreferredSize(new Dimension(healthManaFieldWidth, uhHealHealthField.peer.getPreferredSize.height))
    addComponent(uhHealHealthField, 4, 3)

    c.gridx = 5
    c.gridy = 3
    addComponent(new Label("Mana"), 5, 3)

    c.gridx = 6
    c.gridy = 3
    c.fill = GridBagConstraints.HORIZONTAL
    uhHealManaField.peer.setPreferredSize(new Dimension(healthManaFieldWidth, uhHealManaField.peer.getPreferredSize.height))
    addComponent(uhHealManaField, 6, 3)

    // H Potion
    c.gridx = 0
    c.gridy = 4
    addComponent(new Label("H Potion"), 0, 4)

    c.gridx = 1
    c.gridy = 4
    addComponent(new Label(""), 1, 4)

    c.gridx = 3
    c.gridy = 4
    addComponent(new Label("Health"), 3, 4)

    c.gridx = 4
    c.gridy = 4
    c.fill = GridBagConstraints.HORIZONTAL
    hPotionHealHealthField.peer.setPreferredSize(new Dimension(healthManaFieldWidth, hPotionHealHealthField.peer.getPreferredSize.height))
    addComponent(hPotionHealHealthField, 4, 4)

    c.gridx = 5
    c.gridy = 4
    addComponent(new Label("Mana"), 5, 4)

    c.gridx = 6
    c.gridy = 4
    c.fill = GridBagConstraints.HORIZONTAL
    hPotionHealManaField.peer.setPreferredSize(new Dimension(healthManaFieldWidth, hPotionHealManaField.peer.getPreferredSize.height))
    addComponent(hPotionHealManaField, 6, 4)

    // M Potion
    c.gridx = 0
    c.gridy = 5
    addComponent(new Label("M Potion"), 0, 5)

    c.gridx = 1
    c.gridy = 5
    addComponent(new Label(""), 1, 5)

    c.gridx = 5
    c.gridy = 5
    addComponent(new Label("Mana"), 5, 5)

    c.gridx = 6
    c.gridy = 5
    c.fill = GridBagConstraints.HORIZONTAL
    mPotionHealManaMinField.peer.setPreferredSize(new Dimension(healthManaFieldWidth, mPotionHealManaMinField.peer.getPreferredSize.height))
    addComponent(mPotionHealManaMinField, 6, 5)

//    // Button row
//    c.gridy = 6
//    c.gridx = 3
//    c.gridwidth = 2
//    add(updateButton.peer, c)


    private def addComponent(component: Component, x: Int, y: Int): Unit = {
      c.gridx = x
      c.gridy = y
      c.gridwidth = 1
      c.fill = GridBagConstraints.NONE
      add(component.peer, c)
    }

    private def addTextField(textField: TextField, x: Int, y: Int, width: Int): Unit = {
      c.gridx = x
      c.gridy = y
      c.gridwidth = 1
      c.fill = GridBagConstraints.HORIZONTAL
      textField.peer.setPreferredSize(new Dimension(width, textField.peer.getPreferredSize.height))
      add(textField.peer, c)
    }



  })
}