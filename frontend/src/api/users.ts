import { z } from 'zod'
import { apiClient, type ApiClient } from './client'

/** The few account fields the workspace screens need (Administrators only: `/users` is an admin endpoint). */
export const userSummarySchema = z.object({
  id: z.string(),
  role: z.string(),
  status: z.string(),
  firstName: z.string(),
  lastName: z.string(),
  email: z.string().nullish(),
})
export type UserSummary = z.infer<typeof userSummarySchema>

const userPageSchema = z.object({ items: z.array(userSummarySchema), page: z.number(), size: z.number(), total: z.number() })

/** Active Coordinator accounts matching `q`, for an Administrator choosing a workspace Owner. */
export const searchCoordinatorAccounts = (q: string, signal?: AbortSignal, client: ApiClient = apiClient) =>
  client.get<UserSummary[]>(`/users?role=COORDINATOR&status=ACTIVE&size=10&q=${encodeURIComponent(q)}`, {
    signal,
    parse: (d) => userPageSchema.parse(d).items,
  })
