import { Navigate, Outlet, useLocation, useSearchParams } from 'react-router-dom'
import type { Role } from '@/api'
import { ErrorWithRetry } from '@/components/common/QueryState'
import { Skeleton } from '@/components/ui/skeleton'
import { useSession } from '@/features/session/useSession'
import { loginPathFor, safeNextPath } from '@/lib/nextPath'
import { homePathFor } from '@/lib/roles'
import { ForbiddenPage } from '@/pages/ForbiddenPage'

function FullPageLoading() {
  return (
    <div role="status" aria-live="polite" className="mx-auto flex min-h-screen max-w-md flex-col justify-center gap-3 px-4">
      <span className="sr-only">Loading your session...</span>
      <Skeleton className="h-8 w-40" />
      <Skeleton className="h-24 w-full" />
    </div>
  )
}

/** Signed-out visitors go to /login and come back to the page they asked for (deep links survive sign-in). */
export function RequireAuth() {
  const session = useSession()
  const location = useLocation()
  if (session.isLoading) return <FullPageLoading />
  if (session.isError) {
    return (
      <div className="mx-auto flex min-h-screen max-w-md flex-col justify-center px-4">
        <ErrorWithRetry
          title="We could not check your session"
          error={session.error}
          onRetry={() => void session.refetch()}
        />
      </div>
    )
  }
  if (!session.user) {
    return <Navigate to={loginPathFor(location.pathname + location.search + location.hash)} replace />
  }
  return <Outlet />
}

/** Presentation only: the API still enforces every permission. A wrong-role visit shows the forbidden screen. */
export function RequireRole({ roles }: { roles: readonly Role[] }) {
  const { user } = useSession()
  if (!user) return null
  if (!roles.includes(user.role)) return <ForbiddenPage />
  return <Outlet />
}

/** Sign-in, activation and forgot-password make no sense while signed in. */
export function PublicOnly() {
  const { user, isLoading } = useSession()
  const [params] = useSearchParams()
  if (isLoading) return <FullPageLoading />
  if (user) return <Navigate to={safeNextPath(params.get('next')) ?? homePathFor(user.role)} replace />
  return <Outlet />
}

/** "/" sends everyone to their own area (or to the login page). */
export function RoleHomeRedirect() {
  const { user } = useSession()
  if (!user) return null
  return <Navigate to={homePathFor(user.role)} replace />
}
