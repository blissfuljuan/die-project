import { useMutation, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query'
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

/**
 * Drops every cached answer except the session and then stores the new session value. queryClient.clear() must not be used
 * here: it also deletes the session query that the route guards are observing, and those observers would never see the new
 * value, leaving the user on the login page after a successful sign-in.
 */
function replaceSession(queryClient: QueryClient, user: Me | null) {
  queryClient.removeQueries({ predicate: (q) => q.queryKey[0] !== SESSION_KEY[0] })
  queryClient.setQueryData(SESSION_KEY, user)
}

export function useLogin() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (input: LoginInput) => login(input),
    onSuccess: (user) => {
      // Nothing cached from a previous session may leak into this one.
      replaceSession(queryClient, user)
    },
  })
}

export function useLogout() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => logout(),
    // Signed out locally even if the call failed (e.g. the session had already expired).
    onSettled: () => {
      replaceSession(queryClient, null)
    },
  })
}
