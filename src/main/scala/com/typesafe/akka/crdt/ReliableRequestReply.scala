/*
 * Copyright 2012-2013 Eligotech BV.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.typesafe.akka.crdt

import scala.concurrent.duration._

import akka.actor._
import akka.pattern._
import akka.util.Timeout

import org.eligosource.eventsourced.core._

@SerialVersionUID(1L)
case class DestinationFailure(channelId: Int, failureCount: Int, request: Any, t: Throwable)

@SerialVersionUID(1L)
case class DestinationNotResponding(channelId: Int, failureCount: Int, request: Any)

trait ReliableRequestReply {
  /**
   * Reliable request-reply channel configuration object.
   *
   * @param replyTimeout timeout for receiving a reply from the destination.
   *        Must be less than `policy.confirmationTimeout`
   * @param policy redelivery policy for the reliable channel.
   *
   * @see [[org.eligosource.eventsourced.core.ReliableChannel]].
   */
  case class ReliableRequestReplyChannelProps(
    id: Int,
    destination: ActorRef,
    replyTimeout: FiniteDuration = 5 seconds,
    policy: RedeliveryPolicy = RedeliveryPolicy().copy(confirmationTimeout = 10 seconds),
    name: Option[String] = None,
    dispatcherName: Option[String] = None) extends ChannelProps {

    /**
     * Returns a new `ReliableRequestReplyChannelProps` with the specified name.
     */
    def withName(name: String) =
      copy(name = Some(name))

    /**
     * Returns a new `ReliableRequestReplyChannelProps` with the specified dispatcher name.
     */
    def withDispatcherName(dispatcherName: String) =
      copy(dispatcherName = Some(dispatcherName))

    /**
     * Returns a new `ReliableRequestReplyChannelProps` with the specified reply timeout.
     */
    def withReplyTimeout(replyTimeout: FiniteDuration) =
      copy(replyTimeout = replyTimeout)

    /**
     * Returns a new `ReliableRequestReplyChannelProps` with the specified confirmation timeout.
     */
    def withConfirmationTimeout(confirmationTimeout: FiniteDuration) =
      copy(policy = policy.copy(confirmationTimeout = confirmationTimeout))

    /**
     * Returns a new `ReliableRequestReplyChannelProps` with the specified restart delay.
     */
    def withRestartDelay(restartDelay: FiniteDuration) =
      copy(policy = policy.copy(restartDelay = restartDelay))

    /**
     * Returns a new `ReliableRequestReplyChannelProps` with the specified maximum number of restarts.
     */
    def withRestartMax(restartMax: Int) =
      copy(policy = policy.copy(restartMax = restartMax))

    /**
     * Returns a new `ReliableRequestReplyChannelProps` with the specified re-delivery delay.
     */
    def withRedeliveryDelay(redeliveryDelay: FiniteDuration) =
      copy(policy = policy.copy(redeliveryDelay = redeliveryDelay))

    /**
     * Returns a new `ReliableRequestReplyChannelProps` with the specified maximum number of re-delivery attempts.
     */
    def withRedeliveryMax(redeliveryMax: Int) =
      copy(policy = policy.copy(redeliveryMax = redeliveryMax))

    /**
     * Creates a [[org.eligosource.eventsourced.core.ReliableChannel]] and a proxy for `destination`
     * with the settings defined by this configuration object. The destination proxy together with
     * the reliable channel mediate reliable request-reply interactions between a channel user (usually
     * an `Eventsourced` processor) and the `destination`.
     */
    def createChannel(journal: ActorRef)(implicit actorRefFactory: ActorRefFactory) = {
      require(replyTimeout < policy.confirmationTimeout,
        "replyTimeout (%s) must be less than policy.confirmationTimeout (%s)" format (replyTimeout, policy.confirmationTimeout))

      val proxy = actor(new DestinationProxy(this) with Receiver, name.map("%sDestinationProxy" format _), dispatcherName)
      actor(new ReliableChannel(id, journal, proxy, policy, dispatcherName), name, dispatcherName)
    }
  }

  /**
   * Destination proxy that mediates reliable request-reply interactions with a destination that is
   * specified by `props.destination`.
   *
   * @param props reliable request-reply channel configuration object.
   */
  private class DestinationProxy(props: ReliableRequestReplyChannelProps) extends Actor { this: Receiver =>
    import props._

    implicit val timeout = Timeout(props.replyTimeout)

    var lastSequenceNr = 0L
    var redeliveries = 0

    def receive = {
      case request => {
        val current = message

        if (current.sequenceNr == lastSequenceNr) redeliveries += 1 else {
          lastSequenceNr = current.sequenceNr
          redeliveries = 0
        }

        import context.dispatcher

        val initiator = sender
        val future = destination ? request

        future onSuccess {
          case rep => initiator ! current.copy(rep)
        }

        future onFailure {
          case thr: AskTimeoutException =>
            initiator ! current.copy(DestinationNotResponding(id, redeliveries + 1, request))
          case thr =>
            initiator ! current.copy(DestinationFailure(id, redeliveries + 1, request, thr))
        }
      }
    }
  }
}

