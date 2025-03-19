package keyboard

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import keyboard.Gpt3ApiClient.{alertStory, storiesMap}
import main.scala.MainApp.{actionKeyboardManagerRef, jsonProcessorActorRef}
import play.api.libs.json.{JsValue, Json}
import userUI.SettingsUtils
import userUI.SettingsUtils.UISettings
import userUI.UpdateSettings
import processing.ResponseGenerated
import utils.Credentials.gptCredentials

import scala.collection.mutable
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._
import scala.io.Source

case class AnalyzeMessage(json: JsValue)
case object CheckPendingResponses
case class AutoResponderCommand(messages: Seq[JsValue])
case class TextResponse(key: String, responseText: String)
case class UpdateAlertStory(alertType: String)
case object CancelAlert
case class RequestAnswer(dialogueHistory: Seq[(JsValue, String)], pendingMessages: mutable.Queue[(JsValue, Long)], settings: UISettings)
import processing.Process.loadTextFromFile

import akka.pattern.pipe

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.Authorization
import akka.http.scaladsl.model.headers.GenericHttpCredentials
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.Materializer
import play.api.libs.json._
import scala.concurrent.{Future}

object ApplicationSetup {
  // Move these implicits inside an object or class
  implicit val system: ActorSystem = ActorSystem("Gpt3System")
  implicit val materializer: Materializer = Materializer(system)
  implicit val executionContext: ExecutionContext = system.dispatcher
}
//import ApplicationSetup._
object Gpt3ApiClient {

  import ApplicationSetup._ // Import execution context like this

  //  import context.dispatcher
  val apiKey = gptCredentials.apiKey
  val endpoint = "https://api.openai.com/v1/chat/completions"

  val storiesMap = Map(
    "training" -> loadTextFromFile("src/main/scala/extraFiles/trainingStory.txt"),
    "solo hunt" -> loadTextFromFile("src/main/scala/extraFiles/soloHuntStory.txt"),
    "team hunt" -> loadTextFromFile("src/main/scala/extraFiles/teamHuntStory.txt"),
    "fishing" -> loadTextFromFile("src/main/scala/extraFiles/fishingStory.txt"),
    "rune making" -> loadTextFromFile("src/main/scala/extraFiles/runeMakingStory.txt"),
    "gm alert" -> loadTextFromFile("src/main/scala/extraFiles/gmAlertStory.txt"),
    "pk alert" -> loadTextFromFile("src/main/scala/extraFiles/pkAlertStory.txt"),
  )
  val initialStory = loadTextFromFile("src/main/scala/extraFiles/initialStory.txt")
  var alertStory = ""


  def generateResponse(dialogueHistory: Seq[(JsValue, String)], messages: Seq[JsValue], settings: UISettings): Future[String] = {
    // Determine the main story based on settings, if enabled
    val mainStory = if (settings.autoResponderSettings.enabled) {
      storiesMap.getOrElse(settings.autoResponderSettings.selectedStory, "")
    } else {
      "" // No main story if auto responder is disabled
    }

    // Additional story from settings
    val additionalStory = if (settings.autoResponderSettings.enabled) {
      settings.autoResponderSettings.additionalStory
    } else {
      "" // No additional story if auto responder is disabled
    }

    // Determine if 'Game master alert' is contained in any of the messages
    alertStory = messages.exists { message =>
      (message \ "text").asOpt[String].exists(_.contains("Game master alert."))
    } match {
      case true => storiesMap("gm alert")
      case false => ""
    }
    println(s"generateResponse  -> alertStory: $alertStory")

    // Combine the stories
    val combinedStory = s"$initialStory $mainStory $additionalStory $alertStory (Dialogue history: $dialogueHistory)"

    val systemMessage = Json.obj(
      "role" -> "system",
      "content" -> combinedStory
    )

    val chatMessages = systemMessage +: messages.map(msg => Json.obj(
      "role" -> "user",
      "content" -> (msg \ "text").as[String]
    ))

    val requestBody = Json.obj(
      "model" -> "gpt-3.5-turbo",
      "messages" -> chatMessages,
      "temperature" -> 0.7,
      "max_tokens" -> 50,
      "stop" -> Json.arr("\n", " ready")
    )

    val entity = HttpEntity(ContentTypes.`application/json`, requestBody.toString())

    val request = HttpRequest(
      method = HttpMethods.POST,
      uri = Uri(endpoint),
      headers = List(Authorization(GenericHttpCredentials("Bearer", apiKey))),
      entity = entity
    )

    for {
      response <- Http().singleRequest(request)
      //      _ = println(s"Response: $response")
      entity <- response.status match {
        case StatusCodes.OK => Unmarshal(response.entity).to[String]
        case statusCode =>
          val error = Unmarshal(response.entity).to[String].recover { case _ => "unknown error" }
          println(s"API returned a non-OK status code: $statusCode, entity: ${Await.result(error, 10.seconds)}")
          Future.failed(new RuntimeException(s"Unexpected response status: ${response.status}"))
      }
      json = Json.parse(entity)
      text = (json \ "choices")(0) \ "message" \ "content"
    } yield text.as[String]

  }
}

class AutoResponderManager(keyboardActorRef: ActorRef, jsonProcessorActorRef: ActorRef) extends Actor {
  //  import context.dispatcher
  import ApplicationSetup._
  var settings: Option[UISettings] = None  // Start with None and update when settings are received


  override def receive: Receive = {

    case RequestAnswer(history, pending, settings) =>
      val messages = pending.map(_._1).toSeq
      println("Message requested in AutoResponderManager.")
      Gpt3ApiClient.generateResponse(history, messages, settings).map { response =>
        jsonProcessorActorRef ! ResponseGenerated(history, response, pending)
      }

    case _ => println("Unhandled message type received in AutoResponderManager.")

  }


}

object AutoResponderManager {
  def props(keyboardActorRef: ActorRef, jsonProcessorActorRef: ActorRef): Props =
    Props(new AutoResponderManager(keyboardActorRef, jsonProcessorActorRef))
}