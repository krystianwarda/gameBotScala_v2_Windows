package com.example

import com.typesafe.config.{Config, ConfigFactory}
import java.nio.file.{Paths, Files}
import java.nio.charset.StandardCharsets
import io.circe.parser._
import io.circe.Json

object KafkaConfigLoader {

  def loadMergedConfig(): Config = {
    val kafkaSecretPath = "C:/Projects/gameBotScala_v2_Windows/repos/terraformSegment/kafka-key.json"

    val jsonStr = new String(Files.readAllBytes(Paths.get(kafkaSecretPath)), StandardCharsets.UTF_8)
    val parsedJson = parse(jsonStr).getOrElse(Json.Null)

    val apiKey = parsedJson.hcursor.get[String]("api_key").getOrElse("")
    val apiSecret = parsedJson.hcursor.get[String]("api_secret").getOrElse("")

    // Inject into environment for Typesafe config
    val overrides = ConfigFactory.parseString(
      s"""
         |KAFKA_API_KEY = "$apiKey"
         |KAFKA_API_SECRET = "$apiSecret"
         |""".stripMargin
    )

    // Load default application.conf and override
    overrides.withFallback(ConfigFactory.load()).resolve()
  }
}
