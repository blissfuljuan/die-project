# Deployment (P0-VS05)

How DIE runs locally (Docker Compose) and in production (Render + Supabase). Companion documents: [recovery.md](recovery.md) (backup, restore, incidents),
[smoke-test.md](smoke-test.md) (post-deploy check), [../messaging.md](../messaging.md) (queues and workers).

Status honesty: this slice is **Implemented — Unverified**. The author ran parts of it ad hoc during development (observations in smoke-test.md section 3) but none of it counts as verification. **Nothing has been deployed to Render or Supabase from this repository yet**;
`render.yaml` passes Render's published JSON Schema and the local structural checks, and every Render/Supabase statement below that is not about our own code is
unverified until a real deployment happens (list at the end).

## 1. Topology

```
Browser --https--> die-frontend (Render static site)
                     |  /api/*  (Render rewrite = reverse proxy; the browser sees ONE origin)
                     v
                   die-api (Render web service, Docker) --JDBC/TLS--> Supabase Postgres (plain Postgres only)
                     |  AMQP (private network)           ^
                     v                                    |
                   die-rabbitmq (private service + disk)  |
                     ^                ^                   |
                     |                |   HTTP health     |
      die-orchestrator (private)   die-document-worker (private)   <- the API reads their /health/ready
```

| Service | Render type | Image build | Listens | Health |
|---|---|---|---|---|
| die-frontend | static site | `npm ci && npm run build` in `frontend/` | https | - |
| die-api | web (Docker) | `infra/render/api.Dockerfile` (context = repo root) | 8080 | Render check: `/actuator/health/liveness`; readiness: `/actuator/health/readiness` |
| die-rabbitmq | private service + 5 GB disk | `infra/render/rabbitmq.Dockerfile` | 5672 | `rabbitmq-diagnostics ping` |
| die-orchestrator | private service | `infra/render/orchestrator.Dockerfile` | 8081 (health only) | `/health/live`, `/health/ready` |
| die-document-worker | private service | `infra/render/document-worker.Dockerfile` | 8082 (health only) | same |

The `infra/render/*.Dockerfile` files mirror the service Dockerfiles with the repository root as build context (Render cannot pass the named build context `contracts` that Compose uses); CI builds both variants.
Workers are *private services*, not background workers, only so that the API can read their health endpoint for the heartbeat report.

## 2. Local stack (Docker Compose)

```bash
cp .env.example .env            # edit the passwords; set DIE_BOOTSTRAP_ADMIN_* for the first administrator
docker compose up -d --build --wait      # postgres, rabbitmq, api, orchestrator, document-worker, frontend
bash scripts/smoke.sh                    # or: pwsh scripts/smoke.ps1
docker compose down                      # keeps volumes;  `down -v` also deletes the database and the broker data
docker compose up -d postgres rabbitmq   # infrastructure only (run the API from an IDE)
```

Startup order is enforced by health checks: `postgres, rabbitmq -> api (readiness) -> frontend` and `rabbitmq -> orchestrator, document-worker`; `--wait` returns when every service is healthy.
Measured on a developer machine with warm Docker layer caches: all six services healthy from empty volumes in about 50 seconds. A cold build downloads Maven/npm/pip dependencies and takes several minutes.

| Port (host) | Service | Variable | Notes |
|---|---|---|---|
| 5173 | frontend (nginx) | `FRONTEND_HOST_PORT` | the entry point: SPA + same-origin proxy of `/api/` |
| 8080 | api | `API_HOST_PORT` | direct access for tools/Swagger (local profile only) |
| 5432 | postgres | `POSTGRES_HOST_PORT` | |
| 5672 / 15672 | rabbitmq AMQP / management UI | `RABBITMQ_AMQP_HOST_PORT` / `RABBITMQ_MGMT_HOST_PORT` | UI login = `RABBITMQ_USER` / `RABBITMQ_PASSWORD` |

