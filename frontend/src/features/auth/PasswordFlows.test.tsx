import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { API, http, HttpResponse, server } from '@/test/server'
import { mockSession, participant, problem, renderApp } from '@/test/utils'

const NEW_PASSWORD = 'another long passphrase'

describe('Forgot password', () => {
  beforeEach(() => mockSession(null))

  it('shows the same neutral confirmation for any address', async () => {
    const user = userEvent.setup()
    server.use(
      http.post(`${API}/auth/password-reset/request`, () =>
        HttpResponse.json({ message: 'If an account exists for that address, a reset link has been sent.' }, { status: 202 }),
      ),
    )
    renderApp('/forgot-password')
    await user.type(await screen.findByLabelText('Email'), 'unknown@example.edu')
    await user.click(screen.getByRole('button', { name: 'Send reset link' }))
    expect(await screen.findByRole('heading', { name: 'Check your email' })).toBeInTheDocument()
    expect(screen.getByText(/if an account exists for that address/i)).toBeInTheDocument()
  })

  it('reports request throttling', async () => {
    const user = userEvent.setup()
    server.use(http.post(`${API}/auth/password-reset/request`, () => problem(429, 'TOO_MANY_ATTEMPTS')))
    renderApp('/forgot-password')
    await user.type(await screen.findByLabelText('Email'), 'ana@example.edu')
    await user.click(screen.getByRole('button', { name: 'Send reset link' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(/too many reset requests/i)
  })
})

describe('Reset password', () => {
  beforeEach(() => mockSession(null))

  it('needs the token from the email', async () => {
    renderApp('/reset-password')
    expect(await screen.findByRole('heading', { name: 'Reset link needed' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /new reset link/i })).toHaveAttribute('href', '/forgot-password')
  })

  it('sets the new password with the token, then points to sign in', async () => {
    const user = userEvent.setup()
    let body: Record<string, string> = {}
    server.use(
      http.post(`${API}/auth/password-reset/confirm`, async ({ request }) => {
        body = (await request.json()) as Record<string, string>
        return HttpResponse.json({ status: 'OK' })
      }),
    )
    renderApp('/reset-password?token=tok-1')
    await user.type(await screen.findByLabelText('New password'), NEW_PASSWORD)
    await user.type(screen.getByLabelText('Repeat the new password'), NEW_PASSWORD)
    await user.click(screen.getByRole('button', { name: 'Save new password' }))
    expect(await screen.findByRole('heading', { name: 'Password updated' })).toBeInTheDocument()
    expect(body).toEqual({ token: 'tok-1', newPassword: NEW_PASSWORD })
    expect(screen.getByTestId('location')).not.toHaveTextContent('tok-1')
  })

  it('offers a new link when the old one is no longer valid', async () => {
    const user = userEvent.setup()
    server.use(http.post(`${API}/auth/password-reset/confirm`, () => problem(400, 'RESET_LINK_INVALID')))
    renderApp('/reset-password?token=old')
    await user.type(await screen.findByLabelText('New password'), NEW_PASSWORD)
    await user.type(screen.getByLabelText('Repeat the new password'), NEW_PASSWORD)
    await user.click(screen.getByRole('button', { name: 'Save new password' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(/not valid any more/i)
    expect(screen.getByRole('link', { name: /new reset link/i })).toBeInTheDocument()
  })

  it('checks the policy and the confirmation locally', async () => {
    const user = userEvent.setup()
    renderApp('/reset-password?token=t')
    await user.type(await screen.findByLabelText('New password'), 'tiny')
    await user.click(screen.getByRole('button', { name: 'Save new password' }))
    expect(await screen.findByText('Use at least 12 characters.')).toBeInTheDocument()
  })
})

describe('Change password (profile)', () => {
  beforeEach(() => mockSession(participant))

  async function fill(user: ReturnType<typeof userEvent.setup>, current = 'current password!') {
    await user.type(await screen.findByLabelText('Current password'), current)
    await user.type(screen.getByLabelText('New password'), NEW_PASSWORD)
    await user.type(screen.getByLabelText('Repeat the new password'), NEW_PASSWORD)
    await user.click(screen.getByRole('button', { name: 'Change password' }))
  }

  it('confirms success and clears the form', async () => {
    const user = userEvent.setup()
    server.use(http.post(`${API}/auth/password/change`, () => new HttpResponse(null, { status: 204 })))
    renderApp('/me')
    await fill(user)
    expect(await screen.findByRole('status')).toHaveTextContent(/password has been changed/i)
    await waitFor(() => expect(screen.getByLabelText('Current password')).toHaveValue(''))
  })

  it('shows a wrong current password next to that field', async () => {
    const user = userEvent.setup()
    server.use(http.post(`${API}/auth/password/change`, () => problem(400, 'CURRENT_PASSWORD_INCORRECT')))
    renderApp('/me')
    await fill(user)
    expect(await screen.findByText('Your current password is not correct.')).toBeInTheDocument()
  })

  it('shows throttling as a form message', async () => {
    const user = userEvent.setup()
    server.use(http.post(`${API}/auth/password/change`, () => problem(429, 'TOO_MANY_ATTEMPTS')))
    renderApp('/me')
    await fill(user)
    expect(await screen.findByRole('alert')).toHaveTextContent(/too many attempts/i)
  })
})
