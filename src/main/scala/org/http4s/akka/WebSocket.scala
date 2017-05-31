package org.http4s.akka

import scala.reflect.ClassTag
import scalaz.concurrent.Task
import scalaz.stream.{Exchange, Process, Sink, async}

import akka.actor.{ActorSystem, PoisonPill, Props}
import org.http4s.server.websocket.{WS => Http4sWS}
import org.http4s.websocket.WebsocketBits.{Close, WebSocketFrame}
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
    * @param in the `FrameFormatter` responsible for converting `WebSocketFrame`s received from the browser to the domain class `In`.
    * @param out the `FrameFormatter` responsible for converting the messages with type `Out` received from the created actor to a `WebSocketFrame`.
    * @param actorSystem the actor system that will be used to created the necessary actors.
    * @tparam In the type of messages coming in
    * @tparam Out the type of messages going out
    */
  def handleWithActor[In, Out : ClassTag](props: Props,
                                          status: Task[Response] = Response(Status.NotImplemented).withBody("This is a WebSocket route."),
                                          queueSize: Option[Int] = Some(10))
                                         (implicit in: FrameFormatter[In], out: FrameFormatter[Out], actorSystem: ActorSystem): Task[Response] = {
    val outQueue = queueSize match {
      case Some(size) => async.boundedQueue[Out](size)
      case None => async.unboundedQueue[Out]
    }
    val clientActor = actorSystem.actorOf(Props(new ClientActor(props, outQueue)))
  
    val toClient = outQueue.dequeue.map(out.toFrame) ++ Process(Close())
    val fromClient: Sink[Task, WebSocketFrame] = Process.constant { frame: WebSocketFrame =>
      Task {
        in.fromFrame(frame) match {
          case Some(input) =>
            // By setting the clientActor as the sender we inform the clientActor that
            // the message was from the browser and it should be sent to the serverActor.
            // This ruse allows us to send messages to the serverActor without
            // having its ActorRef, which saves us from having to ask clientActor for it.
            // Or having to resort to more complex tools.
            clientActor.tell(input, clientActor)
          case None =>
            org.log4s.getLogger.error(s"received unhandled ${frame.getClass.getSimpleName} websocket frame" +
              s"expecting a ${in.fromFrameDefinedFor} frame!")
        }
      }
    } onComplete Process.eval_(Task {
      clientActor ! PoisonPill
    })
    
    Http4sWS(Exchange(toClient, fromClient), status)
  }
}
