import { Link } from 'react-router-dom'
import type { Workspace } from '@/api'
import { Badge } from '@/components/ui/badge'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { capabilityLabel } from '@/lib/roles'
import { useWorkspaceProjects } from './queries'
import { workspaceSubtitle, workspaceTitle } from './labels'

/**
 * One workspace in a list. For Coordinators: Owner badge (ownerId from the API) and capability chips taken from the
 * API's `myCapabilities`; the UI never derives permissions on its own.
 */
export function WorkspaceCard({
  workspace,
  userId,
  showCoordinatorInfo,
}: {
  workspace: Workspace
  userId: string
  showCoordinatorInfo?: boolean
}) {
  const isOwner = !!workspace.ownerId && workspace.ownerId === userId
  const projects = useWorkspaceProjects(workspace.id, !!showCoordinatorInfo)
  const activeProjects = projects.data?.filter((p) => p.status === 'ACTIVE').length

  return (
    <Card>
      <CardHeader>
        <div className="flex flex-wrap items-start justify-between gap-2">
          <CardTitle className="text-base leading-snug break-words">
            <Link to={`/workspaces/${workspace.id}`} className="underline-offset-2 hover:underline">
              {workspaceTitle(workspace)}
            </Link>
          </CardTitle>
          <div className="flex flex-wrap gap-1.5">
            {isOwner && <Badge>Owner</Badge>}
            {workspace.status === 'ARCHIVED' && <Badge variant="outline">Archived</Badge>}
          </div>
        </div>
        <p className="text-sm text-muted-foreground">{workspaceSubtitle(workspace)}</p>
      </CardHeader>
      {showCoordinatorInfo && (
        <CardContent className="flex flex-col gap-3 text-sm">
          <p>
            {workspace.enrolledCount} {workspace.enrolledCount === 1 ? 'Participant' : 'Participants'}
            {' · '}
            {projects.isPending ? (
              <span className="text-muted-foreground">Counting projects...</span>
            ) : projects.isError ? (
              <span className="text-muted-foreground">Project count unavailable</span>
            ) : (
              `${activeProjects} active ${activeProjects === 1 ? 'project' : 'projects'}`
            )}
          </p>
          {workspace.myCapabilities.length > 0 && (
            <ul aria-label="Your permissions here" className="flex flex-wrap gap-1.5">
              {workspace.myCapabilities.map((c) => (
                <li key={c}>
                  <Badge variant="secondary">{capabilityLabel(c)}</Badge>
                </li>
              ))}
            </ul>
          )}
        </CardContent>
      )}
    </Card>
  )
}
