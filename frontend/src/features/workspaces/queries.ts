import { useQuery } from '@tanstack/react-query'
import { fetchProject, fetchWorkspace, fetchWorkspaceProjects, fetchWorkspaces } from '@/api'

export const workspaceKeys = {
  all: ['workspaces'] as const,
  detail: (id: string) => ['workspaces', id] as const,
  projects: (id: string) => ['workspaces', id, 'projects'] as const,
}
export const projectKey = (id: string) => ['projects', id] as const

export const useWorkspaces = () =>
  useQuery({ queryKey: workspaceKeys.all, queryFn: ({ signal }) => fetchWorkspaces(signal) })

export const useWorkspace = (id: string) =>
  useQuery({ queryKey: workspaceKeys.detail(id), queryFn: ({ signal }) => fetchWorkspace(id, signal) })

export const useWorkspaceProjects = (id: string, enabled = true) =>
  useQuery({
    queryKey: workspaceKeys.projects(id),
    queryFn: ({ signal }) => fetchWorkspaceProjects(id, signal),
    enabled,
  })

export const useProject = (id: string) =>
  useQuery({ queryKey: projectKey(id), queryFn: ({ signal }) => fetchProject(id, signal) })
