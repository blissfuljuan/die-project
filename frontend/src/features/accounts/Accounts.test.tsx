import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { API, http, HttpResponse, server } from '@/test/server'
import { admin, coordinator, mockSession, problem, renderApp } from '@/test/utils'

const account = (over: Record<string, unknown> = {}) => ({
  id: 'a-1',
  role: 'PARTICIPANT',
  status: 'ACTIVE',
  studentNumber: '2024-0001',
  lastName: 'Reyes',
  firstName: 'Ana',
  middleName: null,
  email: null,
  emailVerified: true,
  createdAt: '2026-10-01T00:00:00Z',
  version: 1,
  ...over,
})

const ana = account()
const ben = account({ id: 'a-2', studentNumber: '2024-0002', lastName: 'Santos', firstName: 'Ben', status: 'PENDING_ACTIVATION' })
const carl = account({ id: 'u-coordinator', role: 'COORDINATOR', studentNumber: null, email: 'carl@example.edu', lastName: 'Dela Cruz', firstName: 'Carl' })
const dora = account({ id: 'a-4', studentNumber: '2024-0004', lastName: 'Lim', firstName: 'Dora', status: 'DEACTIVATED' })
const me = account({ id: 'u-admin', role: 'ADMIN', studentNumber: null, email: 'ada@example.edu', lastName: 'Admin', firstName: 'Ada' })

const page = (items: unknown[], over: Record<string, unknown> = {}) => ({ items, page: 0, size: 25, total: items.length, ...over })

/** Answers GET /users and records every query string. */
function mockUsers(items: unknown[] = [me, ana, ben, carl, dora]) {
  const requests: URL[] = []
  server.use(
    http.get(`${API}/users`, ({ request }) => {
      const url = new URL(request.url)
      requests.push(url)
      return HttpResponse.json(page(items))
    }),
  )
  return requests
}

const change = (user: unknown, secret: string | null = null) => ({
  user,
  temporaryCredential: secret,
  temporaryCredentialExpiresAt: secret ? '2030-01-15T00:00:00Z' : null,
})

