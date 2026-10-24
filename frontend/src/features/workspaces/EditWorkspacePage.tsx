import { useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { isApiError, type Workspace } from '@/api'
import { PageHeader } from '@/components/common/PageHeader'
import { ErrorWithRetry, ListSkeleton } from '@/components/common/QueryState'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { useSession } from '@/features/session/useSession'
import { NotFoundBody } from '@/pages/ForbiddenPage'
import { useUpdateWorkspace } from './mutations'
import { useWorkspace } from './queries'
import { workspaceTitle } from './labels'
import { WorkspaceForm } from './WorkspaceForm'

/** Whether the API will let this user change the settings (Administrators, or a Coordinator holding MANAGE_SETTINGS). */
export const canEditSettings = (role: string, workspace: Pick<Workspace, 'myCapabilities'>) =>
  role === 'ADMIN' || workspace.myCapabilities.includes('MANAGE_SETTINGS')

export function EditWorkspacePage() {
  const { workspaceId = '' } = useParams()
  const { user } = useSession()
  const navigate = useNavigate()
  const workspace = useWorkspace(workspaceId)
  const update = useUpdateWorkspace(workspaceId)
  const [stale, setStale] = useState(false)
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
  if (!canEditSettings(user.role, w)) return <NotFoundBody title="Not found, or no access" />

  const back = `/workspaces/${w.id}`
  return (
    <>
      <p className="mb-2 text-sm">
        <Link to={back} className="underline underline-offset-2">
          Back to {workspaceTitle(w)}
        </Link>
      </p>
      <PageHeader title="Project Workspace settings" description={workspaceTitle(w)} />
      {w.status === 'ARCHIVED' ? (
        <Alert>
          <AlertDescription>This Project Workspace is archived, so its settings can no longer be changed.</AlertDescription>
        </Alert>
      ) : (
        <>
          {stale && (
            <Alert variant="destructive" className="mb-4">
              <AlertDescription>
                <p>Someone else changed these settings while you were editing. Load the latest version to continue.</p>
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  className="mt-3"
                  onClick={() => {
                    setStale(false)
                    void workspace.refetch()
                  }}
                >
                  Load the latest settings
                </Button>
              </AlertDescription>
            </Alert>
          )}
          <WorkspaceForm
            // A new version remounts the form with the values the server now holds.
            key={w.version}
            submitLabel="Save settings"
            pending={update.isPending}
            onCancel={() => navigate(back)}
            initial={{
              academicYear: w.academicYear ?? '',
              semester: w.semester ?? '',
              subjectCode: w.subjectCode,
              subjectName: w.subjectName,
              section: w.section ?? '',
              teamFormation: w.teamFormation === 'PARTICIPANT_FORMED' ? 'PARTICIPANT_FORMED' : 'COORDINATOR_ASSIGNED',
              teamMinSize: w.teamMinSize,
              teamMaxSize: w.teamMaxSize,
              participantsMayChangeTeams: w.participantsMayChangeTeams,
            }}
            onSubmit={async (settings) => {
              try {
                await update.mutateAsync({ ...settings, version: w.version })
              } catch (error) {
                if (isApiError(error) && error.code === 'CLASS_VERSION_CONFLICT') setStale(true)
                throw error
              }
              navigate(back)
            }}
          />
        </>
      )}
    </>
  )
}
