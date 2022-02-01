/*
 * Copyright 2011-2022 GatlingCorp (https://gatling.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.gatling.http.action.ws.fsm

import scala.concurrent.duration.DurationInt

import io.gatling.commons.stats.OK
import io.gatling.core.action.Action
import io.gatling.core.session.Session
import io.gatling.http.check.ws.{ WsFrameCheck, WsFrameCheckSequence }
import io.gatling.http.client.WebSocket

import com.typesafe.scalalogging.StrictLogging
import io.netty.buffer.Unpooled
import io.netty.handler.codec.http.websocketx.{ BinaryWebSocketFrame, CloseWebSocketFrame, TextWebSocketFrame }

final class WsIdleState(fsm: WsFsm, session: Session, webSocket: WebSocket, protected val remainingReconnects: Int) extends WsState(fsm) with StrictLogging {

  import fsm._

  override def onSendTextFrame(
      actionName: String,
      message: String,
      checkSequences: List[WsFrameCheckSequence[WsFrameCheck]],
      session: Session,
      next: Action
  ): NextWsState = {
    logger.debug(s"Send text frame $actionName $message")
    // actually send message!
    val now = clock.nowMillis
    webSocket.sendFrame(new TextWebSocketFrame(message))
    statsEngine.logResponse(session.scenario, session.groups, actionName, now, now, OK, None, None)

    checkSequences match {
      case WsFrameCheckSequence(timeout, currentCheck :: remainingChecks) :: remainingCheckSequences =>
        logger.debug("Trigger check after sending text frame")
        scheduleTimeout(timeout)
        //[fl]
        //
        //[fl]
        NextWsState(
          WsPerformingCheckState(
            fsm,
            webSocket = webSocket,
            currentCheck = currentCheck,
            remainingChecks = remainingChecks,
            checkSequenceStart = now,
            remainingCheckSequences,
            session = session,
            remainingReconnects = remainingReconnects,
            next = Left(next)
          )
        )

      case _ =>
        // same as Nil as WsFrameCheckSequence#checks can't be Nil, but compiler complains that match may not be exhaustive
        NextWsState(this, () => next ! session)
    }
  }

  override def onSendBinaryFrame(
      actionName: String,
      message: Array[Byte],
      checkSequences: List[WsFrameCheckSequence[WsFrameCheck]],
      session: Session,
      next: Action
  ): NextWsState = {
    logger.debug(s"Send binary frame $actionName length=${message.length}")
    // actually send message!
    val now = clock.nowMillis
    webSocket.sendFrame(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(message)))
    statsEngine.logResponse(session.scenario, session.groups, actionName, now, now, OK, None, None)

    checkSequences match {
      case WsFrameCheckSequence(timeout, currentCheck :: remainingChecks) :: remainingCheckSequences =>
        logger.debug("Trigger check after sending binary frame")
        scheduleTimeout(timeout)
        //[fl]
        //
        //[fl]
        NextWsState(
          WsPerformingCheckState(
            fsm,
            webSocket = webSocket,
            currentCheck = currentCheck,
            remainingChecks = remainingChecks,
            checkSequenceStart = now,
            remainingCheckSequences,
            session = session,
            remainingReconnects = remainingReconnects,
            next = Left(next)
          )
        )

      case _ => // same as Nil as WsFrameCheckSequence#checks can't be Nil, but compiler complains that match may not be exhaustive
        NextWsState(this, () => next ! session)
    }
  }

  override def onTextFrameReceived(message: String, timestamp: Long): NextWsState = {
    // try to auto reply or log the message
    if (!autoReplyTextFrames(message, webSocket)) {
      logUnmatchedServerMessage(session)
    }
    NextWsState(this)
  }

  override def onBinaryFrameReceived(message: Array[Byte], timestamp: Long): NextWsState = {
    // server push message, just log
    logUnmatchedServerMessage(session)
    NextWsState(this)
  }

  override def onWebSocketClosed(code: Int, reason: String, timestamp: Long): NextWsState = {
    // server issued close
    logger.debug(s"WebSocket was forcefully closed ($code:$reason) by the server while in Idle state")
    NextWsState(new WsCrashedState(fsm, None, remainingReconnects))
  }

  override def onClientCloseRequest(actionName: String, session: Session, next: Action): NextWsState = {
    logger.debug("Client requested WebSocket close")
    scheduleTimeout(httpProtocol.wsPart.clientCloseTimeout)
    webSocket.sendFrame(new CloseWebSocketFrame())

    NextWsState(new WsClosingState(fsm, actionName, session, next, clock.nowMillis))
  }
}
