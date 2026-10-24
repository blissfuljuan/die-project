import { zodResolver } from '@hookform/resolvers/zod'
import { useMemo, useState } from 'react'
import { useForm } from 'react-hook-form'
import { Link, useParams } from 'react-router-dom'
import { z } from 'zod'
import { friendlyMessage, isApiError, type Enrollment } from '@/api'
import { CheckboxField } from '@/components/common/CheckboxField'
import { PageHeader } from '@/components/common/PageHeader'
import { Pagination } from '@/components/common/Pagination'
import { EmptyState, ErrorWithRetry, ListSkeleton } from '@/components/common/QueryState'
import { TextField } from '@/components/common/TextField'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { useSession } from '@/features/session/useSession'
import { useWorkspace } from '@/features/workspaces/queries'
import { workspaceTitle } from '@/features/workspaces/labels'
import { applyServerErrors } from '@/lib/forms'
import { fullName } from '@/lib/roles'
import { NotFoundBody } from '@/pages/ForbiddenPage'
import { CredentialReveal } from './CredentialReveal'
import { useEnroll, useReissueCredential, useRoster, useUnenroll } from './queries'

const PAGE_SIZE = 50

const ACCOUNT_LABEL: Record<string, string> = {
  ACTIVE: 'Active',
  PENDING_ACTIVATION: 'Waiting to activate',
  DEACTIVATED: 'Deactivated',
}

/** Whether the API will let this user manage the roster (Administrators, or a Coordinator holding the Participants permission). */
export const canManageRoster = (role: string, myCapabilities: readonly string[]) =>
  role === 'ADMIN' || myCapabilities.includes('MANAGE_ROSTER')

