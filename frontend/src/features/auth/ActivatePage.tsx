import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm, useWatch } from 'react-hook-form'
import { Link } from 'react-router-dom'
import { z } from 'zod'
import {
  beginActivation,
  checkActivation,
  type ActivationCheck,
  type ActivationCredentials,
} from '@/api'
import { PasswordStrengthHint } from '@/components/common/PasswordStrengthHint'
import { TextField } from '@/components/common/TextField'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { AuthLayout } from '@/layouts/AuthLayout'
import { applyServerErrors } from '@/lib/forms'
import { passwordSchema } from '@/lib/password'

const credentialsSchema = z.object({
  identifier: z.string().trim().min(1, 'Enter your Student ID or email address.').max(254),
  temporaryCredential: z.string().trim().min(1, 'Enter the temporary credential you were given.').max(64),
})
type CredentialsValues = z.infer<typeof credentialsSchema>

const accountSchema = z
  .object({
    email: z.string().trim().min(1, 'Enter your email address.').email('Enter a valid email address.'),
    newPassword: passwordSchema,
    confirm: z.string(),
  })
  .refine((v) => v.newPassword === v.confirm, { path: ['confirm'], message: 'The two passwords do not match.' })
type AccountValues = z.infer<typeof accountSchema>

type Stage =
  | { step: 'credentials' }
  | { step: 'account'; credentials: ActivationCredentials; check: ActivationCheck }
  | { step: 'verify-email'; email: string }
  | { step: 'done' }

/**
 * Activation: (1) Student ID or email + temporary credential, (2) email + new password,
 * (3) "check your email" and the link lands on /verify-email. Accounts whose email is already verified
 * (after an Administrator reset) are active as soon as step 2 succeeds.
 */
export function ActivatePage() {
  const [stage, setStage] = useState<Stage>({ step: 'credentials' })

  if (stage.step === 'credentials') {
    return <CredentialsStep onChecked={(credentials, check) => setStage({ step: 'account', credentials, check })} />
  }
  if (stage.step === 'account') {
    return (
      <AccountStep
        credentials={stage.credentials}
        check={stage.check}
        onBack={() => setStage({ step: 'credentials' })}
        onDone={(status, email) => setStage(status === 'ACTIVE' ? { step: 'done' } : { step: 'verify-email', email })}
      />
    )
  }
  if (stage.step === 'verify-email') {
    return (
      <AuthLayout title="Check your email">
        <Alert role="status">
          <AlertDescription>
            <p>
              We sent a verification link to <strong className="break-all">{stage.email}</strong>. Open it to finish
              activating your account. The link works once and expires after 24 hours.
            </p>
            <p className="mt-2">Nothing arrived? Check your spam folder, or ask your Coordinator for help.</p>
          </AlertDescription>
        </Alert>
      </AuthLayout>
    )
  }
  return (
    <AuthLayout title="Your account is active">
      <Alert role="status">
        <AlertDescription>You can now sign in with your email address and your new password.</AlertDescription>
      </Alert>
      <Button asChild className="mt-6">
        <Link to="/login">Go to sign in</Link>
      </Button>
    </AuthLayout>
  )
}

function CredentialsStep({ onChecked }: { onChecked: (c: ActivationCredentials, check: ActivationCheck) => void }) {
  const [formError, setFormError] = useState<string | null>(null)
  const check = useMutation({ mutationFn: (input: ActivationCredentials) => checkActivation(input) })
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<CredentialsValues>({
    resolver: zodResolver(credentialsSchema),
    defaultValues: { identifier: '', temporaryCredential: '' },
  })

  return (
    <AuthLayout
      title="Activate your account"
      description="Step 1 of 2. Enter your Student ID (or email address) and the temporary credential your Coordinator gave you."
    >
      <form
        noValidate
        className="flex flex-col gap-4"
        onSubmit={handleSubmit((values) => {
          setFormError(null)
          check.mutate(values, {
            onSuccess: (result) => onChecked(values, result),
            onError: (error) => setFormError(applyServerErrors(error, setError, ['identifier', 'temporaryCredential'])),
          })
        })}
      >
        {formError && (
          <Alert variant="destructive">
            <AlertDescription>{formError}</AlertDescription>
          </Alert>
        )}
        <TextField
          label="Student ID or email"
          autoComplete="username"
          autoCapitalize="none"
          spellCheck={false}
          error={errors.identifier?.message}
          {...register('identifier')}
        />
        <TextField
          label="Temporary credential"
          type="password"
          autoComplete="off"
          error={errors.temporaryCredential?.message}
          {...register('temporaryCredential')}
        />
        <Button type="submit" disabled={check.isPending}>
          {check.isPending ? 'Checking...' : 'Continue'}
        </Button>
      </form>
      <p className="mt-6 text-sm">
        <Link to="/login" className="underline underline-offset-2">
          Already activated? Sign in
        </Link>
      </p>
    </AuthLayout>
  )
}

function AccountStep({
  credentials,
  check,
  onBack,
  onDone,
}: {
  credentials: ActivationCredentials
  check: ActivationCheck
  onBack: () => void
  onDone: (status: string, email: string) => void
}) {
  const [formError, setFormError] = useState<string | null>(null)
  const begin = useMutation({ mutationFn: (v: AccountValues) => beginActivation({ ...credentials, email: v.email, newPassword: v.newPassword }) })
  const {
    register,
    handleSubmit,
    setError,
    control,
    formState: { errors },
  } = useForm<AccountValues>({
    resolver: zodResolver(accountSchema),
    defaultValues: { email: check.email ?? '', newPassword: '', confirm: '' },
  })
  const newPassword = useWatch({ control, name: 'newPassword' })
  const name = [check.firstName, check.lastName].filter(Boolean).join(' ')
  const emailLocked = check.emailVerified && !!check.email

  return (
    <AuthLayout
      title="Set up your sign-in"
      description={`Step 2 of 2.${name ? ` Welcome, ${name}.` : ''} Choose the email address you will sign in with and a new password.`}
    >
      <form
        noValidate
        className="flex flex-col gap-4"
        onSubmit={handleSubmit((values) => {
          setFormError(null)
          begin.mutate(values, {
            onSuccess: (result) => onDone(result.status, values.email),
            onError: (error) => setFormError(applyServerErrors(error, setError, ['email', 'newPassword'])),
          })
        })}
      >
        {formError && (
          <Alert variant="destructive">
            <AlertDescription>{formError}</AlertDescription>
          </Alert>
        )}
        <TextField
          label="Email"
          type="email"
          autoComplete="email"
          inputMode="email"
          readOnly={emailLocked}
          hint={
            emailLocked
              ? 'This address is already verified.'
              : 'We will email a verification link to this address. Use an address you can open now.'
          }
          error={errors.email?.message}
          {...register('email')}
        />
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
        <div className="flex flex-wrap gap-2">
          <Button type="submit" disabled={begin.isPending}>
            {begin.isPending ? 'Saving...' : 'Activate account'}
          </Button>
          <Button type="button" variant="ghost" onClick={onBack} disabled={begin.isPending}>
            Back
          </Button>
        </div>
      </form>
    </AuthLayout>
  )
}
