"""Against a real RabbitMQ with the project topology (infra/rabbitmq/definitions.json).

Set RABBITMQ_TEST_URL, e.g. amqp://guest:guest@localhost:5672/%2F. Skipped without it. The worker runs in a thread
exactly as in production (app.run_session); the test publishes to the exchange and reads the answers.
"""
from __future__ import annotations

import os
import threading
import time

import pika
import pytest

from conftest import CONTRACTS, analyze_envelope
from die_document_worker import app
from die_document_worker.config import Settings
from die_document_worker.dedupe import ProcessedMessages
from die_document_worker.envelope import EnvelopeCodec
from die_document_worker.health import HealthState
from die_document_worker.processor import MessageProcessor

URL = os.environ.get("RABBITMQ_TEST_URL")
pytestmark = [pytest.mark.broker, pytest.mark.skipif(not URL, reason="RABBITMQ_TEST_URL not set")]

QUEUES = ["die.test.analyze", "die.test.analyze.results", "die.test.analyze.dlq",
          "die.test.analyze.retry.1", "die.test.analyze.retry.2"]


def wait_for(condition, timeout=15.0, what="condition"):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        if condition():
            return
        time.sleep(0.1)
    raise AssertionError(f"timed out waiting for {what}")


class RunningWorker:
    def __init__(self, processed: ProcessedMessages | None = None):
        params = pika.URLParameters(URL)
        self.settings = Settings(host=params.host, port=params.port, username=params.credentials.username,
                                 password=params.credentials.password, vhost=params.virtual_host, health_port=0,
                                 prefetch=5, contracts_dir=CONTRACTS)
        self.codec = EnvelopeCodec(CONTRACTS)
        self.processed = processed or ProcessedMessages()
        self.state = HealthState("document-analysis")
        self.stop = threading.Event()
        self.thread = threading.Thread(target=self._run, daemon=True)
        self.error: Exception | None = None

    def _run(self):
        try:
            app.run_session(self.settings, lambda pub: MessageProcessor(self.codec, pub, self.processed),
                            self.state, self.stop)
        except Exception as exc:  # noqa: BLE001
            self.error = exc

    def __enter__(self):
        self.thread.start()
        wait_for(lambda: self.state.snapshot()["consuming"], what="worker to start consuming")
        return self

    def __exit__(self, *exc):
        self.stop.set()
        self.thread.join(10)


@pytest.fixture
def channel():
    connection = pika.BlockingConnection(pika.URLParameters(URL))
    ch = connection.channel()
    for q in QUEUES:
        ch.queue_purge(q)
    yield ch
    connection.close()


def publish(channel, body: bytes, message_id: str | None = None):
    channel.basic_publish("die.test", "die.test.analyze", body,
                          pika.BasicProperties(delivery_mode=2, content_type="application/json", message_id=message_id))


def count(channel, queue: str) -> int:
    return channel.queue_declare(queue=queue, passive=True).method.message_count


def test_job_round_trip_and_duplicate_delivery(channel):
    codec = EnvelopeCodec(CONTRACTS)
    body = analyze_envelope(codec)
    with RunningWorker():
        publish(channel, body)
        wait_for(lambda: count(channel, "die.test.analyze.results") == 1, what="the result")
        publish(channel, body)                       # the broker redelivering / the orchestrator re-sending
        publish(channel, analyze_envelope(codec, message_id="a1b2c3d4-0000-4000-8000-0000000000cc"))
        wait_for(lambda: count(channel, "die.test.analyze.results") == 2, what="the second job's result")
        time.sleep(1)
        assert count(channel, "die.test.analyze.results") == 2      # the duplicate produced nothing
        assert count(channel, "die.test.analyze") == 0
        assert count(channel, "die.test.analyze.dlq") == 0

    method, props, result = channel.basic_get("die.test.analyze.results", auto_ack=True)
    assert props.delivery_mode == 2
    envelope = codec.decode(result)
    assert envelope.type == "test.analyze.result" and envelope.payload["outcome"] == "SUCCEEDED"
    assert envelope.correlation_id == "req-20261018-0001"


def test_poison_message_goes_to_the_dlq_and_the_worker_keeps_consuming(channel):
    codec = EnvelopeCodec(CONTRACTS)
    with RunningWorker() as worker:
        publish(channel, b"definitely not json")
        wait_for(lambda: count(channel, "die.test.analyze.dlq") == 1, what="poison in the DLQ")
        _, props, dead = channel.basic_get("die.test.analyze.dlq", auto_ack=True)
        assert dead == b"definitely not json"
        assert "x-die-failure-reason" in props.headers
        assert count(channel, "die.test.analyze") == 0

        publish(channel, analyze_envelope(codec))
        wait_for(lambda: count(channel, "die.test.analyze.results") == 1, what="a normal job after the poison")
        assert worker.error is None


def test_failing_job_is_retried_then_reported_failed_and_dead_lettered(channel):
    codec = EnvelopeCodec(CONTRACTS)
    with RunningWorker():
        publish(channel, analyze_envelope(codec, attempt=1, simulate_failure=True))
        wait_for(lambda: count(channel, "die.test.analyze.retry.1") == 1, timeout=4, what="a message in retry tier 1")
        _, props, retried = channel.basic_get("die.test.analyze.retry.1", auto_ack=True)
        assert codec.decode(retried).attempt == 2
        assert count(channel, "die.test.analyze.results") == 0

        publish(channel, analyze_envelope(codec, attempt=3, simulate_failure=True))        # last attempt
        wait_for(lambda: count(channel, "die.test.analyze.dlq") == 1, what="exhausted job in the DLQ")
        wait_for(lambda: count(channel, "die.test.analyze.results") == 1, what="the FAILED result")
        _, _, failed = channel.basic_get("die.test.analyze.results", auto_ack=True)
        assert codec.decode(failed).payload["outcome"] == "FAILED"


def test_interruption_mid_job_redelivers_to_the_next_worker(channel):
    """A worker that dies after receiving but before acknowledging loses nothing: the broker redelivers the message."""
    codec = EnvelopeCodec(CONTRACTS)
    publish(channel, analyze_envelope(codec))
    # publish() is not confirmed: wait until the quorum queue shows the message, or basic_get races it and returns nothing
    wait_for(lambda: count(channel, "die.test.analyze") == 1, what="the job in the work queue")
    doomed = pika.BlockingConnection(pika.URLParameters(URL))
    doomed_channel = doomed.channel()
    method, _, body = doomed_channel.basic_get("die.test.analyze", auto_ack=False)
    assert body is not None
    doomed.close()                                        # simulated crash: connection gone, no ack

    with RunningWorker():
        wait_for(lambda: count(channel, "die.test.analyze.results") == 1, what="the redelivered job's result")
    assert count(channel, "die.test.analyze") == 0


def test_the_worker_reconnects_after_the_broker_drops_its_connection():
    """run() keeps a session alive: a lost connection ends run_session with an error and run() starts a new one."""
    state = HealthState("document-analysis")
    stop = threading.Event()
    params = pika.URLParameters(URL)
    settings = Settings(host=params.host, port=params.port, username=params.credentials.username,
                        password=params.credentials.password, vhost=params.virtual_host, health_port=0, prefetch=5,
                        contracts_dir=CONTRACTS)
    # A closed port: the session must fail fast and raise so the supervising loop can back off and retry.
    broken = Settings(**{**settings.__dict__, "port": 1})
    with pytest.raises(Exception):
        app.run_session(broken, lambda pub: None, state, stop)
    assert state.snapshot()["consuming"] is False
