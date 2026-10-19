"""Health endpoints (liveness / readiness) and the worker heartbeat state."""
from __future__ import annotations

import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

LIVENESS_GRACE_SECONDS = 90


class HealthState:
    """Written by the consumer loop, read by the HTTP thread."""

    def __init__(self, worker: str):
        self.worker = worker
        self._lock = threading.Lock()
        self._consuming = False
        self._last_heartbeat = time.monotonic()
        self._handled = 0

    def heartbeat(self) -> None:
        with self._lock:
            self._last_heartbeat = time.monotonic()

    def set_consuming(self, value: bool) -> None:
        with self._lock:
            self._consuming = value
            self._last_heartbeat = time.monotonic()

    def message_handled(self) -> None:
        with self._lock:
            self._handled += 1

    def snapshot(self) -> dict:
        with self._lock:
            age = time.monotonic() - self._last_heartbeat
            return {
                "worker": self.worker,
                "consuming": self._consuming,
                "secondsSinceHeartbeat": round(age, 1),
                "messagesHandled": self._handled,
                "live": age < LIVENESS_GRACE_SECONDS,
            }


def start_health_server(state: HealthState, port: int) -> ThreadingHTTPServer:
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self) -> None:  # noqa: N802 - stdlib naming
            snap = state.snapshot()
            if self.path in ("/health", "/health/live"):
                code = 200 if snap["live"] else 503
            elif self.path == "/health/ready":
                code = 200 if snap["live"] and snap["consuming"] else 503
            else:
                self.send_error(404)
                return
            body = json.dumps({"status": "UP" if code == 200 else "DOWN", **snap}).encode()
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *args) -> None:  # keep stdout for structured logs
            return

    server = ThreadingHTTPServer(("0.0.0.0", port), Handler)
    threading.Thread(target=server.serve_forever, name="health", daemon=True).start()
    return server
