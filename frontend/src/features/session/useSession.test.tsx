import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { renderHook, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { API, http, HttpResponse, server } from '@/test/server'
import { mockSession, participant, problem } from '@/test/utils'
import { SESSION_KEY } from './sessionKey'
import { useLogin, useLogout, useSession } from './useSession'

function setup() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  )
  return { client, wrapper }
}

describe('useSession', () => {
  it('returns the signed-in user', async () => {
    mockSession(participant)
    const { wrapper } = setup()
    const { result } = renderHook(() => useSession(), { wrapper })
    expect(result.current.isLoading).toBe(true)
    await waitFor(() => expect(result.current.user?.email).toBe('ana@example.edu'))
    expect(result.current.isError).toBe(false)
  })

  it('treats 401 as signed out (not as an error)', async () => {
    mockSession(null)
    const { wrapper } = setup()
    const { result } = renderHook(() => useSession(), { wrapper })
    await waitFor(() => expect(result.current.isLoading).toBe(false))
    expect(result.current.user).toBeNull()
    expect(result.current.isError).toBe(false)
  })

  it('reports an unreachable server as an error, not as signed out', async () => {
    server.use(http.get(`${API}/auth/me`, () => HttpResponse.error()))
    const { wrapper } = setup()
    const { result } = renderHook(() => useSession(), { wrapper })
    await waitFor(() => expect(result.current.isError).toBe(true))
  })

  it('rejects a body that does not match the schema', async () => {
    server.use(http.get(`${API}/auth/me`, () => HttpResponse.json({ id: 'x', role: 'SUPERUSER' })))
    const { wrapper } = setup()
    const { result } = renderHook(() => useSession(), { wrapper })
    await waitFor(() => expect(result.current.isError).toBe(true))
  })
})

describe('login and logout', () => {
  it('login sets the session and clears other cached data', async () => {
    server.use(http.post(`${API}/auth/login`, () => HttpResponse.json(participant)))
    const { client, wrapper } = setup()
    client.setQueryData(['workspaces'], { stale: true })
    const { result } = renderHook(() => useLogin(), { wrapper })
    result.current.mutate({ email: 'ana@example.edu', password: 'pw' })
    await waitFor(() => expect(client.getQueryData(SESSION_KEY)).toEqual(participant))
    expect(client.getQueryData(['workspaces'])).toBeUndefined()
  })

  it('login failure leaves the session untouched', async () => {
    server.use(http.post(`${API}/auth/login`, () => problem(401, 'INVALID_CREDENTIALS')))
    const { client, wrapper } = setup()
    const { result } = renderHook(() => useLogin(), { wrapper })
    result.current.mutate({ email: 'a@b.c', password: 'bad' })
    await waitFor(() => expect(result.current.isError).toBe(true))
    expect(client.getQueryData(SESSION_KEY)).toBeUndefined()
  })

  it('logout clears the cache even when the server call fails', async () => {
    server.use(http.post(`${API}/auth/logout`, () => problem(401, 'AUTHENTICATION_REQUIRED')))
    const { client, wrapper } = setup()
    client.setQueryData(SESSION_KEY, participant)
    client.setQueryData(['workspaces'], { items: [] })
    const { result } = renderHook(() => useLogout(), { wrapper })
    result.current.mutate()
    await waitFor(() => expect(client.getQueryData(SESSION_KEY)).toBeNull())
    expect(client.getQueryData(['workspaces'])).toBeUndefined()
  })
})
