import { zodResolver } from '@hookform/resolvers/zod'
import { useForm } from 'react-hook-form'
import { Link, useSearchParams } from 'react-router-dom'
import { z } from 'zod'
import { friendlyMessage, isApiError } from '@/api'
import { TextField } from '@/components/common/TextField'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { useLogin } from '@/features/session/useSession'
import { AuthLayout } from '@/layouts/AuthLayout'
import { applyServerErrors } from '@/lib/forms'
import { safeNextPath } from '@/lib/nextPath'

const schema = z.object({
  email: z.string().trim().min(1, 'Enter your email address.').email('Enter a valid email address.'),
  password: z.string().min(1, 'Enter your password.'),
})
type Values = z.infer<typeof schema>

export function LoginPage() {
  const [params] = useSearchParams()
  const login = useLogin()
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<Values>({ resolver: zodResolver(schema), defaultValues: { email: '', password: '' } })

  const expired = safeNextPath(params.get('next')) !== null

  const onSubmit = (values: Values) => {
    login.mutate(values, {
      // On success the session query is set and <PublicOnly> redirects to the return path or the role home.
      onError: (error) => applyServerErrors(error, setError, ['email', 'password']),
    })
  }

  // Wrong credentials, unknown account and deactivated account all look the same here (the API does too).
  const formError = login.error && !(isApiError(login.error) && login.error.errors.length > 0) ? friendlyMessage(login.error) : null

  return (
    <AuthLayout title="Sign in" description="Use the email address linked to your DIE account.">
      {expired && (
        <Alert className="mb-4" role="status">
          <AlertDescription>Please sign in to continue.</AlertDescription>
        </Alert>
      )}
      <form onSubmit={handleSubmit(onSubmit)} noValidate className="flex flex-col gap-4">
        {formError && (
          <Alert variant="destructive">
            <AlertDescription>{formError}</AlertDescription>
          </Alert>
        )}
        <TextField
          label="Email"
          type="email"
          autoComplete="username"
          inputMode="email"
          error={errors.email?.message}
          {...register('email')}
        />
        <TextField
          label="Password"
          type="password"
          autoComplete="current-password"
          error={errors.password?.message}
          {...register('password')}
        />
        <Button type="submit" disabled={login.isPending}>
          {login.isPending ? 'Signing in...' : 'Sign in'}
        </Button>
      </form>
      <ul className="mt-6 flex flex-col gap-2 text-sm">
        <li>
          <Link to="/forgot-password" className="underline underline-offset-2">
            Forgot your password?
          </Link>
        </li>
        <li>
          <Link to="/activate" className="underline underline-offset-2">
            First time here? Activate your account
          </Link>
        </li>
      </ul>
    </AuthLayout>
  )
}
