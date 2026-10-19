import { screen, within } from '@testing-library/react'
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
  enrolledCount: 12,
  version: 1,
  ownerId: 'u-coordinator',
  myCapabilities: ['MANAGE_ROSTER', 'MANAGE_PROJECTS'],
  coordinators: [{ id: 'u-coordinator', firstName: 'Carl', lastName: 'Dela Cruz', owner: true }],
  ...over,
})
const page = (items: unknown[]) => HttpResponse.json({ items, page: 0, size: 100, total: items.length })

const project = (over: Record<string, unknown> = {}) => ({
  id: 'p-1',
  workspaceId: 'w-1',
  kind: 'TEAM',
  title: 'Library app',
  description: null,
  teamName: 'Team Alpha',
  status: 'ACTIVE',
  version: 1,
  createdAt: '2026-10-01T00:00:00Z',
  myRole: 'REPRESENTATIVE',
  members: [{ userId: 'u-participant', firstName: 'Ana', lastName: 'Reyes', studentNumber: null, role: 'REPRESENTATIVE', joinedAt: null }],
  teamPolicy: { formation: 'PARTICIPANT_FORMED', minSize: 2, maxSize: 4, participantsMayChangeTeams: false },
  meetsMinSize: false,
  pendingInvitations: [],
  ...over,
})

describe('Participant dashboard', () => {
  beforeEach(() => mockSession(participant))

  it('shows a loading state, then my workspaces and projects', async () => {
    server.use(
      http.get(`${API}/workspaces`, () => page([workspace()])),
      http.get(`${API}/workspaces/w-1/projects`, () => HttpResponse.json([project()])),
    )
    renderApp('/app')
    expect(await screen.findByText(/welcome, ana/i)).toBeInTheDocument()
    expect(await screen.findByRole('link', { name: 'CS101 Software Engineering' })).toHaveAttribute('href', '/workspaces/w-1')
    const projectLink = await screen.findByRole('link', { name: 'Library app' })
    expect(projectLink).toHaveAttribute('href', '/projects/p-1')
    expect(screen.getByText('Representative')).toBeInTheDocument()
    // A Participant is never shown coordinator-only information on a workspace card.
    expect(screen.queryByText('Owner')).not.toBeInTheDocument()
  })

  it('shows empty states', async () => {
    server.use(http.get(`${API}/workspaces`, () => page([])))
    renderApp('/app')
    expect(await screen.findByText(/not in a project workspace yet/i)).toBeInTheDocument()
    expect(screen.getByText('No projects yet')).toBeInTheDocument()
  })

  it('shows an error with retry for the workspace list, and recovers', async () => {
    let fail = true
    server.use(
      http.get(`${API}/workspaces`, () => (fail ? problem(500, 'INTERNAL_ERROR') : page([workspace()]))),
      http.get(`${API}/workspaces/w-1/projects`, () => HttpResponse.json([])),
    )
    const user = userEvent.setup()
    renderApp('/app')
    expect(await screen.findByText(/could not be loaded/i)).toBeInTheDocument()
    fail = false
    await user.click(screen.getByRole('button', { name: 'Try again' }))
    expect(await screen.findByRole('link', { name: 'CS101 Software Engineering' })).toBeInTheDocument()
  })

  it('shows an error with retry when a project list fails', async () => {
    server.use(
      http.get(`${API}/workspaces`, () => page([workspace()])),
      http.get(`${API}/workspaces/w-1/projects`, () => problem(500, 'INTERNAL_ERROR')),
    )
    renderApp('/app')
    expect(await screen.findByText('Some projects could not be loaded')).toBeInTheDocument()
  })
})

