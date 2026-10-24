import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { fetchPlatformHealth, type PlatformHealth } from '@/api'
import { PageHeader } from '@/components/common/PageHeader'
import { ErrorWithRetry, ListSkeleton } from '@/components/common/QueryState'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { useSession } from '@/features/session/useSession'

type Variant = 'success' | 'destructive' | 'secondary'
const variantFor = (status: string): Variant => (status === 'UP' ? 'success' : status === 'DOWN' || status === 'DEGRADED' ? 'destructive' : 'secondary')
const wordFor = (status: string) => (status === 'UP' ? 'Working' : status === 'DOWN' ? 'Down' : status === 'DEGRADED' ? 'Degraded' : status)

export function AdminDashboard() {
  const { user } = useSession()
  const health = useQuery({
    queryKey: ['ops', 'health'],
    queryFn: ({ signal }) => fetchPlatformHealth(signal),
    refetchInterval: 30_000,
  })
  if (!user) return null

  return (
    <>
      <PageHeader
        title={`Welcome, ${user.firstName}`}
        description="Platform health and administration. Administrators manage accounts and the platform; they do not see private project work."
      />
      <div className="flex flex-col gap-8">
        <section aria-labelledby="platform-health">
          <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
            <h2 id="platform-health" className="text-lg font-semibold">
              Platform health
            </h2>
            <Button type="button" variant="outline" size="sm" onClick={() => void health.refetch()} disabled={health.isFetching}>
              {health.isFetching ? 'Checking...' : 'Check again'}
            </Button>
          </div>
          {health.isPending && !health.error && <ListSkeleton label="Loading platform health" rows={2} />}
          {health.error && (
            <ErrorWithRetry
              title="Platform health could not be loaded"
              error={health.error}
              onRetry={() => void health.refetch()}
              retrying={health.isFetching}
            />
          )}
          {health.data && <HealthSummary health={health.data} />}
        </section>

        <section aria-labelledby="admin-areas">
          <h2 id="admin-areas" className="mb-3 text-lg font-semibold">
            Administration
          </h2>
          <ul className="grid gap-4 sm:grid-cols-2">
            <AreaLink to="/admin/accounts" title="Accounts" text="Create, deactivate and reset accounts." />
            <AreaLink to="/admin/workspaces" title="Project Workspaces" text="See and assign Owners of Project Workspaces." />
          </ul>
        </section>
      </div>
    </>
  )
}

function AreaLink({ to, title, text }: { to: string; title: string; text: string }) {
  return (
    <li>
      <Card>
        <CardHeader>
          <div className="flex items-center justify-between gap-2">
            <CardTitle className="text-base">
              <Link to={to} className="underline-offset-2 hover:underline">
                {title}
              </Link>
            </CardTitle>
            <Badge variant="outline">Coming soon</Badge>
          </div>
          <CardDescription>{text}</CardDescription>
        </CardHeader>
      </Card>
    </li>
  )
}

function Stat({ label, value }: { label: string; value: string | number }) {
  return (
    <div>
      <dt className="text-muted-foreground">{label}</dt>
      <dd className="font-medium">{value}</dd>
    </div>
  )
}

function HealthSummary({ health }: { health: PlatformHealth }) {
  const queues = Object.entries(health.queueDepths)
  return (
    <div className="flex flex-col gap-4">
      <Card>
        <CardHeader>
          <div className="flex items-center justify-between gap-3">
            <CardTitle>Overall</CardTitle>
            <Badge variant={variantFor(health.status)}>{wordFor(health.status)}</Badge>
          </div>
          <CardDescription>Checked {new Date(health.checkedAt).toLocaleString()}</CardDescription>
        </CardHeader>
      </Card>

      <div className="grid gap-4 md:grid-cols-2">
        <Card>
          <CardHeader>
            <CardTitle className="text-base">Services</CardTitle>
          </CardHeader>
          <CardContent>
            <ul className="flex flex-col gap-2 text-sm">
              <li className="flex items-center justify-between gap-2">
                <span>Database</span>
                <Badge variant={variantFor(health.database.status)}>{wordFor(health.database.status)}</Badge>
              </li>
              <li className="flex items-center justify-between gap-2">
                <span>Message broker</span>
                <Badge variant={variantFor(health.broker.status)}>{wordFor(health.broker.status)}</Badge>
              </li>
              {health.workers.map((w) => (
                <li key={w.name} className="flex items-center justify-between gap-2">
                  <span className="break-words">Worker: {w.name}</span>
                  <Badge variant={variantFor(w.status)}>{wordFor(w.status)}</Badge>
                </li>
              ))}
            </ul>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle className="text-base">Background work</CardTitle>
          </CardHeader>
          <CardContent>
            <dl className="grid grid-cols-2 gap-x-6 gap-y-3 text-sm">
              <Stat label="Waiting to send" value={health.outbox.pending} />
              <Stat label="Failed to send" value={health.outbox.failed} />
              <Stat label="Test jobs queued" value={health.testJobs.queued} />
              <Stat label="Test jobs running" value={health.testJobs.running} />
              <Stat label="Test jobs failed" value={health.testJobs.failed} />
              <Stat label="Test jobs overdue" value={health.testJobs.overdue} />
            </dl>
          </CardContent>
        </Card>
      </div>

      {queues.length > 0 && (
        <Card>
          <CardHeader>
            <CardTitle className="text-base">Queue sizes</CardTitle>
          </CardHeader>
          <CardContent>
            <dl className="grid grid-cols-1 gap-x-6 gap-y-2 text-sm sm:grid-cols-2">
              {queues.map(([name, depth]) => (
                <div key={name} className="flex justify-between gap-2">
                  <dt className="text-muted-foreground break-all">{name}</dt>
                  <dd className="font-medium">{depth}</dd>
                </div>
              ))}
            </dl>
          </CardContent>
        </Card>
      )}
    </div>
  )
}
