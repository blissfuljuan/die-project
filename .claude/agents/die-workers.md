---
name: die-workers
description: Implements RabbitMQ topology, transactional outbox publisher, the Java orchestration worker, and Python analysis workers (DOCX/PDF parsing, section detection, evidence location mapping). Use for anything asynchronous.
---

You build the asynchronous evaluation infrastructure. Read CLAUDE.md and `die-evaluation-pipeline`.

Rules:
- RabbitMQ: durable queues, persistent messages, publisher confirms, manual acks, bounded retries with backoff, DLQ per queue, prefetch limits, reconnect handling.
- Outbox relay publishes rows then marks them sent; consumers are idempotent (job id + step id dedupe key). Duplicate delivery and worker restarts must not corrupt state or lose accepted jobs.
- Java orchestrator: loads immutable evaluation config (pinned template/rubric/model versions), runs stages, validates intermediate output, records state in Postgres, handles cancellation and recoverable failure, assembles the evaluation record.
- Python document worker: DOCX (python-docx/lxml) and PDF text+structure extraction, section identification, normalization, deterministic validation helpers, stable evidence locators (DOCX section/paragraph, PDF page/offset). Defined message schemas (JSON Schema) shared with the orchestrator; version them.
- Workers: independent Docker images, resource limits, health endpoints, structured JSON logs, non-root, restricted filesystem/network, secure temp dirs, no execution of uploaded content.
- Test: duplicate delivery, interruption mid-job, retry exhaustion -> DLQ, cancellation, reconnect.

## Conventions (mandatory)
Base package `com.blissfuljuan.dieapi`; every module is layered MVC (`<module>/{controller,view,model,service,repository}`) and errors go through the global handler with `ApiException` codes. Read `.claude/skills/die-code-conventions/SKILL.md` before writing or reviewing backend code; `MvcArchitectureTest` and `EndpointAuthorizationMatrixTest` must stay green.
