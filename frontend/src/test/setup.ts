import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterAll, afterEach, beforeAll } from 'vitest'
import { server } from './server'

beforeAll(() => {
  server.listen({ onUnhandledFrame: 'error' })
  // Node's fetch rejects relative URLs; resolve them against the jsdom origin
  // (after MSW patched fetch, so MSW still sees every request).
  const patched = globalThis.fetch
  globalThis.fetch = ((input: RequestInfo | URL, init?: RequestInit) => {
    if (typeof input === 'string' && input.startsWith('/')) {
      return patched(new URL(input, window.location.origin).toString(), init)
    }
    return patched(input, init)
  }) as typeof fetch
})

afterEach(() => {
  cleanup()
  server.resetHandlers()
  document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/'
})

afterAll(() => server.close())