describe('Coordinator dashboard', () => {
  beforeEach(() => mockSession(coordinator))

  it('shows the Owner badge, capability chips and project counts from the API', async () => {
    server.use(
      http.get(`${API}/workspaces`, () =>
        page([workspace(), workspace({ id: 'w-2', subjectCode: 'IT200', subjectName: 'Databases', ownerId: 'someone-else', myCapabilities: ['MANAGE_ROSTER'] })]),
      ),
      http.get(`${API}/workspaces/w-1/projects`, () => HttpResponse.json([project(), project({ id: 'p-2', status: 'ARCHIVED' })])),
      http.get(`${API}/workspaces/w-2/projects`, () => HttpResponse.json([])),
    )
    renderApp('/coordinator')
    const first = (await screen.findByRole('link', { name: 'CS101 Software Engineering' })).closest('li')!
    expect(within(first).getByText('Owner')).toBeInTheDocument()
    expect(await within(first).findByText(/1 active project/)).toBeInTheDocument()
    expect(within(first).getByText('Participants')).toBeInTheDocument()
    expect(within(first).getByText('Projects')).toBeInTheDocument()

    const second = screen.getByRole('link', { name: 'IT200 Databases' }).closest('li')!
    expect(within(second).queryByText('Owner')).not.toBeInTheDocument()
    expect(within(second).queryByText('Projects')).not.toBeInTheDocument()
    expect(await within(second).findByText(/0 active projects/)).toBeInTheDocument()
  })

  it('shows an empty state', async () => {
    server.use(http.get(`${API}/workspaces`, () => page([])))
    renderApp('/coordinator')
    expect(await screen.findByText(/do not coordinate a project workspace yet/i)).toBeInTheDocument()
  })

  it('shows an error with retry', async () => {
    server.use(http.get(`${API}/workspaces`, () => problem(503, 'SERVICE_UNAVAILABLE')))
    renderApp('/coordinator')
    expect(await screen.findByText(/temporarily unavailable/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument()
  })
})

describe('Administrator dashboard', () => {
  beforeEach(() => mockSession(admin))

  const health = {
    status: 'DEGRADED',
    checkedAt: '2026-10-19T10:00:00Z',
    database: { status: 'UP', responseMillis: 3 },
    broker: { status: 'DOWN', reason: 'unreachable' },
    workers: [{ name: 'orchestrator', status: 'UP', heartbeatAgeSeconds: 2, heartbeatFresh: true }],
    outbox: { pending: 4, failed: 1, oldestPendingSeconds: 10 },
    testJobs: { queued: 0, running: 0, succeeded: 5, failed: 0, overdue: 0 },
    queueDepths: { 'die.jobs': 7 },
  }

  it('shows platform health as words and counts', async () => {
    server.use(http.get(`${API}/ops/health`, () => HttpResponse.json(health)))
    renderApp('/admin')
    expect(await screen.findByText('Degraded')).toBeInTheDocument()
    expect(screen.getByText('Database')).toBeInTheDocument()
    expect(screen.getByText('Down')).toBeInTheDocument()
    expect(screen.getByText('Worker: orchestrator')).toBeInTheDocument()
    expect(screen.getByText('die.jobs')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Accounts' })).toHaveAttribute('href', '/admin/accounts')
  })

  it('shows an error with retry', async () => {
    server.use(http.get(`${API}/ops/health`, () => problem(403, 'ACCESS_DENIED')))
    renderApp('/admin')
    expect(await screen.findByText(/platform health could not be loaded/i)).toBeInTheDocument()
  })
})

describe('Read-only detail pages', () => {
  it('shows a workspace as the API returned it and hides project work from Administrators', async () => {
    mockSession(admin)
    let projectCalls = 0
    server.use(
      http.get(`${API}/workspaces/w-1`, () => HttpResponse.json(workspace())),
      http.get(`${API}/workspaces/w-1/projects`, () => {
        projectCalls += 1
        return HttpResponse.json([])
      }),
    )
    renderApp('/workspaces/w-1')
    expect(await screen.findByRole('heading', { name: 'CS101 Software Engineering' })).toBeInTheDocument()
    expect(screen.getByText('Carl Dela Cruz')).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Projects' })).not.toBeInTheDocument()
    expect(projectCalls).toBe(0)
  })

  it('treats a 404 as "not found, or no access"', async () => {
    mockSession(participant)
    server.use(http.get(`${API}/projects/p-9`, () => problem(404, 'PROJECT_NOT_FOUND')))
    renderApp('/projects/p-9')
    expect(await screen.findByRole('heading', { name: /not found, or no access/i })).toBeInTheDocument()
  })

  it('shows project members without Student IDs unless the API sent them', async () => {
    mockSession(participant)
    server.use(http.get(`${API}/projects/p-1`, () => HttpResponse.json(project())))
    renderApp('/projects/p-1')
    expect(await screen.findByRole('heading', { name: 'Library app' })).toBeInTheDocument()
    expect(screen.getByText('Ana Reyes')).toBeInTheDocument()
    expect(screen.queryByText(/student id/i)).not.toBeInTheDocument()
    expect(screen.getByText(/smaller than the minimum team size/i)).toBeInTheDocument()
  })
})