describe('Accounts list', () => {
  it('lists accounts with Student ID for Participants, email for staff, and no actions on your own account', async () => {
    mockSession(admin)
    mockUsers()
    renderApp('/admin/accounts')
    const table = await screen.findByRole('table', { name: 'Accounts' })
    const anaRow = within(table).getByText('Ana Reyes').closest('tr')!
    expect(within(anaRow).getByText('2024-0001')).toBeInTheDocument()
    expect(within(anaRow).getByText('Participant')).toBeInTheDocument()
    expect(within(anaRow).getByText('Active')).toBeInTheDocument()
    const carlRow = within(table).getByText('Carl Dela Cruz').closest('tr')!
    expect(within(carlRow).getByText('carl@example.edu')).toBeInTheDocument()
    const benRow = within(table).getByText('Ben Santos').closest('tr')!
    expect(within(benRow).getByText('Waiting to activate')).toBeInTheDocument()

    const meRow = within(table).getByText('Ada Admin').closest('tr')!
    expect(within(meRow).getByText('(you)')).toBeInTheDocument()
    expect(within(meRow).queryByRole('button')).not.toBeInTheDocument()
  })

  it('offers Activate again only for deactivated accounts, and Reset/Deactivate only for the others', async () => {
    mockSession(admin)
    mockUsers()
    renderApp('/admin/accounts')
    const table = await screen.findByRole('table', { name: 'Accounts' })
    const doraRow = within(table).getByText('Dora Lim').closest('tr')!
    expect(within(doraRow).getByRole('button', { name: 'Activate Dora Lim again' })).toBeInTheDocument()
    expect(within(doraRow).queryByRole('button', { name: /Deactivate|Reset/ })).not.toBeInTheDocument()
    const anaRow = within(table).getByText('Ana Reyes').closest('tr')!
    expect(within(anaRow).getByRole('button', { name: 'Reset the credentials of Ana Reyes' })).toBeInTheDocument()
    expect(within(anaRow).getByRole('button', { name: 'Deactivate Ana Reyes' })).toBeInTheDocument()
    expect(within(anaRow).queryByRole('button', { name: /Activate/ })).not.toBeInTheDocument()
  })

  it('searches and filters through the API', async () => {
    mockSession(admin)
    const requests = mockUsers()
    const user = userEvent.setup()
    renderApp('/admin/accounts')
    await screen.findByRole('table', { name: 'Accounts' })

    await user.type(screen.getByLabelText('Search accounts'), 'ana')
    await waitFor(() => expect(requests.at(-1)?.searchParams.get('q')).toBe('ana'))
    await user.selectOptions(screen.getByLabelText('Filter by role'), 'COORDINATOR')
    await waitFor(() => expect(requests.at(-1)?.searchParams.get('role')).toBe('COORDINATOR'))
    await user.selectOptions(screen.getByLabelText('Filter by status'), 'DEACTIVATED')
    await waitFor(() => expect(requests.at(-1)?.searchParams.get('status')).toBe('DEACTIVATED'))
    expect(requests.at(-1)?.searchParams.get('q')).toBe('ana')
    expect(requests.at(-1)?.searchParams.get('page')).toBe('0')
  })

  it('pages through many accounts', async () => {
    mockSession(admin)
    const requests: URL[] = []
    server.use(
      http.get(`${API}/users`, ({ request }) => {
        const url = new URL(request.url)
        requests.push(url)
        const p = Number(url.searchParams.get('page'))
        return HttpResponse.json(page([p === 0 ? ana : ben], { page: p, total: 60 }))
      }),
    )
    const user = userEvent.setup()
    renderApp('/admin/accounts')
    expect(await screen.findByText('Ana Reyes')).toBeInTheDocument()
    expect(screen.getByText(/Page 1 of 3/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Next' }))
    expect(await screen.findByText('Ben Santos')).toBeInTheDocument()
    expect(requests.at(-1)?.searchParams.get('page')).toBe('1')
  })

  it('shows an empty state and an error with retry', async () => {
    mockSession(admin)
    mockUsers([])
    const { unmount } = renderApp('/admin/accounts')
    expect(await screen.findByText('No accounts match')).toBeInTheDocument()
    unmount()

    mockSession(admin)
    let calls = 0
    server.use(
      http.get(`${API}/users`, () => {
        calls += 1
        return calls === 1 ? problem(500, 'INTERNAL_ERROR') : HttpResponse.json(page([ana]))
      }),
    )
    const user = userEvent.setup()
    renderApp('/admin/accounts')
    await user.click(await screen.findByRole('button', { name: 'Try again' }))
    expect(await screen.findByText('Ana Reyes')).toBeInTheDocument()
  })

  it('is closed to everyone but Administrators', async () => {
    mockSession(coordinator)
    server.use(http.get(`${API}/workspaces`, () => HttpResponse.json(page([]))))
    renderApp('/admin/accounts')
    expect(await screen.findByRole('heading', { name: /not found, or no access/i })).toBeInTheDocument()
  })
})

describe('Create an account', () => {
  async function openForm(user: ReturnType<typeof userEvent.setup>) {
    await user.click(await screen.findByRole('button', { name: 'New account' }))
  }

  it('asks for what is missing', async () => {
    mockSession(admin)
    mockUsers()
    const user = userEvent.setup()
    renderApp('/admin/accounts')
    await openForm(user)
    await user.click(screen.getByRole('button', { name: 'Create account' }))
    expect(await screen.findByText('Enter the first name.')).toBeInTheDocument()
    expect(screen.getByText('Enter the last name.')).toBeInTheDocument()
    expect(screen.getByText('Enter the Student ID.')).toBeInTheDocument()
  })

  it('creates a Participant by Student ID and shows the temporary credential once', async () => {
    mockSession(admin)
    mockUsers()
    let sent: Record<string, unknown> | null = null
    server.use(
      http.post(`${API}/users`, async ({ request }) => {
        sent = (await request.json()) as Record<string, unknown>
        return HttpResponse.json(change(account({ id: 'a-9', lastName: 'Lim', firstName: 'Cleo', studentNumber: '2024-0099' }), 'New-secret-123'), { status: 201 })
      }),
    )
    const user = userEvent.setup()
    renderApp('/admin/accounts')
    await openForm(user)
    await user.type(screen.getByLabelText('First name'), 'Cleo')
    await user.type(screen.getByLabelText('Last name'), 'Lim')
    await user.type(screen.getByLabelText('Student ID'), '2024-0099')
    await user.click(screen.getByRole('button', { name: 'Create account' }))

    await waitFor(() => expect(sent).toEqual({ role: 'PARTICIPANT', lastName: 'Lim', firstName: 'Cleo', studentNumber: '2024-0099' }))
    expect(await screen.findByText('New-secret-123')).toBeInTheDocument()
    expect(screen.getByText(/Shown only once/i)).toBeInTheDocument()
    expect(screen.getByText('The account for Cleo Lim was created.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Create account' })).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'I have handed it over' }))
    expect(screen.queryByText('New-secret-123')).not.toBeInTheDocument()
  })

  it('a Coordinator is identified by email instead', async () => {
    mockSession(admin)
    mockUsers()
    let sent: Record<string, unknown> | null = null
    server.use(
      http.post(`${API}/users`, async ({ request }) => {
        sent = (await request.json()) as Record<string, unknown>
        return HttpResponse.json(change(carl, 'Coord-secret'), { status: 201 })
      }),
    )
    const user = userEvent.setup()
    renderApp('/admin/accounts')
    await openForm(user)
    await user.selectOptions(screen.getByLabelText('Role'), 'COORDINATOR')
    expect(screen.queryByLabelText('Student ID')).not.toBeInTheDocument()
    await user.type(screen.getByLabelText('First name'), 'Carl')
    await user.type(screen.getByLabelText('Last name'), 'Dela Cruz')

    await user.type(screen.getByLabelText('Email'), 'not-an-email')
    await user.click(screen.getByRole('button', { name: 'Create account' }))
    expect(await screen.findByText('Enter a valid email address.')).toBeInTheDocument()
    expect(sent).toBeNull()

    await user.clear(screen.getByLabelText('Email'))
    await user.type(screen.getByLabelText('Email'), 'carl@example.edu')
    await user.click(screen.getByRole('button', { name: 'Create account' }))
    await waitFor(() => expect(sent).toEqual({ role: 'COORDINATOR', lastName: 'Dela Cruz', firstName: 'Carl', email: 'carl@example.edu' }))
    expect(await screen.findByText('Coord-secret')).toBeInTheDocument()
  })

  it('shows the API message when the Student ID or email is taken', async () => {
    mockSession(admin)
    mockUsers()
    server.use(http.post(`${API}/users`, () => problem(409, 'ALREADY_EXISTS')))
    const user = userEvent.setup()
    renderApp('/admin/accounts')
    await openForm(user)
    await user.type(screen.getByLabelText('First name'), 'Ana')
    await user.type(screen.getByLabelText('Last name'), 'Reyes')
    await user.type(screen.getByLabelText('Student ID'), '2024-0001')
    await user.click(screen.getByRole('button', { name: 'Create account' }))
    expect(await screen.findByText(/already exists/i)).toBeInTheDocument()
  })

  it('can be cancelled', async () => {
    mockSession(admin)
    mockUsers()
    const user = userEvent.setup()
    renderApp('/admin/accounts')
    await openForm(user)
    await user.click(screen.getByRole('button', { name: 'Cancel' }))
    expect(screen.queryByRole('button', { name: 'Create account' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'New account' })).toBeInTheDocument()
  })
})

