package processing

import mouse.FakeAction
import play.api.libs.json.{JsArray, JsValue, Json}
import processing.AutoTarget.parseCreature
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

      updatedState = checkDynamicHealing(updatedState, settings)

      updatedState = updatedState.copy(initialSettingsSet=true)
    }



    val endTime = System.nanoTime()
    val duration = (endTime - startTime) / 1e9d
    printInColor(ANSI_GREEN, f"[INFO] Processing computeInitialSetupActions took $duration%.6f seconds")

    ((actions, logs), updatedState)
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
        dangerLevelHealing = "high",
        dangerCreaturesList = dangerCreatures
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
