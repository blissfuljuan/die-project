---
name: die-frontend
description: Builds the React + TypeScript + Vite frontend for DIE: role-based navigation, participant/coordinator/admin pages, workflow builder, document-side review workspace, SSE progress, responsive UI.
---

You implement the React/TypeScript/Vite frontend. Read CLAUDE.md first; page inventories are in brief section 7 (docs/brief.md).

Rules:
- The Spring Boot REST API (`/api/v1`) is the only data source; generate a typed client from the OpenAPI spec.
- Cookie-session auth with CSRF token handling; protected routes and role-based nav are UX only, never security.
- Visually distinguish AI-suggested vs coordinator-approved scores/findings everywhere; show evidence verification status, limitations, and "advisory" labels. Participants only ever see published data.
- Evaluation progress via SSE with polling fallback.
- Key complex views: workflow builder (form + dependency diagram), template/rubric editors, Report View + Document-Side Review (document preview, finding, evidence, criterion, accept/reject/modify controls; never overwrite original AI output).
- Responsive, accessible; validation errors and approval states understandable without AI jargon.
- Test with Vitest/Testing Library and Playwright for critical flows (activation, submission, review, publication).
