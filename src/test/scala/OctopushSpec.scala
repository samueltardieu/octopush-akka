import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import net.rfc1149.octopush.{ErrorCodes, Octopush}
import net.rfc1149.octopush.Octopush._
import org.specs2.mutable._

import scala.concurrent.Await
import scala.concurrent.duration._

class OctopushSpec extends Specification with After {

  implicit val system = ActorSystem()
  implicit val dispatcher = system.dispatcher
  implicit val materializer = ActorMaterializer()

  override def after = {
    system.terminate()
  }

  "The unmarshallers" should {

    "decode the balance web site example" in {
      val response =
        <octopush>
          <balance type="FR">1251.80</balance>
          <balance type="XXX">1829.40</balance>
        </octopush>
      val balance = Await.result(balanceUnmarshaller(response), 1.second)
      balance.premiumFrance must be equalTo 1251.80
      balance.lowCostFrance must be equalTo 1829.40
    }

    "decode the credit web site example" in {
      val response =
        <octopush>
          <credit>5623.34</credit>
        </octopush>
      Await.result(creditUnmarshaller(response), 1.second) must be equalTo 5623.34
    }

    "decode the SMS sending web site example" in {
      val response =
        <octopush>
          <error_code>000</error_code>
          <cost>0.105</cost>
          <balance>6.93</balance>
          <ticket>api110000000021</ticket>
          <sending_date>1326311820</sending_date>
          <number_of_sendings>1</number_of_sendings>
          <currency_code>€</currency_code>
          <successs>
            <success>
              <recipient>+33601010101</recipient>
              <country_code>FR</country_code>
              <cost>0.550</cost>
            </success>
          </successs>
          <failures/>
        </octopush>
      val result = Await.result(smsResultUnmarshaller(response), 1.second)
      result.cost must be equalTo 0.105
      result.balance must be equalTo 6.93
      result.ticket must be equalTo "api110000000021"
      result.sendingDate must be equalTo 1326311820L
      result.numberOfSendings must be equalTo 1
      result.currencyCode must be equalTo "€"
      result.successes must have size 1
      val success = result.successes.head
      success.recipient must be equalTo "+33601010101"
      success.countryCode must be equalTo "FR"
      success.cost must be equalTo 0.55
    }

  }

  "retrieving the balance" should {

    "signal an error if the credentials are wrong" in {
      implicit val system = ActorSystem()
      val octopush = new Octopush("login@example.com", "apikey")
      Await.result(octopush.balance(), 5.seconds) should throwA[APIError]("101")
    }
  }

  "sending a SMS" should {

    "signal an error if the credentials are wrong" in {
      implicit val system = ActorSystem()
      val octopush = new Octopush("login@example.com", "apikey")
      val sms = SMS(smsRecipients = List("+33601010101"), smsText = "Hi, this is a SMS", smsType = LowCostFrance)
      val result = octopush.sms(sms)
      Await.result(result, 5.seconds) should throwA[APIError]("101")
    }
  }

  "error codes" should {

    "be lookable" in {
      ErrorCodes.errorMessage.get(101) must be equalTo Some("Mauvais identifiants")
    }

    "be retrievable from APIError exceptions" in {
      (throw APIError(101)).asInstanceOf[String] must throwA[APIError]("Mauvais identifiants")
    }
  }

}
