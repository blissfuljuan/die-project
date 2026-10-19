import { useMutation } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { friendlyMessage, verifyEmail } from '@/api'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { AuthLayout } from '@/layouts/AuthLayout'

/**
 * Landing page of the emailed activation link: `/verify-email?token=...` (path fixed by the backend mail).
 * The token is single-use, so it is submitted exactly once per page load and then removed from the address bar.
 */
export function VerifyEmailPage() {
  const [params] = useSearchParams()
  const navigate = useNavigate()
  const [token] = useState(() => params.get('token') ?? '')
  const started = useRef(false)
  const verify = useMutation({ mutationFn: (t: string) => verifyEmail(t) })
  const { mutate } = verify

  useEffect(() => {
    if (!token || started.current) return
    started.current = true
    mutate(token)
    navigate('/verify-email', { replace: true })
  }, [token, mutate, navigate])

  if (!token) {
    return (
      <AuthLayout title="Verification link needed">
        <Alert variant="destructive">
          <AlertDescription>
            This page needs the link from your verification email. If it has expired, ask your Coordinator for a new
            temporary credential and activate again.
          </AlertDescription>
        </Alert>
        <p className="mt-6 text-sm">
          <Link to="/activate" className="underline underline-offset-2">
            Activate an account
          </Link>
        </p>
      </AuthLayout>
    )
  }

  if (verify.isSuccess) {
    return (
      <AuthLayout title="Email verified">
        <Alert role="status">
          <AlertDescription>Your account is active. You can now sign in.</AlertDescription>
        </Alert>
        <Button asChild className="mt-6">
          <Link to="/login">Go to sign in</Link>
        </Button>
      </AuthLayout>
    )
  }

  if (verify.isError) {
    return (
      <AuthLayout title="We could not verify your email">
        <Alert variant="destructive">
          <AlertDescription>{friendlyMessage(verify.error)}</AlertDescription>
        </Alert>
        <p className="mt-6 text-sm">
          <Link to="/activate" className="underline underline-offset-2">
            Start activation again
          </Link>
        </p>
      </AuthLayout>
    )
  }

  return (
    <AuthLayout title="Verifying your email">
      <p role="status" aria-live="polite" className="text-muted-foreground">
        Please wait while we confirm your email address...
      </p>
    </AuthLayout>
  )
}
