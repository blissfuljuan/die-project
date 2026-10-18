import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse, okStatus, server, STATUS_URL } from '@/test/server'
import { ConnectionStatusCard } from './ConnectionStatusCard'

function renderCard() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <ConnectionStatusCard />
    </QueryClientProvider>,
  )
}

describe('ConnectionStatusCard', () => {
  it('shows a loading state first', async () => {
    server.use(http.get(STATUS_URL, async () => new Promise(() => {})))
    renderCard()
    expect(await screen.findByText(/checking the connection/i)).toBeInTheDocument()
    expect(screen.getByText('Checking')).toBeInTheDocument()
  })

  it('shows the connected state with application details', async () => {
    renderCard()
    expect(await screen.findByText(/connected to the die server/i)).toBeInTheDocument()
    expect(screen.getByText('Connected')).toBeInTheDocument()
    expect(screen.getByText('die-api')).toBeInTheDocument()
    expect(screen.getByText('v1')).toBeInTheDocument()
    expect(screen.getByText('1.2.3')).toBeInTheDocument()
    expect(screen.getByText('Server time')).toBeInTheDocument()
  })

  it('shows the error code and trace id on an HTTP error', async () => {
    server.use(
      http.get(STATUS_URL, () =>
        HttpResponse.json(
          {
            status: 503,
            title: 'Service Unavailable',
            detail: 'The service is temporarily unavailable.',
            code: 'SERVICE_UNAVAILABLE',
            traceId: 'trace-abc-123',
          },
          { status: 503 },
        ),
      ),
    )
    renderCard()
    expect(await screen.findByRole('alert')).toHaveTextContent('The service is temporarily unavailable.')
    expect(screen.getByText('SERVICE_UNAVAILABLE')).toBeInTheDocument()
    expect(screen.getByText('trace-abc-123')).toBeInTheDocument()
    expect(screen.getByText('Disconnected')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Retry' })).toBeEnabled()
  })

  it('shows a network error without a code', async () => {
    server.use(http.get(STATUS_URL, () => HttpResponse.error()))
    renderCard()
    expect(await screen.findByRole('alert')).toHaveTextContent(/cannot reach the server/i)
    expect(screen.queryByText('Error code')).not.toBeInTheDocument()
    expect(screen.getByText('Disconnected')).toBeInTheDocument()
  })

  it('treats a malformed success body as an error', async () => {
    server.use(http.get(STATUS_URL, () => HttpResponse.json({ nope: true })))
    renderCard()
    expect(await screen.findByRole('alert')).toHaveTextContent(/could not be understood/i)
  })

  it('recovers when Retry succeeds', async () => {
    let calls = 0
    server.use(
      http.get(STATUS_URL, () => {
        calls += 1
        return calls === 1
          ? HttpResponse.json({ code: 'INTERNAL_ERROR', detail: 'Boom', traceId: 't-1' }, { status: 500 })
          : HttpResponse.json(okStatus)
      }),
    )
    renderCard()
    await screen.findByRole('alert')

    await userEvent.click(screen.getByRole('button', { name: 'Retry' }))

    expect(await screen.findByText(/connected to the die server/i)).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(calls).toBe(2)
  })
})
