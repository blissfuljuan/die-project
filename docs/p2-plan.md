# P2 — Requirements, SDLC Workflow and Document Intake: plan (reconciled with brief v1.0, 2026-10-18)

**Authoritative scope: `docs/brief.md` P2-VS01..VS06. Status: `docs/slice-tracker.md`.** This file keeps the earlier detailed design; where it conflicts with the brief, the table below wins. Vocabulary: Workspace (was class), Participant (participant), Coordinator (coordinator) — see ADR 0002; internal table/package names are unchanged, public API paths use `/workspaces`.

## Mapping and deltas (brief slice -> sections below)
| Brief slice | Detailed design below | Changes required by the brief |
|---|---|---|
| P2-VS01 Requirements Sets | **new, not designed yet** (moved here from P4) | Stable requirement identities, versions, draft/validate/publish/assign as separate operations, Workspace current assignment, Project binding kept until migration, compare/history, copy-on-apply configuration templates. AI extraction/lineage stays post-MVP. |
| P2-VS02 Workflow designer | Slice 1 + 2 | Add reusable configuration templates (copy-on-apply). DAG with prerequisites and approvals (progression modes stay compatible). Sprint/iterative specifics are not MVP. Re-assignment after submissions becomes the VS06 migration, not "refuse". |
| P2-VS03 Artifact definitions + DOCX templates | Slice 3 | Add **artifact definition** (obligation + submission policy) linked to stage; template preview; DOCX only (no PDF/ZIP/image/URL store-only kinds in the MVP — **decided in ADR 0003: dropped**). |
| P2-VS04 Intake + Drive | Slice 4, 5, 6 (storage, drafts, upload, link import) | **Destination precedence is Project > Workspace > Administrator default** — remove the "coordinator default" tier and per-coordinator personal connections from slice 4; Workspace-level override replaces it. Configured-but-unavailable destination blocks (no silent fallback). Backend-persisted drafts. |
| P2-VS05 Validation + corrections | Slice 6 (validation) | Add Blocking/Advisory revision requests, correction requests, deadlines + authorized extensions (late handling becomes extensions). Failed drafts stay correctable. |
| P2-VS06 History + migration | Slice 5 history, Slice 7 | Add requirements/workflow migration: preview, compatibility validation, stale-confirmation rejection, atomic per Project, preserve drafts and running evaluations, map stage history. |
Build order proposal: storage + local provider -> outbox/RabbitMQ/Python worker/upload safety (needs P0-VS04) -> VS01 -> VS02 -> VS03 -> VS04 -> VS05 -> VS06 -> Drive OAuth live acceptance. **Frontend is in scope for every slice** (brief section 16.1): each slice's acceptance includes its screens and a Playwright path; the earlier "API-first" note below is superseded. Prerequisites: P0-VS01 (frontend foundation) and P0-VS04 (worker/broker skeleton).
Admin rule: no endpoint added here may let an administrator read documents, templates' private drafts or submissions (`AccessPolicy.requireProjectContent`/`requireClassContent`); administrators only configure the Administrator default storage destination.

---
# Original detailed design (pre-brief-v1.0; read with the deltas above)

Scope: brief §3.4–3.7 (+ §10 criteria 5–10). No rubrics, no LLM, no outbox/RabbitMQ evaluation flow (P3), no requirements (P4), no repo/ZIP analysis (P5). ZIP/images/URLs/PDF are **not** accepted in the MVP (ADR 0003); keep `ArtifactKindPolicy` extensible.
Follows the P1 conventions: every module is `<module>/{controller,view,model,service,repository}`, every endpoint gets a row in `EndpointAuthorizationMatrixTest.manifest()`, every feature ships negative authorization tests, errors via `ApiException`.

