package net.rfc1149.octopush

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.client.RequestBuilding.Post
import akka.http.scaladsl.marshallers.xml.ScalaXmlSupport
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.Accept
import akka.http.scaladsl.unmarshalling.{Unmarshal, Unmarshaller}
import akka.http.scaladsl.util.FastFuture
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}

import scala.concurrent.Future
import scala.util.{Failure, Success}
import scala.xml.NodeSeq

class Octopush(userLogin: String, apiKey: String)(implicit system: ActorSystem) extends ScalaXmlSupport {

  import Octopush._

  private[this] implicit val materializer = ActorMaterializer()
  private[this] implicit val executionContext = system.dispatcher
  private[this] implicit val log = system.log
  private[this] val apiPool = Http().newHostConnectionPoolHttps[NotUsed]("www.octopush-dm.com")

  private[this] def apiRequest[T](path: String, fields: (String, String)*)(implicit ev: Unmarshaller[NodeSeq, T]): Future[T] = {
    val formData = FormData(Seq("user_login" -> userLogin, "api_key" -> apiKey) ++ fields: _*)
    val request = Post(s"/api/$path", formData).addHeader(Accept(MediaTypes.`application/xml`))
    log.debug(s"Posting $request")
    Source.single((request, NotUsed)).via(apiPool).runWith(Sink.head).map(_._1).flatMap {
      case Success(response) if response.status.isSuccess() =>
        Unmarshal(response.entity).to[NodeSeq].flatMap {
          x =>
            log.debug(s"Received succesful answer with payload: $x")
            // Errors are delivered as 200 OK with a payload containing a non-zero error_code field
            (x \ "error_code").headOption.map(_.text.toInt).filterNot(_ == 0).fold(Unmarshal(x).to[T])(e => FastFuture.failed(APIError(e)))
        }
      case Success(response) =>
        log.debug("Received unsuccessful answer: $response.status")
        FastFuture.failed(new StatusError(response.status))
      case Failure(t) =>
        log.error(t, "Connexion failure")
        FastFuture.failed(t)
    }
  }

  def balance(): Future[Balance] = apiRequest("balance")(balanceUnmarshaller)

  def credit(): Future[Double] = apiRequest("credit")(creditUnmarshaller)

  def sms(smsRecipients: List[String], smsText: String, smsType: SmsType, transactional: Boolean = false): Future[NodeSeq] = {
    apiRequest[NodeSeq]("sms",
      "sms_recipients" -> smsRecipients.mkString(","), "sms_text" -> smsText, "sms_type" -> smsType.toString,
      "transactional" -> (if (transactional) "1" else "0"))
  }

}

object Octopush {

  class StatusError(val status: StatusCode) extends Exception {
    override def getMessage = s"${status.intValue} ${status.reason}"
  }

  object StatusError {
    def unapply(statusError: StatusError): Option[Int] = Some(statusError.status.intValue())
  }

  case class APIError(errorCode: Int) extends Exception {
    override def getMessage = s"$errorCode ${ErrorCodes.errorMessage(errorCode)}"
  }

  case class Balance(lowCostFrance: Double, premiumFrance: Double)

  case class SMSSuccess(recipient: String, countryCode: String, cost: Double)

  // XXX Find definition and include failures
  case class SMSResult(cost: Double, balance: Double,
                       ticket: String, sendingDate: Long, numberOfSendings: Int,
                       currencyCode: String, successes: Seq[SMSSuccess])

  val balanceUnmarshaller: Unmarshaller[NodeSeq, Balance] = Unmarshaller.strict { xml =>
    Balance(lowCostFrance = (xml \ "balance" filter (_ \@ "type" == "XXX")).text.toDouble,
      premiumFrance = (xml \ "balance" filter (_ \@ "type" == "FR")).text.toDouble)
  }

  val creditUnmarshaller: Unmarshaller[NodeSeq, Double] = Unmarshaller.strict { xml =>
    (xml \ "credit").text.toDouble
  }

  val smsResultUnmarshaller: Unmarshaller[NodeSeq, SMSResult] = Unmarshaller.strict { xml =>
    SMSResult(cost = (xml \ "cost").text.toDouble,
      balance = (xml \ "balance").text.toDouble,
      ticket = (xml \ "ticket").text,
      sendingDate = (xml \ "sending_date").text.toLong,
      numberOfSendings = (xml \ "number_of_sendings").text.toInt,
      currencyCode = (xml \ "currency_code").text,
      successes = (xml \ "successs" \ "success").map { success =>
        SMSSuccess(recipient = (success \ "recipient").text,
          countryCode = (success \ "country_code").text,
          cost = (success \ "cost").text.toDouble)
      })
  }

  sealed trait SmsType
  case object LowCostFrance extends SmsType {
    override val toString = "XXX"
  }
  case object PremiumFrance extends SmsType {
    override val toString = "FR"
  }
  case object WWW extends SmsType {
    override val toString = "WWW"
  }

}
