import { defineConfig, devices } from '@playwright/test';

// Captures the README / PR screenshots (docs/screenshots) from a live DevKit devnet, like the E2E.
const port = Number(process.env.PAYMENTS_CLIENT_PORT ?? 5174);

export default defineConfig({
  testDir: 'e2e',
  testMatch: ['screenshots.spec.ts'],
  timeout: 10 * 60_000,
  expect: { timeout: 90_000 },
  workers: 1,
  reporter: [['list']],
  use: { baseURL: `http://localhost:${port}`, actionTimeout: 60_000, ...devices['Desktop Chrome'], viewport: { width: 1280, height: 860 } },
  webServer: {
    command: 'npx vite',
    port,
    reuseExistingServer: true,
    env: { PAYMENTS_NODE_URL: process.env.PAYMENTS_NODE_URL ?? 'http://localhost:7470', PAYMENTS_CLIENT_PORT: String(port) }
  }
});
