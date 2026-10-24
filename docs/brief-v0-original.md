# DOCUMENT–IMPLEMENTATION EVALUATOR (DIE)
## Comprehensive Project Brief and Required Features

- Specification Version: 1.0
- Application Type: AI-Assisted Academic Software Project Evaluation Platform
- Development Approach: Modular, Web-Based, API-Driven, Containerized
- Primary Users: Students, Instructors, Administrators

## 1. PROJECT OVERVIEW

### 1.1 Project Description
The Document–Implementation Evaluator (DIE) is a custom web-based application designed to support the management, monitoring, assessment, and verification of student software development projects throughout their Software Development Life Cycle (SDLC).

The system provides a centralized academic environment where students can submit structured software project documentation, implementation artifacts, source code references, testing evidence, and other required deliverables.

Instructors can configure development workflows, establish evaluation criteria, review submissions, initiate AI-assisted evaluations, and determine whether the submitted documents and software implementation satisfy the expected requirements.

The platform combines traditional academic project management with artificial intelligence to support:

* Software documentation quality assessment.
* Document structural compliance.
* Internal and cross-document consistency checking.
* Software requirements extraction and traceability.
* Document-to-implementation alignment verification.
* Source-code inspection and static analysis.
* Testing evidence assessment.
* Incremental project development monitoring.
* Rubric-based academic evaluation.
* Instructor-controlled recommendations and final approvals.

The AI evaluator serves as an intelligent assessment assistant. It provides observations, evidence, preliminary scoring, identified inconsistencies, and improvement recommendations.

AI-generated results are advisory. The instructor retains final authority over every academic evaluation.

Instructors must be able to accept, partially accept, modify, reject, or supplement AI findings and recommendations before publishing the official evaluation.

### 1.2 Project Objectives
The primary objectives are:

1. Establish a centralized platform for managing student software development projects and their documentation.
2. Support configurable SDLC workflows, including phase-based, iterative, incremental, and Agile approaches.
3. Standardize document submissions through instructor-provided templates.
4. Automate deterministic document validation and provide AI-assisted document quality assessment.
5. Evaluate consistency between requirements, system designs, implementation evidence, and testing documents.
6. Support traceability of requirements throughout project development.
7. Assist instructors in identifying incomplete, inconsistent, unsupported, or potentially noncompliant deliverables.
8. Provide configurable rubric-based evaluation with numerical or qualitative assessments.
9. Preserve instructor authority over findings, grading, feedback, and project-stage approvals.
10. Maintain historical records of document revisions, requirement changes, evaluations, and instructor decisions.
11. Support collaborative academic evaluation involving multiple authorized instructors.
12. Provide secure, auditable, extensible, and reliable system operations.

### 1.3 Intended Users

**Administrator**
Responsible for system-wide account management, global academic configurations, storage integrations, AI service configuration, operational monitoring, and security.

**Instructor**
Responsible for academic classes, student enrollment, project management, SDLC configuration, document templates, rubrics, evaluation requests, reviews, approvals, and feedback publication.

**Student**
Responsible for project participation, preparation and submission of documents, implementation evidence, document revisions, and reviewing instructor-approved feedback.

**Additional Resource-Specific Roles**
Authorized users may receive scoped responsibilities as:

* Project Team Representative.
* Evaluation Reviewer.
* Lead Reviewer.
* Final Evaluation Approver.
* Class or Project Evaluation Coordinator.

These responsibilities supplement, rather than replace, the primary application roles.

## 2. SYSTEM SCOPE

### 2.1 General Scope
DIE will support the academic software development process from student enrollment and project formation to documentation, evaluation, revision, implementation verification, and final approval.

The system is designed primarily for academic software development projects, including:

* Software Engineering courses.
* Systems Development courses.
* Capstone projects.
* Undergraduate software development projects.
* Project-based programming courses.
* Other courses requiring structured software documentation and implementation evaluation.

### 2.2 Supported Development Methodologies
The application must not be restricted to a single SDLC model.

It shall support:

* Waterfall and other phase-based workflows.
* Iterative development.
* Incremental development.
* Agile or sprint-based development.
* Instructor-defined custom workflows.

Each workflow consists of configurable stages, deliverables, dependencies, submission policies, evaluation rules, and progression conditions.

### 2.3 Implementation Scope
Development shall be divided into two primary delivery categories.

**Minimum Viable Product (MVP)**
The MVP shall deliver a complete, usable workflow covering:

1. Local authentication and account activation.
2. Academic class and student roster management.
3. Individual and group project management.
4. Configurable SDLC workflows.
5. Instructor-provided document templates.
6. Student document intake and submission versioning.
7. Google Drive-backed storage.
8. Deterministic document validation.
9. Instructor-defined rubrics.
10. On-demand AI document quality and internal consistency evaluation.
11. Evidence-linked AI findings.
12. Instructor review, modification, rejection, and approval.
13. Controlled publication of evaluation results.
14. Containerized evaluation infrastructure.
15. Essential operational monitoring, backups, and security controls.

