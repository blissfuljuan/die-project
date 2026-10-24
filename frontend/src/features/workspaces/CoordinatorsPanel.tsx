import { useState } from 'react'
import { fetchCoordinatorCandidates, friendlyMessage, type CoordinatorEntry, type Me, type Workspace } from '@/api'
import { PersonPicker, type PersonOption } from '@/components/common/PersonPicker'
import { ErrorWithRetry, ListSkeleton } from '@/components/common/QueryState'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Label } from '@/components/ui/label'
import { Select } from '@/components/ui/select'
import { DEFAULT_CAPABILITIES } from '@/lib/capabilities'
import { capabilityLabel, fullName } from '@/lib/roles'
import { CapabilityChecklist } from './CapabilityChecklist'
import {
  useAddCoordinator,
  useCoordinators,
  useRemoveCoordinator,
  useSetCoordinatorCapabilities,
  useTransferOwnership,
} from './mutations'

type Mode =
  | { kind: 'none' }
  | { kind: 'add' }
  | { kind: 'edit'; userId: string }
  | { kind: 'remove'; userId: string }
  | { kind: 'transfer' }

/**
 * Who coordinates a Project Workspace and what each may do. What is offered comes from the API's answer about this user
 * (`myCapabilities`, `ownerId`); the API checks every action again. Changes to people need the workspace to be active;
 * handing ownership over is also allowed while archived (owner recovery).
 */
export function CoordinatorsPanel({ workspace, user }: { workspace: Workspace; user: Me }) {
  const coordinators = useCoordinators(workspace.id)
  const add = useAddCoordinator(workspace.id)
  const setCaps = useSetCoordinatorCapabilities(workspace.id)
  const remove = useRemoveCoordinator(workspace.id)
  const transfer = useTransferOwnership(workspace.id)
  const [mode, setMode] = useState<Mode>({ kind: 'none' })

  const isAdmin = user.role === 'ADMIN'
  const archived = workspace.status === 'ARCHIVED'
  const canManage = (isAdmin || workspace.myCapabilities.includes('MANAGE_COORDINATORS')) && !archived
  const canTransfer = isAdmin || (!!workspace.ownerId && workspace.ownerId === user.id)
  // Administrators may grant anything; everyone else only what they hold.
  const grantable = isAdmin ? null : workspace.myCapabilities

  const close = () => {
    setMode({ kind: 'none' })
    add.reset()
    setCaps.reset()
    remove.reset()
    transfer.reset()
  }

  if (coordinators.isPending && !coordinators.error) return <ListSkeleton label="Loading Coordinators" rows={2} />
  if (coordinators.error || !coordinators.data) {
    return (
      <ErrorWithRetry
        title="The Coordinators could not be loaded"
        error={coordinators.error}
        onRetry={() => void coordinators.refetch()}
        retrying={coordinators.isFetching}
      />
    )
  }
  const list = coordinators.data

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-base">Coordinators</CardTitle>
        <CardDescription>
          The Owner holds every permission. Other Coordinators hold only the permissions listed for them.
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <ul className="flex flex-col gap-3" aria-label="Coordinators">
          {list.map((c) => (
            <li key={c.userId} className="rounded-lg border p-3 text-sm">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="font-medium">{fullName(c)}</span>
                  {c.owner && <Badge>Owner</Badge>}
                </div>
                {canManage && !c.owner && mode.kind === 'none' && (
                  <div className="flex gap-2">
                    <Button
                      type="button"
                      variant="outline"
                      size="sm"
                      aria-label={`Change permissions of ${fullName(c)}`}
                      onClick={() => setMode({ kind: 'edit', userId: c.userId })}
                    >
                      Change permissions
                    </Button>
                    <Button
                      type="button"
                      variant="outline"
                      size="sm"
                      aria-label={`Remove ${fullName(c)}`}
                      onClick={() => setMode({ kind: 'remove', userId: c.userId })}
                    >
                      Remove
                    </Button>
                  </div>
                )}
              </div>
              {c.owner ? (
                <p className="mt-2 text-muted-foreground">Every permission</p>
              ) : (
                <ul aria-label={`Permissions of ${fullName(c)}`} className="mt-2 flex flex-wrap gap-1.5">
                  {c.capabilities.length === 0 && <li className="text-muted-foreground">No permissions</li>}
                  {c.capabilities.map((code) => (
                    <li key={code}>
                      <Badge variant="secondary">{capabilityLabel(code)}</Badge>
                    </li>
                  ))}
                </ul>
              )}
              {mode.kind === 'edit' && mode.userId === c.userId && (
                <EditPermissions
                  entry={c}
                  grantable={grantable}
                  pending={setCaps.isPending}
                  error={setCaps.error}
                  onCancel={close}
                  onSave={(capabilities) => setCaps.mutate({ userId: c.userId, capabilities }, { onSuccess: close })}
                />
              )}
              {mode.kind === 'remove' && mode.userId === c.userId && (
                <ConfirmBox
                  message={`Remove ${fullName(c)} from this Project Workspace? They lose access to it immediately.`}
                  confirmLabel={`Remove ${fullName(c)}`}
                  pending={remove.isPending}
                  error={remove.error}
                  onCancel={close}
                  onConfirm={() => remove.mutate(c.userId, { onSuccess: close })}
                />
              )}
            </li>
          ))}
        </ul>

        {archived && (
          <p className="text-sm text-muted-foreground">This Project Workspace is archived, so Coordinators cannot be added or changed.</p>
        )}

        {mode.kind === 'add' && (
          <AddCoordinator
            workspaceId={workspace.id}
            grantable={grantable}
            pending={add.isPending}
            error={add.error}
            onCancel={close}
            onAdd={(person, capabilities) => add.mutate({ userId: person.id, capabilities }, { onSuccess: close })}
          />
        )}

        {mode.kind === 'transfer' && (
          <TransferOwnership
            candidates={list.filter((c) => !c.owner)}
            pending={transfer.isPending}
            error={transfer.error}
            onCancel={close}
            onTransfer={(userId) => transfer.mutate(userId, { onSuccess: close })}
          />
        )}

        {mode.kind === 'none' && (canManage || canTransfer) && (
          <div className="flex flex-wrap gap-2">
            {canManage && (
              <Button type="button" variant="outline" size="sm" onClick={() => setMode({ kind: 'add' })}>
                Add a Coordinator
              </Button>
            )}
            {canTransfer && (
              <Button type="button" variant="outline" size="sm" onClick={() => setMode({ kind: 'transfer' })}>
                Transfer ownership
              </Button>
            )}
          </div>
        )}
      </CardContent>
    </Card>
  )
}

