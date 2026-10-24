# Messaging: RabbitMQ topology, message envelope, worker contract (P0-VS04)

Source of truth for every asynchronous hop between the API, the Java orchestration worker and the Python document worker.
Postgres holds the authoritative state of a job; the queue is only transport.

Files: `contracts/messaging/envelope-v1.schema.json` (+ `examples/valid|invalid`, the shared test corpus),
`infra/rabbitmq/definitions.json` (topology), `infra/rabbitmq/die-entrypoint.sh` (imports it at start).

## 1. Message envelope (version 1)

Every message body is one UTF-8 JSON document:

| Field | Type | Meaning |
|---|---|---|
| `envelopeVersion` | `1` | Schema version. Anything else is invalid and goes to the DLQ. |
| `messageId` | UUID (lower case) | Identity of the message. Kept across redelivery and retry. The consumers' dedupe key. |
| `correlationId` | `[A-Za-z0-9._-]{8,64}` | The originating API request's `X-Request-Id`. Copied unchanged into every derived message and every log line (`correlationId` MDC / JSON field). |
| `type` | dotted lower-case name | e.g. `test.ping`. Selects the payload schema. |
| `createdAt` | UTC instant `...Z` | Creation time of the first publication. |
| `attempt` | integer 1..10 | Delivery attempt. A consumer that retries republishes the message with `attempt + 1`. |
| `payload` | object | Type specific, validated by the same schema (`additionalProperties: false`). |

AMQP properties mirror the envelope for diagnostics (`message_id`, `correlation_id`, `type`, `delivery_mode = 2`,
`content_type = application/json`); consumers trust the body, not the properties.

**Versioning.** `envelope-v1.schema.json` never changes incompatibly. A breaking change creates `envelope-v2.schema.json` and a
new `envelopeVersion`; producers and consumers are deployed so that v2 consumers exist before v2 producers. A new message
type or an optional payload field is added to v1 only together with the examples in `contracts/messaging/examples`.
The Java API, the Java worker and the Python worker each validate every inbound and outbound message against this schema
and each run the same example corpus in their tests (`EnvelopeContractTest`, `test_contract.py`).

**Derived ids.** A message produced while handling another gets a deterministic id: `uuid(parentMessageId | step)`
(Java: `UUID.nameUUIDFromBytes`, Python: `uuid5`; algorithms are per sender, never compared across languages). A redelivered
input therefore yields output the next consumer recognises as a duplicate.

### Message types (P0-VS04)

| Type | From -> to | Payload |
|---|---|---|
| `test.ping` | API -> orchestrator | `testJobId`, `note?`, `simulateFailure?` |
| `test.progress` | orchestrator -> API | `testJobId`, `stage`, `worker` (moves the job QUEUED -> RUNNING) |
| `test.analyze` | orchestrator -> document worker | same as `test.ping` |
| `test.analyze.result` | document worker -> orchestrator | `testJobId`, `outcome` SUCCEEDED/FAILED, `worker`, `result{pong,noteLength,noteSha256}` or `reason` |
| `test.result` | orchestrator -> API | `testJobId`, `outcome`, `worker`, `workers[]`, `result?`, `reason?` (moves the job to SUCCEEDED/FAILED) |

`simulateFailure` is an operations hook: the document worker fails every attempt so retry exhaustion and the DLQ can be demonstrated.

## 2. Topology

```
API --outbox--> [die.test]/die.test.jobs ---------> orchestrator
orchestrator --> [die.test]/die.test.results         (test.progress)           -> API result consumer
orchestrator --> [die.test]/die.test.analyze ------> document worker
document worker --> [die.test]/die.test.analyze.results -> orchestrator
orchestrator --> [die.test]/die.test.results         (test.result)             -> API result consumer
```

**Naming convention.** For a work queue `Q` in domain `D` (the first two segments of the queue name, here `die.test`):

