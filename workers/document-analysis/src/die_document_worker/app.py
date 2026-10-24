"""Broker session management: connect, consume with manual acks, reconnect with backoff, shut down on SIGTERM."""
from __future__ import annotations

import logging
import signal
import threading
import time
from typing import Any

import pika
from pika.exceptions import AMQPError, NackError, UnroutableError

from . import logging_setup
from .config import Settings
from .dedupe import ProcessedMessages
from .envelope import EnvelopeCodec
from .health import HealthState, start_health_server
from .processor import MessageProcessor, PublishError, Settlement
from .topology import ANALYZE_QUEUE, Destination

log = logging.getLogger("die.worker")


class PikaPublisher:
    """Confirmed, persistent, mandatory publishing on the session channel."""

    def __init__(self, channel):
        self._channel = channel

    def publish(self, destination: Destination, body: bytes, *, message_id: str | None = None,
                correlation_id: str | None = None, type: str | None = None,
                headers: dict[str, Any] | None = None) -> None:
        props = pika.BasicProperties(delivery_mode=2, content_type="application/json", content_encoding="utf-8",
                                     message_id=message_id, correlation_id=correlation_id, type=type,
                                     app_id="die-document-analysis", headers=headers or None)
        try:
            self._channel.basic_publish(destination.exchange, destination.routing_key, body, props, mandatory=True)
        except (UnroutableError, NackError) as exc:
            raise PublishError(f"broker refused the message: {exc!r}") from exc
        except AMQPError as exc:
            raise PublishError(f"broker unavailable: {exc!r}") from exc


def run_session(settings: Settings, processor_factory, state: HealthState, stop: threading.Event) -> None:
    """One connection's lifetime. Returns when stopped; raises on connection loss so the caller reconnects."""
    credentials = pika.PlainCredentials(settings.username, settings.password)
    params = pika.ConnectionParameters(
        host=settings.host, port=settings.port, virtual_host=settings.vhost, credentials=credentials,
        heartbeat=30, blocked_connection_timeout=30, socket_timeout=5, connection_attempts=1)
    connection = pika.BlockingConnection(params)
    try:
        channel = connection.channel()
        channel.confirm_delivery()
        channel.basic_qos(prefetch_count=settings.prefetch)
        channel.queue_declare(queue=ANALYZE_QUEUE, passive=True)     # topology comes from infra/rabbitmq/definitions.json
        processor: MessageProcessor = processor_factory(PikaPublisher(channel))
        state.set_consuming(True)
        log.info("consuming %s (prefetch %d)", ANALYZE_QUEUE, settings.prefetch)
        for method, _props, body in channel.consume(ANALYZE_QUEUE, inactivity_timeout=1):
            state.heartbeat()
            if stop.is_set():
                break
            if method is None:
                continue          # idle tick: lets pika service AMQP heartbeats and lets us notice SIGTERM
            settlement = processor.process(body)
            if settlement is Settlement.ACK:
                channel.basic_ack(method.delivery_tag)
                state.message_handled()
            else:
                channel.basic_nack(method.delivery_tag, requeue=True)
                time.sleep(1)     # do not spin while the broker cannot route our answers
        if connection.is_open:
            channel.cancel()
    finally:
        state.set_consuming(False)
        if connection.is_open:
            try:
                connection.close()
            except AMQPError:
                pass


def run(settings: Settings) -> None:
    logging_setup.configure(settings.log_level)
    stop = threading.Event()
    for sig in (signal.SIGTERM, signal.SIGINT):
        signal.signal(sig, lambda *_: stop.set())

    codec = EnvelopeCodec(settings.contracts_dir)
    processed = ProcessedMessages()
    state = HealthState(settings.worker_name)
    start_health_server(state, settings.health_port)
    log.info("document-analysis worker starting (broker %s:%d, health :%d)", settings.host, settings.port,
             settings.health_port)

    delay = 1.0
    while not stop.is_set():
        try:
            run_session(settings, lambda publisher: MessageProcessor(codec, publisher, processed), state, stop)
            delay = 1.0
        except (AMQPError, OSError) as exc:
            log.warning("broker session ended (%s); reconnecting in %.0fs", exc.__class__.__name__, delay)
            state.heartbeat()
            stop.wait(delay)
            delay = min(delay * 2, 30.0)
    log.info("worker stopped")
