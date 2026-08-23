// Generate the AI ingestion artifacts (ADR-038 §5).
//
//   /llms.txt            curated index following the llmstxt.org convention
//   /llms-full.txt       every page concatenated, for direct ingestion
//   /ai/starter-pack.md  raw markdown, fetchable as CLAUDE.md / a Cursor rule
//   /ai/index.md         raw markdown copy of the AI landing page
//
// Pages are read from the content collection *after* `import-repo-docs.mjs`
// has run, so imported tutorials and rendered catalog tables are included.

import fs from 'node:fs/promises';
import path from 'node:path';
import {
  CONTENT_ROOT,
  SITE_URL,
  GITHUB_REPO,
  YANO_REPO,
  IMPORTED_DOCS,
  contentPathForRoute,
} from './repo-sources.mjs';
import { generateCatalog } from './generate-catalog.mjs';

// Curated section order — mirrors the sidebar in astro.config.mjs. Files are
// relative to src/content/docs/. A missing file is skipped with a warning so a
// rename cannot break the build; an *unlisted* file is appended to "Other" and
// warned about, so it is never silently absent.
/**
 * Content paths for every page imported into one section, index first.
 * Derived from IMPORTED_DOCS so the two lists cannot drift apart.
 */
function importedFiles(section) {
  return Object.values(IMPORTED_DOCS)
    .filter((route) => route.startsWith(`/${section}/`))
    .map(contentPathForRoute)
    .sort((a, b) => {
      const ai = a.endsWith('/index.md') ? 0 : 1;
      const bi = b.endsWith('/index.md') ? 0 : 1;
      return ai - bi || a.localeCompare(b);
    });
}

const SECTIONS = [
  {
    title: 'AI',
    files: ['ai/index.md', 'ai/starter-pack.md'],
  },
  {
    title: 'Start here',
    files: [
      'start-here/what-is-an-app-chain.md',
      'start-here/why-yano-x.md',
      'start-here/build-from-source.md',
      'start-here/quickstart.md',
    ],
  },
  {
    title: 'Concepts',
    files: [
      'concepts/architecture.md',
      'concepts/consensus-and-finality.md',
      'concepts/state-and-proofs.md',
      'concepts/effects.md',
      'concepts/anchoring.md',
      'concepts/determinism-rules.md',
    ],
  },
  {
    title: 'Recipes',
    files: ['recipes/index.md', 'recipes/choosing-a-recipe.md'],
  },
  {
    title: 'Plugin framework',
    files: [
      'plugins/index.md',
      'plugins/scaffold-sign-install.md',
      'plugins/spi-and-manifest.md',
      'plugins/consensus-rules.md',
      'plugins/testing-and-deployment.md',
    ],
  },
  // Imported sections are derived from IMPORTED_DOCS rather than retyped, so
  // adding a tutorial is a one-place edit in repo-sources.mjs.
  { title: 'Tutorials', files: importedFiles('tutorials') },
  { title: 'State machines', files: importedFiles('state-machines') },
  {
    title: 'Products',
    files: [
      'products/index.md',
      'products/evidence.md',
      'products/cardano-history.md',
      'products/eutxo-and-zk.md',
    ],
  },
  {
    title: 'Reference',
    files: [
      'reference/cli.md',
      'reference/rest-api.md',
      'reference/capabilities.md',
      'reference/configuration.md',
      'reference/modules.md',
      'reference/shelf.md',
    ],
  },
  {
    title: 'Contributing',
    files: ['contributing/index.md'],
  },
];

// ---------------------------------------------------------------------------
// Markdown helpers
// ---------------------------------------------------------------------------

function stripFrontmatter(content) {
  if (!content.startsWith('---')) return { frontmatter: {}, body: content };
  const end = content.indexOf('\n---', 4);
  if (end === -1) return { frontmatter: {}, body: content };
  const raw = content.slice(4, end).trim();
  const body = content.slice(end + 4).replace(/^\n+/, '');
  const frontmatter = {};
  for (const line of raw.split('\n')) {
    const m = line.match(/^([A-Za-z_][A-Za-z0-9_]*):\s*(.*)$/);
    if (m) frontmatter[m[1]] = m[2].replace(/^["']|["']$/g, '').trim();
  }
  return { frontmatter, body };
}

function slugFromFile(rel) {
  const noExt = rel.replace(/\.(md|mdx)$/, '');
  return noExt.endsWith('/index') ? noExt.replace(/\/index$/, '') : noExt;
}

function urlFromFile(rel) {
  const slug = slugFromFile(rel);
  return slug ? `${SITE_URL}/${slug}/` : `${SITE_URL}/`;
}

/** Turn site-relative links into absolute ones so an ingested dump stays usable. */
function absolutizeLinks(body) {
  return body.replace(/(\]\()(\/[^)\s]*)\)/g, (_, prefix, href) => `${prefix}${SITE_URL}${href})`);
}

