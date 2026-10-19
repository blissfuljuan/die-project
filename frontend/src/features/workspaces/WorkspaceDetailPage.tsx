import { Link, useParams } from 'react-router-dom'
import { isApiError } from '@/api'
import { PageHeader } from '@/components/common/PageHeader'
import { EmptyState, ErrorWithRetry, ListSkeleton, QueryList } from '@/components/common/QueryState'
import { Badge } from '@/components/ui/badge'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { useSession } from '@/features/session/useSession'
import { capabilityLabel, fullName, homePathFor } from '@/lib/roles'
import { NotFoundBody } from '@/pages/ForbiddenPage'
import { projectKindLabel, teamFormationLabel, teamRoleLabel, workspaceSubtitle, workspaceTitle } from './labels'
import { useWorkspace, useWorkspaceProjects } from './queries'

/** Read-only view of what the API returns for this workspace; nothing is inferred on the client. */
export function WorkspaceDetailPage() {
  const { workspaceId = '' } = useParams()
  const { user } = useSession()
  const workspace = useWorkspace(workspaceId)
  // Administrators do not see project work; the project list is requested only for Participants and Coordinators.
  const projects = useWorkspaceProjects(workspaceId, !!workspace.data && user?.role !== 'ADMIN')
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
  const isOwner = !!w.ownerId && w.ownerId === user.id

  return (
    <>
      <p className="mb-2 text-sm">
        <Link to={homePathFor(user.role)} className="underline underline-offset-2">
          Back to dashboard
        </Link>
      </p>
      <PageHeader
        title={workspaceTitle(w)}
        description={workspaceSubtitle(w)}
        actions={
          <>
            {isOwner && <Badge>Owner</Badge>}
            {w.status === 'ARCHIVED' && <Badge variant="outline">Archived</Badge>}
          </>
        }
      />

      <div className="flex flex-col gap-6">
        <Card>
          <CardHeader>
            <CardTitle className="text-base">Teams</CardTitle>
          </CardHeader>
          <CardContent>
            <dl className="grid grid-cols-1 gap-x-6 gap-y-3 text-sm sm:grid-cols-2">
              <div>
                <dt className="text-muted-foreground">How teams are formed</dt>
                <dd className="font-medium">{teamFormationLabel(w.teamFormation)}</dd>
              </div>
              <div>
                <dt className="text-muted-foreground">Team size</dt>
                <dd className="font-medium">
                  {w.teamMinSize} to {w.teamMaxSize}
                </dd>
              </div>
              <div>
                <dt className="text-muted-foreground">Participants may change teams</dt>
                <dd className="font-medium">{w.participantsMayChangeTeams ? 'Yes' : 'No'}</dd>
              </div>
              {user.role !== 'PARTICIPANT' && (
                <div>
                  <dt className="text-muted-foreground">Participants enrolled</dt>
                  <dd className="font-medium">{w.enrolledCount}</dd>
                </div>
              )}
            </dl>
          </CardContent>
        </Card>

        {w.coordinators.length > 0 && (
          <Card>
            <CardHeader>
              <CardTitle className="text-base">Coordinators</CardTitle>
            </CardHeader>
            <CardContent>
              <ul className="flex flex-col gap-2 text-sm">
                {w.coordinators.map((c) => (
                  <li key={c.id} className="flex flex-wrap items-center gap-2">
                    <span>{fullName(c)}</span>
                    {c.owner && <Badge>Owner</Badge>}
                  </li>
                ))}
              </ul>
            </CardContent>
          </Card>
        )}

        {user.role === 'COORDINATOR' && w.myCapabilities.length > 0 && (
          <Card>
            <CardHeader>
              <CardTitle className="text-base">What you can do here</CardTitle>
              <CardDescription>Granted to you in this Project Workspace.</CardDescription>
            </CardHeader>
            <CardContent>
              <ul className="flex flex-wrap gap-1.5">
                {w.myCapabilities.map((c) => (
                  <li key={c}>
                    <Badge variant="secondary">{capabilityLabel(c)}</Badge>
                  </li>
                ))}
              </ul>
            </CardContent>
          </Card>
        )}

        {user.role !== 'ADMIN' && (
          <section aria-labelledby="ws-projects">
            <h2 id="ws-projects" className="mb-3 text-lg font-semibold">
              Projects
            </h2>
            <QueryList
              query={projects}
              loadingLabel="Loading projects"
              errorTitle="The projects could not be loaded"
              isEmpty={(d) => d.length === 0}
              empty={<EmptyState title="No projects to show" />}
            >
              {(list) => (
                <ul className="flex flex-col gap-3">
                  {list.map((p) => (
                    <li key={p.id} className="rounded-lg border bg-card p-4 text-sm">
                      <div className="flex flex-wrap items-center justify-between gap-2">
                        <Link to={`/projects/${p.id}`} className="font-medium underline-offset-2 hover:underline">
                          {p.title || p.teamName || 'Untitled project'}
                        </Link>
                        <div className="flex gap-1.5">
                          {p.myRole && <Badge variant="secondary">{teamRoleLabel(p.myRole)}</Badge>}
                          {p.status === 'ARCHIVED' && <Badge variant="outline">Archived</Badge>}
                        </div>
                      </div>
                      <p className="text-muted-foreground">
                        {projectKindLabel(p.kind)}
                        {p.teamName ? ` · ${p.teamName}` : ''} · {p.members.length}{' '}
                        {p.members.length === 1 ? 'member' : 'members'}
                      </p>
                    </li>
                  ))}
                </ul>
              )}
            </QueryList>
          </section>
        )}
      </div>
    </>
  )
}
