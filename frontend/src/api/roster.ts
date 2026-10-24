import { z } from 'zod'
import { apiClient, type ApiClient } from './client'

// ---- the roster (enrolled Participants) ----

export const enrollmentSchema = z.object({
  participantId: z.string(),
  studentNumber: z.string().nullish(),
  lastName: z.string(),
  firstName: z.string(),
  middleName: z.string().nullish(),
  email: z.string().nullish(),
  accountStatus: z.string(),
  program: z.string().nullish(),
  section: z.string().nullish(),
  status: z.string(),
  enrolledAt: z.string().nullish(),
  removedAt: z.string().nullish(),
})
export type Enrollment = z.infer<typeof enrollmentSchema>

export const enrollmentPageSchema = z.object({
  items: z.array(enrollmentSchema),
  page: z.number(),
  size: z.number(),
  total: z.number(),
})
export type EnrollmentPage = z.infer<typeof enrollmentPageSchema>

const ws = (id: string) => `/workspaces/${encodeURIComponent(id)}`

export const fetchRoster = (
  workspaceId: string,
  opts: { page: number; size: number; includeRemoved: boolean },
  signal?: AbortSignal,
  client: ApiClient = apiClient,
) =>
  client.get<EnrollmentPage>(
    `${ws(workspaceId)}/enrollments?page=${opts.page}&size=${opts.size}&includeRemoved=${opts.includeRemoved}`,
    { signal, parse: (d) => enrollmentPageSchema.parse(d) },
  )

/** Coordinators must give the last name that goes with the Student ID (the API refuses guesses). */
export interface EnrollInput {
  studentNumber: string
  lastName: string
  program?: string
  section?: string
}
export const enrollParticipant = (workspaceId: string, input: EnrollInput, client: ApiClient = apiClient) =>
  client.post<void>(`${ws(workspaceId)}/enrollments`, input)

export const unenrollParticipant = (workspaceId: string, participantId: string, client: ApiClient = apiClient) =>
  client.delete<void>(`${ws(workspaceId)}/enrollments/${encodeURIComponent(participantId)}`)

export const temporaryCredentialSchema = z.object({ secret: z.string(), expiresAt: z.string() })
export type TemporaryCredential = z.infer<typeof temporaryCredentialSchema>

/** Only for a Participant who has not activated yet. The secret is returned once; the API never shows it again. */
export const reissueTemporaryCredential = (workspaceId: string, participantId: string, client: ApiClient = apiClient) =>
  client.post<TemporaryCredential>(
    `${ws(workspaceId)}/enrollments/${encodeURIComponent(participantId)}/temporary-credential`,
    undefined,
    { parse: (d) => temporaryCredentialSchema.parse(d) },
  )

// ---- roster import (upload -> preview -> decide -> commit) ----

export const importSummarySchema = z.object({
  total: z.number(),
  create: z.number(),
  match: z.number(),
  skipDuplicate: z.number(),
  conflict: z.number(),
  invalid: z.number(),
  unresolvedConflicts: z.number(),
})
export type ImportSummary = z.infer<typeof importSummarySchema>

export const importViewSchema = z.object({
  id: z.string(),
  workspaceId: z.string(),
  fileName: z.string(),
  status: z.string(),
  createdAt: z.string().nullish(),
  committedAt: z.string().nullish(),
  summary: importSummarySchema,
})
export type ImportView = z.infer<typeof importViewSchema>

export const importRowSchema = z.object({
  rowNumber: z.number(),
  studentNumber: z.string().nullish(),
  lastName: z.string().nullish(),
  firstName: z.string().nullish(),
  middleName: z.string().nullish(),
  email: z.string().nullish(),
  program: z.string().nullish(),
  section: z.string().nullish(),
  action: z.string(),
  conflictKind: z.string().nullish(),
  resolution: z.string().nullish(),
  message: z.string().nullish(),
  allowedResolutions: z.array(z.string()).default([]),
})
export type ImportRow = z.infer<typeof importRowSchema>

export const importDetailSchema = z.object({
  importInfo: importViewSchema,
  rows: z.array(importRowSchema),
  page: z.number(),
  size: z.number(),
  totalRows: z.number(),
})
export type ImportDetail = z.infer<typeof importDetailSchema>

export const issuedCredentialSchema = z.object({
  rowNumber: z.number(),
  participantId: z.string(),
  studentNumber: z.string().nullish(),
  lastName: z.string(),
  firstName: z.string(),
  temporaryCredential: z.string(),
  expiresAt: z.string().nullish(),
})
export type IssuedCredential = z.infer<typeof issuedCredentialSchema>

export const commitResultSchema = z.object({
  importId: z.string(),
  alreadyCommitted: z.boolean(),
  created: z.number(),
  enrolledExisting: z.number(),
  skippedDuplicates: z.number(),
  skippedByResolution: z.number(),
  invalid: z.number(),
  credentials: z.array(issuedCredentialSchema).default([]),
})
export type CommitResult = z.infer<typeof commitResultSchema>

/** The API accepts .csv and .xlsx up to 5 MB and 500 rows. Nothing is changed until the import is committed. */
export const ROSTER_FILE_LIMIT_BYTES = 5 * 1024 * 1024
export const ROSTER_ROW_LIMIT = 500

export const uploadRosterFile = (workspaceId: string, file: File, client: ApiClient = apiClient) => {
  const form = new FormData()
  form.append('file', file)
  return client.post<ImportView>(`${ws(workspaceId)}/roster-imports`, form, { parse: (d) => importViewSchema.parse(d) })
}

export const fetchImportDetail = (
  importId: string,
  opts: { action?: string; page: number; size: number },
  signal?: AbortSignal,
  client: ApiClient = apiClient,
) => {
  const params = new URLSearchParams({ page: String(opts.page), size: String(opts.size) })
  if (opts.action) params.set('action', opts.action)
  return client.get<ImportDetail>(`/roster-imports/${encodeURIComponent(importId)}?${params}`, {
    signal,
    parse: (d) => importDetailSchema.parse(d),
  })
}

export const resolveImportRows = (
  importId: string,
  resolutions: Array<{ rowNumber: number; resolution: string }>,
  client: ApiClient = apiClient,
) =>
  client.put<ImportView>(`/roster-imports/${encodeURIComponent(importId)}/resolutions`, { resolutions }, {
    parse: (d) => importViewSchema.parse(d),
  })

/** The response carries the one-time temporary credentials of the accounts that were created. Keep it in memory only. */
export const commitImport = (importId: string, client: ApiClient = apiClient) =>
  client.post<CommitResult>(`/roster-imports/${encodeURIComponent(importId)}/commit`, undefined, {
    parse: (d) => commitResultSchema.parse(d),
  })

export const cancelImport = (importId: string, client: ApiClient = apiClient) =>
  client.delete<void>(`/roster-imports/${encodeURIComponent(importId)}`)
