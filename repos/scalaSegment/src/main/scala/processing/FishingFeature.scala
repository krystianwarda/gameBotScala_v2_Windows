package processing

import play.api.libs.json.{JsObject, JsValue}
import userUI.SettingsUtils.UISettings
import mouse._
import keyboard._
import cats.syntax.all._

import processing.Process.{extractOkButtonPosition, handleRetryStatus, performMouseActionSequance, timeToRetry}
import utils.consoleColorPrint.{ANSI_GREEN, printInColor}

object FishingFeature {

  // Minimal version: cast rod and click tile
  def computeFishingFeature(
                             json: JsValue,
                             settings: UISettings,
                             state: GameState
                           ): ((List[MouseAction], List[String]), GameState) = {
    println("Inside computeFishingFeature")
    if (settings.fishingSettings.enabled) {
      println("No Action in computeFishingFeature")
      return ((List.empty, List("Fishing disabled")), state)
    } else {

      val startTime = System.nanoTime()

      println("Selected tiles: " + settings.fishingSettings.selectedRectangles.mkString(", "))
      println(settings.fishingSettings.selectedRectangles.nonEmpty)


      val maybeRodXY = for {
        x <- (json \ "screenInfo" \ "inventoryPanelLoc" \ "inventoryWindow" \ "contentsPanel" \ "slot10" \ "x").asOpt[Int]
        y <- (json \ "screenInfo" \ "inventoryPanelLoc" \ "inventoryWindow" \ "contentsPanel" \ "slot10" \ "y").asOpt[Int]
      } yield (x, y)

      println("TEST USERSETTINGS")
      println(settings.fishingSettings.selectedRectangles.headOption)

      val maybeTileXY = settings.fishingSettings.selectedRectangles.headOption.flatMap { tileId =>
        for {
          x <- (json \ "screenInfo" \ "mapPanelLoc" \ tileId \ "x").asOpt[Int]
          y <- (json \ "screenInfo" \ "mapPanelLoc" \ tileId \ "y").asOpt[Int]
        } yield (x, y)
      }

      println("TEST ON")
      println(maybeRodXY)
      println(maybeTileXY)
      println("TEST END")

      val mouseActions = (maybeRodXY, maybeTileXY) match {
        case (Some((rodX, rodY)), Some((tileX, tileY))) =>
          List(
            MoveMouse(rodX, rodY),
            RightButtonPress(rodX, rodY),
            RightButtonRelease(rodX, rodY),
            MoveMouse(tileX, tileY),
            LeftButtonPress(tileX, tileY),
            LeftButtonRelease(tileX, tileY)
          )
        case _ =>
          println("[Fishing] Missing positions for rod or tile")
          List.empty
      }

      val logs = if (mouseActions.nonEmpty)
        List("[Fishing] Basic fishing executed.")
      else
        List("[Fishing] No actions executed.")


      val endTime = System.nanoTime()
      val duration = (endTime - startTime) / 1e9d
      printInColor(ANSI_GREEN, f"[INFO] Processing computeFishingActions took $duration%.6f seconds")

      println("Finished action in fishing feature")
      println(mouseActions)
      println("END fishing")
      ((mouseActions, logs), state)
    }


  }

  // Dummy function to simulate stack size extraction. Replace with actual logic.
  def extractStackSize(slot: String): Int = {
    // Assume slot naming convention contains numbers which imply stack size, e.g., "slot1" implies 1 item.
    // This is purely illustrative. Adjust to match your actual data.
    val size = slot.filter(_.isDigit)
    if (size.isEmpty) 1 else size.toInt
  }

  // Function to dynamically determine the full key name based on substring and extract the necessary JSON object
  def extractItemInfoOpt(json: JsValue, containerNameSubstring: String, contentsPanel: String, itemSlot: String): Option[JsObject] = {
    // Retrieve all keys from "inventoryPanelLoc" and filter them to find the full key containing the substring
    val inventoryPanelLoc = (json \ "screenInfo" \ "inventoryPanelLoc").as[JsObject]
    val fullContainerNameOpt = inventoryPanelLoc.keys.find(_.contains(containerNameSubstring))

    // Using the full container name to navigate further if available
    fullContainerNameOpt.flatMap { fullContainerName =>
      (json \ "screenInfo" \ "inventoryPanelLoc" \ fullContainerName \ contentsPanel \ itemSlot).asOpt[JsObject]
    }
  }

}
