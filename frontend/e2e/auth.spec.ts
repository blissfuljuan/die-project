import { expect, test } from '@playwright/test'
import { hasAdmin, hasHorizontalScroll, SKIP_REASON, signInAsAdmin } from './helpers'

// Real backend through the Vite dev server. Tests that sign in need the bootstrap Administrator:
//   E2E_ADMIN_EMAIL=... E2E_ADMIN_PASSWORD=... npm run test:e2e

test.describe('signed out', () => {
  test('a protected URL redirects to /login and keeps the deep link', async ({ page }) => {
    await page.goto('/admin')
    await expect(page.getByRole('heading', { name: 'Sign in' })).toBeVisible()
    await expect(page).toHaveURL(/\/login\?next=%2Fadmin$/)
  })

  test('wrong credentials show one generic message', async ({ page }) => {
    await page.goto('/login')
    await page.getByLabel('Email').fill('nobody-e2e@example.invalid')
    await page.getByLabel('Password').fill('definitely not the password')
    await page.getByRole('button', { name: 'Sign in' }).click()
    await expect(page.getByRole('alert')).toContainText('The email or password is not correct.')
    await expect(page).toHaveURL(/\/login/)
  })

  test('forgot password gives the neutral confirmation for an unknown address', async ({ page }) => {
    await page.goto('/forgot-password')
    await page.getByLabel('Email').fill('nobody-e2e@example.invalid')
    await page.getByRole('button', { name: 'Send reset link' }).click()
    await expect(page.getByText(/if an account exists for that address/i)).toBeVisible()
  })

  test('verification and reset landing pages explain a missing link', async ({ page }) => {
    await page.goto('/verify-email')
    await expect(page.getByRole('heading', { name: 'Verification link needed' })).toBeVisible()
    await page.goto('/reset-password')
    await expect(page.getByRole('heading', { name: 'Reset link needed' })).toBeVisible()
  })

  test('login page has no horizontal scroll at phone width', async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 800 })
    await page.goto('/login')
    await expect(page.getByRole('heading', { name: 'Sign in' })).toBeVisible()
    expect(await hasHorizontalScroll(page)).toBe(false)
  })
})

test.describe('Administrator', () => {
  test.skip(!hasAdmin, SKIP_REASON)

  test('signs in, sees platform health, and signs out', async ({ page }) => {
    await signInAsAdmin(page)
    await expect(page).toHaveURL(/\/admin$/)
    await expect(page.getByRole('heading', { name: 'Platform health' })).toBeVisible()
    await expect(page.getByText('Database', { exact: true })).toBeVisible()
    await expect(page.getByText('Message broker')).toBeVisible()

    await page.getByRole('button', { name: /account menu/i }).click()
    await page.getByRole('menuitem', { name: 'Sign out' }).click()
    await expect(page.getByRole('heading', { name: 'Sign in' })).toBeVisible()

    // The session is really gone: a protected URL sends us back to /login.
    await page.goto('/admin')
    await expect(page).toHaveURL(/\/login\?next=%2Fadmin$/)
  })

  test('a deep link survives sign-in', async ({ page }) => {
    await page.goto('/admin/accounts')
    await expect(page).toHaveURL(/\/login\?next=%2Fadmin%2Faccounts$/)
    await page.getByLabel('Email').fill(process.env.E2E_ADMIN_EMAIL ?? '')
    await page.getByLabel('Password').fill(process.env.E2E_ADMIN_PASSWORD ?? '')
    await page.getByRole('button', { name: 'Sign in' }).click()
    await expect(page).toHaveURL(/\/admin\/accounts$/)
    await expect(page.getByRole('heading', { name: 'Accounts' })).toBeVisible()
  })

  test('a signed-in user is moved away from /login', async ({ page }) => {
    await signInAsAdmin(page)
    await page.goto('/login')
    await expect(page).toHaveURL(/\/admin$/)
  })

  test('an Administrator cannot open the Participant area', async ({ page }) => {
    await signInAsAdmin(page)
    await page.goto('/app')
    await expect(page.getByRole('heading', { name: /not found, or no access/i })).toBeVisible()
  })

  test('dashboard has no horizontal scroll at phone width', async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 800 })
    await signInAsAdmin(page)
    await expect(page.getByRole('heading', { name: 'Platform health' })).toBeVisible()
    expect(await hasHorizontalScroll(page)).toBe(false)
    await page.getByRole('button', { name: 'Open menu' }).click()
    await expect(page.getByRole('dialog', { name: 'Navigation' })).toBeVisible()
    expect(await hasHorizontalScroll(page)).toBe(false)
  })
})
