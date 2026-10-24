import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { API, http, HttpResponse, server } from '@/test/server'
import { coordinator, mockSession, problem, renderApp } from '@/test/utils'

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
  enrolledCount: 0,
  version: 1,
  ownerId: 'u-coordinator',
  myCapabilities: ['MANAGE_ROSTER'],
  coordinators: [],
  ...over,
})

const summary = (over: Record<string, unknown> = {}) => ({
  total: 4,
  create: 2,
  match: 0,
  skipDuplicate: 0,
  conflict: 1,
  invalid: 1,
  unresolvedConflicts: 1,
  ...over,
})

const importView = (over: Record<string, unknown> = {}, sum = summary()) => ({
  id: 'i-1',
  workspaceId: 'w-1',
  fileName: 'class.csv',
  status: 'PREVIEW',
  createdAt: null,
  committedAt: null,
  summary: sum,
  ...over,
})

const row = (over: Record<string, unknown>): Record<string, unknown> => ({
  rowNumber: 1,
  studentNumber: '2024-0001',
  lastName: 'Reyes',
  firstName: 'Ana',
  middleName: null,
  email: null,
  program: null,
  section: null,
  action: 'CREATE',
  conflictKind: null,
  resolution: null,
  message: null,
  allowedResolutions: [],
  ...over,
})

const ROWS = [
  row({ rowNumber: 1 }),
  row({ rowNumber: 2, studentNumber: '2024-0002', lastName: 'Santos', firstName: 'Ben' }),
  row({
    rowNumber: 3,
    studentNumber: '2024-0003',
    lastName: 'Lim',
    firstName: 'Cleo',
    action: 'CONFLICT',
    conflictKind: 'ID_NAME_MISMATCH',
    message: 'The name does not match the account with this Student ID.',
    allowedResolutions: ['USE_EXISTING', 'SKIP'],
  }),
  row({ rowNumber: 4, studentNumber: '', lastName: '', firstName: '', action: 'INVALID', message: 'Student ID is missing.' }),
]

const detail = (rows: Array<Record<string, unknown>> = ROWS, sum = summary(), over: Record<string, unknown> = {}) => ({
  importInfo: importView({}, sum),
  rows,
  page: 0,
  size: 100,
  totalRows: rows.length,
  ...over,
})

function mockPage(wsOver: Record<string, unknown> = {}) {
  server.use(http.get(`${API}/workspaces/w-1`, () => HttpResponse.json(workspace(wsOver))))
}

/** Uploads a small CSV and waits for the review step. */
async function uploadAndReview(user: ReturnType<typeof userEvent.setup>, name = 'class.csv') {
  const input = await screen.findByLabelText('Roster file')
  await user.upload(input, new File(['Student ID,Last Name,First Name\n'], name, { type: 'text/csv' }))
  await user.click(screen.getByRole('button', { name: 'Preview the import' }))
}

describe('Roster import: upload', () => {
  it('rejects a file that is not CSV or XLSX before sending anything', async () => {
    mockSession(coordinator)
    mockPage()
    let uploads = 0
    server.use(http.post(`${API}/workspaces/w-1/roster-imports`, () => ((uploads += 1), HttpResponse.json(importView(), { status: 201 }))))
    const user = userEvent.setup({ applyAccept: false })
    renderApp('/workspaces/w-1/roster/import')
    await user.upload(await screen.findByLabelText('Roster file'), new File(['x'], 'notes.txt', { type: 'text/plain' }))
    expect(await screen.findByText('Use a .csv or .xlsx file.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Preview the import' })).toBeDisabled()
    expect(uploads).toBe(0)
  })

  it('shows the API message when the file cannot be used', async () => {
    mockSession(coordinator)
    mockPage()
    server.use(http.post(`${API}/workspaces/w-1/roster-imports`, () => problem(400, 'MISSING_COLUMNS')))
    const user = userEvent.setup()
    renderApp('/workspaces/w-1/roster/import')
    await uploadAndReview(user)
    expect(await screen.findByText(/required columns are missing/i)).toBeInTheDocument()
  })

  it('is not available to a Coordinator without the Participants permission', async () => {
    mockSession(coordinator)
    mockPage({ myCapabilities: ['MANAGE_PROJECTS'] })
    renderApp('/workspaces/w-1/roster/import')
    expect(await screen.findByRole('heading', { name: /not found, or no access/i })).toBeInTheDocument()
  })

  it('is read-only once the workspace is archived', async () => {
    mockSession(coordinator)
    mockPage({ status: 'ARCHIVED' })
    renderApp('/workspaces/w-1/roster/import')
    expect(await screen.findByText(/archived, so its roster can no longer be changed/i)).toBeInTheDocument()
    expect(screen.queryByLabelText('Roster file')).not.toBeInTheDocument()
  })
})

