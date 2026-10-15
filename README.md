# Document–Implementation Evaluator (DIE)

AI-assisted academic evaluation platform. See [CLAUDE.md](CLAUDE.md) for invariants and [docs/brief.md](docs/brief.md) for the full specification.

## Layout
| Path | Purpose |
|---|---|
| `backend/` | Spring Boot API (`/api/v1`), base package `com.blissfuljuan.dieapi`, MVC layout per module (`<module>/{controller,view,model,service,repository}`), Flyway migrations in `src/main/resources/db/migration` |
| `frontend/` | React + TypeScript + Vite |
| `workers/orchestrator/` | Java evaluation orchestration worker |
| `workers/document-analysis/` | Python DOCX/PDF analysis worker |
| `infra/` | RabbitMQ topology, Postgres role bootstrap |
| `docs/` | Brief, ADRs (`decisions/`), runbooks (`ops/`) |

## Local development
```bash
cp .env.example .env        # then edit the passwords
docker compose up -d        # Postgres + RabbitMQ
docker compose --profile apps up -d --build   # app services, once their sources exist
```
RabbitMQ management UI: http://localhost:15672. Postgres: `localhost:5432`.

Production uses Supabase Postgres (plain DB only), Google Drive, and a Render Blueprint (`render.yaml`, P0 follow-up).

## Queue topology (infra/rabbitmq/definitions.json)
`evaluation.jobs` (quorum) -> on nack/reject goes to `evaluation.jobs.retry` (30s TTL) -> back to `evaluation.jobs`. After the max attempt count (tracked by the consumer), the consumer publishes to `die.evaluation.dlx` -> `evaluation.jobs.dlq`. `document.analyze` uses a similar DLQ.

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
MSYS_NO_PATHCONV=1 docker run --rm -v "$PWD/backend:/app" -v die-m2:/root/.m2 -v /var/run/docker.sock:/var/run/docker.sock -w /app -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal -e TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock maven:3.9-eclipse-temurin-21 mvn -B test
```
With a local JDK 21 and Maven, `mvn test` in `backend/` is enough. `src/test/resources/docker-java.properties` pins the Docker API version that Docker 29+ requires.

## Profiles and production configuration
There is no default profile. For local development run with `SPRING_PROFILES_ACTIVE=local` (insecure cookie flag, log-only email, `postgres/postgres` defaults). Without it the app refuses to start unless these are set: `DB_URL`, `DB_APP_USER`/`DB_APP_PASSWORD` (a least-privileged role such as `die_app`, created by infrastructure; V4 grants it DML only), `DB_MIGRATOR_USER`/`DB_MIGRATOR_PASSWORD` (schema owner, must differ), `DIE_MAIL_MODE=smtp` with `spring.mail.*` and `DIE_MAIL_FROM`, and `DIE_FRONTEND_BASE_URL`. Behind a proxy the app trusts `X-Forwarded-For` only from private-range proxies.
