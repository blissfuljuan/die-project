# DIE frontend

React + TypeScript (strict) + Vite, React Router, TanStack Query, Tailwind CSS 4, shadcn/ui primitives
(`components.json`, `src/components/ui`), React Hook Form + Zod. It talks only to the Spring Boot REST API
(`/api/v1`). Current scope: P0-VS01 (connection status page at `/`).

## Run

```bash
cp .env.example .env        # optional
npm ci
npm run dev                 # http://localhost:5173, proxies /api -> http://localhost:8080
```

Start the API first (from the repo root: `docker compose --profile apps up -d --build api`, or run
`backend/` locally with `SPRING_PROFILES_ACTIVE=local`). The dev server proxy keeps the browser on one origin,
so session and CSRF cookies work without CORS.

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

## API client (`src/api`)

`ApiClient` sends `credentials: 'include'`, has a request timeout, parses the RFC 9457 error body into
`ApiError` (`status`, `code`, `traceId`, `errors[]`), and distinguishes `NetworkError` (no response / timeout)
from `ApiError` (HTTP error) and `ResponseFormatError` (bad 2xx body). For POST/PUT/PATCH/DELETE it ensures the
`XSRF-TOKEN` cookie exists (`GET /auth/csrf`) and sends it as `X-XSRF-TOKEN`; GET never does.

## Docker

`Dockerfile` builds the SPA and serves it with nginx (unprivileged, port 8080) with history fallback; `/api/`
is proxied to the `api` Compose service (`nginx.conf`). Production is a Render Static Site.
