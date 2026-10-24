import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { API, http, HttpResponse, server } from '@/test/server'
import { mockSession, problem, renderApp } from '@/test/utils'

const GOOD_PASSWORD = 'a long passphrase here'

async function passStepOne(user: ReturnType<typeof userEvent.setup>, body: object = { firstName: 'Ana', lastName: 'Reyes', email: null, emailVerified: false }) {
  server.use(http.post(`${API}/auth/activation/check`, () => HttpResponse.json(body)))
  await user.type(await screen.findByLabelText('Student ID or email'), '2026-0001')
  await user.type(screen.getByLabelText('Temporary credential'), 'TEMP-123')
  await user.click(screen.getByRole('button', { name: 'Continue' }))
}

describe('Activation flow', () => {
  beforeEach(() => mockSession(null))

  it('requires both credentials in step 1', async () => {
    const user = userEvent.setup()
    renderApp('/activate')
    await user.click(await screen.findByRole('button', { name: 'Continue' }))
    expect(await screen.findByText(/enter your student id or email/i)).toBeInTheDocument()
    expect(screen.getByText(/enter the temporary credential/i)).toBeInTheDocument()
  })

  it('shows a neutral error when the credentials are not accepted', async () => {
    const user = userEvent.setup()
    renderApp('/activate')
    server.use(http.post(`${API}/auth/activation/check`, () => problem(401, 'ACTIVATION_CREDENTIALS_INVALID')))
    await user.type(await screen.findByLabelText('Student ID or email'), 'x')
    await user.type(screen.getByLabelText('Temporary credential'), 'y')
    await user.click(screen.getByRole('button', { name: 'Continue' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(/were not accepted/i)
  })

  it('walks through both steps to the "check your email" state', async () => {
    const user = userEvent.setup()
    let sent: Record<string, string> = {}
    renderApp('/activate')
    await passStepOne(user)
    expect(await screen.findByText(/welcome, ana reyes/i)).toBeInTheDocument()

    server.use(
      http.post(`${API}/auth/activation/begin`, async ({ request }) => {
        sent = (await request.json()) as Record<string, string>
        return HttpResponse.json({ status: 'VERIFICATION_REQUIRED' })
      }),
    )
    await user.type(screen.getByLabelText('Email'), 'ana@example.edu')
    await user.type(screen.getByLabelText('New password'), GOOD_PASSWORD)
    await user.type(screen.getByLabelText('Repeat the new password'), GOOD_PASSWORD)
    await user.click(screen.getByRole('button', { name: 'Activate account' }))

    expect(await screen.findByRole('heading', { name: 'Check your email' })).toBeInTheDocument()
    expect(screen.getByText('ana@example.edu')).toBeInTheDocument()
    expect(sent).toMatchObject({
      identifier: '2026-0001',
      temporaryCredential: 'TEMP-123',
      email: 'ana@example.edu',
      newPassword: GOOD_PASSWORD,
    })
  })

  it('enforces the 12 character policy and matching passwords before calling the API', async () => {
    const user = userEvent.setup()
    renderApp('/activate')
    await passStepOne(user)
    await user.type(await screen.findByLabelText('Email'), 'ana@example.edu')
    await user.type(screen.getByLabelText('New password'), 'short')
    await user.type(screen.getByLabelText('Repeat the new password'), 'different')
    expect(screen.getByText(/too short \(5\/12\)/i)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Activate account' }))
    expect(await screen.findByText('Use at least 12 characters.')).toBeInTheDocument()
    expect(screen.getByText('The two passwords do not match.')).toBeInTheDocument()
  })

  it('shows a server password-policy error next to the password field', async () => {
    const user = userEvent.setup()
    renderApp('/activate')
    await passStepOne(user)
    server.use(http.post(`${API}/auth/activation/begin`, () => problem(400, 'PASSWORD_TOO_EASY', 'Password is too easy to guess')))
    await user.type(await screen.findByLabelText('Email'), 'ana@example.edu')
    await user.type(screen.getByLabelText('New password'), GOOD_PASSWORD)
    await user.type(screen.getByLabelText('Repeat the new password'), GOOD_PASSWORD)
    await user.click(screen.getByRole('button', { name: 'Activate account' }))
    expect(await screen.findByText(/too easy to guess/i)).toBeInTheDocument()
  })

  it('keeps an already verified email read-only and finishes as active', async () => {
    const user = userEvent.setup()
    renderApp('/activate')
    await passStepOne(user, { firstName: 'Ana', lastName: 'Reyes', email: 'ana@example.edu', emailVerified: true })
    const email = await screen.findByLabelText('Email')
    expect(email).toHaveValue('ana@example.edu')
    expect(email).toHaveAttribute('readonly')
    server.use(http.post(`${API}/auth/activation/begin`, () => HttpResponse.json({ status: 'ACTIVE' })))
    await user.type(screen.getByLabelText('New password'), GOOD_PASSWORD)
    await user.type(screen.getByLabelText('Repeat the new password'), GOOD_PASSWORD)
    await user.click(screen.getByRole('button', { name: 'Activate account' }))
    expect(await screen.findByRole('heading', { name: 'Your account is active' })).toBeInTheDocument()
  })
})

describe('Email verification landing page', () => {
  beforeEach(() => mockSession(null))

  it('consumes the token exactly once and removes it from the address bar', async () => {
    let calls = 0
    let token = ''
    server.use(
      http.post(`${API}/auth/activation/verify`, async ({ request }) => {
        calls += 1
        token = ((await request.json()) as { token: string }).token
        return HttpResponse.json({ status: 'ACTIVE' })
      }),
    )
    renderApp('/verify-email?token=abc123')
    expect(await screen.findByRole('heading', { name: 'Email verified' })).toBeInTheDocument()
    expect(token).toBe('abc123')
    expect(calls).toBe(1)
    expect(screen.getByTestId('location')).not.toHaveTextContent('abc123')
  })

  it('explains an expired or used link', async () => {
    server.use(http.post(`${API}/auth/activation/verify`, () => problem(400, 'VERIFICATION_LINK_INVALID')))
    renderApp('/verify-email?token=old')
    expect(await screen.findByRole('alert')).toHaveTextContent(/no longer valid|not valid any more/i)
  })

  it('asks for the link when the token is missing', async () => {
    renderApp('/verify-email')
    expect(await screen.findByRole('heading', { name: 'Verification link needed' })).toBeInTheDocument()
  })
})
