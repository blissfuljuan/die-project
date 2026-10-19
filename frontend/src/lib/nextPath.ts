/**
 * A return path is only accepted when it is a same-origin absolute path ("/x?y#z"). Anything else
 * (protocol-relative "//host", "http://...", backslashes, control characters) is dropped, so the login page
 * cannot be used as an open redirect. Auth pages themselves are never a return target.
 */
export function safeNextPath(raw: string | null | undefined): string | null {
  if (!raw || raw.length > 2000) return null
  if (!raw.startsWith('/') || raw.startsWith('//') || raw.includes('\\')) return null
  // eslint-disable-next-line no-control-regex
  if (/[\u0000-\u001f\u007f]/.test(raw)) return null
  const path = raw.split(/[?#]/)[0] ?? ''
  if (['/login', '/activate', '/forgot-password', '/reset-password', '/verify-email'].includes(path)) return null
  return raw
}

export const loginPathFor = (returnTo: string): string => {
  const next = safeNextPath(returnTo)
  return next && next !== '/' ? `/login?next=${encodeURIComponent(next)}` : '/login'
}
