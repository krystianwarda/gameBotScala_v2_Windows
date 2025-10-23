package utils

import cats.effect.IO
import utils.SettingsUtils.UISettings
import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import scala.swing._
import scala.swing.event.ButtonClicked
import scala.concurrent.duration._
import java.awt.{Dimension, Font}
import javax.swing.{SwingConstants, Timer}
import java.awt.event.{ActionEvent, ActionListener}
import java.awt.Robot
import java.awt.event.KeyEvent
import keyboard.KeyboardUtils

case class Credentials(accountNumber: String, password: String)

object AutorunManager {
  private val autorunSettingsPath = "C:\\RealeraRelease_v1\\autorun_settings\\bot_settings.txt"
  private val credentialsPath = "C:\\RealeraRelease_v1\\autorun_settings\\credentials.txt"

  def loadCredentials(): IO[Option[Credentials]] = {
    IO {
      val file = new java.io.File(credentialsPath)
      if (file.exists()) {
        val content = scala.io.Source.fromFile(file).mkString.trim
        val parts = content.split("\\s+")
        if (parts.length >= 2) {
          Some(Credentials(parts(0), parts(1)))
        } else {
          println(s"Invalid credentials format in: $credentialsPath")
          None
        }
      } else {
        println(s"Credentials file not found at: $credentialsPath")
        None
      }
    }
  }

  def listAllWindows(): IO[Unit] = {
    IO {
      import java.awt.Frame
      val frames = Frame.getFrames
      println(s"\n=== DETECTED WINDOWS (${frames.length} total) ===")

      if (frames.isEmpty) {
        println("No AWT/Swing windows found")
      } else {
        frames.zipWithIndex.foreach { case (frame, index) =>
          val title = Option(frame.getTitle).getOrElse("(null title)")
          val visible = frame.isVisible
          val className = frame.getClass.getSimpleName
          println(s"[$index] Title: '$title' | Visible: $visible | Class: $className")
        }
      }

      // Also check native windows using JNA if available
      try {
        println("\n=== CHECKING NATIVE WINDOWS ===")
        import com.sun.jna.platform.win32.User32
        import com.sun.jna.platform.win32.WinDef.HWND
        import com.sun.jna.platform.win32.WinUser
        import com.sun.jna.Pointer

        val user32 = User32.INSTANCE
        val windowTitles = scala.collection.mutable.ListBuffer[String]()

        val callback = new WinUser.WNDENUMPROC {
          def callback(hWnd: HWND, data: Pointer): Boolean = {
            val titleBuffer = new Array[Char](512)
            user32.GetWindowText(hWnd, titleBuffer, 512)
            val title = new String(titleBuffer).trim.takeWhile(_ != '\u0000')
            if (title.nonEmpty && user32.IsWindowVisible(hWnd)) {
              windowTitles += title
            }
            true
          }
        }

        user32.EnumWindows(callback, null)

        if (windowTitles.isEmpty) {
          println("No visible native windows found")
        } else {
          windowTitles.zipWithIndex.foreach { case (title, index) =>
            val containsRealera = title.toLowerCase.contains("realera")
            val marker = if (containsRealera) " *** REALERA MATCH ***" else ""
            println(s"[$index] '$title'$marker")
          }
        }

      } catch {
        case e: Exception =>
          println(s"Could not enumerate native windows: ${e.getMessage}")
      }

      println("=== END WINDOW DETECTION ===\n")
    }
  }


  def pressEnter(): IO[Unit] = {
    IO {
      val robot = new Robot()
      Thread.sleep(500)
      robot.keyPress(KeyEvent.VK_ENTER)
      robot.keyRelease(KeyEvent.VK_ENTER)
      println("Enter key pressed")
    }
  }

  def pressTab(): IO[Unit] = {
    IO {
      val robot = new Robot()
      Thread.sleep(500)
      robot.keyPress(KeyEvent.VK_TAB)
      robot.keyRelease(KeyEvent.VK_TAB)
      println("Tab key pressed")
    }
  }

  def typeTextOnly(robot: Robot, text: String): IO[Unit] = {
    IO {
      println(s"Typing text: $text")
      text.foreach { char =>
        val keyCode = KeyEvent.getExtendedKeyCodeForChar(char)
        if (keyCode == KeyEvent.VK_UNDEFINED) {
          println(s"[ERROR] Cannot type char: $char")
        } else {
          val shiftRequired = Character.isUpperCase(char) || "~!@#$%^&*()_+{}|:\"<>?".contains(char)
          if (shiftRequired) robot.keyPress(KeyEvent.VK_SHIFT)
          robot.keyPress(keyCode)
          robot.keyRelease(keyCode)
          if (shiftRequired) robot.keyRelease(KeyEvent.VK_SHIFT)
          Thread.sleep(50) // Small delay between characters
        }
      }
      println(s"Finished typing: $text")
    }
  }