All host ports bind to `127.0.0.1`. Another stack on the same machine: `docker compose -p other --env-file other.env up ...` with different host ports (this is how the slice was verified next to an unrelated container).
Networks: `default` (published ports, frontend to api) and `broker` (internal, no internet): RabbitMQ, API and workers. Resource limits: api 1 CPU / 768 MB, workers 1 CPU / 512 MB / 256 pids, rabbitmq 1 CPU / 768 MB, postgres 1 CPU / 512 MB, frontend 0.5 CPU / 128 MB.

## 3. Environment variables

"Secret" = never in git, never in logs; enter it in the Render dashboard (`sync: false` in `render.yaml`) or a password manager.

### API (`die-api`)

| Variable | Required | Secret | Default / example | Meaning |
|---|---|---|---|---|
| `SPRING_PROFILES_ACTIVE` | yes (prod) | no | `json-logs` | Production = **not** `local`/`test`. `json-logs` switches console logs to one JSON object per line. Compose uses `local,json-logs`. |
| `DB_URL` | yes | yes (contains host) | `jdbc:postgresql://HOST:5432/postgres?sslmode=require` | Supabase direct or **session-mode** pooler (port 5432). Transaction pooling (6543) breaks Flyway. |
| `DB_APP_USER` / `DB_APP_PASSWORD` | yes | password | `die_app` | Runtime role, DML only (V4/V8). Must differ from the migrator and must not be `postgres`. |
| `DB_MIGRATOR_USER` / `DB_MIGRATOR_PASSWORD` | yes | password | `die_migrator` | Schema owner; Flyway runs as this user at startup. |
| `DB_POOL_SIZE` | no | no | `10` (Blueprint: 8) | Hikari pool; mind Supabase's connection limit. |
| `RABBITMQ_HOST` | yes | no | Blueprint: from `die-rabbitmq` | |
| `RABBITMQ_PORT` | no | no | `5672` | |
| `RABBITMQ_USER` / `RABBITMQ_PASSWORD` | yes | password | env group `die-broker` (password generated by Render) | Start refuses empty, `guest`, example (`change-me*`) or < 16 character passwords. |
| `DIE_MESSAGING_ENABLED` | no | no | `true` | `false` runs the API without a broker (tests only). |
| `DIE_FRONTEND_BASE_URL` | yes | no | `https://die-frontend.onrender.com` | Base of the links in emails. Must be `https://` in production. |
| `DIE_MAIL_MODE` | yes | no | `smtp` | `log` prints activation/reset links in the log; refused in production. |
| `DIE_MAIL_FROM` | yes | no | `no-reply@school.edu` | |
| `SPRING_MAIL_HOST`, `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD`, `SPRING_MAIL_PORT` | yes | username+password | port 587, STARTTLS on | SMTP relay. The SMTP sender has never been exercised against a real server. |
| `SESSION_COOKIE_SECURE` | no | no | `true` | Must stay `true`; start refuses `false`. |
| `DIE_BOOTSTRAP_ADMIN_EMAIL` / `_PASSWORD` | first deploy only | password (>= 12 chars) | | Creates the first ADMIN when none exists. Delete both afterwards. |
| `DIE_OPS_ORCHESTRATOR_HEALTH_URL`, `DIE_OPS_DOCUMENT_WORKER_HEALTH_URL` | no | no | `http://orchestrator:8081/health/ready`; Render: `hostport` of the service | Where the API reads worker heartbeats. `host:port` alone is expanded. Blank = not monitored. |
| `DIE_OPS_STALE_JOB_SECONDS` | no | no | `900` | Test jobs QUEUED/RUNNING longer than this are failed by the reaper. |
| `SPRINGDOC_API_DOCS_ENABLED`, `SPRINGDOC_SWAGGER_UI_ENABLED` | no | no | Blueprint: `false` | No public API documentation in production. |

### RabbitMQ (`die-rabbitmq`) and workers

