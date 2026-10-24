import { CheckboxField } from '@/components/common/CheckboxField'
import { WORKSPACE_CAPABILITIES } from '@/lib/capabilities'
import { capabilityLabel } from '@/lib/roles'

/**
 * The permission checkboxes for one Coordinator. Someone who is not an Administrator can only hand out permissions they
 * hold themselves, so the others are shown but disabled (the API enforces the same rule).
 */
export function CapabilityChecklist({
  legend,
  value,
  onChange,
  grantable,
  disabled,
}: {
  legend: string
  value: string[]
  onChange: (next: string[]) => void
  /** Codes the acting person may grant; `null` means all (Administrators). */
  grantable: readonly string[] | null
  disabled?: boolean
}) {
  const toggle = (code: string, on: boolean) => onChange(on ? [...value, code] : value.filter((c) => c !== code))
  return (
    <fieldset className="flex flex-col gap-3 rounded-lg border p-4">
      <legend className="px-1 text-sm font-medium">{legend}</legend>
      {WORKSPACE_CAPABILITIES.map(({ code, help }) => {
        const mayGrant = grantable === null || grantable.includes(code)
        const checked = value.includes(code)
        return (
          <CheckboxField
            key={code}
            label={capabilityLabel(code)}
            hint={mayGrant ? help : `${help} You do not hold this permission yourself, so you cannot grant it.`}
            checked={checked}
            // A permission that was already granted can still be taken away even if the actor lacks it; granting needs it.
            disabled={disabled || (!mayGrant && !checked)}
            onChange={(e) => toggle(code, e.target.checked)}
          />
        )
      })}
    </fieldset>
  )
}
