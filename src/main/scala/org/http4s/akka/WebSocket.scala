package org.http4s.akka

import scala.concurrent.duration._
import scala.reflect.ClassTag

import akka.actor.{ActorSystem, PoisonPill, Props}
import fs2.time.awakeEvery
import fs2.{Scheduler, Sink, Strategy, Stream, Task, async}
import org.http4s.server.websocket.WS
import org.http4s.websocket.WebsocketBits.{Close, Ping, WebSocketFrame}
import org.http4s.{Response, Status}

object WebSocket {
  /**
    * Build a response which will accept an HTTP websocket upgrade request and initiate a websocket connection.
    * The WebSocket will pass messages to/from the actor created by the given `props` asynchronously.
    * The messages received from the websocket will be converted to type `In` via the `in` `FrameFormatter` and then sent
    * to the actor. Similarly the messages the actor sends will be converted from type `Out` via the `out` `FrameFormatter`.
    * @param props the `Props` that creates the actor to handle the websocket connection.
    * @param status The status code to return to a client making a non-websocket HTTP request to this route
    * @param queueSize the size of the queue used to store the messages the actor wants to send to the browser.
    *                  Set to `None` to use an unbounded queue.
    * @param serverAliveInterval The websocket will be automatically closed by the browser if no message is sent within a
    *                            timeout. If you would like to keep the websocket open set this to a Duration inferior to
    *                            the timeout. Set to and infinite duration to disable it.
    * @param in the `FrameFormatter` responsible for converting `WebSocketFrame`s received from the browser to the domain class `In`.
    * @param out the `FrameFormatter` responsible for converting the messages with type `Out` received from the created actor to a `WebSocketFrame`.
    * @param actorSystem the actor system that will be used to created the necessary actors.
    * @tparam In the type of messages coming in
    * @tparam Out the type of messages going out
    */
  def handleWithActor[In, Out : ClassTag](props: Props,
                                          status: Task[Response] = Response(Status.NotImplemented).withBody("This is a WebSocket route."),
                                          queueSize: Option[Int] = Some(10), serverAliveInterval: Duration = Duration.Inf)
                                         (implicit in: FrameFormatter[In], out: FrameFormatter[Out], actorSystem: ActorSystem,
                                          scheduler: Scheduler, strategy: Strategy): Task[Response] = {
    val logger = org.log4s.getLogger
    
    val queueAndSignal = for {
      signal <- async.signalOf[Task, Boolean](false)
      queue <- queueSize match {
        case Some(size) => async.boundedQueue[Task, Out](size)
        case None => async.unboundedQueue[Task, Out]
      }
    } yield (queue, signal)
    
    queueAndSignal.flatMap { case (queue, signal) =>
      // When the serverActor terminates the clientActor will indicate it via the close signal
      // we use it to interrupt the actorMessages and close the websocket from the server side
      val actorMessages: Stream[Task, WebSocketFrame] = queue.dequeue.map(out.toFrame).interruptWhen(signal) ++ Stream(Close())
      val messagesToBrowser: Stream[Task, WebSocketFrame] = serverAliveInterval match {
        case interval: FiniteDuration =>
          val pings = awakeEvery[Task](interval).map { d =>
            logger.trace(s"Sending server alive ping! $d")
            Ping()
          }
          // As soon as the actorMessages stop we also stop the pings.
          actorMessages mergeHaltL pings
        case _ => actorMessages
      }
      
      val clientActor = actorSystem.actorOf(Props(new ClientActor(props, queue, signal)))
      val messagesFromBrowser: Sink[Task, WebSocketFrame] = _.map { frame: WebSocketFrame =>
        in.fromFrame(frame) match {
          case Some(input) =>
            // By setting the clientActor as the sender we inform it that the message was from the browser and it should
            // be sent to the serverActor. This ruse allows us to send messages to the serverActor without having its
            // ActorRef, which saves us from having to ask clientActor for it.
            clientActor.tell(input, clientActor)
          case None =>
            logger.error(s"received unhandled ${frame.getClass.getSimpleName} websocket frame" +
              s"expecting a ${in.fromFrameDefinedFor} frame!")
        }
      } onFinalize Task {
        clientActor ! PoisonPill
      }
      
      WS(messagesToBrowser, messagesFromBrowser, status)
    }
  }
}
