# ADR 0003 — Decisions on the open points of the brief reconciliation

Status: accepted, 2026-10-22 (project owner). Source of the questions: `docs/brief-reconciliation.md` sections 3 and 8.

## 1. MVP intake accepts DOCX only
Participants submit DOCX documents, by upload or by a Google Drive link. The old plan's PDF, ZIP, image and URL "store-only" kinds are **dropped from the MVP** (P2). Reasons: the brief v1.0 scope is DOCX, each extra kind adds storage, malware-scan, validation and UI work, and nothing would evaluate them.
Keep the design extensible: `ArtifactKindPolicy` stays a per-kind strategy and `artifact_version.kind` stays a data value, so PDF/ZIP/repository kinds can return post-MVP (code analysis) by adding policies, without a schema change. Do not build the other kinds now.

## 2. Capability delegation stays restricted
A non-administrator may grant a Coordinator only capabilities they hold themselves (`CAPABILITY_ESCALATION_FORBIDDEN`). Holding `MANAGE_COORDINATORS` alone does not let someone hand out `APPROVE`, `PUBLISH` or any other capability they lack. Administrators grant freely (they never gain content capabilities by doing so).

## 3. Ownership transfer is immediate and audited
`PUT /api/v1/workspaces/{id}/owner` takes effect at once; the target must already be an assigned, active Coordinator. The transfer is audited (`WORKSPACE_OWNER_TRANSFERRED`, from and to). No acceptance step: it would add a pending state, expiry, endpoints and UI and would complicate administrator owner-recovery. Revisit if transfers are ever done by mistake in practice.

## 4. Administrators keep workspace administration, never content
Administrators keep workspace administration (settings, roster, projects, coordinators, owner recovery) so orphaned or abandoned workspaces can be handled. They have **no access to documents, reviews or approvals**; that stays structural (`ProjectRole.ADMIN.canAccessContent()` is false and every content endpoint gates on `requireProjectContent` / `requireClassContent`), as in ADR 0002.
