import { expect, test } from '@playwright/test'

// Requires the real API at http://localhost:8080 (the Vite dev server proxies /api to it).

test('shows the connected state against the real backend', async ({ page }) => {
  await page.goto('/status')
  await expect(page.getByRole('heading', { name: 'Connection status' })).toBeVisible()
  await expect(page.getByText('Connected to the DIE server.')).toBeVisible()
  await expect(page.getByText('die-api')).toBeVisible()
  await expect(page.getByText('v1', { exact: true })).toBeVisible()
  await expect(page.getByText('Server time')).toBeVisible()
})

test('shows disconnected, then recovers on retry', async ({ page }) => {
  // Fail every status request until the test lets the backend through again
  // (React StrictMode in dev may issue the first request twice).
  let failing = true
  await page.route('**/api/v1/status', async (route) => {
    if (failing) await route.abort('connectionrefused')
    else await route.continue()
  })

  await page.goto('/status')
  await expect(page.getByRole('alert')).toContainText('Cannot reach the server')
  await expect(page.getByText('Disconnected')).toBeVisible()

  failing = false
  await page.getByRole('button', { name: 'Retry' }).click()

  await expect(page.getByText('Connected to the DIE server.')).toBeVisible()
  await expect(page.getByRole('alert')).toHaveCount(0)
})

test('has no horizontal scroll at phone width', async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 800 })
  await page.goto('/status')
  await expect(page.getByRole('heading', { name: 'Connection status' })).toBeVisible()
  const overflow = await page.evaluate(
    () => document.documentElement.scrollWidth > document.documentElement.clientWidth,
  )
  expect(overflow).toBe(false)
})
