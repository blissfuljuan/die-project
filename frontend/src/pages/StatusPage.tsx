import { Link } from 'react-router-dom'
import { ConnectionStatusCard } from '@/features/status/ConnectionStatusCard'

export function StatusPage() {
  return (
    <main className="mx-auto flex min-h-screen w-full max-w-2xl flex-col gap-6 px-4 py-8 sm:py-12">
      <header>
        <h1 className="text-2xl font-bold tracking-tight sm:text-3xl">DIE</h1>
        <p className="text-muted-foreground">Document-Implementation Evaluator</p>
      </header>
      <ConnectionStatusCard />
      <p className="text-sm">
        <Link to="/" className="underline underline-offset-2">
          Back to DIE
        </Link>
      </p>
    </main>
  )
}
