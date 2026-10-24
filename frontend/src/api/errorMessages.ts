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
  ACTIVATION_EMAIL_LOCKED:
    'The email address on this account is already verified and cannot be changed here. Use that address; the confirmation link is sent to it.',
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
  FIELD_REQUIRED: 'Please fill in every required field.',
  FIELD_TOO_LONG: 'One of the entries is too long. Please shorten it.',
  VERSION_REQUIRED: 'Reload the page and try again.',
  INVALID_TEAM_FORMATION: 'Choose who forms the teams.',
  INVALID_TEAM_SIZE: 'Team sizes must satisfy: at least 1, minimum no larger than maximum, maximum at most 50.',
  TEAM_SIZE_BELOW_EXISTING_TEAM: 'A team in this Project Workspace is already larger than that maximum. Move people out first.',
  WORKSPACE_OWNER_REQUIRED: 'Choose the Coordinator who will own this Project Workspace.',
  WORKSPACE_OWNER_INVALID: 'That person cannot be the Owner of this Project Workspace.',
  WORKSPACE_OWNER_INACTIVE: 'The Owner of this Project Workspace is not active. Transfer ownership to an active Coordinator first.',
  WORKSPACE_OWNER_REMOVAL_FORBIDDEN: 'The Owner cannot be removed. Transfer ownership to another Coordinator first.',
  WORKSPACE_OWNER_CAPABILITIES_FIXED: 'The Owner always holds every permission. Transfer ownership first to change them.',
  COORDINATOR_ALREADY_ASSIGNED: 'That Coordinator is already assigned to this Project Workspace.',
  COORDINATOR_NOT_ASSIGNABLE: 'That account cannot be added. It must be an active Coordinator account.',
  COORDINATOR_NOT_FOUND: 'That Coordinator is not assigned to this Project Workspace.',
  INVALID_CAPABILITY: 'One of the chosen permissions is not recognised.',
  PARTICIPANT_NOT_FOUND: 'No Participant matches that Student ID and last name, or you cannot enroll them here. Check both and try again.',
  LAST_NAME_REQUIRED: "Enter the Participant's last name as well. It has to match the Student ID.",
  INVALID_ENROLLMENT_TARGET: 'Enter the Student ID of the Participant.',
  ALREADY_ENROLLED: 'That Participant is already enrolled in this Project Workspace.',
  ENROLLMENT_NOT_FOUND: 'That Participant is not enrolled in this Project Workspace.',
  CREDENTIAL_REISSUE_LIMIT: 'Too many temporary credentials were issued recently. Wait a while before issuing another.',
  IMPORT_NOT_FOUND: 'That import could not be found. It may have been cancelled or removed.',
  IMPORT_NOT_PENDING: 'That import has already been completed or cancelled.',
  IMPORT_ALREADY_COMMITTED: 'That import has already been completed.',
  IMPORT_STALE: 'The roster changed since this preview was made. Upload the file again to get a fresh preview.',
  IMPORT_CHANGED: 'The roster changed since this preview was made. Upload the file again to get a fresh preview.',
  UNRESOLVED_CONFLICTS: 'Some rows still need a decision. Decide every conflict before importing.',
  RESOLUTION_NOT_ALLOWED: 'That decision is not allowed for this row.',
  ROW_NOT_A_CONFLICT: 'That row does not need a decision.',
  ROW_NOT_FOUND: 'That row is not in this import.',
  RESOLUTIONS_REQUIRED: 'Choose at least one decision to save.',
  COMMIT_ALREADY_RUNNING: 'An import is already being applied. Wait for it to finish.',
  UNSUPPORTED_FILE_TYPE: 'Use a .csv or .xlsx file.',
  FILE_EMPTY: 'That file is empty.',
  FILE_TOO_LARGE: 'That file is too large. The limit is 5 MB.',
  FILE_NOT_TEXT: 'That file does not look like a CSV file.',
  CSV_NOT_UTF: 'That CSV file is not saved as UTF-8. Save it as "CSV UTF-8" and try again.',
  CSV_UNREADABLE: 'That CSV file could not be read.',
  XLSX_INVALID: 'That spreadsheet could not be read. Save it as .xlsx again or use a CSV file.',
  XLSX_UNREADABLE: 'That spreadsheet could not be read. Save it as .xlsx again or use a CSV file.',
  XLSX_NO_SHEETS: 'That spreadsheet has no sheets.',
  XLSX_TOO_LARGE_UNPACKED: 'That spreadsheet is too large once opened. Remove unused sheets and rows.',
  XLSX_TOO_MANY_PARTS: 'That spreadsheet is too complex. Save the data in a plain sheet or use a CSV file.',
  HEADER_ROW_MISSING: 'The first row must name the columns (Student ID, Last Name, First Name, ...).',
  MISSING_COLUMNS: 'Some required columns are missing. The file needs Student ID, Last Name and First Name.',
  DUPLICATE_COLUMN: 'A column appears twice in the header row.',
  NO_DATA_ROWS: 'The file has a header but no Participants.',
  TOO_MANY_ROWS: 'That file has too many rows. The limit is 500 Participants per import.',
  TOO_MANY_COLUMNS: 'That file has too many columns.',
  UPLOAD_UNREADABLE: 'The upload could not be read. Try again.',
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
