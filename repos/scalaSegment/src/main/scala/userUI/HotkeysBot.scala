package userUI

import akka.actor.ActorRef
import player.Player

import scala.swing._
import java.awt.{Dimension, GridBagConstraints, GridBagLayout, Insets}
import javax.swing.JPanel

class HotkeysBot(player: Player, uiAppActor: ActorRef, jsonProcessorActor: ActorRef) extends HotkeyObserver {
  // Individual TextField components for each function key
  val hF1Field = new TextField()
  val hF2Field = new TextField()
  val hF3Field = new TextField()
  val hF4Field = new TextField()
  val hF5Field = new TextField()
  val hF6Field = new TextField()
  val hF7Field = new TextField()
  val hF8Field = new TextField()
  val hF9Field = new TextField()
  val hF10Field = new TextField()
  val hF11Field = new TextField()
  val hF12Field = new TextField()

  // List of keys and fields for easy iteration in the UI setup
  val keys = List("F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8", "F9", "F10", "F11", "F12")
  val fields = List(hF1Field, hF2Field, hF3Field, hF4Field, hF5Field, hF6Field, hF7Field, hF8Field, hF9Field, hF10Field, hF11Field, hF12Field)

  val hotkeysTab: Component = Component.wrap(new JPanel(new GridBagLayout) {
    val c = new GridBagConstraints()
    c.insets = new Insets(5, 5, 5, 5)
    val keyLabelWidth = 50
    val textFieldWidth = 100

    keys.zip(fields).foreach { case (key, field) =>
      val index = keys.indexOf(key)
      val x = if (index < 6) 0 else 2
      val y = if (index < 6) index else index - 6
      val label = new Label(key)
      addComponent(label, x, y, keyLabelWidth)
      addTextField(field, x + 1, y, textFieldWidth)
    }

    private def addComponent(component: Component, x: Int, y: Int, width: Int): Unit = {
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

  // Retrieve the text from each function key's TextField
  def getFunctionKeyTexts: Map[String, String] = Map(
    "F1" -> hF1Field.text,
    "F2" -> hF2Field.text,
    "F3" -> hF3Field.text,
    "F4" -> hF4Field.text,
    "F5" -> hF5Field.text,
    "F6" -> hF6Field.text,
    "F7" -> hF7Field.text,
    "F8" -> hF8Field.text,
    "F9" -> hF9Field.text,
    "F10" -> hF10Field.text,
    "F11" -> hF11Field.text,
    "F12" -> hF12Field.text
  )

  SharedSettingsModel.registerObserver(updateHotkeysTab)

  override def update(): Unit = {
    updateHotkeysTab()
  }

  def updateHotkeysTab(): Unit = {
    fields.zip(keys).foreach { case (field, key) =>
      field.enabled = !SharedSettingsModel.isAssigned(key)
      if (!field.enabled) {
        SharedSettingsModel.getSpellNameForKey(key).foreach(field.text = _)
      }
    }
  }

}
