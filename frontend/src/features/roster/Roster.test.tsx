import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { API, http, HttpResponse, server } from '@/test/server'
import { admin, coordinator, mockSession, participant, problem, renderApp } from '@/test/utils'

const workspace = (over: Record<string, unknown> = {}) => ({
  id: 'w-1',
  academicYear: '2026-2027',
  semester: '1st',
  subjectCode: 'CS101',
  subjectName: 'Software Engineering',
  section: 'A',
  status: 'ACTIVE',
  teamFormation: 'PARTICIPANT_FORMED',
  teamMinSize: 2,
  teamMaxSize: 4,
  participantsMayChangeTeams: false,
  enrolledCount: 2,
  version: 1,
  ownerId: 'u-coordinator',
  myCapabilities: ['MANAGE_SETTINGS', 'MANAGE_ROSTER', 'MANAGE_PROJECTS', 'MANAGE_COORDINATORS'],
  coordinators: [{ id: 'u-coordinator', firstName: 'Carl', lastName: 'Dela Cruz', owner: true }],
  ...over,
})

const ana = {
  participantId: 'p-ana',
  studentNumber: '2024-0001',
  lastName: 'Reyes',
  firstName: 'Ana',
  middleName: null,
  email: 'ana@example.edu',
  accountStatus: 'ACTIVE',
  program: 'BSIT',
  section: 'A',
  status: 'ENROLLED',
  enrolledAt: null,
  removedAt: null,
}
const ben = { ...ana, participantId: 'p-ben', studentNumber: '2024-0002', lastName: 'Santos', firstName: 'Ben', email: null, accountStatus: 'PENDING_ACTIVATION' }

const rosterPage = (items: unknown[], over: Record<string, unknown> = {}) => ({ items, page: 0, size: 50, total: items.length, ...over })

function mockRoster(items: unknown[] = [ana, ben], wsOver: Record<string, unknown> = {}) {
  const requests: URL[] = []
  server.use(
    http.get(`${API}/workspaces/w-1`, () => HttpResponse.json(workspace(wsOver))),
    http.get(`${API}/workspaces/w-1/enrollments`, ({ request }) => {
      const url = new URL(request.url)
      requests.push(url)
      return HttpResponse.json(rosterPage(items))
    }),
  )
  return requests
}

