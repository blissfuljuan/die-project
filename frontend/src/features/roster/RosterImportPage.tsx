import { useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import {
  friendlyMessage,
  isApiError,
  ROSTER_FILE_LIMIT_BYTES,
  ROSTER_ROW_LIMIT,
  type CommitResult,
  type ImportRow,
  type ImportView,
} from '@/api'
import { PageHeader } from '@/components/common/PageHeader'
import { Pagination } from '@/components/common/Pagination'
import { ErrorWithRetry, ListSkeleton } from '@/components/common/QueryState'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Label } from '@/components/ui/label'
import { Select } from '@/components/ui/select'
import { useSession } from '@/features/session/useSession'
import { useWorkspace } from '@/features/workspaces/queries'
import { workspaceTitle } from '@/features/workspaces/labels'
import { toCsv } from '@/lib/csv'
import { NotFoundBody } from '@/pages/ForbiddenPage'
import { canManageRoster } from './RosterPage'
import { useCancelImport, useCommitImport, useImportDetail, useResolveImport, useUploadRoster } from './queries'

const PAGE_SIZE = 100

const ACTION_LABEL: Record<string, string> = {
  CREATE: 'New account',
  MATCH: 'Existing account',
  SKIP_DUPLICATE: 'Already enrolled or repeated',
  CONFLICT: 'Needs a decision',
  INVALID: 'Not valid',
}

const RESOLUTION_LABEL: Record<string, string> = {
  USE_EXISTING: 'Use the existing account',
  SKIP: 'Skip this row',
  CREATE_NEW: 'Create a new account',
}

const FILTERS: Array<[string, string]> = [
  ['', 'All rows'],
  ['CONFLICT', 'Needs a decision'],
  ['CREATE', 'New accounts'],
  ['MATCH', 'Existing accounts'],
  ['SKIP_DUPLICATE', 'Already enrolled or repeated'],
  ['INVALID', 'Not valid'],
]

/** Upload a CSV/XLSX roster, review what would happen, decide the conflicts, then import. Nothing changes until the import. */
export function RosterImportPage() {
  const { workspaceId = '' } = useParams()
  const { user } = useSession()
  const workspace = useWorkspace(workspaceId)
  const [preview, setPreview] = useState<ImportView | null>(null)
  const [result, setResult] = useState<CommitResult | null>(null)
  if (!user) return null

  if (workspace.isPending && !workspace.error) return <ListSkeleton label="Loading the Project Workspace" rows={3} />
  if (workspace.error) {
    if (isApiError(workspace.error) && (workspace.error.status === 404 || workspace.error.status === 403)) {
      return <NotFoundBody title="Not found, or no access" />
    }
    return (
      <ErrorWithRetry
        title="This Project Workspace could not be loaded"
        error={workspace.error}
        onRetry={() => void workspace.refetch()}
        retrying={workspace.isFetching}
      />
    )
  }
  const w = workspace.data
  if (!canManageRoster(user.role, w.myCapabilities)) return <NotFoundBody title="Not found, or no access" />

  return (
    <>
      <p className="mb-2 text-sm">
        <Link to={`/workspaces/${w.id}/roster`} className="underline underline-offset-2">
          Back to the Participants
        </Link>
      </p>
      <PageHeader title="Import Participants" description={workspaceTitle(w)} />
      {w.status === 'ARCHIVED' ? (
        <Alert>
          <AlertDescription>This Project Workspace is archived, so its roster can no longer be changed.</AlertDescription>
        </Alert>
      ) : result ? (
        <ImportResult workspaceId={w.id} result={result} />
      ) : preview ? (
        <ImportReview
          workspaceId={w.id}
          importView={preview}
          onCancelled={() => setPreview(null)}
          onCommitted={setResult}
          onSummary={setPreview}
        />
      ) : (
        <UploadStep workspaceId={w.id} onPreview={setPreview} />
      )}
    </>
  )
}