| Variable | Service | Required | Secret | Meaning |
|---|---|---|---|---|
| `RABBITMQ_USER`, `RABBITMQ_PASSWORD` | rabbitmq (Render) | yes | password | Seeds the login on the **first start with an empty disk only**. Compose uses `RABBITMQ_DEFAULT_USER/_PASS` from the same two values. |
| `RABBITMQ_NODENAME` | rabbitmq (Render image) | set in the image | no | `rabbit@localhost`: keeps the persistent disk usable when Render changes the hostname on redeploy. |
| `RABBITMQ_HOST`, `RABBITMQ_PORT`, `RABBITMQ_USER`, `RABBITMQ_PASSWORD`, `RABBITMQ_VHOST` | orchestrator, document-worker | host, user, password | password | Broker connection. |
| `HEALTH_PORT` | both workers | yes | no | 8081 (orchestrator), 8082 (document worker); the Dockerfile HEALTHCHECK uses these ports. |
| `DIE_PREFETCH`, `LOG_LEVEL` | both workers | no | no | |
| `POSTGRES_*` | compose `postgres` only | yes | passwords | Local only; production has no Postgres container. |

The workers have **no** database credentials and no AI provider key (P0). When the orchestrator calls an AI provider (P3) its key becomes a secret on that service only.

### Frontend

No runtime variables: the SPA calls relative `/api/v1/...`. `VITE_API_BASE_URL` stays unset (setting it would make the API cross-origin again, see section 6).

## 4. Supabase (plain Postgres only)

DIE uses Supabase only as a managed Postgres: no Supabase Auth, Storage, Realtime or Row Level Security, and the `anon`/`authenticated` roles are never used.

1. Create the project; note the project ref and the region (put Render in a nearby region; `render.yaml` uses `oregon`, change `region:` on **every** service together).
2. In the SQL editor, as `postgres`, create the two roles **before the first deploy** (V4 grants rights to `die_app` only if that role already exists):

   ```sql
   -- same intent as infra/postgres/01-roles.sh
   CREATE ROLE die_migrator LOGIN PASSWORD '<long random>' NOSUPERUSER NOCREATEDB NOCREATEROLE;
   CREATE ROLE die_app      LOGIN PASSWORD '<different long random>' NOSUPERUSER NOCREATEDB NOCREATEROLE;
   GRANT CONNECT ON DATABASE postgres TO die_migrator, die_app;
   GRANT USAGE, CREATE ON SCHEMA public TO die_migrator;
   GRANT USAGE ON SCHEMA public TO die_app;
   ALTER DEFAULT PRIVILEGES FOR ROLE die_migrator IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO die_app;
   ALTER DEFAULT PRIVILEGES FOR ROLE die_migrator IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO die_app;
   ```
3. Connection string: *Project settings > Database*. Render's outbound traffic is IPv4, so use the **Supavisor session pooler** (`aws-0-<region>.pooler.supabase.com:5432`, user `die_app.<project-ref>` / `die_migrator.<project-ref>`) or the IPv4 add-on's direct connection. Never transaction mode (6543).
   `DB_URL=jdbc:postgresql://aws-0-<region>.pooler.supabase.com:5432/postgres?sslmode=require`; `DB_APP_USER=die_app.<project-ref>`; `DB_MIGRATOR_USER=die_migrator.<project-ref>`. `sslmode=verify-full` with Supabase's CA certificate is stricter and needs the certificate in the image (not done).
4. Separation of duties: the API *process* holds both credentials today because Flyway runs inside it (migrator for Flyway, app role for the connection pool at run time). If a stricter split is needed, run migrations as a Render pre-deploy command or one-off job and give the API only the app credentials (future hardening, not implemented).
5. Verify after the first start: `SELECT max(version) FROM flyway_schema_history;` = latest `V<n>`; connected as `die_app`, `DELETE FROM audit_log` must fail.

## 5. Deploying on Render

