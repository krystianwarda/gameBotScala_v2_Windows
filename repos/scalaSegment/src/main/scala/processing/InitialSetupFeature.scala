package processing

import cats.effect.IO
import cats.effect.Ref
import play.api.libs.json.{JsObject, JsValue, Json, Reads, Writes}
import mouse.MouseAction
import keyboard.KeyboardAction

import utils.SettingsUtils.UISettings
import utils.GameState
import utils.ProcessingUtils.{MKActions, Step}



object InitialSetupFeature {

  def run(json: JsValue, settings: UISettings, state: GameState):
  (GameState, List[MouseAction], List[KeyboardAction]) =
    Steps.runFirst(json, settings, state)


  private object Steps {
    // ordered list of steps
    val all: List[Step] = List(
      SortCarcassesByDistanceAndTime,
      CheckDynamicHealing,
    )


    def runFirst(json: JsValue, settings: UISettings, start: GameState)
    : (GameState, List[MouseAction], List[KeyboardAction]) = {

      // We'll carry along (currentState, maybeActions)
      // as we fold through the steps.
      @annotation.tailrec
      def loop(remaining: List[Step],
               currentState: GameState,
               carriedActions: MKActions): (GameState, MKActions) = remaining match {

        // 1) If we've already gotten actions, stop immediately.
        case _ if carriedActions.mouse.nonEmpty || carriedActions.keyboard.nonEmpty =>
          (currentState, carriedActions)

        // 2) No more steps? return what we have so far.
        case Nil =>
          (currentState, MKActions(Nil, Nil))

        // 3) Try the next step
        case step :: rest =>
          step.run(currentState, json, settings) match {
            // a) Step wants to update state *and* emits actions:
            case Some((newState, actions)) if actions.mouse.nonEmpty || actions.keyboard.nonEmpty =>
              // we break and return immediately
              (newState, actions)

            // b) Step updates state but has no actions: carry on
            case Some((newState, MKActions(Nil, Nil))) =>
              loop(rest, newState, MKActions(Nil, Nil))

            // c) Step does nothing: carry on with same state
            case None =>
              loop(rest, currentState, MKActions(Nil, Nil))
          }
      }

      // run the loop
      val (finalState, MKActions(m,k)) = loop(all, start, MKActions(Nil, Nil))
      (finalState, m, k)
    }
  }

  private object SortCarcassesByDistanceAndTime extends Step {
    override def run(state: GameState, json: JsValue, settings: UISettings) = {
      val sortedImm = sortCarcass(state.autoLoot.carcassToLootImmediately, json)
      val sortedPost = sortCarcass(state.autoLoot.carcassToLootAfterFight, json)

      val updated = state.copy(autoLoot = state.autoLoot.copy(
        carcassToLootImmediately  = sortedImm,
        carcassToLootAfterFight   = sortedPost
      ))
      Some((updated, MKActions(Nil, Nil)))
    }

    private def sortCarcass(carcassList: List[(String, Long)], json: JsValue) = {
      // extract character pos
      val (cx, cy, cz) = (json \ "characterInfo").asOpt[JsObject].map { info =>
        ((info \ "PositionX").asOpt[Int].getOrElse(0),
          (info \ "PositionY").asOpt[Int].getOrElse(0),
          (info \ "PositionZ").asOpt[Int].getOrElse(0))
      }.getOrElse((0,0,0))

      def extract(tile: String): (Int,Int,Int) = {
        val x = tile.substring(0,5).toInt
        val y = tile.substring(5,10).toInt
        val z = tile.substring(10,12).toInt
        (x,y,z)
      }
      def dist(a:(Int,Int,Int), b:(Int,Int,Int)): Double =
        math.sqrt(math.pow(a._1-b._1,2)+math.pow(a._2-b._2,2)+math.pow(a._3-b._3,2))

      carcassList.sortBy { case (tile, time) =>
        val pt = extract(tile)
        (dist(pt,(cx,cy,cz)), time)
      }
    }
  }

  private object CheckDynamicHealing extends Step {
    def run(
             state:    GameState,
             json:     JsValue,
             settings: UISettings
           ): Option[(GameState, MKActions)] = {

      // 1) parse the UI-list into (name, countThreshold, dangerLevel)
      case class Cfg(name: String, count: Int, danger: Int)
      def parse(line: String): Option[Cfg] = {
        val pat = """Name:\s*([^,]+),\s*Count:\s*(\d+).*?Danger:\s*(\d+),.*""".r
        line match {
          case pat(n, c, d) => Some(Cfg(n.trim, c.toInt, d.toInt))
          case _            => None
        }
      }

      val thresholds = settings.autoTargetSettings.creatureList.flatMap(parse)
        .filter(_.danger >= 5)

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
          val s2 = state.copy(autoHeal = state.autoHeal.copy(dangerLevelHealing = true))
          Some((s2, MKActions(Nil, Nil)))

        case (true, false) =>
          println("[CheckDynamicHealing] → DISARM danger‐healing")
          val s2 = state.copy(autoHeal = state.autoHeal.copy(dangerLevelHealing = false))
          Some((s2, MKActions(Nil, Nil)))

        case _ =>
          None
      }
    }
  }


}
