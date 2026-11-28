import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';
import { defineConfig } from 'vitest/config';

// The client calls the node through this origin (/api/v1/...), so no CORS setup is needed in development.
// PAYMENTS_NODE_URL is the Yano X node that hosts the asset-payments chain.
const node = process.env.PAYMENTS_NODE_URL ?? 'http://localhost:7070';
const port = Number(process.env.PAYMENTS_CLIENT_PORT ?? 5174);

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: { port, strictPort: true, proxy: { '/api': node } },
  preview: { port, strictPort: true, proxy: { '/api': node } },
  // Unit tests run in node; component tests (*.test.tsx) opt into jsdom with a @vitest-environment docblock.
  test: { include: ['src/**/*.test.{ts,tsx}'], environment: 'node' }
});
