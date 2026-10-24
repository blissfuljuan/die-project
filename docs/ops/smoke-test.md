# Smoke test runbook (P0-VS05)

`scripts/smoke.sh` (bash) and `scripts/smoke.ps1` (Windows PowerShell 5.1 and PowerShell 7) check a **running** stack end to end. Use them after `docker compose up --wait`, after every deployment, and after any restore. Exit code 0 = all checks passed, 1 = a check failed, 2 = missing credentials.

## 1. Run it

```bash
# Local Compose stack (administrator from the first-start variables)
set -a; . ./.env; set +a
bash scripts/smoke.sh
```
```powershell
# PowerShell
$env:DIE_BOOTSTRAP_ADMIN_EMAIL = "admin@example.edu"; $env:DIE_BOOTSTRAP_ADMIN_PASSWORD = "<password>"
.\scripts\smoke.ps1
```

| Variable | Default | Meaning |
|---|---|---|
| `SMOKE_FRONTEND_URL` | `http://localhost:5173` | what a browser would open; every call except the readiness probe goes through it (so nginx's `/api` proxy is exercised) |
| `SMOKE_API_URL` | `http://localhost:8080` | direct API address for the actuator probes; set to the empty string to skip (production: the actuator is not exposed through the frontend) |
| `SMOKE_ADMIN_EMAIL`, `SMOKE_ADMIN_PASSWORD` | `DIE_BOOTSTRAP_ADMIN_EMAIL`, `DIE_BOOTSTRAP_ADMIN_PASSWORD` | an ACTIVE administrator. The password is never printed |
| `SMOKE_JOB_TIMEOUT` | `90` | seconds to wait for the test job |
| `SMOKE_CURL_OPTS` | empty | extra curl options (bash only), for example `--insecure` for a self-signed certificate |

Production (Render): `SMOKE_FRONTEND_URL=https://<your-frontend> SMOKE_API_URL= bash scripts/smoke.sh`. The test creates one test job, one session and a few audit rows (`LOGIN_SUCCEEDED`, `TEST_JOB_SUBMITTED`, `LOGOUT`) and nothing else. Use a dedicated administrator account for it in production.

## 2. What each check proves

| # | Check | Proves |
|---|---|---|
| 1 | API readiness UP, liveness UP | Spring Boot started, Flyway done, Postgres and RabbitMQ reachable |
| 2 | SPA shell served, deep link falls back to `index.html` | frontend image/static site and history fallback |
| 3 | `GET /api/v1/status` through the frontend | the `/api` proxy (nginx locally, Render rewrite in production) reaches the API |
| 4 | `GET /api/v1/auth/csrf` sets `XSRF-TOKEN` | cookies pass through the proxy |
| 5 | `POST` without the CSRF header is refused with 403 | CSRF protection is active behind the proxy |
| 6 | Admin login, `/auth/me` reports `ADMIN` | session creation, JDBC session store, the bootstrap/administrator account |
| 7 | `GET /api/v1/ops/health`: database UP, broker UP, orchestrator and document-worker UP with fresh heartbeats | the admin health report and the API's network path to the workers |
| 8 | `POST /api/v1/ops/test-jobs` returns 202, job reaches `SUCCEEDED` within the timeout | transactional outbox -> RabbitMQ -> Java orchestrator -> Python worker -> RabbitMQ -> API result consumer -> Postgres |
| 9 | the job's `correlationId` equals the `X-Request-Id` the script sent | the correlation id survives the whole chain |
| 10 | logout | session teardown |

## 3. Output observed during development (2026-10-18; not accepted as verification)

Stack: project `dievs05` (separate from other containers), all six services healthy from empty volumes in 50 s with warm layer caches. Bash output (trimmed to the first 160 characters of the long line):

```
DIE smoke test  frontend=http://localhost:55173  api=http://localhost:58080
  PASS  API readiness is UP (Postgres + RabbitMQ)
  PASS  API liveness is UP
  PASS  frontend serves the SPA shell
  PASS  SPA history fallback (deep link returns index.html)
  PASS  GET /api/v1/status through the nginx proxy: UP
  PASS  CSRF cookie issued through the proxy
  PASS  POST without CSRF header is refused (403)
  PASS  admin login
  PASS  session is an ADMIN session
        report: {"status":"UP","checkedAt":"2026-10-18T14:18:43.753333538Z","database":{"status":"UP",...
  PASS  ops health: database UP
  PASS  ops health: broker UP
  PASS  ops health: orchestrator and document-worker UP with fresh heartbeats
  PASS  test job 28aa4626-8404-46a0-85ba-271e273b4e80 accepted (202, state QUEUED), request id smoke-1792333123-137
  PASS  test job reached SUCCEEDED through API -> RabbitMQ -> orchestrator -> document worker -> API
  PASS  request id preserved as the job's correlation id
  PASS  logout

SMOKE TEST PASSED  (correlation id: smoke-1792333123-137)
```
The PowerShell script (Windows PowerShell 5.1.26100) produced the same 16 PASS lines and exit code 0.

Correlation trail of that request (`docker compose logs` for each service, filtered on the id):

```
api            outbox-relay   "outbox message published to die.test/die.test.jobs"        requestId=smoke-1792333123-137
orchestrator   QueueWorker    "handled test.ping -> 2 message(s)"                          correlationId=smoke-1792333123-137
document-worker die.processor "analyze job handled (attempt 1)"                            correlationId=smoke-1792333123-137
orchestrator   QueueWorker    "handled test.analyze.result -> 1 message(s)"                correlationId=smoke-1792333123-137
api            TestProgressHandler / TestResultHandler  "test job ... result SUCCEEDED"    requestId=smoke-1792333123-137
```

Failure drills run on the same stack: orchestrator stopped -> `ops/health` showed it `UNREACHABLE` and the platform `DEGRADED`; RabbitMQ stopped -> readiness 503 while liveness and the frontend status stayed 200, `ops/health` showed the broker DOWN and both workers DOWN (not consuming); everything recovered after the containers were started again, and a job that exhausted its retries ended `FAILED` with its message in `die.test.analyze.dlq`, replayed with `scripts/dlq.py` (recovery.md section 5.2).

## 4. When it fails

| Failing check | First thing to look at |
|---|---|
| 1 readiness | `GET /actuator/health/readiness` shows only DOWN; read the API logs (`database health check failed` / `broker health check failed`) and `docker compose ps` |
| 2, 3 | frontend container logs; nginx cannot resolve `api` (API not started) or the Render rewrite destination is wrong |
| 4, 5 | a proxy that strips cookies or headers; a wrong `SMOKE_FRONTEND_URL` scheme |
| 6 login 401 | the administrator does not exist (bootstrap variables were not set on the first start), wrong password, or the account is locked after repeated failures (wait 15 minutes) |
| 6 login 429 | login throttling; wait or clear `login_attempt` (recovery.md section 11) |
| 7 workers not UP | worker container unhealthy, `DIE_OPS_*_HEALTH_URL` not set or not reachable from the API |
| 8 job `FAILED` | job's `failureReason`; worker logs by the printed request id; DLQ (recovery.md section 5.2) |
| 8 timeout, job `QUEUED` | broker down or relay failing: `outbox` numbers in `ops/health`; after `DIE_OPS_STALE_JOB_SECONDS` the reaper fails the job |
| 9 | an intermediary overwrote `X-Request-Id` |

## 5. What the smoke test does not prove

It is a connectivity and wiring check with one administrator. It does not test student/instructor flows, email delivery, Google Drive, AI providers, SSE streaming, load, or the browser UI (Playwright covers the UI separately). A pass on Compose says nothing about Render until the same script has passed against the deployed URL.