**Subsequent Development Phases**
The complete platform shall additionally support:

* AI-assisted requirement extraction.
* Instructor-approved requirements baselines.
* Cross-document consistency evaluation.
* Full-project document–implementation evaluation.
* GitHub repository analysis.
* Source-code ZIP archive analysis.
* Static code inspection.
* Existing test-result analysis.
* Requirement-to-code traceability.
* Requirement change-impact analysis.
* Targeted AI reevaluation.
* Advanced collaborative review workflows.
* Comprehensive analytics.
* Extended report exports.
* Advanced archival and retention automation.
* Optional self-hosted model deployment.
* Future institutional sign-in.

## 3. REQUIRED FUNCTIONAL FEATURES

### 3.1 Authentication and User Account Management

**Administrator Features**

* Create, view, update, activate, deactivate, and manage user accounts.
* Assign Student, Instructor, and Administrator roles.
* Assign resource-specific permissions.
* Reset credentials through authorized procedures.
* Monitor account activation status.
* Manage authentication policies.
* Review authentication and security events.

**Student Account Creation**
Student accounts shall primarily be created through instructor or administrator roster imports.

The minimum roster information shall include:

* Student ID Number.
* Last Name.
* First Name.

Optional information may include middle name, email address, class, program, and section.

**Student Account Activation**
The system shall implement the following process:

1. Student account is created or matched through roster import.
2. A unique temporary password is generated for a new account.
3. The temporary credential is securely distributed.
4. The student initially signs in using the Student ID and temporary password.
5. The application requires the student to provide an email address and create a new password.
6. Email ownership is verified.
7. The temporary password becomes invalid.
8. Subsequent logins use the verified email address and new password.

Temporary credentials must be individually generated, securely stored as hashes, rate-limited, and subject to expiration or administrator reset policies.

**Authentication Architecture**
The application shall use:

* Spring Security.
* Local application credentials.
* Server-managed authenticated sessions.
* Secure, HttpOnly cookies.
* CSRF protection.
* Password hashing.
* Session expiration and invalidation.
* Account recovery and password reset.

Institutional OAuth2/OIDC authentication is deferred from the initial release. The architecture should permit adding it in the future.

### 3.2 Academic Class Management
Instructors and administrators shall be able to:

* Create academic classes.
* Configure academic year, semester, subject, and section.
* Assign instructors to classes.
* View and manage class rosters.
* Import student rosters using CSV or XLSX files.
* Preview roster data before committing imports.
* Automatically recognize previously registered students.
* Skip duplicate accounts and duplicate enrollments.
* Enroll existing students into additional classes.
* Identify and resolve student identity conflicts.
* Add or remove students according to authorization rules.
* Archive completed classes.
* View class-level project progress.

A student may belong to multiple classes using one application account.

### 3.3 Project and Team Management
The application shall support both individual and group projects.

**Project Features**

* Create and configure projects within classes.
* Associate projects with SDLC workflows.
* Assign project titles and descriptions.
* Assign students or project teams.
* Configure project-related deadlines.
* Display project deliverables and development progress.
* Maintain project membership history.

**Team Formation**
Two team formation methods shall be supported:

*Student-formed teams*

* Students create teams.
* Students invite eligible classmates.
* Team sizes follow instructor-defined minimum and maximum limits.
* Team membership changes follow instructor policy.

*Instructor-assigned teams*

* Instructors create teams.
* Instructors assign or transfer students.
* Instructors appoint or replace team representatives.
* Instructors manage membership exceptions.

**Group Submission Rules**
Any authorized member of a project team may upload or officially submit documents.

The system shall record:

* Uploading member.
* Submitting member.
* Submission timestamp.
* Submitted file versions.
* Revisions and replacements.

Concurrent changes shall be handled safely to prevent accidental overwriting.

### 3.4 Configurable SDLC Workflow Management
The application shall provide a configurable workflow management module.

**Workflow Configuration**
An instructor can:

* Create a workflow.
* Copy an existing workflow.
* Use administrator-provided templates.
* Create private workflows.
* Share workflows with other instructors.
* Modify copied workflow templates independently.
* Define stages, phases, and sprints.
* Assign required documents and deliverables.
* Configure deadlines and submission conditions.
* Define stage dependencies.
* Configure stage approval rules.
* Configure revision limits.
* Assign evaluation rubrics.
* Publish workflow versions.

**Workflow Ownership**
The system shall support:

* Administrator-owned templates.
* Instructor-owned private workflows.
* Instructor-shared workflows.

Initially, workflow sharing should use independent copies. Centrally managed linked templates may be introduced later.

**Workflow Builder**
The application shall provide:

* Form-based workflow configuration.
* Visual workflow diagrams.
* Stage dependency visualization.
* Workflow validation.
* Workflow publishing and versioning.

