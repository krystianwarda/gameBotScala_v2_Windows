package processing

import cats.effect.unsafe.implicits.global
import mouse._
import play.api.libs.json.{JsDefined, JsNumber, JsObject, JsValue, Json}
import processing.Process.{extractOkButtonPosition, handleRetryStatus, performMouseActionSequance, timeToRetry, updateRetryStatusBasedOnTime}
import utils.{ProcessorState, SettingsUtils}
import utils.consoleColorPrint.{ANSI_GREEN, ANSI_RED, printInColor}

import java.time.Instant
import scala.collection.immutable.Seq
import scala.util.Random

object Fishing {


  def computeFishingActions(json: JsValue, settings: SettingsUtils.UISettings, currentState: ProcessorState): ((Seq[FakeAction], Seq[Log]), ProcessorState) = {
    val startTime = System.nanoTime()
    var actions: Seq[FakeAction] = Seq()
    var logs: Seq[Log] = Seq()
    val fishId = 3578
    var singleFishCount = 0
    var fishStackFound = false
    var updatedState = currentState
    val currentTime = System.currentTimeMillis()
    println("Inside old computeFishingActions")

    if (settings.fishingSettings.enabled) {
      logs = logs :+ Log("I want a fish!")
      println("Selected tiles: " + settings.fishingSettings.selectedRectangles.mkString(", "))
      println(settings.fishingSettings.selectedRectangles.nonEmpty)


      // Check for extra window and press 'Ok' if it exists
      extractOkButtonPosition(json) match {
        case Some((posX, posY)) =>

          val actionsSeq = Seq(
            MouseAction(posX, posY, "move"),
            MouseAction(posX, posY, "pressLeft"),
            MouseAction(posX, posY, "releaseLeft")
          )

          if (updatedState.retryMergeFishDelay == 0 || timeToRetry(updatedState.retryMergeFishDelay, updatedState.retryMidDelay)) {
            actions = actions ++ performMouseActionSequance(actionsSeq)
            logs = logs :+ Log("Merging fishes")
            updatedState = updatedState.copy(retryMergeFishDelay = currentTime) // Set to current time after action
          } else {
            println(s"Waiting to retry merging fishes. Time left: ${(updatedState.retryMidDelay - (currentTime - updatedState.retryMergeFishDelay)) / 1000} seconds")
          }



        case None => // No 'Ok' button or invalid format
      }




      // Accessing and checking containersInfo
      (json \ "containersInfo").asOpt[JsObject].foreach { containersInfo =>
        println("Iterate over containers to find single fish items")

        // Count single fishes and find stacks
        containersInfo.fields.foreach { case (containerName, containerDetails) =>
          println(s"Checking container: $containerName")
          (containerDetails \ "items").asOpt[JsObject].foreach { items =>
            items.fields.foreach {
              case (slot, itemDetails) =>
                val itemId = (itemDetails \ "itemId").asOpt[Int]
                val itemCount = (itemDetails \ "itemCount").asOpt[Int]
                println(s"Checking item in slot $slot: itemId=$itemId, itemCount=$itemCount")
                if (itemId.contains(fishId)) {
                  if (itemCount.contains(1)) {
                    println(s"Found single fish in slot: $slot")
                    singleFishCount += 1
                  } else if (itemCount.exists(count => count > 1 && count < 100)) {
                    println(s"Found stack of fish in slot: $slot with count $itemCount")
                    fishStackFound = true
                  }

                  // Check if the stack is greater than 10
                  if (itemCount.exists(count => count > 10)) {
                    println(s"Found stack of fish greater than 10 in slot: $slot")
                    val itemSlot = slot.replace("slot", "item")

                    val itemScreenInfoOpt = extractItemInfoOpt(json, containerName, "contentsPanel", itemSlot)

                    // Use pattern matching to handle Option[JsObject]
                    itemScreenInfoOpt match {
                      case Some(itemScreenInfo) =>
                        val x = (itemScreenInfo \ "x").as[Int]
                        val y = (itemScreenInfo \ "y").as[Int]
                        println(s"Screen coordinates for item: x=$x, y=$y")

                        // Decide target location based on settings
                        val (targetX, targetY) = if (settings.fishingSettings.fishThrowoutRectangles.nonEmpty) {
                          val randomTileId = scala.util.Random.shuffle(settings.fishingSettings.fishThrowoutRectangles.toList).head
                          val targetTileScreenX = (json \ "screenInfo" \ "mapPanelLoc" \ randomTileId \ "x").asOpt[Int].getOrElse(0)
                          val targetTileScreenY = (json \ "screenInfo" \ "mapPanelLoc" \ randomTileId \ "y").asOpt[Int].getOrElse(0)
                          println(s"Throwing item to random tile: $randomTileId, targetTileScreenX: $targetTileScreenX, targetTileScreenY: $targetTileScreenY")
                          (targetTileScreenX, targetTileScreenY)
                        } else {
                          val presentCharLocation = (json \ "screenInfo" \ "mapPanelLoc" \ "8x6").as[JsObject]
                          val presentCharLocationX = (presentCharLocation \ "x").as[Int]
                          val presentCharLocationY = (presentCharLocation \ "y").as[Int]
                          println(s"Throwing item on the ground at: x=$presentCharLocationX, y=$presentCharLocationY")
                          (presentCharLocationX, presentCharLocationY)
                        }

                        println(s"retryThroughoutFishesStatus: ${updatedState.retryThroughoutFishesStatus}")

                        val actionsSeq = Seq(
                          MouseAction(x, y, "move"),
                          MouseAction(targetX, targetY, "pressLeft"),
                          MouseAction(targetX, targetY, "move"),
                          MouseAction(targetX, targetY, "releaseLeft")
                        )

                        // Check and update retry status based on current value and threshold
                        if (updatedState.retryThroughoutFishesStatus == 0) {
                          actions = actions ++ performMouseActionSequance(actionsSeq) // Use ++ to concatenate sequences
                          logs = logs :+ Log(s"Moveing fishes out.")
                          updatedState = updatedState.copy(retryThroughoutFishesStatus = updatedState.retryThroughoutFishesStatus + 1)
                        } else {
                          val newRetryStatus = handleRetryStatus(updatedState.retryThroughoutFishesStatus, updatedState.retryAttemptsLong)
                          if (newRetryStatus == 0) {
                            println(s"Resetting retryThroughoutFishesStatus.")
                          }
                          updatedState = updatedState.copy(retryThroughoutFishesStatus = newRetryStatus)
                        }

                      case None =>
                        println("No item information found. Skipping actions.")
                    }


                  }

                }
            }
          }
        }


        // Collect slots of single fishes and stacks
        val singleFishSlots = containersInfo.fields.flatMap { case (containerName, containerDetails) =>
          (containerDetails \ "items").asOpt[JsObject].toSeq.flatMap { items =>
            items.fields.collect {
              case (slot, itemDetails) if
                (itemDetails \ "itemId").asOpt[Int].contains(fishId) &&
                  (itemDetails \ "itemCount").asOpt[Int].contains(1) =>
                (containerName, slot)
            }
          }
        }

        val stackFishSlots = containersInfo.fields.flatMap { case (containerName, containerDetails) =>
          (containerDetails \ "items").asOpt[JsObject].toSeq.flatMap { items =>
            items.fields.collect {
              case (slot, itemDetails) if
                (itemDetails \ "itemId").asOpt[Int].contains(fishId) &&
                  (itemDetails \ "itemCount").asOpt[Int].exists(count => count > 1 && count < 100) =>
                (containerName, slot)
            }
          }
        }

        println(s"Single fish slots found: ${singleFishSlots.size}")
        println(s"Size Stack fish slots found: ${stackFishSlots.size}")
        println(s"Stack fish slots found: ${stackFishSlots}")

        // Condition to merge single fish or merge single fish with stack
        if ((fishStackFound && singleFishCount == 1) || (singleFishCount >= 2)) {
          println("merge fishes action.")

          // Collect slots of single fishes
          if (singleFishSlots.size >= 2) {
            val sortedSingleFishSlots = singleFishSlots.sortBy(_._2)
            val (containerName1, slot1) = sortedSingleFishSlots.head
            val (containerName2, slot2) = sortedSingleFishSlots(1)

            println(s"Processing single fish in container: $containerName1, slot: $slot1")
            println(s"Processing single fish in container: $containerName2, slot: $slot2")

            val itemSlot1 = slot1.replace("slot", "item")
            val itemSlot2 = slot2.replace("slot", "item")

            val itemScreenInfoOpt1 = extractItemInfoOpt(json, containerName1, "contentsPanel", itemSlot1)
            val itemScreenInfoOpt2 = extractItemInfoOpt(json, containerName2, "contentsPanel", itemSlot2)

            println(s"itemScreenInfoOpt1 $itemScreenInfoOpt1")
            println(s"itemScreenInfoOpt2 $itemScreenInfoOpt2")


            (itemScreenInfoOpt1, itemScreenInfoOpt2) match {
              case (Some(itemScreenInfo1), Some(itemScreenInfo2)) =>
                val (x1, y1) = ((itemScreenInfo1 \ "x").as[Int], (itemScreenInfo1 \ "y").as[Int])
                val (x2, y2) = ((itemScreenInfo2 \ "x").as[Int], (itemScreenInfo2 \ "y").as[Int])
                println(s"Screen coordinates for merging: x1=$x1, y1=$y1, x2=$x2, y2=$y2")
                println(s"retryMergeFishStatus: ${updatedState.retryMergeFishStatus}")

                val actionsSeq = Seq(
                  MouseAction(x1, y1, "move"),
                  MouseAction(x1, y1, "pressLeft"),
                  MouseAction(x2, y2, "move"),
                  MouseAction(x2, y2, "releaseLeft")
                )


                if (updatedState.retryMergeFishStatus == 0) {
                  actions = actions ++ performMouseActionSequance(actionsSeq) // Use ++ to concatenate sequences
                  logs = logs :+ Log(s"Merging fishes")
                  updatedState = updatedState.copy(retryMergeFishStatus = updatedState.retryMergeFishStatus + 1)
                } else {
                  val newRetryStatus = handleRetryStatus(updatedState.retryMergeFishStatus, updatedState.retryAttemptsLong)
                  if (newRetryStatus == 0) {
                    println(s"Resetting retryMergeFishStatus.")
                  }
                  updatedState = updatedState.copy(retryMergeFishStatus = newRetryStatus)
                }


              case _ =>
                println("Could not find screen info for both single fishes.")
            }
          } else if (singleFishSlots.size == 1 && stackFishSlots.nonEmpty) {
            // Merge single fish with stack fish
            val (singleContainerName, singleSlot) = singleFishSlots.head
            val (stackContainerName, stackSlot) = stackFishSlots.head

            println(s"Merging single fish in container: $singleContainerName, slot: $singleSlot with stack in container: $stackContainerName, slot: $stackSlot")

            val singleItemSlot = singleSlot.replace("slot", "item")
            val stackItemSlot = stackSlot.replace("slot", "item")

            // Using the function to extract item information
            val singleItemScreenInfoOpt = extractItemInfoOpt(json, singleContainerName, "contentsPanel", singleItemSlot)
            val stackItemScreenInfoOpt = extractItemInfoOpt(json, stackContainerName, "contentsPanel", stackItemSlot)

            println(s"Single Item Screen Info: $singleItemScreenInfoOpt")
            println(s"Stack Item Screen Info: $stackItemScreenInfoOpt")

            (singleItemScreenInfoOpt, stackItemScreenInfoOpt) match {
              case (Some(singleItemScreenInfo), Some(stackItemScreenInfo)) =>
                val (x1, y1) = ((singleItemScreenInfo \ "x").as[Int], (singleItemScreenInfo \ "y").as[Int])
                val (x2, y2) = ((stackItemScreenInfo \ "x").as[Int], (stackItemScreenInfo \ "y").as[Int])
                println(s"Screen coordinates for merging: x1=$x1, y1=$y1, x2=$x2, y2=$y2")

                println(s"retryMergeFishStatus: ${updatedState.retryMergeFishStatus}")

                val actionsSeq = Seq(
                  MouseAction(x1, y1, "move"),
                  MouseAction(x1, y1, "pressLeft"),
                  MouseAction(x2, y2, "move"),
                  MouseAction(x2, y2, "releaseLeft")
                )


                // Check and update retry status based on time elapsed

                if (updatedState.retryMergeFishDelay == 0 || timeToRetry(updatedState.retryMergeFishDelay, updatedState.retryMidDelay)) {
                  actions = actions ++ performMouseActionSequance(actionsSeq)
                  logs = logs :+ Log("Merging fishes")
                  updatedState = updatedState.copy(retryMergeFishDelay = currentTime) // Set to current time after action
                } else {
                  println(s"Waiting to retry merging fishes. Time left: ${(updatedState.retryMidDelay - (currentTime - updatedState.retryMergeFishDelay)) / 1000} seconds")
                }




              case _ =>
                println("Could not find screen info for single fish and stack.")
            }
          } else {
            println("Unexpected error: Did not find enough single fishes or stack to merge.")
          }
        } else if (stackFishSlots.size >= 2) {
          // Handle merging two stacks
          val sortedStackFishSlots = stackFishSlots.sortBy { case (_, slot) => extractStackSize(slot) }
          val (containerName1, slot1) = sortedStackFishSlots.head
          val (containerName2, slot2) = sortedStackFishSlots(1)

          println(s"Merging stack in container: $containerName1, slot: $slot1 with stack in container: $containerName2, slot: $slot2")

          val stackItemSlot1 = slot1.replace("slot", "item")
          val stackItemSlot2 = slot2.replace("slot", "item")

          val stackItemScreenInfoOpt1 = extractItemInfoOpt(json, containerName1, "contentsPanel", stackItemSlot1)
          val stackItemScreenInfoOpt2 = extractItemInfoOpt(json, containerName2, "contentsPanel", stackItemSlot2)

          (stackItemScreenInfoOpt1, stackItemScreenInfoOpt2) match {
            case (Some(itemScreenInfo1), Some(itemScreenInfo2)) =>
              val (x1, y1) = ((itemScreenInfo1 \ "x").as[Int], (itemScreenInfo1 \ "y").as[Int])
              val (x2, y2) = ((itemScreenInfo2 \ "x").as[Int], (itemScreenInfo2 \ "y").as[Int])

              val actionsSeq = Seq(
                MouseAction(x1, y1, "move"),
                MouseAction(x1, y1, "pressLeft"),
                MouseAction(x2, y2, "move"),
                MouseAction(x2, y2, "releaseLeft")
              )

              if (updatedState.retryMergeFishStatus == 0) {
                actions = actions ++ performMouseActionSequance(actionsSeq)
                logs = logs :+ Log("Merging fish stacks")
                updatedState = updatedState.copy(retryMergeFishStatus = updatedState.retryMergeFishStatus + 1)
              } else {
                val newRetryStatus = handleRetryStatus(updatedState.retryMergeFishStatus, updatedState.retryAttemptsLong)
                updatedState = updatedState.copy(retryMergeFishStatus = newRetryStatus)
              }
            case _ => println("Could not find screen info for both fish stacks.")
          }
        } else if (singleFishCount == 0 || (singleFishCount == 1 && !fishStackFound)) {
          println("continue fishing.")

          if (settings.fishingSettings.selectedRectangles.nonEmpty) {

            val randomTileId = scala.util.Random.shuffle(settings.fishingSettings.selectedRectangles.toList).head

            // Use the randomTileId to access the JSON structure
            val targetTileScreenX = (json \ "screenInfo" \ "mapPanelLoc" \ randomTileId \ "x").asOpt[Int].getOrElse(0)
            val targetTileScreenY = (json \ "screenInfo" \ "mapPanelLoc" \ randomTileId \ "y").asOpt[Int].getOrElse(0)
            val arrowsX = (json \ "screenInfo" \ "inventoryPanelLoc" \ "inventoryWindow" \ "contentsPanel" \ "slot10" \ "x").asOpt[Int].getOrElse(0)
            val arrowsY = (json \ "screenInfo" \ "inventoryPanelLoc" \ "inventoryWindow" \ "contentsPanel" \ "slot10" \ "y").asOpt[Int].getOrElse(0)


            println(s"Random Tile ID: $randomTileId, targetTileScreenX: $targetTileScreenX, targetTileScreenY: $targetTileScreenY, arrowsX: $arrowsX, arrowsY: $arrowsY")
            println(s"retryFishingStatus: ${updatedState.retryFishingStatus}")


            println("FISHING TEST")

            GlobalMouseManager.instance.foreach { manager =>
              manager.enqueueTask(
                List(
                  MoveMouse(arrowsX, arrowsY),
                  RightButtonPress(arrowsX, arrowsY),
                  RightButtonRelease(arrowsX, arrowsY),
                  MoveMouse(targetTileScreenX, targetTileScreenY),
                  LeftButtonPress(targetTileScreenX, targetTileScreenY),
                  LeftButtonRelease(targetTileScreenX, targetTileScreenY)
                )
              ).unsafeRunAndForget()
            }


            println("FISHING TEST2")


            // Check and update retry status based on current value and threshold
            if (updatedState.retryFishingStatus == 0) {
//              actions = actions ++ performMouseActionSequance(actionsSeq)  // Use ++ to concatenate sequences
              logs = logs :+ Log(s"Fishing")
              updatedState = updatedState.copy(retryFishingStatus = updatedState.retryFishingStatus + 1)
            } else {
              val newRetryStatus = handleRetryStatus(updatedState.retryFishingStatus, updatedState.retryAttempts)
              if (newRetryStatus == 0) {
                println(s"Resetting retryFishingStatus.")
              }
              updatedState = updatedState.copy(retryFishingStatus = newRetryStatus)
            }

          } else {
            println("Tiles has not been selected - searching for tiles.")
          }
        }
      }
    }
    val endTime = System.nanoTime()
    val duration = (endTime - startTime) / 1e9d
    printInColor(ANSI_GREEN, f"[INFO] Processing computeFishingActions took $duration%.6f seconds")

    ((actions, logs),updatedState)
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


