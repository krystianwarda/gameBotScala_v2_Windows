//package utils
//
//import sttp.client._
//import play.api.libs.json._
//import javax.mail.internet._
//import scala.util.{Try, Success, Failure}
//
//object EmailUtils {
//  // Configuration details for OAuth
//  private val clientId = "your-client-id"
//  private val clientSecret = "your-client-secret"
//  private val tokenUrl = "https://api.login.yahoo.com/oauth2/get_token"
//  private val redirectUri = "http://localhost"
//
//  // Function to obtain an access token
//  private def obtainAccessToken(): String = {
//    val requestBody = Map(
//      "grant_type" -> "client_credentials",
//      "client_id" -> clientId,
//      "client_secret" -> clientSecret,
//      "redirect_uri" -> redirectUri
//    )
//
//    implicit val backend = HttpURLConnectionBackend()
//    val request = basicRequest
//      .body(requestBody)
//      .post(uri"$tokenUrl")
//      .contentType("application/x-www-form-urlencoded")
//      .auth.basic(clientId, clientSecret)
//
//    val response = request.send()
//    response.body match {
//      case Right(body) => Json.parse(body) \ "access_token" as[String]
//      case Left(error) => throw new RuntimeException(s"Failed to obtain access token: $error")
//    }
//  }
//
//  // Generalized function to send an email
//  def sendEmail(from: String, to: String, subject: String, body: String): Boolean = {
//    val accessToken = obtainAccessToken()  // Handle token internally
//    val mailJson = Json.obj(
//      "from" -> from,
//      "to" -> List(to),
//      "subject" -> subject,
//      "textPart" -> body
//    )
//
//    val uri = uri"https://api.mail.yahoo.com/some/email/send/endpoint" // Actual Mail API endpoint
//    implicit val backend = HttpURLConnectionBackend()
//    val request = basicRequest
//      .body(mailJson.toString())
//      .post(uri)
//      .contentType("application/json")
//      .header("Authorization", s"Bearer $accessToken")
//
//    val response = request.send()
//    response.body match {
//      case Right(_) =>
//        println("Email sent successfully")
//        true
//      case Left(error) =>
//        println(s"Failed to send email: $error")
//        false
//    }
//  }
//}
