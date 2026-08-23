// Astro integration that publishes the Yano X AI ingestion artifacts:
//
//   /llms.txt             curated index (llmstxt.org convention)
//   /llms-full.txt        every page concatenated for ingestion
//   /ai/starter-pack.md   raw markdown copy of the AI Starter Pack
//   /ai/index.md          raw markdown copy of the /ai/ landing page
//   /ai/catalog.json      machine-readable recipes/capabilities/modules/config
//
// `astro build` writes them into the output directory so they ship with the
// static site. `astro dev` serves them from a temp directory through a Vite
// middleware, so they work locally without a build and pick up doc edits.

import { fileURLToPath } from 'node:url';
import path from 'node:path';
import os from 'node:os';
import fs from 'node:fs/promises';
import { generateLlmsFiles } from './generate-llms-txt.mjs';
import { generateCatalog, writeCatalog } from './generate-catalog.mjs';

const SERVED_PATHS = new Set([
  '/llms.txt',
  '/llms-full.txt',
  '/ai/starter-pack.md',
  '/ai/index.md',
  '/ai/catalog.json',
]);

export default function llmsIntegration() {
  return {
    name: 'yano-x-llms-txt',
    hooks: {
      'astro:build:done': async ({ dir, logger }) => {
        const outDir = fileURLToPath(dir);
        try {
          const catalog = await generateCatalog({ logger });
          await generateLlmsFiles({ outDir, logger, catalog });
          await writeCatalog({ outDir, logger, catalog });
        } catch (err) {
          logger.error(`[llms-txt] generation failed: ${err.stack || err.message}`);
          throw err;
        }
      },

      'astro:server:setup': async ({ server, logger }) => {
        const tmpDir = await fs.mkdtemp(path.join(os.tmpdir(), 'yano-x-llms-'));
        const quiet = { info: () => {}, error: (m) => logger.error(m) };

        async function regenerate() {
          const catalog = await generateCatalog({ logger: quiet });
          await generateLlmsFiles({ outDir: tmpDir, logger: quiet, catalog });
          await writeCatalog({ outDir: tmpDir, logger: quiet, catalog });
        }

        try {
          await regenerate();
          logger.info(`[llms-txt] dev middleware ready (${[...SERVED_PATHS].join(', ')})`);
        } catch (err) {
          logger.error(`[llms-txt] dev warmup failed: ${err.message}`);
        }

        server.middlewares.use(async (req, res, next) => {
          const url = (req.url || '').split('?')[0];
          if (!SERVED_PATHS.has(url)) return next();
          try {
            await regenerate();
            const body = await fs.readFile(path.join(tmpDir, url.replace(/^\//, '')), 'utf8');
            res.statusCode = 200;
            res.setHeader(
              'Content-Type',
              url.endsWith('.json')
                ? 'application/json; charset=utf-8'
                : 'text/markdown; charset=utf-8',
            );
            res.setHeader('Cache-Control', 'no-store');
            res.end(body);
          } catch (err) {
            logger.error(`[llms-txt] dev serve failed for ${url}: ${err.message}`);
            res.statusCode = 500;
            res.end(`llms-txt generation error: ${err.message}`);
          }
        });
      },
    },
  };
}
