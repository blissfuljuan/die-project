import { PageHeader } from '@/components/common/PageHeader'
import { Badge } from '@/components/ui/badge'

/** Target of a navigation entry whose feature belongs to a later slice. */
export function ComingSoonPage({ title }: { title: string }) {
  return (
    <>
      <PageHeader title={title} actions={<Badge variant="outline">Coming soon</Badge>} />
      <p className="text-muted-foreground">This area is planned but not available yet.</p>
    </>
  )
}
