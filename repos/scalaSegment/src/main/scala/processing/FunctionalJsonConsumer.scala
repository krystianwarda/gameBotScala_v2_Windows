package processing

//import cats.syntax.all._
import cats.effect.{IO, Ref}
import keyboard.{KeyboardAction, KeyboardActionManager}
import mouse._
import utils.SettingsUtils.UISettings
import mouse.MouseActionManager
import utils.{GameState, JsonProcessingState}
import cats.effect.IO
import play.api.libs.json.JsValue
import cats.syntax.all._
import utils.ProcessingUtils.MKTask
import cats.syntax.traverse._
import cats.syntax.applicative._
import utils.consoleColorPrint.{ANSI_RED, printInColor}  // for .pure/Applicative if you ever need it

// 2) FunctionalJsonConsumer.scala
class FunctionalJsonConsumer(
                              stateRef:       Ref[IO, GameState],
                              settingsRef:    Ref[IO, UISettings],
                              mouseManager:   MouseActionManager,
                              keyboardManager: KeyboardActionManager
                            ) {

  def runPipeline(json: JsValue): IO[(GameState, List[MKTask])] = {
    // 0) Drop the initial “{"__status":"ok"}” JSON immediately
    (json \ "__status").asOpt[String] match {
      case Some("ok") =>
        // just return the current state and no tasks
        stateRef.get.map(state => (state, Nil))

      case _ =>
        // 1) normal processing
        for {
          
          startTime <- IO(System.nanoTime())
          _ <- IO(printInColor(ANSI_RED, "[PIPELINE] Starting JSON processing"))


          settings <- settingsRef.get
          startState   <- stateRef.get

          (state0, generalTasks)   = InitialSetupFeature.run(json, settings, startState)
          (state1, fishTasks)   = FishingFeature.run(json, settings, state0)
          (state2, healTasks)   = HealingFeature.run(json, settings, state1)
          (state3, targetTasks) = AutoTargetFeature.run(json, settings, state2)
          (state4, lootTasks)   = AutoLootFeature.run(json, settings, state3)
          (state5, caveTasks)   = CaveBotFeature.run(json, settings, state4)
          _ <- stateRef.set(state5)

          endTime <- IO(System.nanoTime())
          duration = (endTime - startTime) / 1e9d
          _ <- IO(printInColor(ANSI_RED, f"[PIPELINE] Finished JSON processing in $duration%.3f seconds"))


        } yield (state5, generalTasks ++ fishTasks ++ healTasks ++ lootTasks ++ targetTasks ++ caveTasks)
    }
  }

  def process(json: JsValue): IO[Unit] = {
    for {
      currentState <- stateRef.get
      currentTime = System.currentTimeMillis()

      // Check if already processing (with timeout protection)
      canProcess = !currentState.jsonProcessing.isProcessing ||
        (currentTime - currentState.jsonProcessing.processingStartTime) > currentState.jsonProcessing.processingTimeout

      result <- if (canProcess) {
        processJson(json, currentTime)
      } else {
        IO.println("⏳ JSON processing already in progress, skipping...")
      }
    } yield result
  }

  private def processJson(json: JsValue, startTime: Long): IO[Unit] = {
    for {
      // Set processing flag
      _ <- stateRef.update(_.copy(jsonProcessing = JsonProcessingState(
        isProcessing = true,
        processingStartTime = startTime
      )))

      // Run pipeline and destructure the result
      pipelineResult <- runPipeline(json)
      (finalState, tasks) = pipelineResult

      // Clear processing flag and update state
      finalStateWithClearedFlag = finalState.copy(jsonProcessing = JsonProcessingState(isProcessing = false))
      _ <- stateRef.set(finalStateWithClearedFlag)

      // Execute tasks
      _ <- executeTasks(tasks)
    } yield ()
  }

  private def executeTasks(tasks: List[MKTask]): IO[Unit] = {
    tasks
      .filter(task => task.actions.mouse.nonEmpty || task.actions.keyboard.nonEmpty)
      .traverse_ { task =>
        for {
          _ <- IO.println(s"🛠️ Running task: ${task.taskName}")
          _ <- if (task.actions.mouse.nonEmpty)
            mouseManager.enqueueBatches(List(task.taskName -> task.actions.mouse))
          else IO.unit
          _ <- if (task.actions.keyboard.nonEmpty)
            keyboardManager.enqueueBatches(List(task.taskName -> task.actions.keyboard))
          else IO.unit
        } yield ()
      }
  }
//  def process(json: JsValue): IO[Unit] =
//    runPipeline(json).flatMap { case (_, tasks) =>
//      tasks
//        // Skip tasks with no mouse and no keyboard actions
//        .filter(task => task.actions.mouse.nonEmpty || task.actions.keyboard.nonEmpty)
//        .traverse_ { task =>
//          for {
//            _ <- IO.println(s"🛠️ Running task: ${task.taskName}")
//            // Enqueue only non-empty action batches
//            _ <- if (task.actions.mouse.nonEmpty)
//              mouseManager.enqueueBatches(List(task.taskName -> task.actions.mouse))
//            else IO.unit
//            _ <- if (task.actions.keyboard.nonEmpty)
//              keyboardManager.enqueueBatches(List(task.taskName -> task.actions.keyboard))
//            else IO.unit
//          } yield ()
//        }
//    }
}


//  def executeKeyboardActions(actions: List[KeyboardAction]): IO[Unit] =
//    keyboardManager.enqueueTask(actions)
//
//  def executeMouseActions(actions: List[MouseAction]): IO[Unit] =
//    mouseManager.enqueueTask(actions)