**Stage Progression**
Instructors must be able to configure whether a stage becomes available:

* After submission of prerequisite deliverables.
* After instructor approval.
* At a scheduled date.
* Through manual instructor action.
* When multiple dependencies have been satisfied.
* In parallel with other stages.

A stage shall not become officially completed solely because an AI evaluation returned a passing score.

Final completion shall require instructor-defined conditions and mandatory approval.

**Agile Support**
Agile or iterative projects shall support:

* Sprint creation.
* Sprint goals.
* Deliverables per sprint.
* Requirements added or changed per iteration.
* Sprint-specific evaluations.
* Revision history.
* Requirement carry-over.
* Cross-sprint progress tracking.

### 3.5 Document Template Management
Instructors shall provide structured document templates for required deliverables.

Students must use the instructor-assigned document template.

**Template Features**

* Upload DOCX templates.
* Define mandatory sections.
* Define optional sections.
* Configure heading structure.
* Configure required tables and fields.
* Define requirement numbering conventions.
* Specify document instructions.
* Define structural validation rules.
* Assign templates to deliverables.
* Version and publish templates.
* Download assigned templates from the student workspace.

Example templates include:

* Software Requirements Specification.
* System Design Document.
* Software Architecture Document.
* Database Design Document.
* Test Plan.
* Test Case Specification.
* Test Execution Report.
* Deployment Plan.
* User Manual.
* Project Progress Report.

**Template Updates**
When a template is revised:

* Existing submissions retain their original template version.
* Previous evaluations remain associated with their original template.
* The instructor may explicitly require migration.
* New submissions follow the applicable assigned template.

### 3.6 Student Document Intake
Students shall have a dedicated submission page for every assigned deliverable.

**Supported Submission Methods**

1. Direct file upload.
2. Publicly accessible Google Drive file link.

For a Google Drive link, the system shall validate access and retrieve an independent copy into the configured application storage destination.

**Supported Document Formats**
Initial document-processing support shall prioritize:

* DOCX.
* PDF.

Additional artifact types may include:

* Screenshots and images.
* Source ZIP archives.
* Test evidence files.
* Repository references.
* Deployment URLs.

The system must distinguish documents it can semantically evaluate from files it merely stores as supporting evidence.

**Submission Features**

* Download assigned template.
* View instructions and deadlines.
* Upload individual documents.
* Submit stage packages.
* Save drafts.
* Replace drafts.
* Run automatic structural validation.
* Submit officially.
* View current version.
* View submission history.
* Submit revised versions.
* View revision limits and deadlines.
* View approved instructor feedback.

**Structural Validation Modes**

* Strict mode: Structurally invalid documents cannot become accepted official submissions.
* Warning-only mode: Documents may be submitted despite structural violations, which remain visible to the student and instructor.

Instructors shall choose the enforcement mode per deliverable.

### 3.7 Document Storage Management
The system shall implement a storage-provider abstraction.

**Initial Storage Provider**
Google Drive.

**Supported Google Drive Accounts**

* Personal Google Drive accounts.
* Institutional Google Workspace accounts.
* Google Workspace Shared Drives.

**Storage Configuration**
Administrators can establish a system default storage connection.

Instructors may nominate authorized Google Drive accounts and destinations.

The application shall resolve storage destination using this precedence:

1. Project-specific destination.
2. Class-specific destination.
3. Instructor default destination.
4. Administrator default destination.

**Storage Requirements**

* Backend-controlled Google API integration.
* Secure OAuth credential handling.
* Connection and permission validation.
* File upload and retrieval.
* Logical folder organization.
* File identifier tracking.
* Artifact metadata stored in PostgreSQL.
* Checksums and immutable submission references.
* Connection health monitoring.
* Storage migration capability.
* Revoked-access and unavailable-file handling.

Students shall not directly receive unrestricted access to other projects' stored files.

The storage abstraction must permit future S3-compatible providers without major changes to submission and evaluation logic.

### 3.8 Rubric Management
The application shall support standardized and instructor-customizable rubrics.

**Rubric Configuration Levels**

* Workflow-level.
* Stage/sprint-level.
* Deliverable-level.
* Evaluation-specific.

More specific configurations take precedence over defaults according to explicit override rules.

**Scoring Modes**

*Numerical assessment*

* Weighted criteria.
* Numeric scores.
* Maximum scores.
* Optional passing thresholds.
* Overall scoring rules.

*Qualitative assessment*

* Instructor-defined performance levels.
* Written observations.
* Assessment descriptions.
* Recommendations.

**Rubric Features**

* Create rubrics.
* Copy rubrics.
* Reuse rubric templates.
* Configure criteria and descriptions.
* Configure scoring rules.
* Define evaluation standards.
* Create versions.
* Assign rubrics to deliverables.
* Override rubrics for individual evaluations.
* Preserve resolved rubric snapshots.

AI-suggested scores must be distinguishable from final instructor-approved scores.

