package processing

import cats.effect.IO
import cats.effect.Ref
import play.api.libs.json.{JsObject, JsValue}
import mouse.MouseAction
import keyboard.KeyboardAction
import utils.SettingsUtils.UISettings
import utils.GameState
import utils.ProcessingUtils.{MKActions, Step}


object InitialSetupFeature {

  def computeInitialSetup(
                           json: JsValue,
                           settingsRef: Ref[IO, UISettings],
                           stateRef: Ref[IO, GameState]
                         ): IO[(List[MouseAction], List[KeyboardAction])] = for {
    settings <- settingsRef.get
    result <- if (!settings.fishingSettings.enabled) {
      IO(println("Fishing disabled in computeFishingFeature")).as((Nil, Nil))
    } else {
      stateRef.get.flatMap { state =>
        val (newState, mouseActs, keyActs) = Steps.runFirst(json, settings, state)
        stateRef.set(newState).as((mouseActs, keyActs))
      }
    }
  } yield result

  private object Steps {
    // ordered list of steps
    val all: List[Step] = List(
      SortCarcassesByDistanceAndTime,
    )

    def runFirst(json: JsValue, settings: UISettings, state: GameState): (GameState, List[MouseAction], List[KeyboardAction]) =
      all.iterator
        .flatMap(_.run(state, json, settings))
        .map { case (s, a) => (s, a.mouse, a.keyboard) }
        .toSeq
        .headOption
        .getOrElse((state, Nil, Nil))
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
}