/** The enrolled Participants of one Project Workspace: look, enroll one by Student ID, re-issue a credential, remove. */
export function RosterPage() {
  const { workspaceId = '' } = useParams()
  const { user } = useSession()
  const workspace = useWorkspace(workspaceId)
  const [page, setPage] = useState(0)
  const [includeRemoved, setIncludeRemoved] = useState(false)
  const roster = useRoster(workspaceId, page, PAGE_SIZE, includeRemoved)
  const unenroll = useUnenroll(workspaceId)
  const reissue = useReissueCredential(workspaceId)
  const [revealed, setRevealed] = useState<{ who: string; secret: string; expiresAt: string } | null>(null)
  const [confirmRemove, setConfirmRemove] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
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
  const archived = w.status === 'ARCHIVED'

  const issue = (e: Enrollment) => {
    setNotice(null)
    reissue.mutate(e.participantId, {
      onSuccess: (c) => setRevealed({ who: fullName(e), secret: c.secret, expiresAt: c.expiresAt }),
    })
  }

  return (
    <>
      <p className="mb-2 text-sm">
        <Link to={`/workspaces/${w.id}`} className="underline underline-offset-2">
          Back to {workspaceTitle(w)}
        </Link>
      </p>
      <PageHeader
        title="Participants"
        description={`${workspaceTitle(w)} · ${w.enrolledCount} enrolled`}
        actions={
          !archived && (
            <Button asChild variant="outline">
              <Link to={`/workspaces/${w.id}/roster/import`}>Import from a file</Link>
            </Button>
          )
        }
      />

      <div className="flex flex-col gap-6">
        {archived && (
          <Alert>
            <AlertDescription>This Project Workspace is archived, so its roster can no longer be changed.</AlertDescription>
          </Alert>
        )}
        {revealed && (
          <CredentialReveal who={revealed.who} secret={revealed.secret} expiresAt={revealed.expiresAt} onDone={() => setRevealed(null)} />
        )}
        {notice && (
          <Alert role="status">
            <AlertDescription>{notice}</AlertDescription>
          </Alert>
        )}
        {reissue.error && (
          <Alert variant="destructive">
            <AlertDescription>
              {isApiError(reissue.error) && reissue.error.code === 'ACCOUNT_ALREADY_ACTIVATED'
                ? 'This Participant has already activated their account. If they forgot their password, they can use "Forgot your password?" on the sign-in page.'
                : friendlyMessage(reissue.error)}
            </AlertDescription>
          </Alert>
        )}

        {!archived && <EnrollCard workspaceId={w.id} isAdmin={user.role === 'ADMIN'} onEnrolled={(name) => setNotice(`${name} is now enrolled.`)} />}

        <section aria-labelledby="roster-list" className="flex flex-col gap-3">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <h2 id="roster-list" className="text-lg font-semibold">
              Enrolled Participants
            </h2>
            <CheckboxField
              label="Show removed Participants"
              checked={includeRemoved}
              onChange={(e) => {
                setIncludeRemoved(e.target.checked)
                setPage(0)
              }}
            />
          </div>

          {roster.isPending && !roster.error && <ListSkeleton label="Loading the roster" rows={4} />}
          {roster.error && !roster.data && (
            <ErrorWithRetry
              title="The roster could not be loaded"
              error={roster.error}
              onRetry={() => void roster.refetch()}
              retrying={roster.isFetching}
            />
          )}
          {roster.data && roster.data.items.length === 0 && (
            <EmptyState title="No Participants yet">Enroll one above or import a file.</EmptyState>
          )}
          {roster.data && roster.data.items.length > 0 && (
            <>
              <div className="overflow-x-auto rounded-lg border">
                <table className="w-full text-left text-sm">
                  <caption className="sr-only">Participants of this Project Workspace</caption>
                  <thead className="bg-muted/50 text-muted-foreground">
                    <tr>
                      <th scope="col" className="px-3 py-2 font-medium">Student ID</th>
                      <th scope="col" className="px-3 py-2 font-medium">Name</th>
                      <th scope="col" className="px-3 py-2 font-medium">Program / section</th>
                      <th scope="col" className="px-3 py-2 font-medium">Account</th>
                      <th scope="col" className="px-3 py-2 font-medium">Actions</th>
                    </tr>
                  </thead>
                  <tbody>
                    {roster.data.items.map((e) => {
                      const name = fullName(e)
                      const enrolled = e.status === 'ENROLLED'
                      return (
                        <tr key={e.participantId} className="border-t align-top">
                          <td className="px-3 py-2">{e.studentNumber ?? ''}</td>
                          <td className="px-3 py-2">
                            <div className="font-medium">{name}</div>
                            {e.email && <div className="text-muted-foreground break-all">{e.email}</div>}
                          </td>
                          <td className="px-3 py-2">{[e.program, e.section].filter(Boolean).join(' · ')}</td>
                          <td className="px-3 py-2">
                            <div className="flex flex-wrap gap-1.5">
                              <Badge variant="secondary">{ACCOUNT_LABEL[e.accountStatus] ?? e.accountStatus}</Badge>
                              {!enrolled && <Badge variant="outline">Removed</Badge>}
                            </div>
                          </td>
                          <td className="px-3 py-2">
                            {enrolled && !archived && (
                              <div className="flex flex-col items-start gap-2">
                                <div className="flex flex-wrap gap-2">
                                  {e.accountStatus === 'PENDING_ACTIVATION' && (
                                    <Button
                                      type="button"
                                      variant="outline"
                                      size="sm"
                                      aria-label={`Issue a new temporary credential for ${name}`}
                                      disabled={reissue.isPending}
                                      onClick={() => issue(e)}
                                    >
                                      New credential
                                    </Button>
                                  )}
                                  <Button
                                    type="button"
                                    variant="outline"
                                    size="sm"
                                    aria-label={`Remove ${name} from this Project Workspace`}
                                    onClick={() => {
                                      unenroll.reset()
                                      setConfirmRemove(e.participantId)
                                    }}
                                  >
                                    Remove
                                  </Button>
                                </div>
                                {confirmRemove === e.participantId && (
                                  <div role="group" aria-label={`Confirm removing ${name}`} className="flex flex-col gap-2 rounded-md border border-destructive/40 p-2">
                                    <p>Remove {name} from this Project Workspace? They lose access to it and leave their team.</p>
                                    {unenroll.error && <p className="text-destructive">{friendlyMessage(unenroll.error)}</p>}
                                    <div className="flex gap-2">
                                      <Button
                                        type="button"
                                        variant="destructive"
                                        size="sm"
                                        disabled={unenroll.isPending}
                                        onClick={() =>
                                          unenroll.mutate(e.participantId, {
                                            onSuccess: () => {
                                              setConfirmRemove(null)
                                              setNotice(`${name} was removed.`)
                                            },
                                          })
                                        }
                                      >
                                        {unenroll.isPending ? 'Removing...' : `Remove ${name}`}
                                      </Button>
                                      <Button type="button" variant="outline" size="sm" disabled={unenroll.isPending} onClick={() => setConfirmRemove(null)}>
                                        Cancel
                                      </Button>
                                    </div>
                                  </div>
                                )}
                              </div>
                            )}
                          </td>
                        </tr>
                      )
                    })}
                  </tbody>
                </table>
              </div>
              <Pagination page={roster.data.page} size={roster.data.size} total={roster.data.total} onPage={setPage} busy={roster.isFetching} />
            </>
          )}
        </section>
      </div>
    </>
  )
}

