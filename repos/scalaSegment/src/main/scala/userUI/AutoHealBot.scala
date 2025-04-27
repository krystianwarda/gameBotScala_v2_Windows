package userUI


import akka.actor.ActorRef
import cats.effect.{IO, Ref}
import player.Player
import utils.SettingsUtils.UISettings

import scala.swing.event.ButtonClicked
import scala.swing._
import scala.swing.event._
import scala.swing._
import java.awt.{Dimension, GridBagConstraints, GridBagLayout, Insets}
import javax.swing.{DefaultComboBoxModel, JComboBox, JLabel, JPanel, JScrollPane}


class AutoHealBot(uiAppActor: ActorRef, jsonProcessorActor: ActorRef, settingsRef: Ref[IO, UISettings])  {
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
  val mPotionHealManaMaxField = new TextField()

  val friend1HealSpellField = new TextField()
  val friend1NameField = new TextField()
  val friend1HealHealthField = new TextField()
  val friend1HealManaField = new TextField()

  val friend2HealSpellField = new TextField()
  val friend2NameField = new TextField()
  val friend2HealHealthField = new TextField()
  val friend2HealManaField = new TextField()

  val friend3HealSpellField = new TextField()
  val friend3NameField = new TextField()
  val friend3HealHealthField = new TextField()
  val friend3HealManaField = new TextField()

  val lightHealHotkeyCheckbox = new CheckBox("Use hotkey")
  val strongHealHotkeyCheckbox = new CheckBox("Use hotkey")
  val ihHealHotkeyCheckbox = new CheckBox("Use hotkey")
  val uhHealHotkeyCheckbox = new CheckBox("Use hotkey")
  val hPotionHealHotkeyCheckbox = new CheckBox("Use hotkey")
  val mPotionHotkeyCheckbox = new CheckBox("Use hotkey")
  val friend1HealHotkeyCheckbox = new CheckBox("Use hotkey")
  val friend2HealHotkeyCheckbox = new CheckBox("Use hotkey")
  val friend3HealHotkeyCheckbox = new CheckBox("Use hotkey")


  val funcButtons = Array("F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8", "F9", "F10", "F11", "F12")
  val lightHealHotkeyDropdown = new JComboBox[String](funcButtons) // Simplified initialization with items

  lightHealHotkeyCheckbox.reactions += {
    case ButtonClicked(`lightHealHotkeyCheckbox`) =>
      if (lightHealHotkeyCheckbox.selected) {
        val selectedKey = lightHealHotkeyDropdown.getSelectedItem.toString
        val spellText = lightHealSpellField.text
        SharedSettingsModel.assignKey(selectedKey, spellText)
      } else {
        val selectedKey = lightHealHotkeyDropdown.getSelectedItem.toString
        SharedSettingsModel.unassignKey(selectedKey)
      }
  }


  val strongHealHotkeyDropdown = new JComboBox[String](funcButtons) // Simplified initialization with items

  strongHealHotkeyCheckbox.reactions += {
    case ButtonClicked(`strongHealHotkeyCheckbox`) =>
      if (strongHealHotkeyCheckbox.selected) {
        val selectedKey = strongHealHotkeyDropdown.getSelectedItem.toString
        val spellText = strongHealSpellField.text
        SharedSettingsModel.assignKey(selectedKey, spellText)
      } else {
        val selectedKey = strongHealHotkeyDropdown.getSelectedItem.toString
        SharedSettingsModel.unassignKey(selectedKey)
      }
  }

