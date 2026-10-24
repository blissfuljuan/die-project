from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import pytest

from die_document_worker.dedupe import ProcessedMessages
from die_document_worker.envelope import Envelope, EnvelopeCodec
from die_document_worker.processor import MessageProcessor, PublishError
from die_document_worker.topology import Destination

REPO = Path(__file__).resolve().parents[3]
CONTRACTS = REPO / "contracts" / "messaging"


@pytest.fixture(scope="session")
def codec() -> EnvelopeCodec:
    return EnvelopeCodec(CONTRACTS)


class FakePublisher:
    """Records confirmed publications; can be told that the broker is down."""

    def __init__(self) -> None:
        self.sent: list[tuple[Destination, bytes, dict[str, Any]]] = []
        self.down = False

    def publish(self, destination, body, *, message_id=None, correlation_id=None, type=None, headers=None) -> None:
        if self.down:
            raise PublishError("broker unavailable")
        self.sent.append((destination, body, {"message_id": message_id, "correlation_id": correlation_id,
                                              "type": type, "headers": headers or {}}))


@pytest.fixture
def publisher() -> FakePublisher:
    return FakePublisher()


@pytest.fixture
def processor(codec, publisher) -> MessageProcessor:
    return MessageProcessor(codec, publisher, ProcessedMessages())


def analyze_envelope(codec: EnvelopeCodec, *, message_id: str = "a1b2c3d4-0000-4000-8000-0000000000aa", attempt: int = 1,
                     simulate_failure: bool = False, note: str | None = "hello") -> bytes:
    return codec.encode(Envelope(
        message_id=message_id, correlation_id="req-20261018-0001", type="test.analyze",
        created_at="2026-10-18T09:30:00.123Z", attempt=attempt,
        payload={"testJobId": "3f2b8c1e-5d4a-4e7b-9a60-1c2d3e4f5a6b", "note": note, "simulateFailure": simulate_failure}))


def decode(codec: EnvelopeCodec, body: bytes) -> Envelope:
    return codec.decode(body)


def load(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))
