"""The versioned message envelope (contracts/messaging/envelope-v1.schema.json, docs/messaging.md)."""
from __future__ import annotations

import json
import uuid
from dataclasses import dataclass, replace
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from jsonschema import Draft202012Validator

SCHEMA_FILE = "envelope-v1.schema.json"
# Namespace for deterministic ids of messages this worker derives from another one.
_DERIVED_NS = uuid.UUID("6f1c2d1e-8a0b-4c53-9d58-0e9b6d7f3a11")


class InvalidEnvelope(Exception):
    """The bytes are not a valid envelope. Never retried: goes to the DLQ."""

    def __init__(self, message: str, problems: list[str] | None = None):
        super().__init__(message)
        self.problems = problems or []


@dataclass(frozen=True)
class Envelope:
    message_id: str
    correlation_id: str
    type: str
    created_at: str
    attempt: int
    payload: dict[str, Any]
    envelope_version: int = 1

    def next_attempt(self) -> "Envelope":
        return replace(self, attempt=self.attempt + 1)

    def to_json(self) -> str:
        return json.dumps(
            {
                "envelopeVersion": self.envelope_version,
                "messageId": self.message_id,
                "correlationId": self.correlation_id,
                "type": self.type,
                "createdAt": self.created_at,
                "attempt": self.attempt,
                "payload": self.payload,
            },
            separators=(",", ":"),
            ensure_ascii=False,
        )


def now_utc() -> str:
    t = datetime.now(timezone.utc)
    return t.strftime("%Y-%m-%dT%H:%M:%S.") + f"{t.microsecond // 1000:03d}Z"


def derived_message_id(parent_message_id: str, step: str) -> str:
    """Same parent and step give the same id, so a redelivered input yields output that downstream consumers recognise as a duplicate."""
    return str(uuid.uuid5(_DERIVED_NS, f"{parent_message_id}|{step}"))


class EnvelopeCodec:
    def __init__(self, contracts_dir: Path):
        schema = json.loads((Path(contracts_dir) / SCHEMA_FILE).read_text(encoding="utf-8"))
        Draft202012Validator.check_schema(schema)
        self._validator = Draft202012Validator(schema)

    def problems(self, document: Any) -> list[str]:
        return sorted(
            f"{'/'.join(map(str, e.absolute_path)) or '<root>'}: {e.message}" for e in self._validator.iter_errors(document)
        )

    def decode(self, body: bytes) -> Envelope:
        try:
            document = json.loads(body.decode("utf-8"))
        except (UnicodeDecodeError, ValueError) as exc:
            raise InvalidEnvelope("message body is not valid UTF-8 JSON") from exc
        problems = self.problems(document)
        if problems:
            raise InvalidEnvelope("message violates the envelope schema", problems)
        return Envelope(
            envelope_version=document["envelopeVersion"],
            message_id=document["messageId"],
            correlation_id=document["correlationId"],
            type=document["type"],
            created_at=document["createdAt"],
            attempt=document["attempt"],
            payload=document["payload"],
        )

    def encode(self, envelope: Envelope) -> bytes:
        text = envelope.to_json()
        problems = self.problems(json.loads(text))
        if problems:
            raise ValueError(f"envelope violates the v1 schema: {problems}")
        return text.encode("utf-8")
