"""The shared contract: the same schema and example corpus the Java API and the Java worker test against."""
from __future__ import annotations

import pytest

from conftest import CONTRACTS, load
from die_document_worker.envelope import InvalidEnvelope

VALID = sorted((CONTRACTS / "examples" / "valid").glob("*.json"))
INVALID = sorted((CONTRACTS / "examples" / "invalid").glob("*.json"))


def test_the_corpus_is_present():
    assert len(VALID) >= 6
    assert len(INVALID) >= 8


@pytest.mark.parametrize("path", VALID, ids=lambda p: p.name)
def test_valid_examples_are_accepted(codec, path):
    assert codec.problems(load(path)) == []
    envelope = codec.decode(path.read_bytes())
    assert envelope.envelope_version == 1


@pytest.mark.parametrize("path", INVALID, ids=lambda p: p.name)
def test_invalid_examples_are_rejected(codec, path):
    assert codec.problems(load(path)) != []
    with pytest.raises(InvalidEnvelope):
        codec.decode(path.read_bytes())


@pytest.mark.parametrize("body", [b"{not json", b"\xff\x00", b"[]", b"null"])
def test_garbage_is_an_invalid_envelope_not_a_crash(codec, body):
    with pytest.raises(InvalidEnvelope):
        codec.decode(body)


def test_encoded_envelopes_round_trip(codec):
    from conftest import analyze_envelope

    body = analyze_envelope(codec)
    assert codec.encode(codec.decode(body)) == body


def test_an_envelope_that_breaks_the_schema_cannot_be_encoded(codec):
    from die_document_worker.envelope import Envelope

    bad = Envelope(message_id="x", correlation_id="req-20261018-0001", type="test.analyze",
                   created_at="2026-10-18T09:30:00.123Z", attempt=1, payload={})
    with pytest.raises(ValueError):
        codec.encode(bad)
