package keyboard

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import main.scala.MainApp.actionKeyboardManagerRef
import play.api.libs.json.{JsValue, Json}

import scala.collection.mutable
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._

case class AnalyzeMessage(json: JsValue)
case object CheckPendingResponses
case class AutoResponderCommand(messages: Seq[JsValue])
case class TextResponse(key: String, responseText: String)

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


  def generateResponse(messages: Seq[JsValue]): Future[String] = {
    val systemMessage = Json.obj(
      "role" -> "system",
      "content" -> "You are a player in a computer game, training your skills by hitting a monk creature on your computer for long hours. You haven't seen and aren't supposed to see anybody. Suddenly, someone is talking to you. Keep your answers very brief, do not make sentences, and do not use capital letters, but try to use up to 3-4 words. Try to answer politely if someone asks you how you are doing. If someone will ask you about your skill say 91/90."
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



class AutoResponderManager(keyboardActorRef: ActorRef) extends Actor {
//  import context.dispatcher
  import ApplicationSetup._
  override def preStart(): Unit = {
    // Initialize the scheduler in preStart to ensure it starts with the actor.
    context.system.scheduler.scheduleWithFixedDelay(1.second, 3.seconds, self, CheckPendingResponses)

  }


  val dialogueHistory: mutable.Buffer[JsValue] = mutable.Buffer.empty
  val respondedMessages: mutable.Set[String] = mutable.Set.empty
  val pendingResponses: mutable.Queue[(String, Long)] = mutable.Queue.empty

  override def receive: Receive = {
    case AutoResponderCommand(messages) =>
      println(s"Received ${messages.size} messages to process in AutoResponder.")
      messages.foreach { messageJson =>
        val text = (messageJson \ "text").as[String]
        val from = (messageJson \ "from").as[String]
        val key = s"$from:$text"
        println(s"Processing message from $from with text $text. Key: $key")

        if (!respondedMessages.contains(key) && !pendingResponses.exists(_._1 == key)) {
          pendingResponses.enqueue((key, System.currentTimeMillis()))
//          respondedMessages.add(key) // Optionally mark as responded immediately upon queuing, if appropriate
          println(s"Enqueued message: $key")
        } else {
          println(s"Message already processed or in queue: $key")
        }

      }


    case CheckPendingResponses =>
      println(s"Checking pending responses, queue size: ${pendingResponses.size}")
      val currentTime = System.currentTimeMillis()
      pendingResponses.dequeueAll { case (key, time) =>
        if (currentTime - time >= 5000) {
          println(s"Processing queued message: $key")
          println(s"respondedMessages: $respondedMessages")
          if (!respondedMessages.contains(key)) {
            println(s"Attempting to generate response for $key")
            Gpt3ApiClient.generateResponse(dialogueHistory.toSeq)
              .map { response =>
                val responseJson = Json.obj("from" -> "ChatGPT", "text" -> response)
                dialogueHistory += responseJson
                respondedMessages.add(key) // Mark as responded only after successful response generation
                println(s"Response generated for $key: $response")
                TextResponse(key, response)
              }
              .recover { case ex =>
                println(s"Failed to generate response for $key: ${ex.getMessage}")
                TextResponse(key, s"Error: ${ex.getMessage}")
              }
              .pipeTo(self)
          } else {
            println(s"Skipping processing for already responded message: $key")
          }
          true // Confirm removal from the queue
        } else {
          false // Keep in the queue if not yet ready
        }
      }

    case TextResponse(key, responseText) =>
      println(s"Sending response to keyboard for $key: $responseText")
      actionKeyboardManagerRef ! TypeText(responseText)  // Assuming actionKeyboardManagerRef is a reference to ActionKeyboardManager


//    case TextResponse(key, responseText) =>
//      println(s"Sending response to keyboard for $key: $responseText")
//      keyboardActorRef ! TypeText(responseText)



    case _ => println("Unhandled message type received in AutoResponderManager.")
  }
}

object AutoResponderManager {
  def props(keyboardActorRef: ActorRef): Props = Props(new AutoResponderManager(keyboardActorRef))
}
