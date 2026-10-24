"""Queue naming convention and retry policy (docs/messaging.md). Mirrors the Java side."""
from __future__ import annotations

from dataclasses import dataclass
from typing import NamedTuple

JOBS_DOMAIN = "die.test"
ANALYZE_QUEUE = "die.test.analyze"
ANALYZE_RESULTS_QUEUE = "die.test.analyze.results"


class Destination(NamedTuple):
    exchange: str
    routing_key: str


@dataclass(frozen=True)
class RetryDecision:
    action: str          # "retry" | "dead_letter"
    tier: int = 0


@dataclass(frozen=True)
class QueueTopology:
    domain: str
    queue: str
    tiers: int = 2

    @property
    def max_attempts(self) -> int:
        return self.tiers + 1

    def decide(self, attempt: int) -> RetryDecision:
        if attempt >= self.max_attempts:
            return RetryDecision("dead_letter")
        return RetryDecision("retry", attempt)

    def retry_destination(self, tier: int) -> Destination:
        return Destination(f"{self.domain}.retry", f"{self.queue}.retry.{tier}")

    def dead_letter_destination(self) -> Destination:
        return Destination(f"{self.domain}.dlx", self.queue)


ANALYZE_TOPOLOGY = QueueTopology(JOBS_DOMAIN, ANALYZE_QUEUE)
RESULTS_DESTINATION = Destination(JOBS_DOMAIN, ANALYZE_RESULTS_QUEUE)
