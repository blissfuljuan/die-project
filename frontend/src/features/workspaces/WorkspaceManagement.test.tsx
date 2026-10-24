import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { API, http, HttpResponse, server } from '@/test/server'
import { admin, coordinator, mockSession, participant, problem, renderApp } from '@/test/utils'

const ALL = [
  'MANAGE_SETTINGS',
  'MANAGE_ROSTER',
  'MANAGE_PROJECTS',
  'MANAGE_COORDINATORS',
  'CONFIGURE_REQUIREMENTS',
  'CONFIGURE_WORKFLOW',
  'MANAGE_TEMPLATES',
  'MANAGE_STORAGE',
  'INITIATE_EVALUATION',
  'REVIEW',
  'APPROVE',
  'PUBLISH',
]

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
  enrolledCount: 12,
  version: 3,
  ownerId: 'u-coordinator',
  myCapabilities: ALL,
  coordinators: [{ id: 'u-coordinator', firstName: 'Carl', lastName: 'Dela Cruz', owner: true }],
  ...over,
})

const carl = { userId: 'u-coordinator', firstName: 'Carl', lastName: 'Dela Cruz', owner: true, capabilities: ALL, assignedAt: null }
const dana = {
  userId: 'u-dana',
  firstName: 'Dana',
  lastName: 'Cruz',
  owner: false,
  capabilities: ['MANAGE_ROSTER', 'MANAGE_PROJECTS'],
  assignedAt: null,
}

/** Mocks everything the workspace page asks for. */
function mockWorkspace(over: Record<string, unknown> = {}, entries: unknown[] = [carl, dana]) {
  server.use(
    http.get(`${API}/workspaces/w-1`, () => HttpResponse.json(workspace(over))),
    http.get(`${API}/workspaces/w-1/coordinators`, () => HttpResponse.json(entries)),
    http.get(`${API}/workspaces/w-1/projects`, () => HttpResponse.json({ items: [], page: 0, size: 100, total: 0 })),
  )
}

const page = (items: unknown[]) => ({ items, page: 0, size: 100, total: items.length })

async function fillSettings(user: ReturnType<typeof userEvent.setup>) {
  await user.type(await screen.findByLabelText('Academic year'), '2026-2027')
  await user.type(screen.getByLabelText('Semester'), '1st')
  await user.type(screen.getByLabelText('Subject code'), 'CS101')
  await user.type(screen.getByLabelText('Section'), 'A')
  await user.type(screen.getByLabelText('Subject name'), 'Software Engineering')
}

