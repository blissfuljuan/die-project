import { zodResolver } from '@hookform/resolvers/zod'
import { useState, type ReactNode } from 'react'
import { useForm } from 'react-hook-form'
import { z } from 'zod'
import type { WorkspaceSettings } from '@/api'
import { CheckboxField } from '@/components/common/CheckboxField'
import { TextField } from '@/components/common/TextField'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import { Select } from '@/components/ui/select'
import { applyServerErrors } from '@/lib/forms'
import { teamFormationLabel } from './labels'

const text = (what: string) => z.string().trim().min(1, `Enter ${what}.`).max(100, 'Use at most 100 characters.')
const size = z
  .number({ error: 'Enter a whole number.' })
  .int('Enter a whole number.')
  .min(1, 'Use at least 1.')
  .max(50, 'Use at most 50.')

export const workspaceSettingsSchema = z
  .object({
    academicYear: text('the academic year'),
    semester: text('the semester'),
    subjectCode: text('the subject code'),
    subjectName: text('the subject name'),
    section: text('the section'),
    teamFormation: z.enum(['COORDINATOR_ASSIGNED', 'PARTICIPANT_FORMED']),
    teamMinSize: size,
    teamMaxSize: size,
    participantsMayChangeTeams: z.boolean(),
  })
  .refine((v) => v.teamMaxSize >= v.teamMinSize, {
    path: ['teamMaxSize'],
    message: 'The maximum cannot be smaller than the minimum.',
  })
type Values = z.infer<typeof workspaceSettingsSchema>

export const DEFAULT_SETTINGS: WorkspaceSettings = {
  academicYear: '',
  semester: '',
  subjectCode: '',
  subjectName: '',
  section: '',
  teamFormation: 'COORDINATOR_ASSIGNED',
  teamMinSize: 1,
  teamMaxSize: 5,
  participantsMayChangeTeams: false,
}

const FIELDS = [
  'academicYear',
  'semester',
  'subjectCode',
  'subjectName',
  'section',
  'teamFormation',
  'teamMinSize',
  'teamMaxSize',
  'participantsMayChangeTeams',
] as const

/**
 * The settings of a Project Workspace (shared by "create" and "edit"). `children` is extra content placed above the
 * buttons (the Administrator's Owner picker). Validation here is for convenience; the API validates again and its
 * field errors are shown next to the matching inputs.
 */
export function WorkspaceForm({
  initial = DEFAULT_SETTINGS,
  submitLabel,
  pending,
  onSubmit,
  onCancel,
  children,
}: {
  initial?: WorkspaceSettings
  submitLabel: string
  pending: boolean
  /** Throw (reject) to show the API's message; field errors are attached to their inputs. */
  onSubmit: (values: WorkspaceSettings) => Promise<unknown>
  onCancel: () => void
  children?: ReactNode
}) {
  const [formError, setFormError] = useState<string | null>(null)
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<Values>({ resolver: zodResolver(workspaceSettingsSchema), defaultValues: initial })

  const submit = handleSubmit(async (values) => {
    setFormError(null)
    try {
      await onSubmit(values)
    } catch (error) {
      setFormError(applyServerErrors(error, setError, FIELDS))
    }
  })

  return (
    <form noValidate onSubmit={submit} className="flex max-w-2xl flex-col gap-5">
      {formError && (
        <Alert variant="destructive">
          <AlertDescription>{formError}</AlertDescription>
        </Alert>
      )}
      <div className="grid gap-4 sm:grid-cols-2">
        <TextField label="Academic year" placeholder="2026-2027" error={errors.academicYear?.message} {...register('academicYear')} />
        <TextField label="Semester" placeholder="1st" error={errors.semester?.message} {...register('semester')} />
        <TextField label="Subject code" placeholder="CS101" error={errors.subjectCode?.message} {...register('subjectCode')} />
        <TextField label="Section" placeholder="A" error={errors.section?.message} {...register('section')} />
      </div>
      <TextField label="Subject name" error={errors.subjectName?.message} {...register('subjectName')} />

      <fieldset className="flex flex-col gap-4 rounded-lg border p-4">
        <legend className="px-1 text-sm font-medium">Teams</legend>
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="ws-team-formation">How teams are formed</Label>
          <Select id="ws-team-formation" {...register('teamFormation')}>
            <option value="COORDINATOR_ASSIGNED">{teamFormationLabel('COORDINATOR_ASSIGNED')}</option>
            <option value="PARTICIPANT_FORMED">{teamFormationLabel('PARTICIPANT_FORMED')}</option>
          </Select>
        </div>
        <div className="grid gap-4 sm:grid-cols-2">
          <TextField
            label="Smallest team"
            type="number"
            inputMode="numeric"
            min={1}
            max={50}
            error={errors.teamMinSize?.message}
            {...register('teamMinSize', { valueAsNumber: true })}
          />
          <TextField
            label="Largest team"
            type="number"
            inputMode="numeric"
            min={1}
            max={50}
            error={errors.teamMaxSize?.message}
            {...register('teamMaxSize', { valueAsNumber: true })}
          />
        </div>
        <CheckboxField
          label="Participants may change teams"
          hint="Lets a Participant leave a team on their own."
          {...register('participantsMayChangeTeams')}
        />
      </fieldset>

      {children}

      <div className="flex flex-wrap gap-3">
        <Button type="submit" disabled={pending}>
          {pending ? 'Saving...' : submitLabel}
        </Button>
        <Button type="button" variant="outline" onClick={onCancel} disabled={pending}>
          Cancel
        </Button>
      </div>
    </form>
  )
}