### 3.9 AI Document Quality Evaluation
AI evaluation shall be initiated only by an authorized instructor.

Student submission shall not automatically trigger an LLM evaluation.

Automatic deterministic validation may occur during intake.

**MVP Evaluation Capabilities**
The AI evaluator shall assess:

* Document completeness.
* Clarity.
* Coherence.
* Readability.
* Internal consistency.
* Ambiguity.
* Terminology consistency.
* Requirement statement quality.
* Alignment with assigned rubric criteria.
* Potentially missing or underdeveloped content.
* Compliance with instructor-defined expectations.

**Evaluation Levels**

* Overall document.
* Individual section.
* Individual requirement.
* Individual rubric criterion.

**AI Evaluation Output**
Each finding should include:

* Finding identifier.
* Evaluation scope.
* Finding category.
* Related rubric criterion.
* Document or section reference.
* Explanation.
* Supporting evidence.
* Evidence verification status.
* Suggested assessment or score.
* Recommended corrective action.
* Limitations or uncertainty.

AI-generated assessments shall remain internal until reviewed and approved by authorized instructors.

### 3.10 Cross-Document Consistency Evaluation
In subsequent development phases, the instructor shall be able to compare:

* SRS against System Design.
* SRS against Test Plans.
* Design against Implementation.
* Requirements against Test Cases.
* Documentation across SDLC stages.
* Current sprint artifacts against earlier approved iterations.

The evaluator shall identify:

* Contradictory requirements.
* Missing design coverage.
* Inconsistent terminology.
* Untraceable changes.
* Requirements omitted from related documents.
* Unexplained differences between versions.

### 3.11 Requirements Extraction and Traceability
The system shall support AI-assisted extraction of requirements from submitted documentation.

**Requirement Extraction**
AI proposes:

* Requirement statements.
* Functional or nonfunctional classification.
* Suggested stable identifiers.
* Source document references.
* Ambiguities.
* Potential duplicates.

**Instructor Validation**
The instructor can:

* Accept.
* Modify.
* Merge.
* Split.
* Reject.
* Add requirements manually.

Only instructor-approved requirements become part of the official requirements baseline.

**Requirements Traceability**
Requirements shall be traceable to:

* Original SRS statements.
* Design documents.
* System components.
* Source-code evidence.
* Test cases.
* Testing results.
* Evaluation findings.
* SDLC stages or sprints.

**Change Lineage**
The system must preserve:

* Requirement versions.
* Previous requirement relationships.
* Added, modified, deleted, merged, and split requirements.
* Approval history.
* Affected artifacts.
* Superseded evaluations and findings.

### 3.12 Source-Code and Implementation Evaluation
This is a post-MVP capability.

**Source Intake**
Initially supported:

* GitHub repository references.
* Uploaded source-code ZIP archives.

**Evaluation Process**

1. Resolve the specified Git commit or source archive.
2. Create a reproducible source snapshot.
3. Identify project structure and languages.
4. Run appropriate static-analysis tools.
5. Locate potentially relevant implementation components.
6. Retrieve existing test evidence.
7. Compare code evidence against approved requirements.
8. Generate evidence-linked findings.
9. Identify unsupported requirements.
10. Recommend further inspection or verification.

The evaluator shall not claim that source code works merely because matching functions or classes exist.

**Scope Limitation**
The initial source-code evaluator shall not execute untrusted student applications or arbitrary project build scripts.

Static analysis and inspection of existing test evidence are the initial supported verification methods.

### 3.13 AI Evidence Verification
AI findings shall cite actual source artifacts.

Supported evidence locations include:

* DOCX sections and paragraphs.
* PDF pages and text locations.
* Source-code files and line ranges.
* Requirements baseline identifiers.
* Test suites and test cases.
* Screenshots and supporting artifacts.

The application shall validate references before presenting them as verified evidence.

A fabricated or invalid citation must be flagged or excluded.

**Evidence Assessment States**

* Verified under the selected verification method.
* Partially supported.
* Not verified.
* Contradicted.
* Not applicable.

Instructors can configure evidence thresholds.

Evidence strength must not be confused with guaranteed correctness or runtime functionality.

### 3.14 AI Re-Evaluation and Change-Impact Analysis
The platform shall support instructor-triggered:

* Full reevaluation.
* Document-specific reevaluation.
* Section-specific reevaluation.
* Requirement-specific reevaluation.
* Criterion-specific reevaluation.
* Changed-artifact reevaluation.

When a revised artifact is submitted, the system should identify potentially affected requirements and previous findings.

It shall recommend relevant reevaluation targets without automatically invoking the LLM.

Previous findings shall remain accessible.

A new finding may explicitly supersede an older finding, with the relationship recorded.

### 3.15 Instructor Review Workspace
The instructor evaluation interface shall provide two complementary views.

**Report View**
Displays:

* Evaluation summary.
* Applied rubric.
* Suggested scores.
* Findings.
* Recommendations.
* Evidence references.
* Evaluation limitations.
* Review and approval status.