  def findRealeraWindow(): IO[Boolean] = {
    for {
      _ <- listAllWindows()
      result <- IO {
        import com.sun.jna.platform.win32.User32
        import com.sun.jna.platform.win32.WinDef.{HWND, DWORD}
        import com.sun.jna.platform.win32.WinUser
        import com.sun.jna.platform.win32.Kernel32
        import com.sun.jna.Pointer

        println(s"\n=== SEARCHING FOR REALERA WINDOW ===")

        try {
          val user32 = User32.INSTANCE
          val kernel32 = Kernel32.INSTANCE
          var realeraWindow: Option[HWND] = None
          var backupWindow: Option[HWND] = None

          val callback = new WinUser.WNDENUMPROC {
            def callback(hWnd: HWND, data: Pointer): Boolean = {
              val titleBuffer = new Array[Char](512)
              user32.GetWindowText(hWnd, titleBuffer, 512)
              val title = new String(titleBuffer).trim.takeWhile(_ != '\u0000')

              if (title.nonEmpty && user32.IsWindowVisible(hWnd)) {
                val titleLower = title.toLowerCase

                if (titleLower.contains("realera client") || titleLower.contains("realera - ")) {
                  println(s"✅ Found priority Realera game window: '$title'")
                  realeraWindow = Some(hWnd)
                } else if (titleLower.contains("tibia") || titleLower.contains("otclient")) {
                  if (backupWindow.isEmpty) {
                    println(s"✅ Found backup game window: '$title'")
                    backupWindow = Some(hWnd)
                  }
                }
              }
              true
            }
          }

          user32.EnumWindows(callback, null)
          val selectedWindow = realeraWindow.orElse(backupWindow)

          selectedWindow match {
            case Some(hwnd) =>
              println("Attempting to bring window to foreground...")

              // Get thread IDs and convert to DWORD
              val foregroundWindow = user32.GetForegroundWindow()
              val currentThreadId = new DWORD(kernel32.GetCurrentThreadId())
              val foregroundThreadId = new DWORD(user32.GetWindowThreadProcessId(foregroundWindow, null))
              val targetThreadId = new DWORD(user32.GetWindowThreadProcessId(hwnd, null))

              // Restore window first
              user32.ShowWindow(hwnd, WinUser.SW_RESTORE)
              Thread.sleep(1000)

              // Try to attach to foreground thread to gain permission
              if (foregroundThreadId.intValue() != currentThreadId.intValue()) {
                user32.AttachThreadInput(currentThreadId, foregroundThreadId, true)
              }
              if (targetThreadId.intValue() != currentThreadId.intValue() && targetThreadId.intValue() != foregroundThreadId.intValue()) {
                user32.AttachThreadInput(currentThreadId, targetThreadId, true)
              }

              // Now try to set foreground
              user32.SetForegroundWindow(hwnd)
              Thread.sleep(1000)

              // Show window normally
              user32.ShowWindow(hwnd, WinUser.SW_SHOW)
              Thread.sleep(500)

              // Focus
              user32.SetFocus(hwnd)
              Thread.sleep(1000)

              // Detach thread inputs
              if (foregroundThreadId.intValue() != currentThreadId.intValue()) {
                user32.AttachThreadInput(currentThreadId, foregroundThreadId, false)
              }
              if (targetThreadId.intValue() != currentThreadId.intValue() && targetThreadId.intValue() != foregroundThreadId.intValue()) {
                user32.AttachThreadInput(currentThreadId, targetThreadId, false)
              }

              println("✅ Window focused successfully")
              true
            case None =>
              println("❌ No Realera/game window found")
              false
          }

        } catch {
          case e: Exception =>
            println(s"Error finding Realera window: ${e.getMessage}")
            false
        }
      }
    } yield result
  }

