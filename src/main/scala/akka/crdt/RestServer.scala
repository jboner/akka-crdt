/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.crdt

import akka.crdt.convergent._
import akka.actor._
import scala.concurrent.{ Future, future, ExecutionContext }
import scala.util.{ Success, Failure }
import scala.util.control.NonFatal
import play.api.libs.json.Json
import play.api.libs.json.Json.{ toJson, parse, stringify }
import com.typesafe.config.ConfigFactory
import unfiltered.Async
import unfiltered.request._
import unfiltered.response._
import unfiltered.netty._
import unfiltered.util._
import unfiltered.request.QParams._

/**
 * Main REST server. Starts up on port 9000 on 0.0.0.0 by default. Configure it to run on other port and address.
 *
 * Run using ``sbt run -Dakka.crdt.rest-server.port=9999``
 * or as a regular main class ``java -Dakka.crdt.rest-server.port=9999 -cp ... akka.crdt.RestServer``.
 */
object RestServer {

  def main(args: Array[String]): Unit = {
    val config = ConfigFactory.defaultOverrides.withFallback(ConfigFactory.parseString("""
			akka {
				actor.provider = akka.cluster.ClusterActorRefProvider
				loglevel       = INFO
				loggers        = ["akka.testkit.TestEventListener"]
        //PN: TestEventListener?
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
          //PN: that is a very popular port, isn't it?
				}
    		crdt.convergent.leveldb.destroy-on-shutdown  = on 
			}
			"""))

    val system = ActorSystem("crdt", config)
    val storage = ConvergentReplicatedDataTypeDatabase(system)

    println(s"""
		=======================================================================================
		★ ★ ★  CRDT Database Server listening on port: ${config.getInt("akka.crdt.rest-server.port")}. Press Control-C to exit...  ★ ★ ★
		=======================================================================================""")

    Runtime.getRuntime.addShutdownHook(new Thread(new Runnable {
      def run = {
        storage.shutdown()
        system.shutdown()
      }
    }))
  }
}

/**
 * Rest server for CRDT storage.
 *
 * Is started up automatically by the `ConvergentReplicatedDataTypeDatabase`
 * extension, if "akka.crdt.rest-server.run = on".
 */
class RestServer(storage: ConvergentReplicatedDataTypeDatabase) {
  @volatile private var http: Option[Http] = None

  val hostname = storage.settings.RestServerHostname
  val port = storage.settings.RestServerPort

  def start(): Unit = http = Some(Http(port, hostname).handler(new CvRDTPlan(storage)).start())

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

  def textResponse[A](content: String): ResponseFunction[A] = Ok ~> PlainTextContent ~> ResponseString(content + END)

  def jsonResponse[A](json: String): ResponseFunction[A] = Ok ~> JsonContent ~> ResponseString(json + END)

  def errorResponse[A](error: String): ResponseFunction[A] = BadRequest ~> PlainTextContent ~> ResponseString(error + END)

  def errorResponse[A](error: Throwable): ResponseFunction[A] = errorResponse(error.toString)
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
        // server nodes
        // =================================================================
        case GET(Path("/nodes")) ⇒
          val nodes = storage.nodes map { node ⇒ Json.obj("host" -> node._1, "port" -> node._2) }
          req.respond(textResponse(stringify(toJson(nodes))))

        // =================================================================
        // g-counter
        // =================================================================
        case GET(Path(Seg("g-counter" :: Nil))) ⇒
          async {
            future { storage.getOrCreate[GCounter]().get } map { counter ⇒
              jsonResponse(counter.view.toString)
            }
          }

        case GET(Path(Seg("g-counter" :: id :: Nil))) ⇒
          async {
            future { storage.getOrCreate[GCounter](id).get } map { counter ⇒
              jsonResponse(counter.view.toString)
            }
          }

        case POST(Path(Seg("g-counter" :: id :: Nil))) & Params(params) ⇒
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
                  jsonResponse(counter.view.toString)
                }
              }
            case Left(error) ⇒
              responder.respond(errorResponse(error.map(e ⇒ s"Parameter ${e.error}").mkString("", ", ", END)))
          }

        // =================================================================
        // pn-counter
        // =================================================================
        case GET(Path(Seg("pn-counter" :: Nil))) ⇒
          async {
            future { storage.getOrCreate[PNCounter]().get } map { counter ⇒
              jsonResponse(counter.view.toString)
            }
          }

        case GET(Path(Seg("pn-counter" :: id :: Nil))) ⇒
          async {
            future { storage.getOrCreate[PNCounter](id).get } map { counter ⇒
              jsonResponse(counter.view.toString)
            }
          }

        case POST(Path(Seg("pn-counter" :: id :: Nil))) & Params(params) ⇒
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
                  jsonResponse(counter.view.toString)
                }
              }
            case Left(error) ⇒
              responder.respond(errorResponse(error map { e ⇒ s"$e.name $e.error" } mkString ("", ", ", END)))
          }

        // =================================================================
        // g-set
        // =================================================================
        case GET(Path(Seg("g-set" :: Nil))) ⇒
          async {
            future { storage.getOrCreate[GSet]().get } map { set ⇒
              jsonResponse(set.view.toString)
            }
          }

        case GET(Path(Seg("g-set" :: id :: Nil))) ⇒
          async {
            future { storage.getOrCreate[GSet](id).get } map { set ⇒
              jsonResponse(set.view.toString)
            }
          }

        case POST(Path(Seg("g-set" :: id :: "add" :: Nil))) ⇒
          async {
            val jsonValue = parse(new String(Body.bytes(req)).trim())
            future {
              val oldSet = storage.getOrCreate[GSet](id).get
              val newSet = oldSet + jsonValue
              storage.update(newSet)
            } map { set ⇒
              jsonResponse(set.view.toString)
            }
          }

        // =================================================================
        // 2p-set
        // =================================================================
        case GET(Path(Seg("2p-set" :: Nil))) ⇒
          async {
            future { storage.getOrCreate[TwoPhaseSet]().get } map { set ⇒
              jsonResponse(set.view.toString)
            }
          }

        case GET(Path(Seg("2p-set" :: id :: Nil))) ⇒
          async {
            future { storage.getOrCreate[TwoPhaseSet](id).get } map { set ⇒
              jsonResponse(set.view.toString)
            }
          }

        case POST(Path(Seg("2p-set" :: id :: "add" :: Nil))) ⇒
          async {
            val jsonValue = parse(new String(Body.bytes(req)).trim())
            future {
              val oldSet = storage.getOrCreate[TwoPhaseSet](id).get
              val newSet = oldSet + jsonValue
              storage.update(newSet)
            } map { set ⇒
              jsonResponse(set.view.toString)
            }
          }

        case POST(Path(Seg("2p-set" :: id :: "remove" :: Nil))) ⇒
          async {
            val jsonValue = parse(new String(Body.bytes(req)).trim())
            future {
              val oldSet = storage.getOrCreate[TwoPhaseSet](id).get
              val newSet = oldSet - jsonValue
              storage.update(newSet)
            } map { set ⇒
              jsonResponse(set.view.toString)
            }
          }

        case invalid ⇒
          responder.respond(errorResponse(s"Invalid request: ${invalid.method} ${invalid.uri}"))
      }
  }
}
