/** One field-level validation problem from the backend's `errors[]`. */
export interface FieldError {
  field: string
  message: string
}

/** The backend's RFC 9457 error body (all fields optional: a proxy may answer instead of the API). */
export interface ProblemBody {
  status?: number
  title?: string
  detail?: string
  instance?: string
  code?: string
  timestamp?: string
  traceId?: string
  errors?: FieldError[]
}

/** The server answered with a non-2xx status. */
export class ApiError extends Error {
  readonly kind = 'http' as const
  readonly status: number
  readonly code: string
  readonly title: string
  readonly detail: string
  readonly traceId: string | undefined
  readonly timestamp: string | undefined
  readonly instance: string | undefined
  readonly errors: FieldError[]

  constructor(status: number, body: ProblemBody = {}) {
    const detail = body.detail ?? body.title ?? `The server answered with status ${status}.`
    super(detail)
    this.name = 'ApiError'
    this.status = status
    this.code = body.code ?? `HTTP_${status}`
    this.title = body.title ?? `HTTP ${status}`
    this.detail = detail
    this.traceId = body.traceId
    this.timestamp = body.timestamp
    this.instance = body.instance
    this.errors = body.errors ?? []
  }
}

/** The request never produced an HTTP response (offline, DNS, connection refused) or timed out. */
export class NetworkError extends Error {
  readonly kind = 'network' as const
  readonly timedOut: boolean

  constructor(timedOut: boolean, options?: { cause?: unknown }) {
    super(
      timedOut ? 'The server did not answer in time.' : 'The server could not be reached.',
      options,
    )
    this.name = 'NetworkError'
    this.timedOut = timedOut
  }
}

/** The server answered 2xx but the body did not have the expected shape. */
export class ResponseFormatError extends Error {
  readonly kind = 'format' as const

  constructor(message: string, options?: { cause?: unknown }) {
    super(message, options)
    this.name = 'ResponseFormatError'
  }
}

export type ClientError = ApiError | NetworkError | ResponseFormatError

export const isApiError = (e: unknown): e is ApiError => e instanceof ApiError
export const isNetworkError = (e: unknown): e is NetworkError => e instanceof NetworkError
export const isClientError = (e: unknown): e is ClientError =>
  e instanceof ApiError || e instanceof NetworkError || e instanceof ResponseFormatError

/** Reads a (possibly non-JSON) failed response into an ApiError. Never throws. */
export async function toApiError(response: Response): Promise<ApiError> {
  let body: ProblemBody = {}
  try {
    const text = await response.text()
    if (text) {
      const parsed: unknown = JSON.parse(text)
      if (parsed && typeof parsed === 'object') body = parsed as ProblemBody
    }
  } catch {
    // HTML from a proxy, an empty body, ...: fall back to the status-derived error.
  }
  const traceId = body.traceId ?? response.headers.get('X-Request-Id') ?? undefined
  return new ApiError(response.status, { ...body, traceId })
}