**Document-Side Review**
Displays:

* Original document content or preview.
* Relevant document section.
* AI finding.
* Supporting evidence.
* Rubric criterion.
* Instructor review controls.
* Comments and annotations.

**Instructor Review Actions**
For each finding, instructors must be able to:

* Accept the finding.
* Reject the finding.
* Modify the finding.
* Modify the recommendation.
* Modify the proposed score.
* Add independent observations.
* Request additional evidence.
* Request a revision.
* Mark the finding for further review.

The original AI output shall never be overwritten.

### 3.16 Collaborative Instructor Review
Multiple authorized instructors may participate in the evaluation process.

**Review Roles**

* Reviewer: Performs assessment, comments, and proposes changes.
* Lead Reviewer: Coordinates reviews and reconciles disagreements.
* Final Approver: Authorizes the final evaluation.

**Approval Policies**
The application shall support:

* One-approver policy.
* Multi-approver policy.

Under a multi-approver policy, the required number or designated set of approvals must be completed before finalization.

Approval policy settings shall be captured in the evaluation's applicable review configuration.

**Review Management**

* Manual reviewer assignment.
* Optional workload-based assignment.
* Review deadlines.
* Review progress.
* Reviewer comments.
* Conflict detection.
* Approval or return for changes.
* Decision and approval history.

### 3.17 Evaluation Publication
Official publication is separate from evaluation approval.

Students shall only receive instructor-approved information.

Instructors may publish:

* Final overall assessment.
* Selected rubric results.
* Approved observations.
* Approved recommendations.
* Revision instructions.
* Final evaluation reports.

Draft AI findings, internal reviewer comments, and rejected recommendations remain private.

Publication must be enforced in backend authorization and data access, not merely hidden in the frontend.

### 3.18 Evaluation Reports
The application shall support report generation and export in:

* PDF.
* DOCX.
* JSON.

Reports shall include project information, evaluation scope, input versions, rubric, findings, supporting references, instructor decisions, and final approval data.

Official reports shall derive from approved evaluation records rather than an AI-generated rewrite of the final decision.

Each export shall retain its evaluation source reference, creation timestamp, version, and checksum.

Basic approved-feedback display is part of the MVP. Full report-export functionality may be delivered in a subsequent phase.

### 3.19 Notifications
The system shall support configurable in-app and email notifications.

Notification events may include:

* Team invitations.
* Upcoming deadlines.
* Submission confirmation.
* Structural validation failures.
* Revision requests.
* Reviewer assignments.
* Completed AI evaluations.
* Pending approvals.
* Published feedback.
* Stage completion.
* Storage connection problems.
* Evaluation failures.

Administrators configure available channels and mandatory events.

Instructors and users may configure eligible notification preferences.

### 3.20 Dashboards and Progress Monitoring

**Student Dashboard**

* Enrolled classes.
* Current projects.
* Assigned stages and sprints.
* Pending deliverables.
* Deadlines.
* Submission statuses.
* Revision requests.
* Approved evaluations.
* Project progress history.

**Instructor Dashboard**

* Assigned classes.
* Student projects.
* Pending submissions.
* AI evaluation queue.
* Assigned reviews.
* Pending approvals.
* Project and stage progress.
* Requirement coverage.
* Evaluation history.

**Administrator Dashboard**

* Registered users.
* Active classes.
* Storage connection status.
* Queue and worker health.
* AI usage.
* Estimated model costs.
* Failed jobs.
* System errors.
* Security audit events.
* Operational performance metrics.

## 4. TECHNICAL ARCHITECTURE REQUIREMENTS

### 4.1 Frontend
Selected stack: React, TypeScript, and Vite.

The frontend shall provide role-based navigation, responsive interfaces, protected routes, forms, dashboards, document previews, evaluation controls, and live evaluation progress.

The application shall use the Spring Boot REST API as its only application-data interface.

### 4.2 Backend
Selected stack: Java Spring Boot.

The backend is responsible for:

* Authentication.
* Session management.
* Authorization.
* Academic workflows.
* Submission management.
* Storage orchestration.
* Rubric management.
* Evaluation job creation.
* Review and approval workflows.
* Publication.
* Audit logging.
* Reporting.
* Integration with workers.

The architecture shall follow a modular design with clear domain boundaries.

### 4.3 Database
Selected database: Supabase PostgreSQL.

Supabase shall only provide the PostgreSQL database service.

The application shall not depend on Supabase Auth or Supabase Storage.

The Spring Boot backend shall enforce application authorization.

Database connections shall use a dedicated least-privileged application identity.

**Database Migration**
All schema changes shall be managed using Flyway versioned SQL migrations.

**Required Database Domains**