  val ihHealHotkeyDropdown = new JComboBox[String]()
  ihHealHotkeyDropdown.setModel(new DefaultComboBoxModel(funcButtons))
  val uhHealHotkeyDropdown = new JComboBox[String]()
  uhHealHotkeyDropdown.setModel(new DefaultComboBoxModel(funcButtons))
  val hPotionHealHotkeyDropdown = new JComboBox[String]()
  hPotionHealHotkeyDropdown.setModel(new DefaultComboBoxModel(funcButtons))
  val mPotionHotkeyDropdown = new JComboBox[String]()
  mPotionHotkeyDropdown.setModel(new DefaultComboBoxModel(funcButtons))
  val friend1HealHotkeyDropdown = new JComboBox[String]()
  friend1HealHotkeyDropdown.setModel(new DefaultComboBoxModel(funcButtons))
  val friend2HealHotkeyDropdown = new JComboBox[String]()
  friend2HealHotkeyDropdown.setModel(new DefaultComboBoxModel(funcButtons))
  val friend3HealHotkeyDropdown = new JComboBox[String]()
  friend3HealHotkeyDropdown.setModel(new DefaultComboBoxModel(funcButtons))

  // The AutoHeal tab component
  val autoHealTab: Component = Component.wrap(new JPanel(new GridBagLayout) {
    val c = new GridBagConstraints()
    c.insets = new Insets(5, 5, 5, 5)

    // Define the preferred width for each text field
    val spellFieldWidth = 140
    val standardFieldWidth = 70

    // Add components to the layout
    // Light Heal
    addComponent(new Label("LoSpell"), 0, 0)
    c.gridwidth = 2
    addTextField(lightHealSpellField, 1, 0, 2, spellFieldWidth)
    c.gridwidth = 1
    addComponent(new Label("Health%"), 3, 0)
    addTextField(lightHealHealthField, 4, 0, 1, standardFieldWidth)
    addComponent(new Label("Mana"), 5, 0)
    addTextField(lightHealManaField, 6, 0, 1, standardFieldWidth)


    // Add checkbox for using a hotkey connected with lightHeal
    addComponent(lightHealHotkeyCheckbox, 7, 0) // Adjust the row index as needed
    c.gridx = c.gridx + 1
    add(new JScrollPane(lightHealHotkeyDropdown), c)

    // Strong Heal
    addComponent(new Label("HiSpell"), 0, 1)
    c.gridwidth = 2
    addTextField(strongHealSpellField, 1, 1,2 ,spellFieldWidth)
    c.gridwidth = 1
    addComponent(new Label("Health%"), 3, 1)
    addTextField(strongHealHealthField, 4, 1, 1, standardFieldWidth)
    addComponent(new Label("Mana"), 5, 1)
    addTextField(strongHealManaField, 6, 1, 1, standardFieldWidth)


    addComponent(strongHealHotkeyCheckbox, 7, 1)
    c.gridx = c.gridx + 1
    add(new JScrollPane(strongHealHotkeyDropdown), c)

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
    addComponent(new Label("Health%"), 3, 2)

    c.gridx = 4
    c.gridy = 2
    c.gridwidth = 1
    c.fill = GridBagConstraints.HORIZONTAL
    ihHealHealthField.peer.setPreferredSize(new Dimension(standardFieldWidth, ihHealHealthField.peer.getPreferredSize.height))
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
    ihHealManaField.peer.setPreferredSize(new Dimension(standardFieldWidth, ihHealManaField.peer.getPreferredSize.height))
    addComponent(ihHealManaField, 6, 2)

//    addComponent(ihHealHotkeyCheckbox, 7, 2)
//    c.gridx = c.gridx + 1
//    add(new JScrollPane(ihHealHotkeyDropdown), c)

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
    addComponent(new Label("Health%"), 3, 3)

    c.gridx = 4
    c.gridy = 3
    c.fill = GridBagConstraints.HORIZONTAL
    uhHealHealthField.peer.setPreferredSize(new Dimension(standardFieldWidth, uhHealHealthField.peer.getPreferredSize.height))
    addComponent(uhHealHealthField, 4, 3)

    c.gridx = 5
    c.gridy = 3
    addComponent(new Label("Mana"), 5, 3)

    c.gridx = 6
    c.gridy = 3
    c.fill = GridBagConstraints.HORIZONTAL
    uhHealManaField.peer.setPreferredSize(new Dimension(standardFieldWidth, uhHealManaField.peer.getPreferredSize.height))
    addComponent(uhHealManaField, 6, 3)

//    addComponent(uhHealHotkeyCheckbox, 7, 3)
//    c.gridx = c.gridx + 1
//    add(new JScrollPane(uhHealHotkeyDropdown), c)

    // H Potion
    c.gridx = 0
    c.gridy = 4
    addComponent(new Label("H Potion"), 0, 4)

    c.gridx = 1
    c.gridy = 4
    addComponent(new Label(""), 1, 4)

    c.gridx = 3
    c.gridy = 4
    addComponent(new Label("Health%"), 3, 4)

    c.gridx = 4
    c.gridy = 4
    c.fill = GridBagConstraints.HORIZONTAL
    hPotionHealHealthField.peer.setPreferredSize(new Dimension(standardFieldWidth, hPotionHealHealthField.peer.getPreferredSize.height))
    addComponent(hPotionHealHealthField, 4, 4)

    c.gridx = 5
    c.gridy = 4
    addComponent(new Label("Mana"), 5, 4)

    c.gridx = 6
    c.gridy = 4
    c.fill = GridBagConstraints.HORIZONTAL
    hPotionHealManaField.peer.setPreferredSize(new Dimension(standardFieldWidth, hPotionHealManaField.peer.getPreferredSize.height))
    addComponent(hPotionHealManaField, 6, 4)

//    addComponent(hPotionHealHotkeyCheckbox, 7, 4)
//    c.gridx = c.gridx + 1
//    add(new JScrollPane(hPotionHealHotkeyDropdown), c)

    // M Potion
    c.gridx = 0
    c.gridy = 5
    addComponent(new Label("M Potion"), 0, 5)

    c.gridx = 1
    c.gridy = 5
    addComponent(new Label(""), 1, 5)

    c.gridx = 3
    c.gridy = 5
    addComponent(new Label("ManaMin"), 3, 5)

    c.gridx = 4
    c.gridy = 5
    c.fill = GridBagConstraints.HORIZONTAL
    mPotionHealManaMinField.peer.setPreferredSize(new Dimension(standardFieldWidth, mPotionHealManaMinField.peer.getPreferredSize.height))
    addComponent(mPotionHealManaMinField, 4, 5)

    c.gridx = 5
    c.gridy = 5
    addComponent(new Label("ManaMax"), 5, 5)

    c.gridx = 6
    c.gridy = 5
    c.fill = GridBagConstraints.HORIZONTAL
    mPotionHealManaMaxField.peer.setPreferredSize(new Dimension(standardFieldWidth, mPotionHealManaMaxField.peer.getPreferredSize.height))
    addComponent(mPotionHealManaMaxField, 6, 5)

//    addComponent(mPotionHotkeyCheckbox, 7, 5)
//    c.gridx = c.gridx + 1
//    add(new JScrollPane(mPotionHotkeyDropdown), c)

    // Heal friend 1
    c.gridx = 0
    c.gridy = 6
    addComponent(new Label("Heal F Spell"), 0, c.gridy)

    addTextField(friend1HealSpellField, 1, c.gridy, 1, standardFieldWidth)
    addTextField(friend1NameField, 2, c.gridy, 1, standardFieldWidth)
    addComponent(new Label("Health%"), 3, c.gridy)
    addTextField(friend1HealHealthField, 4, c.gridy, 1, standardFieldWidth)
    addComponent(new Label("Mana"), 5, c.gridy)
    addTextField(friend1HealManaField, 6, c.gridy, 1, standardFieldWidth)


    addComponent(friend1HealHotkeyCheckbox, 7, c.gridy)
    c.gridx = c.gridx + 1
    add(new JScrollPane(friend1HealHotkeyDropdown), c)

    // Adding action listeners to the friend1HealHotkeyCheckbox
    friend1HealHotkeyCheckbox.reactions += {
      case ButtonClicked(`friend1HealHotkeyCheckbox`) =>
        val selectedKey = friend1HealHotkeyDropdown.getSelectedItem.toString
        val spellName = friend1HealSpellField.text.trim  // Ensure no trailing space
        val characterName = friend1NameField.text.trim   // Trim to remove any extra whitespace
        if (friend1HealHotkeyCheckbox.selected) {
          val fullSpellText = s"$spellName$characterName"
          SharedSettingsModel.assignKey(selectedKey, fullSpellText)
        } else {
          SharedSettingsModel.unassignKey(selectedKey)
        }
    }

    // Heal friend 2
    c.gridx = 0
    c.gridy = 7
    addComponent(new Label("Heal F Spell"), 0, c.gridy)

    addTextField(friend2HealSpellField, 1, c.gridy, 1, standardFieldWidth)
    addTextField(friend2NameField, 2, c.gridy, 1, standardFieldWidth)
    addComponent(new Label("Health%"), 3, c.gridy)
    addTextField(friend2HealHealthField, 4, c.gridy, 1, standardFieldWidth)
    addComponent(new Label("Mana"), 5, c.gridy)
    addTextField(friend2HealManaField, 6, c.gridy, 1, standardFieldWidth)


    addComponent(friend2HealHotkeyCheckbox, 7, c.gridy)
    c.gridx = c.gridx + 1
    add(new JScrollPane(friend2HealHotkeyDropdown), c)

    // Adding action listeners to the friend2HealHotkeyCheckbox
    friend2HealHotkeyCheckbox.reactions += {
      case ButtonClicked(`friend2HealHotkeyCheckbox`) =>
        val selectedKey = friend2HealHotkeyDropdown.getSelectedItem.toString
        val spellName = friend2HealSpellField.text.trim  // Ensure no trailing space
        val characterName = friend2NameField.text.trim   // Trim to remove any extra whitespace
        if (friend2HealHotkeyCheckbox.selected) {
          val fullSpellText = s"$spellName$characterName"
          SharedSettingsModel.assignKey(selectedKey, fullSpellText)
        } else {
          SharedSettingsModel.unassignKey(selectedKey)
        }
    }

    // Heal friend 3
    c.gridx = 0
    c.gridy = 8
    addComponent(new Label("Heal F Spell"), 0, c.gridy)

    addTextField(friend3HealSpellField, 1, c.gridy, 1, standardFieldWidth)
    addTextField(friend3NameField, 2, c.gridy, 1, standardFieldWidth)
    addComponent(new Label("Health%"), 3, c.gridy)
    addTextField(friend3HealHealthField, 4, c.gridy, 1, standardFieldWidth)
    addComponent(new Label("Mana"), 5, c.gridy)
    addTextField(friend3HealManaField, 6, c.gridy, 1, standardFieldWidth)


    addComponent(friend3HealHotkeyCheckbox, 7, c.gridy)
    c.gridx = c.gridx + 1
    add(new JScrollPane(friend3HealHotkeyDropdown), c)

    // Adding action listeners to the friend3HealHotkeyCheckbox
    friend3HealHotkeyCheckbox.reactions += {
      case ButtonClicked(`friend3HealHotkeyCheckbox`) =>
        val selectedKey = friend3HealHotkeyDropdown.getSelectedItem.toString
        val spellName = friend3HealSpellField.text.trim  // Ensure no trailing space
        val characterName = friend3NameField.text.trim   // Trim to remove any extra whitespace
        if (friend3HealHotkeyCheckbox.selected) {
          val fullSpellText = s"$spellName$characterName"
          SharedSettingsModel.assignKey(selectedKey, fullSpellText)
        } else {
          SharedSettingsModel.unassignKey(selectedKey)
        }
    }


    private def addComponent(component: Component, x: Int, y: Int): Unit = {
      c.gridx = x
      c.gridy = y
      c.gridwidth = 1
      c.fill = GridBagConstraints.NONE
      add(component.peer, c)
    }

    private def addTextField(textField: TextField, x: Int, y: Int, width: Int, cellWidth: Int): Unit = {
      c.gridx = x
      c.gridy = y
      c.gridwidth = width
      c.fill = GridBagConstraints.HORIZONTAL
      textField.peer.setPreferredSize(new Dimension(cellWidth, textField.peer.getPreferredSize.height))
      add(textField.peer, c)
    }

  })
}