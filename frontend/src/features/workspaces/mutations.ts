import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  addCoordinator,
  archiveWorkspace,
  createWorkspace,
  fetchCoordinators,
  removeCoordinator,
  setCoordinatorCapabilities,
  transferOwnership,
  unarchiveWorkspace,
  updateWorkspace,
  type Workspace,
  type WorkspaceSettings,
} from '@/api'
import { workspaceKeys } from './queries'

export const coordinatorKeys = {
  list: (workspaceId: string) => ['workspaces', workspaceId, 'coordinators'] as const,
}

/** Lists, dashboards and the detail page all show workspace data: refresh them after any change. */
function useRefreshWorkspaces() {
  const queryClient = useQueryClient()
  return (workspace?: Workspace) => {
    if (workspace) queryClient.setQueryData(workspaceKeys.detail(workspace.id), workspace)
    void queryClient.invalidateQueries({ queryKey: workspaceKeys.all })
  }
}

export function useCreateWorkspace() {
  const refresh = useRefreshWorkspaces()
  return useMutation({
    mutationFn: (input: WorkspaceSettings & { ownerId?: string }) => createWorkspace(input),
    onSuccess: (workspace) => refresh(workspace),
  })
}

export function useUpdateWorkspace(id: string) {
  const refresh = useRefreshWorkspaces()
  return useMutation({
    mutationFn: (input: WorkspaceSettings & { version: number }) => updateWorkspace(id, input),
    onSuccess: (workspace) => refresh(workspace),
  })
}

export function useArchiveWorkspace(id: string) {
  const refresh = useRefreshWorkspaces()
  return useMutation({ mutationFn: () => archiveWorkspace(id), onSuccess: (workspace) => refresh(workspace) })
}

export function useUnarchiveWorkspace(id: string) {
  const refresh = useRefreshWorkspaces()
  return useMutation({ mutationFn: () => unarchiveWorkspace(id), onSuccess: (workspace) => refresh(workspace) })
}

// ---- coordinators ----

export const useCoordinators = (workspaceId: string, enabled = true) =>
  useQuery({
    queryKey: coordinatorKeys.list(workspaceId),
    queryFn: ({ signal }) => fetchCoordinators(workspaceId, signal),
    enabled,
  })

/** Who is a Coordinator, their permissions and the Owner all show up in the workspace too (myCapabilities, ownerId). */
function useRefreshCoordinators(workspaceId: string) {
  const queryClient = useQueryClient()
  return () => {
    void queryClient.invalidateQueries({ queryKey: coordinatorKeys.list(workspaceId) })
    void queryClient.invalidateQueries({ queryKey: workspaceKeys.all })
    void queryClient.invalidateQueries({ queryKey: workspaceKeys.detail(workspaceId) })
  }
}

export function useAddCoordinator(workspaceId: string) {
  const refresh = useRefreshCoordinators(workspaceId)
  return useMutation({
    mutationFn: (input: { userId: string; capabilities: string[] }) => addCoordinator(workspaceId, input),
    onSuccess: refresh,
  })
}

export function useSetCoordinatorCapabilities(workspaceId: string) {
  const refresh = useRefreshCoordinators(workspaceId)
  return useMutation({
    mutationFn: (input: { userId: string; capabilities: string[] }) =>
      setCoordinatorCapabilities(workspaceId, input.userId, input.capabilities),
    onSuccess: refresh,
  })
}

export function useRemoveCoordinator(workspaceId: string) {
  const refresh = useRefreshCoordinators(workspaceId)
  return useMutation({ mutationFn: (userId: string) => removeCoordinator(workspaceId, userId), onSuccess: refresh })
}

export function useTransferOwnership(workspaceId: string) {
  const refresh = useRefreshCoordinators(workspaceId)
  return useMutation({ mutationFn: (userId: string) => transferOwnership(workspaceId, userId), onSuccess: refresh })
}
