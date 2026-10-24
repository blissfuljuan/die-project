import * as React from 'react'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'

export interface TextFieldProps extends React.InputHTMLAttributes<HTMLInputElement> {
  label: string
  error?: string
  hint?: React.ReactNode
}

/** Label + input + hint + error, wired with aria-describedby / aria-invalid. Works with react-hook-form's register(). */
export const TextField = React.forwardRef<HTMLInputElement, TextFieldProps>(
  ({ label, error, hint, id, ...props }, ref) => {
    const auto = React.useId()
    const inputId = id ?? auto
    const hintId = `${inputId}-hint`
    const errorId = `${inputId}-error`
    const describedBy = [hint ? hintId : null, error ? errorId : null].filter(Boolean).join(' ') || undefined
    return (
      <div className="flex flex-col gap-1.5">
        <Label htmlFor={inputId}>{label}</Label>
        <Input ref={ref} id={inputId} aria-invalid={error ? true : undefined} aria-describedby={describedBy} {...props} />
        {hint && (
          <div id={hintId} className="text-xs text-muted-foreground">
            {hint}
          </div>
        )}
        {error && (
          <p id={errorId} className="text-sm text-destructive">
            {error}
          </p>
        )}
      </div>
    )
  },
)
TextField.displayName = 'TextField'
