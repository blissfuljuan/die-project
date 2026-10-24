import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { friendlyMessage, isApiError, type Account, type AccountChange } from '@/api'
import { CredentialReveal } from '@/components/common/CredentialReveal'
import { PageHeader } from '@/components/common/PageHeader'
import { Pagination } from '@/components/common/Pagination'
import { EmptyState, ErrorWithRetry, ListSkeleton } from '@/components/common/QueryState'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Select } from '@/components/ui/select'
import { useSession } from '@/features/session/useSession'
import { fullName, ROLE_LABEL } from '@/lib/roles'
import type { Role } from '@/api/auth'
import { CreateAccountCard } from './CreateAccountCard'
import { useAccounts, useActivateAccount, useDeactivateAccount, useResetCredentials } from './queries'

const PAGE_SIZE = 25
const SEARCH_DEBOUNCE_MS = 300

export const STATUS_LABEL: Record<string, string> = {
  ACTIVE: 'Active',
  PENDING_ACTIVATION: 'Waiting to activate',
  DEACTIVATED: 'Deactivated',
}

type Pending = { kind: 'deactivate' | 'reset'; id: string } | null
type Revealed = { who: string; secret: string; expiresAt?: string | null }

/** Administrators: find accounts, create them, deactivate or re-activate them, and reset their credentials. */
export function AccountsPage() {
  const { user: me } = useSession()
  const [typed, setTyped] = useState('')
  const [q, setQ] = useState('')
  const [role, setRole] = useState('')
  const [status, setStatus] = useState('')
  const [page, setPage] = useState(0)
  const [creating, setCreating] = useState(false)
  const [pending, setPending] = useState<Pending>(null)
  const [revealed, setRevealed] = useState<Revealed | null>(null)
  const [notice, setNotice] = useState<string | null>(null)

  useEffect(() => {
    const t = setTimeout(() => {
      setQ(typed.trim())
      setPage(0)
    }, SEARCH_DEBOUNCE_MS)
    return () => clearTimeout(t)
  }, [typed])

  const accounts = useAccounts({ q, role, status, page, size: PAGE_SIZE })
  const deactivate = useDeactivateAccount()
  const activate = useActivateAccount()
  const reset = useResetCredentials()
  if (!me) return null

  const resetActionState = () => {
    deactivate.reset()
    reset.reset()
    activate.reset()
  }

  const doActivate = (a: Account) => {
    resetActionState()
    setNotice(null)
    activate.mutate(a.id, {
      onSuccess: (change) => {
        setNotice(`${fullName(a)} can sign in again.`)
        if (change.temporaryCredential) {
          setRevealed({ who: fullName(a), secret: change.temporaryCredential, expiresAt: change.temporaryCredentialExpiresAt })
          setNotice(`${fullName(a)} was re-activated and must activate the account again with a new temporary credential.`)
        }
      },
    })
  }

  return (
    <>
      <PageHeader
        title="Accounts"
        description="Create accounts and keep them safe. Credentials are shown once, when they are created."
        actions={
          !creating && (
            <Button type="button" onClick={() => setCreating(true)}>
              New account
            </Button>
          )
        }
      />

      <div className="flex flex-col gap-6">
        {revealed && (
          <CredentialReveal who={revealed.who} secret={revealed.secret} expiresAt={revealed.expiresAt} onDone={() => setRevealed(null)} />
        )}
        {notice && (
          <Alert role="status">
            <AlertDescription>{notice}</AlertDescription>
          </Alert>
        )}
        {activate.error && (
          <Alert variant="destructive">
            <AlertDescription>{friendlyMessage(activate.error)}</AlertDescription>
          </Alert>
        )}

        {creating && (
          <CreateAccountCard
            onCancel={() => setCreating(false)}
            onCreated={(change) => {
              setCreating(false)
              const who = fullName(change.user)
              setNotice(`The account for ${who} was created.`)
              if (change.temporaryCredential) {
                setRevealed({ who, secret: change.temporaryCredential, expiresAt: change.temporaryCredentialExpiresAt })
              }
            }}
          />
        )}

        <section aria-labelledby="accounts-list" className="flex flex-col gap-4">
          <h2 id="accounts-list" className="sr-only">
            All accounts
          </h2>
          <div className="grid gap-3 sm:grid-cols-3">
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="acc-search">Search accounts</Label>
              <Input
                id="acc-search"
                type="search"
                autoComplete="off"
                placeholder="Name, Student ID or email"
                value={typed}
                onChange={(e) => setTyped(e.target.value)}
              />
            </div>
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="acc-role">Filter by role</Label>
              <Select
                id="acc-role"
                value={role}
                onChange={(e) => {
                  setRole(e.target.value)
                  setPage(0)
                }}
              >
                <option value="">All roles</option>
                <option value="PARTICIPANT">Participants</option>
                <option value="COORDINATOR">Coordinators</option>
                <option value="ADMIN">Administrators</option>
              </Select>
            </div>
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="acc-status">Filter by status</Label>
              <Select
                id="acc-status"
                value={status}
                onChange={(e) => {
                  setStatus(e.target.value)
                  setPage(0)
                }}
              >
                <option value="">All statuses</option>
                <option value="ACTIVE">Active</option>
                <option value="PENDING_ACTIVATION">Waiting to activate</option>
                <option value="DEACTIVATED">Deactivated</option>
              </Select>
            </div>
          </div>

          {accounts.isPending && !accounts.error && <ListSkeleton label="Loading accounts" rows={4} />}
          {accounts.error && !accounts.data && (
            <ErrorWithRetry
              title="The accounts could not be loaded"
              error={accounts.error}
              onRetry={() => void accounts.refetch()}
              retrying={accounts.isFetching}
            />
          )}
          {accounts.data && accounts.data.items.length === 0 && <EmptyState title="No accounts match" />}
          {accounts.data && accounts.data.items.length > 0 && (
            <>
              <div className="overflow-x-auto rounded-lg border">
                <table className="w-full text-left text-sm">
                  <caption className="sr-only">Accounts</caption>
                  <thead className="bg-muted/50 text-muted-foreground">
                    <tr>
                      <th scope="col" className="px-3 py-2 font-medium">Name</th>
                      <th scope="col" className="px-3 py-2 font-medium">Role</th>
                      <th scope="col" className="px-3 py-2 font-medium">Student ID / email</th>
                      <th scope="col" className="px-3 py-2 font-medium">Status</th>
                      <th scope="col" className="px-3 py-2 font-medium">Actions</th>
                    </tr>
                  </thead>
                  <tbody>
                    {accounts.data.items.map((a) => (
                      <AccountRow
                        key={a.id}
                        account={a}
                        isMe={a.id === me.id}
                        pending={pending}
                        deactivate={deactivate}
                        reset={reset}
                        busy={activate.isPending}
                        onAsk={(kind) => {
                          resetActionState()
                          setPending({ kind, id: a.id })
                        }}
                        onCancel={() => {
                          resetActionState()
                          setPending(null)
                        }}
                        onActivate={() => doActivate(a)}
                        onDeactivated={() => {
                          setPending(null)
                          setNotice(`${fullName(a)} was deactivated and signed out.`)
                        }}
                        onReset={(change) => {
                          setPending(null)
                          setNotice(`${fullName(a)} was signed out and must activate again with the new temporary credential.`)
                          if (change.temporaryCredential) {
                            setRevealed({ who: fullName(a), secret: change.temporaryCredential, expiresAt: change.temporaryCredentialExpiresAt })
                          }
                        }}
                      />
                    ))}
                  </tbody>
                </table>
              </div>
              <Pagination page={accounts.data.page} size={accounts.data.size} total={accounts.data.total} onPage={setPage} busy={accounts.isFetching} />
            </>
          )}
        </section>
      </div>
    </>
  )
}

