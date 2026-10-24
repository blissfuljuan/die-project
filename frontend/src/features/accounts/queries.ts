import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  activateAccount,
  createAccount,
  deactivateAccount,
  fetchAccounts,
  resetAccountCredentials,
  type AccountQuery,
  type NewAccount,
} from '@/api'

export const accountKeys = { all: ['accounts'] as const }

export const useAccounts = (query: AccountQuery) =>
  useQuery({
    queryKey: [...accountKeys.all, query],
    queryFn: ({ signal }) => fetchAccounts(query, signal),
    placeholderData: keepPreviousData,
  })

function useRefreshAccounts() {
  const queryClient = useQueryClient()
  return () => void queryClient.invalidateQueries({ queryKey: accountKeys.all })
}

/** The results of create / activate / reset hold a one-time secret: they live in mutation state, never in the query cache. */
export function useCreateAccount() {
  const refresh = useRefreshAccounts()
  return useMutation({ mutationFn: (input: NewAccount) => createAccount(input), onSuccess: refresh })
}

export function useDeactivateAccount() {
  const refresh = useRefreshAccounts()
  return useMutation({ mutationFn: (id: string) => deactivateAccount(id), onSuccess: refresh })
}

export function useActivateAccount() {
  const refresh = useRefreshAccounts()
  return useMutation({ mutationFn: (id: string) => activateAccount(id), onSuccess: refresh })
}

export function useResetCredentials() {
  const refresh = useRefreshAccounts()
  return useMutation({ mutationFn: (id: string) => resetAccountCredentials(id), onSuccess: refresh })
}
