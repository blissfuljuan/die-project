# DIE frontend

React + TypeScript (strict) + Vite, React Router, TanStack Query, Tailwind CSS 4, shadcn/ui primitives
(`components.json`, `src/components/ui`), React Hook Form + Zod. It talks only to the Spring Boot REST API
(`/api/v1`). Workspace management UI (P1-VS03): see `src/features/workspaces/`. Confirmations are inline panels (no modal dialog dependency). Current scope: P0-VS01 (system status page at `/status`) and P1 UI wave 1 (identity screens, app shell,
role dashboards, read-only workspace/project pages).

## Run

```bash
cp .env.example .env        # optional
npm ci
npm run dev                 # http://localhost:5173, proxies /api -> http://localhost:8080
```

Start the API first (from the repo root: `docker compose --profile apps up -d --build api`, or run
`backend/` locally with `SPRING_PROFILES_ACTIVE=local`). The dev server proxy keeps the browser on one origin,
so session and CSRF cookies work without CORS.

## Routes

One area per role (decision: separate prefixes, one shared shell). Guards are presentation only; the API decides what
a user may read or change, and a wrong-role visit shows the neutral "Not found, or no access" screen.

| Path | Who | Notes |
|---|---|---|
| `/login` | signed out | `?next=` return path (same-origin paths only); signed-in users are redirected away |
| `/activate` | signed out | Step 1 Student ID/email + temporary credential (`/auth/activation/check`), step 2 email + new password (`/begin`), "check your email" |
| `/verify-email?token=` | anyone | **Path used by the backend activation mail.** Posts the token once to `/auth/activation/verify`, then removes it from the URL |
| `/forgot-password` | signed out | Always the same neutral confirmation; 429 shown as a throttling message |
| `/reset-password?token=` | anyone | **Path used by the backend reset mail.** Posts token + new password, then removes the token from the URL |
| `/status` | anyone | Connection/system status card (was `/` in P0) |
| `/` | signed in | Redirects to the role home |
| `/app` | Participant | Dashboard: my workspaces and projects |
| `/coordinator` | Coordinator | Dashboard: workspaces with Owner badge, capability chips (from `myCapabilities`), project counts |
| `/admin` | Administrator | Dashboard: platform health (`/ops/health`), links to placeholder pages |
| `/admin/accounts` | Administrator | "Coming soon" placeholder (listed in the nav) |
| `/admin/workspaces` | Administrator | All Project Workspaces with their Owner; "New Project Workspace" |
| `/workspaces/new` | Coordinator, Administrator | Create a Project Workspace (a Coordinator becomes the Owner; an Administrator picks the Owner by searching accounts) |
| `/workspaces/:id/edit` | Coordinator with Settings permission, Administrator | Edit settings; sends the version it saw and offers "Load the latest settings" on a 409 |
| `/workspaces/:id/roster` | Coordinator with Participants permission, Administrator | Paged roster (50 per page, "show removed"), enroll by Student ID + last name, "New credential" for Participants who have not activated (shown once), remove with confirmation |
| `/workspaces/:id/roster/import` | same | Upload .csv/.xlsx -> preview (counts, per-row outcome, filter) -> decide conflicts -> Import -> one-time credentials table + CSV download (formula-safe, `lib/csv.ts`) |
| `/workspaces/:id`, `/projects/:id` | any signed-in | Shows only what the API returns (404/403 -> "Not found, or no access"; Administrators never see project lists). The workspace page also has Edit settings / Archive (confirmation) / Restore (Administrators), and for Coordinators and Administrators the **Coordinators panel**: Owner badge, permissions per Coordinator, change permissions, remove, add (candidate search), transfer ownership |
| `/me` | any signed-in | Profile and change password |

Session: `useSession` reads `GET /auth/me` (401 = signed out; an unreachable server is an error, not "signed out").
Any API answer `401 AUTHENTICATION_REQUIRED` flips the session to signed out and the guards redirect to
`/login?next=<current path>`. Login and logout clear the whole query cache. Backend error codes are turned into
plain sentences in one place: `src/api/errorMessages.ts`. The API client retries a mutating request once after a
`CSRF_TOKEN_INVALID` 403 with a fresh token.

Backend mail links (`die.frontend-base-url` + path): activation `/verify-email?token=...`, reset
`/reset-password?token=...`. Both match the routes above. The production nginx/Render static site must serve
`index.html` for these paths (history fallback; already configured in `nginx.conf`).

## End-to-end tests (Playwright, real backend)

```bash
# API reachable at http://localhost:8080 (or set VITE_DEV_PROXY_TARGET); bootstrap Administrator credentials:
E2E_ADMIN_EMAIL=admin@example.edu E2E_ADMIN_PASSWORD='the-bootstrap-password' npm run test:e2e
```

`e2e/status.spec.ts` and the signed-out part of `e2e/auth.spec.ts` need no credentials; the Administrator tests are
skipped with a clear message when `E2E_ADMIN_EMAIL`/`E2E_ADMIN_PASSWORD` are unset. PowerShell:
`$env:E2E_ADMIN_EMAIL='...'; $env:E2E_ADMIN_PASSWORD='...'; npm run test:e2e`.

## Scripts

| Script | Purpose |
|---|---|
| `npm run dev` | Vite dev server |
| `npm run build` | Typecheck, then production build to `dist/` |
| `npm run preview` | Serve `dist/` (also proxies `/api`) |
| `npm run lint` | ESLint |
| `npm run typecheck` | `tsc --noEmit` |
| `npm test` | Vitest + Testing Library + MSW |
| `npm run test:e2e` | Playwright against the **real** API through the dev server |

Playwright needs a browser: `npx playwright install chromium`, or use an installed one with
`E2E_CHANNEL=msedge` / `E2E_CHANNEL=chrome`. Set `E2E_BASE_URL` to test an already running frontend instead of
starting the dev server.

## Environment variables

| Variable | Default | Meaning |
|---|---|---|
| `VITE_API_BASE_URL` | `/api/v1` | API base used by the browser (build-time) |
| `VITE_DEV_PROXY_TARGET` | `http://localhost:8080` | Dev/preview proxy target for `/api` (not exposed to the browser) |
| `E2E_BASE_URL`, `E2E_CHANNEL` | unset | Playwright overrides |
| `E2E_ADMIN_EMAIL`, `E2E_ADMIN_PASSWORD` | unset | Bootstrap Administrator for the sign-in e2e tests (tests skip when unset) |

## API client (`src/api`)

`ApiClient` sends `credentials: 'include'`, has a request timeout, parses the RFC 9457 error body into
`ApiError` (`status`, `code`, `traceId`, `errors[]`), and distinguishes `NetworkError` (no response / timeout)
from `ApiError` (HTTP error) and `ResponseFormatError` (bad 2xx body). For POST/PUT/PATCH/DELETE it ensures the
`XSRF-TOKEN` cookie exists (`GET /auth/csrf`) and sends it as `X-XSRF-TOKEN`; GET never does.

## Docker

`Dockerfile` builds the SPA and serves it with nginx (unprivileged, port 8080) with history fallback; `/api/`
is proxied to the `api` Compose service (`nginx.conf`). Production is a Render Static Site.
