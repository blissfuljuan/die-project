import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation } from '@tanstack/react-query'
import { useForm } from 'react-hook-form'
import { Link } from 'react-router-dom'
import { z } from 'zod'
import { friendlyMessage, isApiError, requestPasswordReset } from '@/api'
import { TextField } from '@/components/common/TextField'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { AuthLayout } from '@/layouts/AuthLayout'

const schema = z.object({
  email: z.string().trim().min(1, 'Enter your email address.').email('Enter a valid email address.'),
})
type Values = z.infer<typeof schema>

export function ForgotPasswordPage() {
  const request = useMutation({ mutationFn: (email: string) => requestPasswordReset(email) })
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<Values>({ resolver: zodResolver(schema), defaultValues: { email: '' } })

  // The same neutral confirmation is shown whether or not an account exists. Only throttling and transport
  // problems are reported differently, because they are not about the address.
  const throttled = isApiError(request.error) && request.error.status === 429
  const failed = request.isError

  if (request.isSuccess) {
    return (
      <AuthLayout title="Check your email">
        <Alert role="status">
          <AlertDescription>
            <p>
              If an account exists for that address, we have sent a link to reset the password. The link works once and
              expires after about an hour.
            </p>
          </AlertDescription>
        </Alert>
        <p className="mt-6 text-sm">
          <Link to="/login" className="underline underline-offset-2">
            Back to sign in
          </Link>
        </p>
      </AuthLayout>
    )
  }

  return (
    <AuthLayout title="Forgot your password?" description="Enter your email address and we will send you a reset link.">
      <form onSubmit={handleSubmit((v) => request.mutate(v.email))} noValidate className="flex flex-col gap-4">
        {failed && (
          <Alert variant="destructive">
            <AlertDescription>
              {throttled
                ? 'Too many reset requests. Please wait a few minutes before asking again.'
                : friendlyMessage(request.error)}
            </AlertDescription>
          </Alert>
        )}
        <TextField label="Email" type="email" autoComplete="email" inputMode="email" error={errors.email?.message} {...register('email')} />
        <Button type="submit" disabled={request.isPending}>
          {request.isPending ? 'Sending...' : 'Send reset link'}
        </Button>
      </form>
      <p className="mt-6 text-sm">
        <Link to="/login" className="underline underline-offset-2">
          Back to sign in
        </Link>
      </p>
    </AuthLayout>
  )
}
