"""Settlement decisions for one delivery, without a broker."""
from __future__ import annotations

import hashlib

from conftest import analyze_envelope
from die_document_worker.dedupe import ProcessedMessages
from die_document_worker.processor import MessageProcessor, Settlement

JOB = "3f2b8c1e-5d4a-4e7b-9a60-1c2d3e4f5a6b"


def test_success_publishes_one_result_with_the_same_correlation_id(processor, publisher, codec):
    settlement = processor.process(analyze_envelope(codec, note="hello"))

    assert settlement is Settlement.ACK
    [(destination, body, meta)] = publisher.sent
    assert (destination.exchange, destination.routing_key) == ("die.test", "die.test.analyze.results")
    result = codec.decode(body)
    assert result.type == "test.analyze.result"
    assert result.correlation_id == "req-20261018-0001"
    assert result.attempt == 1
    assert result.payload["testJobId"] == JOB
    assert result.payload["outcome"] == "SUCCEEDED"
    assert result.payload["result"] == {"pong": True, "noteLength": 5,
                                        "noteSha256": hashlib.sha256(b"hello").hexdigest()}
    assert meta["message_id"] == result.message_id


def test_a_job_without_a_note_still_gets_a_pong(processor, publisher, codec):
    processor.process(analyze_envelope(codec, note=None))
    result = codec.decode(publisher.sent[0][1])
    assert result.payload["result"] == {"pong": True, "noteLength": 0, "noteSha256": None}


def test_duplicate_delivery_is_acknowledged_without_a_second_result(processor, publisher, codec):
    body = analyze_envelope(codec)
    assert processor.process(body) is Settlement.ACK
    assert processor.process(body) is Settlement.ACK
    assert len(publisher.sent) == 1


def test_redelivery_after_a_crash_yields_the_same_result_id(codec, publisher):
    """A restarted worker has no memory of the message, but its answer is recognisable as the same message downstream."""
    body = analyze_envelope(codec)
    MessageProcessor(codec, publisher, ProcessedMessages()).process(body)
    MessageProcessor(codec, publisher, ProcessedMessages()).process(body)      # fresh worker, same delivery
    first, second = (codec.decode(sent[1]) for sent in publisher.sent)
    assert first.message_id == second.message_id


def test_poison_bytes_are_dead_lettered_unchanged(processor, publisher):
    poison = b"this is not an envelope"
    assert processor.process(poison) is Settlement.ACK
    [(destination, body, meta)] = publisher.sent
    assert (destination.exchange, destination.routing_key) == ("die.test.dlx", "die.test.analyze")
    assert body == poison
    assert "invalid envelope" in meta["headers"]["x-die-failure-reason"]
    assert meta["headers"]["x-die-source-queue"] == "die.test.analyze"


def test_an_unsupported_type_is_dead_lettered_without_retries(processor, publisher, codec):
    from die_document_worker.envelope import Envelope

    body = codec.encode(Envelope(message_id="a1b2c3d4-0000-4000-8000-0000000000bb", correlation_id="req-20261018-0001",
                                 type="test.unknown", created_at="2026-10-18T09:30:00.123Z", attempt=1, payload={"x": 1}))
    assert processor.process(body) is Settlement.ACK
    [(destination, _, meta)] = publisher.sent
    assert destination.exchange == "die.test.dlx"
    assert "unsupported message type" in meta["headers"]["x-die-failure-reason"]


def test_first_failure_goes_to_retry_tier_one_with_the_next_attempt(processor, publisher, codec):
    assert processor.process(analyze_envelope(codec, simulate_failure=True)) is Settlement.ACK
    [(destination, body, _)] = publisher.sent
    assert (destination.exchange, destination.routing_key) == ("die.test.retry", "die.test.analyze.retry.1")
    retried = codec.decode(body)
    assert retried.attempt == 2
    assert retried.message_id == "a1b2c3d4-0000-4000-8000-0000000000aa"      # identity is kept across retries


def test_second_failure_goes_to_tier_two(processor, publisher, codec):
    processor.process(analyze_envelope(codec, attempt=2, simulate_failure=True))
    assert publisher.sent[0][0].routing_key == "die.test.analyze.retry.2"


def test_retry_exhaustion_reports_failure_then_dead_letters(processor, publisher, codec):
    original = analyze_envelope(codec, attempt=3, simulate_failure=True)
    assert processor.process(original) is Settlement.ACK
    [(first, first_body, _), (second, second_body, meta)] = publisher.sent
    failed = codec.decode(first_body)
    assert (first.exchange, first.routing_key) == ("die.test", "die.test.analyze.results")
    assert failed.payload["outcome"] == "FAILED"
    assert failed.payload["testJobId"] == JOB
    assert "retries exhausted" in failed.payload["reason"]
    assert (second.exchange, second.routing_key) == ("die.test.dlx", "die.test.analyze")
    assert second_body == original
    assert "retries exhausted" in meta["headers"]["x-die-failure-reason"]


def test_a_failed_attempt_is_not_remembered_so_a_later_attempt_can_succeed(processor, publisher, codec):
    processor.process(analyze_envelope(codec, simulate_failure=True))
    publisher.sent.clear()
    assert processor.process(analyze_envelope(codec, attempt=2, simulate_failure=False)) is Settlement.ACK
    assert codec.decode(publisher.sent[0][1]).payload["outcome"] == "SUCCEEDED"


def test_if_the_result_cannot_be_published_the_message_is_left_for_redelivery(processor, publisher, codec):
    publisher.down = True
    assert processor.process(analyze_envelope(codec)) is Settlement.REQUEUE
    publisher.down = False
    assert processor.process(analyze_envelope(codec)) is Settlement.ACK          # redelivered later: processed, not skipped
    assert len(publisher.sent) == 1


def test_if_a_failure_cannot_be_routed_the_message_is_left_for_redelivery(processor, publisher, codec):
    publisher.down = True
    assert processor.process(analyze_envelope(codec, simulate_failure=True)) is Settlement.REQUEUE
    assert processor.process(b"poison") is Settlement.REQUEUE
