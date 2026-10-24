import { Button } from '@/components/ui/button'

/** Previous / next for a server-paged list (page is 0-based; `total` is the whole count). */
export function Pagination({
  page,
  size,
  total,
  onPage,
  busy,
}: {
  page: number
  size: number
  total: number
  onPage: (page: number) => void
  busy?: boolean
}) {
  const pages = Math.max(1, Math.ceil(total / size))
  if (pages <= 1) return null
  return (
    <nav aria-label="Pages" className="flex flex-wrap items-center justify-between gap-3 text-sm">
      <span aria-live="polite">
        Page {page + 1} of {pages} · {total} in total
      </span>
      <div className="flex gap-2">
        <Button type="button" variant="outline" size="sm" disabled={busy || page <= 0} onClick={() => onPage(page - 1)}>
          Previous
        </Button>
        <Button type="button" variant="outline" size="sm" disabled={busy || page >= pages - 1} onClick={() => onPage(page + 1)}>
          Next
        </Button>
      </div>
    </nav>
  )
}
