package userUI

import akka.actor.ActorRef
import play.api.libs.json.{Format, Json}
import player.Player

import javax.swing._
import java.awt._
import java.awt.event._
import scala.swing._
import scala.swing.event.ButtonClicked


// Ensure RectangleSettings is defined as a case class with parameters
case class RectangleSettings(x: Int, y: Int, width: Int, height: Int)
object RectangleSettings {
  // Ensure there's an implicit Format instance for RectangleSettings
  implicit val format: Format[RectangleSettings] = Json.format[RectangleSettings]
}
class FishingBot(player: Player, uiAppActor: ActorRef, jsonProcessorActor: ActorRef) {
  var selectedRectangles: Seq[String] = Seq.empty // Change to store strings directly

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
    gridFrame.setSize(width, height)

    // Calculate position for gridFrame based on startPoint
    val screenSize = Toolkit.getDefaultToolkit.getScreenSize
    val x = Math.min(startPoint.x, screenSize.width - width)
    val y = Math.min(startPoint.y, screenSize.height - height)
    gridFrame.setLocation(x, y)

    // Set semi-transparent background color
    val transparentWhite = new Color(255, 255, 255, 128)
    gridFrame.setBackground(transparentWhite)


    val panel = new JPanel(new GridLayout(11, 15)) {
      setOpaque(false) // Ensure panel is not opaque to show the frame's background
      for (y <- 0 until 11; x <- 0 until 15) {
        val rectangle = new Rectangle(x, y)
        val button = new JButton(rectangle.toString) {
          setOpaque(true) // Necessary for color visibility
          setBackground(new Color(255, 255, 255, 64)) // Initial very transparent white
          setBorder(BorderFactory.createLineBorder(Color.BLACK))
          // Use client properties to track selection state
          putClientProperty("selected", false)

          addActionListener(_ => {
            val isSelected = Option(getClientProperty("selected")).getOrElse(false).asInstanceOf[Boolean]
            if (!isSelected) {
              setBackground(new Color(0, 255, 0, 64)) // Semi-transparent green when selected
              putClientProperty("selected", true)
            } else {
              setBackground(new Color(255, 255, 255, 32)) // Very transparent white otherwise
              putClientProperty("selected", false)
            }
          })
        }
        add(button)
      }
    }


    // Separate frame for the Close button
    val closeButtonFrame = new JFrame() {
      setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE)
      setSize(new Dimension(100, 50))
      setUndecorated(true)
      setAlwaysOnTop(true)
      setLocationRelativeTo(gridFrame) // Position relative to gridFrame
    }

    val closeButton = new JButton("Close")
    closeButton.addActionListener(_ => {
      val markedRectangles = panel.getComponents
        .filter(_.isInstanceOf[JButton])
        .map(_.asInstanceOf[JButton])
        .filter(button => Option(button.getClientProperty("selected")).getOrElse(false).asInstanceOf[Boolean])
        .map(_.getText) // Directly use the button text

      // Update the selectedRectangles with string representations
      selectedRectangles = markedRectangles

      println("Marked Rectangles: " + selectedRectangles.mkString(", "))

      gridFrame.dispose() // Dispose the overlay frame
      closeButtonFrame.dispose() // Dispose the Close button frame
    })

    closeButtonFrame.getContentPane.add(closeButton)
    closeButtonFrame.setVisible(true)

    val contentPane = gridFrame.getContentPane
    contentPane.setLayout(new BorderLayout())
    contentPane.add(panel, BorderLayout.CENTER)
    contentPane.add(closeButton, BorderLayout.SOUTH)

    gridFrame.setVisible(true)
    gridFrame.requestFocus()
  }

  def parseRectanglesFromString(rectanglesString: String): Seq[RectangleSettings] = {
    rectanglesString.split(";").toSeq.filter(_.nonEmpty).map { rectStr =>
      val parts = rectStr.split(",")
      RectangleSettings(parts(0).toInt, parts(1).toInt, parts(2).toInt, parts(3).toInt)
    }
  }

  // Rectangle class to represent grid rectangles
  private case class Rectangle(x: Int, y: Int) {
    override def toString: String = s"${x + 1}x${y + 1}"
  }

  val showOverlayButton = new scala.swing.Button("Mark Area") {
    reactions += {
      case ButtonClicked(_) =>
        overlayFrame.setSize(Toolkit.getDefaultToolkit.getScreenSize)
        overlayFrame.setVisible(true)
    }
  }

  val fishingTab: scala.swing.BoxPanel = new scala.swing.BoxPanel(scala.swing.Orientation.Vertical) {
    contents += showOverlayButton
  }
}


//    val closeButton = new JButton("Close") // Add a close button
//    closeButton.addActionListener(_ => {
//      val markedRectangles = panel.getComponents
//        .filter(_.isInstanceOf[JButton])
//        .map(_.asInstanceOf[JButton])
//        .filter(button => Option(button.getClientProperty("selected")).getOrElse(false).asInstanceOf[Boolean])
//        .map(_.getText)
//      println("Chosen rectangles: " + markedRectangles.mkString(", "))
//      gridFrame.dispose()
//    })