type Mutation<T> = { isPending: boolean; error: unknown; mutate: (id: string, o: { onSuccess: (r: T) => void }) => void }

function AccountRow({
  account: a,
  isMe,
  pending,
  deactivate,
  reset,
  busy,
  onAsk,
  onCancel,
  onActivate,
  onDeactivated,
  onReset,
}: {
  account: Account
  isMe: boolean
  pending: Pending
  deactivate: Mutation<Account>
  reset: Mutation<AccountChange>
  busy: boolean
  onAsk: (kind: 'deactivate' | 'reset') => void
  onCancel: () => void
  onActivate: () => void
  onDeactivated: () => void
  onReset: (change: AccountChange) => void
}) {
  const name = fullName(a)
  const mine = pending?.id === a.id ? pending.kind : null
  const deactivated = a.status === 'DEACTIVATED'
  return (
    <tr className="border-t align-top">
      <td className="px-3 py-2">
        <span className="font-medium">{name}</span>
        {isMe && <span className="ml-2 text-muted-foreground">(you)</span>}
      </td>
      <td className="px-3 py-2">{ROLE_LABEL[a.role as Role] ?? a.role}</td>
      <td className="px-3 py-2 break-all">{a.role === 'PARTICIPANT' ? (a.studentNumber ?? '') : (a.email ?? '')}</td>
      <td className="px-3 py-2">
        <Badge variant={deactivated ? 'outline' : 'secondary'}>{STATUS_LABEL[a.status] ?? a.status}</Badge>
      </td>
      <td className="px-3 py-2">
        {!isMe && (
          <div className="flex flex-col items-start gap-2">
            {mine === null && (
              <div className="flex flex-wrap gap-2">
                {deactivated ? (
                  <Button type="button" variant="outline" size="sm" aria-label={`Activate ${name} again`} disabled={busy} onClick={onActivate}>
                    Activate again
                  </Button>
                ) : (
                  <>
                    <Button type="button" variant="outline" size="sm" aria-label={`Reset the credentials of ${name}`} onClick={() => onAsk('reset')}>
                      Reset credentials
                    </Button>
                    <Button type="button" variant="outline" size="sm" aria-label={`Deactivate ${name}`} onClick={() => onAsk('deactivate')}>
                      Deactivate
                    </Button>
                  </>
                )}
              </div>
            )}
            {mine === 'deactivate' && (
              <ConfirmPanel
                label={`Confirm deactivating ${name}`}
                message={`Deactivate ${name}? They are signed out at once and cannot sign in until an Administrator activates the account again.`}
                action={`Deactivate ${name}`}
                mutation={deactivate}
                id={a.id}
                onCancel={onCancel}
                onDone={() => onDeactivated()}
              />
            )}
            {mine === 'reset' && (
              <ConfirmPanel
                label={`Confirm resetting ${name}`}
                message={`Reset the credentials of ${name}? Their password stops working, they are signed out everywhere, and they must activate the account again with a new temporary credential.`}
                action={`Reset ${name}`}
                mutation={reset}
                id={a.id}
                onCancel={onCancel}
                onDone={onReset}
              />
            )}
          </div>
        )}
      </td>
    </tr>
  )
}

