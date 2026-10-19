import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'

/** Shell for the public screens (sign in, activation, password reset). */
export function AuthLayout({ title, description, children }: { title: string; description?: string; children: ReactNode }) {
  return (
    <div className="mx-auto flex min-h-screen w-full max-w-md flex-col px-4 py-8 sm:py-12">
      <a href="#main" className="sr-only focus:not-sr-only focus:mb-2">
        Skip to content
      </a>
      <div className="mb-6">
        <p className="text-sm font-semibold tracking-wide text-muted-foreground">DIE</p>
        <p className="text-xs text-muted-foreground">Document-Implementation Evaluator</p>
      </div>
      <main id="main" className="flex-1">
        <h1 className="text-2xl font-bold tracking-tight">{title}</h1>
        {description && <p className="mt-1 mb-6 text-muted-foreground">{description}</p>}
        {!description && <div className="mb-6" />}
        {children}
      </main>
      <footer className="mt-8 text-center text-xs text-muted-foreground">
        <Link to="/status" className="underline underline-offset-2">
          System status
        </Link>
      </footer>
    </div>
  )
}
