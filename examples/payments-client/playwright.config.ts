import { defineConfig, devices } from '@playwright/test';

// E2E against a live devnet: Yaci DevKit (its CIP-30 wallet at :10000) plus a Yano X node hosting an
// asset-payments chain (e2e/devkit-node.sh). PAYMENTS_NODE_URL is that node; the dev server proxies /api to it.
const port = Number(process.env.PAYMENTS_CLIENT_PORT ?? 5174);

export default defineConfig({
  testDir: 'e2e',
  timeout: 10 * 60_000,
  expect: { timeout: 90_000 },
  workers: 1,
  reporter: [['list']],
  use: { baseURL: `http://localhost:${port}`, trace: 'retain-on-failure', ...devices['Desktop Chrome'] },
  webServer: {
    command: 'npx vite',
    port,
    reuseExistingServer: true,
    env: { PAYMENTS_NODE_URL: process.env.PAYMENTS_NODE_URL ?? 'http://localhost:7470' }
  }
});
