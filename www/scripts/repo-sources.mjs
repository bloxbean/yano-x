// Shared access to the Yano X repository's canonical, machine-readable sources.
//
// Nothing in the docsite hardcodes a version, a recipe list, a capability id,
// or a module name. Every such value is read from a file the Gradle build
// already maintains and release-gates:
//
//   gradle.properties                              versions
//   config/artifacts-v1.json                       module + artifact inventory
//   tooling/devtools/.../appchain-recipe-catalog.json
//   tooling/devtools/.../appchain-capability-catalog.json
//   tooling/devtools/.../appchain-release-capability-index.json
//   tooling/devtools/.../appchain-first-party-metadata.json
//
// See ADR-038 §4.3.

import fs from 'node:fs/promises';
import { existsSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

/**
 * Locate the repository root.
 *
 * This module is loaded two ways: directly by the Node scripts under
 * `www/scripts/`, and bundled by Vite when `src/pages/index.astro` imports
 * the catalog generator. In the bundled case `import.meta.url` points into
 * `dist/`, so a plain `__dirname/..` would resolve to the build output. Probe
 * the candidates instead and accept the first one that actually looks like the
 * Yano X checkout.
 */
function findRepoRoot() {
  const marks = ['gradle.properties', 'settings.gradle'];
  const looksRight = (dir) => marks.every((m) => existsSync(path.join(dir, m)));

  const candidates = [path.resolve(__dirname, '../..')];
  for (let dir = process.cwd(); ; dir = path.dirname(dir)) {
    candidates.push(dir);
    if (dir === path.dirname(dir)) break;
  }

  const found = candidates.find(looksRight);
  if (!found) {
    throw new Error(
      'Could not locate the Yano X repository root (no directory containing ' +
      `${marks.join(' and ')} among: ${candidates.join(', ')}).`,
    );
  }
  return found;
}

/** Repository root. */
export const REPO_ROOT = findRepoRoot();
/** the documentation site, `www/` */
export const DOCSITE_ROOT = path.join(REPO_ROOT, 'www');
/** `www/src/content/docs` */
export const CONTENT_ROOT = path.join(DOCSITE_ROOT, 'src/content/docs');

export const SITE_URL = 'https://yanox.dev';
export const GITHUB_REPO = 'https://github.com/bloxbean/yano-x';
export const GITHUB_BLOB = `${GITHUB_REPO}/blob/main`;
export const YANO_REPO = 'https://github.com/bloxbean/yano';

const DX_DIR = 'tooling/devtools/src/main/resources/appchain-dx/v1alpha1';

/** Absolute path for a repository-root-relative path. */
export function repoPath(...parts) {
  return path.join(REPO_ROOT, ...parts);
}

/** Canonical GitHub blob URL for a repository-root-relative path. */
export function githubUrl(relPath, anchor = '') {
  const clean = relPath.split(path.sep).join('/').replace(/^\.\//, '');
  return `${GITHUB_BLOB}/${clean}${anchor}`;
}

async function readJson(relPath) {
  const raw = await fs.readFile(repoPath(relPath), 'utf8');
  return JSON.parse(raw);
}

/**
 * Parse `gradle.properties` into a plain object. Comments and blank lines are
 * skipped; only `key=value` lines are kept.
 */
export async function readGradleProperties() {
  const raw = await fs.readFile(repoPath('gradle.properties'), 'utf8');
  const out = {};
  for (const line of raw.split('\n')) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith('#') || trimmed.startsWith('!')) continue;
    const eq = trimmed.indexOf('=');
    if (eq === -1) continue;
    out[trimmed.slice(0, eq).trim()] = trimmed.slice(eq + 1).trim();
  }
  return out;
}

/**
 * The version facts the docs quote. `yanoVersion` in particular determines the
 * base JVM ZIP the Gradle build downloads, so it must never be typed by hand
 * into a page.
 */
export async function resolveVersions() {
  const props = await readGradleProperties();
  const yanoVersion = props.yanoVersion ?? 'unknown';
  return {
    /** Yano X's own artifact/plugin version. */
    yanoXVersion: props.version ?? 'unknown',
    /** The exact Yano host line Yano X is built against. */
    yanoVersion,
    group: props.group ?? 'com.bloxbean.cardano',
    javaVersion: '25',
    /** Resolved automatically by the Gradle build for a released yanoVersion. */
    yanoJvmZipUrl: `${YANO_REPO}/releases/download/v${yanoVersion}/yano-${yanoVersion}.zip`,
  };
}

