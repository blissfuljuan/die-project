"""Business handlers. Pure functions over the envelope payload: no I/O, nothing from the message is ever executed."""
from __future__ import annotations

import hashlib
from typing import Any

from .envelope import Envelope

WORKER = "document-analysis"
ANALYZE = "test.analyze"
ANALYZE_RESULT = "test.analyze.result"


class SimulatedFailure(Exception):
    """Raised on request (payload.simulateFailure) so retry exhaustion and the DLQ can be demonstrated end to end."""


def handle_analyze(envelope: Envelope) -> dict[str, Any]:
    """P0-VS04 connectivity job: answer 'pong' with a deterministic fingerprint of the note. No document parsing yet."""
    payload = envelope.payload
    if payload.get("simulateFailure"):
        raise SimulatedFailure("simulateFailure was requested for this job")
    note = payload.get("note") or ""
    return {
        "testJobId": payload["testJobId"],
        "outcome": "SUCCEEDED",
        "worker": WORKER,
        "result": {
            "pong": True,
            "noteLength": len(note),
            "noteSha256": hashlib.sha256(note.encode("utf-8")).hexdigest() if note else None,
        },
    }