| Object | Name | Properties |
|---|---|---|
| work exchange | `D` | direct, durable; routing key = `Q` |
| work queue | `Q` | durable **quorum** queue, `x-delivery-limit: 5` (a crash-looping consumer cannot redeliver forever), dead-letters to `D.dlx` / `Q` |
| retry exchange | `D.retry` | direct, durable; routing key `Q.retry.N` |
| retry queues | `Q.retry.1` (5 s TTL), `Q.retry.2` (30 s TTL) | durable classic; expiry dead-letters back to `D` / `Q` (that is the backoff) |
| dead-letter exchange | `D.dlx` | direct, durable; routing key = `Q` |
| dead-letter queue | `Q.dlq` | durable quorum; kept for an operator, never consumed automatically |

Existing `evaluation.*` / `document.*` objects are unchanged. All messages are published persistent (`delivery_mode = 2`),
with `mandatory` set (an unroutable message is an error, not a silent drop).

## 3. Delivery rules (identical in the API, the Java worker and the Python worker)

* **Publish**: persistent + mandatory on a confirm channel; the call returns only after the broker's ack. `basic.return` or a nack is a refusal; a connection error or timeout is "unavailable".
* **Consume**: manual acks, a prefetch limit (10 in the API, 5 in the workers), one connection per process.
* **Per delivery**
  1. Not a valid envelope (not JSON, schema violation, unknown version) -> publish the raw bytes to `D.dlx` with `x-die-failure-reason`, ack.
  2. `(queue, messageId)` already handled -> ack, publish nothing.
  3. Handled -> publish every output (confirmed), remember the id (API: `inbox_message` row in the handler's transaction), ack. Outputs are published **before** the ack, so a crash in between causes a redelivery, never a lost job.
  4. Permanent failure (unsupported type, unknown job) -> give-up notices + `D.dlx`, ack.
  5. Other failure -> if `attempt < 3`: republish to `D.retry` / `Q.retry.<attempt>` with `attempt + 1`, ack (after the confirm). Otherwise give-up notices (a `test.result` FAILED so the job does not stay RUNNING) + `D.dlx`, ack.
  6. The outcome itself cannot be published (broker trouble) -> nack with requeue (`basic_nack`), or simply lose the connection; the broker redelivers.
* **Reconnect**: nothing assumes a connection stays up. The API publisher recreates its channel on the next message; every consumer has a watchdog (2 s in Java, a reconnect loop with 1..30 s backoff in Python) that re-subscribes. amqp-client automatic recovery is deliberately off (stale delivery tags after recovery would close the channel).
* **Idempotency**: duplicate delivery is expected (broker redelivery, relay republication after a crash, retry after a late ack). The API dedupes durably (`inbox_message(consumer, message_id)` in the same transaction as the state change, plus guarded state transitions); the workers dedupe in a bounded in-memory LRU and rely on deterministic derived ids for the rest.

## 4. Transactional outbox (API)

`OutboxService.enqueue` (propagation MANDATORY) inserts an `outbox_event` row in the caller's transaction; its `payload` is the full
envelope and its `id` is the `messageId`. `OutboxRelay.relayOnce` (run every 500 ms by `OutboxRelayRunner`, disable with
`die.messaging.relay.enabled=false`):

1. leases due rows in a short transaction (`FOR UPDATE SKIP LOCKED`, `locked_until = now() + 30 s`);
2. publishes each with a confirm, **outside any transaction**;
3. marks the row `PUBLISHED`. On failure it stays `PENDING` with `next_attempt_at` backoff (`min(30 s, 2^attempts)`). A broker that is unreachable never exhausts a row; after 10 *refusals* (nack/unroutable) the row is parked as `FAILED` for an operator.

A crash between the confirm and step 3 republishes the row after its lease expires; consumers dedupe on the unchanged `messageId`.
`die_app` has `SELECT, INSERT, UPDATE` on `outbox_event` and `test_job`, `SELECT, INSERT` on `inbox_message` (V8).

## 5. Operations endpoint

`POST /api/v1/ops/test-jobs` (ADMIN, CSRF) body `{ "note"?: string(<=200), "simulateFailure"?: boolean }` -> `202` with the job (`state: QUEUED`) and a `Location`.
`GET /api/v1/ops/test-jobs/{id}` -> state `QUEUED | RUNNING | SUCCEEDED | FAILED`, `result`, `failureReason`, timestamps, `correlationId`.
This is infrastructure, not project content (no class/project scoping); it is audited (`TEST_JOB_SUBMITTED`).
A job whose `test.result` never arrives (for example a poison message dead-lettered before its job id could be read) is failed by the stale-job reaper (`ops.service.StaleJobReaper`, P0-VS05) once it has been `QUEUED`/`RUNNING` for `die.ops.reaper.stale-after-seconds` (900); a late result is ignored. Real evaluation jobs (P3) get leases and fencing instead. `GET /api/v1/ops/health` (ADMIN) reports broker, worker heartbeats, outbox backlog and queue/DLQ depths; operations: `docs/ops/`.

## 6. Workers

| | Orchestrator (`workers/orchestrator`) | Document worker (`workers/document-analysis`) |
|---|---|---|
| Language / client | Java 21, `amqp-client`, Jackson, networknt JSON Schema | Python 3.12, `pika`, `jsonschema` |
| Consumes | `die.test.jobs`, `die.test.analyze.results` | `die.test.analyze` |
| Health | `GET :8081/health/live` and `/health/ready` | `GET :8082/health/live` and `/health/ready` |
| Heartbeat | AMQP heartbeat (30 s) + a structured `heartbeat` log line every 30 s; `/health/live` turns 503 after 90 s of silence; `/health/ready` is 200 only while every consumer is subscribed | same (idle ticks every second update the heartbeat) |
| Logs | JSON on stdout (logstash encoder), MDC: `correlationId`, `messageId`, `messageType` | JSON on stdout, same field names |
| Container | non-root uid 10001, read-only root FS, `/tmp` tmpfs `noexec`, `cap_drop: ALL`, `no-new-privileges`, pids/memory/cpu limits, only on the **internal** `broker` network (no route to the internet) | same |

Neither worker parses, opens or executes message content beyond JSON validation. Document parsing arrives in P2 and must keep that property.

## 7. Configuration

API: `DIE_MESSAGING_ENABLED` (default `true`; tests that need no broker set `false`), `RABBITMQ_HOST`, `RABBITMQ_PORT`, `RABBITMQ_USER`, `RABBITMQ_PASSWORD`
(+ `die.messaging.*` in `application.yml`). Workers: `RABBITMQ_HOST/PORT/USER/PASSWORD/VHOST`, `HEALTH_PORT`, `DIE_PREFETCH`, `LOG_LEVEL`.
The broker's first start imports the topology through `die-entrypoint.sh` (idempotent). `load_definitions` in `rabbitmq.conf` cannot be used: a node that loads
definitions at boot does not seed `RABBITMQ_DEFAULT_USER`, so nobody could log in.

## 8. Tests

* Contract corpus: `EnvelopeContractTest` (API, Java worker), `tests/test_contract.py` (Python worker).
* API: `RabbitInboundConsumerTest` (settlement decisions), `MessagingIntegrationTest` (real Postgres + RabbitMQ: outbox -> broker -> simulated worker -> `SUCCEEDED`, duplicate and contradicting results, poison, unknown job, retry tier + recovery, retry exhaustion -> DLQ, broker stopped/started with relay and consumer reconnect).
* Java worker: `QueueWorkerTest`, `TestJobHandlersTest`, `OrchestratorBrokerIT` (real RabbitMQ: round trip, duplicate, worker dying mid-job, retry exhaustion, poison, reconnect).
* Python worker: `tests/` (`pytest`; `test_broker.py` needs `RABBITMQ_TEST_URL`).
