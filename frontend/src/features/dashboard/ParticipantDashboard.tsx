import { useQueries } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { fetchWorkspaceProjects, type Project, type Workspace } from '@/api'
import { PageHeader } from '@/components/common/PageHeader'
import { EmptyState, ErrorWithRetry, ListSkeleton, QueryList } from '@/components/common/QueryState'
import { Badge } from '@/components/ui/badge'
import { Card, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { useSession } from '@/features/session/useSession'
import { projectKindLabel, teamRoleLabel, workspaceTitle } from '@/features/workspaces/labels'
import { useWorkspaces, workspaceKeys } from '@/features/workspaces/queries'
import { WorkspaceCard } from '@/features/workspaces/WorkspaceCard'

export function ParticipantDashboard() {
  const { user } = useSession()
  const workspaces = useWorkspaces()
  if (!user) return null

  return (
    <>
      <PageHeader title={`Welcome, ${user.firstName}`} description="Your Project Workspaces and projects." />
      <div className="flex flex-col gap-8">
        <section aria-labelledby="my-workspaces">
          <h2 id="my-workspaces" className="mb-3 text-lg font-semibold">
            My Project Workspaces
          </h2>
          <QueryList
            query={workspaces}
            loadingLabel="Loading your Project Workspaces"
            errorTitle="Your Project Workspaces could not be loaded"
            isEmpty={(d) => d.items.length === 0}
            empty={
              <EmptyState title="You are not in a Project Workspace yet">
                Your Coordinator adds Participants to a Project Workspace. It will appear here once you are added.
              </EmptyState>
            }
          >
            {(page) => (
              <ul className="grid gap-4 sm:grid-cols-2">
                {page.items.map((w) => (
                  <li key={w.id}>
                    <WorkspaceCard workspace={w} userId={user.id} />
                  </li>
                ))}
              </ul>
            )}
          </QueryList>
        </section>

        <section aria-labelledby="my-projects">
          <h2 id="my-projects" className="mb-3 text-lg font-semibold">
            My projects
          </h2>
          {workspaces.isPending && !workspaces.error && <ListSkeleton label="Loading your projects" rows={2} />}
          {workspaces.data && <MyProjects workspaces={workspaces.data.items} />}
          {workspaces.error && (
            <p className="text-sm text-muted-foreground">Your projects appear once your Project Workspaces load.</p>
          )}
        </section>
      </div>
    </>
  )
}

function MyProjects({ workspaces }: { workspaces: Workspace[] }) {
  const results = useQueries({
    queries: workspaces.map((w) => ({
      queryKey: workspaceKeys.projects(w.id),
      queryFn: ({ signal }: { signal: AbortSignal }) => fetchWorkspaceProjects(w.id, signal),
    })),
  })

  if (workspaces.length === 0) {
    return <EmptyState title="No projects yet">Projects show up here after you join a Project Workspace.</EmptyState>
  }
  if (results.some((r) => r.isPending)) return <ListSkeleton label="Loading your projects" rows={2} />

  const failed = results.find((r) => r.isError)
  const projects = results.flatMap((r, i) =>
    (r.data ?? []).map((p) => ({ project: p, workspace: workspaces[i] as Workspace })),
  )

  return (
    <div className="flex flex-col gap-4">
      {failed && (
        <ErrorWithRetry
          title="Some projects could not be loaded"
          error={failed.error}
          onRetry={() => results.forEach((r) => r.isError && void r.refetch())}
        />
      )}
      {projects.length === 0 && !failed ? (
        <EmptyState title="You are not on a project yet">
          Ask your Coordinator about your team, or accept a team invitation when it is available.
        </EmptyState>
      ) : (
        <ul className="grid gap-4 sm:grid-cols-2">
          {projects.map(({ project, workspace }) => (
            <li key={project.id}>
              <ProjectSummary project={project} workspaceName={workspaceTitle(workspace)} />
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

export function ProjectSummary({ project, workspaceName }: { project: Project; workspaceName: string }) {
  return (
    <Card>
      <CardHeader>
        <div className="flex flex-wrap items-start justify-between gap-2">
          <CardTitle className="text-base leading-snug break-words">
            <Link to={`/projects/${project.id}`} className="underline-offset-2 hover:underline">
              {project.title || project.teamName || 'Untitled project'}
            </Link>
          </CardTitle>
          <div className="flex flex-wrap gap-1.5">
            {project.myRole && <Badge variant="secondary">{teamRoleLabel(project.myRole)}</Badge>}
            {project.status === 'ARCHIVED' && <Badge variant="outline">Archived</Badge>}
          </div>
        </div>
        <CardDescription>
          {workspaceName} · {projectKindLabel(project.kind)}
          {project.teamName ? ` · ${project.teamName}` : ''}
        </CardDescription>
      </CardHeader>
    </Card>
  )
}
