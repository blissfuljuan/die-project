import { useState } from 'react'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'

/**
 * A temporary credential, shown once. The API never returns it again, so the person is told to hand it over now. It is
 * kept out of the page title, the URL and the query cache; leaving the page drops it.
 */
export function CredentialReveal({
  who,
  secret,
  expiresAt,
  onDone,
}: {
  who: string
  secret: string
  expiresAt?: string | null
  onDone: () => void
}) {
  const [copied, setCopied] = useState<'yes' | 'no' | null>(null)
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(secret)
      setCopied('yes')
    } catch {
      setCopied('no')
    }
  }
  return (
    <Alert role="status" className="border-primary/40">
      <AlertDescription>
        <p className="font-medium">New temporary credential for {who}</p>
        <p className="mt-2">
          <code className="rounded bg-muted px-2 py-1 font-mono text-base break-all">{secret}</code>
        </p>
        <p className="mt-2 text-muted-foreground">
          Shown only once. Give it to {who} now; they use it with their Student ID to activate the account.
          {expiresAt ? ` It expires on ${new Date(expiresAt).toLocaleDateString()}.` : ''}
        </p>
        <div className="mt-3 flex flex-wrap gap-2">
          <Button type="button" variant="outline" size="sm" onClick={() => void copy()}>
            Copy credential
          </Button>
          <Button type="button" size="sm" onClick={onDone}>
            I have handed it over
          </Button>
        </div>
        {copied === 'yes' && <p className="mt-2 text-sm">Copied.</p>}
        {copied === 'no' && <p className="mt-2 text-sm text-destructive">Copying is not available here. Select the text above and copy it.</p>}
      </AlertDescription>
    </Alert>
  )
}
