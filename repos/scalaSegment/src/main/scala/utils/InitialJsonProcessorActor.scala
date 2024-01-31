package utils

import akka.actor.Actor
import akka.actor.Actor.Receive
import play.api.libs.json.JsValue
case class JsonData(json: JsValue)

class InitialJsonProcessorActor extends Actor {
  override def receive: Receive = {
    case jsonData: JsonData =>
      println(s"InitialJsonProcessorActor received JSON: ${jsonData.json}")

    case other =>
      println(s"InitialJsonProcessorActor received an unrecognized message of type ${other.getClass.getName}: $other")
  }
}




