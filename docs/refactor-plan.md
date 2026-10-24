# Refactor plan — SOLID and design patterns in existing code

**Status: all steps done (see CLAUDE.md "Refactor status").** Audit of `backend/src/main/java` (6.1k lines, 2026-10-16). Goal: behaviour-preserving refactors; the existing test suite (161 tests, including `EndpointAuthorizationMatrixTest` and the locking/race tests) must pass unchanged after every step. Run via the Docker command in README (no local JDK). One step = one commit.

## Findings
| # | Where | Problem | Principle |
|---|---|---|---|
| F1 | Role/status/kind as strings everywhere (73 literals in 8 files: `"STUDENT"`, `"REPRESENTATIVE"`, `"INDIVIDUAL"`, `"STUDENT_FORMED"`, `"ARCHIVED"`...) | typo-prone, no exhaustiveness checks, logic scattered | typed values |
| F2 | `access.service.AccessPolicy` (182 lines) | writes SQL itself in the service layer; handles class + project + SQL scopes; each new resource adds more `switch (role)` | S, O, D |
| F3 | `project.service.ProjectService` (508 lines) | queries, creation flows, edits, membership diffing, invitations in one class; `create` branches on actor x kind; repeated lock -> re-check -> version -> audit skeleton; nested request records and `ResponseStatusException` mixed with `ApiException` | S, O, convention |
| F4 | `roster.service.RosterParser` (358) | CSV and XLSX handling in one class; row validation mixed with file reading | S, O |
| F5 | `roster.service.RosterImportService` (360), `RosterAnalyzer` | outcome/conflict handling by type checks; commit phases mixed with persistence | S, O |
| F6 | `academic.service.ClassService` (306) | CRUD, archive, instructors, enrollment orchestration together | S |
| F7 | `audit.record(...)` called inline from every service | action names are strings, coupling every use case to auditing | O, D |
| F8 | Throttle/lockout logic duplicated in `LoginThrottleService`, `ActivationService`, `PasswordService` | one concept, three implementations | DRY, S |
| F9 | Conditional `ResponseStatusException` vs `ApiException.*` | two error idioms | convention |

## Steps (in order; each independent and shippable)
1. [x] **R1 Typed values (F1).** *(done 2026-10-16, 161 tests)* Enums `UserRole`, `ProjectKind`, `TeamRole`, `TeamFormation`, `ProjectStatus`, `EnrollmentStatus`, `ClassStatus` in each module's `model/`, converted at the repository boundary (`JdbcClient` mapping). No behaviour change. `AppUserDetails.getRole()` stays a String for Spring Security; add `UserRole role()`.
2. [x] **R2 Error idiom (F9).** *(done 2026-10-16, 161 tests)* Replace `ResponseStatusException` in services with `ApiException` and specific codes (add codes to the skill list). Response shape unchanged for existing tests (assert status+code).
3. [x] **R3 AccessPolicy (F2).** *(done 2026-10-16, 162 tests)* Move SQL into `access/repository` (`AccessRepository`); split into `ClassAccess` and `ProjectAccess`; `AccessPolicy` stays the facade with the same public methods so callers do not change. Role-specific logic (scope SQL, role resolution) becomes a `RoleScopeStrategy` per `UserRole` selected from a map, replacing the repeated `switch`.
4. [x] **R4 ProjectService split (F3).** *(done 2026-10-16, 171 tests; `ProjectService` removed, controller uses the five services directly)*
   - Move `CreateProject`/`UpdateProject`/`SetMembers` records to `project/model`.
   - `ProjectQueryService` (list/get/history/eligible/invitations).
   - `ProjectCreationService` with a `ProjectCreationStrategy` per case (`StudentTeamCreation`, `StaffIndividualCreation`, `StaffTeamCreation`) chosen by a factory.
   - `MembershipReconciler` (the removal -> demotion -> promotion -> addition diff) as a plain, unit-tested class.
   - `ProjectMutation` template helper for lock class -> lock project -> authorization re-check -> version check -> audit, used by update/archive/setMembers/invitation flows.
   - `InvitationService` for invite/accept/decline/cancel. `ProjectService` disappears or becomes a thin facade for the controller.
5. [x] **R5 Roster (F4, F5).** *(done 2026-10-16, 189 tests)* `RosterFileReader` strategy (`CsvRosterReader`, `XlsxRosterReader`) chosen by `RosterReaderFactory`; `RosterRowValidator` separate; conflict kinds and resolutions as an enum with a `ConflictResolver` strategy per kind; `RosterImportService` keeps only orchestration/phases.
6. [x] **R6 ClassService (F6).** *(done 2026-10-16, 189 tests)* Split into `ClassService` (CRUD/archive), `ClassInstructorService`, `ClassEnrollmentService` (uses existing `EnrollmentRules`).
7. [x] **R7 Audit via events (F7).** *(done 2026-10-16, 194 tests; implemented as one `AuditEvent` record + `AuditAction` enum and a plain synchronous `@EventListener` — not BEFORE_COMMIT — so rows stay in the publisher's transaction, honour noRollbackFor and are written when there is no transaction)* Domain events (`ProjectArchived`, `MembersChanged`, ...) published with `ApplicationEventPublisher`; an `AuditEventListener` writes `audit_log`. Must stay in the same transaction (use a `@TransactionalEventListener(phase = BEFORE_COMMIT)`) so audit rows keep committing atomically with the change. Do this last of the structural steps and only if events pay off for P2 (stage recomputation, notifications).
8. [x] **R8 Throttling (F8).** *(done 2026-10-16, 200 tests; shared piece = windowed throttle: `ThrottlePolicy`, `ThrottleBucket`, `LoginAttemptRepository`; the per-credential lockout in `ActivationService` is a different concept and was left alone)* Extract a `ThrottlePolicy` + `AttemptCounter` used by login, activation and password flows; keep the existing limits and messages (covered by auth tests).

## Guard rails
- After each step: full `mvn test` via Docker, ArchUnit green; no endpoint or response changes (the matrix test is the safety net).
- Add ArchUnit rules as seams appear (no SQL/JdbcClient in `..service..` after R3; no string role literals once R1 lands).
- New P2 code follows the same standards from day one, so the refactor and P2 do not conflict: R1-R3 should land **before** P2 slice 4 starts, because P2 builds on `AccessPolicy`, roles and `ProjectRole`.
- Update `CLAUDE.md` status after each step.

## Follow-ups (done 2026-10-16)
- [x] **SQL out of every service**: activation/password-reset/login-user/temp-credential/bootstrap-admin/audit-log SQL moved to repositories; ArchUnit `servicesDoNotUseJdbc` now covers all `..service..` packages.
- [x] **RosterImportService <= 250 lines** (225): `RosterCommitSupport` (stale check, summary JSON).
- [x] **`AuditResource` enum** for audit `resource_type`.
- [x] Docs (CLAUDE.md, die-code-conventions skill) reconciled with the final code.
