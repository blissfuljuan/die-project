"""Worker settings, read from the environment (12-factor). No secrets have defaults."""
from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path


def _default_contracts_dir() -> Path:
    # Docker image: /app/contracts (DIE_CONTRACTS_DIR). Repository checkout: <repo>/contracts/messaging.
    here = Path(__file__).resolve()
    for parent in here.parents:
        candidate = parent / "contracts" / "messaging"
        if candidate.is_dir():
            return candidate
    return Path("/app/contracts")


@dataclass(frozen=True)
class Settings:
    host: str
    port: int
    username: str
    password: str
    vhost: str
    health_port: int
    prefetch: int
    contracts_dir: Path
    worker_name: str = "document-analysis"
    log_level: str = "INFO"

    @staticmethod
    def from_env(env: dict[str, str] | None = None) -> "Settings":
        e = os.environ if env is None else env
        return Settings(
            host=e.get("RABBITMQ_HOST", "localhost"),
            port=int(e.get("RABBITMQ_PORT", "5672")),
            username=e.get("RABBITMQ_USER", ""),
            password=e.get("RABBITMQ_PASSWORD", ""),
            vhost=e.get("RABBITMQ_VHOST", "/"),
            health_port=int(e.get("HEALTH_PORT", "8082")),
            prefetch=int(e.get("DIE_PREFETCH", "5")),
            contracts_dir=Path(e["DIE_CONTRACTS_DIR"]) if e.get("DIE_CONTRACTS_DIR") else _default_contracts_dir(),
            log_level=e.get("LOG_LEVEL", "INFO").upper(),
        )
