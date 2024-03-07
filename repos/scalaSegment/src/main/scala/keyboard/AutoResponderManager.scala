package keyboard

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
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
  val apiKey = "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"
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
    context.system.scheduler.scheduleWithFixedDelay(0.seconds, 5.seconds, self, CheckPendingResponses)
  }

  val dialogueHistory: mutable.Buffer[JsValue] = mutable.Buffer.empty

  // Holds messages that have already been responded to
  val respondedMessages: mutable.Set[String] = mutable.Set.empty
  // Queue for messages waiting to be responded to, with timestamp for delay handling
  val pendingResponses: mutable.Queue[(String, Long)] = mutable.Queue.empty


  override def receive: Receive = {
    case AutoResponderCommand(messages) =>
      messages.foreach { messageJson =>
        val text = (messageJson \ "text").as[String]
        val from = (messageJson \ "from").as[String]
        val key = s"$from:$text"

        // Add player message to dialogue history
        dialogueHistory += messageJson

        // Check if the message was already responded to
        if (!respondedMessages.contains(key) && !pendingResponses.exists(_._1 == key)) {
          // Add to pending responses with the current time
          pendingResponses.enqueue((key, System.currentTimeMillis()))
        }
      }

    case CheckPendingResponses =>
      while (pendingResponses.nonEmpty && System.currentTimeMillis() - pendingResponses.front._2 >= 5000) {
        val (key, _) = pendingResponses.dequeue()

        // Ensure a message hasn't been responded to before generating a response
        if (!respondedMessages.contains(key)) {
          respondedMessages.add(key) // Mark as responded preemptively to avoid duplication

          // Print the messages going to the HTTP request for debugging
          println("Sending the following dialogue history to GPT-3 API:")
          dialogueHistory.foreach(msg => println(msg))

          // Use the full dialogue history when generating a response
          Gpt3ApiClient.generateResponse(dialogueHistory.toSeq)
            .map(response => {
              // Add ChatGPT response to dialogue history before sending to KeyboardActor
              val responseJson = Json.obj("from" -> "ChatGPT", "text" -> response)
              dialogueHistory += responseJson
              TextResponse(key, response)
            })
            .recover { case ex => TextResponse(key, s"Error: ${ex.getMessage}") }
            .pipeTo(self)
        }
      }

    case TextResponse(key, responseText) =>
      println(s"Responding to: $key with '$responseText'")
      keyboardActorRef ! TypeText(responseText)


        // Simulate responding to the message. Later, integrate with a dynamic response.
//        println(s"Responding to: $key with 'Yes'")
//        keyboardActorRef ! TypeText("Yes")


    case _ => println("Unhandled message in AutoResponderManager")
  }
}

object AutoResponderManager {
  def props(keyboardActorRef: ActorRef): Props = Props(new AutoResponderManager(keyboardActorRef))
}
