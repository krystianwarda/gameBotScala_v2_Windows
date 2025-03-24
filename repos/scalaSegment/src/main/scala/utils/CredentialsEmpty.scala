package utils

object CredentialsEmpty {

  val discordWebhookUrl: String = ""

  object gptCredentials {
    def apiKey(): String = {
      ""
    }
  }

}
