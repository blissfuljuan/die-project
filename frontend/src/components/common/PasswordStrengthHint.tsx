import { passwordStrength } from '@/lib/password'

const COLOR = ['bg-destructive', 'bg-amber-500', 'bg-success', 'bg-success']

/** Policy reminder plus a length meter. Purely advisory: the server enforces the policy. */
export function PasswordStrengthHint({ value }: { value: string }) {
  const s = passwordStrength(value)
  const filled = s.level === 0 ? 1 : s.level
  return (
    <div>
      <p>Use 12 to 128 characters. A long phrase is better than symbols. Do not reuse your email or Student ID.</p>
      {value.length > 0 && (
        <div className="mt-2 flex items-center gap-2" aria-live="polite">
          <div className="flex h-1.5 w-24 gap-0.5" aria-hidden="true">
            {[0, 1, 2].map((i) => (
              <span key={i} className={`h-full flex-1 rounded ${i < filled ? COLOR[s.level] : 'bg-muted'}`} />
            ))}
          </div>
          <span>Password strength: {s.label}</span>
        </div>
      )}
    </div>
  )
}
