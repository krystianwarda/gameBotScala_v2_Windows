package userUI

import akka.actor.ActorRef
import player.Player
import javax.swing._
import java.awt._
import java.awt.event._
import scala.swing._
import scala.swing.event.ButtonClicked

class FishingBot(player: Player, uiAppActor: ActorRef, jsonProcessorActor: ActorRef) {
  // Define drawingComponent separately
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
        overlayFrame.dispose() // Close the overlayFrame when mouse is released
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
          overlayFrame.dispose() // Close the overlayFrame when Escape is pressed
        }
      }
    })

    // Ensure component is focusable to receive key events
    setFocusable(true)
    requestFocusInWindow()
  }

  // Define overlayFrame separately
  private val overlayFrame = new JFrame()
  overlayFrame.setUndecorated(true)
  overlayFrame.setExtendedState(java.awt.Frame.MAXIMIZED_BOTH)
  overlayFrame.setAlwaysOnTop(true)
  overlayFrame.setType(java.awt.Window.Type.UTILITY)
  overlayFrame.setBackground(new Color(255, 255, 255, 128))
  overlayFrame.getContentPane.setLayout(new BorderLayout())
  overlayFrame.getContentPane.add(drawingComponent, BorderLayout.CENTER)

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
