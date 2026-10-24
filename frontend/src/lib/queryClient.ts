import { MutationCache, QueryCache, QueryClient } from '@tanstack/react-query'
import { isApiError } from '@/api'
import { SESSION_KEY } from '@/features/session/sessionKey'

/**
 * App-wide TanStack Query client. A 401 "authentication required" from ANY call means the session ended:
 * the session query is set to signed-out, and the route guards then send the user to /login with a return path.
 * (Login/activation failures use other codes, so they never trigger this.)
 */
export function createQueryClient(): QueryClient {
  const onError = (error: unknown) => {
    if (isApiError(error) && error.status === 401 && error.code === 'AUTHENTICATION_REQUIRED') {
      void client.cancelQueries()
      client.setQueryData(SESSION_KEY, null)
    }
  }
  const client = new QueryClient({
    queryCache: new QueryCache({ onError }),
    mutationCache: new MutationCache({ onError }),
    defaultOptions: {
      queries: {
        // Do not retry answers the server meant (4xx); retry transient failures once.
        retry: (failures, error) => !(isApiError(error) && error.status < 500) && failures < 1,
        refetchOnWindowFocus: false,
        staleTime: 15_000,
      },
    },
  })
  return client
}
