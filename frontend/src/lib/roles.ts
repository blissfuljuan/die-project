import type { Role } from '@/api/auth'

/** Display names. The UI never says class/student/instructor (except the institutional label "Student ID"). */
export const ROLE_LABEL: Record<Role, string> = {
  PARTICIPANT: 'Participant',
  COORDINATOR: 'Coordinator',
  ADMIN: 'Administrator',
}

/**
 * Route decision (documented in frontend/README.md): one area per role. These are UX only; the API decides
 * what a signed-in user may actually read or change.
 */
export const HOME_PATH: Record<Role, string> = {
  PARTICIPANT: '/app',
  COORDINATOR: '/coordinator',
  ADMIN: '/admin',
}

export const homePathFor = (role: Role): string => HOME_PATH[role]

export interface NavItem {
  to: string
  label: string
  /** Shown as "Coming soon": the route exists but belongs to a later slice. */
  soon?: boolean
  end?: boolean
}

export const NAV_BY_ROLE: Record<Role, NavItem[]> = {
  PARTICIPANT: [
    { to: '/app', label: 'Dashboard', end: true },
    { to: '/me', label: 'My profile' },
  ],
  COORDINATOR: [
    { to: '/coordinator', label: 'Dashboard', end: true },
    { to: '/me', label: 'My profile' },
  ],
  ADMIN: [
    { to: '/admin', label: 'Dashboard', end: true },
    { to: '/admin/accounts', label: 'Accounts', soon: true },
    { to: '/admin/workspaces', label: 'Project Workspaces' },
    { to: '/me', label: 'My profile' },
  ],
}

const CAPABILITY_LABEL: Record<string, string> = {
  MANAGE_SETTINGS: 'Settings',
  MANAGE_ROSTER: 'Participants',
  MANAGE_PROJECTS: 'Projects',
  MANAGE_COORDINATORS: 'Coordinators',
  CONFIGURE_REQUIREMENTS: 'Requirements',
  CONFIGURE_WORKFLOW: 'Workflow',
  MANAGE_TEMPLATES: 'Templates',
  MANAGE_STORAGE: 'Storage',
  INITIATE_EVALUATION: 'Start evaluation',
  REVIEW: 'Review',
  APPROVE: 'Approve',
  PUBLISH: 'Publish',
}

export const capabilityLabel = (code: string): string =>
  CAPABILITY_LABEL[code] ?? code.toLowerCase().replace(/_/g, ' ')

export const fullName = (p: { firstName: string; lastName: string }): string => `${p.firstName} ${p.lastName}`.trim()
