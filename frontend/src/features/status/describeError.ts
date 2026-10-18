import { isApiError, isNetworkError } from '@/api'

export interface ErrorSummary {
  title: string
  message: string
  code?: string
  traceId?: string
}

/** Plain-language summary of why the connection check failed. */
export function describeError(error: unknown): ErrorSummary {
  if (isNetworkError(error)) {
    return {
      title: error.timedOut ? 'The server is not answering' : 'Cannot reach the server',
      message: error.timedOut
        ? 'The server took too long to respond. Please try again in a moment.'
        : 'Check your internet connection, then try again. If the problem continues, the service may be down.',
    }
  }
  if (isApiError(error)) {
    return {
      title: 'The server reported a problem',
      message: error.detail,
      code: error.code,
      traceId: error.traceId,
    }
  }
  return {
    title: 'Unexpected response',
    message: 'The server answered, but the reply could not be understood. Please try again.',
  }
}
