# Brief v1.0 vs. what is built / planned (2026-10-18)

**Update 2026-10-22:** ADR 0003 closed the four open decisions (DOCX-only intake, capability delegation rule, immediate Owner transfer, admin workspace administration). Still open in code: explicit account security version; storage precedence rework (P2); P2-VS01 Requirements Sets design.

**Reconciliation status (2026-10-18):** docs/skills/agents/CLAUDE.md aligned (brief, tracker, phase checklists, domain/authz/pipeline skills, P2 plan deltas). Code aligned: vocabulary (ADR 0002) and admin content-access structure. **Still open in code/plan:** Workspace Owner + capabilities (P1-VS03); explicit account security version; storage precedence rework (no code yet); DOCX-only intake decision; P2-VS01 Requirements Sets design; P0 frontend/worker/health/Render work; whether admins should lose workspace administration entirely. Slice status: `docs/slice-tracker.md`.

`docs/brief.md` (v1.0) is now the baseline. This lists where code, `CLAUDE.md` and `docs/p2-plan.md` disagree with it. Items marked **DECIDE** need the project owner before work starts.

## 1. Vocabulary (affects API paths, DB, code, UI)
| Built | Brief v1.0 |
|---|---|
| Class (`/classes`, `academic/`, `class` table) | Project Workspace |
| Student | Project Participant |
| Instructor | Project Coordinator |
| (none) | Workspace Owner (single, atomic, transferable) |
| Reviewer / approver | workflow responsibilities of Coordinators |
**DECIDED (ADR 0002, 2026-10-18): public API/UI renamed, DB tables and internal Java names kept.** Original options: rename code/API/DB now (touches ~200 tests, `/api/v1/classes`, `UserRole`, matrix manifest) or keep internal names and rename only the user-facing layer (UI labels, OpenAPI descriptions, docs). Recommended: rename the public API + UI vocabulary, keep DB table names, via an ADR; do it before P2 adds more surface.

## 2. Behavioural gaps in finished P1 code
1. **Admin sees private documents** — brief: admin authority does NOT grant access to project documents, reviewer decisions or approvals. Today `AdminAccess` grants class/project view and manage. DONE structurally in ADR 0002 (no content endpoints exist yet). Needs a split: admin keeps account/infra powers, loses document/review/approval access (and class content access, to be decided precisely).
2. **Workspace Owner + multiple Coordinators + resource capabilities** — we have "assigned instructors" (flat). Need Owner designation, no Owner-less Workspace, ownership transfer with audit, capability set per Coordinator.
3. **Coordinator invitation** by admin (P1-VS01/02) — current admin account creation with temp credential covers most; verify the invitation/expiry wording and that admins cannot set/read passwords (reset-credentials flow is OK).
4. **Account security version** for session revocation — we revalidate per request (`SessionRevalidationFilter`); brief wants an explicit security-version check. Cheap to add; also needed for cross-instance revocation.
5. Participants in multiple Workspaces, one active Project per Workspace — already true.

## 3. Scope changes that alter `docs/p2-plan.md`
- **Requirements Sets move from P4 into P2** (VS01): versioned sets, stable requirement identities, draft/validate/publish/assign, Workspace current assignment, Project bindings, compare/history, copy-on-apply. AI extraction and lineage stay post-MVP.
- **Storage precedence is now Project > Workspace > Administrator default.** The plan's "instructor default" tier is dropped (C-01). Connection ownership/bindings need rework accordingly; Coordinators bind at Workspace/Project level.
- **Intake is DOCX** (upload + Drive link). The plan's PDF/ZIP/image/URL "store-only" kinds are not in the MVP brief. **DECIDED (ADR 0003, 2026-10-22): dropped from the MVP; the per-kind policy design stays extensible.**
- **P2 adds**: reusable configuration templates (copy-on-apply), revision requests (Blocking vs Advisory), deadline extensions, and **controlled requirements/workflow migration** with preview, compatibility check, stale-confirmation rejection (plan currently "refuse re-assign"). Requirements/workflow version is pinned per Project until migrated.
- Workflow: brief mandates a DAG with prerequisites + approvals; the plan's progression modes and presets remain compatible. Sprint/iterative-specific rules are not required for MVP.
- **Frontend is in the MVP** (60 screens, Tailwind, shadcn/ui, TanStack Query, RHF+Zod, Playwright), per phase. The plan is "API-first, UI later"; each slice's acceptance now includes UI and E2E. P1 backend is done without UI: P1-VS06 (dashboards) and the UI parts of VS01–VS05 are open.