1. Put the repository on GitHub and install the Render GitHub app for it. In GitHub, mark **`CI green`** (from `.github/workflows/ci.yml`) as a *required status check* on `main`: `autoDeployTrigger: checksPass` in `render.yaml` then deploys a commit only after the checks of that commit pass ("deploy gated on green").
2. Render dashboard > *New > Blueprint* > select the repository. Render reads `render.yaml`, creates the services, the env group `die-broker` (generated broker password) and prompts for every `sync: false` value (database, mail, `DIE_FRONTEND_BASE_URL`, first administrator).
3. Service names decide the default addresses. The Blueprint assumes `https://die-frontend.onrender.com` and `https://die-api.onrender.com`; if Render appends a suffix or you use custom domains, update (a) `DIE_FRONTEND_BASE_URL` and (b) the `destination:` of the `/api/*` rewrite in `render.yaml` (and push).
4. Wait for all five services to be live. The API's own Render health check is **liveness** (does not depend on Postgres/RabbitMQ, so a broker blip does not restart the API); readiness is `/actuator/health/readiness`.
5. Run the smoke test against production (section 3 of smoke-test.md): `SMOKE_FRONTEND_URL=https://die-frontend.onrender.com SMOKE_API_URL= bash scripts/smoke.sh` with the bootstrap administrator.
6. **Delete `DIE_BOOTSTRAP_ADMIN_EMAIL` / `_PASSWORD`** from the API's environment and redeploy. Change the administrator's password through the application.
7. Schedule backups ([recovery.md](recovery.md) section 2) and run the restore test once before relying on them.

Rolling out later: merge to `main` -> CI -> Render deploys the services whose `buildFilter` paths changed. The API's migrations run at startup; see recovery.md section 8 for the rule that makes rollbacks safe.

## 6. Same-site / CORS decision (open item from CLAUDE.md)

Decision: **one origin.** The SPA is served by `die-frontend`; Render's rewrite rule proxies `/api/*` to `die-api`, so the browser never makes a cross-origin request. Consequences:

* The session cookie (`HttpOnly`, `Secure`, `SameSite=Lax`) and the CSRF cookie are first-party; no CORS configuration exists on the API and none must be added. The same layout works locally (Vite dev proxy, nginx in Compose).
* Do **not** set `VITE_API_BASE_URL` and do not open the API's own `onrender.com` URL from the browser. If a custom API domain is introduced later, put it on the same registrable domain as the frontend and re-evaluate `SameSite` and CORS first.
* Client IP: `server.forward-headers-strategy: native` trusts `X-Forwarded-For` only from private-range proxies. Whether Render's rewrite proxy forwards the real client address in a header the API accepts, and whether its source addresses are private, is **unverified**; until checked, login throttling may see the proxy's address for every user (a shared bucket). Check on first deploy (a failed login must show the tester's address in the audit log).
* Not verified on Render: that the rewrite proxy forwards `Set-Cookie`/`Cookie`, `X-Request-Id`, request bodies for `POST` and SSE streams (evaluation progress, P3) unbuffered. The smoke test exercises cookies, CSRF and POST; SSE needs its own check when it exists.

## 7. Availability: one broker

`die-rabbitmq` is a single node with one disk (Render does not offer a managed RabbitMQ, and quorum queues on one node give durability against process crashes, not against node loss).

| Event | What happens | Operator action |
|---|---|---|
| Broker restarts or is redeployed (seconds to a minute) | API keeps serving; accepted jobs wait in the Postgres outbox (`PENDING`, retried with backoff); readiness turns DOWN, liveness stays UP; workers and the API consumer reconnect on their own; persistent messages survive on the disk. Verified with a stopped broker in Compose and in `MessagingIntegrationTest`. | none; check `GET /api/v1/ops/health` |
| Broker down for longer than `DIE_OPS_STALE_JOB_SECONDS` | jobs that were accepted but not completed are failed by the reaper with a reason | resubmit after recovery |
| Broker disk lost or corrupted | queued and in-flight messages are gone; Postgres still has every job and every outbox row | follow recovery.md section 5: re-import definitions, then jobs `PENDING` in the outbox are published again; jobs already `PUBLISHED` but unfinished are failed by the reaper and must be resubmitted (P3 adds re-enqueue from authoritative state) |
| Render maintenance / zone problem | same as a restart | none |

There is no automatic failover and no RabbitMQ clustering. Raising availability means a managed broker (for example CloudAMQP) behind the same `RABBITMQ_*` variables; no code change is expected because nothing relies on node-local features, but that is untested.

## 8. Health endpoints

| Endpoint | Auth | Content | Used by |
|---|---|---|---|
| `GET /actuator/health/liveness` | none | `{"status":"UP"}` | Render health check |
| `GET /actuator/health/readiness` | none | `{"status":"UP"}` / `DOWN` (503): application state + Postgres + RabbitMQ | Compose `--wait`, smoke test |
| `GET /actuator/health` | none | status + names of the groups | |
| `GET /api/v1/ops/health` | ADMIN session | database, broker (with response time), each worker's state and heartbeat age (`heartbeatFresh` = within `die.ops.max-heartbeat-age-seconds`, 90), outbox backlog (pending, failed, oldest pending age), test jobs by state and overdue, depth of the test queues and their DLQs | the admin dashboard (P3) and operators |
| Orchestrator `:8081/health/live|ready`, document worker `:8082/health/live|ready` | none, private network | status, `secondsSinceHeartbeat` (+ `consuming` for Python) | Docker health checks, the API |

Public endpoints show only the status word: no hosts, credentials, versions or component names (`show-details`/`show-components: never`, asserted by `PlatformHealthIntegrationTest`). No other actuator endpoint is exposed.

## 9. Logs and correlation

* API (`json-logs`): one JSON object per line (`@timestamp`, `level`, `logger_name`, `message`, MDC fields `requestId`, `messageId`). Workers: JSON with `correlationId`, `messageId`, `messageType`, `service`. nginx: JSON access lines with `requestId` (query strings are not logged).
* One request id follows a job end to end: the edge (nginx) or the caller sets `X-Request-Id`, the API echoes it, stores it in the outbox envelope as `correlationId`, the workers log it as `correlationId`, and the API result handlers log it again as `requestId`. Search every service's logs for that one value. Verified in the Compose stack (log excerpt in smoke-test.md).
* Secrets never appear in logs: `LogSanitizationTest` (login with right and wrong passwords, Authorization/Cookie/CSRF headers, DEBUG logging for the application, Spring Security and Spring Web) and a messaging-flow test (broker password, session cookie) assert it. That test found and fixed a real leak: Spring logs request bodies at DEBUG using the record's `toString()`, which printed login passwords; secret-bearing request/response records now override `toString()`. Mail mode `log` deliberately prints links and is refused in production.

## 10. CI and image scanning

`.github/workflows/ci.yml` (needs no secrets): lint (hadolint, shellcheck, yamllint, `docker compose config`, Blueprint structure), Flyway check (applied migrations are never edited; empty database migrates; validate; second run is a no-op), backend `mvn verify` (Testcontainers), frontend (`npm ci`, typecheck, lint, test, build), orchestrator `mvn verify`, Python `pytest` against a real RabbitMQ, a Compose smoke job (build, `up --wait`, `scripts/smoke.sh`, Trivy scan of the four images, CRITICAL findings with a fix fail the job), and the aggregate **CI green** job. `.github/dependabot.yml` keeps the digest-pinned base images, actions and dependencies current.

## 11. Not verified (read before trusting this document)

* Any Render behaviour: Blueprint apply, private networking and `hostport`, `checksPass` gating, the rewrite proxy (cookies, forwarded client IP, SSE), disk mounting and ownership for RabbitMQ, port detection of the private workers, memory sizing on the `starter` plan, health-check behaviour on slow starts.
* Any Supabase behaviour: role creation SQL, pooler user naming, TLS settings, connection limits, Flyway through the session pooler. The Supabase profile has never connected to a real Supabase project.
* The GitHub Actions workflow was never executed (no runner available). It was checked with actionlint (clean, includes shellcheck of the run scripts), yamllint, and its commands were run by hand locally (Flyway migrate/validate/no-op, hadolint, shellcheck, Compose build/up/smoke). Action versions are tags, not commit SHAs; the Trivy step has never run.
* SMTP delivery, TLS to the database, `sslmode=verify-full`, multi-instance API behaviour (in-memory limiters are per instance), and the Windows PowerShell smoke script against a TLS endpoint.
