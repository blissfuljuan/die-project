import { useQuery } from '@tanstack/react-query'
import { fetchStatus } from '@/api'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardFooter, CardHeader, CardTitle } from '@/components/ui/card'
import { describeError } from './describeError'

const STATUS_QUERY_KEY = ['status'] as const

function formatTime(iso: string): string {
  return new Date(iso).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'medium' })
}

export function ConnectionStatusCard() {
  const { data, error, isPending, isFetching, refetch } = useQuery({
    queryKey: STATUS_QUERY_KEY,
    queryFn: ({ signal }) => fetchStatus(signal),
    retry: false,
    refetchOnWindowFocus: false,
  })

  const connected = !!data && !error
  const summary = error ? describeError(error) : null

  let badge = <Badge variant="secondary">Checking</Badge>
  if (connected) badge = <Badge variant="success">Connected</Badge>
  else if (error) badge = <Badge variant="destructive">Disconnected</Badge>

  return (
    <Card role="region" aria-labelledby="connection-status-title">
      <CardHeader>
        <div className="flex items-center justify-between gap-3">
          <CardTitle id="connection-status-title">Connection status</CardTitle>
          {badge}
        </div>
        <CardDescription>Whether this app can reach the DIE server.</CardDescription>
      </CardHeader>

      <CardContent>
        {/* One polite live region announces every state change to assistive technology. */}
        <div aria-live="polite" aria-atomic="true">
          {isPending && !error && <p>Checking the connection to the server...</p>}

          {connected && (
            <div>
              <p className="mb-4">Connected to the DIE server.</p>
              <dl className="grid grid-cols-1 gap-x-6 gap-y-3 text-sm sm:grid-cols-2">
                <Field label="Application" value={data.application} />
                <Field label="API version" value={data.apiVersion} />
                <Field label="Build version" value={data.buildVersion} />
                <Field label="Server time" value={formatTime(data.time)} />
              </dl>
            </div>
          )}

          {summary && (
            <Alert variant="destructive">
              <AlertTitle>{summary.title}</AlertTitle>
              <AlertDescription>
                <p>{summary.message}</p>
                {(summary.code || summary.traceId) && (
                  <dl className="mt-3 grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 font-mono text-xs">
                    {summary.code && (
                      <>
                        <dt>Error code</dt>
                        <dd className="break-all">{summary.code}</dd>
                      </>
                    )}
                    {summary.traceId && (
                      <>
                        <dt>Trace ID</dt>
                        <dd className="break-all">{summary.traceId}</dd>
                      </>
                    )}
                  </dl>
                )}
              </AlertDescription>
            </Alert>
          )}
        </div>
      </CardContent>

      <CardFooter>
        <Button
          type="button"
          variant={error ? 'default' : 'outline'}
          onClick={() => void refetch()}
          disabled={isFetching}
        >
          {isFetching ? 'Checking...' : error ? 'Retry' : 'Check again'}
        </Button>
      </CardFooter>
    </Card>
  )
}

function Field({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <dt className="text-muted-foreground">{label}</dt>
      <dd className="font-medium break-words">{value}</dd>
    </div>
  )
}
