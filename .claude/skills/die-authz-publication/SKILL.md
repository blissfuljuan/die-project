---
name: die-authz-publication
description: Authorization model and publication/visibility rules for DIE (brief v1.0). Use when adding or reviewing any endpoint, query, export, notification, or SSE stream that could expose project content or evaluation data.
---

# Authorization and publication

## Access inputs
Application role (Participant / Coordinator / Administrator) + Workspace membership/assignment + Owner + per-Coordinator resource capabilities + project/team membership + reviewer assignment + approver assignment + workflow permission + storage-connection ownership. Backend is authoritative.

## Administrator rule (ADR 0002)
Administrators manage accounts, storage config, AI providers, qualification/routing, infrastructure health and recovery. They have **no automatic access** to private project documents, reviewer decisions or approvals. Every content endpoint (requirements, workflows, templates, artifacts, evaluations, reviews, approvals, publications) must call `AccessPolicy.requireProjectContent` / `requireClassContent` (admins and outsiders get the identical 404) and carry a matrix row proving ADMIN is refused. `ProjectRole.ADMIN` may administer membership/archive only.

## Visibility tiers for evaluation data
1. **Private/internal**: raw AI findings, suggested scores, individual reviewer decisions and comments, rejected recommendations, drafts. Assigned Coordinators only; never Participants, never Administrators.
2. **Approved**: final assessment snapshot per approval policy. Still not visible to Participants.
3. **Published**: explicitly published, participant-safe subset bound to one immutable artifact version; corrections are new revisions. Only this tier is queryable by Participants.

## Implementation rules
- Publication is a distinct record; participant queries join through published items — filter in query/repository, never only in DTO mapping or UI.
- Same rule for exports, notifications, SSE, search, error messages (404 for out-of-scope resources, no existence leaks).
- Participants access only their own projects' files; downloads stream through the API after authorization; no Drive URLs.
- Reviewer independence: a reviewer cannot read other reviewers' decisions before submitting their own (unless the policy allows) and decisions stay attributable.
- Every new endpoint gets a row in `EndpointAuthorizationMatrixTest.manifest()` and negative tests (cross-workspace, cross-project, cross-reviewer, participant vs unpublished, admin vs content).
- Audit: submissions, reviews, approvals, publications, role/credential changes, storage connection and security-sensitive admin changes, requirement/configuration changes, finding supersession, migrations.

## How it is implemented
All decisions go through `com.blissfuljuan.dieapi.access.service.AccessPolicy` (facade over `ClassAccess`/`ProjectAccess`; one `RoleAccess` strategy per role: `AdminAccess`, `CoordinatorAccess`, `ParticipantAccess`). Pattern: `requireClassView/Manage/Content`, `requireProjectView/Edit/Staff/Content` first (outsider or missing -> 404, visible-but-not-allowed -> 403), then act. Listings use `visibleClasses/visibleProjects` SQL scopes. Owner and capabilities (P1-VS03): `requireWorkspaceCapability(user, workspaceId, WorkspaceCapability.X)` for every sensitive coordinator operation (settings, roster, projects, coordinators; later CONFIGURE_*, REVIEW, APPROVE, PUBLISH ...), `requireWorkspaceOwner` for ownership transfer, `capabilitiesOf` for the backend-derived `myCapabilities` in views. Coordinators need the capability (the Owner has all), participants get 403, outsiders 404; administrators pass only `administrative()` capabilities and never content ones (content-related capabilities must also go through `requireClassContent/requireProjectContent`). After taking a row lock, re-authorize with `ClassLocks.lock*(user, id, capability)`. Add the remaining resource checks (reviewer, approver, storage ownership) as collaborators of AccessPolicy.
