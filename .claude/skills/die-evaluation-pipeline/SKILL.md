---
name: die-evaluation-pipeline
description: End-to-end async AI evaluation flow in DIE (outbox, RabbitMQ, Java orchestrator, Python workers, qualified model routing, evidence verification, SSE, recovery). Use when touching evaluation jobs, workers, messaging, prompts, or findings.
---

# Evaluation pipeline (P3; P2 reuses the same outbox/RabbitMQ/Python backbone for intake validation)

1. An authorized Coordinator POSTs an evaluation. The API checks eligibility (valid artifact, SDLC prerequisites, no unresolved Blocking revision request, qualified route), resolves and pins artifact version + rubric snapshot + model/route + prompt version, and in ONE transaction inserts the evaluation attempt (QUEUED) and an outbox row. Idempotent by key; returns 202 + id.
2. Outbox relay publishes to RabbitMQ (durable queue, persistent message, publisher confirm), marks the row sent.
3. Java orchestrator consumes (manual ack), takes a **lease** with a fencing token, sets RUNNING, emits persisted progress events (SSE from the API, REST polling to reconcile). Workers heartbeat; stale leases are reclaimed.
4. Python worker (`document.analyze`): fetch artifact via backend-mediated access, verify checksum, parse DOCX, return normalized sections + evidence locators. Untrusted content is never executed.
5. Orchestrator runs deterministic checks, then model tasks through the provider abstraction using only **qualified** providers/models for the capability; structured JSON output.
6. Schema validation + evidence verifier (quote/locator must exist). Citation states: verified / partially supported / not verified / contradicted / not applicable; invalid citations flagged or excluded.
7. Persist findings append-only with original AI output, execution provenance, limitations, suggested scores, token/cost log; state COMPLETED_INTERNAL; notify the initiating Coordinator. Nothing is visible to Participants.
8. Failures: bounded, audited retries -> DLQ -> FAILED with reason; **provider fallback only as a distinct new attempt**; stale/failed attempts go through controlled reconciliation, quarantine and operator incident handling. Postgres is the source of truth, never the queue. Steps are idempotent per (attempt, step).

Review/approval/publication follow in Human Review and Approval modules (independent reviewers, adjudication, quorum, explicit publication); this pipeline never approves or publishes anything.

Do not: call models in API threads, auto-trigger on Participant submission (deterministic intake validation only), execute uploaded content, send secrets or unapproved data to providers (provider data-handling policy must permit it).
