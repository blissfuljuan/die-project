---
name: die-domain-model
description: DIE domain concepts, entity relationships, versioning and snapshot rules (brief v1.0). Use when designing schema, entities, or APIs for workspaces, projects, requirements, workflows, templates, artifacts, rubrics, evaluations, reviews, publications.
---

# DIE domain model (brief v1.0; ADR 0002 vocabulary)
Public names: Project Workspace, Participant, Coordinator. Internal tables/classes still say `academic_class`, `enrollment`, `student_id` — do not rename them casually.

## Core chain
Workspace (Owner + Coordinators) -> Membership -> Project (individual | team) -> pinned RequirementsSetVersion + WorkflowVersion -> Stage -> ArtifactDefinition (+ TemplateVersion) -> ArtifactDraft -> ArtifactVersion (submitted, immutable) -> Evaluation -> Findings -> Reviews -> Approval -> Publication (revisions).

## Versioning (published = immutable)
Requirements Sets (stable requirement identities + versions), workflow (DAG), artifact definition, DOCX template, rubric, review/approval policy, model config, submission. Draft, Validate, Publish and Assign are four separate operations. A Project keeps its assigned requirements/workflow versions until an authorized **migration** (impact preview, compatibility validation, stale-confirmation rejection, atomic per Project; history and running evaluations preserved). Configuration templates are reusable by copy-on-apply (independent copies).

## Submission
Backend-persisted draft -> structural validation (errors block, warnings allowed per policy) -> submit = immutable version with storage ref, SHA-256, provenance (uploader, submitter, source upload|drive-link, resolved destination). Corrections create new versions. Revision requests are Blocking or Advisory; unresolved Blocking requests prevent evaluation. Deadlines and authorized extensions. Intake is DOCX (upload + authorized Drive link).

## Storage
Destination precedence: **Project override > Workspace override > Administrator default** (no personal/instructor tier). Configured-but-unavailable destination blocks (no silent fallback). Provenance stays with the original destination.

## Evaluation (P3)
Only an authorized Coordinator initiates. Eligibility: valid artifact + SDLC prerequisites + no unresolved Blocking revision + qualified model route. Each attempt pins artifact version, rubric snapshot, model/config, prompt version, evidence context. Fallback only between distinct attempts. Findings append-only with evidence refs; original AI output never overwritten.
Review: versioned review policy; several reviewers decide **independently** (attributable); disagreements need explicit adjudication; reviewer/lead/final approver are workflow responsibilities of Coordinators. Approval: configurable one-/multi-approver quorum -> immutable final-assessment snapshot. Publication is separate, explicit, bound to one artifact version; corrections are new immutable revisions.

## Rubric resolution
Numerical (weighted criteria) and qualitative (levels), hierarchical (evaluation > artifact > stage > workflow); resolve at initiation and store the snapshot on the attempt.

## Post-MVP
AI requirements extraction/lineage, traceability links, code analysis (GitHub/ZIP), conformance, analytics.
