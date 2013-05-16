/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.crdt.convergent

import akka.actor._
import scala.concurrent.{ Future, future, ExecutionContext }
import scala.util.{ Success, Failure }
import scala.util.control.NonFatal
import play.api.libs.json.Json.{ toJson, parse, stringify }
import play.api.libs.json.JsValue
import unfiltered.Async
import unfiltered.request._
import unfiltered.response._
import unfiltered.netty._
import unfiltered.util._
import QParams._
import com.typesafe.config.ConfigFactory

/**
 * Used to run as a main server in demos etc. Starts up on random port on 127.0.0.1.
 *
 * POST:
 * <pre>
 * 		curl -i -H "Accept: application/json" -X POST -d "node=darkstar" -d "delta=1" http://127.0.0.1:9000/g-counter/jonas
 * </pre>
 *
 * GET:
 * <pre>
 * 		curl -i -H "Accept: application/json" http://127.0.0.1:9000/g-counter/jonas
 * </pre>
 */
object RestServer {
  def main(args: Array[String]): Unit = {
    val config = ConfigFactory.parseString("""
			akka {
				actor.provider = akka.cluster.ClusterActorRefProvider
				loglevel       = INFO
				loggers        = ["akka.testkit.TestEventListener"]
				remote {
					enabled-transports = ["akka.remote.netty.tcp"]
					netty.tcp {
	      		hostname = "127.0.0.1"
	      		port     = 0
					}
					log-remote-lifecycle-events = off
				}
				crdt.rest-server {
				  run      = on
	  			hostname = "0.0.0.0"
				  port     = 9000
				}
			}
			""")
    val system = ActorSystem("CvRDTDatabase", config)
    val storage = ConvergentReplicatedDataTypeDatabase(system)
    println(s"""
		======================================================================================
		★ ★ ★  CvRDT Database Server listening on port: ${config.getInt("akka.crdt.rest-server.port")}. Press any key to exit...  ★ ★ ★
		======================================================================================""")
    System.in.read()
    storage.shutdown()
    system.shutdown()
  }
}

class RestServer(storage: ConvergentReplicatedDataTypeDatabase) {
  @volatile private var http: Option[Http] = None

  final val hostname = storage.settings.RestServerHostname
  final val port = storage.settings.RestServerPort

  def start(): Unit = {
    http = Some(Http(port, hostname).handler(new CvRDTPlan(storage)).start())
  }

  def shutdown(): Unit = http foreach (_.stop())
}

trait AsyncPlan {
  val END = "\r\n"
  def async[A](body: ⇒ Future[ResponseFunction[A]])(implicit responder: Async.Responder[A], executionContext: ExecutionContext): Unit = {
    try {
      body onComplete {
        case Success(result) ⇒ responder.respond(result)
        case Failure(error)  ⇒ responder.respond(errorResponse(error))
      }
    } catch {
      case NonFatal(e) ⇒ responder.respond(errorResponse(e))
    }
  }

  def textResponse[A](content: String): ResponseFunction[A] = PlainTextContent ~> ResponseString(content + END)

  def jsonResponse[A](json: String): ResponseFunction[A] = JsonContent ~> ResponseString(json + END)

  def errorResponse[A](error: String): ResponseFunction[A] = BadRequest ~> PlainTextContent ~> ResponseString(error + END)

  def errorResponse[A](error: Throwable): ResponseFunction[A] = errorResponse(error.getMessage)
}

class CvRDTPlan(storage: ConvergentReplicatedDataTypeDatabase)
  extends async.Plan with ServerErrorResponse with AsyncPlan {
  import storage.system.dispatcher

  def intent = {
    case req ⇒
      implicit val responder = req
      req match {
        // =================================================================
        // ping
        // =================================================================
        case GET(Path("/ping")) ⇒
          req.respond(textResponse("pong"))

        // =================================================================
        // g-counter
        // =================================================================
        case GET(Path(Seg("g-counter" :: Nil))) ⇒
          async {
            future { storage.getOrCreate[GCounter]().get } map { counter ⇒
              jsonResponse(stringify(toJson(counter)))
            }
          }

        case GET(Path(Seg("g-counter" :: id :: Nil))) ⇒
          async {
            future { storage.getOrCreate[GCounter](id).get } map { counter ⇒
              jsonResponse(stringify(toJson(counter)))
            }
          }

        case POST(Path(Seg("g-counter" :: id :: Nil))) & Params(params) ⇒
          if (params.isEmpty) { // POST with full CRDT body
            async {
              val json = new String(Body.bytes(req)).trim()
              future { storage.update(parse(json).as[GCounter]) } map { _ ⇒ jsonResponse(json) }
            }
          } else { // POST with params node/delta
            val validateParams = for {
              node ← lookup("node") is
                required("'node' is missing") is
                trimmed is
                nonempty("'node' is empty")
              delta ← lookup("delta") is
                required("'delta' is missing") is
                int(s ⇒ s"$s' is not an integer") is
                pred(i ⇒ i >= 1, _ ⇒ "delta must be >= 1")
            } yield (node.get, delta.get)

            validateParams(params) match {
              case Right((node, delta)) ⇒
                async {
                  future { storage.update(storage.getOrCreate[GCounter](id).get + (node, delta)) } map { counter ⇒
                    jsonResponse(stringify(toJson(counter)))
                  }
                }
              case Left(error) ⇒
                responder.respond(errorResponse(error.map(e ⇒ s"Parameter ${e.error}").mkString("", ", ", END)))
            }
          }

        // =================================================================
        // pn-counter
        // =================================================================
        case GET(Path(Seg("pn-counter" :: Nil))) ⇒
          async {
            future { storage.getOrCreate[PNCounter]().get } map { counter ⇒
              jsonResponse(stringify(toJson(counter)))
            }
          }

        case GET(Path(Seg("pn-counter" :: id :: Nil))) ⇒
          async {
            future { storage.getOrCreate[PNCounter](id).get } map { counter ⇒
              jsonResponse(stringify(toJson(counter)))
            }
          }

        case POST(Path(Seg("pn-counter" :: id :: Nil))) & Params(params) ⇒
          if (params.isEmpty) { // POST with full CRDT body
            async {
              val json = new String(Body.bytes(req)).trim()
              future { storage.update(parse(json).as[PNCounter]) } map { _ ⇒ jsonResponse(json) }
            }
          } else { // POST with params node/delta
            val validateParams = for {
              node ← lookup("node") is
                required("'node' is missing") is
                trimmed is
                nonempty("'node' is empty")
              delta ← lookup("delta") is
                required("'delta' is missing") is
                int(s ⇒ s"$s' is not an integer") // can be negative
            } yield (node.get, delta.get)

            validateParams(params) match {
              case Right((node, delta)) ⇒
                async {
                  future { storage.update(storage.getOrCreate[PNCounter](id).get + (node, delta)) } map { counter ⇒
                    jsonResponse(stringify(toJson(counter)))
                  }
                }
              case Left(error) ⇒
                responder.respond(errorResponse(error map { e ⇒ s"$e.name $e.error" } mkString ("", ", ", END)))
            }
          }
      }
  }
}
