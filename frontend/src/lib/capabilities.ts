import { capabilityLabel } from './roles'

/**
 * The permissions a Coordinator can hold in one Project Workspace, in display order. `now` ones are enforced by the API
 * today; the others are stored but only take effect when their feature ships (the screen says so instead of hiding them).
 * The Owner holds all of them and cannot be reduced.
 */
export const WORKSPACE_CAPABILITIES: ReadonlyArray<{ code: string; help: string; now: boolean }> = [
  { code: 'MANAGE_SETTINGS', help: 'Change the workspace settings and archive it.', now: true },
  { code: 'MANAGE_ROSTER', help: 'Enroll and remove Participants, import rosters, re-issue temporary credentials.', now: true },
  { code: 'MANAGE_PROJECTS', help: 'Create, assign and manage projects and teams.', now: true },
  { code: 'MANAGE_COORDINATORS', help: 'Add and remove Coordinators and change their permissions.', now: true },
  { code: 'CONFIGURE_REQUIREMENTS', help: 'Set up requirements (coming later).', now: false },
  { code: 'CONFIGURE_WORKFLOW', help: 'Set up the project workflow (coming later).', now: false },
  { code: 'MANAGE_TEMPLATES', help: 'Manage document templates (coming later).', now: false },
  { code: 'MANAGE_STORAGE', help: 'Choose where documents are stored (coming later).', now: false },
  { code: 'INITIATE_EVALUATION', help: 'Start an AI evaluation (coming later).', now: false },
  { code: 'REVIEW', help: 'Review evaluation results (coming later).', now: false },
  { code: 'APPROVE', help: 'Approve reviewed results (coming later).', now: false },
  { code: 'PUBLISH', help: 'Publish feedback to Participants (coming later).', now: false },
]

/** What a new Coordinator gets when nothing else is chosen (mirrors the API default). */
export const DEFAULT_CAPABILITIES = ['MANAGE_ROSTER', 'MANAGE_PROJECTS']

export const capabilityNames = (codes: readonly string[]): string => codes.map(capabilityLabel).join(', ')
