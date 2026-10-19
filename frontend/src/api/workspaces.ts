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
