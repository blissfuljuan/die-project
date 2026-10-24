import { QueryClientProvider, type QueryClient } from '@tanstack/react-query'
import { render } from '@testing-library/react'
import { MemoryRouter, useLocation } from 'react-router-dom'
import type { Me } from '@/api'
import { createQueryClient } from '@/lib/queryClient'
import { AppRoutes } from '@/routes/AppRoutes'
import { API, http, HttpResponse, server } from './server'

export const participant: Me = {
  id: 'u-participant',
  email: 'ana@example.edu',
  firstName: 'Ana',
  lastName: 'Reyes',
  role: 'PARTICIPANT',
}
export const coordinator: Me = {
  id: 'u-coordinator',
  email: 'carl@example.edu',
  firstName: 'Carl',
  lastName: 'Dela Cruz',
  role: 'COORDINATOR',
}
export const admin: Me = {
  id: 'u-admin',
  email: 'ada@example.edu',
  firstName: 'Ada',
  lastName: 'Admin',
  role: 'ADMIN',
}

export const problem = (status: number, code: string, detail = 'Problem', extra: object = {}) =>
  HttpResponse.json({ status, title: detail, detail, code, traceId: 't-1', ...extra }, { status })

/** Makes GET /auth/me answer as the given user, or 401 when null (signed out). */
export function mockSession(user: Me | null) {
  server.use(
    http.get(`${API}/auth/me`, () =>
      user ? HttpResponse.json(user) : problem(401, 'AUTHENTICATION_REQUIRED', 'Authentication required'),
    ),
  )
}

function LocationProbe() {
  const l = useLocation()
  return <output data-testid="location">{l.pathname + l.search}</output>
}

/** Renders the real route map at `route`. A `<output data-testid="location">` shows the current URL. */
export function renderApp(route = '/', client: QueryClient = createQueryClient()) {
  client.setDefaultOptions({ queries: { retry: false, staleTime: 0 } })
  const view = render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[route]}>
        <AppRoutes />
        <LocationProbe />
      </MemoryRouter>
    </QueryClientProvider>,
  )
  return { ...view, client }
}
