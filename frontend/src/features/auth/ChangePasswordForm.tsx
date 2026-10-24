import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm, useWatch } from 'react-hook-form'
import { z } from 'zod'
import { changePassword } from '@/api'
import { PasswordStrengthHint } from '@/components/common/PasswordStrengthHint'
import { TextField } from '@/components/common/TextField'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { applyServerErrors } from '@/lib/forms'
import { passwordSchema } from '@/lib/password'

const schema = z
  .object({
    currentPassword: z.string().min(1, 'Enter your current password.'),
    newPassword: passwordSchema,
    confirm: z.string(),
  })
  .refine((v) => v.newPassword === v.confirm, { path: ['confirm'], message: 'The two passwords do not match.' })
  .refine((v) => v.newPassword !== v.currentPassword, {
    path: ['newPassword'],
    message: 'Choose a password different from the current one.',
  })
type Values = z.infer<typeof schema>

export function ChangePasswordForm() {
  const [formError, setFormError] = useState<string | null>(null)
  const change = useMutation({ mutationFn: (input: { currentPassword: string; newPassword: string }) => changePassword(input) })
  const {
    register,
    handleSubmit,
    setError,
    reset,
    control,
    formState: { errors },
  } = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: { currentPassword: '', newPassword: '', confirm: '' },
  })
  const newPassword = useWatch({ control, name: 'newPassword' })

  return (
    <form
      noValidate
      className="flex max-w-md flex-col gap-4"
      onSubmit={handleSubmit((v) => {
        setFormError(null)
        change.mutate(
          { currentPassword: v.currentPassword, newPassword: v.newPassword },
          {
            onSuccess: () => reset(),
            onError: (error) => setFormError(applyServerErrors(error, setError, ['currentPassword', 'newPassword'])),
          },
        )
      })}
    >
      {change.isSuccess && (
        <Alert role="status">
          <AlertDescription>
            Your password has been changed. Your other signed-in devices were signed out.
          </AlertDescription>
        </Alert>
      )}
      {formError && (
        <Alert variant="destructive">
          <AlertDescription>{formError}</AlertDescription>
        </Alert>
      )}
      <TextField
        label="Current password"
        type="password"
        autoComplete="current-password"
        error={errors.currentPassword?.message}
        {...register('currentPassword')}
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
      <div>
        <Button type="submit" disabled={change.isPending}>
          {change.isPending ? 'Saving...' : 'Change password'}
        </Button>
      </div>
    </form>
  )
}
