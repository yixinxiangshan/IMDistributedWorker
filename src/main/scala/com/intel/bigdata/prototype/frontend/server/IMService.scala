package com.intel.bigdata.prototype.frontend.server

import java.util.UUID
import scala.concurrent.duration._
import akka.util.Timeout
import akka.actor._
import spray.can.Http
import spray.util._
import spray.http._
import spray.http.HttpMethods._
import spray.http.MediaTypes._
import spray.json._
import DefaultJsonProtocol._
import akka.util.Timeout.durationToTimeout
import spray.http.ContentType.apply
import spray.http.HttpEntity.apply
import spray.http.StatusCode.int2StatusCode
import akka.pattern.ask
import scala.concurrent.Future
import spray.httpx.marshalling._
import com.intel.bigdata.prototype.backend.worker.{Work,Service,ServiceInfo,ServiceTimes}
import com.intel.bigdata.prototype.backend.master.{Router}



class IMService(router: ActorRef) extends Actor with SprayActorLogging {
  implicit val timeout: Timeout = 600.second // for the actor 'asks'
  private implicit val system = context.system
  import context.dispatcher // ExecutionContext for the futures and scheduler

  import spray.httpx.SprayJsonSupport._

  def nextWorkId(): String = UUID.randomUUID().toString

  def receive = {
    // when a new connection comes in we register ourselves as the connection handler
    case _: Http.Connected => sender ! Http.Register(self)

    case HttpRequest(GET, Uri.Path("/"), _, _, _) =>
      sender ! index

    case httpRequest: HttpRequest =>
      httpRequest.uri.path.toString match {
        case "/start" =>
          processAgentServiceRequest(httpRequest.uri.path.toString, "start")
        case "/stopIM" =>
          processAgentServiceRequest(httpRequest.uri.path.toString, "stop")
        case "/status" =>
          processAgentServiceRequest(httpRequest.uri.path.toString, "status")
        case "/config" =>
          processAgentServiceRequest(httpRequest.uri.path.toString, "config")
        case "/info" =>
          httpRequest.uri.query.get("service").map(
            processAgentServiceInfo(_)
          )
        case _ => sender ! HttpResponse(status = 404, entity = "Unknown resource!")
      }

  }

  def processAgentRequest(path: String, action: String) = {     
      val imRequestSender = sender	  
      val work = Work(nextWorkId(), action)
      val future = router ? work
      future onSuccess {
	     case Router.Ok => {
	        val msg = "work completed " + work.workId
	        imRequestSender ! HttpResponse(entity = msg)
	     } 
	     case Router.NotOk => {
	        val msg = "work failed " + work.workId
	        imRequestSender ! HttpResponse(entity = msg)
	     }
      }
    }

  def processAgentServiceRequest(path: String, command: String) = {     
      val imRequestSender = sender	  
      val service = Service(nextWorkId(), command)
      val future = router ? service
      future onSuccess {
	     case Router.Ok => {
	        val msg = """{ "service": { "id": """"+service.id+"""", "status": "success"}}"""
                imRequestSender ! HttpResponse(entity = msg.toJson.prettyPrint)
	     } 
	     case Router.NotOk => {
	        val msg = """{ "service": { "id": """"+service.id+"""", "status": "failure"}}"""
	        imRequestSender ! HttpResponse(entity = msg)
	     }
      }
    }
 
  def processAgentServiceInfo(serviceId: String) = {     
      val imRequestSender = sender	  
      val service = Service(serviceId, "info")
      val info = router ? ServiceInfo(service)
      info onSuccess {
        case serviceTimes: ServiceTimes =>
	  val msg = """{ "service": { "id": """"+serviceTimes.service.id+"""", "timesPerWorker": """+serviceTimes.timesPerWorker.toJson.prettyPrint+""", "completionTime": """+serviceTimes.completionTime.toJson.prettyPrint+"""}}"""
	  imRequestSender ! HttpResponse(entity = msg)
        }
    }
  ////////////// helpers //////////////

  lazy val index = HttpResponse(
    entity = HttpEntity(`text/html`,
      <html>
        <body>
    	  <script type="text/javascript">
            <!--
            var serviceId;
            function getServiceId() {
               var xmlhttp = new XMLHttpRequest();
               xmlhttp.onreadystatechange=function() {
                 if (xmlhttp.readyState==4 && xmlhttp.status==200) {
                   serviceId=JSON.parse(eval(xmlhttp.responseText)).service.id
                   setTimeout(getServiceTimes,3000)
                 }
               }
               xmlhttp.open("GET","/start",true);
               xmlhttp.send();
            }
            function getServiceTimes() {
               var xmlhttp = new XMLHttpRequest();
               xmlhttp.onreadystatechange=function() {
                 if (xmlhttp.readyState==4 && xmlhttp.status==200) {
                   var completionTime=JSON.parse(xmlhttp.responseText).service.completionTime;
alert('completionTime='+completionTime)
                 }
               }
               xmlhttp.open("GET","/info?service="+serviceId,true);
               xmlhttp.send();
            }
            -->
    	  </script>
          <h1><i>IMDistributedWorkers prototype</i>!</h1>
          <p>Actions:</p>
          <ul>
            <li><a id="startLink" href="#" onclick="getServiceId()">/start</a></li>
            <li><a id="getLink" href="/status">/status</a></li>
            <li><a id="configLink" href="/config">/config</a></li>
            <li><a href="/instantStatus">/instantStatus</a></li>
            <li><a href="/stopIM">/stopIM</a></li>
            <li><a href="/info">/info</a></li>
          </ul>
        </body>
      </html>.toString()
    )
  )
}
