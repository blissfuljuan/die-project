import { ApiError, friendlyMessage, NetworkError } from '@/api'
import { loginPathFor, safeNextPath } from './nextPath'
import { passwordSchema, passwordStrength } from './password'

describe('safeNextPath', () => {
  it('accepts same-origin paths', () => {
    expect(safeNextPath('/workspaces/1?x=1#y')).toBe('/workspaces/1?x=1#y')
  })
  it.each(['//evil.example', 'https://evil.example', '/\\evil', 'javascript:alert(1)', '', null, undefined, '/login', '/reset-password?token=1', '/a\nb'])(
    'rejects %s',
    (value) => expect(safeNextPath(value as string | null | undefined)).toBeNull(),
  )
  it('builds a login path with an encoded return path, but not for "/"', () => {
    expect(loginPathFor('/projects/1?a=b')).toBe('/login?next=%2Fprojects%2F1%3Fa%3Db')
    expect(loginPathFor('/')).toBe('/login')
  })
})

describe('password helpers', () => {
  it('follows the backend length policy (12 to 128)', () => {
    expect(passwordSchema.safeParse('x'.repeat(11)).success).toBe(false)
    expect(passwordSchema.safeParse('x'.repeat(12)).success).toBe(true)
    expect(passwordSchema.safeParse('x'.repeat(128)).success).toBe(true)
    expect(passwordSchema.safeParse('x'.repeat(129)).success).toBe(false)
    expect(passwordSchema.safeParse(' '.repeat(12)).success).toBe(false)
  })
  it('gives a length-based hint', () => {
    expect(passwordStrength('').level).toBe(0)
    expect(passwordStrength('short').label).toMatch(/too short/i)
    expect(passwordStrength('x'.repeat(12)).label).toBe('Acceptable')
    expect(passwordStrength('x'.repeat(16)).label).toBe('Good')
    expect(passwordStrength('x'.repeat(24)).label).toBe('Strong')
  })
})

describe('friendlyMessage', () => {
  it('maps known backend codes', () => {
    expect(friendlyMessage(new ApiError(401, { code: 'INVALID_CREDENTIALS' }))).toBe('The email or password is not correct.')
    expect(friendlyMessage(new ApiError(429, { code: 'TOO_MANY_ATTEMPTS' }))).toMatch(/too many attempts/i)
  })
  it('falls back by status and never leaks server error details for 5xx', () => {
    expect(friendlyMessage(new ApiError(500, { code: 'WHATEVER', detail: 'NullPointerException at X' }))).not.toMatch(/NullPointer/)
    expect(friendlyMessage(new ApiError(404, { code: 'UNKNOWN' }))).toMatch(/not be found, or you may not have access/i)
  })
  it('describes network problems', () => {
    expect(friendlyMessage(new NetworkError(false))).toMatch(/could not reach the server/i)
    expect(friendlyMessage(new NetworkError(true))).toMatch(/too long/i)
  })
})

describe('csv', () => {
  it('neutralises spreadsheet formulas and quotes awkward cells', async () => {
    const { csvCell, toCsv } = await import('./csv')
    expect(csvCell('=SUM(A1)')).toBe("'=SUM(A1)")
    expect(csvCell('+1')).toBe("'+1")
    expect(csvCell('-2')).toBe("'-2")
    expect(csvCell('@x')).toBe("'@x")
    expect(csvCell('plain')).toBe('plain')
    expect(csvCell('a,b')).toBe('"a,b"')
    expect(csvCell('say "hi"')).toBe('"say ""hi"""')
    expect(csvCell(null)).toBe('')
    expect(toCsv(['a', 'b'], [['1', '=2']])).toBe("a,b\r\n1,'=2\r\n")
  })
})
