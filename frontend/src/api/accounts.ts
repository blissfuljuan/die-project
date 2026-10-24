import { z } from 'zod'
import { apiClient, type ApiClient } from './client'

export const ACCOUNT_ROLES = ['PARTICIPANT', 'COORDINATOR', 'ADMIN'] as const
export type AccountRole = (typeof ACCOUNT_ROLES)[number]
export const ACCOUNT_STATUSES = ['ACTIVE', 'PENDING_ACTIVATION', 'DEACTIVATED'] as const

export const accountSchema = z.object({
  id: z.string(),
  role: z.string(),
  status: z.string(),
  studentNumber: z.string().nullish(),
  lastName: z.string(),
  firstName: z.string(),
  middleName: z.string().nullish(),
  email: z.string().nullish(),
  emailVerified: z.boolean().default(false),
  createdAt: z.string().nullish(),
  version: z.number(),
})
export type Account = z.infer<typeof accountSchema>

export const accountPageSchema = z.object({ items: z.array(accountSchema), page: z.number(), size: z.number(), total: z.number() })
export type AccountPage = z.infer<typeof accountPageSchema>

/** What the API returns when an operation may have issued a one-time temporary credential. */
export const accountChangeSchema = z.object({
  user: accountSchema,
  temporaryCredential: z.string().nullish(),
  temporaryCredentialExpiresAt: z.string().nullish(),
})
export type AccountChange = z.infer<typeof accountChangeSchema>

export interface AccountQuery {
  q: string
  role: string
  status: string
  page: number
  size: number
}

export const fetchAccounts = (query: AccountQuery, signal?: AbortSignal, client: ApiClient = apiClient) => {
  const params = new URLSearchParams({ page: String(query.page), size: String(query.size) })
  if (query.q) params.set('q', query.q)
  if (query.role) params.set('role', query.role)
  if (query.status) params.set('status', query.status)
  return client.get<AccountPage>(`/users?${params}`, { signal, parse: (d) => accountPageSchema.parse(d) })
}

/** Participants are identified by Student ID, staff by email; the API checks the combination. */
export interface NewAccount {
  role: AccountRole
  lastName: string
  firstName: string
  middleName?: string
  studentNumber?: string
  email?: string
}

export const createAccount = (input: NewAccount, client: ApiClient = apiClient) =>
  client.post<AccountChange>('/users', input, { parse: (d) => accountChangeSchema.parse(d) })

const user = (id: string) => `/users/${encodeURIComponent(id)}`

export const deactivateAccount = (id: string, client: ApiClient = apiClient) =>
  client.post<Account>(`${user(id)}/deactivate`, undefined, { parse: (d) => accountSchema.parse(d) })

/** A deactivated account that never finished activating gets a new temporary credential in the answer. */
export const activateAccount = (id: string, client: ApiClient = apiClient) =>
  client.post<AccountChange>(`${user(id)}/activate`, undefined, { parse: (d) => accountChangeSchema.parse(d) })

/** Clears the password, signs the person out everywhere and returns a new temporary credential (shown once). */
export const resetAccountCredentials = (id: string, client: ApiClient = apiClient) =>
  client.post<AccountChange>(`${user(id)}/reset-credentials`, undefined, { parse: (d) => accountChangeSchema.parse(d) })
