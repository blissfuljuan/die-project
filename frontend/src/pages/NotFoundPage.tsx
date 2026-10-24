import { NotFoundBody } from './ForbiddenPage'

export function NotFoundPage() {
  return (
    <main className="mx-auto w-full max-w-5xl px-4">
      <NotFoundBody title="Not found, or no access" />
    </main>
  )
}