* Identity and Credentials.
* Academic Management.
* Projects and Teams.
* SDLC Workflow Definitions.
* Document Templates.
* Submissions and Artifact Versions.
* Storage Connections.
* Rubrics.
* Requirements and Traceability.
* Evaluation Requests and Runs.
* Evidence References.
* Instructor Reviews.
* Final Approvals.
* Publications.
* Notifications.
* AI Configuration.
* Audit and Retention.

The database shall enforce primary keys, foreign keys, unique constraints, version relationships, and other applicable integrity rules.

### 4.4 AI Integration
The architecture shall support replaceable AI providers.

Administrators configure preferred models by task.

Authorized instructors may override the selected model within administrator-defined permissions.

The AI integration shall support:

* Cloud LLM APIs.
* Optional self-hosted inference endpoints.
* Embedding models where needed.
* Task-specific model routing.
* Structured JSON output.
* Prompt versioning.
* Token and cost logging.
* Failure handling.
* Output validation.
* Evidence verification.

### 4.5 Containerized Evaluation Infrastructure
All processing workers shall run in separate Docker containers.

The main Spring Boot application must not perform expensive AI or source-code analysis directly within request-processing threads.

**Message Broker**
Selected technology: RabbitMQ.

RabbitMQ shall operate in its own Docker container.

Required capabilities:

* Durable queues.
* Persistent messages.
* Publisher confirmations.
* Manual acknowledgements.
* Retry and dead-letter queues.
* Queue monitoring.
* Controlled recovery after failure.

**Java Orchestration Worker**
A standalone Java worker shall:

* Receive evaluation jobs.
* Load immutable evaluation configuration.
* Coordinate processing stages.
* Dispatch specialized tasks.
* Track evaluation state.
* Collect worker outputs.
* Validate intermediate results.
* Manage recoverable failures.
* Assemble completed evaluation records.

**Python Analysis Workers**
Python workers shall be deployed independently.

The initial document-analysis worker shall handle:

* DOCX parsing.
* PDF text and structure extraction.
* Section identification.
* Document normalization.
* Deterministic validation support.
* Evidence location mapping.

Future Python workers may handle source-code analysis, semantic retrieval, embeddings, and other specialized tasks.

Each worker shall have defined resource limits, health monitoring, and structured logs.

**Job Persistence**
PostgreSQL shall retain authoritative evaluation-job state and audit history.

A transactional outbox shall protect the handoff between database changes and RabbitMQ publication.

Jobs shall be idempotent wherever possible and recoverable following worker interruptions.

### 4.6 REST API
All application operations shall be exposed through versioned REST APIs.

Suggested base path: `/api/v1`

The API shall include endpoint groups for:

* Authentication and account activation.
* Users and student roster import.
* Classes and enrollments.
* Projects and team membership.
* Workflows and stage management.
* Document templates.
* Artifact upload and Google Drive imports.
* Submissions and revisions.
* Rubrics.
* Evaluation requests.
* Evaluation findings.
* Instructor review decisions.
* Approvals.
* Publications.
* Reports.
* Storage connections.
* AI configuration.
* Administrative monitoring.

Long-running evaluation progress shall be available through Server-Sent Events (SSE), with status polling as a fallback.

The API shall publish OpenAPI documentation and provide consistent error responses.

### 4.7 Render Deployment
Production infrastructure shall be defined through a Render Blueprint.

Expected services:

* React frontend as Render Static Site.
* Spring Boot API as Docker-based Web Service.
* RabbitMQ as a private Docker service with persistent storage.
* Java orchestration worker as an independent Docker-based background worker.
* Python document-analysis worker as an independent Docker-based background worker.
* Additional analysis workers as required by subsequent development phases.

Supabase PostgreSQL and Google Drive remain external services.

The platform shall support Docker Compose for local development.

Worker processes shall use independent images, runtime settings, secrets, and resource limits.

The initial RabbitMQ deployment may use one persistent broker instance. Production availability limitations and recovery procedures must be documented.

## 5. SECURITY REQUIREMENTS
The application shall implement defense-in-depth security controls.

### 5.1 Authentication Security

* Secure password hashing.
* Individual temporary activation credentials.
* Email verification.
* Session expiration.
* Secure cookies.
* CSRF protection.
* Login attempt limits.
* Password reset.
* Session revocation.

### 5.2 Authorization
Authorization is handled by Spring Boot.

Access shall be validated using:

* Primary user roles.
* Class membership.
* Project membership.
* Reviewer assignments.
* Approver assignments.
* Workflow permissions.
* Storage connection ownership.

### 5.3 Data Isolation
Students shall not access another project's private documents or unpublished evaluation findings.

Instructors shall only access authorized academic resources.

Administrators shall receive management capabilities according to their assigned permissions.

### 5.4 File Security

* Validate file types and sizes.
* Scan uploaded artifacts for malware.
* Prevent unsafe archive extraction.
* Enforce file access permissions.
* Protect Google OAuth credentials.
* Restrict external file URL processing.
* Preserve artifact integrity through checksums.
* Secure temporary processing locations.

### 5.5 AI Processing Security