describe('Roster page', () => {
  it('lists the Participants with their account state and only offers a new credential to those who have not activated', async () => {
    mockSession(coordinator)
    mockRoster()
    renderApp('/workspaces/w-1/roster')
    const table = await screen.findByRole('table', { name: 'Participants of this Project Workspace' })
    const anaRow = within(table).getByText('Ana Reyes').closest('tr')!
    expect(within(anaRow).getByText('Active')).toBeInTheDocument()
    expect(within(anaRow).queryByRole('button', { name: /new temporary credential/i })).not.toBeInTheDocument()
    const benRow = within(table).getByText('Ben Santos').closest('tr')!
    expect(within(benRow).getByText('Waiting to activate')).toBeInTheDocument()
    expect(within(benRow).getByRole('button', { name: 'Issue a new temporary credential for Ben Santos' })).toBeInTheDocument()
  })

  it('pages through a long roster and can include removed Participants', async () => {
    mockSession(coordinator)
    const requests: URL[] = []
    server.use(
      http.get(`${API}/workspaces/w-1`, () => HttpResponse.json(workspace({ enrolledCount: 120 }))),
      http.get(`${API}/workspaces/w-1/enrollments`, ({ request }) => {
        const url = new URL(request.url)
        requests.push(url)
        const page = Number(url.searchParams.get('page'))
        return HttpResponse.json(rosterPage([page === 0 ? ana : ben], { page, total: 120 }))
      }),
    )
    const user = userEvent.setup()
    renderApp('/workspaces/w-1/roster')
    expect(await screen.findByText('Ana Reyes')).toBeInTheDocument()
    expect(screen.getByText(/Page 1 of 3/)).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Next' }))
    expect(await screen.findByText('Ben Santos')).toBeInTheDocument()
    expect(requests.at(-1)?.searchParams.get('page')).toBe('1')

    await user.click(screen.getByLabelText('Show removed Participants'))
    await waitFor(() => expect(requests.at(-1)?.searchParams.get('includeRemoved')).toBe('true'))
    expect(requests.at(-1)?.searchParams.get('page')).toBe('0')
  })

  it('shows an empty state', async () => {
    mockSession(coordinator)
    mockRoster([])
    renderApp('/workspaces/w-1/roster')
    expect(await screen.findByText('No Participants yet')).toBeInTheDocument()
  })

  it('is not available to a Coordinator without the Participants permission', async () => {
    mockSession(coordinator)
    mockRoster([ana], { myCapabilities: ['MANAGE_PROJECTS'] })
    renderApp('/workspaces/w-1/roster')
    expect(await screen.findByRole('heading', { name: /not found, or no access/i })).toBeInTheDocument()
  })

  it('is read-only once the workspace is archived', async () => {
    mockSession(coordinator)
    mockRoster([ana], { status: 'ARCHIVED' })
    renderApp('/workspaces/w-1/roster')
    expect(await screen.findByText(/archived, so its roster can no longer be changed/i)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Enroll Participant' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^Remove/ })).not.toBeInTheDocument()
  })

  it('a Participant cannot open it', async () => {
    mockSession(participant)
    server.use(
      http.get(`${API}/workspaces`, () => HttpResponse.json({ items: [], page: 0, size: 100, total: 0 })),
      http.get(`${API}/invitations`, () => HttpResponse.json([])),
    )
    renderApp('/workspaces/w-1/roster')
    expect(await screen.findByRole('heading', { name: /not found, or no access/i })).toBeInTheDocument()
  })
})

describe('Enroll a Participant', () => {
  it('asks for the Student ID and, from a Coordinator, the last name', async () => {
    mockSession(coordinator)
    mockRoster()
    const user = userEvent.setup()
    renderApp('/workspaces/w-1/roster')
    await user.click(await screen.findByRole('button', { name: 'Enroll Participant' }))
    expect(await screen.findByText('Enter the Student ID.')).toBeInTheDocument()
    expect(screen.getByText("Enter the Participant's last name.")).toBeInTheDocument()
  })

  it('enrolls with the details given and clears the form', async () => {
    mockSession(coordinator)
    mockRoster()
    let sent: Record<string, unknown> | null = null
    server.use(
      http.post(`${API}/workspaces/w-1/enrollments`, async ({ request }) => {
        sent = (await request.json()) as Record<string, unknown>
        return new HttpResponse(null, { status: 201 })
      }),
    )
    const user = userEvent.setup()
    renderApp('/workspaces/w-1/roster')
    await user.type(await screen.findByLabelText('Student ID'), '2024-0099')
    await user.type(screen.getByLabelText('Last name'), 'Lim')
    await user.type(screen.getByLabelText('Section (optional)'), 'B')
    await user.click(screen.getByRole('button', { name: 'Enroll Participant' }))

    await waitFor(() => expect(sent).toEqual({ studentNumber: '2024-0099', lastName: 'Lim', section: 'B' }))
    expect(await screen.findByText('2024-0099 is now enrolled.')).toBeInTheDocument()
    expect(screen.getByLabelText('Student ID')).toHaveValue('')
  })

  it('an Administrator may leave the last name out', async () => {
    mockSession(admin)
    mockRoster([ana], { myCapabilities: ['MANAGE_SETTINGS', 'MANAGE_ROSTER'] })
    let sent: Record<string, unknown> | null = null
    server.use(
      http.post(`${API}/workspaces/w-1/enrollments`, async ({ request }) => {
        sent = (await request.json()) as Record<string, unknown>
        return new HttpResponse(null, { status: 201 })
      }),
    )
    const user = userEvent.setup()
    renderApp('/workspaces/w-1/roster')
    await user.type(await screen.findByLabelText('Student ID'), '2024-0099')
    await user.click(screen.getByRole('button', { name: 'Enroll Participant' }))
    await waitFor(() => expect(sent).toMatchObject({ studentNumber: '2024-0099', lastName: '' }))
  })

  it('shows the API message when nobody matches, without saying which part was wrong', async () => {
    mockSession(coordinator)
    mockRoster()
    server.use(http.post(`${API}/workspaces/w-1/enrollments`, () => problem(404, 'PARTICIPANT_NOT_FOUND')))
    const user = userEvent.setup()
    renderApp('/workspaces/w-1/roster')
    await user.type(await screen.findByLabelText('Student ID'), '2024-0099')
    await user.type(screen.getByLabelText('Last name'), 'Nobody')
    await user.click(screen.getByRole('button', { name: 'Enroll Participant' }))
    expect(await screen.findByText(/No Participant matches that Student ID and last name/i)).toBeInTheDocument()
  })

  it('says so when the Participant is already enrolled', async () => {
    mockSession(coordinator)
    mockRoster()
    server.use(http.post(`${API}/workspaces/w-1/enrollments`, () => problem(409, 'ALREADY_ENROLLED')))
    const user = userEvent.setup()
    renderApp('/workspaces/w-1/roster')
    await user.type(await screen.findByLabelText('Student ID'), '2024-0001')
    await user.type(screen.getByLabelText('Last name'), 'Reyes')
    await user.click(screen.getByRole('button', { name: 'Enroll Participant' }))
    expect(await screen.findByText('That Participant is already enrolled in this Project Workspace.')).toBeInTheDocument()
  })
})

describe('Temporary credentials and removal', () => {
  it('shows a re-issued credential once and then forgets it', async () => {
    mockSession(coordinator)
    mockRoster()
    server.use(
      http.post(`${API}/workspaces/w-1/enrollments/p-ben/temporary-credential`, () =>
        HttpResponse.json({ secret: 'Zk3-secret-AAAA', expiresAt: '2030-01-15T00:00:00Z' }),
      ),
    )
    const user = userEvent.setup()
    renderApp('/workspaces/w-1/roster')
    await user.click(await screen.findByRole('button', { name: 'Issue a new temporary credential for Ben Santos' }))
    expect(await screen.findByText('Zk3-secret-AAAA')).toBeInTheDocument()
    expect(screen.getByText(/Shown only once/i)).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'I have handed it over' }))
    expect(screen.queryByText('Zk3-secret-AAAA')).not.toBeInTheDocument()
  })

  it('shows the rate-limit message', async () => {
    mockSession(coordinator)
    mockRoster()
    server.use(http.post(`${API}/workspaces/w-1/enrollments/p-ben/temporary-credential`, () => problem(429, 'CREDENTIAL_REISSUE_LIMIT')))
    const user = userEvent.setup()
    renderApp('/workspaces/w-1/roster')
    await user.click(await screen.findByRole('button', { name: 'Issue a new temporary credential for Ben Santos' }))
    expect(await screen.findByText(/Too many temporary credentials were issued recently/i)).toBeInTheDocument()
  })

  it('says to use a password reset when the account is already active', async () => {
    mockSession(coordinator)
    mockRoster()
    server.use(http.post(`${API}/workspaces/w-1/enrollments/p-ben/temporary-credential`, () => problem(409, 'ACCOUNT_ALREADY_ACTIVATED')))
    const user = userEvent.setup()
    renderApp('/workspaces/w-1/roster')
    await user.click(await screen.findByRole('button', { name: 'Issue a new temporary credential for Ben Santos' }))
    expect(await screen.findByText(/already activated their account/i)).toBeInTheDocument()
  })

  it('removes a Participant only after confirming', async () => {
    mockSession(coordinator)
    mockRoster()
    let removed = 0
    server.use(http.delete(`${API}/workspaces/w-1/enrollments/p-ana`, () => ((removed += 1), new HttpResponse(null, { status: 204 }))))
    const user = userEvent.setup()
    renderApp('/workspaces/w-1/roster')
    await user.click(await screen.findByRole('button', { name: 'Remove Ana Reyes from this Project Workspace' }))
    expect(removed).toBe(0)
    const confirm = screen.getByRole('group', { name: 'Confirm removing Ana Reyes' })
    await user.click(within(confirm).getByRole('button', { name: 'Remove Ana Reyes' }))
    await waitFor(() => expect(removed).toBe(1))
    expect(await screen.findByText('Ana Reyes was removed.')).toBeInTheDocument()
  })

  it('cancelling the confirmation removes nobody', async () => {
    mockSession(coordinator)
    mockRoster()
    let removed = 0
    server.use(http.delete(`${API}/workspaces/w-1/enrollments/p-ana`, () => ((removed += 1), new HttpResponse(null, { status: 204 }))))
    const user = userEvent.setup()
    renderApp('/workspaces/w-1/roster')
    await user.click(await screen.findByRole('button', { name: 'Remove Ana Reyes from this Project Workspace' }))
    await user.click(within(screen.getByRole('group', { name: 'Confirm removing Ana Reyes' })).getByRole('button', { name: 'Cancel' }))
    expect(screen.queryByRole('group', { name: 'Confirm removing Ana Reyes' })).not.toBeInTheDocument()
    expect(removed).toBe(0)
  })
})

describe('Link from the workspace page', () => {
  const detailMocks = (over: Record<string, unknown>) =>
    server.use(
      http.get(`${API}/workspaces/w-1`, () => HttpResponse.json(workspace(over))),
      http.get(`${API}/workspaces/w-1/coordinators`, () => HttpResponse.json([])),
      http.get(`${API}/workspaces/w-1/projects`, () => HttpResponse.json({ items: [], page: 0, size: 100, total: 0 })),
    )

  it('offers Manage Participants to a Coordinator who may manage the roster', async () => {
    mockSession(coordinator)
    detailMocks({})
    renderApp('/workspaces/w-1')
    expect(await screen.findByRole('link', { name: 'Manage Participants' })).toHaveAttribute('href', '/workspaces/w-1/roster')
  })

  it('does not offer it otherwise', async () => {
    mockSession(coordinator)
    detailMocks({ myCapabilities: ['MANAGE_PROJECTS'], ownerId: 'someone-else' })
    renderApp('/workspaces/w-1')
    expect(await screen.findByRole('heading', { name: 'CS101 Software Engineering' })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Manage Participants' })).not.toBeInTheDocument()
  })
})
