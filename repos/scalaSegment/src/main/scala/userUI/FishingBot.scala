package userUI

import akka.actor.ActorRef
import cats.effect.{IO, Ref}
import play.api.libs.json.{Format, Json}
import player.Player
import utils.SettingsUtils.UISettings

import javax.swing._
import java.awt._
import java.awt.event._
import scala.swing.MenuBar.NoMenuBar.reactions
import scala.swing._
import scala.swing.event.{ButtonClicked, SelectionChanged}


// Ensure RectangleSettings is defined as a case class with parameters
// RectangleSettings defined as a case class
case class RectangleSettings(x: Int, y: Int, width: Int, height: Int)



// Companion object for RectangleSettings
object RectangleSettings {
  implicit val format: Format[RectangleSettings] = Json.format[RectangleSettings]
}


class FishingBot(uiAppActor: ActorRef, jsonProcessorActor: ActorRef, settingsRef: Ref[IO, UISettings]) {
  var selectedRectangles: Seq[String] = Seq.empty // Change to store strings directly
  var fishThrowoutRectangles : Seq[String] = Seq.empty
  var currentMode: String = "Selected"  // Default mode

  // Define the ComboBox within the Scala Swing framework
  val modeSelector = new ComboBox(Seq("Selected", "Throwout"))

  // Listen to selection changes
  modeSelector.selection.reactions += {
    case SelectionChanged(`modeSelector`) =>
      currentMode = modeSelector.selection.item
      println(s"Current mode set to: $currentMode")
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


  private def createGridOverlay(startPoint: Point, endPoint: Point): Unit = {
    val width = Math.abs(startPoint.x - endPoint.x)
    val height = Math.abs(startPoint.y - endPoint.y)

    val gridFrame = new JFrame()
    gridFrame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE)
    gridFrame.setUndecorated(true)
    gridFrame.setSize(width, height)
    val screenSize = Toolkit.getDefaultToolkit.getScreenSize
    val x = Math.min(startPoint.x, screenSize.width - width)
    val y = Math.min(startPoint.y, screenSize.height - height)
    gridFrame.setLocation(x, y)
    val transparentWhite = new Color(255, 255, 255, 128)
    gridFrame.setBackground(transparentWhite)

    val panel: JPanel = new JPanel(new GridLayout(11, 15))
    panel.setOpaque(false)
    for (_y <- 0 until 11; _x <- 0 until 15) {
      val rectangle = new Rectangle(_x, _y)
      val button = new JButton(rectangle.toString) {
        setOpaque(true)
        setBackground(new Color(255, 255, 255, 21))
        setBorder(BorderFactory.createLineBorder(Color.BLACK))
        putClientProperty("selected", false)
        addActionListener(_ => {
          val isSelected = Option(getClientProperty("selected")).getOrElse(false).asInstanceOf[Boolean]
          if (!isSelected) {
            setBackground(new Color(0, 255, 0, 21))
            putClientProperty("selected", true)
          } else {
            setBackground(new Color(255, 255, 255, 21))
            putClientProperty("selected", false)
          }
        })
      }
      panel.add(button)
    }

    gridFrame.getContentPane.add(panel)
    gridFrame.setVisible(true)


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
      val markedRectangleIds: Seq[String] = panel.getComponents
        .filter(_.isInstanceOf[JButton])
        .map(_.asInstanceOf[JButton])
        .filter(button => Option(button.getClientProperty("selected")).getOrElse(false).asInstanceOf[Boolean])
        .map(_.getText)

      println(s"Selected rectangles for mode [$currentMode]: $markedRectangleIds")

      // ✅ Update shared UISettings directly with current selection
      import cats.effect.unsafe.implicits.global
      settingsRef.update { old =>
        val updatedFishing = currentMode match {
          case "Selected" =>
            old.fishingSettings.copy(
              selectedRectangles = markedRectangleIds.toList,
              enabled = true // ✅ turn on fishing when selected tiles are updated
            )
          case "Throwout" =>
            old.fishingSettings.copy(
              fishThrowoutRectangles = markedRectangleIds.toList
            )
        }
        old.copy(fishingSettings = updatedFishing)
      }.unsafeRunSync()


      gridFrame.dispose()
      closeButtonFrame.dispose()
    })



    closeButtonFrame.add(closeButton)
    closeButtonFrame.pack() // Adjust frame size to fit the button
    closeButtonFrame.setVisible(true)

    gridFrame.setVisible(true)
    gridFrame.requestFocus()
  }


  // Helper method to collect marked rectangles - unchanged, assuming it's implemented in your original code
  private def collectMarkedRectangles(panel: JPanel): Seq[String] = {
    panel.getComponents.collect {
      case button: JButton if Option(button.getClientProperty("selected")).contains(true) => button.getText
    }
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

  // Integration of ComboBox into your UI layout
  val fishingTab: BoxPanel = new BoxPanel(Orientation.Vertical) {
    contents += modeSelector
    contents += showOverlayButton
    // Other components as needed
  }


}