describe('Create a Project Workspace', () => {
  it('a Coordinator creates one without naming an Owner and lands on its page', async () => {
    mockSession(coordinator)
    let sent: Record<string, unknown> | null = null
    server.use(
      http.post(`${API}/workspaces`, async ({ request }) => {
        sent = (await request.json()) as Record<string, unknown>
        return HttpResponse.json(workspace({ id: 'w-9' }), { status: 201 })
      }),
      http.get(`${API}/workspaces/w-9`, () => HttpResponse.json(workspace({ id: 'w-9' }))),
      http.get(`${API}/workspaces/w-9/coordinators`, () => HttpResponse.json([carl])),
      http.get(`${API}/workspaces/w-9/projects`, () => HttpResponse.json(page([]))),
    )
    const user = userEvent.setup()
    renderApp('/workspaces/new')
    await fillSettings(user)
    await user.click(screen.getByRole('button', { name: 'Create Project Workspace' }))

    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent('/workspaces/w-9'))
    expect(sent).toMatchObject({
      subjectCode: 'CS101',
      teamFormation: 'COORDINATOR_ASSIGNED',
      teamMinSize: 1,
      teamMaxSize: 5,
      participantsMayChangeTeams: false,
    })
    expect(sent).not.toHaveProperty('ownerId')
  })

  it('explains what is missing before calling the API', async () => {
    mockSession(coordinator)
    const user = userEvent.setup()
    renderApp('/workspaces/new')
    await user.click(await screen.findByRole('button', { name: 'Create Project Workspace' }))
    expect(await screen.findByText('Enter the academic year.')).toBeInTheDocument()
    expect(screen.getByText('Enter the subject name.')).toBeInTheDocument()
  })

  it('refuses a largest team that is smaller than the smallest', async () => {
    mockSession(coordinator)
    const user = userEvent.setup()
    renderApp('/workspaces/new')
    await fillSettings(user)
    const min = screen.getByLabelText('Smallest team')
    const max = screen.getByLabelText('Largest team')
    await user.clear(min)
    await user.type(min, '6')
    await user.clear(max)
    await user.type(max, '3')
    await user.click(screen.getByRole('button', { name: 'Create Project Workspace' }))
    expect(await screen.findByText('The maximum cannot be smaller than the minimum.')).toBeInTheDocument()
  })

  it('an Administrator must choose an Owner, found by name or email', async () => {
    mockSession(admin)
    let sent: Record<string, unknown> | null = null
    let searched = ''
    server.use(
      http.get(`${API}/users`, ({ request }) => {
        searched = new URL(request.url).searchParams.get('q') ?? ''
        return HttpResponse.json(
          page([{ id: 'u-coordinator', role: 'COORDINATOR', status: 'ACTIVE', firstName: 'Carl', lastName: 'Dela Cruz', email: 'carl@example.edu' }]),
        )
      }),
      http.post(`${API}/workspaces`, async ({ request }) => {
        sent = (await request.json()) as Record<string, unknown>
        return HttpResponse.json(workspace({ id: 'w-9' }), { status: 201 })
      }),
      http.get(`${API}/workspaces/w-9`, () => HttpResponse.json(workspace({ id: 'w-9' }))),
      http.get(`${API}/workspaces/w-9/coordinators`, () => HttpResponse.json([carl])),
    )
    const user = userEvent.setup()
    renderApp('/workspaces/new')
    await fillSettings(user)

    await user.click(screen.getByRole('button', { name: 'Create Project Workspace' }))
    expect(await screen.findByText('Choose the Coordinator who will own this Project Workspace.')).toBeInTheDocument()
    expect(sent).toBeNull()

    await user.type(screen.getByLabelText('Owner'), 'carl')
    await user.click(await screen.findByRole('button', { name: /Carl Dela Cruz/ }))
    expect(searched).toBe('carl')
    expect(screen.queryByText('Choose the Coordinator who will own this Project Workspace.')).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Create Project Workspace' }))
    await waitFor(() => expect(sent).toMatchObject({ ownerId: 'u-coordinator', subjectCode: 'CS101' }))
  })

  it('waits for three characters before searching', async () => {
    mockSession(admin)
    const user = userEvent.setup()
    renderApp('/workspaces/new')
    await user.type(await screen.findByLabelText('Owner'), 'ca')
    expect(await screen.findByText(/at least 3 characters/i)).toBeInTheDocument()
  })

  it('shows the API message when the Owner is not acceptable', async () => {
    mockSession(admin)
    server.use(
      http.get(`${API}/users`, () =>
        HttpResponse.json(page([{ id: 'u-x', role: 'COORDINATOR', status: 'ACTIVE', firstName: 'Xavier', lastName: 'Yu', email: 'x@example.edu' }])),
      ),
      http.post(`${API}/workspaces`, () => problem(400, 'COORDINATOR_NOT_ASSIGNABLE')),
    )
    const user = userEvent.setup()
    renderApp('/workspaces/new')
    await fillSettings(user)
    await user.type(screen.getByLabelText('Owner'), 'xav')
    await user.click(await screen.findByRole('button', { name: /Xavier Yu/ }))
    await user.click(screen.getByRole('button', { name: 'Create Project Workspace' }))
    expect(await screen.findByText(/must be an active Coordinator account/i)).toBeInTheDocument()
  })

  it('a Participant cannot open the page', async () => {
    mockSession(participant)
    server.use(
      http.get(`${API}/workspaces`, () => HttpResponse.json(page([]))),
      http.get(`${API}/invitations`, () => HttpResponse.json([])),
    )
    renderApp('/workspaces/new')
    expect(await screen.findByRole('heading', { name: /not found, or no access/i })).toBeInTheDocument()
  })
})

