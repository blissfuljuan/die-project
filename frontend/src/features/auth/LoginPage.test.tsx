import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { API, http, HttpResponse, server } from '@/test/server'
import { mockSession, participant, problem, renderApp } from '@/test/utils'

async function fillAndSubmit(email = 'ana@example.edu', password = 'correct horse battery') {
  const user = userEvent.setup()
  await user.type(await screen.findByLabelText('Email'), email)
  await user.type(screen.getByLabelText('Password'), password)
  await user.click(screen.getByRole('button', { name: 'Sign in' }))
}

describe('LoginPage', () => {
  it('validates required fields before calling the API', async () => {
    mockSession(null)
    const user = userEvent.setup()
    renderApp('/login')
    await user.click(await screen.findByRole('button', { name: 'Sign in' }))
    expect(await screen.findByText('Enter your email address.')).toBeInTheDocument()
    expect(screen.getByText('Enter your password.')).toBeInTheDocument()
  })

  it('signs in, sends the CSRF header and lands on the return path', async () => {
    mockSession(null)
    let csrf: string | null = null
    server.use(
      http.post(`${API}/auth/login`, ({ request }) => {
        csrf = request.headers.get('X-XSRF-TOKEN')
        mockSession(participant)
        return HttpResponse.json(participant)
      }),
      http.get(`${API}/workspaces`, () => HttpResponse.json({ items: [], page: 0, size: 100, total: 0 })),
    )
    renderApp('/login?next=%2Fme')
    await fillAndSubmit()
    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent('/me'))
    expect(csrf).toBe('test-csrf')
  })

  it('shows one generic message for wrong credentials', async () => {
    mockSession(null)
    server.use(http.post(`${API}/auth/login`, () => problem(401, 'INVALID_CREDENTIALS', 'Invalid email or password')))
    renderApp('/login')
    await fillAndSubmit('nobody@example.edu', 'whatever whatever')
    expect(await screen.findByRole('alert')).toHaveTextContent('The email or password is not correct.')
    expect(screen.getByTestId('location')).toHaveTextContent('/login')
  })

  it('explains throttling (429) without naming the account', async () => {
    mockSession(null)
    server.use(http.post(`${API}/auth/login`, () => problem(429, 'TOO_MANY_ATTEMPTS', 'Too many attempts')))
    renderApp('/login')
    await fillAndSubmit()
    expect(await screen.findByRole('alert')).toHaveTextContent(/too many attempts.*wait a few minutes/i)
  })

  it('maps backend field errors onto the fields', async () => {
    mockSession(null)
    server.use(
      http.post(`${API}/auth/login`, () =>
        problem(400, 'VALIDATION_FAILED', 'Validation failed', { errors: [{ field: 'email', message: 'must be a well-formed email address' }] }),
      ),
    )
    renderApp('/login')
    await fillAndSubmit('ana@example.edu')
    expect(await screen.findByText('must be a well-formed email address')).toBeInTheDocument()
  })

  it('tells the user when the server cannot be reached', async () => {
    mockSession(null)
    server.use(http.post(`${API}/auth/login`, () => HttpResponse.error()))
    renderApp('/login')
    await fillAndSubmit()
    expect(await screen.findByRole('alert')).toHaveTextContent(/could not reach the server/i)
  })

  it('disables the button while signing in', async () => {
    mockSession(null)
    server.use(http.post(`${API}/auth/login`, () => new Promise(() => {})))
    renderApp('/login')
    await fillAndSubmit()
    expect(await screen.findByRole('button', { name: 'Signing in...' })).toBeDisabled()
  })
})
