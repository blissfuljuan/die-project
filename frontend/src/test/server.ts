import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'

export const STATUS_URL = '*/api/v1/status'

export const okStatus = {
  application: 'die-api',
  apiVersion: 'v1',
  buildVersion: '1.2.3',
  status: 'UP',
  time: '2026-10-18T12:00:00Z',
}

export const handlers = [http.get(STATUS_URL, () => HttpResponse.json(okStatus))]

export const server = setupServer(...handlers)

export { http, HttpResponse }
