import { QueryClientProvider } from '@tanstack/react-query'
import { useState } from 'react'
import { ErrorBoundary } from '@/components/common/ErrorBoundary'
import { createQueryClient } from '@/lib/queryClient'
import { AppRoutes } from '@/routes/AppRoutes'

export function App() {
  const [queryClient] = useState(createQueryClient)
  return (
    <ErrorBoundary>
      <QueryClientProvider client={queryClient}>
        <AppRoutes />
      </QueryClientProvider>
    </ErrorBoundary>
  )
}
