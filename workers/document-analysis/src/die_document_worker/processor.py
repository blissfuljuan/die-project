"""Settlement logic for one delivery, independent of pika so it can be tested without a broker."""
from __future__ import annotations

import logging
from enum import Enum
from typing import Any, Callable, Protocol

from .dedupe import ProcessedMessages
from .envelope import Envelope, EnvelopeCodec, InvalidEnvelope, derived_message_id, now_utc
from .handlers import ANALYZE, ANALYZE_RESULT, WORKER, handle_analyze
from .logging_setup import bind, unbind
from .topology import ANALYZE_TOPOLOGY, RESULTS_DESTINATION, Destination, QueueTopology

log = logging.getLogger("die.processor")


class PublishError(Exception):
    """A message was not confirmed by the broker (unroutable, nacked, or the connection failed)."""


class Publisher(Protocol):
    def publish(self, destination: Destination, body: bytes, *, message_id: str | None = None,
                correlation_id: str | None = None, type: str | None = None,
                headers: dict[str, Any] | None = None) -> None: ...


class Settlement(Enum):
    ACK = "ack"
    REQUEUE = "requeue"      # could not route the outcome; leave the message for redelivery


class MessageProcessor:
    """Per delivery: invalid -> DLQ; duplicate -> ack; success -> publish result then ack; failure -> retry tier or
    (attempts used up) FAILED result + DLQ. Results are published (confirmed) BEFORE the delivery is acknowledged, so a
    crash in between causes a redelivery, never a lost job; the redelivery is harmless because result ids are derived
    deterministically from the input message id."""

    def __init__(self, codec: EnvelopeCodec, publisher: Publisher, processed: ProcessedMessages,
                 topology: QueueTopology = ANALYZE_TOPOLOGY,
                 handler: Callable[[Envelope], dict[str, Any]] = handle_analyze):
        self._codec = codec
        self._publisher = publisher
        self._processed = processed
        self._topology = topology
        self._handler = handler

    def process(self, body: bytes) -> Settlement:
        try:
            envelope = self._codec.decode(body)
        except InvalidEnvelope as exc:
            log.warning("poison message dead-lettered: %s %s", exc, exc.problems)
            return self._dead_letter_raw(body, f"invalid envelope: {exc} {exc.problems}")

        token = bind(correlationId=envelope.correlation_id, messageId=envelope.message_id, messageType=envelope.type)
        try:
            if self._processed.seen(envelope.message_id):
                log.info("duplicate delivery ignored")
                return Settlement.ACK
            if envelope.type != ANALYZE:
                log.warning("unsupported message type, dead-lettered")
                return self._dead_letter_raw(body, f"unsupported message type {envelope.type}")
            return self._handle(envelope, body)
        finally:
            unbind(token)

    def _handle(self, envelope: Envelope, body: bytes) -> Settlement:
        try:
            result_payload = self._handler(envelope)
            self._send_result(envelope, "analyze-result", result_payload)
            self._processed.remember(envelope.message_id)
            log.info("analyze job handled (attempt %d)", envelope.attempt)
            return Settlement.ACK
        except PublishError as exc:
            log.error("could not publish the result, leaving the message for redelivery: %s", exc)
            return Settlement.REQUEUE
        except Exception as exc:  # noqa: BLE001 - every handler failure is routed, never crashes the worker
            log.warning("handling failed on attempt %d: %s", envelope.attempt, exc)
            try:
                return self._retry_or_dead_letter(envelope, body, str(exc))
            except PublishError as publish_exc:
                log.error("could not route the failure, leaving the message for redelivery: %s", publish_exc)
                return Settlement.REQUEUE

    def _retry_or_dead_letter(self, envelope: Envelope, body: bytes, reason: str) -> Settlement:
        decision = self._topology.decide(envelope.attempt)
        if decision.action == "retry":
            nxt = envelope.next_attempt()
            self._publisher.publish(
                self._topology.retry_destination(decision.tier), self._codec.encode(nxt),
                message_id=nxt.message_id, correlation_id=nxt.correlation_id, type=nxt.type,
                headers={"x-die-retry-reason": reason[:400]})
            log.info("scheduled retry %d in tier %d", nxt.attempt, decision.tier)
            return Settlement.ACK
        # Attempts used up: tell the API the job failed (so test_job does not stay RUNNING), then dead-letter.
        log.error("retries exhausted after %d attempts, dead-lettered", envelope.attempt)
        self._send_result(envelope, "analyze-failed", {
            "testJobId": envelope.payload.get("testJobId"),
            "outcome": "FAILED",
            "worker": WORKER,
            "reason": f"retries exhausted: {reason}"[:500],
        })
        self._dead_letter_raw(body, f"retries exhausted: {reason}", envelope)
        return Settlement.ACK

    def _send_result(self, source: Envelope, step: str, payload: dict[str, Any]) -> None:
        result = Envelope(message_id=derived_message_id(source.message_id, step), correlation_id=source.correlation_id,
                          type=ANALYZE_RESULT, created_at=now_utc(), attempt=1, payload=payload)
        self._publisher.publish(RESULTS_DESTINATION, self._codec.encode(result), message_id=result.message_id,
                                correlation_id=result.correlation_id, type=result.type)

    def _dead_letter_raw(self, body: bytes, reason: str, envelope: Envelope | None = None) -> Settlement:
        try:
            self._publisher.publish(
                self._topology.dead_letter_destination(), body,
                message_id=envelope.message_id if envelope else None,
                correlation_id=envelope.correlation_id if envelope else None,
                type=envelope.type if envelope else None,
                headers={"x-die-failure-reason": reason[:500], "x-die-source-queue": self._topology.queue})
        except PublishError as exc:
            log.error("could not dead-letter, leaving the message for redelivery: %s", exc)
            return Settlement.REQUEUE
        return Settlement.ACK