describe('Deactivate, activate and reset', () => {
  it('deactivates only after confirming', async () => {
    mockSession(admin)
    mockUsers()
    let calls = 0
    server.use(http.post(`${API}/users/a-1/deactivate`, () => ((calls += 1), HttpResponse.json(account({ status: 'DEACTIVATED' })))))
    const user = userEvent.setup()
    renderApp('/admin/accounts')
    await user.click(await screen.findByRole('button', { name: 'Deactivate Ana Reyes' }))
    expect(calls).toBe(0)
    const confirm = screen.getByRole('group', { name: 'Confirm deactivating Ana Reyes' })
    await user.click(within(confirm).getByRole('button', { name: 'Deactivate Ana Reyes' }))
    await waitFor(() => expect(calls).toBe(1))
    expect(await screen.findByText('Ana Reyes was deactivated and signed out.')).toBeInTheDocument()
  })

  it('cancelling the confirmation changes nothing', async () => {
    mockSession(admin)
    mockUsers()
    let calls = 0
    server.use(http.post(`${API}/users/a-1/deactivate`, () => ((calls += 1), HttpResponse.json(ana))))
    const user = userEvent.setup()
    renderApp('/admin/accounts')
    await user.click(await screen.findByRole('button', { name: 'Deactivate Ana Reyes' }))
    await user.click(within(screen.getByRole('group', { name: 'Confirm deactivating Ana Reyes' })).getByRole('button', { name: 'Cancel' }))
    expect(screen.queryByRole('group', { name: /Confirm deactivating/ })).not.toBeInTheDocument()
    expect(calls).toBe(0)
  })

  it('explains that the Owner of an active workspace must hand ownership over first, and links to the workspaces', async () => {
    mockSession(admin)
    mockUsers()
    server.use(http.post(`${API}/users/u-coordinator/deactivate`, () => problem(409, 'WORKSPACE_OWNER_TRANSFER_REQUIRED')))
    const user = userEvent.setup()
    renderApp('/admin/accounts')
    await user.click(await screen.findByRole('button', { name: 'Deactivate Carl Dela Cruz' }))
    const confirm = screen.getByRole('group', { name: 'Confirm deactivating Carl Dela Cruz' })
    await user.click(within(confirm).getByRole('button', { name: 'Deactivate Carl Dela Cruz' }))

    expect(await within(confirm).findByText(/owns an active Project Workspace/i)).toBeInTheDocument()
    expect(within(confirm).getByRole('link', { name: 'Open Project Workspaces to transfer ownership' })).toHaveAttribute('href', '/admin/workspaces')
    // Nothing was deactivated: the account is still listed as active.
    const row = screen.getByText('Carl Dela Cruz').closest('tr')!
    expect(within(row).getByText('Active')).toBeInTheDocument()
  })

  it('resets credentials after confirming and shows the new temporary credential once', async () => {
    mockSession(admin)
    mockUsers()
    let calls = 0
    server.use(http.post(`${API}/users/a-1/reset-credentials`, () => ((calls += 1), HttpResponse.json(change(account({ status: 'PENDING_ACTIVATION' }), 'Reset-secret-77')))))
    const user = userEvent.setup()
    renderApp('/admin/accounts')
    await user.click(await screen.findByRole('button', { name: 'Reset the credentials of Ana Reyes' }))
    expect(calls).toBe(0)
    const confirm = screen.getByRole('group', { name: 'Confirm resetting Ana Reyes' })
    expect(within(confirm).getByText(/signed out everywhere/i)).toBeInTheDocument()
    await user.click(within(confirm).getByRole('button', { name: 'Reset Ana Reyes' }))

    expect(await screen.findByText('Reset-secret-77')).toBeInTheDocument()
    expect(calls).toBe(1)
    expect(screen.getByText(/was signed out and must activate again/i)).toBeInTheDocument()
  })

  it('activates a deactivated account again', async () => {
    mockSession(admin)
    mockUsers()
    let calls = 0
    server.use(http.post(`${API}/users/a-4/activate`, () => ((calls += 1), HttpResponse.json(change(account({ id: 'a-4', status: 'ACTIVE' }))))))
    const user = userEvent.setup()
    renderApp('/admin/accounts')
    await user.click(await screen.findByRole('button', { name: 'Activate Dora Lim again' }))
    await waitFor(() => expect(calls).toBe(1))
    expect(await screen.findByText('Dora Lim can sign in again.')).toBeInTheDocument()
    expect(screen.queryByText(/Shown only once/i)).not.toBeInTheDocument()
  })

  it('shows a new temporary credential when the re-activated account never finished activating', async () => {
    mockSession(admin)
    mockUsers()
    server.use(
      http.post(`${API}/users/a-4/activate`, () => HttpResponse.json(change(account({ id: 'a-4', status: 'PENDING_ACTIVATION' }), 'Again-secret-5'))),
    )
    const user = userEvent.setup()
    renderApp('/admin/accounts')
    await user.click(await screen.findByRole('button', { name: 'Activate Dora Lim again' }))
    expect(await screen.findByText('Again-secret-5')).toBeInTheDocument()
    expect(screen.getByText(/must activate the account again with a new temporary credential/i)).toBeInTheDocument()
  })

  it('shows the API message when the account changed in the meantime', async () => {
    mockSession(admin)
    mockUsers()
    server.use(http.post(`${API}/users/a-4/activate`, () => problem(409, 'ACCOUNT_NOT_DEACTIVATED')))
    const user = userEvent.setup()
    renderApp('/admin/accounts')
    await user.click(await screen.findByRole('button', { name: 'Activate Dora Lim again' }))
    expect(await screen.findByText('Only deactivated accounts can be activated again.')).toBeInTheDocument()
  })
})
