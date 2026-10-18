import { z } from 'zod'
import { apiClient, type ApiClient } from './client'

export const statusSchema = z.object({
  application: z.string(),
  apiVersion: z.string(),
  buildVersion: z.string(),
  status: z.string(),
  time: z.string().refine((v) => !Number.isNaN(Date.parse(v)), 'time must be an ISO date-time'),
})

export type ApiStatus = z.infer<typeof statusSchema>

export function fetchStatus(signal?: AbortSignal, client: ApiClient = apiClient): Promise<ApiStatus> {
  return client.get<ApiStatus>('/status', { signal, parse: (d) => statusSchema.parse(d) })
}