function UploadStep({ workspaceId, onPreview }: { workspaceId: string; onPreview: (v: ImportView) => void }) {
  const upload = useUploadRoster(workspaceId)
  const [file, setFile] = useState<File | null>(null)
  const [fileError, setFileError] = useState<string | null>(null)

  const choose = (f: File | null) => {
    upload.reset()
    setFile(null)
    setFileError(null)
    if (!f) return
    if (!/\.(csv|xlsx)$/i.test(f.name)) return setFileError('Use a .csv or .xlsx file.')
    if (f.size > ROSTER_FILE_LIMIT_BYTES) return setFileError('That file is larger than 5 MB.')
    setFile(f)
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-base">1. Choose the roster file</CardTitle>
        <CardDescription>
          A .csv or .xlsx file with a header row: Student ID, Last Name, First Name, and optionally Middle Name, Email, Program and
          Section. Up to {ROSTER_ROW_LIMIT} Participants. Nothing is changed until you confirm in the next step.
        </CardDescription>
      </CardHeader>
      <CardContent className="flex max-w-xl flex-col gap-4">
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="roster-file">Roster file</Label>
          <input
            id="roster-file"
            type="file"
            accept=".csv,.xlsx"
            className="text-sm file:mr-3 file:rounded-md file:border file:border-input file:bg-card file:px-3 file:py-2 file:text-sm"
            onChange={(e) => choose(e.target.files?.[0] ?? null)}
          />
          {fileError && <p className="text-sm text-destructive">{fileError}</p>}
        </div>
        {upload.error && (
          <Alert variant="destructive">
            <AlertDescription>{friendlyMessage(upload.error)}</AlertDescription>
          </Alert>
        )}
        <div>
          <Button type="button" disabled={!file || upload.isPending} onClick={() => file && upload.mutate(file, { onSuccess: onPreview })}>
            {upload.isPending ? 'Reading the file...' : 'Preview the import'}
          </Button>
        </div>
      </CardContent>
    </Card>
  )
}

