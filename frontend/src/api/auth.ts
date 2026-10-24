import { z } from 'zod'
import { apiClient, type ApiClient } from './client'
import { isApiError } from './errors'

export const ROLES = ['PARTICIPANT', 'COORDINATOR', 'ADMIN'] as const
export type Role = (typeof ROLES)[number]

export const meSchema = z.object({
  id: z.string(),
  email: z.string(),
  firstName: z.string(),
  lastName: z.string(),
  role: z.enum(ROLES),
})
export type Me = z.infer<typeof meSchema>

export const activationCheckSchema = z.object({
  firstName: z.string().nullish(),
  lastName: z.string().nullish(),
  email: z.string().nullish(),
  emailVerified: z.boolean(),
})
export type ActivationCheck = z.infer<typeof activationCheckSchema>

export const activationStatusSchema = z.object({ status: z.string() })
export type ActivationStatus = z.infer<typeof activationStatusSchema>

const messageSchema = z.object({ message: z.string() })

export interface LoginInput {
  email: string
  password: string
}
export interface ActivationCredentials {
  identifier: string
  temporaryCredential: string
}
export interface ActivationBegin extends ActivationCredentials {
  email: string
  newPassword: string
}

/** Current session, or `null` when signed out (401). Any other failure is thrown. */
export async function fetchMe(signal?: AbortSignal, client: ApiClient = apiClient): Promise<Me | null> {
  try {
    return await client.get<Me>('/auth/me', { signal, parse: (d) => meSchema.parse(d) })
  } catch (error) {
    if (isApiError(error) && error.status === 401) return null
    throw error
  }
}

export const login = (input: LoginInput, client: ApiClient = apiClient) =>
  client.post<Me>('/auth/login', input, { parse: (d) => meSchema.parse(d) })

export const logout = (client: ApiClient = apiClient) => client.post<void>('/auth/logout')

export const checkActivation = (input: ActivationCredentials, client: ApiClient = apiClient) =>
  client.post<ActivationCheck>('/auth/activation/check', input, { parse: (d) => activationCheckSchema.parse(d) })

export const beginActivation = (input: ActivationBegin, client: ApiClient = apiClient) =>
  client.post<ActivationStatus>('/auth/activation/begin', input, { parse: (d) => activationStatusSchema.parse(d) })

export const verifyEmail = (token: string, client: ApiClient = apiClient) =>
  client.post<ActivationStatus>('/auth/activation/verify', { token }, { parse: (d) => activationStatusSchema.parse(d) })

export const requestPasswordReset = (email: string, client: ApiClient = apiClient) =>
  client.post<{ message: string }>('/auth/password-reset/request', { email }, { parse: (d) => messageSchema.parse(d) })

export const confirmPasswordReset = (token: string, newPassword: string, client: ApiClient = apiClient) =>
  client.post<{ status: string }>('/auth/password-reset/confirm', { token, newPassword })

export const changePassword = (
  input: { currentPassword: string; newPassword: string },
  client: ApiClient = apiClient,
) => client.post<void>('/auth/password/change', input)