function ErrorLine({ error }: { error: unknown }) {
  if (!error) return null
  return (
    <Alert variant="destructive">
      <AlertDescription>{friendlyMessage(error)}</AlertDescription>
    </Alert>
  )
}

function ConfirmBox({
  message,
  confirmLabel,
  pending,
  error,
  onCancel,
  onConfirm,
}: {
  message: string
  confirmLabel: string
  pending: boolean
  error: unknown
  onCancel: () => void
  onConfirm: () => void
}) {
  return (
    <div role="group" aria-label="Confirm" className="mt-3 flex flex-col gap-3 rounded-lg border border-destructive/40 p-3">
      <p>{message}</p>
      <ErrorLine error={error} />
      <div className="flex flex-wrap gap-2">
        <Button type="button" variant="destructive" size="sm" onClick={onConfirm} disabled={pending}>
          {pending ? 'Working...' : confirmLabel}
        </Button>
        <Button type="button" variant="outline" size="sm" onClick={onCancel} disabled={pending}>
          Cancel
        </Button>
      </div>
    </div>
  )
}

function EditPermissions({
  entry,
  grantable,
  pending,
  error,
  onCancel,
  onSave,
}: {
  entry: CoordinatorEntry
  grantable: readonly string[] | null
  pending: boolean
  error: unknown
  onCancel: () => void
  onSave: (capabilities: string[]) => void
}) {
  const [value, setValue] = useState(entry.capabilities)
  return (
    <div className="mt-3 flex flex-col gap-3">
      <CapabilityChecklist
        legend={`Permissions of ${fullName(entry)}`}
        value={value}
        onChange={setValue}
        grantable={grantable}
        disabled={pending}
      />
      <ErrorLine error={error} />
      <div className="flex flex-wrap gap-2">
        <Button type="button" size="sm" onClick={() => onSave(value)} disabled={pending}>
          {pending ? 'Saving...' : 'Save permissions'}
        </Button>
        <Button type="button" variant="outline" size="sm" onClick={onCancel} disabled={pending}>
          Cancel
        </Button>
      </div>
    </div>
  )
}