describe('Edit settings', () => {
  it('is prefilled, sends the version it saw and returns to the workspace', async () => {
    mockSession(coordinator)
    mockWorkspace()
    let sent: Record<string, unknown> | null = null
    server.use(
      http.patch(`${API}/workspaces/w-1`, async ({ request }) => {
        sent = (await request.json()) as Record<string, unknown>
        return HttpResponse.json(workspace({ version: 4, subjectName: 'Software Engineering II' }))
      }),
    )
    const user = userEvent.setup()
    renderApp('/workspaces/w-1/edit')
    const name = await screen.findByLabelText('Subject name')
    expect(name).toHaveValue('Software Engineering')
    expect(screen.getByLabelText('Largest team')).toHaveValue(4)
    await user.clear(name)
    await user.type(name, 'Software Engineering II')
    await user.click(screen.getByRole('button', { name: 'Save settings' }))

    await waitFor(() => expect(screen.getByTestId('location')).not.toHaveTextContent('/edit'))
    expect(screen.getByTestId('location')).toHaveTextContent('/workspaces/w-1')
    expect(sent).toMatchObject({ version: 3, subjectName: 'Software Engineering II', teamMinSize: 2, teamMaxSize: 4 })
  })

  it('offers to load the latest settings when someone else saved first', async () => {
    mockSession(coordinator)
    mockWorkspace()
    server.use(http.patch(`${API}/workspaces/w-1`, () => problem(409, 'CLASS_VERSION_CONFLICT')))
    const user = userEvent.setup()
    renderApp('/workspaces/w-1/edit')
    await user.click(await screen.findByRole('button', { name: 'Save settings' }))
    expect(await screen.findByRole('button', { name: 'Load the latest settings' })).toBeInTheDocument()

    mockWorkspace({ version: 4, subjectName: 'Changed elsewhere' })
    await user.click(screen.getByRole('button', { name: 'Load the latest settings' }))
    await waitFor(() => expect(screen.getByLabelText('Subject name')).toHaveValue('Changed elsewhere'))
  })

  it('puts an API field error next to its input', async () => {
    mockSession(coordinator)
    mockWorkspace()
    server.use(
      http.patch(`${API}/workspaces/w-1`, () =>
        problem(400, 'VALIDATION_FAILED', 'Invalid', { errors: [{ field: 'subjectCode', message: 'That code is already in use.' }] }),
      ),
    )
    const user = userEvent.setup()
    renderApp('/workspaces/w-1/edit')
    await user.click(await screen.findByRole('button', { name: 'Save settings' }))
    expect(await screen.findByText('That code is already in use.')).toBeInTheDocument()
  })

  it('is not offered to a Coordinator without the settings permission', async () => {
    mockSession(coordinator)
    mockWorkspace({ ownerId: 'someone-else', myCapabilities: ['MANAGE_ROSTER'] })
    renderApp('/workspaces/w-1/edit')
    expect(await screen.findByRole('heading', { name: /not found, or no access/i })).toBeInTheDocument()
  })

  it('an archived workspace cannot be edited', async () => {
    mockSession(coordinator)
    mockWorkspace({ status: 'ARCHIVED' })
    renderApp('/workspaces/w-1/edit')
    expect(await screen.findByText(/archived, so its settings can no longer be changed/i)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Save settings' })).not.toBeInTheDocument()
  })
})

describe('Archive and restore', () => {
  it('archives after a confirmation and shows the workspace as archived', async () => {
    mockSession(coordinator)
    mockWorkspace()
    let calls = 0
    server.use(
      http.post(`${API}/workspaces/w-1/archive`, () => {
        calls += 1
        mockWorkspace({ status: 'ARCHIVED' })
        return HttpResponse.json(workspace({ status: 'ARCHIVED' }))
      }),
    )
    const user = userEvent.setup()
    renderApp('/workspaces/w-1')
    await user.click(await screen.findByRole('button', { name: 'Archive' }))
    expect(calls).toBe(0)
    const confirm = screen.getByRole('group', { name: 'Confirm archive' })
    await user.click(within(confirm).getByRole('button', { name: 'Archive this Project Workspace' }))

    expect(await screen.findByText('Archived')).toBeInTheDocument()
    expect(calls).toBe(1)
    expect(screen.queryByRole('button', { name: 'Archive' })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Edit settings' })).not.toBeInTheDocument()
  })

  it('cancelling the confirmation changes nothing', async () => {
    mockSession(coordinator)
    mockWorkspace()
    let calls = 0
    server.use(http.post(`${API}/workspaces/w-1/archive`, () => ((calls += 1), HttpResponse.json(workspace()))))
    const user = userEvent.setup()
    renderApp('/workspaces/w-1')
    await user.click(await screen.findByRole('button', { name: 'Archive' }))
    await user.click(within(screen.getByRole('group', { name: 'Confirm archive' })).getByRole('button', { name: 'Cancel' }))
    expect(screen.queryByRole('group', { name: 'Confirm archive' })).not.toBeInTheDocument()
    expect(calls).toBe(0)
  })

  it('shows the API message when archiving fails', async () => {
    mockSession(coordinator)
    mockWorkspace()
    server.use(http.post(`${API}/workspaces/w-1/archive`, () => problem(403, 'ACCESS_DENIED')))
    const user = userEvent.setup()
    renderApp('/workspaces/w-1')
    await user.click(await screen.findByRole('button', { name: 'Archive' }))
    await user.click(screen.getByRole('button', { name: 'Archive this Project Workspace' }))
    expect(await screen.findByText('You do not have permission to do that.')).toBeInTheDocument()
  })

  it('only an Administrator can restore an archived workspace', async () => {
    mockSession(coordinator)
    mockWorkspace({ status: 'ARCHIVED' })
    const { unmount } = renderApp('/workspaces/w-1')
    expect(await screen.findByText('Archived')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Restore from archive' })).not.toBeInTheDocument()
    unmount()

    mockSession(admin)
    mockWorkspace({ status: 'ARCHIVED', myCapabilities: ALL.slice(0, 4) })
    let restored = 0
    server.use(http.post(`${API}/workspaces/w-1/unarchive`, () => ((restored += 1), HttpResponse.json(workspace()))))
    const user = userEvent.setup()
    renderApp('/workspaces/w-1')
    await user.click(await screen.findByRole('button', { name: 'Restore from archive' }))
    await waitFor(() => expect(restored).toBe(1))
  })

  it('a Coordinator without the settings permission sees neither Edit nor Archive', async () => {
    mockSession(coordinator)
    mockWorkspace({ ownerId: 'someone-else', myCapabilities: ['MANAGE_ROSTER'] })
    renderApp('/workspaces/w-1')
    expect(await screen.findByRole('heading', { name: 'CS101 Software Engineering' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Archive' })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Edit settings' })).not.toBeInTheDocument()
  })
})

describe('Coordinators, permissions and ownership', () => {
  it('lists Coordinators with the Owner badge and each one\'s permissions', async () => {
    mockSession(coordinator)
    mockWorkspace()
    renderApp('/workspaces/w-1')
    const list = await screen.findByRole('list', { name: 'Coordinators' })
    const owner = within(list).getByText('Carl Dela Cruz').closest('li')!
    expect(within(owner).getByText('Owner')).toBeInTheDocument()
    expect(within(owner).getByText('Every permission')).toBeInTheDocument()
    const other = within(list).getByText('Dana Cruz').closest('li')!
    const perms = within(other).getByRole('list', { name: 'Permissions of Dana Cruz' })
    expect(within(perms).getByText('Participants')).toBeInTheDocument()
    expect(within(perms).getByText('Projects')).toBeInTheDocument()
    expect(within(perms).queryByText('Approve')).not.toBeInTheDocument()
  })

  it('changes a Coordinator\'s permissions', async () => {
    mockSession(coordinator)
    mockWorkspace()
    let sent: Record<string, unknown> | null = null
    server.use(
      http.patch(`${API}/workspaces/w-1/coordinators/u-dana`, async ({ request }) => {
        sent = (await request.json()) as Record<string, unknown>
        return HttpResponse.json({ ...dana, capabilities: ['MANAGE_ROSTER', 'REVIEW'] })
      }),
    )
    const user = userEvent.setup()
    renderApp('/workspaces/w-1')
    await user.click(await screen.findByRole('button', { name: 'Change permissions of Dana Cruz' }))
    const checklist = within(screen.getByRole('group', { name: 'Permissions of Dana Cruz' }))
    await user.click(checklist.getByLabelText('Projects'))
    await user.click(checklist.getByLabelText('Review'))
    await user.click(screen.getByRole('button', { name: 'Save permissions' }))
    await waitFor(() => expect(sent).toEqual({ capabilities: ['MANAGE_ROSTER', 'REVIEW'] }))
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Save permissions' })).not.toBeInTheDocument())
  })

  it('cannot hand out a permission the person does not hold themselves', async () => {
    mockSession(coordinator)
    // A delegated Coordinator: may manage Coordinators and holds Participants + Coordinators only.
    mockWorkspace({ ownerId: 'someone-else', myCapabilities: ['MANAGE_ROSTER', 'MANAGE_COORDINATORS'] })
    const user = userEvent.setup()
    renderApp('/workspaces/w-1')
    await user.click(await screen.findByRole('button', { name: 'Change permissions of Dana Cruz' }))
    const checklist = within(screen.getByRole('group', { name: 'Permissions of Dana Cruz' }))
    expect(checklist.getByLabelText('Participants')).toBeEnabled()
    // Dana holds Projects already, so it can still be taken away; Review is held by nobody here, so it cannot be granted.
    expect(checklist.getByLabelText('Projects')).toBeEnabled()
    expect(checklist.getByLabelText('Review')).toBeDisabled()
    expect(checklist.getAllByText(/you cannot grant it/i).length).toBeGreaterThan(0)
  })

  it('removes a Coordinator only after confirming', async () => {
    mockSession(coordinator)
    mockWorkspace()
    let removed = 0
    server.use(http.delete(`${API}/workspaces/w-1/coordinators/u-dana`, () => ((removed += 1), new HttpResponse(null, { status: 204 }))))
    const user = userEvent.setup()
    renderApp('/workspaces/w-1')
    await user.click(await screen.findByRole('button', { name: 'Remove Dana Cruz' }))
    expect(removed).toBe(0)
    await user.click(within(screen.getByRole('group', { name: 'Confirm' })).getByRole('button', { name: 'Remove Dana Cruz' }))
    await waitFor(() => expect(removed).toBe(1))
  })

  it('never offers to change or remove the Owner', async () => {
    mockSession(coordinator)
    mockWorkspace()
    renderApp('/workspaces/w-1')
    await screen.findByRole('list', { name: 'Coordinators' })
    expect(screen.queryByRole('button', { name: /Change permissions of Carl/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Remove Carl/ })).not.toBeInTheDocument()
  })

  it('adds a Coordinator found through the candidate search, with the default permissions', async () => {
    mockSession(coordinator)
    mockWorkspace()
    let query = ''
    let sent: Record<string, unknown> | null = null
    server.use(
      http.get(`${API}/workspaces/w-1/coordinator-candidates`, ({ request }) => {
        query = new URL(request.url).searchParams.get('q') ?? ''
        return HttpResponse.json([{ userId: 'u-lopez', firstName: 'Maria', lastName: 'Lopez', email: 'maria.lopez@example.edu' }])
      }),
      http.post(`${API}/workspaces/w-1/coordinators`, async ({ request }) => {
        sent = (await request.json()) as Record<string, unknown>
        return HttpResponse.json({ ...dana, userId: 'u-lopez', firstName: 'Maria', lastName: 'Lopez' }, { status: 201 })
      }),
    )
    const user = userEvent.setup()
    renderApp('/workspaces/w-1')
    await user.click(await screen.findByRole('button', { name: 'Add a Coordinator' }))
    const form = screen.getByRole('group', { name: 'Add a Coordinator' })
    expect(within(form).getByRole('button', { name: 'Add Coordinator' })).toBeDisabled()
    await user.type(within(form).getByLabelText('Find a Coordinator'), 'lopez')
    await user.click(await within(form).findByRole('button', { name: /Maria Lopez/ }))
    expect(query).toBe('lopez')
    await user.click(within(form).getByRole('button', { name: 'Add Coordinator' }))
    await waitFor(() => expect(sent).toEqual({ userId: 'u-lopez', capabilities: ['MANAGE_ROSTER', 'MANAGE_PROJECTS'] }))
  })

  it('shows the API message when the person is already assigned', async () => {
    mockSession(coordinator)
    mockWorkspace()
    server.use(
      http.get(`${API}/workspaces/w-1/coordinator-candidates`, () =>
        HttpResponse.json([{ userId: 'u-lopez', firstName: 'Maria', lastName: 'Lopez', email: 'maria.lopez@example.edu' }]),
      ),
      http.post(`${API}/workspaces/w-1/coordinators`, () => problem(409, 'COORDINATOR_ALREADY_ASSIGNED')),
    )
    const user = userEvent.setup()
    renderApp('/workspaces/w-1')
    await user.click(await screen.findByRole('button', { name: 'Add a Coordinator' }))
    const form = screen.getByRole('group', { name: 'Add a Coordinator' })
    await user.type(within(form).getByLabelText('Find a Coordinator'), 'lopez')
    await user.click(await within(form).findByRole('button', { name: /Maria Lopez/ }))
    await user.click(within(form).getByRole('button', { name: 'Add Coordinator' }))
    expect(await within(form).findByText('That Coordinator is already assigned to this Project Workspace.')).toBeInTheDocument()
  })

  it('transfers ownership to an assigned Coordinator', async () => {
    mockSession(coordinator)
    mockWorkspace()
    let sent: Record<string, unknown> | null = null
    server.use(
      http.put(`${API}/workspaces/w-1/owner`, async ({ request }) => {
        sent = (await request.json()) as Record<string, unknown>
        return new HttpResponse(null, { status: 204 })
      }),
    )
    const user = userEvent.setup()
    renderApp('/workspaces/w-1')
    await user.click(await screen.findByRole('button', { name: 'Transfer ownership' }))
    const box = screen.getByRole('group', { name: 'Transfer ownership' })
    expect(within(box).getByRole('button', { name: 'Transfer ownership' })).toBeDisabled()
    await user.selectOptions(within(box).getByLabelText('New Owner'), 'u-dana')
    await user.click(within(box).getByRole('button', { name: 'Make Dana Cruz the Owner' }))
    await waitFor(() => expect(sent).toEqual({ userId: 'u-dana' }))
  })

  it('says to add another Coordinator first when nobody else is assigned', async () => {
    mockSession(coordinator)
    mockWorkspace({}, [carl])
    const user = userEvent.setup()
    renderApp('/workspaces/w-1')
    await user.click(await screen.findByRole('button', { name: 'Transfer ownership' }))
    expect(screen.getByText(/add another Coordinator first/i)).toBeInTheDocument()
  })

  it('only the Owner or an Administrator can transfer ownership', async () => {
    mockSession(coordinator)
    mockWorkspace({ ownerId: 'someone-else', myCapabilities: ['MANAGE_COORDINATORS'] })
    renderApp('/workspaces/w-1')
    await screen.findByRole('button', { name: 'Add a Coordinator' })
    expect(screen.queryByRole('button', { name: 'Transfer ownership' })).not.toBeInTheDocument()
  })

  it('an Administrator can transfer ownership, also while the workspace is archived', async () => {
    mockSession(admin)
    mockWorkspace({ status: 'ARCHIVED', myCapabilities: ALL.slice(0, 4) })
    renderApp('/workspaces/w-1')
    expect(await screen.findByRole('button', { name: 'Transfer ownership' })).toBeInTheDocument()
    // People cannot be added or changed while archived.
    expect(screen.queryByRole('button', { name: 'Add a Coordinator' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Change permissions of Dana Cruz' })).not.toBeInTheDocument()
    expect(screen.getByText(/archived, so Coordinators cannot be added or changed/i)).toBeInTheDocument()
  })

  it('a Coordinator without the Coordinators permission can look but not change', async () => {
    mockSession(coordinator)
    mockWorkspace({ ownerId: 'someone-else', myCapabilities: ['MANAGE_ROSTER'] })
    renderApp('/workspaces/w-1')
    await screen.findByRole('list', { name: 'Coordinators' })
    expect(screen.queryByRole('button', { name: 'Add a Coordinator' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Change permissions/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^Remove/ })).not.toBeInTheDocument()
  })

  it('a Participant sees only the names and never asks for the Coordinator list', async () => {
    mockSession(participant)
    let asked = 0
    server.use(
      http.get(`${API}/workspaces/w-1`, () =>
        HttpResponse.json(workspace({ myCapabilities: [], ownerId: 'u-coordinator', coordinators: [{ id: 'u-coordinator', firstName: 'Carl', lastName: 'Dela Cruz', owner: true }] })),
      ),
      http.get(`${API}/workspaces/w-1/coordinators`, () => ((asked += 1), HttpResponse.json([]))),
      http.get(`${API}/workspaces/w-1/projects`, () => HttpResponse.json(page([]))),
    )
    renderApp('/workspaces/w-1')
    expect(await screen.findByText('Carl Dela Cruz')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Transfer ownership' })).not.toBeInTheDocument()
    expect(asked).toBe(0)
  })
})

describe('Administrator workspaces page', () => {
  it('lists workspaces with their Owner and links to create one', async () => {
    mockSession(admin)
    server.use(
      http.get(`${API}/workspaces`, () => HttpResponse.json(page([workspace(), workspace({ id: 'w-2', subjectCode: 'IT200', status: 'ARCHIVED' })]))),
      http.get(`${API}/ops/health`, () => HttpResponse.json({})),
    )
    renderApp('/admin/workspaces')
    expect(await screen.findByRole('link', { name: 'CS101 Software Engineering' })).toBeInTheDocument()
    expect(screen.getAllByText(/Owner: Carl Dela Cruz/)).toHaveLength(2)
    expect(screen.getByText('Archived')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'New Project Workspace' })).toHaveAttribute('href', '/workspaces/new')
  })
})
