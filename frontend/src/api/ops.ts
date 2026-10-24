import { z } from 'zod'
import { apiClient, type ApiClient } from './client'

const dependencySchema = z.object({
  status: z.string(),
  responseMillis: z.number().nullish(),
  reason: z.string().nullish(),
})

export const platformHealthSchema = z.object({
  status: z.string(),
  checkedAt: z.string(),
  database: dependencySchema,
  broker: dependencySchema,
  workers: z.array(
    z.object({
      name: z.string(),
      status: z.string(),
      heartbeatAgeSeconds: z.number().nullish(),
      heartbeatFresh: z.boolean(),
    }),
  ),
  outbox: z.object({ pending: z.number(), failed: z.number(), oldestPendingSeconds: z.number().nullish() }),
  testJobs: z.object({
    queued: z.number(),
    running: z.number(),
    succeeded: z.number(),
    failed: z.number(),
    overdue: z.number(),
  }),
  queueDepths: z.record(z.string(), z.number()).default({}),
})
export type PlatformHealth = z.infer<typeof platformHealthSchema>

export const fetchPlatformHealth = (signal?: AbortSignal, client: ApiClient = apiClient) =>
  client.get<PlatformHealth>('/ops/health', { signal, parse: (d) => platformHealthSchema.parse(d) })
