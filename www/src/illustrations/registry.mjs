// Every illustration a page can request with `<!-- illustration: <id> -->`.
// Each module in ./data default-exports one illustration whose `id` matches
// its file name; adding a file is enough to register it.

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const DATA_DIR = fileURLToPath(new URL('./data/', import.meta.url));

const modules = await Promise.all(fs.readdirSync(DATA_DIR)
  .filter((name) => name.endsWith('.mjs'))
  .sort()
  .map(async (name) => {
    const { default: data } = await import(/* @vite-ignore */ pathToFileURL(path.join(DATA_DIR, name)).href);
    if (`${data?.id}.mjs` !== name) {
      throw new Error(`Illustration ${name} must default-export an object whose id is "${name.slice(0, -4)}"`);
    }
    return data;
  }));

export const ILLUSTRATIONS = Object.freeze(Object.fromEntries(modules.map((data) => [data.id, data])));
