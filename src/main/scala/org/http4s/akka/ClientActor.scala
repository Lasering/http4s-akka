package org.http4s.akka

import scala.reflect.ClassTag

import akka.actor.SupervisorStrategy.Stop
import akka.actor.{Actor, OneForOneStrategy, Props, Terminated}
import fs2.Task
import fs2.async.mutable.{Queue, Signal}

private class ClientActor[Out](props: Props, outQueue: Queue[Task, Out], closeSignal: Signal[Task, Boolean])
                              (implicit messageType: ClassTag[Out]) extends Actor {
  val serverActor = context actorOf props
  context watch serverActor
  
  def receive: Receive = {
    case Terminated(`serverActor`) =>
      closeSignal.set(true).unsafeRun()
      context stop self
    case messageType(m) if sender() == serverActor =>
      outQueue.enqueue1(m).unsafeRun()
    case m if sender() == serverActor =>
      org.log4s.getLogger.error(s"Server sent unhandled message ${m.getClass.getSimpleName} " +
        s"expecting a ${messageType.runtimeClass.getSimpleName}!")
    case m if sender() == self =>
      serverActor ! m
  }
  
  override def supervisorStrategy: OneForOneStrategy = OneForOneStrategy() {
    case _ => Stop
  }
}