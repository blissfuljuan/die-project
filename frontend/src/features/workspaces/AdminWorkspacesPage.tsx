import { Link } from 'react-router-dom'
import { PageHeader } from '@/components/common/PageHeader'
import { EmptyState, QueryList } from '@/components/common/QueryState'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { workspaceSubtitle, workspaceTitle } from './labels'
import { useWorkspaces } from './queries'

/** Administrators: every Project Workspace (settings and people only; never project work) and the entry to create one. */
export function AdminWorkspacesPage() {
  const workspaces = useWorkspaces()
  return (
    <>
      <PageHeader
        title="Project Workspaces"
        description="Create Project Workspaces, name their Owners and recover ownership when needed."
        actions={
          <Button asChild>
            <Link to="/workspaces/new">New Project Workspace</Link>
          </Button>
        }
      />
      <QueryList
        query={workspaces}
        loadingLabel="Loading Project Workspaces"
        errorTitle="The Project Workspaces could not be loaded"
        isEmpty={(d) => d.items.length === 0}
        empty={<EmptyState title="There are no Project Workspaces yet">Create the first one to get started.</EmptyState>}
      >
        {(page) => (
          <ul className="flex flex-col gap-3">
            {page.items.map((w) => {
              const owner = w.coordinators.find((c) => c.owner)
              return (
                <li key={w.id} className="rounded-lg border bg-card p-4 text-sm">
                  <div className="flex flex-wrap items-center justify-between gap-2">
                    <Link to={`/workspaces/${w.id}`} className="font-medium underline-offset-2 hover:underline">
                      {workspaceTitle(w)}
                    </Link>
                    {w.status === 'ARCHIVED' && <Badge variant="outline">Archived</Badge>}
                  </div>
                  <p className="text-muted-foreground">
                    {workspaceSubtitle(w)}
                    {owner ? ` · Owner: ${owner.firstName} ${owner.lastName}`.trimEnd() : ''}
                  </p>
                </li>
              )
            })}
          </ul>
        )}
      </QueryList>
    </>
  )
}