function ConfirmPanel<T>({
  label,
  message,
  action,
  mutation,
  id,
  onCancel,
  onDone,
}: {
  label: string
  message: string
  action: string
  mutation: Mutation<T>
  id: string
  onCancel: () => void
  onDone: (result: T) => void
}) {
  const err = mutation.error
  const ownsWorkspaces = isApiError(err) && err.code === 'WORKSPACE_OWNER_TRANSFER_REQUIRED'
  return (
    <div role="group" aria-label={label} className="flex max-w-sm flex-col gap-2 rounded-md border border-destructive/40 p-2">
      <p>{message}</p>
      {err !== null && err !== undefined && (
        <Alert variant="destructive">
          <AlertDescription>
            <p>{friendlyMessage(err)}</p>
            {ownsWorkspaces && (
              <p className="mt-2">
                <Link to="/admin/workspaces" className="underline underline-offset-2">
                  Open Project Workspaces to transfer ownership
                </Link>
              </p>
            )}
          </AlertDescription>
        </Alert>
      )}
      <div className="flex gap-2">
        <Button type="button" variant="destructive" size="sm" disabled={mutation.isPending} onClick={() => mutation.mutate(id, { onSuccess: onDone })}>
          {mutation.isPending ? 'Working...' : action}
        </Button>
        <Button type="button" variant="outline" size="sm" disabled={mutation.isPending} onClick={onCancel}>
          Cancel
        </Button>
      </div>
    </div>
  )
}
