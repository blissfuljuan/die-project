import type { ReactNode } from 'react'
import { friendlyMessage } from '@/api'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'

export function ListSkeleton({ rows = 3, label = 'Loading' }: { rows?: number; label?: string }) {
  return (
    <div role="status" aria-live="polite" className="flex flex-col gap-3">
      <span className="sr-only">{label}...</span>
      {Array.from({ length: rows }, (_, i) => (
        <Skeleton key={i} className="h-16 w-full" />
      ))}
    </div>
  )
}

export function ErrorWithRetry({
  title = 'This could not be loaded',
  error,
  onRetry,
  retrying,
}: {
  title?: string
  error: unknown
  onRetry: () => void
  retrying?: boolean
}) {
  return (
    <Alert variant="destructive">
      <AlertTitle>{title}</AlertTitle>
      <AlertDescription>
        <p>{friendlyMessage(error)}</p>
        <Button type="button" variant="outline" size="sm" className="mt-3" onClick={onRetry} disabled={retrying}>
          {retrying ? 'Trying again...' : 'Try again'}
        </Button>
      </AlertDescription>
    </Alert>
  )
}

export function EmptyState({ title, children }: { title: string; children?: ReactNode }) {
  return (
    <div className="rounded-lg border border-dashed p-6 text-center">
      <p className="font-medium">{title}</p>
      {children && <p className="mt-1 text-sm text-muted-foreground">{children}</p>}
    </div>
  )
}

/** Loading / error-with-retry / empty / content for one list query. */
export function QueryList<T>({
  query,
  isEmpty,
  empty,
  loadingLabel,
  children,
  errorTitle,
}: {
  query: { data: T | undefined; error: unknown; isPending: boolean; isFetching: boolean; refetch: () => unknown }
  isEmpty: (data: T) => boolean
  empty: ReactNode
  loadingLabel: string
  errorTitle?: string
  children: (data: T) => ReactNode
}) {
  if (query.isPending && !query.error) return <ListSkeleton label={loadingLabel} />
  if (query.error || query.data === undefined) {
    return (
      <ErrorWithRetry
        title={errorTitle}
        error={query.error}
        onRetry={() => void query.refetch()}
        retrying={query.isFetching}
      />
    )
  }
  if (isEmpty(query.data)) return <>{empty}</>
  return <>{children(query.data)}</>
}
