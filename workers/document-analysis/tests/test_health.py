from __future__ import annotations

import json
import urllib.error
import urllib.request

import pytest

from die_document_worker import health
from die_document_worker.dedupe import ProcessedMessages


def get(server, path):
    port = server.server_address[1]
    try:
        with urllib.request.urlopen(f"http://127.0.0.1:{port}{path}", timeout=3) as response:
            return response.status, json.loads(response.read())
    except urllib.error.HTTPError as err:
        return err.code, (json.loads(err.read()) if err.code != 404 else None)


@pytest.fixture
def server_and_state():
    state = health.HealthState("document-analysis")
    server = health.start_health_server(state, 0)
    yield server, state
    server.shutdown()


def test_live_but_not_ready_until_consuming(server_and_state):
    server, state = server_and_state
    assert get(server, "/health/live")[0] == 200
    status, body = get(server, "/health/ready")
    assert status == 503 and body["status"] == "DOWN"
    state.set_consuming(True)
    status, body = get(server, "/health/ready")
    assert status == 200 and body["consuming"] is True


def test_a_stalled_heartbeat_makes_the_worker_not_live(server_and_state, monkeypatch):
    server, state = server_and_state
    state.set_consuming(True)
    monkeypatch.setattr(health, "LIVENESS_GRACE_SECONDS", -1)
    assert get(server, "/health/live")[0] == 503


def test_unknown_paths_are_404(server_and_state):
    assert get(server_and_state[0], "/admin")[0] == 404


def test_processed_message_memory_is_bounded():
    memory = ProcessedMessages(capacity=2)
    for message_id in ("a", "b", "c"):
        memory.remember(message_id)
    assert not memory.seen("a")
    assert memory.seen("b") and memory.seen("c")
