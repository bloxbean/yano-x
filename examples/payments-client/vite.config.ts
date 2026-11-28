import { defineConfig } from 'vitest/config';

// The client calls the node through this origin (/api/v1/...), so no CORS setup is needed in development.
// PAYMENTS_NODE_URL is the Yano X node that hosts the asset-payments chain.
const node = process.env.PAYMENTS_NODE_URL ?? 'http://localhost:7070';

export default defineConfig({
  server: { port: Number(process.env.PAYMENTS_CLIENT_PORT ?? 5174), strictPort: true, proxy: { '/api': node } },
  preview: { port: Number(process.env.PAYMENTS_CLIENT_PORT ?? 5174), strictPort: true, proxy: { '/api': node } },
  test: { include: ['src/**/*.test.ts'], environment: 'node' }
});
