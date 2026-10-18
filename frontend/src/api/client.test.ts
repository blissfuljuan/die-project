import { ApiClient } from './client'
import { CSRF_HEADER, readCsrfCookie } from './csrf'
import { ApiError, NetworkError, ResponseFormatError } from './errors'
import { http, HttpResponse, server } from '@/test/server'

const BASE = 'http://localhost/api/v1'
const client = (extra: ConstructorParameters<typeof ApiClient>[0] = {}) =>
  new ApiClient({ baseUrl: BASE, ...extra })

describe('error parsing', () => {
  it('maps an RFC 9457 body to a typed ApiError', async () => {
    server.use(
      http.get(`${BASE}/thing`, () =>
        HttpResponse.json(
          {
            status: 400,
            title: 'Validation failed',
            detail: 'Please fix the highlighted fields.',
            instance: '/api/v1/thing',
            code: 'VALIDATION_FAILED',
            timestamp: '2026-10-18T12:00:00Z',
            traceId: 'trace-1',
            errors: [{ field: 'name', message: 'must not be blank' }],
          },
          { status: 400 },
        ),
      ),
    )
    const error = await client().get('/thing').catch((e: unknown) => e)
    expect(error).toBeInstanceOf(ApiError)
    const api = error as ApiError
    expect(api.status).toBe(400)
    expect(api.code).toBe('VALIDATION_FAILED')
    expect(api.traceId).toBe('trace-1')
    expect(api.detail).toBe('Please fix the highlighted fields.')
    expect(api.errors).toEqual([{ field: 'name', message: 'must not be blank' }])
  })

  it('falls back to status-derived values for non-JSON error bodies', async () => {
    server.use(
      http.get(`${BASE}/thing`, () =>
        new HttpResponse('<html>Bad gateway</html>', {
          status: 502,
          headers: { 'Content-Type': 'text/html', 'X-Request-Id': 'req-9' },
        }),
      ),
    )
    const api = (await client().get('/thing').catch((e: unknown) => e)) as ApiError
    expect(api).toBeInstanceOf(ApiError)
    expect(api.code).toBe('HTTP_502')
    expect(api.traceId).toBe('req-9')
  })

  it('distinguishes network failures from HTTP errors', async () => {
    server.use(http.get(`${BASE}/thing`, () => HttpResponse.error()))
    const error = await client().get('/thing').catch((e: unknown) => e)
    expect(error).toBeInstanceOf(NetworkError)
    expect(error).not.toBeInstanceOf(ApiError)
    expect((error as NetworkError).timedOut).toBe(false)
  })

  it('times out slow requests as NetworkError(timedOut)', async () => {
    server.use(http.get(`${BASE}/slow`, () => new Promise(() => {})))
    const error = await client({ timeoutMs: 50 }).get('/slow').catch((e: unknown) => e)
    expect(error).toBeInstanceOf(NetworkError)
    expect((error as NetworkError).timedOut).toBe(true)
  })

  it('reports invalid JSON on success as ResponseFormatError', async () => {
    server.use(http.get(`${BASE}/thing`, () => new HttpResponse('not json', { status: 200 })))
    await expect(client().get('/thing')).rejects.toBeInstanceOf(ResponseFormatError)
  })

  it('sends cookies (credentials: include)', async () => {
    const fetchImpl = vi.fn<typeof fetch>(async () => Response.json({ ok: true }))
    await client({ fetchImpl }).get('/thing')
    expect(fetchImpl.mock.calls[0]?.[1]?.credentials).toBe('include')
  })
})

describe('CSRF handling', () => {
  it('does not add the CSRF header or fetch a token for GET', async () => {
    const seen: Array<string | null> = []
    let csrfCalls = 0
    server.use(
      http.get(`${BASE}/thing`, ({ request }) => {
        seen.push(request.headers.get(CSRF_HEADER))
        return HttpResponse.json({})
      }),
      http.get(`${BASE}/auth/csrf`, () => {
        csrfCalls += 1
        return HttpResponse.json({ headerName: CSRF_HEADER, token: 'x' })
      }),
    )
    await client({ readCsrf: () => 'tok' }).get('/thing')
    expect(seen).toEqual([null])
    expect(csrfCalls).toBe(0)
  })

  it.each(['post', 'put', 'patch', 'delete'] as const)('adds the CSRF header on %s', async (verb) => {
    let header: string | null = null
    server.use(
      http.all(`${BASE}/thing`, ({ request }) => {
        header = request.headers.get(CSRF_HEADER)
        return new HttpResponse(null, { status: 204 })
      }),
    )
    const c = client({ readCsrf: () => 'token-123' })
    if (verb === 'delete') await c.delete('/thing')
    else await c[verb]('/thing', { a: 1 })
    expect(header).toBe('token-123')
  })

  it('fetches /auth/csrf first when the cookie is missing, then sends the token', async () => {
    let cookie: string | undefined
    const order: string[] = []
    server.use(
      http.get(`${BASE}/auth/csrf`, () => {
        order.push('csrf')
        cookie = 'fresh-token'
        return HttpResponse.json({ headerName: CSRF_HEADER, token: 'fresh-token' })
      }),
      http.post(`${BASE}/thing`, ({ request }) => {
        order.push(`post:${request.headers.get(CSRF_HEADER)}`)
        return HttpResponse.json({ ok: true })
      }),
    )
    await client({ readCsrf: () => cookie }).post('/thing', { a: 1 })
    expect(order).toEqual(['csrf', 'post:fresh-token'])
  })

  it('reads the XSRF-TOKEN cookie value', () => {
    expect(readCsrfCookie('a=1; XSRF-TOKEN=abc%3D; b=2')).toBe('abc=')
    expect(readCsrfCookie('a=1')).toBeUndefined()
  })
})
