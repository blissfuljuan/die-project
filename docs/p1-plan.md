# P1 — Identity and Academic Management: plan

Scope (brief §3.1–3.3, §5.1–5.3). No workflow, template, storage or AI work here.

## Slices (build in order, each with tests)
1. **Auth core** — local login (email+password), session cookie, CSRF, logout, `/api/v1/auth/me`, login throttling, Spring Session JDBC.
2. **Accounts & admin** — admin/instructor CRUD, activate/deactivate, role assignment, credential reset, auth/security events to `audit_log`.
3. **Student activation** — Student ID + temp password -> set email + new password -> verify email -> temp invalidated.
4. **Password reset** — request/confirm via emailed single-use token (no account enumeration).
5. **Classes & enrollment** — create/archive class, assign instructors, manual add/remove student.
6. **Roster import** — CSV/XLSX upload -> preview -> commit; match existing students; skip duplicates; flag identity conflicts; temp credentials generated for new accounts.
7. **Projects & teams** — individual/team projects, student-formed (invitations, size limits) and instructor-assigned teams, representative, membership history, optimistic locking.
8. **Authorization layer** — central `AccessPolicy` (role + class membership + project membership); negative-test matrix for every endpoint.

## Auth decisions
- Passwords and temp credentials: Argon2id (`Argon2PasswordEncoder`); only hashes stored.
- Temp credential: random 12+ chars from a CSPRNG, shown/distributed once (roster-import result file for the instructor; never logged), expires (default 14 days), single active per user, invalidated on activation or admin reset, 5 failed attempts -> locked until admin reset.
- Login identifier: verified email only. Student ID (or email, for staff) is accepted only by the activation endpoints while status = PENDING_ACTIVATION.
- Session: server-managed, Spring Session JDBC (so API can scale to >1 instance), HttpOnly + Secure + SameSite=Lax cookie, 30 min idle timeout, session-fixation protection, revoke-all on password change/reset/deactivation.
- CSRF: cookie repository, SPA sends `X-XSRF-TOKEN`.
- Throttling: `login_attempt` table, per identifier and per IP; generic error messages.
- Future OIDC: authentication goes through an `AuthenticationProvider`; `app_user` has no provider-specific columns, add an `external_identity` table later.
- Email: `EmailSender` interface; logging implementation for dev; SMTP implementation configurable.

## API surface (`/api/v1`)
| Area | Endpoints |
|---|---|
| auth | `POST /auth/login`, `POST /auth/logout`, `GET /auth/me`, `POST /auth/activate`, `POST /auth/verify-email`, `POST /auth/password-reset/request`, `POST /auth/password-reset/confirm`, `POST /auth/password/change` |
| users | `GET/POST /users`, `GET/PATCH /users/{id}`, `POST /users/{id}/deactivate`, `POST /users/{id}/activate`, `POST /users/{id}/reset-credentials` (admin) |
| classes | `GET/POST /classes`, `GET/PATCH /classes/{id}`, `POST /classes/{id}/archive`, `PUT /classes/{id}/instructors` |
| enrollment | `GET /classes/{id}/enrollments`, `POST /classes/{id}/enrollments`, `DELETE /classes/{id}/enrollments/{studentId}` |
| roster | `POST /classes/{id}/roster-imports` (multipart -> preview), `GET /roster-imports/{id}`, `POST /roster-imports/{id}/commit`, `POST /roster-imports/{id}/rows/{n}/resolve`, `DELETE /roster-imports/{id}` |
| projects | `GET/POST /classes/{id}/projects`, `GET/PATCH /projects/{id}`, `GET /projects/{id}/members` |
| teams | `POST /projects/{id}/invitations`, `POST /invitations/{id}/accept|decline`, `PUT /projects/{id}/members` (instructor), `PUT /projects/{id}/representative` |

Errors: RFC 9457 problem details. Out-of-scope resources return 404, not 403, where existence would leak.

## Roster import rules
- Required columns: Student ID, Last Name, First Name. Optional: Middle Name, Email, Program, Section.
- Row outcomes: `CREATE` (new account), `MATCH` (existing student, add enrollment), `SKIP_DUPLICATE` (already enrolled / repeated in file), `CONFLICT` (same Student ID with different name, or email owned by another user) -> instructor must resolve before commit; unresolved conflicts block commit.
- Preview persists parsed rows; commit is transactional and idempotent (re-commit is a no-op).
- Limits: file size and row count caps; reject formulas/macros in XLSX; treat all cell content as text.

## Authorization rules (P1)
- Admin: all users/classes. Instructor: only classes they are assigned to; may create students via roster only. Student: own profile, own classes, own projects/teams.
- Every endpoint gets a negative test: cross-class instructor, student reading another student's data, student calling instructor routes.

## Definition of done
Brief §10 criteria 1–4 pass end to end; authorization matrix tests green; Flyway migrations apply on a clean DB; audit rows written for role/credential/activation/deactivation events.