function EnrollCard({ workspaceId, isAdmin, onEnrolled }: { workspaceId: string; isAdmin: boolean; onEnrolled: (name: string) => void }) {
  const enroll = useEnroll(workspaceId)
  const [formError, setFormError] = useState<string | null>(null)
  // The API makes Coordinators prove they know the person: the last name has to match the Student ID. Administrators may omit it.
  const schema = useMemo(
    () =>
      z.object({
        studentNumber: z.string().trim().min(1, 'Enter the Student ID.').max(64, 'Use at most 64 characters.'),
        lastName: isAdmin
          ? z.string().trim().max(100, 'Use at most 100 characters.')
          : z.string().trim().min(1, "Enter the Participant's last name.").max(100, 'Use at most 100 characters.'),
        program: z.string().trim().max(100, 'Use at most 100 characters.'),
        section: z.string().trim().max(100, 'Use at most 100 characters.'),
      }),
    [isAdmin],
  )
  type Values = z.infer<typeof schema>
  const {
    register,
    handleSubmit,
    reset,
    setError,
    formState: { errors },
  } = useForm<Values>({ resolver: zodResolver(schema), defaultValues: { studentNumber: '', lastName: '', program: '', section: '' } })

  const submit = handleSubmit((v) => {
    setFormError(null)
    enroll.mutate(
      { studentNumber: v.studentNumber, lastName: v.lastName, program: v.program || undefined, section: v.section || undefined },
      {
        onSuccess: () => {
          onEnrolled(v.studentNumber)
          reset()
        },
        onError: (error) => setFormError(applyServerErrors(error, setError, ['studentNumber', 'lastName', 'program', 'section'])),
      },
    )
  })

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-base">Enroll a Participant</CardTitle>
        <CardDescription>
          For a Participant who already has an account. To create accounts for a whole class, import a file instead.
        </CardDescription>
      </CardHeader>
      <CardContent>
        <form noValidate onSubmit={submit} className="flex max-w-2xl flex-col gap-4">
          {formError && (
            <Alert variant="destructive">
              <AlertDescription>{formError}</AlertDescription>
            </Alert>
          )}
          <div className="grid gap-4 sm:grid-cols-2">
            <TextField label="Student ID" error={errors.studentNumber?.message} {...register('studentNumber')} />
            <TextField
              label={isAdmin ? 'Last name (optional)' : 'Last name'}
              hint={isAdmin ? undefined : 'Must match the Student ID.'}
              error={errors.lastName?.message}
              {...register('lastName')}
            />
            <TextField label="Program (optional)" error={errors.program?.message} {...register('program')} />
            <TextField label="Section (optional)" error={errors.section?.message} {...register('section')} />
          </div>
          <div>
            <Button type="submit" disabled={enroll.isPending}>
              {enroll.isPending ? 'Enrolling...' : 'Enroll Participant'}
            </Button>
          </div>
        </form>
      </CardContent>
    </Card>
  )
}
