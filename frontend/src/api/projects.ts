import { z } from 'zod'
import { apiClient, type ApiClient } from './client'

export const memberSchema = z.object({
  userId: z.string(),
  firstName: z.string(),
  lastName: z.string(),
  studentNumber: z.string().nullish(),
  role: z.string(),
  joinedAt: z.string().nullish(),
})

export const invitationSchema = z.object({
  id: z.string(),
  participantId: z.string(),
  firstName: z.string(),
  lastName: z.string(),
  status: z.string(),
  expiresAt: z.string().nullish(),
  createdAt: z.string().nullish(),
})

export const teamPolicySchema = z.object({
  formation: z.string(),
  minSize: z.number(),
  maxSize: z.number(),
  participantsMayChangeTeams: z.boolean(),
})

export const projectSchema = z.object({
  id: z.string(),
  workspaceId: z.string(),
  kind: z.string(),
  title: z.string().nullish(),
  description: z.string().nullish(),
  teamName: z.string().nullish(),
  status: z.string(),
  version: z.number(),
  createdAt: z.string().nullish(),
  myRole: z.string().nullish(),
  members: z.array(memberSchema).default([]),
  teamPolicy: teamPolicySchema.nullish(),
  meetsMinSize: z.boolean().nullish(),
  pendingInvitations: z.array(invitationSchema).default([]),
})
export type Project = z.infer<typeof projectSchema>
export type ProjectMember = z.infer<typeof memberSchema>

const projectPageSchema = z.object({ items: z.array(projectSchema), page: z.number(), size: z.number(), total: z.number() })

/** The API pages this list; the screens show the first 100 projects of a workspace (the largest page the API serves). */
export const fetchWorkspaceProjects = (workspaceId: string, signal?: AbortSignal, client: ApiClient = apiClient) =>
  client.get<Project[]>(`/workspaces/${encodeURIComponent(workspaceId)}/projects?size=100`, {
    signal,
    parse: (d) => projectPageSchema.parse(d).items,
  })

export const fetchProject = (id: string, signal?: AbortSignal, client: ApiClient = apiClient) =>
  client.get<Project>(`/projects/${encodeURIComponent(id)}`, { signal, parse: (d) => projectSchema.parse(d) })
