import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  cancelImport,
  commitImport,
  enrollParticipant,
  fetchImportDetail,
  fetchRoster,
  reissueTemporaryCredential,
  resolveImportRows,
  unenrollParticipant,
  uploadRosterFile,
  type EnrollInput,
} from '@/api'
import { workspaceKeys } from '@/features/workspaces/queries'

export const rosterKeys = {
  list: (workspaceId: string) => ['workspaces', workspaceId, 'roster'] as const,
  page: (workspaceId: string, page: number, size: number, includeRemoved: boolean) =>
    ['workspaces', workspaceId, 'roster', { page, size, includeRemoved }] as const,
  importDetail: (importId: string, action: string, page: number) => ['roster-imports', importId, { action, page }] as const,
  importAll: (importId: string) => ['roster-imports', importId] as const,
}

export const useRoster = (workspaceId: string, page: number, size: number, includeRemoved: boolean) =>
  useQuery({
    queryKey: rosterKeys.page(workspaceId, page, size, includeRemoved),
    queryFn: ({ signal }) => fetchRoster(workspaceId, { page, size, includeRemoved }, signal),
    placeholderData: keepPreviousData,
  })

/** The roster changes the enrolled count shown on the workspace too. */
function useRefreshRoster(workspaceId: string) {
  const queryClient = useQueryClient()
  return () => {
    void queryClient.invalidateQueries({ queryKey: rosterKeys.list(workspaceId) })
    void queryClient.invalidateQueries({ queryKey: workspaceKeys.detail(workspaceId) })
    void queryClient.invalidateQueries({ queryKey: workspaceKeys.all })
  }
}

export function useEnroll(workspaceId: string) {
  const refresh = useRefreshRoster(workspaceId)
  return useMutation({ mutationFn: (input: EnrollInput) => enrollParticipant(workspaceId, input), onSuccess: refresh })
}

export function useUnenroll(workspaceId: string) {
  const refresh = useRefreshRoster(workspaceId)
  return useMutation({ mutationFn: (participantId: string) => unenrollParticipant(workspaceId, participantId), onSuccess: refresh })
}

/** The result holds a one-time secret: it lives in the mutation state only, never in the query cache. */
export function useReissueCredential(workspaceId: string) {
  return useMutation({ mutationFn: (participantId: string) => reissueTemporaryCredential(workspaceId, participantId) })
}

// ---- import ----

export const useUploadRoster = (workspaceId: string) =>
  useMutation({ mutationFn: (file: File) => uploadRosterFile(workspaceId, file) })

export const useImportDetail = (importId: string | null, action: string, page: number, size: number) =>
  useQuery({
    queryKey: rosterKeys.importDetail(importId ?? '', action, page),
    queryFn: ({ signal }) => fetchImportDetail(importId ?? '', { action: action || undefined, page, size }, signal),
    enabled: importId !== null,
    placeholderData: keepPreviousData,
  })

export function useResolveImport(importId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (resolutions: Array<{ rowNumber: number; resolution: string }>) => resolveImportRows(importId, resolutions),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: rosterKeys.importAll(importId) }),
  })
}

/** The commit response carries the new accounts' temporary credentials, so the page keeps it in state and nowhere else. */
export function useCommitImport(importId: string, workspaceId: string) {
  const refresh = useRefreshRoster(workspaceId)
  return useMutation({ mutationFn: () => commitImport(importId), onSuccess: refresh })
}

export const useCancelImport = (importId: string) => useMutation({ mutationFn: () => cancelImport(importId) })
