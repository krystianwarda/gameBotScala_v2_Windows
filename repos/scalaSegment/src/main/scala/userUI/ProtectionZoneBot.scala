package userUI

import akka.actor.ActorRef
import player.Player

import java.awt.event.{ActionListener, KeyAdapter, KeyEvent, MouseAdapter, MouseMotionAdapter}
import scala.swing._
import java.awt.{BasicStroke, BorderLayout, Color, Dimension, Graphics, Graphics2D, GridBagConstraints, GridBagLayout, GridLayout, Insets, Point, Toolkit, Window}
import javax.swing.{BorderFactory, JButton, JCheckBox, JFrame, JLabel, JPanel, JTextField, WindowConstants}
import scala.swing.MenuBar.NoMenuBar.{listenTo, reactions}
import scala.swing.event._
import scala.swing._
import scala.swing.event._
import scala.collection.mutable


class ProtectionZoneBot(player: Player, uiAppActor: ActorRef, jsonProcessorActor: ActorRef) {
  var selectedRectangles: Seq[String] = Seq.empty
  var stringsList: Seq[String] = Seq.empty

  // Existing checkbox
  val playerOnScreenAlertCheckbox = new CheckBox("Player on the screen alert")
  val escapeToProtectionZoneCheckbox = new CheckBox("Escape to protection zone")
  val inputFieldLabel = new Label("Add creature to ignore:")
  val inputTextField = new TextField(20)

  // Assuming these are Java Swing components
  val addButton = new javax.swing.JButton("Add")
  val removeButton = new javax.swing.JButton("Remove")

  // Assuming this is a Scala Swing component, which needs to be corrected based on your actual implementation
  val comboBox = new ComboBox(List.empty[String]) {
    maximumRowCount = 5
  }

  // Define the JButton for showing the overlay
  val showOverlayButton = new javax.swing.JButton("Mark Protection Zone")
  showOverlayButton.addActionListener(_ => {
    overlayFrame.setSize(Toolkit.getDefaultToolkit.getScreenSize)
    overlayFrame.setVisible(true)
  })




  // Wrap the entire JPanel in a Scala Swing Component
  val protectionZoneTab: Component = Component.wrap(new javax.swing.JPanel(new GridBagLayout) {
    val c = new GridBagConstraints()
    c.insets = new Insets(5, 5, 5, 5)
    c.gridx = 0
    c.gridy = GridBagConstraints.RELATIVE
    c.anchor = GridBagConstraints.NORTHWEST

    add(playerOnScreenAlertCheckbox.peer, c)
    add(escapeToProtectionZoneCheckbox.peer, c)
    add(inputFieldLabel.peer, c)
    c.fill = GridBagConstraints.HORIZONTAL
    add(inputTextField.peer, c)
    c.fill = GridBagConstraints.NONE
    add(addButton, c) // Directly add Java Swing JButton
    add(removeButton, c) // Directly add Java Swing JButton
    c.fill = GridBagConstraints.HORIZONTAL
    add(comboBox.peer, c) // Use .peer for Scala Swing components
    add(showOverlayButton, c) // Directly add Java Swing JButton
  })

  listenTo(comboBox.selection)
  reactions += {
    case SelectionChanged(`comboBox`) =>
      if (inputTextField.text.nonEmpty && !stringsList.contains(inputTextField.text)) {
        stringsList = inputTextField.text +: stringsList // Prepend to list
        updateComboBox()
        inputTextField.text = "" // Clear input field
      }
    case ButtonClicked(`removeButton`) =>
      comboBox.selection.item.foreach { item =>
        stringsList = stringsList.filterNot(_ == item) // Remove selected item
        updateComboBox()
      }
  }


  def setIgnoredCreatures(creatures: Seq[String]): Unit = {
    stringsList = creatures
    updateComboBox()
  }

  def getIgnoredCreatures: Seq[String] = stringsList

  private def updateComboBox(): Unit = {
    comboBox.peer.setModel(ComboBox.newConstantModel(stringsList))
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
