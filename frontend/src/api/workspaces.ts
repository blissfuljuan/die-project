import { z } from 'zod'
import { apiClient, type ApiClient } from './client'

export const coordinatorRefSchema = z.object({
  id: z.string(),
  firstName: z.string(),
  lastName: z.string(),
  owner: z.boolean(),
})

export const workspaceSchema = z.object({
  id: z.string(),
  academicYear: z.string().nullish(),
  semester: z.string().nullish(),
  subjectCode: z.string(),
  subjectName: z.string(),
  section: z.string().nullish(),
  status: z.string(),
  teamFormation: z.string(),
  teamMinSize: z.number(),
  teamMaxSize: z.number(),
  participantsMayChangeTeams: z.boolean(),
  enrolledCount: z.number(),
  version: z.number(),
  ownerId: z.string().nullish(),
  myCapabilities: z.array(z.string()).default([]),
  coordinators: z.array(coordinatorRefSchema).default([]),
})
export type Workspace = z.infer<typeof workspaceSchema>
export type CoordinatorRef = z.infer<typeof coordinatorRefSchema>

export const workspacePageSchema = z.object({
  items: z.array(workspaceSchema),
  page: z.number(),
  size: z.number(),
  total: z.number(),
})
export type WorkspacePage = z.infer<typeof workspacePageSchema>

/** The backend caps `size` at 100. */
export const fetchWorkspaces = (signal?: AbortSignal, client: ApiClient = apiClient) =>
  client.get<WorkspacePage>('/workspaces?size=100', { signal, parse: (d) => workspacePageSchema.parse(d) })

export const fetchWorkspace = (id: string, signal?: AbortSignal, client: ApiClient = apiClient) =>
  client.get<Workspace>(`/workspaces/${encodeURIComponent(id)}`, { signal, parse: (d) => workspaceSchema.parse(d) })

// ---- management (create / edit / archive) ----

export const TEAM_FORMATIONS = ['COORDINATOR_ASSIGNED', 'PARTICIPANT_FORMED'] as const
export type TeamFormation = (typeof TEAM_FORMATIONS)[number]

/** The settings of a Project Workspace as the create/edit forms send them. */
export interface WorkspaceSettings {
  academicYear: string
  semester: string
  subjectCode: string
  subjectName: string
  section: string
  teamFormation: TeamFormation
  teamMinSize: number
  teamMaxSize: number
  participantsMayChangeTeams: boolean
}

/** Administrators must name the Owner; a Coordinator who creates a workspace becomes its Owner. */
export const createWorkspace = (input: WorkspaceSettings & { ownerId?: string }, client: ApiClient = apiClient) =>
  client.post<Workspace>('/workspaces', input, { parse: (d) => workspaceSchema.parse(d) })

/** `version` is the last version the user saw; the API answers 409 CLASS_VERSION_CONFLICT when it is stale. */
export const updateWorkspace = (id: string, input: WorkspaceSettings & { version: number }, client: ApiClient = apiClient) =>
  client.patch<Workspace>(`/workspaces/${encodeURIComponent(id)}`, input, { parse: (d) => workspaceSchema.parse(d) })

export const archiveWorkspace = (id: string, client: ApiClient = apiClient) =>
  client.post<Workspace>(`/workspaces/${encodeURIComponent(id)}/archive`, undefined, { parse: (d) => workspaceSchema.parse(d) })

/** Administrators only. */
export const unarchiveWorkspace = (id: string, client: ApiClient = apiClient) =>
  client.post<Workspace>(`/workspaces/${encodeURIComponent(id)}/unarchive`, undefined, { parse: (d) => workspaceSchema.parse(d) })

// ---- coordinators and ownership ----

export const coordinatorEntrySchema = z.object({
  userId: z.string(),
  firstName: z.string(),
  lastName: z.string(),
  owner: z.boolean(),
  capabilities: z.array(z.string()).default([]),
  assignedAt: z.string().nullish(),
})
export type CoordinatorEntry = z.infer<typeof coordinatorEntrySchema>

export const coordinatorCandidateSchema = z.object({
  userId: z.string(),
  firstName: z.string(),
  lastName: z.string(),
  email: z.string(),
})
export type CoordinatorCandidate = z.infer<typeof coordinatorCandidateSchema>

const ws = (id: string) => `/workspaces/${encodeURIComponent(id)}`

export const fetchCoordinators = (id: string, signal?: AbortSignal, client: ApiClient = apiClient) =>
  client.get<CoordinatorEntry[]>(`${ws(id)}/coordinators`, { signal, parse: (d) => z.array(coordinatorEntrySchema).parse(d) })

/** Unassigned active Coordinator accounts whose name or email starts with `q` (3+ characters; at most 10 come back). */
export const fetchCoordinatorCandidates = (id: string, q: string, signal?: AbortSignal, client: ApiClient = apiClient) =>
  client.get<CoordinatorCandidate[]>(`${ws(id)}/coordinator-candidates?q=${encodeURIComponent(q)}`, {
    signal,
    parse: (d) => z.array(coordinatorCandidateSchema).parse(d),
  })

export const addCoordinator = (id: string, input: { userId: string; capabilities: string[] }, client: ApiClient = apiClient) =>
  client.post<CoordinatorEntry>(`${ws(id)}/coordinators`, input, { parse: (d) => coordinatorEntrySchema.parse(d) })

export const setCoordinatorCapabilities = (id: string, userId: string, capabilities: string[], client: ApiClient = apiClient) =>
  client.patch<CoordinatorEntry>(`${ws(id)}/coordinators/${encodeURIComponent(userId)}`, { capabilities }, {
    parse: (d) => coordinatorEntrySchema.parse(d),
  })

export const removeCoordinator = (id: string, userId: string, client: ApiClient = apiClient) =>
  client.delete<void>(`${ws(id)}/coordinators/${encodeURIComponent(userId)}`)

/** Owner or Administrator. Takes effect at once and is audited (ADR 0003). */
export const transferOwnership = (id: string, userId: string, client: ApiClient = apiClient) =>
  client.put<void>(`${ws(id)}/owner`, { userId })
