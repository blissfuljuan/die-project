import { CSRF_ENDPOINT, CSRF_HEADER, isMutating, readCsrfCookie } from './csrf'
import { NetworkError, ResponseFormatError, toApiError } from './errors'

export interface ApiClientOptions {
  baseUrl?: string
  timeoutMs?: number
  fetchImpl?: typeof fetch
  /** Where the CSRF token lives; overridable for tests. */
  readCsrf?: () => string | undefined
}

export interface RequestOptions {
  method?: string
  body?: unknown
  headers?: Record<string, string>
  signal?: AbortSignal
  timeoutMs?: number
  /** Optional runtime validation of the JSON body (e.g. `schema.parse`). */
  parse?: (data: unknown) => unknown
}

type VerbOptions = Omit<RequestOptions, 'method' | 'body'>

export const DEFAULT_BASE_URL = '/api/v1'
export const DEFAULT_TIMEOUT_MS = 10_000

export function resolveBaseUrl(): string {
  const configured = import.meta.env.VITE_API_BASE_URL as string | undefined
  return (configured && configured.trim() !== '' ? configured : DEFAULT_BASE_URL).replace(/\/+$/, '')
}

export class ApiClient {
  private readonly baseUrl: string
  private readonly timeoutMs: number
  private readonly fetchImpl: typeof fetch
  private readonly readCsrf: () => string | undefined

  constructor(options: ApiClientOptions = {}) {
    this.baseUrl = (options.baseUrl ?? resolveBaseUrl()).replace(/\/+$/, '')
    this.timeoutMs = options.timeoutMs ?? DEFAULT_TIMEOUT_MS
    this.fetchImpl = options.fetchImpl ?? ((input, init) => fetch(input, init))
    this.readCsrf = options.readCsrf ?? (() => readCsrfCookie())
  }

  get<T = unknown>(path: string, options: VerbOptions = {}) {
    return this.request<T>(path, { ...options, method: 'GET' })
  }
  post<T = unknown>(path: string, body?: unknown, options: VerbOptions = {}) {
    return this.request<T>(path, { ...options, method: 'POST', body })
  }
  put<T = unknown>(path: string, body?: unknown, options: VerbOptions = {}) {
    return this.request<T>(path, { ...options, method: 'PUT', body })
  }
  patch<T = unknown>(path: string, body?: unknown, options: VerbOptions = {}) {
    return this.request<T>(path, { ...options, method: 'PATCH', body })
  }
  delete<T = unknown>(path: string, options: VerbOptions = {}) {
    return this.request<T>(path, { ...options, method: 'DELETE' })
  }

  /** Makes sure the XSRF-TOKEN cookie exists and returns its value. */
  async ensureCsrfToken(signal?: AbortSignal): Promise<string | undefined> {
    const existing = this.readCsrf()
    if (existing) return existing
    const body = await this.request<{ token?: string }>(CSRF_ENDPOINT, { method: 'GET', signal })
    return this.readCsrf() ?? body?.token
  }

  async request<T = unknown>(path: string, options: RequestOptions = {}): Promise<T> {
    const method = (options.method ?? 'GET').toUpperCase()
    const headers: Record<string, string> = { Accept: 'application/json', ...options.headers }

    if (isMutating(method)) {
      const token = await this.ensureCsrfToken(options.signal)
      if (token) headers[CSRF_HEADER] = token
    }

    let body: string | undefined
    if (options.body !== undefined) {
      body = JSON.stringify(options.body)
      headers['Content-Type'] ??= 'application/json'
    }

    const response = await this.send(this.url(path), { method, headers, body }, options)

    if (!response.ok) throw await toApiError(response)
    if (response.status === 204) return undefined as T

    let data: unknown
    try {
      const text = await response.text()
      data = text ? JSON.parse(text) : undefined
    } catch (cause) {
      throw new ResponseFormatError('The server sent a response that is not valid JSON.', { cause })
    }
    try {
      return (options.parse ? options.parse(data) : data) as T
    } catch (cause) {
      throw new ResponseFormatError('The server response did not have the expected shape.', { cause })
    }
  }

  private url(path: string): string {
    return `${this.baseUrl}${path.startsWith('/') ? path : `/${path}`}`
  }

  private async send(url: string, init: RequestInit, options: RequestOptions): Promise<Response> {
    const controller = new AbortController()
    let timedOut = false
    const timer = setTimeout(() => {
      timedOut = true
      controller.abort()
    }, options.timeoutMs ?? this.timeoutMs)
    const caller = options.signal
    const onCallerAbort = () => controller.abort()
    if (caller) {
      if (caller.aborted) controller.abort()
      else caller.addEventListener('abort', onCallerAbort, { once: true })
    }
    try {
      return await this.fetchImpl(url, {
        ...init,
        credentials: 'include',
        signal: controller.signal,
      })
    } catch (cause) {
      // A cancellation by the caller (e.g. TanStack Query) must stay an AbortError, not "network down".
      if (caller?.aborted && !timedOut) throw cause
      throw new NetworkError(timedOut, { cause })
    } finally {
      clearTimeout(timer)
      caller?.removeEventListener('abort', onCallerAbort)
    }
  }
}

export const apiClient = new ApiClient()
