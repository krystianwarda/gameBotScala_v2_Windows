package processing

import mouse.FakeAction
import play.api.libs.json.{JsObject, JsValue}
import userUI.SettingsUtils.UISettings

object AutoLoot {
  def computeAutoLootActions(json: JsValue, settings: UISettings, currentState: ProcessorState): ((Seq[FakeAction], Seq[Log]), ProcessorState) = {
    var actions: Seq[FakeAction] = Seq.empty
    var logs: Seq[Log] = Seq.empty
    var updatedState = currentState // Initialize updatedState

    if (settings.autoTargetSettings.enabled) {

      (json \ "attackInfo" \ "Id").asOpt[Int] match {
        case Some(attackedCreatureTarget) =>
          updatedState = updatedState.copy(stateHunting = "attacking")
          println(s"attackInfo is not null, still fighting target Id: $attackedCreatureTarget, can't loot yet")
        case None =>
          println("Trying to loot.")
          if (updatedState.lastTargetName == "") {
            println("No target specified, no looting needed.")
          } else {
            println("Trying to loot2.")
            if (updatedState.stateHunting == "opening window") {

              println("stage: opening window")
              // Accessing and checking if 'extraWindowLoc' is non-null and retrieving 'Open' positions
              val openPosOpt = (json \ "screenInfo" \ "extraWindowLoc").asOpt[JsObject].flatMap { extraWindowLoc =>
                (extraWindowLoc \ "Open").asOpt[JsObject].flatMap { open =>
                  for {
                    posX <- (open \ "posX").asOpt[Int]
                    posY <- (open \ "posY").asOpt[Int]
                  } yield (posX, posY)
                }
              }

              // Handle the option to do something with the coordinates
              openPosOpt match {
                case Some((xPosWindowOpen, yPosWindowOpen)) =>
                  println(s"stage: window is opened, clicking on Open button. x=$xPosWindowOpen , $yPosWindowOpen")

                  val actionsSeq = Seq(
                    MouseAction(xPosWindowOpen, yPosWindowOpen, "move"),
                    MouseAction(xPosWindowOpen, yPosWindowOpen, "pressLeft"),
                    MouseAction(xPosWindowOpen, yPosWindowOpen, "releaseLeft"),
                  )
                  actions = actions :+ FakeAction("useMouse", None, Some(MouseActions(actionsSeq)))
                  updatedState = updatedState.copy(stateHunting = "free") // looting later
                case None =>
                  println("No Open position available or extraWindowLoc is null")
              }

            } else if (updatedState.stateHunting == "attacking") {
              // Retrieve position coordinates
              val xPositionGame = updatedState.lastTargetPos._1
              val yPositionGame = updatedState.lastTargetPos._2
              val zPositionGame = updatedState.lastTargetPos._3

              println(s"Creature (${updatedState.lastTargetName}) in game position $xPositionGame, $yPositionGame, $zPositionGame")


              // Function to generate position keys with padding on z-coordinate
              def generatePositionKey(x: Int, y: Int, z: Int): String = f"$x$y${z}%02d"

              // Function to check for blood and container, and return the index if conditions are met
              def checkForBloodAndContainerAndGetIndex(positionKey: String): Option[String] = {
                (json \ "areaInfo" \ "tiles" \ positionKey).asOpt[JsObject] match {
                  case Some(tileInfo) =>
                    val items = (tileInfo \ "items").as[JsObject]
                    val itemsList = items.values.toList.reverse // reverse to access the last items first

                    val hasBlood = itemsList.exists { item =>
                      val id = (item \ "id").as[Int]
                      id == 2886 || id == 2887
                    }

                    val isLastContainer = (itemsList.head \ "isContainer").as[Boolean]

                    if (hasBlood && isLastContainer) (tileInfo \ "index").asOpt[String]
                    else None

                  case None => None // No tile information found
                }
              }

              // Check the main position and surrounding positions
              val positionsToCheck = for {
                dx <- -1 to 1
                dy <- -1 to 1
              } yield (xPositionGame + dx, yPositionGame + dy)

              // Find the first position that meets the criteria and retrieve the index
              val indexOpt = positionsToCheck.flatMap { case (x, y) =>
                val key = generatePositionKey(x, y, zPositionGame)
                checkForBloodAndContainerAndGetIndex(key)
              }.headOption

              println(s"Creature screen index $indexOpt")

              if (!indexOpt.isEmpty) {
                // Fetch screen coordinates from JSON using the index
                val screenCoordsOpt = indexOpt.flatMap { index =>
                  (json \ "screenInfo" \ "mapPanelLoc" \ index).asOpt[JsObject].flatMap { coords =>
                    for {
                      x <- (coords \ "x").asOpt[Int]
                      y <- (coords \ "y").asOpt[Int]
                    } yield (x, y)
                  }
                }.getOrElse((0, 0)) // Default coordinates if not found

                println(s"Screen coordinates are x: ${screenCoordsOpt._1}, y: ${screenCoordsOpt._2}")

                // Define the sequence of mouse actions based on retrieved screen coordinates
                val (xPositionScreen, yPositionScreen) = screenCoordsOpt


                println(s"Creature body screen position $xPositionScreen, $yPositionScreen")

                val actionsSeq = Seq(
                  MouseAction(xPositionScreen, yPositionScreen, "move"),
                  MouseAction(xPositionScreen, yPositionScreen, "pressCtrl"),
                  MouseAction(xPositionScreen, yPositionScreen, "pressLeft"),
                  MouseAction(xPositionScreen, yPositionScreen, "releaseLeft"),
                  MouseAction(xPositionScreen, yPositionScreen, "releaseCtrl")
                )

                actions = actions :+ FakeAction("useMouse", None, Some(MouseActions(actionsSeq)))
                updatedState = updatedState.copy(stateHunting = "opening window")
              }
            }
          }
      }
    }

    ((actions, logs), updatedState)
  }

}
