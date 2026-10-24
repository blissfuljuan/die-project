import { Link } from 'react-router-dom'
import { Button } from '@/components/ui/button'

/** Deliberately vague: "not found or no access" never confirms that something exists. */
export function ForbiddenPage() {
  return <NotFoundBody title="Not found, or no access" />
}

export function NotFoundBody({ title }: { title: string }) {
  return (
    <div className="flex max-w-md flex-col items-start gap-4 py-10">
      <h1 className="text-2xl font-bold tracking-tight">{title}</h1>
      <p className="text-muted-foreground">
        This page does not exist, or your account does not have access to it. If you think this is a mistake, ask your
        Coordinator.
      </p>
      <Button asChild>
        <Link to="/">Back to my home page</Link>
      </Button>
    </div>
  )
}
