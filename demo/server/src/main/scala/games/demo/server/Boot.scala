package games.demo.server

import akka.actor.{ ActorSystem, Props }
import akka.io.IO
import spray.can.Http
import akka.pattern.ask
import akka.util.Timeout
import scala.concurrent.duration._
import spray.can.server.UHttp
import scala.concurrent.ExecutionContext.Implicits.global

object Boot extends App {
  implicit val system = ActorSystem("SprayServer")

  val service = system.actorOf(Props[Service], "ListenerService")

  implicit val timeout = Timeout(5.seconds)
  IO(UHttp) ? Http.Bind(service, interface = "::0", port = 8080)
}
