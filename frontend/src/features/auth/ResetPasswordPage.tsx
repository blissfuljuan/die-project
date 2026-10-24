import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { useForm, useWatch } from 'react-hook-form'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { z } from 'zod'
import { confirmPasswordReset, isApiError } from '@/api'
import { PasswordStrengthHint } from '@/components/common/PasswordStrengthHint'
import { TextField } from '@/components/common/TextField'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { AuthLayout } from '@/layouts/AuthLayout'
import { applyServerErrors } from '@/lib/forms'
import { passwordSchema } from '@/lib/password'

const schema = z
  .object({ newPassword: passwordSchema, confirm: z.string() })
  .refine((v) => v.newPassword === v.confirm, { path: ['confirm'], message: 'The two passwords do not match.' })
type Values = z.infer<typeof schema>

/** Landing page of the emailed reset link: `/reset-password?token=...` (the path is fixed by the backend mail). */
export function ResetPasswordPage() {
  const [params] = useSearchParams()
  const navigate = useNavigate()
  // Read once, then remove the secret from the address bar and history.
  const [token] = useState(() => params.get('token') ?? '')
  useEffect(() => {
    if (token) navigate('/reset-password', { replace: true })
  }, [token, navigate])

  const [formError, setFormError] = useState<string | null>(null)
  const confirm = useMutation({ mutationFn: (newPassword: string) => confirmPasswordReset(token, newPassword) })
  const {
    register,
    handleSubmit,
    setError,
    control,
    formState: { errors },
  } = useForm<Values>({ resolver: zodResolver(schema), defaultValues: { newPassword: '', confirm: '' } })
  const newPassword = useWatch({ control, name: 'newPassword' })

  if (!token) {
    return (
      <AuthLayout title="Reset link needed">
        <Alert variant="destructive">
          <AlertDescription>
            This page needs the link from your reset email. Request a new link to continue.
          </AlertDescription>
        </Alert>
        <p className="mt-6 text-sm">
          <Link to="/forgot-password" className="underline underline-offset-2">
            Send me a new reset link
          </Link>
        </p>
      </AuthLayout>
    )
  }

  if (confirm.isSuccess) {
    return (
      <AuthLayout title="Password updated">
        <Alert role="status">
          <AlertDescription>
            Your password has been changed and you have been signed out everywhere. Sign in with your new password.
          </AlertDescription>
        </Alert>
        <Button asChild className="mt-6">
          <Link to="/login">Go to sign in</Link>
        </Button>
      </AuthLayout>
    )
  }

  const linkInvalid = isApiError(confirm.error) && confirm.error.code === 'RESET_LINK_INVALID'

  return (
    <AuthLayout title="Choose a new password">
      <form
        noValidate
        className="flex flex-col gap-4"
        onSubmit={handleSubmit((v) => {
          setFormError(null)
          confirm.mutate(v.newPassword, {
            onError: (error) => setFormError(applyServerErrors(error, setError, ['newPassword'])),
          })
        })}
      >
        {formError && (
          <Alert variant="destructive">
            <AlertDescription>
              <p>{formError}</p>
              {linkInvalid && (
                <p className="mt-2">
                  <Link to="/forgot-password" className="underline underline-offset-2">
                    Send me a new reset link
                  </Link>
                </p>
              )}
            </AlertDescription>
          </Alert>
        )}
        <TextField
          label="New password"
          type="password"
          autoComplete="new-password"
          error={errors.newPassword?.message}
          hint={<PasswordStrengthHint value={newPassword} />}
          {...register('newPassword')}
        />
        <TextField
          label="Repeat the new password"
          type="password"
          autoComplete="new-password"
          error={errors.confirm?.message}
          {...register('confirm')}
        />
        <Button type="submit" disabled={confirm.isPending}>
          {confirm.isPending ? 'Saving...' : 'Save new password'}
        </Button>
      </form>
    </AuthLayout>
  )
}
