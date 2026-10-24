import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { API, http, HttpResponse, server } from '@/test/server'
import { admin, coordinator, mockSession, participant, problem, renderApp } from '@/test/utils'

const emptyPage = { items: [], page: 0, size: 100, total: 0 }

function mockLists() {
  server.use(
    http.get(`${API}/workspaces`, () => HttpResponse.json(emptyPage)),
    http.get(`${API}/ops/health`, () => HttpResponse.json({
      status: 'UP', checkedAt: '2026-10-19T10:00:00Z',
      database: { status: 'UP' }, broker: { status: 'UP' }, workers: [],
      outbox: { pending: 0, failed: 0 }, testJobs: { queued: 0, running: 0, succeeded: 0, failed: 0, overdue: 0 },
      queueDepths: {},
    })),
  )
}

describe('route guards', () => {
  it('sends a signed-out visitor to /login and remembers the deep link', async () => {
    mockSession(null)
    renderApp('/workspaces/w-1?tab=x')
    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument()
    expect(screen.getByTestId('location')).toHaveTextContent('/login?next=%2Fworkspaces%2Fw-1%3Ftab%3Dx')
  })

  it('redirects a signed-in user away from the login page to their own area', async () => {
    mockSession(coordinator)
    mockLists()
    renderApp('/login')
    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent('/coordinator'))
  })

  it('honours a safe return path and ignores an unsafe one', async () => {
    mockSession(participant)
    mockLists()
    renderApp('/login?next=//evil.example')
    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent('/app'))
  })

  it('shows the forbidden screen when the role does not match the area', async () => {
    mockSession(participant)
    mockLists()
    renderApp('/admin')
    expect(await screen.findByRole('heading', { name: /not found, or no access/i })).toBeInTheDocument()
    expect(screen.getByTestId('location')).toHaveTextContent('/admin')
  })

  it('shows not found for an unknown path', async () => {
    mockSession(admin)
    renderApp('/nope')
    expect(await screen.findByRole('heading', { name: /not found, or no access/i })).toBeInTheDocument()
  })

  it('sends "/" to the role home', async () => {
    mockSession(admin)
    mockLists()
    renderApp('/')
    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent('/admin'))
  })

  it('does not treat an unreachable server as signed out', async () => {
    server.use(http.get(`${API}/auth/me`, () => HttpResponse.error()))
    renderApp('/app')
    expect(await screen.findByText(/could not check your session/i)).toBeInTheDocument()
    expect(screen.getByTestId('location')).toHaveTextContent('/app')
  })

  it('ends the session and returns to /login when any call answers 401 AUTHENTICATION_REQUIRED', async () => {
    mockSession(participant)
    server.use(
      http.get(`${API}/workspaces`, () => {
        mockSession(null) // the real API would now answer 401 to /auth/me as well
        return problem(401, 'AUTHENTICATION_REQUIRED')
      }),
    )
    renderApp('/app')
    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent('/login?next=%2Fapp'))
    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument()
  })
})

describe('navigation by role', () => {
  it('Participant sees only dashboard and profile', async () => {
    mockSession(participant)
    mockLists()
    renderApp('/app')
    const nav = (await screen.findAllByRole('navigation', { name: 'Main' }))[0]!
    expect(within(nav).getByRole('link', { name: 'Dashboard' })).toBeInTheDocument()
    expect(within(nav).getByRole('link', { name: 'My profile' })).toBeInTheDocument()
    expect(within(nav).queryByRole('link', { name: /accounts/i })).not.toBeInTheDocument()
  })

  it('Administrator sees Accounts labelled "Coming soon" and Project Workspaces as a working page', async () => {
    mockSession(admin)
    mockLists()
    renderApp('/admin')
    const nav = (await screen.findAllByRole('navigation', { name: 'Main' }))[0]!
    expect(within(nav).getByRole('link', { name: /accounts/i })).toHaveTextContent(/coming soon/i)
    expect(within(nav).getByRole('link', { name: /project workspaces/i })).not.toHaveTextContent(/coming soon/i)
  })

  it('shows the user name and role in the menu and signs out, clearing the session', async () => {
    mockSession(coordinator)
    mockLists()
    let loggedOut = false
    server.use(
      http.post(`${API}/auth/logout`, () => {
        loggedOut = true
        mockSession(null)
        return new HttpResponse(null, { status: 204 })
      }),
    )
    const user = userEvent.setup()
    renderApp('/coordinator')
    await user.click(await screen.findByRole('button', { name: /carl dela cruz/i }))
    expect(screen.getByRole('menu')).toHaveTextContent('Coordinator')
    await user.click(screen.getByRole('menuitem', { name: 'Sign out' }))
    await waitFor(() => expect(loggedOut).toBe(true))
    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument()
  })
})