  def maximizeRealeraWindow(): IO[Unit] = {
    IO {
      import com.sun.jna.platform.win32.User32
      import com.sun.jna.platform.win32.WinDef.{HWND, DWORD}
      import com.sun.jna.platform.win32.WinUser
      import com.sun.jna.platform.win32.Kernel32
      import com.sun.jna.Pointer

      try {
        val user32 = User32.INSTANCE
        val kernel32 = Kernel32.INSTANCE
        var realeraWindow: Option[HWND] = None
        var backupWindow: Option[HWND] = None

        val callback = new WinUser.WNDENUMPROC {
          def callback(hWnd: HWND, data: Pointer): Boolean = {
            val titleBuffer = new Array[Char](512)
            user32.GetWindowText(hWnd, titleBuffer, 512)
            val title = new String(titleBuffer).trim.takeWhile(_ != '\u0000')

            if (title.nonEmpty && user32.IsWindowVisible(hWnd)) {
              val titleLower = title.toLowerCase

              if (titleLower.contains("realera client") || titleLower.contains("realera - ")) {
                println(s"✅ Found priority Realera game window for maximizing: '$title'")
                realeraWindow = Some(hWnd)
              } else if (titleLower.contains("tibia") || titleLower.contains("otclient")) {
                if (backupWindow.isEmpty) {
                  backupWindow = Some(hWnd)
                }
              }
            }
            true
          }
        }

        user32.EnumWindows(callback, null)
        val selectedWindow = realeraWindow.orElse(backupWindow)

        selectedWindow match {
          case Some(hwnd) =>
            println("Maximizing and bringing window to foreground...")

            // Get thread IDs and convert to DWORD
            val foregroundWindow = user32.GetForegroundWindow()
            val currentThreadId = new DWORD(kernel32.GetCurrentThreadId())
            val foregroundThreadId = new DWORD(user32.GetWindowThreadProcessId(foregroundWindow, null))
            val targetThreadId = new DWORD(user32.GetWindowThreadProcessId(hwnd, null))

            // Restore first
            println("Restoring window...")
            user32.ShowWindow(hwnd, WinUser.SW_RESTORE)
            Thread.sleep(2000)

            // Attach thread inputs
            if (foregroundThreadId.intValue() != currentThreadId.intValue()) {
              user32.AttachThreadInput(currentThreadId, foregroundThreadId, true)
            }
            if (targetThreadId.intValue() != currentThreadId.intValue() && targetThreadId.intValue() != foregroundThreadId.intValue()) {
              user32.AttachThreadInput(currentThreadId, targetThreadId, true)
            }

            // Maximize
            println("Maximizing window...")
            user32.ShowWindow(hwnd, WinUser.SW_MAXIMIZE)
            Thread.sleep(2000)

            // Set foreground
            println("Setting foreground...")
            user32.SetForegroundWindow(hwnd)
            Thread.sleep(1000)

            // Show and focus
            user32.ShowWindow(hwnd, WinUser.SW_SHOW)
            Thread.sleep(500)
            user32.SetFocus(hwnd)
            Thread.sleep(1000)

            // Detach thread inputs
            if (foregroundThreadId.intValue() != currentThreadId.intValue()) {
              user32.AttachThreadInput(currentThreadId, foregroundThreadId, false)
            }
            if (targetThreadId.intValue() != currentThreadId.intValue() && targetThreadId.intValue() != foregroundThreadId.intValue()) {
              user32.AttachThreadInput(currentThreadId, targetThreadId, false)
            }

            println("✅ Maximized and focused Realera window")
          case None =>
            println("❌ No Realera window found to maximize")
        }

      } catch {
        case e: Exception =>
          println(s"Error maximizing Realera window: ${e.getMessage}")
      }
    }
  }

  def typeCredentials(credentials: Credentials): IO[Unit] = {
    IO {
      val robot = new Robot()

      // Type account number WITHOUT Enter
      typeTextOnly(robot, credentials.accountNumber).unsafeRunSync()

      // Press Tab to move to password field
      Thread.sleep(1000)
      robot.keyPress(KeyEvent.VK_TAB)
      robot.keyRelease(KeyEvent.VK_TAB)
      println("Tab key pressed to move to password field")

      // Type password WITHOUT Enter
      Thread.sleep(1000)
      typeTextOnly(robot, credentials.password).unsafeRunSync()

      println("Credentials entered successfully (without pressing Enter)")
    }
  }

  def checkAndLoadAutorunSettings(): IO[Option[UISettings]] = {
    IO {
      val file = new java.io.File(autorunSettingsPath)
      if (file.exists()) {
        println(s"Autorun settings file found at: $autorunSettingsPath")
        SettingsUtils.loadSettingsFromFile(autorunSettingsPath)
      } else {
        println(s"No autorun settings file found at: $autorunSettingsPath")
        None
      }
    }
  }

