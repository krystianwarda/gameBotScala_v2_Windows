package processing

import mouse.FakeAction
import play.api.libs.json.{JsArray, JsValue, Json, JsObject, JsSuccess}
import processing.AutoTarget.{CreatureSettings, parseCreature, transformToJSON}
import userUI.SettingsUtils
import userUI.SettingsUtils.UISettings
import utils.consoleColorPrint.{ANSI_GREEN, printInColor}

case class Creature(
                     name: String,
                     count: Int,
                     danger: Int,
                     targetBattle: Boolean,
                     loot: Boolean,
                     chase: Boolean,
                     keepDistance: Boolean,
                     avoidWaves: Boolean,
                     useRune: Boolean,
                     useRuneOnScreen: Boolean,
                     useRuneOnBattle: Boolean
                   )


object InitialSetup {
  def computeInitialSetupActions(json: JsValue, settings: UISettings, currentState: ProcessorState): ((Seq[FakeAction], Seq[Log]), ProcessorState) = {
    val startTime = System.nanoTime()
    var actions: Seq[FakeAction] = Seq()
    var logs: Seq[Log] = Seq()
    var updatedState = currentState
    val currentTime = System.currentTimeMillis()

    if (!updatedState.initialSettingsSet) {
      updatedState = defineCreaturesToLoot(json, updatedState, settings)
      updatedState = checkDynamicHealing(updatedState, settings)
      updatedState = updatedState.copy(initialSettingsSet=true)
    }

    updatedState = updatedState.copy(
      autoloot = updatedState.autoloot.copy(
        carsassToLootImmediately = sortCarcassesByDistanceAndTime(json, updatedState.autoloot.carsassToLootImmediately, updatedState),
        carsassToLootAfterFight = sortCarcassesByDistanceAndTime(json, updatedState.autoloot.carsassToLootAfterFight, updatedState)
      ),
    )


    val endTime = System.nanoTime()
    val duration = (endTime - startTime) / 1e9d
    printInColor(ANSI_GREEN, f"[INFO] Processing computeInitialSetupActions took $duration%.6f seconds")

    ((actions, logs), updatedState)
  }


  // Helper function to sort carcasses by proximity to the character and time of death
  def sortCarcassesByDistanceAndTime(json: JsValue, carcassList: List[(String, Long)], state: ProcessorState): List[(String, Long)] = {
    // Extract character's current position from JSON
    val (charX, charY, charZ) = (json \ "characterInfo").asOpt[JsObject].map { characterInfo =>
      val x = (characterInfo \ "PositionX").asOpt[Int].getOrElse(0)
      val y = (characterInfo \ "PositionY").asOpt[Int].getOrElse(0)
      val z = (characterInfo \ "PositionZ").asOpt[Int].getOrElse(0)
      (x, y, z)
    }.getOrElse((0, 0, 0))

    // Helper function to extract position from tile string
    def extractTilePosition(tile: String): (Int, Int, Int) = {
      val posX = tile.substring(0, 5).toInt
      val posY = tile.substring(5, 10).toInt
      val posZ = tile.substring(10, 12).toInt
      (posX, posY, posZ)
    }

    // Helper function to calculate the distance between two 3D points
    def calculateDistance(x1: Int, y1: Int, z1: Int, x2: Int, y2: Int, z2: Int): Double = {
      math.sqrt(math.pow(x2 - x1, 2) + math.pow(y2 - y1, 2) + math.pow(z2 - z1, 2))
    }

    // Sort the carcass list by proximity to the character first, then by time of death
    carcassList.sortBy { case (tile, timeOfDeath) =>
      val (tileX, tileY, tileZ) = extractTilePosition(tile)
      val distance = calculateDistance(tileX, tileY, tileZ, charX, charY, charZ)
      (distance, timeOfDeath) // Sort by distance first, then by time
    }
  }


