import { PageHeader } from '@/components/common/PageHeader'
import { EmptyState, QueryList } from '@/components/common/QueryState'
import { useSession } from '@/features/session/useSession'
import { useWorkspaces } from '@/features/workspaces/queries'
import { WorkspaceCard } from '@/features/workspaces/WorkspaceCard'

export function CoordinatorDashboard() {
  const { user } = useSession()
  const workspaces = useWorkspaces()
  if (!user) return null

  return (
    <>
      <PageHeader
        title={`Welcome, ${user.firstName}`}
        description="The Project Workspaces you coordinate, with what you are allowed to do in each."
      />
      <section aria-labelledby="coord-workspaces">
        <h2 id="coord-workspaces" className="mb-3 text-lg font-semibold">
          My Project Workspaces
        </h2>
        <QueryList
          query={workspaces}
          loadingLabel="Loading your Project Workspaces"
          errorTitle="Your Project Workspaces could not be loaded"
          isEmpty={(d) => d.items.length === 0}
          empty={
            <EmptyState title="You do not coordinate a Project Workspace yet">
              An Administrator or another Coordinator can add you to a Project Workspace.
            </EmptyState>
          }
        >
          {(page) => (
            <ul className="grid gap-4 md:grid-cols-2">
              {page.items.map((w) => (
                <li key={w.id}>
                  <WorkspaceCard workspace={w} userId={user.id} showCoordinatorInfo />
                </li>
              ))}
            </ul>
          )}
        </QueryList>
      </section>
    </>
  )
}
