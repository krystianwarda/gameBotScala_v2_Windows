package utils


import javax.mail._
import javax.mail.internet._
import java.util.Properties

object EmailUtils {
  def sendEmail(from: String, password: String, to: String, subject: String, body: String): Boolean = {
    try {
      val props = new Properties()
      props.put("mail.smtp.host", "smtp.gmail.com") // for Gmail, use smtp.gmail.com
      props.put("mail.smtp.port", "587")
      props.put("mail.smtp.auth", "true")
      props.put("mail.smtp.starttls.enable", "true") // enable STARTTLS

      // Authenticator to log in to your email account
      val session = Session.getInstance(props, new Authenticator {
        override protected def getPasswordAuthentication: PasswordAuthentication = {
          new PasswordAuthentication(from, password)
        }
      })

      // Create a new email message
      val message = new MimeMessage(session)
      message.setFrom(new InternetAddress(from))
      message.addRecipient(Message.RecipientType.TO, new InternetAddress(to))
      message.setSubject(subject)
      message.setText(body)

      // Send the email
      Transport.send(message)
      true // Email was sent successfully
    } catch {
      case e: MessagingException =>
        e.printStackTrace()
        false // Email failed to send
    }
  }
}

