package utils

import akka.actor.{Actor, ActorLogging, ActorSystem}
import play.api.libs.json.JsValue
import processing.Process.captureScreen

import java.io.File

case class SendDiscordAlert(message: String, jsonData: Option[JsValue])

// New case for Sound Alert
case class SendSoundAlert(message: String)

class AlertSenderActor extends Actor with ActorLogging {
  import context.dispatcher // Import the execution context for future operations

  def receive = {


    case SendDiscordAlert(message, jsonData) =>
      log.info("Preparing to send alert to Discord...")
      val screenshot = captureScreen()
      DiscordNotifier.sendAlert(message, Option(screenshot), jsonData) // Assuming DiscordNotifier is accessible
      log.info("Alert sent successfully to Discord.")

    // New sound alert case
    case SendSoundAlert(message) =>
      log.info("Generating sound alert...")
      SoundNotifier.generateNoise(message)
      log.info("Sound alert triggered successfully.")
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


object SoundNotifier {
  import com.sun.speech.freetts.{Voice, VoiceManager}
  System.setProperty("freetts.voices", "com.sun.speech.freetts.en.us.cmu_us_kal.KevinVoiceDirectory")
  def generateNoise(message: String): Unit = {
    println("generateNoise activated.")

    // Specify the voice name (ensure it's a valid one in your TTS library)
    val voiceName = "kevin" // Adjust this based on available voices

    // Get an instance of the VoiceManager and the specified voice
    val voiceManager = VoiceManager.getInstance()
    val voice = voiceManager.getVoice(voiceName)

    if (voice != null) {
      voice.allocate() // Allocate resources for the voice
      try {
        voice.speak(message) // Speak the message
      } finally {
        voice.deallocate() // Deallocate resources after speaking
      }
    } else {
      println("Voice not found.")
    }
  }
}

//object SoundNotifier {
//  import marytts.LocalMaryInterface
//  import marytts.util.data.audio.AudioPlayer
//
//  def generateNoise(message: String): Unit = {
//    println("generateNoise activated.")
//
//    // Create a MaryTTS instance
//    val maryTTS = new LocalMaryInterface()
//
//    // Optionally, set the voice (you can list available voices and choose one)
//    val voiceName = "cmu-slt-hsmm" // Example voice name, ensure this is valid
//    maryTTS.setVoice(voiceName)
//
//    // Generate speech and play it
//    try {
//      val audio = maryTTS.generateAudio(message)
//      val player = new AudioPlayer(audio)
//      player.start()
//      player.join() // Wait for the audio to finish playing
//    } catch {
//      case e: Exception =>
//        println(s"Error generating or playing audio: ${e.getMessage}")
//        e.printStackTrace()
//    }
//  }
//
//  def main(args: Array[String]): Unit = {
//    generateNoise("Hello, this is a test message.")
//  }
//}