* Treat document and repository content as untrusted data.
* Prevent uploaded content from overriding evaluator instructions.
* Avoid exposing secrets to AI providers.
* Restrict worker filesystem and network permissions.
* Validate structured model responses.
* Record provider and model usage.
* Avoid executing untrusted student applications during initial analysis.

## 6. NONFUNCTIONAL REQUIREMENTS

### 6.1 Reliability
The application must preserve official submissions and academic decisions during recoverable service failures.

Queue and worker restarts must not silently discard accepted evaluation jobs.

### 6.2 Performance
Ordinary interactive operations shall remain responsive while evaluations run asynchronously.

Performance targets shall be finalized using workload and concurrency testing.

### 6.3 Scalability
The system shall allow independent scaling of API services and evaluation workers, subject to deployment constraints.

### 6.4 Maintainability
Use modular architecture, clear interfaces, versioned APIs, structured logging, automated tests, and managed database migrations.

### 6.5 Extensibility
The system shall allow new:

* SDLC templates.
* Document types.
* Rubric types.
* AI models.
* Analysis workers.
* Storage providers.
* Static-analysis integrations.
* Evaluation rules.

### 6.6 Usability
Provide clear student, instructor, and administrator interfaces.

Document validation errors, evaluation findings, deadlines, and approval states must be understandable without requiring detailed knowledge of the AI implementation.

### 6.7 Auditability
The application shall retain historical records of:

* Submission versions.
* Template versions.
* Workflow versions.
* Rubric versions.
* Evaluation runs.
* Model configurations.
* Requirement changes.
* Finding supersession.
* Instructor review decisions.
* Final approvals.
* Publication events.
* Security-sensitive administrative changes.

### 6.8 Privacy
Student data and project artifacts shall be accessed only for authorized educational and evaluation purposes.

External AI transmission and Google Drive access shall follow the applicable institutional privacy and data handling policies.

### 6.9 Backup and Recovery
The system shall provide:

* Database backups.
* Configuration backups.
* Artifact manifests.
* Secure storage of backup material.
* Defined recovery procedures.
* Periodic restoration tests.

The backup strategy shall account for external Google Drive artifacts and their recoverability. An artifact manifest alone does not constitute a backup of the underlying document files.

### 6.10 Retention
Retention policies shall be configurable by academic year, course lifecycle, and institutional requirements.

Retention shall distinguish active, completed, archived, and disposition-eligible records.

## 7. REQUIRED APPLICATION PAGES

### 7.1 Student Pages

1. Login and Account Activation.
2. Student Dashboard.
3. My Classes.
4. Class Workspace.
5. My Projects.
6. Project Team Management.
7. SDLC Progress.
8. Deliverables.
9. Document Submission and Revision.
10. Submission History.
11. Published Evaluation Results.
12. Notifications.
13. Profile and Account Settings.

### 7.2 Instructor Pages

1. Instructor Dashboard.
2. Global Evaluation Queue.
3. My Classes.
4. Class Workspace.
5. Student Roster Management.
6. Projects and Teams.
7. SDLC Workflow Library.
8. SDLC Workflow Builder.
9. Document Template Library.
10. Rubric Management.
11. Submission Review.
12. AI Evaluation Workspace.
13. Document-Side Review.
14. Reviewer Assignments.
15. Final Evaluation Approvals.
16. Feedback Publication.
17. Project Analytics.
18. Instructor Google Drive Settings.
19. Notifications.

### 7.3 Administrator Pages

1. Operations Dashboard.
2. User Management.
3. Roster Import Management.
4. Academic Management.
5. Global Workflow Templates.
6. Storage Connection Management.
7. AI Provider and Model Configuration.
8. Evaluation Job Monitoring.
9. Worker and Queue Monitoring.
10. System Analytics and Quotas.
11. Audit and Security Logs.
12. Backup and Recovery Administration.
13. Retention Policies.
14. System Settings.

## 8. IMPLEMENTATION PHASES

**Phase P0 — Technical Foundation**
Establish the repositories, applications, Docker environment, RabbitMQ, Supabase connectivity, Flyway, configuration management, health checks, logging, and CI/CD.

**Phase P1 — Identity and Academic Management**
Implement authentication, roster imports, activation, classes, enrollments, projects, and team formation.

**Phase P2 — Workflow and Document Intake**
Implement configurable SDLC workflows, templates, Google Drive storage, document submission, versioning, validation, and revision management.

**Phase P3 — AI Document Evaluation and Instructor Review**
Implement RabbitMQ evaluation processing, Java orchestration, Python document analysis, AI providers, rubric evaluation, findings, instructor decisions, approval, and publication.

Completion of P3 establishes the MVP release.

**Phase P4 — Requirements Intelligence**
Implement requirements extraction, instructor-approved baselines, cross-document evaluation, and traceability management.

**Phase P5 — Document–Implementation Verification**
Implement GitHub and ZIP analysis, static analysis, testing evidence interpretation, and requirements-to-code mapping.

