import { isApiError, isNetworkError, type ApiError } from './errors'

/**
 * The one place that turns backend error codes (ErrorCode names) into sentences a participant, coordinator or
 * administrator can act on. No technical vocabulary; never confirms whether an account exists.
 */
const MESSAGES: Record<string, string> = {
  // generic
  VALIDATION_FAILED: 'Please check the highlighted fields and try again.',
  MALFORMED_REQUEST: 'The request could not be understood. Please reload the page and try again.',
  ACCESS_DENIED: 'You do not have permission to do that.',
  AUTHENTICATION_REQUIRED: 'Your session has ended. Please sign in again.',
  CSRF_TOKEN_INVALID: 'Your session security check expired. Please reload the page and try again.',
  TOO_MANY_ATTEMPTS: 'Too many attempts. Please wait a few minutes before trying again.',
  SERVER_BUSY: 'The system is busy right now. Please try again in a moment.',
  SERVICE_UNAVAILABLE: 'The service is temporarily unavailable. Please try again in a moment.',
  INTERNAL_ERROR: 'Something went wrong on our side. Please try again.',
  ENDPOINT_NOT_FOUND: 'That page or item could not be found, or you may not have access to it.',
  // sign-in, activation, passwords
  INVALID_CREDENTIALS: 'The email or password is not correct.',
  ACTIVATION_CREDENTIALS_INVALID:
    'Those details were not accepted. Check your Student ID or email and the temporary credential, or ask your Coordinator for a new one.',
  VERIFICATION_LINK_INVALID: 'This verification link is not valid any more. It may have expired or already been used.',
  RESET_LINK_INVALID: 'This reset link is not valid any more. It may have expired or already been used.',
  CURRENT_PASSWORD_INCORRECT: 'Your current password is not correct.',
  PASSWORD_TOO_SHORT: 'Your password must be at least 12 characters long.',
  PASSWORD_TOO_LONG: 'Your password must be at most 128 characters long.',
  PASSWORD_BLANK: 'Your password cannot be blank.',
  PASSWORD_TOO_EASY: 'That password is too easy to guess. Do not reuse your email, Student ID or temporary credential.',
  ACCOUNT_ALREADY_ACTIVATED: 'This account is already active. Please sign in.',
  ACCOUNT_DEACTIVATED: 'This account is not active. Please contact an Administrator.',
  // workspaces and projects (used by the read-only pages now, by management screens later)
  CLASS_NOT_FOUND: 'That Project Workspace could not be found, or you may not have access to it.',
  PROJECT_NOT_FOUND: 'That project could not be found, or you may not have access to it.',
  CLASS_ARCHIVED: 'This Project Workspace is archived and can no longer be changed.',
  PROJECT_ARCHIVED: 'This project is archived and can no longer be changed.',
  CLASS_VERSION_CONFLICT: 'Someone else changed this Project Workspace. Reload to see the latest version.',
  PROJECT_VERSION_CONFLICT: 'Someone else changed this project. Reload to see the latest version.',
  ACCOUNT_VERSION_CONFLICT: 'Someone else changed this account. Reload to see the latest version.',
  WORKSPACE_OWNER_TRANSFER_REQUIRED:
    'This person owns an active Project Workspace. Transfer ownership before changing their account.',
  CAPABILITY_ESCALATION_FORBIDDEN: 'You can only grant permissions that you hold yourself.',
  TEAM_FULL: 'This team is already full.',
  NOT_ENROLLED: 'You are not enrolled in this Project Workspace.',
  INVITATION_NOT_FOUND: 'That invitation could not be found.',
  INVITATION_NOT_PENDING: 'That invitation has already been answered or has expired.',
}

/** Backend code to the form field that should show the message (so it appears next to the input). */
export const FIELD_FOR_CODE: Record<string, string> = {
  PASSWORD_TOO_SHORT: 'newPassword',
  PASSWORD_TOO_LONG: 'newPassword',
  PASSWORD_BLANK: 'newPassword',
  PASSWORD_TOO_EASY: 'newPassword',
  CURRENT_PASSWORD_INCORRECT: 'currentPassword',
}

function byStatus(error: ApiError): string {
  if (error.status === 401) return 'Please sign in to continue.'
  if (error.status === 403) return 'You do not have permission to do that.'
  if (error.status === 404) return 'That item could not be found, or you may not have access to it.'
  if (error.status === 409) return 'This was changed by someone else. Reload and try again.'
  if (error.status === 429) return MESSAGES.TOO_MANY_ATTEMPTS as string
  if (error.status >= 500) return 'Something went wrong on our side. Please try again.'
  return error.detail
}

export function friendlyMessage(error: unknown): string {
  if (isNetworkError(error)) {
    return error.timedOut
      ? 'The server took too long to answer. Please try again in a moment.'
      : 'We could not reach the server. Check your connection and try again.'
  }
  if (isApiError(error)) return MESSAGES[error.code] ?? byStatus(error)
  return 'The server sent a reply we could not understand. Please try again.'
}