function AddCoordinator({
  workspaceId,
  grantable,
  pending,
  error,
  onCancel,
  onAdd,
}: {
  workspaceId: string
  grantable: readonly string[] | null
  pending: boolean
  error: unknown
  onCancel: () => void
  onAdd: (person: PersonOption, capabilities: string[]) => void
}) {
  const [person, setPerson] = useState<PersonOption | null>(null)
  // Start from the defaults, limited to what this person may hand out.
  const [value, setValue] = useState(DEFAULT_CAPABILITIES.filter((c) => grantable === null || grantable.includes(c)))
  return (
    <div role="group" aria-label="Add a Coordinator" className="flex flex-col gap-4 rounded-lg border p-4">
      <h3 className="text-sm font-medium">Add a Coordinator</h3>
      <PersonPicker
        label="Find a Coordinator"
        queryKey={['coordinator-candidates', workspaceId]}
        search={(q, signal) =>
          fetchCoordinatorCandidates(workspaceId, q, signal).then((r) =>
            r.map((c) => ({ id: c.userId, firstName: c.firstName, lastName: c.lastName, email: c.email })),
          )
        }
        value={person}
        onChange={setPerson}
        disabled={pending}
      />
      <CapabilityChecklist legend="Permissions" value={value} onChange={setValue} grantable={grantable} disabled={pending} />
      <ErrorLine error={error} />
      <div className="flex flex-wrap gap-2">
        <Button type="button" size="sm" disabled={pending || !person} onClick={() => person && onAdd(person, value)}>
          {pending ? 'Adding...' : 'Add Coordinator'}
        </Button>
        <Button type="button" variant="outline" size="sm" onClick={onCancel} disabled={pending}>
          Cancel
        </Button>
      </div>
    </div>
  )
}

function TransferOwnership({
  candidates,
  pending,
  error,
  onCancel,
  onTransfer,
}: {
  candidates: CoordinatorEntry[]
  pending: boolean
  error: unknown
  onCancel: () => void
  onTransfer: (userId: string) => void
}) {
  const [target, setTarget] = useState('')
  const chosen = candidates.find((c) => c.userId === target)
  return (
    <div role="group" aria-label="Transfer ownership" className="flex flex-col gap-3 rounded-lg border border-destructive/40 p-4">
      <h3 className="text-sm font-medium">Transfer ownership</h3>
      {candidates.length === 0 ? (
        <p className="text-sm text-muted-foreground">
          Add another Coordinator first: ownership can only be given to a Coordinator who is already assigned here.
        </p>
      ) : (
        <>
          <p className="text-sm">
            The new Owner gets every permission and can transfer ownership again. The change takes effect at once and is recorded.
          </p>
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="transfer-target">New Owner</Label>
            <Select id="transfer-target" value={target} onChange={(e) => setTarget(e.target.value)} disabled={pending}>
              <option value="">Choose a Coordinator</option>
              {candidates.map((c) => (
                <option key={c.userId} value={c.userId}>
                  {fullName(c)}
                </option>
              ))}
            </Select>
          </div>
          <ErrorLine error={error} />
        </>
      )}
      <div className="flex flex-wrap gap-2">
        {candidates.length > 0 && (
          <Button type="button" variant="destructive" size="sm" disabled={pending || !chosen} onClick={() => chosen && onTransfer(chosen.userId)}>
            {pending ? 'Transferring...' : chosen ? `Make ${fullName(chosen)} the Owner` : 'Transfer ownership'}
          </Button>
        )}
        <Button type="button" variant="outline" size="sm" onClick={onCancel} disabled={pending}>
          Cancel
        </Button>
      </div>
    </div>
  )
}
