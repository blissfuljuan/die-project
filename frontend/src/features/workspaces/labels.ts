import type { Workspace } from '@/api'

export const workspaceTitle = (w: Pick<Workspace, 'subjectCode' | 'subjectName'>) => `${w.subjectCode} ${w.subjectName}`.trim()

export const workspaceSubtitle = (w: Pick<Workspace, 'academicYear' | 'semester' | 'section'>) =>
  [w.academicYear, w.semester, w.section ? `Section ${w.section}` : null].filter(Boolean).join(' · ')

export const teamFormationLabel = (formation: string) =>
  formation === 'COORDINATOR_ASSIGNED' ? 'Teams are assigned by Coordinators' : 'Participants form their own teams'

export const projectKindLabel = (kind: string) => (kind === 'TEAM' ? 'Team project' : 'Individual project')

export const teamRoleLabel = (role: string | null | undefined) => {
  if (role === 'REPRESENTATIVE') return 'Representative'
  if (role === 'MEMBER') return 'Member'
  if (role === 'STAFF') return 'Coordinator'
  return role ?? ''
}
