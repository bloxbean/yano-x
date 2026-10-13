// Replace `<!-- illustration: <id> -->` markers with rendered illustrations.
//
// The marker is an HTML comment, so the same markdown stays clean on GitHub
// and inside the JVM distribution, where it is invisible. A marker may wrap a
// plain-markdown fallback that only those readers need:
//
//   <!-- illustration: message-lifecycle -->
//   1. **Submit.** …
//   <!-- /illustration -->
//
// On the site the whole region becomes the illustration, which renders its own
// complete text (see src/illustrations/render.mjs). An unknown id fails the
// build, like an unresolvable link.

import { createHash } from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { ILLUSTRATIONS } from '../src/illustrations/registry.mjs';
import { renderIllustration } from '../src/illustrations/render.mjs';

const OPEN = /^<!--\s*illustration:\s*([a-z0-9-]+)\s*-->$/;
const CLOSE = /^<!--\s*\/illustration\s*-->$/;

export function illustrationMarker(node) {
  if (node?.type !== 'html') return null;
  const value = node.value.trim();
  const open = value.match(OPEN);
  if (open) return { kind: 'open', id: open[1] };
  if (CLOSE.test(value)) return { kind: 'close' };
  return null;
}

function transform(parent, file) {
  const children = parent.children ?? [];
  for (let i = 0; i < children.length; i++) {
    const marker = illustrationMarker(children[i]);
    if (!marker) {
      transform(children[i], file);
      continue;
    }
    if (marker.kind === 'close') {
      throw new Error(`${file}: <!-- /illustration --> without an opening marker`);
    }
    const data = ILLUSTRATIONS[marker.id];
    if (!data) {
      throw new Error(`${file}: unknown illustration "${marker.id}" `
        + `(known: ${Object.keys(ILLUSTRATIONS).join(', ')})`);
    }
    let end = i;
    for (let j = i + 1; j < children.length; j++) {
      const next = illustrationMarker(children[j]);
      if (next?.kind === 'close') { end = j; break; }
      if (next?.kind === 'open') break;
    }
    children.splice(i, end - i + 1, { type: 'html', value: renderIllustration(data) });
  }
}

/**
 * A digest of every illustration source. Astro caches rendered markdown until
 * a page or the Astro config changes; passing this digest as a plugin option
 * puts it in the config, so editing an illustration re-renders its pages.
 */
export function illustrationsDigest() {
  const root = fileURLToPath(new URL('../src/illustrations/', import.meta.url));
  const files = [];
  const walk = (dir) => {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) walk(full);
      else files.push(full);
    }
  };
  walk(root);
  // Some illustrations read repository catalogs at build time (recipe-chooser).
  const catalogs = fileURLToPath(new URL(
    '../../tooling/devtools/src/main/resources/appchain-dx/v1alpha1/', import.meta.url));
  if (fs.existsSync(catalogs)) walk(catalogs);
  const hash = createHash('sha256');
  for (const file of files.sort()) hash.update(path.basename(file)).update(fs.readFileSync(file));
  return hash.digest('hex').slice(0, 16);
}

export default function remarkIllustrations() {
  return (tree, vfile) => transform(tree, vfile?.path ?? 'markdown');
}