/**
 * Remove MDX/Starlight syntax that confuses a plain-markdown consumer.
 *
 * Aside markers are *converted*, not deleted: `:::danger[Key handling]` carries
 * real weight in the starter pack, and dropping it would flatten a safety
 * warning into an ordinary sentence for the agents most likely to read it.
 */
function sanitizeForLlms(body) {
  return body
    .replace(/^\s*import\s.+?from\s+['"][^'"]+['"];?\s*$/gm, '')
    .replace(
      /^:::([a-z]+)(?:\[([^\]]*)\])?\s*$/gm,
      (_, kind, title) => `**${(title || kind).toUpperCase()}:**`,
    )
    .replace(/^:::\s*$/gm, '')
    .replace(/<[A-Z][A-Za-z0-9]*\s[^>]*\/>/g, '')
    .replace(/<([A-Z][A-Za-z0-9]*)(\s[^>]*)?>([\s\S]*?)<\/\1>/g, '$3')
    .replace(/\n{3,}/g, '\n\n');
}

function demoteHeadings(body, by = 2) {
  return body.replace(/^(#{1,4})\s/gm, (_, hashes) => `${'#'.repeat(hashes.length + by)} `);
}

async function listAllDocs(root) {
  const out = [];
  async function walk(dir, prefix) {
    let entries;
    try {
      entries = await fs.readdir(dir, { withFileTypes: true });
    } catch {
      return;
    }
    for (const entry of entries) {
      const full = path.join(dir, entry.name);
      const rel = prefix ? `${prefix}/${entry.name}` : entry.name;
      if (entry.isDirectory()) await walk(full, rel);
      else if (/\.(md|mdx)$/.test(entry.name)) out.push(rel);
    }
  }
  await walk(root, '');
  out.sort();
  return out;
}

async function readDoc(rel) {
  try {
    const raw = await fs.readFile(path.join(CONTENT_ROOT, rel), 'utf8');
    const { frontmatter, body } = stripFrontmatter(raw);
    return { ok: true, rel, frontmatter, body };
  } catch (err) {
    return { ok: false, rel, error: err.message };
  }
}

// ---------------------------------------------------------------------------
// Key facts block — what an agent must know before generating anything
// ---------------------------------------------------------------------------

function keyFacts(catalog) {
  const v = catalog.versions;
  return [
    '- **Yano X is an extension ecosystem, not a blockchain node.** The host is Yano',
    `  (${YANO_REPO}); Yano X adds optional state machines, capabilities, connectors,`,
    '  products, SDKs, and the batteries-included JVM distribution. The dependency',
    '  direction is strictly `yano-x -> yano` and must never be inverted, nor satisfied',
    '  with a composite Gradle build or a sibling source checkout.',
    `- **Versions:** Yano X \`${v.yanoXVersion}\` builds against Yano \`${v.yanoVersion}\`, Java ${v.javaVersion}.`,
    '  There is no published Yano X release yet; users build from source.',
    '- **Yano X is JVM-only.** Never add GraalVM/native-image tasks, reachability',
    '  metadata, or native executables. `verifyJvmOnlyBuild` enforces this.',
    '- **Package names stay `com.bloxbean.cardano.yano.appchain.*`** even though',
    '  repository and artifact names are `yano-x`. This is deliberate, not a leftover.',
    '- **The plugin directory property is `yano.plugins.directory`.** Never',
    '  `yaci.plugins.directory`.',
    '- **Every optional runtime behavior crosses the plugin catalog boundary** and is',
    '  activated through `PluginProviderRegistry` plus a schema-v1 plugin manifest.',
    '  Never raw `ServiceLoader`, direct host construction, product switches, or',
    '  product-specific host CDI/REST activation.',
    '- **Deterministic state-machine code may not** use wall-clock time, randomness,',
    '  environment-dependent iteration order, network calls, or node-local mutable',
    '  decisions. External work is emitted as an effect record and executed later by',
    '  the effect runtime, never called during `apply`.',
    `- **Choose the smallest extension:** configuration (${catalog.counts.recipes} stock recipes,`,
    `  ${catalog.counts.capabilities} capabilities) -> a small composite plugin -> a custom`,
    '  state-machine plugin. Only the last one adds new consensus semantics.',
    '- **The public CLI is `./yano.sh appchain …`**, shipped inside the Yano X JVM',
    '  distribution. There is no separate `yano-x` executable.',
    '- **Changing deterministic application semantics is a versioned consensus',
    '  upgrade**, not an ordinary rolling code change.',
  ].join('\n');
}

// ---------------------------------------------------------------------------
// Generation
// ---------------------------------------------------------------------------

export async function generateLlmsFiles({ outDir, logger, catalog }) {
  const log = (msg) => (logger?.info ? logger.info(msg) : console.log(msg));
  const catalogData = catalog ?? (await generateCatalog({ logger: { info: () => {} } }));
  const v = catalogData.versions;
  const generatedAt = new Date().toISOString();

  const resolved = [];
  const included = new Set();
  for (const section of SECTIONS) {
    const items = [];
    for (const f of section.files) {
      const d = await readDoc(f);
      if (d.ok) {
        items.push(d);
        included.add(d.rel);
      } else {
        log(`[llms-txt] skip ${f} (${d.error})`);
      }
    }
    if (items.length) resolved.push({ title: section.title, items });
  }

  // Anything the curated SECTIONS list forgot still gets published, but loudly.
  // 404.md is a site chrome page, not documentation; never publish it to agents.
  const EXCLUDED = new Set(['404.md']);
  const unlisted = (await listAllDocs(CONTENT_ROOT))
    .filter((rel) => !included.has(rel) && !EXCLUDED.has(rel));
  if (unlisted.length) {
    const items = [];
    for (const rel of unlisted) {
      const d = await readDoc(rel);
      if (!d.ok) continue;
      items.push(d);
      const msg = `[llms-txt] WARNING: ${rel} is not listed in SECTIONS — appending to "Other"`;
      if (process.env.YANO_X_REQUIRE_LISTED_DOCS === '1') throw new Error(msg);
      log(msg);
    }
    if (items.length) resolved.push({ title: 'Other', items });
  }

  // ---------- llms.txt ----------
  const index = [];
  index.push('# Yano X');
  index.push('');
  index.push(`yanoXVersion: ${v.yanoXVersion}`);
  index.push(`yanoVersion: ${v.yanoVersion}`);
  index.push(`generatedAt: ${generatedAt}`);
  index.push('');
  index.push(
    '> Yano X is the Java 25, JVM-only extension ecosystem for Yano app chains. ' +
    'An app chain is an application-specific replicated ledger with deterministic ' +
    'state, threshold finality, MPF proofs, optional Cardano anchoring, and ' +
    'controlled external effects. Yano X supplies the stock state machines, ' +
    'capabilities, connectors, products, SDKs, and the batteries-included JVM ' +
    'distribution that run on top of the Yano host.',
  );
  index.push('');
  index.push('Key facts an AI agent should know before generating Yano X code or configuration:');
  index.push('');
  index.push(keyFacts(catalogData));
  index.push('');
  index.push('## Start here for AI agents');
  index.push('');
  index.push(
    `- [AI Starter Pack](${SITE_URL}/ai/starter-pack/): the single best file to ingest. ` +
    'The extension ladder, the determinism rules, the plugin lifecycle, the ' +
    'invariants that look like typos, and the error-to-fix table.',
  );
  index.push(
    `- [llms-full.txt](${SITE_URL}/llms-full.txt): every page concatenated into one ` +
    'markdown file. Ingest for full coverage.',
  );
  index.push(
    `- [catalog.json](${SITE_URL}/ai/catalog.json): machine-readable recipes, ` +
    'capabilities, runtime artifacts, Gradle modules, configuration properties, and ' +
    'versions, generated from the repository at build time.',
  );
  index.push(
    `- [Using Yano X with AI agents](${SITE_URL}/ai/): install snippets for Claude Code, ` +
    'Cursor, Continue, and ChatGPT.',
  );
  index.push('');

  for (const section of resolved) {
    index.push(`## ${section.title}`);
    index.push('');
    for (const d of section.items) {
      const title = d.frontmatter.title || slugFromFile(d.rel);
      const desc = d.frontmatter.description ? `: ${d.frontmatter.description}` : '';
      index.push(`- [${title}](${urlFromFile(d.rel)})${desc}`);
    }
    index.push('');
  }

  index.push('## Source code');
  index.push('');
  index.push(`- Yano X: ${GITHUB_REPO}`);
  index.push(`- Yano (host): ${YANO_REPO}`);
  index.push(`- Base Yano JVM distribution: ${v.yanoJvmZipUrl}`);
  index.push(
    `- App-Chain Studio (blueprint builder): ${SITE_URL}/studio/`,
  );
  index.push('');

  // ---------- llms-full.txt ----------
  const full = [];
  full.push('# Yano X — Full Documentation (concatenated for AI ingestion)');
  full.push('');
  full.push('> Single-file dump of the Yano X docsite, suitable for AI agent ingestion.');
  full.push('');
  full.push(`yanoXVersion: ${v.yanoXVersion}`);
  full.push(`yanoVersion: ${v.yanoVersion}`);
  full.push(`Generated: ${generatedAt}`);
  full.push(`Site: ${SITE_URL}`);
  full.push(`Repo: ${GITHUB_REPO}`);
  full.push('');
  full.push('## Key facts');
  full.push('');
  full.push(keyFacts(catalogData));
  full.push('');
  full.push('## Table of contents');
  full.push('');
  for (const section of resolved) {
    for (const d of section.items) {
      const t = d.frontmatter.title || slugFromFile(d.rel);
      full.push(`- [${section.title} → ${t}](${urlFromFile(d.rel)})`);
    }
  }
  full.push('');

  for (const section of resolved) {
    full.push('---');
    full.push('');
    full.push(`# ${section.title}`);
    full.push('');
    for (const d of section.items) {
      const t = d.frontmatter.title || slugFromFile(d.rel);
      full.push('---');
      full.push('');
      full.push(`## ${t}`);
      full.push('');
      full.push(`Source: ${urlFromFile(d.rel)}`);
      if (d.frontmatter.description) {
        full.push('');
        full.push(`> ${d.frontmatter.description}`);
      }
      full.push('');
      full.push(demoteHeadings(sanitizeForLlms(absolutizeLinks(d.body)), 2).trim());
      full.push('');
    }
  }

  await fs.mkdir(outDir, { recursive: true });
  const llmsTxtPath = path.join(outDir, 'llms.txt');
  const llmsFullPath = path.join(outDir, 'llms-full.txt');
  await fs.writeFile(llmsTxtPath, `${index.join('\n')}\n`, 'utf8');
  await fs.writeFile(llmsFullPath, `${full.join('\n')}\n`, 'utf8');

  const indexBytes = Buffer.byteLength(index.join('\n'), 'utf8');
  const fullBytes = Buffer.byteLength(full.join('\n'), 'utf8');
  log(`[llms-txt] wrote ${llmsTxtPath} (${index.length} lines, ${indexBytes} bytes)`);
  log(`[llms-txt] wrote ${llmsFullPath} (${full.length} lines, ${fullBytes} bytes)`);

  // Raw markdown copies, so `curl -o CLAUDE.md https://yanox.dev/ai/starter-pack.md`
  // returns markdown rather than rendered HTML.
  for (const rel of ['ai/starter-pack.md', 'ai/index.md']) {
    const d = await readDoc(rel);
    if (!d.ok) {
      log(`[llms-txt] skip raw copy ${rel} (${d.error})`);
      continue;
    }
    const dst = path.join(outDir, rel);
    await fs.mkdir(path.dirname(dst), { recursive: true });
    const cleaned = sanitizeForLlms(absolutizeLinks(d.body));
    const stamped = rel === 'ai/starter-pack.md'
      ? `<!-- yanoXVersion: ${v.yanoXVersion}; yanoVersion: ${v.yanoVersion} -->\n\n${cleaned}`
      : cleaned;
    await fs.writeFile(dst, stamped, 'utf8');
    log(`[llms-txt] wrote ${dst} (raw md)`);
  }

  return { llmsTxtPath, llmsFullPath, indexBytes, fullBytes };
}

// Direct execution for local testing:  node scripts/generate-llms-txt.mjs [outDir]
const invokedDirectly = (() => {
  try {
    return import.meta.url === `file://${path.resolve(process.argv[1] ?? '')}`;
  } catch {
    return false;
  }
})();

if (invokedDirectly) {
  await generateLlmsFiles({ outDir: process.argv[2] ?? path.join(CONTENT_ROOT, '../../../dist') });
}
