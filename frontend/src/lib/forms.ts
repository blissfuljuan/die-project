import type { FieldValues, Path, UseFormSetError } from 'react-hook-form'
import { FIELD_FOR_CODE, friendlyMessage, isApiError } from '@/api'

/**
 * Puts the backend's `errors[]` (and field-specific error codes) next to the matching inputs.
 * Returns the message to show as a form-level alert when nothing could be attached to a field.
 */
export function applyServerErrors<T extends FieldValues>(
  error: unknown,
  setError: UseFormSetError<T>,
  fields: readonly Path<T>[],
): string | null {
  let attached = false
  if (isApiError(error)) {
    for (const e of error.errors) {
      const field = fields.find((f) => f === e.field)
      if (field) {
        setError(field, { type: 'server', message: e.message })
        attached = true
      }
    }
    const byCode = FIELD_FOR_CODE[error.code]
    const field = byCode ? fields.find((f) => f === byCode) : undefined
    if (field && !attached) {
      setError(field, { type: 'server', message: friendlyMessage(error) })
      attached = true
    }
  }
  return attached ? null : friendlyMessage(error)
}
