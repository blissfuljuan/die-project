import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { searchCoordinatorAccounts } from '@/api'
import { PageHeader } from '@/components/common/PageHeader'
import { PersonPicker, type PersonOption } from '@/components/common/PersonPicker'
import { useSession } from '@/features/session/useSession'
import { homePathFor } from '@/lib/roles'
import { useCreateWorkspace } from './mutations'
import { WorkspaceForm } from './WorkspaceForm'

/**
 * New Project Workspace. A Coordinator who creates one becomes its Owner; an Administrator must choose the Owner (the
 * API rejects a request without one), who is then also assigned as a Coordinator.
 */
export function CreateWorkspacePage() {
  const { user } = useSession()
  const navigate = useNavigate()
  const create = useCreateWorkspace()
  const [owner, setOwner] = useState<PersonOption | null>(null)
  const [ownerError, setOwnerError] = useState<string | undefined>()
  if (!user) return null
  const isAdmin = user.role === 'ADMIN'

  return (
    <>
      <p className="mb-2 text-sm">
        <Link to={isAdmin ? '/admin/workspaces' : homePathFor(user.role)} className="underline underline-offset-2">
          Back
        </Link>
      </p>
      <PageHeader
        title="New Project Workspace"
        description={
          isAdmin
            ? 'Set up the workspace and choose the Coordinator who will own it.'
            : 'You will be the Owner of this Project Workspace and can add other Coordinators afterwards.'
        }
      />
      <WorkspaceForm
        submitLabel="Create Project Workspace"
        pending={create.isPending}
        onCancel={() => navigate(-1)}
        onSubmit={async (settings) => {
          if (isAdmin && !owner) {
            setOwnerError('Choose the Coordinator who will own this Project Workspace.')
            return
          }
          const workspace = await create.mutateAsync({ ...settings, ...(isAdmin && owner ? { ownerId: owner.id } : {}) })
          navigate(`/workspaces/${workspace.id}`, { replace: true })
        }}
      >
        {isAdmin && (
          <PersonPicker
            label="Owner"
            queryKey={['owner-search']}
            search={(q, signal) => searchCoordinatorAccounts(q, signal)}
            value={owner}
            onChange={(p) => {
              setOwner(p)
              setOwnerError(undefined)
            }}
            error={ownerError}
          />
        )}
      </WorkspaceForm>
    </>
  )
}
