/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.crdt.convergent

import akka.actor._
import akka.pattern.ask
import akka.util.Timeout
 
import scala.concurrent.duration._
import scala.concurrent.Future

import unfiltered.Async
import unfiltered.request._
import unfiltered.response._
import unfiltered.netty._
import unfiltered.util._
 
object RestServer {
  def main(args: Array[String]): Unit = {
  	val server = new RestServer(null)
  	server.start()
  	println(s"Server listening on port: $server.port. Press any key to exit...")
	  System.in.read()
	  server.shutdown()
  }
}

class RestServer(storage: ConvergentReplicatedDataTypeDatabase) {
	@volatile var http: Http = _ //FIXME put in AtomicReference or protect by AtomicBoolean 
	val port = 9000 // FIXME make port configurable
	
  def start(): Unit = {
    http = Http(port)
      .handler(new GCounterPlan(storage))
      .start()
  }
  
  def shutdown(): Unit = http.stop()
}

class GCounterPlan(storage: ConvergentReplicatedDataTypeDatabase) extends async.Plan with ServerErrorResponse {

	def intent = {
	  case req @ GET(Path("/ping")) =>
	    req.respond(textResponse("Pong"))

	  case req @ GET(Path(Seg("cvrdt" :: "g-counter" :: id :: Nil))) =>
//			    async {
//			      (accountActor ask Status(accountId)).mapTo[Int].map { r =>
//			        if (r > 0) textResponse("Account total: " + r)
//			        else BadRequest ~> textResponse("Unknown account: " + accountId)
//			      }
//			    }

	  case req @ POST(Path(Seg("cvrdt" :: "g-counter" :: id :: Nil))) & Params(params) =>
//			    validate(params) { (accountId, amount) =>
//			      async {
//			        (accountActor ask Deposit(accountId, amount)).mapTo[Int].map { r =>
//			          textResponse("Updated account total: " + r)
//			        }
//			      }
//			    }
	}
	
	
  def textResponse(content: String) = PlainTextContent ~> ResponseString(content + "\r\n")
  
  private def async[A](body: => Future[ResponseFunction[A]])(implicit responder: Async.Responder[A]): Unit = {
//    body onComplete {
//      case Right(rf) => responder.respond(rf)
//      case _ =>
//        // You should do something about the error here, but this is just a simple example ;)
//        responder.respond(RequestTimeout)
//    }
  }

	private def validate[A](params: Params.Map)(success: (String, Int) => Unit)(implicit responder: Async.Responder[A]) {
    import QParams._
    val expected = for {
      accountId <- lookup("accountId") is
        required("accountId is missing") is
        trimmed is
        nonempty("accountId is empty")
      amount <- lookup("amount") is
        required("amount is missing") is
        int(s => "'%s' is not an integer".format(s)) is
        pred(a => a >= 1, _ => "amount must be >= 1")
      } yield accountId.get -> amount.get
 
      expected(params) match {
        case Right((accountId, amount)) => success(accountId, amount)
        case Left(log) =>
          val err = log.map(f => "%s %s".format(f.name, f.error)).mkString("", ", ", "\r\n")
        responder.respond(BadRequest ~> PlainTextContent ~> ResponseString(err))
    }
  }

	def view(time: String) = {
    Html(
     <html><body>
       The current time is: { time }
     </body></html>
   )
  }
}

//object ConvergentReplicatedDataTypePlan 
//	extends cycle.Plan
//  with cycle.SynchronousExecution 
//  with ServerErrorResponse {
//  import QParams._
//  
//  def intent = {
//    case GET(Path("/")) =>
//			println("GET /")
//      view(Map.empty)(<p> What say you? </p>)
//
//    case POST(Path("/") & Params(params)) =>
//      println("POST /")
//      val vw = view(params)_
//      val expected = for { 
//        int <- lookup("int") is
//          int { s => "'%s' is not an integer".format(s) } is
//          required("missing int")
//        word <- lookup("palindrome") is
//          trimmed is 
//          nonempty("Palindrome is empty") is
//          pred(palindrome, { s =>
//            "%s is not a palindrome".format(s)
//          }) is
//          required("missing palindrome")
//      } yield vw(<p>Yup. { int.get } is an integer and { word.get } is a palindrome. </p>)
//      expected(params) orFail { fails =>
//        vw(<ul> { fails.map { f => <li>{f.error} </li> } } </ul>)
//      }
//  }
//
//  def palindrome(s: String) = s.toLowerCase.reverse == s.toLowerCase
//  
//  def view(params: Map[String, Seq[String]])(body: scala.xml.NodeSeq) = {
//    def p(k: String) = params.get(k).flatMap { _.headOption } getOrElse("")
//    Html(
//     <html><body>
//       { body }
//       <form method="POST">
//         Integer <input name="int" value={ p("int") } ></input>
//         Palindrome <input name="palindrome" value={ p("palindrome") } />
//         <input type="submit" />
//       </form>
//     </body></html>
//   )
//  }
//}