function ImportReview({
  workspaceId,
  importView,
  onCancelled,
  onCommitted,
  onSummary,
}: {
  workspaceId: string
  importView: ImportView
  onCancelled: () => void
  onCommitted: (r: CommitResult) => void
  onSummary: (v: ImportView) => void
}) {
  const importId = importView.id
  const [filter, setFilter] = useState('')
  const [page, setPage] = useState(0)
  const [choices, setChoices] = useState<Record<number, string>>({})
  const [confirmCancel, setConfirmCancel] = useState(false)
  const detail = useImportDetail(importId, filter, page, PAGE_SIZE)
  const resolve = useResolveImport(importId)
  const commit = useCommitImport(importId, workspaceId)
  const cancel = useCancelImport(importId)

  // The summary on screen is the latest one the API gave (after saving decisions it changes).
  const summary = detail.data?.importInfo.summary ?? importView.summary
  const pendingChoices = Object.entries(choices).map(([row, resolution]) => ({ rowNumber: Number(row), resolution }))
  const canCommit = summary.unresolvedConflicts === 0 && pendingChoices.length === 0 && summary.total > 0

  const save = () =>
    resolve.mutate(pendingChoices, {
      onSuccess: (v) => {
        setChoices({})
        onSummary(v)
      },
    })

  return (
    <div className="flex flex-col gap-6">
      <Card>
        <CardHeader>
          <CardTitle className="text-base">2. Review {importView.fileName}</CardTitle>
          <CardDescription>This is only a preview. Nobody is created or enrolled until you press Import.</CardDescription>
        </CardHeader>
        <CardContent>
          <dl aria-label="Summary" className="grid grid-cols-2 gap-3 text-sm sm:grid-cols-3 lg:grid-cols-6">
            <Stat label="Rows" value={summary.total} />
            <Stat label="New accounts" value={summary.create} />
            <Stat label="Existing accounts" value={summary.match} />
            <Stat label="Already enrolled" value={summary.skipDuplicate} />
            <Stat label="Not valid" value={summary.invalid} />
            <Stat label="Need a decision" value={summary.unresolvedConflicts} />
          </dl>
        </CardContent>
      </Card>

      <section aria-labelledby="import-rows" className="flex flex-col gap-3">
        <div className="flex flex-wrap items-end justify-between gap-3">
          <h2 id="import-rows" className="text-lg font-semibold">
            Rows
          </h2>
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="import-filter">Show</Label>
            <Select
              id="import-filter"
              value={filter}
              onChange={(e) => {
                setFilter(e.target.value)
                setPage(0)
              }}
            >
              {FILTERS.map(([value, label]) => (
                <option key={value} value={value}>
                  {label}
                </option>
              ))}
            </Select>
          </div>
        </div>

        {detail.isPending && !detail.error && <ListSkeleton label="Loading the rows" rows={4} />}
        {detail.error && !detail.data && (
          <ErrorWithRetry title="The rows could not be loaded" error={detail.error} onRetry={() => void detail.refetch()} retrying={detail.isFetching} />
        )}
        {detail.data && detail.data.rows.length === 0 && <p className="text-sm text-muted-foreground">No rows to show.</p>}
        {detail.data && detail.data.rows.length > 0 && (
          <>
            <div className="overflow-x-auto rounded-lg border">
              <table className="w-full text-left text-sm">
                <caption className="sr-only">Rows of the roster file</caption>
                <thead className="bg-muted/50 text-muted-foreground">
                  <tr>
                    <th scope="col" className="px-3 py-2 font-medium">Row</th>
                    <th scope="col" className="px-3 py-2 font-medium">Student ID</th>
                    <th scope="col" className="px-3 py-2 font-medium">Name</th>
                    <th scope="col" className="px-3 py-2 font-medium">What will happen</th>
                  </tr>
                </thead>
                <tbody>
                  {detail.data.rows.map((r) => (
                    <RowLine
                      key={r.rowNumber}
                      row={r}
                      choice={choices[r.rowNumber]}
                      disabled={resolve.isPending || commit.isPending}
                      onChoose={(value) =>
                        setChoices((c) => {
                          const next = { ...c }
                          // Choosing what is already saved is not a change.
                          if (value === '' || value === r.resolution) delete next[r.rowNumber]
                          else next[r.rowNumber] = value
                          return next
                        })
                      }
                    />
                  ))}
                </tbody>
              </table>
            </div>
            <Pagination page={detail.data.page} size={detail.data.size} total={detail.data.totalRows} onPage={setPage} busy={detail.isFetching} />
          </>
        )}
      </section>

      {resolve.error && (
        <Alert variant="destructive">
          <AlertDescription>{friendlyMessage(resolve.error)}</AlertDescription>
        </Alert>
      )}
      {commit.error && (
        <Alert variant="destructive">
          <AlertDescription>{friendlyMessage(commit.error)}</AlertDescription>
        </Alert>
      )}
      {cancel.error && (
        <Alert variant="destructive">
          <AlertDescription>{friendlyMessage(cancel.error)}</AlertDescription>
        </Alert>
      )}

      <div className="flex flex-col gap-3">
        {!canCommit && summary.total > 0 && (
          <p className="text-sm text-muted-foreground">
            {pendingChoices.length > 0
              ? 'Save your decisions before importing.'
              : `Decide the ${summary.unresolvedConflicts} row(s) that need a decision before importing.`}
          </p>
        )}
        <div className="flex flex-wrap gap-3">
          <Button type="button" variant="outline" disabled={pendingChoices.length === 0 || resolve.isPending} onClick={save}>
            {resolve.isPending ? 'Saving...' : `Save decisions (${pendingChoices.length})`}
          </Button>
          <Button type="button" disabled={!canCommit || commit.isPending} onClick={() => commit.mutate(undefined, { onSuccess: onCommitted })}>
            {commit.isPending ? 'Importing...' : 'Import Participants'}
          </Button>
          <Button type="button" variant="outline" disabled={commit.isPending} onClick={() => setConfirmCancel(true)}>
            Cancel the import
          </Button>
        </div>
        {confirmCancel && (
          <div role="group" aria-label="Confirm cancel" className="flex flex-col gap-3 rounded-lg border border-destructive/40 p-3 text-sm">
            <p>Throw this preview away? Nothing has been changed; you can upload the file again.</p>
            <div className="flex gap-2">
              <Button type="button" variant="destructive" size="sm" disabled={cancel.isPending} onClick={() => cancel.mutate(undefined, { onSuccess: onCancelled })}>
                {cancel.isPending ? 'Cancelling...' : 'Yes, cancel the import'}
              </Button>
              <Button type="button" variant="outline" size="sm" onClick={() => setConfirmCancel(false)}>
                Keep reviewing
              </Button>
            </div>
          </div>
        )}
      </div>
    </div>
  )
}

function Stat({ label, value }: { label: string; value: number }) {
  return (
    <div>
      <dt className="text-muted-foreground">{label}</dt>
      <dd className="text-xl font-semibold">{value}</dd>
    </div>
  )
}

