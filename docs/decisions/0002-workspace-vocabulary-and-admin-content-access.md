# 0002 — Workspace/Participant/Coordinator vocabulary; administrators have no content access

Status: accepted (2026-10-18). Source: `docs/brief.md` v1.0 sections 2 and 5.

## Context
Brief v1.0 names the application roles **Project Participant**, **Project Coordinator** and **Administrator**, and the academic container a **Project Workspace** (one designated Owner, several Coordinators). P1 was built with Class / Student / Instructor. The brief also states that administrator authority does not grant access to private project documents, reviewer decisions or approvals.

## Decisions
1. **Public vocabulary is renamed**: REST paths (`/api/v1/classes` -> `/api/v1/workspaces`, `/coordinators`, `eligible-participants`), JSON fields (`workspaceId`, `participantId`, `participantsMayChangeTeams`, `coordinatorIds`, `coordinators`), enum values (`UserRole` PARTICIPANT / COORDINATOR / ADMIN; `TeamFormation` PARTICIPANT_FORMED / COORDINATOR_ASSIGNED; roster conflict `NOT_A_PARTICIPANT`), error codes (e.g. `PARTICIPANT_NOT_FOUND`, `COORDINATORS_REQUIRED`, `TEAMS_ASSIGNED_BY_COORDINATOR`) and user-facing messages. Future UI uses the brief's terms only.
2. **Kept as is (internal)**: DB table and column names (`academic_class`, `class_instructor`, `enrollment`, `student_id`, `students_may_change_teams`), Java type and package names (`academic`, `ClassService` ...), `student_number` / `studentNumber` (the institutional Student ID is a real identifier, not a role name), and **stored audit history** (`audit_log.action` values such as `CLASS_CREATED`, `STUDENT_ENROLLED` are never rewritten). Module/class renames can follow when the Workspace Owner work (P1-VS03) reshapes the module.
3. **Migration V7** rewrites stored enum values (`app_user.role`, `academic_class.team_formation`, `roster_import_row.conflict_kind`) and their CHECK constraints. Existing sessions of re-roled users are dropped by `SessionRevalidationFilter`. Pre-release: no compatibility alias for old paths or values.
4. **Administrators have no automatic content access.** New `ProjectRole.ADMIN` (administers membership, archive, roster; `isStaff()` and `canEdit()` true) is separate from coordinator `STAFF`; `canAccessContent()` is false for ADMIN and NONE. `AccessPolicy.requireProjectContent` / `requireClassContent` are the mandatory gate for every content endpoint (documents, submissions, requirements, workflows, templates, evaluations, reviews, approvals, publications). Administrators and non-members get the same 404 (existence never leaks). No content endpoints exist yet; this is the structure they must use, enforced by `AdminContentAccessTest`, and each new content endpoint needs a matrix row proving ADMIN is refused.
5. Administrators keep account management, storage/AI/infrastructure administration and workspace/project *administration* (roster, membership). Whether administrators should also lose workspace administration entirely (brief: Coordinators configure Workspaces) is left for P1-VS03 together with Owner designation.

## Consequences
- Reviewer, lead reviewer and final approver stay workflow responsibilities of coordinators and are never derived from the admin role.
- Clients written against the old names break; there are none yet.
