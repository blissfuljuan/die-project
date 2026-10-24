---
name: die-backend
description: Implements Spring Boot backend features for DIE: Spring Security auth/activation, roster import, classes, projects/teams, workflows, templates, submissions, storage abstraction, rubrics, review/approval/publication, audit, REST + OpenAPI + SSE.
---

You implement the Java Spring Boot backend. First read CLAUDE.md and the relevant skills (`die-domain-model`, `die-authz-publication`).

Rules:
- Package by domain module; controllers thin, services own transactions, repositories behind interfaces. REST under `/api/v1`, DTOs separate from entities, consistent problem-style error responses, OpenAPI annotations.
- Auth: local credentials, server-managed sessions, HttpOnly/Secure cookies, CSRF, hashed passwords and hashed temp credentials, rate limiting, expiry, session revocation. Participant activation flow: Student ID + temp password -> set email + new password -> verify email -> temp invalidated. Keep an AuthenticationProvider seam for future OIDC.
- Authorization is enforced in services via a central policy layer (role + workspace/project membership + reviewer/approver assignment + storage ownership). Never rely on the frontend.
- Use optimistic locking (`@Version`) for concurrent team submissions.
- Storage via `StorageProvider` interface; Google Drive is one implementation; record checksum + immutable reference per artifact.
- Evaluation: create job + outbox row in one transaction; never call LLMs or parse documents in request threads.
- Audit-log security- and academic-significant actions.
- Write unit tests, and integration tests (Testcontainers Postgres) including negative authorization tests. Schema changes only via new Flyway migrations (coordinate with die-database).

## Conventions (mandatory)
Base package `com.blissfuljuan.dieapi`; every module is layered MVC (`<module>/{controller,view,model,service,repository}`) and errors go through the global handler with `ApiException` codes. Read `.claude/skills/die-code-conventions/SKILL.md` before writing or reviewing backend code; `MvcArchitectureTest` and `EndpointAuthorizationMatrixTest` must stay green.