function RowLine({
  row,
  choice,
  disabled,
  onChoose,
}: {
  row: ImportRow
  choice: string | undefined
  disabled: boolean
  onChoose: (value: string) => void
}) {
  const name = [row.firstName, row.lastName].filter(Boolean).join(' ')
  const selectId = `row-${row.rowNumber}-decision`
  const current = choice ?? row.resolution ?? ''
  return (
    <tr className="border-t align-top">
      <td className="px-3 py-2">{row.rowNumber}</td>
      <td className="px-3 py-2">{row.studentNumber ?? ''}</td>
      <td className="px-3 py-2">{name}</td>
      <td className="px-3 py-2">
        <div className="flex flex-col gap-2">
          <div>
            <Badge variant={row.action === 'INVALID' ? 'destructive' : row.action === 'CONFLICT' ? 'outline' : 'secondary'}>
              {ACTION_LABEL[row.action] ?? row.action}
            </Badge>
          </div>
          {row.message && <p className="text-muted-foreground">{row.message}</p>}
          {row.action === 'CONFLICT' && row.allowedResolutions.length > 0 && (
            <div className="flex flex-col gap-1">
              <Label htmlFor={selectId} className="sr-only">
                Decision for row {row.rowNumber}
              </Label>
              <Select id={selectId} value={current} disabled={disabled} onChange={(e) => onChoose(e.target.value)}>
                <option value="">Choose what to do</option>
                {row.allowedResolutions.map((a) => (
                  <option key={a} value={a}>
                    {RESOLUTION_LABEL[a] ?? a}
                  </option>
                ))}
              </Select>
            </div>
          )}
        </div>
      </td>
    </tr>
  )
}

/** After the import: the counts and, once, the temporary credentials of the new accounts. */
function ImportResult({ workspaceId, result }: { workspaceId: string; result: CommitResult }) {
  const navigate = useNavigate()
  const [downloaded, setDownloaded] = useState(false)

  const download = () => {
    const csv = toCsv(
      ['Student ID', 'Last name', 'First name', 'Temporary credential', 'Expires'],
      result.credentials.map((c) => [c.studentNumber, c.lastName, c.firstName, c.temporaryCredential, c.expiresAt]),
    )
    const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }))
    const a = document.createElement('a')
    a.href = url
    a.download = 'temporary-credentials.csv'
    a.click()
    URL.revokeObjectURL(url)
    setDownloaded(true)
  }

  return (
    <div className="flex flex-col gap-6">
      <Alert role="status">
        <AlertDescription>
          {result.alreadyCommitted
            ? 'This import had already been applied. No new credentials are shown.'
            : `Import finished: ${result.created} new account(s), ${result.enrolledExisting} existing account(s) enrolled, ${result.skippedDuplicates + result.skippedByResolution} skipped, ${result.invalid} not valid.`}
        </AlertDescription>
      </Alert>

      {result.credentials.length > 0 && (
        <Card>
          <CardHeader>
            <CardTitle className="text-base">Temporary credentials</CardTitle>
            <CardDescription>
              Shown only once. Give each Participant their credential; they use it with their Student ID to activate the account. If you
              lose one, you can issue a new one from the Participants page.
            </CardDescription>
          </CardHeader>
          <CardContent className="flex flex-col gap-4">
            <div className="overflow-x-auto rounded-lg border">
              <table className="w-full text-left text-sm">
                <caption className="sr-only">Temporary credentials of the new accounts</caption>
                <thead className="bg-muted/50 text-muted-foreground">
                  <tr>
                    <th scope="col" className="px-3 py-2 font-medium">Student ID</th>
                    <th scope="col" className="px-3 py-2 font-medium">Name</th>
                    <th scope="col" className="px-3 py-2 font-medium">Temporary credential</th>
                  </tr>
                </thead>
                <tbody>
                  {result.credentials.map((c) => (
                    <tr key={c.participantId} className="border-t">
                      <td className="px-3 py-2">{c.studentNumber ?? ''}</td>
                      <td className="px-3 py-2">{`${c.firstName} ${c.lastName}`.trim()}</td>
                      <td className="px-3 py-2">
                        <code className="font-mono">{c.temporaryCredential}</code>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <div className="flex flex-wrap items-center gap-3">
              <Button type="button" variant="outline" onClick={download}>
                Download as CSV
              </Button>
              {downloaded && <span className="text-sm">Downloaded. Keep the file somewhere safe and delete it after handing the credentials out.</span>}
            </div>
          </CardContent>
        </Card>
      )}

      <div>
        <Button type="button" onClick={() => navigate(`/workspaces/${workspaceId}/roster`)}>
          Go to the Participants
        </Button>
      </div>
    </div>
  )
}
