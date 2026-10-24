# P1 acceptance record (legacy MVP criteria 1-4)

> Reconciled 2026-10-18: this record covers the **backend** of the old criteria 1-4 using the old vocabulary (student/instructor/class = Participant/Coordinator/Workspace; paths are now `/api/v1/workspaces`). It is NOT acceptance of the brief v1.0 P1 exit gate, which needs the Workspace Owner model, dashboards/navigation and UI plus E2E evidence. Status per slice: `docs/slice-tracker.md`. The test path below also predates the package rename (`com.blissfuljuan.dieapi`).


Verified 2026-10-15 by `backend/src/test/java/io/die/evaluator/P1AcceptanceTest.java` (end to end through the HTTP API; only the first administrator is seeded, as `BootstrapAdmin` does in production) and by the per-slice suites. Full suite: 101 tests passing in Docker.

| # | Criterion | Evidence |
|---|---|---|
| 1 | An administrator can create or import student accounts | Admin `POST /api/v1/users` creates a student (one-time temporary credential); class staff — admins included — import rosters (`/classes/{id}/roster-imports` -> commit) which create pending student accounts with hashed credentials. `AccountManagementIntegrationTest`, `RosterImportIntegrationTest`, acceptance test. |
| 2 | Students can activate their accounts and securely sign in | Student ID + temporary credential -> email + new password -> emailed single-use link -> ACTIVE; both admin-created and imported accounts. Sign-in: Argon2 hashes, server-side JDBC session, `HttpOnly; Secure; SameSite=Lax` cookie, CSRF required, throttling, session revocation on deactivation/role change/password change/reset, per-request account revalidation. `ActivationIntegrationTest`, `AuthIntegrationTest`, `PasswordIntegrationTest`, `SecurityHardeningIntegrationTest`, acceptance test. |
| 3 | An instructor can create a class and manage its student roster | Class create/edit/archive, instructor assignment, manual enroll/remove, CSV/XLSX roster import with preview, conflict resolution and commit. `ClassAccessIntegrationTest`, `RosterImportIntegrationTest`, acceptance test. |
| 4 | Individual and group projects can be established | Staff-assigned individual and team projects; student-formed teams with invitations, size limits, representative, membership history, optimistic locking. `ProjectIntegrationTest`, acceptance test. |

## Authorization audit
`EndpointAuthorizationMatrixTest` lists every `/api/v1` endpoint with its rule. The build fails if an endpoint is added without a rule, if an anonymous caller reaches a non-public endpoint, or if any actor that must be refused (other class's staff, other class's students, unrelated students, classmates not on the team, plain members, representatives on staff-only operations, non-invitees) gets anything other than the declared 403/404 — and it asserts that the data is unchanged after ~230 denied calls. GET endpoints are also called as an allowed actor so a 404 is a real denial.

## Security review (die-security-reviewer, class/roster/project code)
Findings H1, H2, M1, M3, M4, M5, M6 were fixed and tested (see CLAUDE.md "P1 security review #2"); M2 and the Low items remain tracked there.

## Not covered by P1 (later phases)
Workflows, templates, document intake, Drive storage (P2); AI evaluation, review, publication, Render deployment, backup/restore demonstration (P3). MVP criteria 5-20 are therefore still open.
