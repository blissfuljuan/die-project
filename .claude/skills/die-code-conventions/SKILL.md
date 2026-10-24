---
name: die-code-conventions
description: Mandatory backend code layout and error-handling rules for DIE: base package com.blissfuljuan.dieapi, Model-View-Controller layering per module (controller/view/model/service/repository), and the global error handler (ApiException codes, ProblemDetail format). Read before adding or changing any backend class, endpoint or error.
---

# DIE backend conventions

## Base package
`com.blissfuljuan.dieapi` (Maven groupId `com.blissfuljuan`). Never use another root.

## Model-View-Controller, in every module
```
com.blissfuljuan.dieapi.<module>/
    controller/   C  HTTP only: routes, request records + @Valid, call ONE service, map to a view. No SQL, no business rules.
    view/         V  response representations (records). Plain data: no logic, no Spring, no model/service/repository imports.
    model/        M  domain data: rows, commands, enums. Plain data. `model/rule/` holds pure, framework-free business rules and strategies (ThrottlePolicy, MembershipReconciler, ConflictResolver).
    service/      M  business rules and orchestration; @Service/@Component live here (also AccessPolicy and its role strategies, parsers, limiters). Sub-packages for strategy families: `service/reader` (roster file readers), `service/creation` (project creation strategies). No SQL, no JdbcClient/DataSource (ArchUnit `servicesDoNotUseJdbc`).
    repository/   M  persistence (SQL via JdbcClient). Only services call repositories. All SQL lives here.
```
Only `config` (wiring, filters, security) and `common` (`controller` = global error handling, `exception`, `util`, `view`) sit outside the layout. A new module that breaks the layout fails `MvcArchitectureTest` (ArchUnit): layer direction, stereotypes in the right packages, controllers never touch persistence, controllers return view types, views and model data stay plain.

Rules of thumb
- Controller methods: parse -> `service.doThing(...)` -> return a `view` record. Session/cookie/servlet plumbing stays in the controller; every decision lives in a service (e.g. `AuthService` decides, `AuthController` only sets the session).
- A service may return view records (read projections) and may use other modules' services; it must not depend on controllers.
- Request bodies are records nested in the controller. Commands/results that several layers share are top-level records in `model/` or `view/` — do not nest them in repositories or services.
- Authorization: ask `access.service.AccessPolicy` first (see die-authz-publication). Add the endpoint to `EndpointAuthorizationMatrixTest.manifest()`.
- Transactions are the service layer's job; never put `@Transactional` on a controller. No hashing/IO inside a transaction that holds row locks.
- Tests mirror the package of what they test (package-private access needs it, e.g. `account.service.CredentialHasherTest`).

## Errors: one global handler, one format
`common.controller.GlobalExceptionHandler` turns every failure into an RFC 9457 problem body:
`status, title, detail (a sentence a person can act on), instance, code (stable, machine-readable), timestamp, traceId (= X-Request-Id header and the log's request id), errors[] (validation only: field + message, never the rejected value)`.
- Throw `ApiException.notFound/conflict/badRequest/forbidden/unauthorized/tooManyRequests/unavailable(ErrorCode.SPECIFIC_CODE, "plain-language message")` for business failures. Plain `ResponseStatusException` is still handled by the global handler (code derived from the status) but is not used in services or controllers: always give a specific code.
- Messages: say what is wrong and what to do; no internals (SQL, class names, hosts), no secrets, no existence leaks (use the same code/message for "missing" and "not yours").
- Unexpected exceptions become `500 INTERNAL_ERROR` with a generic message and the traceId; the stack trace goes only to the log.
- 401/403 from the security filters use `config.JsonAuthenticationEntryPoint` / `JsonAccessDeniedHandler` (same format; codes `AUTHENTICATION_REQUIRED`, `CSRF_TOKEN_INVALID`, `ACCESS_DENIED`). Container-level errors use `common.controller.ErrorPageController`.
- Tests of "indistinguishable" responses must compare `status`, `code`, `detail` — not the whole body (timestamp and traceId differ per request).
- Codes: `common.exception.ErrorCode` (enum) is the single source of truth; the constant name is the `code` in the JSON. Every `ApiException` factory takes an `ErrorCode`: add a constant there for a new failure, never pass a string. The global handler, security handlers and `ProblemDetails.of(HttpStatus, ErrorCode, detail)` use the same enum. A plain `ResponseStatusException` (not allowed in services) and container errors fall back to the HTTP status name via `ProblemDetails.codeFor` (NOT_FOUND, CONFLICT, ...). Constants are grouped in the enum by area (generic, shared, accounts, auth, classes/enrollment, projects/teams, roster import).

## Adding a module — checklist
1. `com.blissfuljuan.dieapi.<module>/{controller,view,model,service,repository}` (create only the layers you need).
2. Flyway migration for any schema change (new V<n>__*.sql, never edit an applied one).
3. Endpoints under `/api/v1`, row in the authorization matrix, negative tests for every outsider role.
4. `mvn test` (the Docker command in README) — `MvcArchitectureTest` and the matrix must pass.

## SOLID and design patterns (mandatory for new and changed code — ADR `docs/decisions/0001-solid-and-design-patterns.md`)
- Apply S/O/L/I/D, but abstract only at a real seam (second implementation, growing `switch`, process boundary). Catalogue and placement: Strategy + registry (`model/rule` for pure rules), Port & Adapter (`service/port`, `service/adapter`), Factory, Template Method for the lock -> re-check -> version -> mutate -> audit skeleton, Facade (`AccessPolicy`), Repository, Observer via Spring events, State as enum/transition table.
- Roles, statuses and kinds are enums in `model/`, never string literals in services. Services never contain SQL (repositories only) and never use `ResponseStatusException` (use `ApiException` with a specific code).
- Services over ~250 lines or mixing queries, rules and orchestration are split. Request/command records live in `model/`, not nested in services.
- The migration of existing code (`docs/refactor-plan.md`) is complete; all new code must comply from the start.
- Where things live today: authorization = `access.service.AccessPolicy` facade over `ClassAccess`/`ProjectAccess`, with one `RoleAccess` strategy per `UserRole` (`AdminAccess`, `CoordinatorAccess`, `ParticipantAccess`, via `RoleAccessRegistry`) and all SQL in `access.repository.AccessRepository`. Throttling = `auth.service.LoginThrottleService` over `auth.model.rule.ThrottlePolicy`/`ThrottleBucket` and `auth.repository.LoginAttemptRepository`.
- Auditing: a service publishes `AuditEvent.of(actor, AuditAction.X, AuditResource.Y, resourceId, details)` with `ApplicationEventPublisher`; `audit.service.AuditEventListener` (synchronous `@EventListener`, same transaction as the publisher, also works without a transaction) writes the row via `AuditService` -> `AuditLogRepository`. Never call `AuditService` outside the `audit` module (ArchUnit). The stored `resource_type` is `AuditResource.value()` (lowercase); the stored action is the `AuditAction` constant name.
- `service/port` and `service/adapter` are the placement for seams: a module that needs something from another module (or infrastructure) defines the port in its own `service/port`, the other module implements it in `service/adapter`. First use: `academic.service.port.{StudentRemovalHandler, ClassTeamStats}` implemented by `project.service.adapter.ProjectClassIntegration`. Module dependencies must stay acyclic (`MvcArchitectureTest.modulesAreFreeOfCycles`); call the port synchronously so the caller's transaction and lock order are kept. Do not create ports without a real second implementation or a cycle to break.
