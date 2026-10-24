import { Component, type ErrorInfo, type ReactNode } from 'react'
import { Button } from '@/components/ui/button'

interface State {
  failed: boolean
}

/** Last line of defence: a rendering bug shows a calm message instead of a blank page. */
export class ErrorBoundary extends Component<{ children: ReactNode }, State> {
  state: State = { failed: false }

  static getDerivedStateFromError(): State {
    return { failed: true }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('Unhandled UI error', error, info.componentStack)
  }

  render() {
    if (!this.state.failed) return this.props.children
    return (
      <main className="mx-auto flex min-h-screen max-w-md flex-col items-start justify-center gap-4 px-4">
        <h1 className="text-2xl font-bold">Something went wrong</h1>
        <p className="text-muted-foreground">
          This page could not be shown. Your data is safe. Reload the page; if it keeps happening, tell your
          Coordinator or an Administrator.
        </p>
        <Button type="button" onClick={() => window.location.reload()}>
          Reload the page
        </Button>
      </main>
    )
  }
}
