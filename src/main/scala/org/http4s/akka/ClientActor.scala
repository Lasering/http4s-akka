package org.http4s.akka

import scala.reflect.ClassTag
import scalaz.stream.async.mutable.Queue

import akka.actor.SupervisorStrategy.Stop
import akka.actor.{Actor, OneForOneStrategy, Props, Terminated}

private class ClientActor[Out](props: Props, outQueue: Queue[Out])(implicit messageType: ClassTag[Out]) extends Actor {
  val serverActor = context actorOf props
  context watch serverActor
  
  def receive: Receive = {
    case Terminated(`serverActor`) =>
      outQueue.close.unsafePerformSync
      context stop self
    case messageType(m) if sender() == serverActor =>
      outQueue.enqueueOne(m).unsafePerformSync
    case m if sender() == self =>
      serverActor ! m
  }
  
  override def supervisorStrategy: OneForOneStrategy = OneForOneStrategy() {
    case _ => Stop
  }
}