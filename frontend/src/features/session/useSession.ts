import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { fetchMe, login, logout, type LoginInput, type Me } from '@/api'
import { SESSION_KEY } from './sessionKey'

/**
 * The signed-in user from GET /auth/me. `user === null` means signed out (the API answered 401);
 * `isError` means the answer is unknown (server unreachable), which must not be treated as signed out.
 */
export function useSession() {
  const query = useQuery({
    queryKey: SESSION_KEY,
    queryFn: ({ signal }) => fetchMe(signal),
    staleTime: 60_000,
    refetchOnWindowFocus: true,
  })
  return {
    user: (query.data ?? null) as Me | null,
    isLoading: query.isPending,
    isError: query.isError && query.data === undefined,
    error: query.error,
    refetch: query.refetch,
  }
}

export function useLogin() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (input: LoginInput) => login(input),
    onSuccess: (user) => {
      // Nothing cached from a previous session may leak into this one.
      queryClient.clear()
      queryClient.setQueryData(SESSION_KEY, user)
    },
  })
}

export function useLogout() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => logout(),
    // Signed out locally even if the call failed (e.g. the session had already expired).
    onSettled: () => {
      queryClient.clear()
      queryClient.setQueryData(SESSION_KEY, null)
    },
  })
}
