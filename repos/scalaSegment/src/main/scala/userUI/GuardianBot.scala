package userUI

import akka.actor.ActorRef
import player.Player

import scala.swing._
import scala.swing.event._
import java.awt.event.{ActionListener, KeyAdapter, KeyEvent, MouseAdapter, MouseMotionAdapter}
import scala.swing._
import java.awt.{BasicStroke, BorderLayout, Color, Dimension, Graphics, Graphics2D, GridBagConstraints, GridBagLayout, GridLayout, Insets, Point, Toolkit, Window}
import javax.swing.{BorderFactory, DefaultComboBoxModel, DefaultListModel, JButton, JCheckBox, JFrame, JLabel, JList, JPanel, JTextField, ListSelectionModel, WindowConstants}
import scala.{+:, ::}
import scala.swing.MenuBar.NoMenuBar.{listenTo, reactions}
import scala.swing.event._
import scala.swing._
import scala.swing.event._
import scala.collection.{Seq, mutable}


class GuardianBot(player: Player, uiAppActor: ActorRef, jsonProcessorActor: ActorRef) {
  var selectedRectangles: Seq[String] = Seq.empty
//  var creaturesList: Seq[String] = Seq.empty

  val discordWebhookLabel = new Label("Discord Webhook:")
  val discordWebhookField = new TextField()
  val messageReceiverNameLabel = new Label("Message Receiver:")
  val messageReceiverNameField = new TextField()
  // Simplified for clarity: Directly manage waypoints with a DefaultListModel
  val pzWaypointListModel = new DefaultListModel[String]()
  val pzWaypointsList = new JList[String](pzWaypointListModel)
  pzWaypointsList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)




  // Existing checkbox

  val soundLabel = new Label(" S")
  val messageLabel = new Label(" M")
  val discordLabel = new Label(" D")
  val logoutLabel = new Label(" X")
  val pzLabel = new Label(" PZ")


  val playerOnScreenSoundCheckbox = new CheckBox()
  val playerOnScreenMessageCheckbox = new CheckBox()
  val playerOnScreenDiscordCheckbox = new CheckBox()
  val playerOnScreenLogoutCheckbox = new CheckBox()
  val playerOnScreenPzCheckbox = new CheckBox()

  val playerDetectedSoundCheckbox = new CheckBox()
  val playerDetectedMessageCheckbox = new CheckBox()
  val playerDetectedDiscordCheckbox = new CheckBox()
  val playerDetectedLogoutCheckbox = new CheckBox()
  val playerDetectedPzCheckbox = new CheckBox()

  val playerAttackedSoundCheckbox = new CheckBox()
  val playerAttackedMessageCheckbox = new CheckBox()
  val playerAttackedDiscordCheckbox = new CheckBox()
  val playerAttackedLogoutCheckbox = new CheckBox()
  val playerAttackedPzCheckbox = new CheckBox()

  val monsterOnScreenSoundCheckbox = new CheckBox()
  val monsterOnScreenMessageCheckbox = new CheckBox()
  val monsterOnScreenDiscordCheckbox = new CheckBox()
  val monsterOnScreenLogoutCheckbox = new CheckBox()
  val monsterOnScreenPzCheckbox = new CheckBox()

  val gmDetectedSoundCheckbox = new CheckBox()
  val gmDetectedMessageCheckbox = new CheckBox()
  val gmDetectedDiscordCheckbox = new CheckBox()
  val gmDetectedLogoutCheckbox = new CheckBox()
  val gmDetectedPzCheckbox = new CheckBox()

  val defaultMessageSoundCheckbox = new CheckBox()
  val defaultMessageMessageCheckbox = new CheckBox()
  val defaultMessageDiscordCheckbox = new CheckBox()
  val defaultMessageLogoutCheckbox = new CheckBox()
  val defaultMessagePzCheckbox = new CheckBox()

  val privateMessageSoundCheckbox = new CheckBox()
  val privateMessageMessageCheckbox = new CheckBox()
  val privateMessageDiscordCheckbox = new CheckBox()
  val privateMessageLogoutCheckbox = new CheckBox()
  val privateMessagePzCheckbox = new CheckBox()

  val lowCapSoundCheckbox = new CheckBox()
  val lowCapMessageCheckbox = new CheckBox()
  val lowCapDiscordCheckbox = new CheckBox()
  val lowCapLogoutCheckbox = new CheckBox()
  val lowCapPzCheckbox = new CheckBox()

  val lowSuppliesSoundCheckbox = new CheckBox()
  val lowSuppliesMessageCheckbox = new CheckBox()
  val lowSuppliesDiscordCheckbox = new CheckBox()
  val lowSuppliesLogoutCheckbox = new CheckBox()
  val lowSuppliesPzCheckbox = new CheckBox()

  // List of pairs of label and checkboxes for different scenarios
  val scenarios = List(
    ("", List(soundLabel, messageLabel, discordLabel, logoutLabel, pzLabel)),
    ("Player on Screen", List(playerOnScreenSoundCheckbox, playerOnScreenMessageCheckbox, playerOnScreenDiscordCheckbox, playerOnScreenLogoutCheckbox, playerOnScreenPzCheckbox)),
    ("Player Detected", List(playerDetectedSoundCheckbox, playerDetectedMessageCheckbox, playerDetectedDiscordCheckbox, playerDetectedLogoutCheckbox, playerDetectedPzCheckbox)),
    ("Player Attacked", List(playerAttackedSoundCheckbox, playerAttackedMessageCheckbox, playerAttackedDiscordCheckbox, playerAttackedLogoutCheckbox, playerAttackedPzCheckbox)),
    ("Monster on Screen", List(monsterOnScreenSoundCheckbox, monsterOnScreenMessageCheckbox, monsterOnScreenDiscordCheckbox, monsterOnScreenLogoutCheckbox, monsterOnScreenPzCheckbox)),
    ("GM Detected", List(gmDetectedSoundCheckbox, gmDetectedMessageCheckbox, gmDetectedDiscordCheckbox, gmDetectedLogoutCheckbox, gmDetectedPzCheckbox)),
    ("Direct message", List(defaultMessageSoundCheckbox, defaultMessageMessageCheckbox, defaultMessageDiscordCheckbox, defaultMessageLogoutCheckbox, defaultMessagePzCheckbox)),
    ("Private message", List(privateMessageSoundCheckbox, privateMessageMessageCheckbox, privateMessageDiscordCheckbox, privateMessageLogoutCheckbox, privateMessagePzCheckbox)),
    ("Low Cap", List(lowCapSoundCheckbox, lowCapMessageCheckbox, lowCapDiscordCheckbox, lowCapLogoutCheckbox, lowCapPzCheckbox)),
    ("Low Supplies", List(lowSuppliesSoundCheckbox, lowSuppliesMessageCheckbox, lowSuppliesDiscordCheckbox, lowSuppliesLogoutCheckbox, lowSuppliesPzCheckbox)),
  )

  val creaturesToIgnoreFieldLabel = new Label("Add players/creatures to ignore:")
  val creaturesToIgnoreTextField = new TextField(32)
  val creaturesToIgnoreCheckbox = new CheckBox("Safe")

  val addButton = new Button("Add")
  val removeButton = new Button("Remove")

  val ignoredCreaturesModel = new DefaultComboBoxModel[String]()
  val ignoredCreaturesDropdown = new ComboBox[String](Array[String]()) // Initially empty
  ignoredCreaturesDropdown.peer.setModel(ignoredCreaturesModel) // Set the custom model

  val guardianTab: Component = Component.wrap(new JPanel(new GridBagLayout) {
    val mainConstraints = new GridBagConstraints()
    mainConstraints.insets = new Insets(5, 5, 5, 5)
    mainConstraints.gridx = 0
    mainConstraints.gridy = 0 // Start at the first row
    mainConstraints.anchor = GridBagConstraints.NORTHWEST
    mainConstraints.fill = GridBagConstraints.HORIZONTAL

    // A single panel for all scenarios
    val scenariosPanel = new JPanel(new GridBagLayout)
    val scenarioConstraints = new GridBagConstraints()
    scenarioConstraints.insets = new Insets(5, 5, 5, 5)
    scenarioConstraints.gridx = 0
    scenarioConstraints.gridy = 0 // Start at the first row in the scenarios panel
    scenarioConstraints.anchor = GridBagConstraints.NORTHWEST
    scenarioConstraints.fill = GridBagConstraints.HORIZONTAL

    scenarios.foreach { case (labelText, checkboxes) =>
      scenarioConstraints.gridx = 0 // Start at the first column for each new scenario

      val label = new Label(labelText)
      scenariosPanel.add(label.peer, scenarioConstraints)

      scenarioConstraints.gridx = 1 // Position checkboxes starting from the second column
      checkboxes.foreach { checkbox =>
        scenariosPanel.add(checkbox.peer, scenarioConstraints)
        scenarioConstraints.gridx += 1
      }

      scenarioConstraints.gridy += 1 // Move to the next row for the next scenario
    }

    // Add the scenarios panel to the main panel
    this.add(scenariosPanel, mainConstraints)
    mainConstraints.gridy += 1 // Important: Increment main panel's gridy to place the next component below

    // Start a new panel for subsequent components
    val formPanel = new JPanel(new GridBagLayout)
    val formConstraints = new GridBagConstraints()
    formConstraints.insets = new Insets(5, 5, 5, 5)
    formConstraints.gridx = 0
    formConstraints.gridy = 0 // Start at the first row in the form panel
    formConstraints.anchor = GridBagConstraints.NORTHWEST
    formConstraints.fill = GridBagConstraints.HORIZONTAL

    // Adding the input field label and text field
    formPanel.add(creaturesToIgnoreFieldLabel.peer, formConstraints)
    formConstraints.gridwidth = 1
    formConstraints.gridy += 1
    formPanel.add(creaturesToIgnoreTextField.peer, formConstraints)
    formConstraints.gridx += 1
    formPanel.add(creaturesToIgnoreCheckbox.peer, formConstraints)




    // Adding buttons
    formConstraints.gridwidth = 1
    formConstraints.gridy += 1
    formConstraints.gridx = 0
    formPanel.add(addButton.peer, formConstraints)
    formConstraints.gridx += 1
    formPanel.add(removeButton.peer, formConstraints)
    formConstraints.gridwidth = 2
    formConstraints.gridy += 1
    formConstraints.gridx = 0
    formPanel.add(ignoredCreaturesDropdown.peer, formConstraints)
    formConstraints.gridwidth = 1
    formConstraints.gridy += 1
    formConstraints.gridx = 0
    formPanel.add(discordWebhookLabel.peer, formConstraints)
    formConstraints.gridx += 1
    formPanel.add(discordWebhookField.peer, formConstraints)
    formConstraints.gridy += 1
    formConstraints.gridx = 0
    formPanel.add(messageReceiverNameLabel.peer, formConstraints)
    formConstraints.gridx += 1
    formPanel.add(messageReceiverNameField.peer, formConstraints)

//    val discordWebhookLabel = new Label("Discord Webhook:")
//    val discordWebhookField = new TextField()
//    val messageReceiverNameLabel = new Label("Message Receiver:")
//    val messageReceiverNameField = new TextField()

    // Add the form panel to the main panel below the scenarios panel
    this.add(formPanel, mainConstraints)
  })


  listenTo(addButton, removeButton)
  reactions += {
    case ButtonClicked(`addButton`) =>
      val name = creaturesToIgnoreTextField.text
      val safeStatus = creaturesToIgnoreCheckbox.selected
      val entry = s"Name:$name, Safe:$safeStatus"
      if (name.nonEmpty && !ignoredCreaturesModel.getIndexOf(entry).!=(-1)) {
        ignoredCreaturesModel.addElement(entry)
        creaturesToIgnoreTextField.text = "" // Clear input field
      }

    case ButtonClicked(`removeButton`) =>
      Option(ignoredCreaturesDropdown.selection.item).foreach { item =>
        ignoredCreaturesModel.removeElement(item)
      }
  }

  def setIgnoredCreatures(creatures: Seq[String]): Unit = {
    ignoredCreaturesModel.removeAllElements()
    creatures.foreach(ignoredCreaturesModel.addElement)
  }

  def getIgnoredCreatures: Seq[String] = {
    val size = ignoredCreaturesModel.getSize
    (0 until size).map(ignoredCreaturesModel.getElementAt)
  }

  private val drawingComponent = new JPanel() {
    setOpaque(false)
    var startPoint: Option[Point] = None
    var endPoint: Option[Point] = None

    override protected def paintComponent(g: Graphics): Unit = {
      super.paintComponent(g)
      if (startPoint.isDefined && endPoint.isDefined) {
        val g2 = g.asInstanceOf[Graphics2D]
        g2.setColor(Color.RED)
        g2.setStroke(new BasicStroke(2))
        val x = Math.min(startPoint.get.x, endPoint.get.x)
        val y = Math.min(startPoint.get.y, endPoint.get.y)
        val width = Math.abs(startPoint.get.x - endPoint.get.x)
        val height = Math.abs(startPoint.get.y - endPoint.get.y)
        g2.drawRect(x, y, width, height)
      }
    }

    addMouseListener(new MouseAdapter {
      override def mousePressed(e: java.awt.event.MouseEvent): Unit = {
        startPoint = Some(e.getPoint)
        endPoint = None // Reset endPoint to ensure proper drag visualization
      }

      override def mouseReleased(e: java.awt.event.MouseEvent): Unit = {
        endPoint = Some(e.getPoint)
        println(s"Selection: Start=${startPoint.get}, End=${endPoint.get}")
        createGridOverlay(startPoint.get, endPoint.get)
        overlayFrame.dispose() // Dispose of the overlay frame
      }
    })

    addMouseMotionListener(new MouseMotionAdapter {
      override def mouseDragged(e: java.awt.event.MouseEvent): Unit = {
        endPoint = Some(e.getPoint)
        repaint()
      }
    })

    addKeyListener(new KeyAdapter() {
      override def keyPressed(e: KeyEvent): Unit = {
        if (e.getKeyCode == KeyEvent.VK_ESCAPE) {
          overlayFrame.dispose() // Close overlay when Escape is pressed
        }
      }
    })

    // Ensure component is focusable to receive key events
    setFocusable(true)
    requestFocusInWindow()
  }

  // Define overlayFrame separately
  private val overlayFrame = new JFrame()
  overlayFrame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE)
  overlayFrame.setUndecorated(true)
  overlayFrame.setExtendedState(java.awt.Frame.MAXIMIZED_BOTH) // Fully qualified to avoid ambiguity
  overlayFrame.setAlwaysOnTop(true)
  overlayFrame.setType(Window.Type.UTILITY)
  overlayFrame.setBackground(new Color(255, 255, 255, 128))
  overlayFrame.getContentPane.setLayout(new BorderLayout())
  overlayFrame.getContentPane.add(drawingComponent, BorderLayout.CENTER)

  // Method to create grid overlay
  private def createGridOverlay(startPoint: Point, endPoint: Point): Unit = {
    val width = Math.abs(startPoint.x - endPoint.x)
    val height = Math.abs(startPoint.y - endPoint.y)

    val gridFrame = new JFrame()
    gridFrame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE)
    gridFrame.setUndecorated(true)
    gridFrame.setSize(width, height) // Set the size exactly as the marked area

    // Calculate position for gridFrame based on startPoint
    val screenSize = Toolkit.getDefaultToolkit.getScreenSize
    val x = Math.min(startPoint.x, screenSize.width - width)
    val y = Math.min(startPoint.y, screenSize.height - height)
    gridFrame.setLocation(x, y)


    // Set semi-transparent background color
    val transparentWhite = new Color(255, 255, 255, 128)
    gridFrame.setBackground(transparentWhite)

    // Step 1: Define the panel
    val panel: JPanel = new JPanel(new GridLayout(11, 15))
    panel.setOpaque(false) // Ensure panel is not opaque to show the frame's background

    // Step 2: Populate the panel with buttons
    for (_y <- 0 until 11; _x <- 0 until 15) {
      val buttonName = (_x + 1) + "x" + (_y + 1) // Naming buttons as "1x1", "2x1", etc.
      val button = new JButton(buttonName) {
        setOpaque(true)
        setBackground(new Color(255, 255, 255, 21))
        setBorder(BorderFactory.createLineBorder(Color.BLACK))
        putClientProperty("selected", false)

        addActionListener(_ => {
          val isSelected = Option(getClientProperty("selected")).getOrElse(false).asInstanceOf[Boolean]
          if (!isSelected) {
            setBackground(new Color(0, 255, 0, 21)) // Semi-transparent green when selected
            putClientProperty("selected", true)
          } else {
            setBackground(new Color(255, 255, 255, 21)) // Back to very transparent white
            putClientProperty("selected", false)
          }
        })
      }
      panel.add(button) // Add button to panel here, after panel has been defined
    }


    // Directly add panel to gridFrame without BorderLayout.SOUTH modification
    gridFrame.getContentPane.add(panel)

    // Adjustments for closeButtonFrame to be placed outside the gridFrame
    val closeButtonFrame = new JFrame()
    closeButtonFrame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE)
    closeButtonFrame.setUndecorated(true)
    closeButtonFrame.setSize(new Dimension(100, 50))
    closeButtonFrame.setAlwaysOnTop(true)
    // Position closeButtonFrame directly below or to the side of gridFrame
    closeButtonFrame.setLocation(x, y + height) // Example: Placed directly below the grid

    val closeButton = new JButton("Close")
    closeButton.addActionListener(_ => {
      // Directly use the button text, assuming it's in the format you want (e.g., '8x6')
      val markedRectangleIds: Seq[String] = panel.getComponents
        .filter(_.isInstanceOf[JButton])
        .map(_.asInstanceOf[JButton])
        .filter(button => Option(button.getClientProperty("selected")).getOrElse(false).asInstanceOf[Boolean])
        .map(_.getText) // Directly use the button text as the identifier

      // Update the selectedRectangles variable to hold these identifiers
      this.selectedRectangles = markedRectangleIds
      // Print selected tiles
      println("Selected tiles:")
      markedRectangleIds.foreach(println)
      // Dispose of the frames after updating
      gridFrame.dispose()
      closeButtonFrame.dispose()
    })


    closeButtonFrame.add(closeButton)
    closeButtonFrame.pack() // Adjust frame size to fit the button
    closeButtonFrame.setVisible(true)

    gridFrame.setVisible(true)
    gridFrame.requestFocus()
  }


}
