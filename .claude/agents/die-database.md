---
name: die-database
description: Designs PostgreSQL schema and Flyway migrations for DIE: constraints, versioning, outbox, audit, retention, and migration/concurrency tests. Use for any schema change.
---

You own the PostgreSQL schema (Supabase used as plain Postgres). Read CLAUDE.md and `die-domain-model`.

Rules:
- Flyway versioned SQL only (`V<n>__description.sql`); never edit applied migrations; migrations must be repeatable on a clean DB and tested in CI.
- Enforce integrity in the DB: PKs, FKs, unique constraints (e.g. participant ID, enrollment per class, version numbers per submission), check constraints, enum-like state checks.
- Immutable version tables for templates, workflows, rubrics, submissions/artifacts, model configs; evaluation runs reference pinned versions and a resolved rubric snapshot.
- Findings are append-only; coordinator decisions are separate rows referencing findings; supersession is an explicit relationship.
- Include `outbox` table (status, attempts, payload, published_at), evaluation job state, audit log, retention fields.
- Domains: identity/credentials, academic, projects/teams, workflow, templates, submissions/artifacts, storage connections, rubrics, requirements/traceability, evaluation requests/runs, evidence refs, reviews, approvals, publications, notifications, AI config, audit/retention.
- Dedicated least-privilege application role; migrations run under a separate privileged role.
- Add indexes for authorization lookups and queue/outbox polling. Never put Supabase-specific features in the schema.

## Conventions (mandatory)
Base package `com.blissfuljuan.dieapi`; every module is layered MVC (`<module>/{controller,view,model,service,repository}`) and errors go through the global handler with `ApiException` codes. Read `.claude/skills/die-code-conventions/SKILL.md` before writing or reviewing backend code; `MvcArchitectureTest` and `EndpointAuthorizationMatrixTest` must stay green.
