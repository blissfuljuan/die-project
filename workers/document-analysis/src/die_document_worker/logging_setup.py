"""Structured JSON logs on stdout. correlationId / messageId are attached to every line while a message is handled."""
from __future__ import annotations

import contextvars
import json
import logging
import sys
from datetime import datetime, timezone

_context: contextvars.ContextVar[dict[str, str]] = contextvars.ContextVar("log_context", default={})


def bind(**fields: str) -> contextvars.Token:
    return _context.set({**_context.get(), **fields})


def unbind(token: contextvars.Token) -> None:
    _context.reset(token)


class JsonFormatter(logging.Formatter):
    def format(self, record: logging.LogRecord) -> str:
        entry = {
            "timestamp": datetime.fromtimestamp(record.created, timezone.utc).isoformat(timespec="milliseconds"),
            "level": record.levelname,
            "logger": record.name,
            "service": "document-analysis",
            "message": record.getMessage(),
            **_context.get(),
        }
        if record.exc_info:
            entry["exception"] = self.formatException(record.exc_info)
        return json.dumps(entry, ensure_ascii=False)


def configure(level: str = "INFO") -> None:
    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(JsonFormatter())
    root = logging.getLogger()
    root.handlers[:] = [handler]
    root.setLevel(level)
    logging.getLogger("pika").setLevel(logging.WARNING)