  def defineCreaturesToLoot(json: JsValue, currentState: ProcessorState, settings: UISettings): ProcessorState = {

    val monsters: Seq[(Int, String)] = (json \ "battleInfo").as[JsObject].values.flatMap { creature =>
      println("gate5")
      val isMonster = (creature \ "IsMonster").as[Boolean]
      println("gate6")
      if (isMonster) {
        Some((creature \ "Id").as[Int], (creature \ "Name").as[String])
      } else None
    }.toSeq

    var updatedState = currentState
    // Use the existing creatureList from settings
    val jsonResult = transformToJSON(settings.autoTargetSettings.creatureList)
    //          println(Json.prettyPrint(Json.toJson(jsonResult)))


    // Checking if 'All' creatures are targeted
    val targetAllCreatures = jsonResult.exists { json =>
      (json \ "name").as[String].equalsIgnoreCase("All")
    }

    // Now use Reads from the Creature object
    val creatureDangerMap = jsonResult.map { json =>
      val creature = Json.parse(json.toString()).as[CreatureSettings](CreatureSettings.reads)
      (creature.name, creature.danger)
    }.toMap

    // create a list of monsters to be looted
    if (updatedState.monstersListToLoot.isEmpty) {
      val monstersWithLoot = if (targetAllCreatures) {
        // Take all monster names if targeting "All" and convert to List
        monsters.map(_._2).toList
      } else {
        // Parse JSON to List of Creature objects and filter to get names of creatures with loot
        jsonResult.flatMap { json =>
          Json.parse(json.toString()).validate[CreatureSettings] match {
            case JsSuccess(creature, _) if creature.loot => Some(creature.name)
            case _ => None
          }
        }.toList // Ensure the result is a List
      }
      // Assuming updatedState is being updated within a case class or similar context
      updatedState = updatedState.copy(monstersListToLoot = monstersWithLoot)
    }
    println(s"monstersListToLoot set to: ${updatedState.monstersListToLoot}")
    updatedState
  }

  def checkDynamicHealing(currentState: ProcessorState, settings: UISettings): ProcessorState = {
    var updatedState = currentState
    println(settings.autoTargetSettings.creatureList)

    // The creature list is a Seq[String]
    val creatureListString: Seq[String] = settings.autoTargetSettings.creatureList

    // Parse each string in the sequence and filter for danger level >= 5
    val dangerCreatures: Seq[Creature] =
      creatureListString.flatMap(parseCreatureList)
        .filter(_.danger >= 5)

    // If there are any dangerous creatures, update the state
    if (dangerCreatures.nonEmpty) {


      updatedState = updatedState.copy(
        autotarget = updatedState.autotarget.copy(
          dangerLevelHealing = "high",
          dangerCreaturesList = dangerCreatures
        ),
      )
    }

    updatedState
  }

  def parseCreatureList(creatureInfo: String): Option[Creature] = {
    // Regex pattern to extract the creature details from the string
    val creaturePattern = """(\w+), Count: (\d+), .*? Danger: (\d+), Target in Battle: (\w+), Loot: (\w+), Chase: (\w+), Keep Distance: (\w+), Avoid waves: (\w+), Use Rune: (\w+), .*? Runes on Screen: (\w+), Runes on Battle: (\w+)""".r

    creatureInfo match {
      case creaturePattern(name, count, danger, targetBattle, loot, chase, keepDistance, avoidWaves, useRune, useRuneOnScreen, useRuneOnBattle) =>
        Some(Creature(
          name = name,
          count = count.toInt,
          danger = danger.toInt,
          targetBattle = targetBattle == "Yes",
          loot = loot == "Yes",
          chase = chase == "Yes",
          keepDistance = keepDistance == "Yes",
          avoidWaves = avoidWaves == "Yes",
          useRune = useRune == "Yes",
          useRuneOnScreen = useRuneOnScreen == "Yes",
          useRuneOnBattle = useRuneOnBattle == "Yes"
        ))
      case _ => None // In case the string doesn't match the pattern
    }
  }





}