**Phase P6 — Advanced Evaluation and Governance**
Implement advanced targeted reevaluation, requirement change-impact analysis, finding supersession, multi-approver workflows, comprehensive exports, and historical analytics.

**Phase P7 — Operational Hardening**
Complete retention automation, advanced backup restoration, monitoring improvements, recovery testing, scaling refinements, and optional self-hosted AI integration.

## 9. TESTING AND ACCEPTANCE REQUIREMENTS

### 9.1 Functional Testing
Verify all role-based workflows, including account activation, roster import, project formation, template assignment, document intake, evaluation, review, approval, and publication.

### 9.2 Authorization Testing
Test unauthorized cross-class, cross-project, and cross-reviewer access.

Confirm that students cannot retrieve unpublished evaluation content from any application endpoint.

### 9.3 Database Testing
Validate schema migrations, uniqueness constraints, referential integrity, concurrent submissions, and version preservation.

### 9.4 Document Processing Testing
Test valid and invalid DOCX structures, unsupported files, malformed PDFs, oversized uploads, inaccessible Drive links, and template-version mismatches.

### 9.5 AI Evaluation Testing
Use controlled document fixtures to assess:

* Completeness detection.
* Structural compliance.
* Ambiguity identification.
* Consistency evaluation.
* Rubric adherence.
* Evidence accuracy.
* False findings.
* Structured output validity.

An AI finding must not be considered correct solely because its language appears persuasive.

### 9.6 Worker and Queue Testing
Test duplicate job delivery, worker interruption, queue reconnect, retry limits, cancellation, dead-letter routing, and evaluation recovery.

### 9.7 Review and Approval Testing
Validate the full instructor review lifecycle, score overrides, multi-approver rules, conflicting reviewer changes, and publication permissions.

### 9.8 Deployment Testing
Verify deployment through Render Blueprint, secure configuration, database connectivity, worker communication, RabbitMQ persistence, and application health checks.

### 9.9 Recovery Testing
Restore database and configuration backups in an isolated environment.

Verify whether artifact manifests correctly resolve to recoverable files and whether evaluation history remains consistent.

## 10. MINIMUM ACCEPTANCE CRITERIA FOR MVP
The MVP is acceptable when all the following conditions are met:

1. An administrator can create or import student accounts.
2. Students can activate their accounts and securely sign in.
3. An instructor can create a class and manage its student roster.
4. Individual and group projects can be established.
5. An instructor can create or assign an SDLC workflow.
6. An instructor can upload and publish a structured DOCX template.
7. A student can download the template and submit a completed document.
8. Direct uploads and supported Google Drive links are imported into authorized storage.
9. Strict or warning-only structural validation works as configured.
10. Official submission versions are preserved.
11. An instructor can create or select an evaluation rubric.
12. An instructor can initiate an AI document evaluation.
13. RabbitMQ and independent workers process the evaluation request.
14. AI findings are recorded with supporting evidence and limitations.
15. An instructor can accept, modify, reject, or supplement individual AI findings.
16. Authorized final approvers can finalize evaluations under the configured approval policy.
17. Students can access only the approved and published evaluation results.
18. Audit records preserve significant submission, review, and approval actions.
19. The system can be deployed through the defined Render configuration.
20. Essential backup and recovery procedures are demonstrated successfully.

## 11. FINAL PROJECT DELIVERABLES
The completed project shall provide:

* A responsive web-based frontend.
* A secure Spring Boot application backend.
* A Supabase PostgreSQL database.
* Database migration scripts.
* Google Drive storage integration.
* Academic class and project management.
* Configurable SDLC workflows.
* Structured document templates.
* Student submission and versioning.
* AI-assisted document assessment.
* Document–implementation verification capabilities.
* Rubric and instructor review management.
* Approved feedback publication.
* Evaluation reports.
* System monitoring and audit records.
* Containerized workers and RabbitMQ.
* Docker development configuration.
* Render Blueprint deployment configuration.
* Automated tests.
* Technical documentation.
* Operational and recovery documentation.

## 12. PROJECT SUCCESS CRITERIA
The project shall be considered successful when it provides instructors with a reliable and practical mechanism for evaluating student software development deliverables throughout the development lifecycle.

Success is not determined by the number of AI findings produced.

Rather, success depends on whether the platform can:

1. Maintain accurate academic and project records.
2. Preserve document and evaluation history.
3. Evaluate documentation against explicit standards.
4. Generate traceable and evidence-backed AI recommendations.
5. Support verification of documented requirements against implementation evidence.
6. Preserve instructor judgment and academic decision authority.
7. Provide actionable feedback to students.
8. Support incremental improvement across development stages.
9. Protect student information and academic records.
10. Operate consistently under realistic academic workloads.

The Document–Implementation Evaluator shall function as an AI-assisted evaluation and academic governance platform, not as an autonomous grading system.