describe('Roster import: review and decide', () => {
  function mockPreview() {
    mockPage()
    const requests: URL[] = []
    server.use(
      http.post(`${API}/workspaces/w-1/roster-imports`, () => HttpResponse.json(importView(), { status: 201 })),
      http.get(`${API}/roster-imports/i-1`, ({ request }) => {
        requests.push(new URL(request.url))
        return HttpResponse.json(detail())
      }),
    )
    return requests
  }

  it('shows the counts and what will happen to each row', async () => {
    mockSession(coordinator)
    mockPreview()
    const user = userEvent.setup()
    renderApp('/workspaces/w-1/roster/import')
    await uploadAndReview(user)

    expect(await screen.findByText('Review class.csv', { exact: false })).toBeInTheDocument()
    const counts = within(await screen.findByLabelText('Summary'))
    expect(counts.getByText('Rows').parentElement).toHaveTextContent('4')
    expect(counts.getByText('New accounts').parentElement).toHaveTextContent('2')
    expect(counts.getByText('Need a decision').parentElement).toHaveTextContent('1')
    const table = await screen.findByRole('table', { name: 'Rows of the roster file' })
    const conflictRow = within(table).getByText('Cleo Lim').closest('tr')!
    expect(within(conflictRow).getByText('Needs a decision')).toBeInTheDocument()
    expect(within(conflictRow).getByText('The name does not match the account with this Student ID.')).toBeInTheDocument()
    const invalidRow = within(table).getByText('Student ID is missing.').closest('tr')!
    expect(within(invalidRow).getByText('Not valid')).toBeInTheDocument()
  })

  it('will not import while a conflict is undecided', async () => {
    mockSession(coordinator)
    mockPreview()
    const user = userEvent.setup()
    renderApp('/workspaces/w-1/roster/import')
    await uploadAndReview(user)
    expect(await screen.findByRole('button', { name: 'Import Participants' })).toBeDisabled()
    expect(screen.getByText(/Decide the 1 row\(s\) that need a decision before importing/i)).toBeInTheDocument()
  })

  it('saves decisions, then allows the import', async () => {
    mockSession(coordinator)
    mockPreview()
    let sent: Record<string, unknown> | null = null
    server.use(
      http.put(`${API}/roster-imports/i-1/resolutions`, async ({ request }) => {
        sent = (await request.json()) as Record<string, unknown>
        // From now on the API reports the conflict as decided.
        server.use(
          http.get(`${API}/roster-imports/i-1`, () =>
            HttpResponse.json(
              detail(
                ROWS.map((r) => (r.rowNumber === 3 ? { ...r, resolution: 'SKIP' } : r)),
                summary({ unresolvedConflicts: 0 }),
              ),
            ),
          ),
        )
        return HttpResponse.json(importView({}, summary({ unresolvedConflicts: 0 })))
      }),
    )
    const user = userEvent.setup()
    renderApp('/workspaces/w-1/roster/import')
    await uploadAndReview(user)

    await user.selectOptions(await screen.findByLabelText('Decision for row 3'), 'SKIP')
    expect(screen.getByText('Save your decisions before importing.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Import Participants' })).toBeDisabled()

    await user.click(screen.getByRole('button', { name: 'Save decisions (1)' }))
    await waitFor(() => expect(sent).toEqual({ resolutions: [{ rowNumber: 3, resolution: 'SKIP' }] }))
    await waitFor(() => expect(screen.getByRole('button', { name: 'Import Participants' })).toBeEnabled())
  })

  it('filters the rows through the API', async () => {
    mockSession(coordinator)
    const requests = mockPreview()
    const user = userEvent.setup()
    renderApp('/workspaces/w-1/roster/import')
    await uploadAndReview(user)
    await user.selectOptions(await screen.findByLabelText('Show'), 'CONFLICT')
    await waitFor(() => expect(requests.at(-1)?.searchParams.get('action')).toBe('CONFLICT'))
  })

  it('cancels the preview after confirming and returns to the upload step', async () => {
    mockSession(coordinator)
    mockPreview()
    let cancelled = 0
    server.use(http.delete(`${API}/roster-imports/i-1`, () => ((cancelled += 1), new HttpResponse(null, { status: 204 }))))
    const user = userEvent.setup()
    renderApp('/workspaces/w-1/roster/import')
    await uploadAndReview(user)
    await user.click(await screen.findByRole('button', { name: 'Cancel the import' }))
    expect(cancelled).toBe(0)
    await user.click(within(screen.getByRole('group', { name: 'Confirm cancel' })).getByRole('button', { name: 'Yes, cancel the import' }))
    expect(await screen.findByLabelText('Roster file')).toBeInTheDocument()
    expect(cancelled).toBe(1)
  })
})

