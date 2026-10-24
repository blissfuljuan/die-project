import { zodResolver } from '@hookform/resolvers/zod'
import { useState } from 'react'
import { useForm, useWatch } from 'react-hook-form'
import { z } from 'zod'
import { ACCOUNT_ROLES, type AccountChange } from '@/api'
import { TextField } from '@/components/common/TextField'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Label } from '@/components/ui/label'
import { Select } from '@/components/ui/select'
import { applyServerErrors } from '@/lib/forms'
import { useCreateAccount } from './queries'

const name = (what: string) => z.string().trim().min(1, `Enter ${what}.`).max(100, 'Use at most 100 characters.')

// A Participant is identified by Student ID, a Coordinator or Administrator by email (the API checks the same rule).
const schema = z
  .object({
    role: z.enum(ACCOUNT_ROLES),
    lastName: name('the last name'),
    firstName: name('the first name'),
    middleName: z.string().trim().max(100, 'Use at most 100 characters.'),
    studentNumber: z.string().trim().max(64, 'Use at most 64 characters.'),
    email: z.string().trim().max(254, 'Use at most 254 characters.'),
  })
  .superRefine((v, ctx) => {
    if (v.role === 'PARTICIPANT') {
      if (!v.studentNumber) ctx.addIssue({ code: 'custom', path: ['studentNumber'], message: 'Enter the Student ID.' })
    } else if (!v.email) {
      ctx.addIssue({ code: 'custom', path: ['email'], message: 'Enter the email address.' })
    } else if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(v.email)) {
      ctx.addIssue({ code: 'custom', path: ['email'], message: 'Enter a valid email address.' })
    }
  })
type Values = z.infer<typeof schema>

const FIELDS = ['role', 'lastName', 'firstName', 'middleName', 'studentNumber', 'email'] as const

/** New account. The temporary credential in the answer is shown once by the page; this card never keeps it. */
export function CreateAccountCard({ onCancel, onCreated }: { onCancel: () => void; onCreated: (change: AccountChange) => void }) {
  const create = useCreateAccount()
  const [formError, setFormError] = useState<string | null>(null)
  const {
    register,
    handleSubmit,
    setError,
    control,
    formState: { errors },
  } = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: { role: 'PARTICIPANT', lastName: '', firstName: '', middleName: '', studentNumber: '', email: '' },
  })
  const role = useWatch({ control, name: 'role' })

  const submit = handleSubmit((v) => {
    setFormError(null)
    create.mutate(
      {
        role: v.role,
        lastName: v.lastName,
        firstName: v.firstName,
        ...(v.middleName ? { middleName: v.middleName } : {}),
        ...(v.role === 'PARTICIPANT' ? { studentNumber: v.studentNumber } : { email: v.email }),
      },
      {
        onSuccess: onCreated,
        onError: (error) => setFormError(applyServerErrors(error, setError, FIELDS)),
      },
    )
  })

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-base">New account</CardTitle>
        <CardDescription>
          The person gets a temporary credential, shown once after you create the account. They use it to activate the account and choose
          a password.
        </CardDescription>
      </CardHeader>
      <CardContent>
        <form noValidate onSubmit={submit} className="flex max-w-2xl flex-col gap-4">
          {formError && (
            <Alert variant="destructive">
              <AlertDescription>{formError}</AlertDescription>
            </Alert>
          )}
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="new-role">Role</Label>
            <Select id="new-role" {...register('role')}>
              <option value="PARTICIPANT">Participant</option>
              <option value="COORDINATOR">Coordinator</option>
              <option value="ADMIN">Administrator</option>
            </Select>
          </div>
          <div className="grid gap-4 sm:grid-cols-2">
            <TextField label="First name" error={errors.firstName?.message} {...register('firstName')} />
            <TextField label="Last name" error={errors.lastName?.message} {...register('lastName')} />
            <TextField label="Middle name (optional)" error={errors.middleName?.message} {...register('middleName')} />
            {role === 'PARTICIPANT' ? (
              <TextField label="Student ID" error={errors.studentNumber?.message} {...register('studentNumber')} />
            ) : (
              <TextField label="Email" type="email" autoComplete="off" error={errors.email?.message} {...register('email')} />
            )}
          </div>
          <div className="flex flex-wrap gap-3">
            <Button type="submit" disabled={create.isPending}>
              {create.isPending ? 'Creating...' : 'Create account'}
            </Button>
            <Button type="button" variant="outline" onClick={onCancel} disabled={create.isPending}>
              Cancel
            </Button>
          </div>
        </form>
      </CardContent>
    </Card>
  )
}
