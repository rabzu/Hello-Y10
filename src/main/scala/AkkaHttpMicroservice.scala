import java.io.IOException

import akka.actor.ActorSystem
import akka.event.{Logging, LoggingAdapter}
import akka.http.scaladsl.Http
import akka.http.scaladsl.client.RequestBuilding
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.stream.{ActorFlowMaterializer, FlowMaterializer}
import com.typesafe.config.{Config, ConfigFactory}
import spray.json.DefaultJsonProtocol

import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.math._

case class IpInfo(ip: String, country: Option[String], city: Option[String], latitude: Option[Double], longitude: Option[Double])

case class IpPairSummaryRequest(ip1: String, ip2: String)

case class IpPairSummary(distance: Option[Double], ip1Info: IpInfo, ip2Info: IpInfo)

object IpPairSummary {
  def apply(ip1Info: IpInfo, ip2Info: IpInfo): IpPairSummary = IpPairSummary(calculateDistance(ip1Info, ip2Info), ip1Info, ip2Info)

  private def calculateDistance(ip1Info: IpInfo, ip2Info: IpInfo): Option[Double] = {
    (ip1Info.latitude, ip1Info.longitude, ip2Info.latitude, ip2Info.longitude) match {
      case (Some(lat1), Some(lon1), Some(lat2), Some(lon2)) =>
        // see http://www.movable-type.co.uk/scripts/latlong.html
        val φ1 = toRadians(lat1)
        val φ2 = toRadians(lat2)
        val Δφ = toRadians(lat2 - lat1)
        val Δλ = toRadians(lon2 - lon1)
        val a = pow(sin(Δφ / 2), 2) + cos(φ1) * cos(φ2) * pow(sin(Δλ / 2), 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        Option(EarthRadius * c)
      case _ => None
    }
  }

  private val EarthRadius = 6371.0
}

trait Protocols extends DefaultJsonProtocol {
  implicit val ipInfoFormat = jsonFormat5(IpInfo.apply)
  implicit val ipPairSummaryRequestFormat = jsonFormat2(IpPairSummaryRequest.apply)
  implicit val ipPairSummaryFormat = jsonFormat3(IpPairSummary.apply)
}

trait Service extends Protocols {
  implicit val system: ActorSystem
  implicit def executor: ExecutionContextExecutor
  implicit val materializer: FlowMaterializer

  def config: Config
  val logger: LoggingAdapter

  lazy val telizeConnectionFlow: Flow[HttpRequest, HttpResponse, Any] =
    Http().outgoingConnection(config.getString("services.telizeHost"), config.getInt("services.telizePort"))

  def telizeRequest(request: HttpRequest): Future[HttpResponse] = Source.single(request).via(telizeConnectionFlow).runWith(Sink.head)

  def fetchIpInfo(ip: String): Future[Either[String, IpInfo]] = {
    telizeRequest(RequestBuilding.Get(s"/geoip/$ip")).flatMap { response =>
      response.status match {
        case OK => Unmarshal(response.entity).to[IpInfo].map(Right(_))
        case BadRequest => Future.successful(Left(s"$ip: incorrect IP format"))
        case _ => Unmarshal(response.entity).to[String].flatMap { entity =>
          val error = s"Telize request failed with status code ${response.status} and entity $entity"
          logger.error(error)
          Future.failed(new IOException(error))
        }
      }
    }
  }

  val routes = extractRequest { request =>
      println(s"REQUEST IS:  ${request}")
      complete(s"Request method is ${request.method.name} & content ${request.entity}")
      }


//  val routes = {
//    logRequestResult("akka-http-microservice") {
//
//      pathPrefix("ip") {
//        (get & path(Segment)) { ip =>
//          complete {
//            fetchIpInfo(ip).map[ToResponseMarshallable] {
//              case Right(ipInfo) => ipInfo
//              case Left(errorMessage) => BadRequest -> errorMessage
//            }
//          }
//        } ~
//        (post & entity(as[IpPairSummaryRequest])) { ipPairSummaryRequest =>
//          complete {
//            val ip1InfoFuture = fetchIpInfo(ipPairSummaryRequest.ip1)
//            val ip2InfoFuture = fetchIpInfo(ipPairSummaryRequest.ip2)
//            ip1InfoFuture.zip(ip2InfoFuture).map[ToResponseMarshallable] {
//              case (Right(info1), Right(info2)) => IpPairSummary(info1, info2)
//              case (Left(errorMessage), _) => BadRequest -> errorMessage
//              case (_, Left(errorMessage)) => BadRequest -> errorMessage
//            }
//          }
//        }
//      }
//    }
//  }
}

object AkkaHttpMicroservice extends App with Service {
  override implicit val system = ActorSystem()
  override implicit val executor = system.dispatcher
  override implicit val materializer = ActorFlowMaterializer()

  override val config = ConfigFactory.load()
  override val logger = Logging(system, getClass)

  Http().bindAndHandle(routes, config.getString("http.interface"), config.getInt("http.port"))
}