  def handleAutorun(
                     settings: UISettings,
                     applySettingsToUI: UISettings => Unit,
                     runBot: () => Unit
                   ): IO[Unit] = {
    for {
      credentials <- loadCredentials()
      _ <- credentials match {
        case Some(creds) =>
          IO {
            println("Autorun settings detected, applying to UI...")
            applySettingsToUI(settings)

            val autorunDialog = AutorunDialog(
              credentials = Some(creds),
              onStop = () => {
                println("Autorun stopped by user")
              },
              onRun = () => {
                println("Autorun countdown finished, starting credential entry process...")
                // Don't run bot immediately, it will be called after credential entry
              },
              runBot = runBot // Pass the runBot function to be called after credentials
            )

            autorunDialog.show()
          }
        case None =>
          IO {
            println("Autorun stopped: Credentials file not found or invalid")
            val errorDialog = new Dialog() {
              title = "AUTORUN ERROR"
              modal = true
              preferredSize = new Dimension(350, 150)

              contents = new BoxPanel(Orientation.Vertical) {
                contents += Swing.VStrut(20)
                contents += new Label("Credentials file not found or invalid!") {
                  horizontalAlignment = Alignment.Center
                  font = new Font(Font.SANS_SERIF, Font.BOLD, 14)
                }
                contents += Swing.VStrut(10)
                contents += new Label(s"Expected at: $credentialsPath") {
                  horizontalAlignment = Alignment.Center
                }
                contents += Swing.VStrut(20)
                contents += new Button("OK") {
                  reactions += {
                    case ButtonClicked(_) =>
                      dialog.dispose()
                  }

                  private val dialog = this.peer.getRootPane.getParent.asInstanceOf[javax.swing.JDialog]
                }
              }
            }

            errorDialog.centerOnScreen()
            errorDialog.open()
          }
      }
    } yield ()
  }
}

case class AutorunDialog(credentials: Option[Credentials], onStop: () => Unit, onRun: () => Unit, runBot: () => Unit) {

  private var countdownTimer: Option[Timer] = None
  private var currentCountdown = 10
  private val countdownLabel = new Label(s"Autorun will start in $currentCountdown seconds")
  private val stopButton = new Button("STOP")

  private val dialog = new Dialog() {
    title = "AUTO RUN DETECTED"
    modal = true
    preferredSize = new Dimension(300, 150)
    resizable = false

    countdownLabel.font = new Font(Font.SANS_SERIF, Font.BOLD, 14)
    countdownLabel.horizontalAlignment = Alignment.Center

    contents = new BoxPanel(Orientation.Vertical) {
      contents += Swing.VStrut(20)
      contents += countdownLabel
      contents += Swing.VStrut(20)
      contents += new BoxPanel(Orientation.Horizontal) {
        contents += Swing.HGlue
        contents += stopButton
        contents += Swing.HGlue
      }
      contents += Swing.VStrut(20)
    }

    listenTo(stopButton)
    reactions += {
      case ButtonClicked(`stopButton`) =>
        stopCountdown()
        onStop()
        close()
    }
  }

  private def startCountdown(): Unit = {
    val timer = new Timer(1000, new ActionListener {
      override def actionPerformed(e: ActionEvent): Unit = {
        currentCountdown -= 1
        countdownLabel.text = s"Autorun will start in $currentCountdown seconds"

        if (currentCountdown <= 0) {
          stopCountdown()
          dialog.close()
          handleAutorunStart()
        }
      }
    })

    countdownTimer = Some(timer)
    timer.start()
  }

  private def handleAutorunStart(): Unit = {
    credentials match {
      case Some(creds) =>
        val credentialProcess = for {
          windowFound <- AutorunManager.findRealeraWindow()
          _ <- if (windowFound) {
            for {
              _ <- AutorunManager.maximizeRealeraWindow()
              _ <- IO.sleep(3.seconds)
              _ <- AutorunManager.typeCredentials(creds)
              _ <- IO.sleep(1.seconds) // Wait before pressing enter
              _ <- AutorunManager.pressEnter()
              _ <- IO.sleep(3.seconds) // Wait for login to complete before starting bot
              _ <- AutorunManager.pressEnter()
              _ <- IO.sleep(3.seconds) // Wait for login to complete before starting bot
              _ <- IO(runBot()) // Now start the bot after everything is complete
            } yield ()
          } else {
            IO(println("Could not find Realera window, skipping credential entry"))
          }
        } yield ()

        credentialProcess.unsafeRunAndForget()
        onRun()

      case None =>
        println("No credentials available")
        onRun()
    }
  }

  private def stopCountdown(): Unit = {
    countdownTimer.foreach(_.stop())
    countdownTimer = None
  }

  def show(): Unit = {
    startCountdown()
    dialog.centerOnScreen()
    dialog.open()
  }
}