export const loadRecipeCatalog = () => readJson(`${DX_DIR}/appchain-recipe-catalog.json`);
export const loadCapabilityCatalog = () => readJson(`${DX_DIR}/appchain-capability-catalog.json`);
export const loadReleaseIndex = () => readJson(`${DX_DIR}/appchain-release-capability-index.json`);
export const loadFirstPartyMetadata = () => readJson(`${DX_DIR}/appchain-first-party-metadata.json`);
export const loadArtifactInventory = () => readJson('config/artifacts-v1.json');

/**
 * Every documentation page reachable from the site, keyed by the
 * repository-relative markdown path it was imported from. Populated by
 * `import-repo-docs.mjs` and consumed by its link rewriter. Kept here so the
 * mapping has exactly one definition.
 */
export const IMPORTED_DOCS = {
  'docs/RELEASE_DOWNLOADS.md': '/start-here/release-downloads/',
  'docs/appchain/deployment/README.md': '/deployment/',
  'docs/appchain/deployment/quickstart.md': '/start-here/quickstart/',
  'docs/appchain/deployment/configure.md': '/deployment/configure/',
  'docs/appchain/deployment/add-chain.md': '/deployment/add-chain/',
  'docs/appchain/deployment/operators.md': '/deployment/operators/',
  // docs/appchain/tutorials/*  ->  /tutorials/*
  'docs/appchain/tutorials/README.md': '/tutorials/',
  'docs/appchain/tutorials/01-first-app-chain.md': '/tutorials/01-first-app-chain/',
  'docs/appchain/tutorials/02-registry-and-proofs.md': '/tutorials/02-registry-and-proofs/',
  'docs/appchain/tutorials/03-stock-state-machines.md': '/tutorials/03-stock-state-machines/',
  'docs/appchain/tutorials/04-evidence-publication.md': '/tutorials/04-evidence-publication/',
  'docs/appchain/tutorials/05-domain-role-approvals.md': '/tutorials/05-domain-role-approvals/',
  'docs/appchain/tutorials/06-webhook-effects.md': '/tutorials/06-webhook-effects/',
  'docs/appchain/tutorials/07-anchors-and-verification.md': '/tutorials/07-anchors-and-verification/',
  'docs/appchain/tutorials/08-plugins-and-composites.md': '/tutorials/08-plugins-and-composites/',
  'docs/appchain/tutorials/09-from-demo-to-pilot.md': '/tutorials/09-from-demo-to-pilot/',

  // docs/appchain/state-machines/*  ->  /state-machines/*
  'docs/appchain/state-machines/README.md': '/state-machines/',
  'docs/appchain/state-machines/approvals.md': '/state-machines/approvals/',
  'docs/appchain/state-machines/authenticated-map.md': '/state-machines/authenticated-map/',
  'docs/appchain/state-machines/authenticated-map-validation.md': '/state-machines/authenticated-map-validation/',
  'docs/appchain/state-machines/balances.md': '/state-machines/balances/',
  'docs/appchain/state-machines/doc-trail.md': '/state-machines/doc-trail/',
  'docs/appchain/state-machines/kv-registry.md': '/state-machines/kv-registry/',
  'docs/appchain/state-machines/role-approvals.md': '/state-machines/role-approvals/',
};

/**
 * Content-collection path for an imported route.
 * `/tutorials/` -> `tutorials/index.md`; `/tutorials/01-x/` -> `tutorials/01-x.md`.
 * Shared by the importer (which writes the file) and the llms.txt generator
 * (which reads it back), so the two cannot disagree.
 */
export function contentPathForRoute(route) {
  const slug = route.replace(/^\//, '').replace(/\/$/, '');
  return slug.includes('/') ? `${slug}.md` : `${slug}/index.md`;
}

/**
 * Authored site pages that stand in for a repository document. A relative link
 * in an imported file that points at one of these is rewritten to the site
 * page instead of to GitHub, so imported and authored content interlink.
 */
export const AUTHORED_EQUIVALENTS = {
  'docs/appchain/README.md': '/start-here/what-is-an-app-chain/',
  'docs/APP_CHAIN_OVERVIEW.md': '/concepts/architecture/',
  'docs/appchain/CAPABILITIES.md': '/reference/capabilities/',
  'docs/BUILD_AND_TEST.md': '/contributing/',
  'docs/BUILD_DISTRIBUTIONS.md': '/start-here/build-from-source/',
};
