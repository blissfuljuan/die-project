"""Bounded memory of messages that were fully handled (duplicate delivery safety)."""
from __future__ import annotations

import threading
from collections import OrderedDict


class ProcessedMessages:
    """In-memory LRU of handled messageIds.

    Enough for this stateless slice: after a restart a duplicate is processed again, and the result is identical
    (deterministic derived ids), so the API inbox drops it. A durable store arrives with the first stage that has
    side effects.
    """

    def __init__(self, capacity: int = 10_000):
        self._capacity = capacity
        self._seen: OrderedDict[str, None] = OrderedDict()
        self._lock = threading.Lock()

    def seen(self, message_id: str) -> bool:
        with self._lock:
            if message_id in self._seen:
                self._seen.move_to_end(message_id)
                return True
            return False

    def remember(self, message_id: str) -> None:
        with self._lock:
            self._seen[message_id] = None
            self._seen.move_to_end(message_id)
            while len(self._seen) > self._capacity:
                self._seen.popitem(last=False)
