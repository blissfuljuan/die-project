import { expect, type Page } from '@playwright/test'

export const ADMIN_EMAIL = process.env.E2E_ADMIN_EMAIL ?? ''
export const ADMIN_PASSWORD = process.env.E2E_ADMIN_PASSWORD ?? ''
export const hasAdmin = ADMIN_EMAIL !== '' && ADMIN_PASSWORD !== ''
export const SKIP_REASON =
  'Set E2E_ADMIN_EMAIL and E2E_ADMIN_PASSWORD to the bootstrap Administrator of the API under test to run this test.'

export async function signInAsAdmin(page: Page, next?: string) {
  await page.goto(next ? `/login?next=${encodeURIComponent(next)}` : '/login')
  await page.getByLabel('Email').fill(ADMIN_EMAIL)
  await page.getByLabel('Password').fill(ADMIN_PASSWORD)
  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(page.getByRole('heading', { name: /^Welcome,/ })).toBeVisible()
}

export const hasHorizontalScroll = (page: Page) =>
  page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth)
