// Import repository markdown into the Starlight content collection.
//
// The files under ../docs remain the single source of truth (ADR-038 §4.1).
// This script runs before `astro dev` and `astro build` and regenerates the
// imported pages from scratch every time, so an imported page cannot drift.
//
// It also mirrors the Gradle `:tooling:studio:prepareStudio` task into
// public/studio/, so the "Open this outcome in App-Chain Studio" links in the
// tutorials resolve to a working app on the docsite instead of to HTML source
// on GitHub.
//
// Link rewriting is path-resolution based, not pattern based: a relative link
// is resolved against its source file's directory to a repository-root-relative
// path, and then mapped to a site route if that path is published here, or to a
// GitHub blob/tree URL otherwise. An unresolvable link fails the build.

import fs from 'node:fs/promises';
import path from 'node:path';
import posix from 'node:path/posix';
import {
  CONTENT_ROOT,
  DOCSITE_ROOT,
  REPO_ROOT,
  GITHUB_REPO,
  IMPORTED_DOCS,
  AUTHORED_EQUIVALENTS,
  repoPath,
  readGradleProperties,
} from './repo-sources.mjs';
import {
  generateCatalog,
  injectCatalogBlocks,
  CATALOG_BLOCK_NAMES,
} from './generate-catalog.mjs';

const GITHUB_BLOB = `${GITHUB_REPO}/blob/main`;
const GITHUB_TREE = `${GITHUB_REPO}/tree/main`;

// Repository paths that are internal and must never become a published link.
// A link resolving into one of these is reduced to its plain label text rather
// than rewritten to a GitHub URL: ADRs are point-in-time decisions, not
// documentation, and several are explicitly marked pre-split evidence.
const UNLINKED_PREFIXES = ['adr/'];

// Directories this script owns completely. They are wiped before each import
// so a renamed source file cannot leave a stale page behind.
const OWNED_DIRS = ['tutorials', 'state-machines', 'deployment'];

// Section landing pages get an explicit sidebar order.
const ORDER = {
  '/tutorials/': 0,
  '/state-machines/': 0,
};

// ---------------------------------------------------------------------------
// Frontmatter extraction
// ---------------------------------------------------------------------------

function yamlQuote(value) {
  return `"${String(value).replace(/\\/g, '\\\\').replace(/"/g, '\\"')}"`;
}

