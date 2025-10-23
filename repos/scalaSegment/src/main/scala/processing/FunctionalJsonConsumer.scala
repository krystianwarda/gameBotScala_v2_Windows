package processing

//import cats.syntax.all._
import cats.effect.{IO, Ref}
import keyboard.{KeyboardAction, KeyboardActionManager}
import mouse._
import utils.SettingsUtils.UISettings
import mouse.MouseActionManager
import utils.{GameState, JsonProcessingState, KafkaConfigLoader, KafkaJsonPublisher}
import cats.effect.IO
import play.api.libs.json.{JsArray, JsObject, JsValue}
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
                              keyboardManager: KeyboardActionManager,
                              kafkaPublisher: KafkaJsonPublisher  // Add this parameter
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
//          metaGeneratedId = (json \ "metaGeneratedId").asOpt[String].getOrElse("null")

          settings <- settingsRef.get
          startState <- stateRef.get




          (state0, generalTasks) = GeneralFeature.run(json, settings, startState)
          (autoHealState, autoHealTasks) = AutoHealFeature.run(json, settings, state0)
          (state2, fishTasks) = FishingFeature.run(json, settings, autoHealState)
          (state3, targetTasks) = AutoTargetFeature.run(json, settings, state2)
          (state4, lootTasks) = AutoLootFeature.run(json, settings, state3)
          (state5, caveBotTasks) = CaveBotFeature.run(json, settings, state4)
          (state6, teamHuntTasks) = TeamHuntFeature.run(json, settings, state5)
          _ <- stateRef.set(state6)

          endTime <- IO(System.nanoTime())
          duration = (endTime - startTime) / 1e9d
          _ <- IO(printInColor(ANSI_RED, f"[PIPELINE] Finished JSON processing in $duration%.3f seconds"))


        } yield (state6, generalTasks ++ autoHealTasks ++ fishTasks ++ lootTasks ++ targetTasks ++ caveBotTasks ++ teamHuntTasks)
    }
  }

  def process(json: JsValue): IO[Unit] = {
    for {
      currentState <- stateRef.get
      settings <- settingsRef.get
      currentTime = System.currentTimeMillis()

      // Check if already processing (with timeout protection)
      canProcess = !currentState.jsonProcessing.isProcessing ||
        (currentTime - currentState.jsonProcessing.processingStartTime) > currentState.jsonProcessing.processingTimeout

      result <- if (canProcess) {

        val isInitialStatusOk = (json \ "__status").asOpt[String].contains("ok")

        if (settings.debugMode && !isInitialStatusOk) {
          println(s"[DEBUG] Top-level keys: ${json.as[play.api.libs.json.JsObject].keys.mkString(", ")}")
          println(s"JSON: ${json}")
        }

        if (settings.shareDataMode && !isInitialStatusOk) {
          val cleanedJson = cleanJson(json)
          val timestamp = java.time.Instant.now().toString
          val jsonWithTimestamp = cleanedJson.as[JsObject] + ("metaProcessedTimestamp" -> play.api.libs.json.Json.toJson(timestamp))
          kafkaPublisher.sendGameData(jsonWithTimestamp)
        }

        processJson(json, currentTime)
      } else {
        IO.println("⏳ JSON processing already in progress, skipping...")
      }
    } yield result
  }


//  private lazy val kafkaPublisher = {
//    System.setProperty("org.apache.kafka.clients.CommonClientConfigs", "DEBUG")
//    println(s"[DEBUG] Java version: ${System.getProperty("java.version")}")
//    //    println(s"[DEBUG] Kafka client version: ${org.apache.kafka.clients.producer.KafkaProducer.version}")
//    val kafkaConfig = KafkaConfigLoader.loadFromFile("C:\\kafka-creds.json")
//    println(s"[DEBUG] KafkaConfig loaded: $kafkaConfig")
//
//    try {
//      new KafkaJsonPublisher(
//        bootstrapServers = kafkaConfig.bootstrap_servers,
//        gameDataTopic = kafkaConfig.topic,
//        actionTopic = kafkaConfig.actionTopic,
//        username = kafkaConfig.kafka_api_key,
//        password = kafkaConfig.kafka_api_secret
//      )
//    } catch {
//      case e: Exception =>
//        println(s"[ERROR] Failed to create KafkaJsonPublisher: ${e.getMessage}")
//        throw e
//    }
//  }

  def cleanJson(js: JsValue): JsValue = js match {
    case JsObject(fields) =>
      val cleanedFields = fields.flatMap {
        case (k, v) =>
          cleanJson(v) match {
            case JsObject(obj) if obj.isEmpty => None // remove empty objects
            case cleaned => Some(k -> cleaned)
          }
      }
      JsObject(cleanedFields)

    case JsArray(values) =>
      JsArray(values.map(cleanJson))

    case other => other
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


}
