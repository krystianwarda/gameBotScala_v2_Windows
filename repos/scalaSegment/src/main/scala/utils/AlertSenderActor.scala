package utils

import akka.actor.{Actor, ActorLogging, ActorSystem}
import play.api.libs.json.JsValue

import java.io.File

case class SendDiscordAlert(message: String, screenshot: Option[File], jsonData: Option[JsValue])

class AlertSenderActor extends Actor with ActorLogging {
  import context.dispatcher // Import the execution context for future operations

  def receive = {
    case SendDiscordAlert(message, screenshot, jsonData) =>
      log.info("Preparing to send alert to Discord...")
      DiscordNotifier.sendAlert(message, screenshot, jsonData) // Assuming DiscordNotifier is accessible
      log.info("Alert sent successfully to Discord.")
  }
}


import sttp.client3._
import sttp.model._
import java.io.{File, PrintWriter}
import scala.util.Using
import play.api.libs.json._

object DiscordNotifier {
  implicit val system: ActorSystem = ActorSystem("DiscordNotifierSystem")
  import system.dispatcher // Import execution context for future operations
  val backend = HttpURLConnectionBackend()

  def sendAlert(message: String, screenshot: Option[File], jsonData: Option[JsValue]): Unit = {
    val jsonPayload = Json.obj(
      "content" -> message,
      "embeds" -> Json.arr(Json.obj("title" -> "test"))
    ).toString()

    val textPart = multipart("payload_json", jsonPayload)

    // Handle the screenshot file part, ensure unique file name
    val screenshotPart = screenshot.map { ss =>
      multipartFile("file1", ss).fileName("screenshot.png")
    }

    // Convert JsValue to JSON string and write to a file, ensuring file closure
    val jsonFilePart = jsonData.map { json =>
      val jsonFile = File.createTempFile("report", ".txt")
      Using(new PrintWriter(jsonFile)) { writer =>
        writer.write(Json.prettyPrint(json))
        writer.flush()  // Flush to ensure all data is written
      }
      jsonFile.deleteOnExit() // Ensure the file is deleted after the application exits
      multipartFile("file2", jsonFile).fileName("report.txt")
    }

    // Combine all parts
    val bodyParts = List(textPart) ++ screenshotPart.toList ++ jsonFilePart.toList

    // Construct and send the request
    val request = basicRequest.post(uri"${Credentials.discordWebhookUrl}")
      .multipartBody(bodyParts)
      .response(asStringAlways)

    val response = request.send(backend)
  }
}
