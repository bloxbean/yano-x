import { defineConfig } from '@playwright/test';
import path from 'node:path';

// Browser and accessibility gates for the static App-Chain Studio (ADR-031.2). Serves the prepared Studio
// directory (Gradle `:tooling:studio:prepareStudio`) on loopback; the docsite build is not needed.
const studio = path.resolve(process.env.STUDIO_DIR ?? '../tooling/studio/build/studio');

export default defineConfig({
  testDir: './tests-studio',
  fullyParallel: false,
  workers: 1,
  reporter: 'list',
  timeout: 120_000,
  use: { baseURL: 'http://127.0.0.1:4342', browserName: 'chromium', acceptDownloads: true },
  webServer: {
    command: `node scripts/serve-studio.mjs "${studio}" 4342`,
    url: 'http://127.0.0.1:4342/studio/bindings.html',
    reuseExistingServer: false,
  },
});