## 4. Scope changes for P3 (not yet planned in detail)
Multi-reviewer **independent** review with disagreement adjudication, configurable one-/multi-approver quorum, immutable final-assessment snapshots, publication **revisions** (immutable), feedback bound to a specific artifact version, provider/model registry with qualification gates and routing, bounded retry + distinct-attempt fallback, leases/heartbeats/fencing, quarantine/incident handling, SSE + REST reconciliation. `CLAUDE.md` invariants still hold; invariant 1 ("instructor decides everything") becomes "authorized reviewers/approvers decide; AI never finalizes".

## 5. Phase map
Old: P0 foundation, P1 identity, P2 workflow+intake+Drive, P3 AI+review (MVP), P4 requirements, P5 GitHub/ZIP, P6 reeval/exports, P7 hardening.
New: P0–P3 as in brief (requirements now P2). Post-MVP: code ingestion/verification (old P5), requirements AI extraction/lineage, analytics, extended exports/impact (old P6), SSO. Old P4 shrinks to "AI requirements extraction + traceability".

## 6. P0 slices already satisfied vs. open (verify before marking Accepted)
- Backend, Postgres+Flyway, sessions/CSRF, auth: built and tested (VS02–VS03 backend parts). Per `CLAUDE.md`, the Compose stack and Docker image build are **not yet verified**.
- Open or unverified: frontend foundation (VS01 UI/typed client/Playwright — check whether `frontend/` exists), RabbitMQ + Java/Python worker containers and heartbeat (VS04, planned only in P2 slice 6a), Render Blueprint, health endpoints for broker/workers, correlation logging (partly done via `RequestIdFilter`) (VS05).
Brief's status values should be tracked per slice ID; none is "Accepted" until evidence is recorded.

## 7. Proposed next step
1. Decide vocabulary (section 1) and admin-document-access rule (2.1) — ADR 0002.
2. Re-plan P2 against section 3 (new `docs/p2-plan.md`), keeping the already-planned build order idea (storage/outbox/worker before templates).
3. Close P0 gaps (compose verified, broker + worker skeletons, health) since P2 intake depends on them.

## 8. P1-VS03 (Owner and capabilities) — decisions and open points (2026-10-19, backend, unverified)
- **Owner model:** `academic_class.owner_id` NOT NULL, composite FK `(id, owner_id) -> class_instructor(class_id, user_id)` DEFERRABLE INITIALLY DEFERRED, so the Owner cannot be unassigned and no workspace can commit Owner-less. Backfill = earliest-assigned coordinator, preferring non-deactivated accounts; the migration fails loudly if a dev database holds a workspace without any coordinator.
- **Capabilities:** `class_instructor.capabilities TEXT[]` (CHECK against the 12 names). Existing coordinators were backfilled with MANAGE_SETTINGS, MANAGE_ROSTER, MANAGE_PROJECTS (what they could do before); new ones default to MANAGE_ROSTER + MANAGE_PROJECTS. The Owner holds everything implicitly. Mapping now enforced: settings/archive = MANAGE_SETTINGS; roster, enrollment, credential re-issue, roster import = MANAGE_ROSTER; staff project creation and every project staff/edit operation of a coordinator = MANAGE_PROJECTS; coordinator administration = MANAGE_COORDINATORS. Administrators pass those four (administration) but never a content-related capability.
- **Delegation rule added (not in the brief):** a non-administrator may only grant capabilities they hold themselves (`CAPABILITY_ESCALATION_FORBIDDEN`). **DECIDED (ADR 0003): keep.**
- **Account guards:** deactivating or re-roling an account that owns an ACTIVE workspace is refused (409, count only, no workspace names). Archived workspaces do not block, but unarchiving requires a usable Owner (`WORKSPACE_OWNER_INACTIVE`); ownership transfer is allowed on archived workspaces (owner recovery by an administrator).
- **Decided:** **DECIDED (ADR 0003): administrators keep workspace administration but never content access.** Administrators administer settings/roster/projects/coordinators and recover ownership. **DECIDED (ADR 0003): Owner transfer stays immediate and audited, no acceptance step.** The coordinator list in the workspace view shows only the owner flag; capabilities are served by `GET /workspaces/{id}/coordinators` (staff only).
