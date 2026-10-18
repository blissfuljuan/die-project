export const CSRF_COOKIE = 'XSRF-TOKEN'
export const CSRF_HEADER = 'X-XSRF-TOKEN'
export const CSRF_ENDPOINT = '/auth/csrf'

const MUTATING = new Set(['POST', 'PUT', 'PATCH', 'DELETE'])

export const isMutating = (method: string): boolean => MUTATING.has(method.toUpperCase())

/** Reads the XSRF-TOKEN cookie set by `GET /api/v1/auth/csrf`. The backend cookie is readable by JS. */
export function readCsrfCookie(cookieSource: string = document.cookie): string | undefined {
  for (const part of cookieSource.split(';')) {
    const [name, ...rest] = part.trim().split('=')
    if (name === CSRF_COOKIE) {
      const value = rest.join('=')
      try {
        return decodeURIComponent(value)
      } catch {
        return value
      }
    }
  }
  return undefined
}
