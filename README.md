# Document–Implementation Evaluator (DIE)

AI-assisted academic evaluation platform. See [CLAUDE.md](CLAUDE.md) for invariants and [docs/brief.md](docs/brief.md) for the full specification.

## Layout
| Path | Purpose |
|---|---|
| `backend/` | Spring Boot API (`/api/v1`), base package `com.blissfuljuan.dieapi`, MVC layout per module (`<module>/{controller,view,model,service,repository}`), Flyway migrations in `src/main/resources/db/migration` |
| `frontend/` | React + TypeScript + Vite |
| `workers/orchestrator/` | Java evaluation orchestration worker (own Maven project, `mvn test` there) |
| `workers/document-analysis/` | Python DOCX/PDF analysis worker (`pip install -e .[test]`, `pytest`) |
| `contracts/` | Language-neutral message schemas and examples shared by the API and both workers |
| `infra/` | RabbitMQ topology, Postgres role bootstrap, Render image variants (`infra/render/`) |
| `scripts/` | smoke tests (`smoke.sh`, `smoke.ps1`), Postgres backup and restore test, DLQ tool, Blueprint check |
| `docs/` | Brief, ADRs (`decisions/`), runbooks (`ops/`) |
| `render.yaml` | Render Blueprint (frontend static site, API, RabbitMQ, both workers); Postgres is Supabase |

## Local development
```bash
cp .env.example .env                          # then edit the passwords; set DIE_BOOTSTRAP_ADMIN_EMAIL / _PASSWORD
docker compose up -d --build --wait           # postgres, rabbitmq, api, orchestrator, document-worker, frontend: all healthy
bash scripts/smoke.sh                         # or .\scripts\smoke.ps1: readiness, proxy, CSRF, login, ops test job -> SUCCEEDED
docker compose up -d postgres rabbitmq        # infrastructure only (run the API from your IDE)
```
Open http://localhost:5173 (frontend, proxies `/api` to the API). RabbitMQ management UI: http://localhost:15672. Postgres: `localhost:5432`.
Host ports can be moved with `FRONTEND_HOST_PORT`, `API_HOST_PORT`, `POSTGRES_HOST_PORT`, `RABBITMQ_AMQP_HOST_PORT`, `RABBITMQ_MGMT_HOST_PORT`; use `docker compose -p <name>` for a second stack.

Production: Render Blueprint [`render.yaml`](render.yaml) + Supabase Postgres. Deployment, environment variables, backups, recovery and the smoke-test runbook are in [`docs/ops/`](docs/ops/) (`deployment.md`, `recovery.md`, `smoke-test.md`). CI: `.github/workflows/ci.yml`.

## Queue topology and workers
See [docs/messaging.md](docs/messaging.md): message envelope (`contracts/messaging/envelope-v1.schema.json`), naming convention, retry tiers and DLQs,
transactional outbox, worker contract. The topology lives in `infra/rabbitmq/definitions.json` and is imported at broker start by `infra/rabbitmq/die-entrypoint.sh`.

Smoke test of API -> RabbitMQ -> orchestrator -> document worker -> API: `scripts/smoke.sh` / `scripts/smoke.ps1` (see `docs/ops/smoke-test.md`);
by hand: `POST /api/v1/ops/test-jobs` (CSRF + session cookie), then `GET /api/v1/ops/test-jobs/{id}` until `SUCCEEDED`. Dead letters: `scripts/dlq.py`.

## First administrator
No admin exists on a fresh database. Set these for the first start only, then remove them:
```powershell
$env:DIE_BOOTSTRAP_ADMIN_EMAIL = "admin@your-school.edu"
$env:DIE_BOOTSTRAP_ADMIN_PASSWORD = "<at least 12 characters>"
```
The admin is created only if no ADMIN account exists. Further accounts are created via `POST /api/v1/users`, which returns the new account's temporary credential once.

## Running the backend tests (no local JDK needed)
Integration tests use Testcontainers (real Postgres), so Docker must be running:
```bash
MSYS_NO_PATHCONV=1 docker run --rm -v "$PWD:/repo" -v die-m2:/root/.m2 -v /var/run/docker.sock:/var/run/docker.sock -w /repo/backend -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal -e TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock maven:3.9-eclipse-temurin-21 mvn -B test
```
The whole repository is mounted because the backend reads the shared message contracts (`contracts/`) and the RabbitMQ topology (`infra/rabbitmq`) from outside `backend/`. With a local JDK 21 and Maven, `mvn test` in `backend/` is enough. `src/test/resources/docker-java.properties` pins the Docker API version that Docker 29+ requires.

## Profiles and production configuration
There is no default profile. For local development run with `SPRING_PROFILES_ACTIVE=local` (insecure cookie flag, log-only email, `postgres/postgres` defaults). Without it the app refuses to start unless these are set: `DB_URL`, `DB_APP_USER`/`DB_APP_PASSWORD` (a least-privileged role such as `die_app`, created by infrastructure; V4 grants it DML only), `DB_MIGRATOR_USER`/`DB_MIGRATOR_PASSWORD` (schema owner, must differ), `DIE_MAIL_MODE=smtp` with `spring.mail.*` and `DIE_MAIL_FROM`, `DIE_FRONTEND_BASE_URL` (https), and, while messaging is enabled, a non-default `RABBITMQ_USER` / `RABBITMQ_PASSWORD` (not `guest`, not an example value, at least 16 characters). `SPRING_PROFILES_ACTIVE=json-logs` switches the console log to JSON. Behind a proxy the app trusts `X-Forwarded-For` only from private-range proxies.
