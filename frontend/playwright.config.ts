import { defineConfig, devices } from '@playwright/test'

// Runs against the REAL backend (default http://localhost:8080) through the Vite dev server.
// Start the API first, e.g. `docker compose --profile apps up -d api` from the repo root.
export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:5173',
    trace: 'retain-on-failure',
    // Use an installed browser instead of the bundled one, e.g. E2E_CHANNEL=msedge or chrome.
    channel: process.env.E2E_CHANNEL || undefined,
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
    { name: 'mobile', use: { ...devices['Pixel 7'] } },
  ],
  webServer: process.env.E2E_BASE_URL
    ? undefined
    : {
        command: 'npm run dev -- --strictPort',
        url: 'http://localhost:5173',
        reuseExistingServer: !process.env.CI,
        timeout: 60_000,
      },
})
