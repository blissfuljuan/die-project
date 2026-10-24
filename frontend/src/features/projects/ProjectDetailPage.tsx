import { Link, useParams } from 'react-router-dom'
import { isApiError } from '@/api'
import { PageHeader } from '@/components/common/PageHeader'
import { ErrorWithRetry, ListSkeleton } from '@/components/common/QueryState'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { useSession } from '@/features/session/useSession'
import { projectKindLabel, teamFormationLabel, teamRoleLabel } from '@/features/workspaces/labels'
import { useProject } from '@/features/workspaces/queries'
import { fullName } from '@/lib/roles'
import { NotFoundBody } from '@/pages/ForbiddenPage'

/** Read-only project page. Members, invitations and Student IDs appear only if the API sent them for this user. */
export function ProjectDetailPage() {
  const { projectId = '' } = useParams()
  const { user } = useSession()
  const project = useProject(projectId)
  if (!user) return null

  if (project.isPending && !project.error) return <ListSkeleton label="Loading the project" rows={3} />
  if (project.error) {
    if (isApiError(project.error) && (project.error.status === 404 || project.error.status === 403)) {
      return <NotFoundBody title="Not found, or no access" />
    }
    return (
      <ErrorWithRetry
        title="This project could not be loaded"
        error={project.error}
        onRetry={() => void project.refetch()}
        retrying={project.isFetching}
      />
    )
  }
  const p = project.data
  const showStudentIds = p.members.some((m) => !!m.studentNumber)

  return (
    <>
      <p className="mb-2 text-sm">
        <Link to={`/workspaces/${p.workspaceId}`} className="underline underline-offset-2">
          Back to the Project Workspace
        </Link>
      </p>
      <PageHeader
        title={p.title || p.teamName || 'Untitled project'}
        description={`${projectKindLabel(p.kind)}${p.teamName && p.title ? ` · ${p.teamName}` : ''}`}
        actions={
          <>
            {p.myRole && <Badge variant="secondary">You: {teamRoleLabel(p.myRole)}</Badge>}
            {p.status === 'ARCHIVED' && <Badge variant="outline">Archived</Badge>}
          </>
        }
      />

      <div className="flex flex-col gap-6">
        {p.description && (
          <Card>
            <CardHeader>
              <CardTitle className="text-base">Description</CardTitle>
            </CardHeader>
            <CardContent className="text-sm break-words whitespace-pre-line">{p.description}</CardContent>
          </Card>
        )}

        {p.kind === 'TEAM' && p.meetsMinSize === false && user.role !== 'ADMIN' && (
          <Alert role="status">
            <AlertDescription>
              This team is smaller than the minimum team size for the Project Workspace
              {p.teamPolicy ? ` (${p.teamPolicy.minSize})` : ''}.
            </AlertDescription>
          </Alert>
        )}

        <Card>
          <CardHeader>
            <CardTitle className="text-base">{p.kind === 'TEAM' ? 'Team' : 'Participant'}</CardTitle>
          </CardHeader>
          <CardContent>
            {p.members.length === 0 ? (
              <p className="text-sm text-muted-foreground">No members to show.</p>
            ) : (
              <ul className="flex flex-col gap-2 text-sm">
                {p.members.map((m) => (
                  <li key={m.userId} className="flex flex-wrap items-center gap-2">
                    <span className="font-medium">{fullName(m)}</span>
                    {m.role === 'REPRESENTATIVE' && <Badge variant="secondary">Representative</Badge>}
                    {showStudentIds && m.studentNumber && (
                      <span className="text-muted-foreground">Student ID {m.studentNumber}</span>
                    )}
                  </li>
                ))}
              </ul>
            )}
          </CardContent>
        </Card>

        {p.pendingInvitations.length > 0 && (
          <Card>
            <CardHeader>
              <CardTitle className="text-base">Pending invitations</CardTitle>
            </CardHeader>
            <CardContent>
              <ul className="flex flex-col gap-2 text-sm">
                {p.pendingInvitations.map((i) => (
                  <li key={i.id}>{fullName(i)}</li>
                ))}
              </ul>
            </CardContent>
          </Card>
        )}

        {p.teamPolicy && (
          <Card>
            <CardHeader>
              <CardTitle className="text-base">Team rules</CardTitle>
            </CardHeader>
            <CardContent>
              <dl className="grid grid-cols-1 gap-x-6 gap-y-3 text-sm sm:grid-cols-3">
                <div>
                  <dt className="text-muted-foreground">Formation</dt>
                  <dd className="font-medium">{teamFormationLabel(p.teamPolicy.formation)}</dd>
                </div>
                <div>
                  <dt className="text-muted-foreground">Team size</dt>
                  <dd className="font-medium">
                    {p.teamPolicy.minSize} to {p.teamPolicy.maxSize}
                  </dd>
                </div>
                <div>
                  <dt className="text-muted-foreground">Participants may change teams</dt>
                  <dd className="font-medium">{p.teamPolicy.participantsMayChangeTeams ? 'Yes' : 'No'}</dd>
                </div>
              </dl>
            </CardContent>
          </Card>
        )}
      </div>
    </>
  )
}