## New modules
| Module | Responsibility |
|---|---|
| `workflow/` | workflows, versions, stages, deliverables, dependencies, progression rules, copy/share, project binding, stage-availability evaluation |
| `template/` | DOCX templates, versions, section/validation rules, publish, download |
| `storage/` | provider abstraction, connections, destination resolution, Google Drive provider, health |
| `submission/` | submissions, artifact versions, drafts, official submit, revisions, validation results, history |
| `intake/` (or inside `submission/service`) | upload safety pipeline (type sniff, size, archive rules, malware scan), DOCX structural validator, Drive link import |

Cycles to avoid (ArchUnit does not yet forbid them): `submission -> workflow/template/storage` only; `workflow` must not depend on `submission` — stage availability takes a small port (`StageProgressPort`) implemented by `submission`.

## Slices (each with tests and matrix rows)
**Build order (differs from numbering): 4 (storage, local provider first) -> 6a (outbox + RabbitMQ + Python validation worker + upload safety) -> 1 -> 3 -> 2 -> 5 -> 6b (Drive OAuth + link import, needs the user's Google credentials) -> 7.** Templates need storage and the intake pipeline, so those come first.
**Frontend:** P2 is API-first; acceptance is through tests (`P2AcceptanceTest`). UI slices follow in a separate plan unless decided otherwise.

### Slice 1 — Workflow definitions & versioning (`V7__workflow.sql`)
- Tables: `workflow` (owner, scope `ADMIN_TEMPLATE|PRIVATE|SHARED`, current draft), `workflow_version` (immutable once published; `version_no`, `status DRAFT|PUBLISHED|RETIRED`, `published_at/by`), `stage` (kind `PHASE|SPRINT`, order, goal, opens-at/due-at, `progression_mode`, `approval_required`, `revision_limit`), `stage_dependency` (stage -> prerequisite stage, `condition SUBMITTED|APPROVED`), `deliverable` (stage, name, `validation_mode STRICT|WARNING`, `accepts` kinds, `required`, due override, template assignment later).
- Progression modes (brief §3.4): `AFTER_PREREQ_SUBMITTED`, `AFTER_INSTRUCTOR_APPROVAL`, `SCHEDULED`, `MANUAL`, `ALL_DEPENDENCIES`, `PARALLEL`. Stage completion is **never** set by an AI score (invariant 2): completion = coordinator-defined condition + recorded approval; P2 stores the rule and the approval flag, P3 supplies the approval.
- Draft edit -> `POST .../validate` (acyclic dependency graph, every stage reachable, deliverables have names, scheduled stages have dates, sprints only in iterative workflows) -> `POST .../publish` creates an immutable version. Edit of a published version = new draft version.
- Copy = deep independent copy (new `workflow`, owner = caller, no link back except `copied_from` for provenance). Share = copy-to-coordinator (independent), admin templates copyable by any coordinator. Visibility: admin templates visible to all staff; private only to owner (+admin); shared copies belong to the recipient.
- Preset library seeded by migration/data: Waterfall, Iterative, Agile/Scrum (admin-owned templates).
- Optimistic `version` on workflow drafts (concurrent edits -> 409).
- **Immutability guards (DB level):** triggers reject UPDATE/DELETE on published `workflow_version` and its stages, dependencies and deliverables, on published `template_version` (+ sections/rules), and on official `artifact_version` rows. Test attempts to violate them as `die_app`.
- Copying a workflow whose deliverables reference another owner's templates: copy the referenced template versions into the copier's ownership (independent copies, no linked templates); admin-owned templates are copied the same way.
- Visual diagram data: API returns stages + dependency edges (nodes/edges); rendering is frontend.

### Slice 2 — Assign workflow to projects (`V8__project_workflow.sql`)
- `project.workflow_version_id` (column exists since V3) becomes a FK; class-level default `class.default_workflow_version_id`; coordinators of the class (or admin) assign a **published** version only. Re-assigning after submissions exist is refused (409) unless explicit migration by coordinator (kept minimal: refuse in P2, document).
- `project_stage_override` (project, stage, kind `MANUAL_OPEN|MANUAL_CLOSE|APPROVED`, actor, reason, timestamp) persists only coordinator decisions. Stage status (`LOCKED|AVAILABLE|IN_PROGRESS|SUBMITTED|APPROVED|COMPLETED`) is **computed on read** by `StageAvailabilityService` from the workflow rules, submissions and overrides, so there is no scheduled sweep and no multi-instance race; scheduled dates are simple comparisons. Manual open/close by staff is audited.
- **Template for in-flight projects:** a deliverable pins a template version at workflow publish, but staff may set a per-project-deliverable template override (to a newer published version of the same template) until its first official submission; after that, only an explicit coordinator "migrate" action (new submission version against the new template; old version and evaluations keep the old template).
- Participants see the stage tree and their own states only (project membership via `AccessPolicy.requireProjectView`).

### Slice 3 — DOCX templates (`V9__template.sql`)
- `document_template`, `template_version` (immutable once published: file ref, checksum, instructions, `status`), `template_section` (heading text/level/order, `MANDATORY|OPTIONAL`, aliases, min words optional), `template_rule` (required tables/fields, requirement-numbering regex, heading-structure constraints).
- Upload (DOCX only, size cap) -> same intake safety pipeline as slice 6 (no macros: reject `.docm`/vbaProject, no external relationships) -> sections auto-suggested from heading styles (deterministic) -> coordinator edits mandatory/optional -> publish.
- Assign to deliverable: `deliverable.template_version_id` bound at **workflow publish** (published workflow version pins template versions; invariant 8). Template revision = new version; existing submissions keep theirs; explicit coordinator "migrate" action is a later, separate request (stub only).
- Participant download: only for deliverables of projects they belong to, where the stage is `AVAILABLE`+; response `Content-Disposition: attachment`, `X-Content-Type-Options: nosniff`.
- Template files live in the storage abstraction (coordinator/admin destination), metadata + checksum in Postgres.

### Slice 4 — Storage abstraction + Google Drive (`V10__storage.sql`)
- Interface `StorageProvider` (`put(stream, meta) -> StorageRef`, `open(ref)`, `exists`, `delete`-never-used-for-official-versions, `health`). `StorageRef` = provider + opaque id + checksum + size; immutable. S3 later = another implementation; no provider types leak into submission code.
- Tables: `storage_connection` (owner user or SYSTEM, provider, display name, `status ACTIVE|NEEDS_REAUTH|REVOKED|ERROR`, destination folder/shared-drive id, last health check/err), `storage_credential` (encrypted refresh token; **encryption key from env/secret store, never in DB**, key id for rotation), `storage_binding` (scope PROJECT|CLASS|INSTRUCTOR_DEFAULT|ADMIN_DEFAULT -> connection).
- Resolver: project > class > coordinator default > admin default (`StorageDestinationResolver`). Authorization: a connection can be bound only by its owner (or admin for system), coordinators may use only their own connections (invariant 10 / storage-connection ownership).
- Google OAuth (backend-controlled authorization-code flow, state + PKCE; Shared Drive support via `supportsAllDrives`). **Open before building:** `drive.file` cannot reach folders/Shared Drives the app did not create, so choose between the Google Picker (keeps `drive.file`) and a broader scope; public-link import must not depend on the user's OAuth, must not follow redirects outside Google hosts, and must handle large-file virus-warning interstitials.
- Lifecycle: deactivating or re-roling the connection owner marks their connections/bindings unusable; the resolved connection id is recorded on every artifact; changing a binding mid-project does not move existing files (storage migration is a separate, later action). Tokens never returned by any API or logged. Callback endpoint is the only unauthenticated-looking route and is bound to the session + state.
- Health: manual `POST /storage-connections/{id}/check` + scheduled check; failures surface to coordinator/admin; revoked/expired -> `NEEDS_REAUTH`, new submissions to that destination fail with a clear `STORAGE_UNAVAILABLE` (503) while existing metadata stays intact.
- Local/dev + tests: `LocalFilesystemStorageProvider` (profile `local`/`test`) and a fake Drive client; **Drive itself is verified manually** (no real Google calls in CI).
- Files are never publicly shared; downloads are streamed through the API after an authorization check. Participants get no Drive URLs.

### Slice 5 — Submissions, versions, drafts (`V11__submission.sql`)
- Tables: `submission` (project, deliverable, `status DRAFT|SUBMITTED`, current official version), `artifact_version` (submission, `version_no`, kind `DOCX|PDF|IMAGE|ZIP|TEST_EVIDENCE|URL|REPO_REF`, `evaluable boolean`, storage ref, sha-256, size, original filename (sanitised, display only), media type sniffed, uploader, submitter, `official boolean`, `template_version_id` pinned, `source` UPLOAD|DRIVE_LINK, `source_url`, timestamps), `submission_package` for stage packages (set of artifact versions submitted together).
- Rules: draft files are replaceable (old draft files kept as superseded, not hard-deleted); **official versions are immutable and never deleted or overwritten**; revision = new `artifact_version` with `version_no+1`; revision limits and deadlines enforced from the stage config (late handling: coordinator-configurable accept-late flag; otherwise 409 `DEADLINE_PASSED`); unique `(submission, version_no)` + row lock on submission for concurrent submits (same pattern as `ClassRepository.lock`).
- Who may submit: project members (team) / the individual project member; representative-only submission is a class/stage policy flag (default: any member may upload drafts, official submit by representative or any member per class policy — decide in slice 5 kickoff). Uploader and submitter both recorded.
- Model details: validation state is separate from submission state (draft -> validated -> submitted -> revised from the domain model is derived from `status` + the version's validation result). A stage package is submitted atomically: all required deliverables valid per their mode, otherwise nothing becomes official. A draft is a single slot with a version check (concurrent edits -> 409). Revision limit counts official versions only. Deadlines are `timestamptz`, interpreted in a class-level time zone. Submission confirmation notification goes through `EmailSender`.
- Official submit triggers deterministic validation only; **never** an LLM evaluation (invariant 3). Event `SubmissionOfficial` updates stage state (slice 2 port).
- History endpoint: all versions with validation summary, template version, who/when. Participants see only their project's submissions; staff see their classes'.

### Slice 6 — Upload safety, validation, Drive link import
- Upload pipeline (`intake/`): size cap, extension + content sniff (DOCX = valid OOXML ZIP with `word/document.xml`; PDF magic + structure sanity), ZIP-bomb guard (entry count, unpacked budget, ratio — reuse the XLSX preflight approach), reject macros/external relationships/embedded OLE, filename sanitising, **malware scan** via ClamAV container (Compose service; scan result stored on the artifact; infected/unscannable -> rejected or quarantined, never offered for download). Participant code/archives are never extracted-and-executed.
- Structural validator (deterministic, runs in the Python worker): maps document headings to template sections (mandatory present, order, heading levels), required tables/fields, numbering convention. Output = `validation_result` rows (`rule`, `severity ERROR|WARNING`, `location` evidence ref: heading/paragraph index, message) stored per artifact version, tied to the template version used.
- Mode per deliverable: `STRICT` -> official submit refused (422 `STRUCTURE_INVALID`) while any ERROR exists; `WARNING` -> allowed, violations stay visible to participant and coordinator and are flagged on the official version.
- PDFs and store-only kinds: validated for type/size/malware only; `evaluable` flag distinguishes DOCX/PDF from store-only (UI labels it).
- Drive link import: accept only `drive.google.com`/`docs.google.com` file URL patterns (parse the file id; no arbitrary URL fetching -> **SSRF-safe by construction**), fetch via the system/coordinator connection or public export, enforce size cap while streaming, verify it is a real DOCX/PDF, then **copy into the resolved destination** and run the same pipeline; inaccessible/private/oversized -> specific error codes (`DRIVE_LINK_INACCESSIBLE`, etc.). The link is stored only as provenance.
- **Execution model (decided, slice 6a):** untrusted documents are never parsed in the API JVM (invariants 5, 7). P2 introduces the minimal asynchronous backbone that P3 reuses: transactional outbox (`outbox_event`) -> RabbitMQ (durable queues, persistent messages, publisher confirms, manual acks, retry + DLQ) -> **Python document worker** doing DOCX/PDF parsing, section detection and deterministic validation, returning results with evidence locations. Postgres holds authoritative job state (`intake_job`: `QUEUED|RUNNING|SUCCEEDED|FAILED`, idempotent by artifact checksum + template version, recoverable on restart). The message contract is language-neutral JSON (artifact ref, checksum, template rules in; rule outcomes + locations out). The worker is the only validator implementation, so nothing is rewritten in P3. Drive link fetching also runs as a job, never in the request thread, and never holds a transaction or lock while fetching.
- **Malware scan fails closed:** if ClamAV is unreachable the artifact stays in a pending/draft state with a retryable `SCAN_UNAVAILABLE`; it can never be submitted or downloaded unscanned.

### Slice 7 — Audit, authorization matrix, acceptance
- Audit events: workflow publish/copy/share, template publish, storage connection create/bind/revoke/health change, workflow assignment, manual stage open/close, draft upload, official submission, revision, validation override/migration requests.
- Extend `EndpointAuthorizationMatrixTest` rows for all new endpoints; negative tests: cross-class coordinator, coordinator using another coordinator's connection, participant reading another project's artifacts, participant downloading a template for a locked stage, participant downloading unpublished template versions, private workflow visible to non-owner, anonymous.
- `P2AcceptanceTest` proves MVP criteria 5–10 end to end; record in `docs/p2-acceptance.md`.
- Run die-qa on the slice, die-security-reviewer on file handling, Drive OAuth and download authorization; update CLAUDE.md status and add ADRs.

## API surface (`/api/v1`, sketch)
| Area | Endpoints |
|---|---|
| workflows | `GET/POST /workflows`, `GET/PATCH /workflows/{id}`, `POST /workflows/{id}/copy`, `POST /workflows/{id}/share`, `PUT /workflow-versions/{id}` (draft body: stages, deps, deliverables), `POST /workflow-versions/{id}/validate`, `POST /workflow-versions/{id}/publish`, `GET /workflow-versions/{id}/graph` |
| assignment | `PUT /projects/{id}/workflow`, `PUT /workspaces/{id}/default-workflow`, `GET /projects/{id}/stages`, `POST /projects/{id}/stages/{stageId}/open|close` (staff) |
| templates | `GET/POST /templates`, `POST /templates/{id}/versions` (multipart), `PUT /template-versions/{id}/sections`, `POST /template-versions/{id}/publish`, `GET /template-versions/{id}/download` |
| storage | `GET/POST /storage-connections`, `GET /storage-connections/oauth/google/start|callback`, `POST /storage-connections/{id}/check`, `DELETE /storage-connections/{id}`, `PUT /storage-bindings` |
| submissions | `GET /projects/{id}/deliverables`, `POST /projects/{id}/deliverables/{did}/artifacts` (upload), `POST .../artifacts/drive-link`, `GET .../submission`, `POST .../submit`, `GET .../history`, `GET /artifact-versions/{id}/download`, `GET /artifact-versions/{id}/validation` |

## SOLID applied to the P2 design
All of this lives inside the MVC layout (`controller/view/model/service/repository`), checked against `MvcArchitectureTest`: only the first segment under the module is constrained and layer rules use `..layer..` patterns, so sub-packages are allowed. Placement:
- **Pure domain rules** (`ProgressionRule` + implementations, `ArtifactKindPolicy`, destination-precedence logic, validation outcome types) go in `<module>/model/rule/`: plain classes, no Spring, no JDBC. The layering test already forbids `model` from depending on service, repository, controller or view, so they cannot pick up infrastructure.
- **Ports needing infrastructure** (`StorageProvider` + `ObjectReader/ObjectWriter/HealthProbe`, `VirusScanner`, `IntakeJobPublisher`, `StageProgressPort`) are interfaces in `<module>/service/port/`; **adapters** (`LocalFilesystemStorageProvider`, `GoogleDriveStorageProvider`, `ClamAvScanner`) in `<module>/service/adapter/`, annotated `@Component` (the Component rule requires `..service..`). Services, controllers and other modules depend on the port package only.
- Registries (`ProgressionRuleRegistry`, `StorageProviderRegistry`) are services that collect all beans of a port type.
- Results of ports and rules shared across layers are records in `model/` (never nested in a service or adapter). Abstract only at a real seam: a second implementation exists or is planned (Drive/S3, Java/Python), or a process boundary (worker). No interface for a class with a single foreseeable implementation.

- **S — single responsibility.** One reason to change per class:
  - `workflow`: `WorkflowService` (draft CRUD), `WorkflowGraphValidator` (acyclic/reachable/dates), `WorkflowPublisher` (freeze a version), `WorkflowCopier` (deep copy incl. templates), `StageAvailabilityService` (read-only computation), `ProjectWorkflowAssignmentService`.
  - `submission`: `DraftService` (upload/replace drafts), `OfficialSubmissionService` (rules, deadlines, limits, atomic package submit), `SubmissionHistoryQuery` (read projections), `ValidationResultService` (stores worker results).
  - `storage`: `StorageDestinationResolver` (precedence only), `StorageConnectionService` (lifecycle), `GoogleOAuthService` (token exchange), `ConnectionHealthService`, `CredentialVault` (encrypt/decrypt tokens).
  - Controllers only parse, call one service, and return a view; repositories only persist; audit goes through `AuditService`, never inline SQL.
- **O — open/closed.** New behaviour is added by new classes, not by editing switches:
  - `ProgressionRule` strategy, one implementation per mode (`AfterPrerequisiteSubmitted`, `AfterApproval`, `Scheduled`, `Manual`, `AllDependencies`, `Parallel`) selected from a registry keyed by `progression_mode`.
  - `ArtifactKindPolicy` per kind (DOCX, PDF, IMAGE, URL, ZIP...: accepted types, size caps, scan and validation requirements, `evaluable`). P5 adds repository/ZIP analysis by adding policies.
  - Worker side: each structural check is a separate rule class registered in a rule registry (`ValidationRule` -> list of outcomes with locations); templates just configure which rules apply.
  - `StorageProvider` implementations are looked up by provider key; S3 is a new class plus a config entry.
- **L — Liskov substitution.** Every `StorageProvider` honours one documented contract: `put` is idempotent for the same checksum, returns an immutable `StorageRef`; `open` of a missing object throws `StorageObjectNotFound`, of an unreachable provider throws `StorageUnavailable` (never provider-specific exceptions); checksums/sizes verified on write. A shared abstract **contract test suite** runs against the local provider, the fake Drive client and (later) S3, so any implementation can replace another. Same for `ProgressionRule` (pure functions of the same input record, no side effects) and `ArtifactKindPolicy`.
- **I — interface segregation.** Consumers depend on the narrowest port:
  - `StorageProvider` is composed of `ObjectWriter`, `ObjectReader`, `HealthProbe`; template and artifact download depend only on `ObjectReader`, submission upload on `ObjectWriter`, the health job on `HealthProbe`.
  - `StageProgressPort` exposes just "is stage X submitted/approved for project Y" to `workflow`, implemented by `submission`; `workflow` never sees the submission model.
  - `AccessPolicy` stays the single authorization entry point as a facade; module-specific checks (`WorkflowAccess`, `StorageAccess`, `SubmissionAccess`) are separate collaborators it delegates to, so a module's rules change in one file and callers see only the methods they need.
- **D — dependency inversion.** High-level rules depend on abstractions, wired in `config`:
  - Services take ports (`StorageProvider`/`ObjectReader`, `VirusScanner`, `IntakeJobPublisher`, `Clock`, `EmailSender`) by constructor injection; `ClamAvScanner`, `LocalFilesystemStorageProvider`, `GoogleDriveStorageProvider` are interchangeable per profile. Tests use fakes, never the real Google API.
  - The API depends on the **message contract**, not on the worker: it writes `outbox_event` rows and reads result messages, so the Python worker (and later Java workers) can change independently.
  - Time via injected `Clock` (deadlines, scheduled stages, token expiry) so rules are testable without sleeping.
  - Domain rules (progression, validation outcomes, destination precedence) are plain classes with no Spring, JDBC or HTTP imports; they are unit-tested directly.
- **Enforcement** (add to `MvcArchitectureTest` in the slice that introduces each seam):
  - `workflow` and `template` never depend on `submission` (explicit rule; a general "free of cycles" rule is not usable yet because `academic` <-> `project` already cycle, so list the new modules' allowed direction: `submission -> workflow, template, storage`; `workflow -> template`; nothing depends on `submission` except `config`).
  - Classes outside `storage.service.adapter` must not depend on `..storage.service.adapter..` (consumers use `..storage.service.port..`); same for the scanner and publisher adapters.
  - Classes in `..model.rule..` import no `org.springframework..`, `java.sql..`, `javax.sql..` or `..jdbc..`.
  - Only `config` may instantiate adapters directly; selection per profile via configuration.
  - Provider contract test base class (`StorageProviderContract`) lands in slice 4.
- Update the die-code-conventions skill with the `model/rule`, `service/port`, `service/adapter` sub-package convention once slice 4 proves it.

## Cross-cutting rules
- Authorization only through `AccessPolicy` (extend with workflow/template ownership and `requireStorageConnectionOwner`); out-of-scope resources return 404.
- Everything published is immutable; mutations of a published workflow/template version are refused (409).
- No secrets, tokens or file contents in logs or error bodies; filenames are data, never paths.
- Downloads: authorization -> stream through API, `nosniff`, attachment disposition, no Drive URLs to participants.
- Upload endpoints throttled per user; resource limits configurable (`die.intake.*`).
- Coordinator-only data (validation overrides, other teams' artifacts) never reaches participant views.

## Risks / open decisions
1. ~~Execution model~~ decided: outbox + RabbitMQ + Python worker in slice 6a. Record as an ADR (`docs/decisions/`) together with the storage abstraction and immutability guards. Slice 6a also needs Compose services for RabbitMQ (already present), the worker image and ClamAV.
2. Official-submit policy for teams (any member vs representative) — default proposed: any active member may upload drafts, representative or staff may submit officially.
3. Late submissions: per-stage `accept_late` flag + late marker on the version (proposed).
4. Drive OAuth requires a Google Cloud project, consent screen and test users — needs the user's credentials; everything else is developable against the local provider.
5. ClamAV adds a container (Compose + Render private service); if infeasible on Render's tier, document the fallback (reject archives, type sniff only) and keep the scan interface.
6. Template section matching must tolerate numbering/case/aliases; keep the matcher deterministic and test with real-world DOCX fixtures (including malformed, macro-enabled, empty, and huge files).

## Definition of done
Brief §10 criteria 5–10 pass end to end; authorization matrix green with all new endpoints; migrations V7+ apply on a clean DB; official versions provably immutable (DB-level guard + tests); audit rows for the events above; Drive provider verified manually against a real account (documented); CLAUDE.md status and ADRs updated.
