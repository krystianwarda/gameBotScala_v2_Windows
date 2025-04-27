package processing

import cats.effect.IO
import cats.effect.Ref
import play.api.libs.json.{JsObject, JsValue, Json, Reads, Writes}
import mouse.MouseAction
import keyboard.KeyboardAction
import utils.SettingsUtils.UISettings
import utils.GameState
import utils.ProcessingUtils.{MKActions, MKTask, Step}



object InitialSetupFeature {

  def run(
           json:     JsValue,
           settings: UISettings,
           state:    GameState
         ): (GameState, List[MKTask]) = {

    val (s, maybeTask) = Steps.runFirst(json, settings, state)
    (s, maybeTask.toList)
  }

  private object Steps {

    // ordered list of steps
    val allSteps: List[Step] = List(
      SortCarcassesByDistanceAndTime,
      CheckDynamicHealing,
    )


    def runFirst(
                  json:     JsValue,
                  settings: UISettings,
                  startState:    GameState
                ): (GameState, Option[MKTask]) = {
      @annotation.tailrec
      def loop(
                remaining: List[Step],
                current:   GameState
              ): (GameState, Option[MKTask]) = remaining match {
        case Nil => (current, None)
        case step :: rest =>
          step.run(current, json, settings) match {
            case Some((newState, task)) =>
              (newState, Some(task))
            case None =>
              loop(rest, current)
          }
      }

      loop(allSteps, startState)
    }
  }

  private object SortCarcassesByDistanceAndTime extends Step {
    private val taskName = "sortCarcasses"

    def run(
             state:    GameState,
             json:     JsValue,
             settings: UISettings
           ): Option[(GameState, MKTask)] = {
      // EARLY EXIT: nothing to sort
      if (state.autoLoot.carcassToLootImmediately.isEmpty &&
        state.autoLoot.carcassToLootAfterFight.isEmpty) {
        None
      } else {
        // do your sorting as before
        val sortedImm = sortCarcass(state.autoLoot.carcassToLootImmediately, json)
        val sortedPost = sortCarcass(state.autoLoot.carcassToLootAfterFight, json)
        val updatedState = state.copy(
          autoLoot = state.autoLoot.copy(
            carcassToLootImmediately = sortedImm,
            carcassToLootAfterFight  = sortedPost
          )
        )

        // wrap in an MKTask — no actions, so use MKActions.empty
        Some(updatedState -> MKTask(taskName, MKActions.empty))
      }
    }

    private def sortCarcass(carcassList: List[(String, Long)], json: JsValue) = {
      // extract character pos
      val (cx, cy, cz) = (json \ "characterInfo").asOpt[JsObject].map { info =>
        (
          (info \ "PositionX").asOpt[Int].getOrElse(0),
          (info \ "PositionY").asOpt[Int].getOrElse(0),
          (info \ "PositionZ").asOpt[Int].getOrElse(0)
        )
      }.getOrElse((0, 0, 0))

      def extract(tile: String): (Int, Int, Int) = {
        val x = tile.substring(0, 5).toInt
        val y = tile.substring(5, 10).toInt
        val z = tile.substring(10, 12).toInt
        (x, y, z)
      }

      def dist(a: (Int, Int, Int), b: (Int, Int, Int)): Double =
        math.sqrt(
          math.pow(a._1 - b._1, 2) +
            math.pow(a._2 - b._2, 2) +
            math.pow(a._3 - b._3, 2)
        )

      carcassList.sortBy { case (tile, time) =>
        val pt = extract(tile)
        (dist(pt, (cx, cy, cz)), time)
      }
    }
  }



  private object CheckDynamicHealing extends Step {
    private val taskName = "checkDynamicHealing"

    def run(
             state:    GameState,
             json:     JsValue,
             settings: UISettings
           ): Option[(GameState, MKTask)] = {

      // 1) parse the UI-list into (name, countThreshold, dangerLevel)
      case class Cfg(name: String, count: Int, danger: Int)
      def parse(line: String): Option[Cfg] = {
        val pat = """Name:\s*([^,]+),\s*Count:\s*(\d+).*?Danger:\s*(\d+),.*""".r
        line match {
          case pat(n, c, d) => Some(Cfg(n.trim, c.toInt, d.toInt))
          case _            => None
        }
      }

      val thresholds = settings.autoTargetSettings.creatureList
        .flatMap(parse)
        .filter(_.danger >= 5)

      // EARLY EXIT: nothing to check if no high‐danger creatures configured
      if (thresholds.isEmpty) return None

      // 2) tally how many of each are in battle
      val counts: Map[String, Int] =
        (json \ "battleInfo").asOpt[JsObject]
          .map(_.value.values.toSeq)
          .getOrElse(Seq.empty)
          .flatMap(cre => (cre \ "Name").asOpt[String])
          .groupBy(identity)
          .view.mapValues(_.size)
          .toMap

      // 3) decide whether we *should* be in danger mode
      val shouldBeOn = thresholds.exists { cfg =>
        val seen = counts.getOrElse(cfg.name, 0)
        if (cfg.count == 0) seen >= 1 else seen >= cfg.count
      }

      (state.autoHeal.dangerLevelHealing, shouldBeOn) match {
        case (false, true) =>
          println("[CheckDynamicHealing] → ARM danger‐healing")
          val updatedState = state.copy(
            autoHeal = state.autoHeal.copy(dangerLevelHealing = true)
          )
          Some(updatedState -> MKTask(taskName, MKActions.empty))

        case (true, false) =>
          println("[CheckDynamicHealing] → DISARM danger‐healing")
          val updatedState = state.copy(
            autoHeal = state.autoHeal.copy(dangerLevelHealing = false)
          )
          Some(updatedState -> MKTask(taskName, MKActions.empty))

        case _ =>
          None
      }
    }
  }


}
