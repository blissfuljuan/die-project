import * as React from 'react'

export interface CheckboxFieldProps extends Omit<React.InputHTMLAttributes<HTMLInputElement>, 'type'> {
  label: string
  hint?: React.ReactNode
}

/** A native checkbox with its label (and optional hint) wired together. Works with react-hook-form's register(). */
export const CheckboxField = React.forwardRef<HTMLInputElement, CheckboxFieldProps>(({ label, hint, id, ...props }, ref) => {
  const auto = React.useId()
  const inputId = id ?? auto
  const hintId = `${inputId}-hint`
  return (
    <div className="flex items-start gap-2">
      <input
        ref={ref}
        id={inputId}
        type="checkbox"
        aria-describedby={hint ? hintId : undefined}
        className="mt-1 size-4 shrink-0 rounded border-input accent-primary disabled:opacity-60"
        {...props}
      />
      <div className="text-sm">
        <label htmlFor={inputId} className="font-medium">
          {label}
        </label>
        {hint && (
          <div id={hintId} className="text-muted-foreground">
            {hint}
          </div>
        )}
      </div>
    </div>
  )
})
CheckboxField.displayName = 'CheckboxField'
