import { useQuery } from '@tanstack/react-query'
import { useEffect, useId, useState } from 'react'
import { friendlyMessage } from '@/api'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { fullName } from '@/lib/roles'

export interface PersonOption {
  id: string
  firstName: string
  lastName: string
  email?: string | null
}

export const MIN_SEARCH_CHARS = 3
const DEBOUNCE_MS = 250

/**
 * Find-a-person control: type at least three characters, pick from the few matches. The search itself is a prop so the same
 * control serves "choose the Owner" (Administrators search accounts) and "add a Coordinator" (candidates of one workspace).
 * Nothing is listed until something is typed, and the API caps the results, so this never shows a whole directory.
 */
export function PersonPicker({
  label,
  queryKey,
  search,
  value,
  onChange,
  error,
  disabled,
}: {
  label: string
  /** Distinguishes this picker's searches in the query cache. */
  queryKey: readonly unknown[]
  search: (q: string, signal: AbortSignal) => Promise<PersonOption[]>
  value: PersonOption | null
  onChange: (person: PersonOption | null) => void
  error?: string
  disabled?: boolean
}) {
  const inputId = useId()
  const [typed, setTyped] = useState('')
  const [q, setQ] = useState('')
  useEffect(() => {
    const t = setTimeout(() => setQ(typed.trim()), DEBOUNCE_MS)
    return () => clearTimeout(t)
  }, [typed])

  const enabled = !value && q.length >= MIN_SEARCH_CHARS
  const results = useQuery({
    queryKey: [...queryKey, q],
    queryFn: ({ signal }) => search(q, signal),
    enabled,
  })

  if (value) {
    return (
      <div className="flex flex-col gap-1.5">
        <span className="text-sm font-medium">{label}</span>
        <div className="flex flex-wrap items-center gap-3 rounded-md border bg-card p-3 text-sm">
          <span className="min-w-0 break-words">
            <span className="font-medium">{fullName(value)}</span>
            {value.email ? <span className="text-muted-foreground"> · {value.email}</span> : null}
          </span>
          <Button type="button" variant="outline" size="sm" disabled={disabled} onClick={() => onChange(null)}>
            Change
          </Button>
        </div>
        {error && <p className="text-sm text-destructive">{error}</p>}
      </div>
    )
  }

  return (
    <div className="flex flex-col gap-1.5">
      <Label htmlFor={inputId}>{label}</Label>
      <Input
        id={inputId}
        type="search"
        autoComplete="off"
        placeholder="Type a name or email (at least 3 letters)"
        value={typed}
        disabled={disabled}
        aria-invalid={error ? true : undefined}
        onChange={(e) => setTyped(e.target.value)}
      />
      {error && <p className="text-sm text-destructive">{error}</p>}
      <div aria-live="polite" className="text-sm">
        {typed.trim().length > 0 && typed.trim().length < MIN_SEARCH_CHARS && (
          <p className="text-muted-foreground">Keep typing: at least {MIN_SEARCH_CHARS} characters.</p>
        )}
        {enabled && results.isPending && !results.error && <p className="text-muted-foreground">Searching...</p>}
        {enabled && results.error && <p className="text-destructive">{friendlyMessage(results.error)}</p>}
        {enabled && results.data && results.data.length === 0 && <p className="text-muted-foreground">No matching Coordinator found.</p>}
        {enabled && results.data && results.data.length > 0 && (
          <ul aria-label="Matches" className="flex flex-col gap-1">
            {results.data.map((p) => (
              <li key={p.id}>
                <button
                  type="button"
                  className="flex w-full flex-wrap items-baseline gap-x-2 rounded-md border bg-card px-3 py-2 text-left hover:bg-accent focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                  onClick={() => {
                    onChange(p)
                    setTyped('')
                    setQ('')
                  }}
                >
                  <span className="font-medium">{fullName(p)}</span>
                  {p.email && <span className="text-muted-foreground">{p.email}</span>}
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  )
}