describe('Roster import: result', () => {
  function mockReadyToCommit() {
    mockPage()
    server.use(
      http.post(`${API}/workspaces/w-1/roster-imports`, () =>
        HttpResponse.json(importView({}, summary({ unresolvedConflicts: 0, conflict: 0 })), { status: 201 }),
      ),
      http.get(`${API}/roster-imports/i-1`, () => HttpResponse.json(detail(ROWS.slice(0, 2), summary({ unresolvedConflicts: 0, conflict: 0, invalid: 0, total: 2 })))),
    )
  }

  const commitResult = (over: Record<string, unknown> = {}) => ({
    importId: 'i-1',
    alreadyCommitted: false,
    created: 2,
    enrolledExisting: 0,
    skippedDuplicates: 0,
    skippedByResolution: 0,
    invalid: 0,
    credentials: [
      { rowNumber: 1, participantId: 'p-1', studentNumber: '2024-0001', lastName: 'Reyes', firstName: 'Ana', temporaryCredential: 'Aaa-111-secret', expiresAt: null },
      { rowNumber: 2, participantId: 'p-2', studentNumber: '2024-0002', lastName: 'Santos', firstName: 'Ben', temporaryCredential: 'Bbb-222-secret', expiresAt: null },
    ],
    ...over,
  })

  it('imports and shows each new credential once, with a CSV download', async () => {
    mockSession(coordinator)
    mockReadyToCommit()
    server.use(http.post(`${API}/roster-imports/i-1/commit`, () => HttpResponse.json(commitResult())))
    const downloads: string[] = []
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
      downloads.push(this.download)
    })
    const originalCreate = URL.createObjectURL
    const originalRevoke = URL.revokeObjectURL
    URL.createObjectURL = vi.fn(() => 'blob:test')
    URL.revokeObjectURL = vi.fn()
    try {
      const user = userEvent.setup()
      renderApp('/workspaces/w-1/roster/import')
      await uploadAndReview(user)
      await user.click(await screen.findByRole('button', { name: 'Import Participants' }))

      expect(await screen.findByText(/Import finished: 2 new account\(s\)/)).toBeInTheDocument()
      const table = screen.getByRole('table', { name: 'Temporary credentials of the new accounts' })
      expect(within(table).getByText('Aaa-111-secret')).toBeInTheDocument()
      expect(within(table).getByText('Bbb-222-secret')).toBeInTheDocument()
      expect(screen.getByText(/Shown only once/i)).toBeInTheDocument()

      await user.click(screen.getByRole('button', { name: 'Download as CSV' }))
      expect(downloads).toEqual(['temporary-credentials.csv'])
      expect(screen.getByText(/Downloaded\. Keep the file somewhere safe/)).toBeInTheDocument()
    } finally {
      click.mockRestore()
      URL.createObjectURL = originalCreate
      URL.revokeObjectURL = originalRevoke
    }
  })

  it('shows no credentials when the import had already been applied', async () => {
    mockSession(coordinator)
    mockReadyToCommit()
    server.use(http.post(`${API}/roster-imports/i-1/commit`, () => HttpResponse.json(commitResult({ alreadyCommitted: true, credentials: [] }))))
    const user = userEvent.setup()
    renderApp('/workspaces/w-1/roster/import')
    await uploadAndReview(user)
    await user.click(await screen.findByRole('button', { name: 'Import Participants' }))
    expect(await screen.findByText(/had already been applied/i)).toBeInTheDocument()
    expect(screen.queryByRole('table', { name: /Temporary credentials/ })).not.toBeInTheDocument()
  })

  it('tells the person to upload again when the roster changed under the preview', async () => {
    mockSession(coordinator)
    mockReadyToCommit()
    server.use(http.post(`${API}/roster-imports/i-1/commit`, () => problem(409, 'IMPORT_STALE')))
    const user = userEvent.setup()
    renderApp('/workspaces/w-1/roster/import')
    await uploadAndReview(user)
    await user.click(await screen.findByRole('button', { name: 'Import Participants' }))
    expect(await screen.findByText(/roster changed since this preview was made/i)).toBeInTheDocument()
    // Still on the review step.
    expect(screen.getByRole('button', { name: 'Import Participants' })).toBeInTheDocument()
  })

  it('goes back to the roster from the result', async () => {
    mockSession(coordinator)
    mockReadyToCommit()
    server.use(
      http.post(`${API}/roster-imports/i-1/commit`, () => HttpResponse.json(commitResult({ credentials: [] }))),
      http.get(`${API}/workspaces/w-1/enrollments`, () => HttpResponse.json({ items: [], page: 0, size: 50, total: 0 })),
    )
    const user = userEvent.setup()
    renderApp('/workspaces/w-1/roster/import')
    await uploadAndReview(user)
    await user.click(await screen.findByRole('button', { name: 'Import Participants' }))
    await user.click(await screen.findByRole('button', { name: 'Go to the Participants' }))
    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent('/workspaces/w-1/roster'))
    expect(screen.getByTestId('location')).not.toHaveTextContent('/import')
  })
})