/** Lift the leading `# Heading` out of the body and return it as the title. */
function extractTitle(body, fallback) {
  const match = body.match(/^#\s+(.+?)\s*$/m);
  if (!match) return { title: fallback, body };
  const title = match[1].replace(/\s*\{#.*\}$/, '').trim();
  return { title, body: body.replace(/^#\s+.+\r?\n+/m, '') };
}

/**
 * Derive a one-line description. The tutorials carry an explicit
 * `- **Outcome:** …` line, which is a better summary than their first
 * paragraph; everything else falls back to the first prose paragraph.
 */
function extractDescription(body, title) {
  // `- **Outcome:** …` frequently wraps onto indented continuation lines, so
  // take the whole bullet rather than only its first line.
  const outcome = body.match(/^-\s+\*\*Outcome:\*\*\s+([\s\S]+?)(?=\n(?:[-*]\s|#|\s*\n))/m);
  if (outcome) return collapse(outcome[1]);

  // Otherwise use the first prose paragraph, joined across its wrapped lines.
  const paragraph = [];
  let inFence = false;
  for (const line of body.split('\n')) {
    if (line.trimStart().startsWith('```')) { inFence = !inFence; continue; }
    if (inFence) continue;
    const t = line.trim();
    if (!t) {
      if (paragraph.length) break;
      continue;
    }
    if (!paragraph.length &&
        (t.startsWith('#') || t.startsWith('|') || t.startsWith('>') ||
         t.startsWith('-') || t.startsWith('*') || t.startsWith('[') ||
         t.startsWith('<') || t.startsWith(':::'))) continue;
    if (!paragraph.length && t.startsWith('!')) continue;
    paragraph.push(t);
  }
  if (paragraph.length) return collapse(paragraph.join(' '));
  return `${title} — Yano X documentation`;
}

/**
 * Reduce markdown to a single plain sentence-ish line, cut on a word boundary
 * so a truncated description never ends mid-word.
 */
function collapse(text) {
  let plain = text
    .replace(/\[([^\]]+)\]\([^)]*\)/g, '$1')
    .replace(/[`*_]/g, '')
    .replace(/\s+/g, ' ')
    .trim();
  // Do not capitalize an identifier such as `kv-registry` that starts the line.
  if (!/^[a-z0-9]+(?:-[a-z0-9]+)+\b/.test(plain)) {
    plain = plain.charAt(0).toUpperCase() + plain.slice(1);
  }
  if (plain.length <= 158) return plain;
  const cut = plain.slice(0, 155);
  const lastSpace = cut.lastIndexOf(' ');
  return `${(lastSpace > 80 ? cut.slice(0, lastSpace) : cut).replace(/[,;:.\s]+$/, '')}…`;
}

/**
 * Starlight renders its own table of contents in the right sidebar, so the
 * hand-maintained "On this page" list in the tutorials is redundant here.
 */
function stripInlineToc(body) {
  return body.replace(
    /^## (?:On this page|Table of Contents)\s*\r?\n[\s\S]*?(?=^## )/gm,
    '',
  );
}

// ---------------------------------------------------------------------------
// Link rewriting
// ---------------------------------------------------------------------------

const linkPattern = /(!?\[[^\]]*\])\(([^)\s]+)\)/g;

function isExternal(target) {
  return /^(?:[a-z][a-z0-9+.-]*:|\/\/|\/|#)/i.test(target);
}

async function classifyRepoPath(relPath) {
  try {
    const stat = await fs.stat(repoPath(relPath));
    return stat.isDirectory() ? 'dir' : 'file';
  } catch {
    return 'missing';
  }
}

/**
 * Rewrite every relative link in one imported file.
 * Returns the rewritten body plus any links that could not be resolved.
 */
async function rewriteLinks(body, sourceRel) {
  const sourceDir = posix.dirname(sourceRel);
  const unresolved = [];
  const replacements = new Map();

  for (const match of body.matchAll(linkPattern)) {
    const target = match[2];
    if (replacements.has(target) || isExternal(target)) continue;

    const hashAt = target.indexOf('#');
    const rawPath = hashAt === -1 ? target : target.slice(0, hashAt);
    const anchor = hashAt === -1 ? '' : target.slice(hashAt);
    const resolved = posix.normalize(posix.join(sourceDir, decodeURI(rawPath)));

    if (resolved.startsWith('..')) {
      unresolved.push({ target, reason: 'escapes the repository root' });
      continue;
    }

    // 1. Another imported page, or an authored page that stands in for it.
    const siteRoute = IMPORTED_DOCS[resolved] ?? AUTHORED_EQUIVALENTS[resolved];
    if (siteRoute) {
      replacements.set(target, `${siteRoute}${anchor}`);
      continue;
    }

    // 2. The App-Chain Studio, mirrored into public/studio/ below.
    if (resolved.startsWith('tooling/studio/src/main/web/')) {
      const file = resolved.slice('tooling/studio/src/main/web/'.length);
      replacements.set(target, `/studio/${file}${anchor}`);
      continue;
    }

    // 3. Internal-only paths lose their link and keep just their text.
    if (UNLINKED_PREFIXES.some((prefix) => resolved.startsWith(prefix))) {
      replacements.set(target, null);
      continue;
    }

    // 4. Anything else in the repository: link to GitHub.
    const kind = await classifyRepoPath(resolved);
    if (kind === 'missing') {
      unresolved.push({ target, reason: `no such path in the repository (${resolved})` });
      continue;
    }
    const base = kind === 'dir' ? GITHUB_TREE : GITHUB_BLOB;
    replacements.set(target, `${base}/${resolved}${anchor}`);
  }

  const rewritten = body.replace(linkPattern, (whole, label, target) => {
    if (!replacements.has(target)) return whole;
    const next = replacements.get(target);
    // null means "internal path": drop the link, keep the label text.
    if (next === null) return label.replace(/^!?\[(.*)\]$/s, '$1');
    return `${label}(${next})`;
  });

  return { body: rewritten, unresolved };
}

// ---------------------------------------------------------------------------
// Destination mapping
// ---------------------------------------------------------------------------

/** `/tutorials/` -> `tutorials/index.md`; `/tutorials/01-x/` -> `tutorials/01-x.md`. */
function destinationFor(route) {
  const slug = route.replace(/^\//, '').replace(/\/$/, '');
  return slug.includes('/') ? `${slug}.md` : `${slug}/index.md`;
}

// ---------------------------------------------------------------------------
// App-Chain Studio mirror (mirrors :tooling:studio:prepareStudio)
// ---------------------------------------------------------------------------

const STUDIO_WEB = 'tooling/studio/src/main/web';
const STUDIO_ASSETS_SRC = 'tooling/devtools/src/main/resources/appchain-dx/v1alpha1';
const STUDIO_ASSETS = [
  'appchain-blueprint.schema.json',
  'appchain-capability-catalog.json',
  'appchain-recipe-catalog.json',
  'appchain-release-capability-index.json',
  'appchain-release-acceptance-index.json',
];

async function mirrorStudio(versions) {
  const dest = path.join(DOCSITE_ROOT, 'public/studio');
  await fs.rm(dest, { recursive: true, force: true });
  await fs.mkdir(path.join(dest, 'assets'), { recursive: true });

  for (const entry of await fs.readdir(repoPath(STUDIO_WEB))) {
    await fs.copyFile(repoPath(STUDIO_WEB, entry), path.join(dest, entry));
  }

  for (const asset of STUDIO_ASSETS) {
    let text = await fs.readFile(repoPath(STUDIO_ASSETS_SRC, asset), 'utf8');
    // The Gradle task expands this one placeholder with the Yano X version.
    if (asset === 'appchain-release-capability-index.json') {
      text = text.replaceAll('${yanoVersion}', versions.version ?? 'unknown');
    }
    await fs.writeFile(path.join(dest, 'assets', asset), text, 'utf8');
  }

  return STUDIO_ASSETS.length + 5;
}

// ---------------------------------------------------------------------------
// Catalog injection into authored pages
// ---------------------------------------------------------------------------

async function* walkMarkdown(dir) {
  let entries;
  try {
    entries = await fs.readdir(dir, { withFileTypes: true });
  } catch {
    return;
  }
  for (const entry of entries) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) yield* walkMarkdown(full);
    else if (/\.mdx?$/.test(entry.name)) yield full;
  }
}

const ANCHOR_PATTERN = /<!--\s*catalog:([a-z-]+)-start\s*-->/g;

/**
 * Regenerate every `<!-- catalog:<name> -->` block in the authored pages.
 * The pages stay committed and reviewable; only the region between each pair
 * of anchors is rewritten, which is how the repository's own generated tables
 * already work.
 */
async function injectCatalogs(catalog, problems, directory = CONTENT_ROOT) {
  let updated = 0;
  let blocks = 0;

  for await (const file of walkMarkdown(directory)) {
    const before = await fs.readFile(file, 'utf8');
    const names = [...before.matchAll(ANCHOR_PATTERN)].map((m) => m[1]);
    if (names.length === 0) continue;

    const rel = path.relative(DOCSITE_ROOT, file);
    for (const name of names) {
      if (!CATALOG_BLOCK_NAMES.includes(name)) {
        problems.push(
          `${rel}: unknown catalog block "${name}" ` +
          `(known: ${CATALOG_BLOCK_NAMES.join(', ')})`,
        );
        continue;
      }
      if (!before.includes(`<!-- catalog:${name}-end -->`)) {
        problems.push(`${rel}: catalog block "${name}" has no matching end anchor`);
      }
      blocks += 1;
    }

    const after = injectCatalogBlocks(before, catalog);
    if (after !== before) {
      await fs.writeFile(file, after, 'utf8');
      updated += 1;
    }
  }

  return { updated, blocks };
}

// ---------------------------------------------------------------------------
// Main
// ---------------------------------------------------------------------------

async function main() {
  const versions = await readGradleProperties();

  for (const dir of OWNED_DIRS) {
    await fs.rm(path.join(CONTENT_ROOT, dir), { recursive: true, force: true });
  }

  const problems = [];
  const catalog = await generateCatalog({ logger: { info: (m) => console.log(m) } });
  // Refresh canonical tables before importing their pages.
  await injectCatalogs(catalog, problems, repoPath('docs/site'));
  let written = 0;

  for (const [sourceRel, route] of Object.entries(IMPORTED_DOCS)) {
    let raw;
    try {
      raw = await fs.readFile(repoPath(sourceRel), 'utf8');
    } catch (err) {
      problems.push(`${sourceRel}: cannot read (${err.code})`);
      continue;
    }

    const fallback = posix.basename(sourceRel, '.md');
    const { title, body: titleless } = extractTitle(raw, fallback);
    const description = extractDescription(titleless, title);
    const detocd = stripInlineToc(titleless);
    const { body, unresolved } = await rewriteLinks(detocd, sourceRel);

    for (const u of unresolved) {
      problems.push(`${sourceRel}: unresolved link "${u.target}" — ${u.reason}`);
    }

    const frontmatter = [
      '---',
      `title: ${yamlQuote(title)}`,
      `description: ${yamlQuote(description)}`,
      ...(ORDER[route] !== undefined
        ? ['sidebar:', `  order: ${ORDER[route]}`]
        : []),
      `editUrl: ${yamlQuote(`${GITHUB_REPO}/edit/main/${sourceRel}`)}`,
      '---',
      '',
    ].join('\n');

    const destPath = path.join(CONTENT_ROOT, destinationFor(route));
    await fs.mkdir(path.dirname(destPath), { recursive: true });
    await fs.writeFile(destPath, `${frontmatter}${body.trim()}\n`, 'utf8');
    written += 1;
  }

  const studioFiles = await mirrorStudio(versions);

  const { updated, blocks } = await injectCatalogs(catalog, problems);

  if (problems.length > 0) {
    console.error('\n[import-repo-docs] import failed:\n');
    for (const p of problems) console.error(`  - ${p}`);
    console.error('');
    process.exit(1);
  }

  console.log(
    `[import-repo-docs] ${written} page(s) imported from ${path.relative(process.cwd(), REPO_ROOT) || '.'}/docs, ` +
    `${studioFiles} Studio file(s) mirrored to public/studio/, ` +
    `${blocks} catalog block(s) rendered into ${updated} page(s)`,
  );
}

await main();
