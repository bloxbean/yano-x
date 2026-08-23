// Build the machine-readable Yano X catalog and the markdown tables generated
// from it (ADR-038 §4.3).
//
// Everything here derives from files the Gradle build already maintains and
// release-gates. No recipe, capability, artifact, module, property, or version
// is typed by hand into a documentation page.
//
// Outputs:
//   /ai/catalog.json                      the whole catalog, for agents and scripts
//   <!-- catalog:<name>-start --> blocks  rendered markdown tables, injected into pages

import fs from 'node:fs/promises';
import path from 'node:path';
import {
  resolveVersions,
  loadRecipeCatalog,
  loadCapabilityCatalog,
  loadReleaseIndex,
  loadFirstPartyMetadata,
  loadArtifactInventory,
  githubUrl,
} from './repo-sources.mjs';

// ---------------------------------------------------------------------------
// Catalog assembly
// ---------------------------------------------------------------------------

/**
 * Resolve a `documentation` field from one of the JSON catalogs. Those fields
 * are repository-relative paths; map them to a site route where the site
 * publishes an equivalent page, and to GitHub otherwise.
 */
const DOC_ROUTES = {
  'docs/appchain/state-machines/kv-registry.md': '/state-machines/kv-registry/',
  'docs/appchain/state-machines/authenticated-map.md': '/state-machines/authenticated-map/',
  'docs/appchain/state-machines/authenticated-map-validation.md': '/state-machines/authenticated-map-validation/',
  'docs/appchain/state-machines/approvals.md': '/state-machines/approvals/',
  'docs/appchain/state-machines/balances.md': '/state-machines/balances/',
  'docs/appchain/state-machines/doc-trail.md': '/state-machines/doc-trail/',
  'docs/appchain/state-machines/role-approvals.md': '/state-machines/role-approvals/',
  'docs/appchain/CAPABILITIES.md': '/reference/capabilities/',
  'docs/appchain/OPTIONAL_CONNECTORS.md': '/reference/capabilities/#optional-connectors',
  'docs/appchain/PROOF_LAB.md': '/concepts/state-and-proofs/',
  'docs/appchain/tutorials/04-evidence-publication.md': '/tutorials/04-evidence-publication/',
  'docs/appchain/tutorials/08-plugins-and-composites.md': '/plugins/',
};

function docLink(docPath) {
  if (!docPath) return null;
  return DOC_ROUTES[docPath] ?? githubUrl(docPath);
}

export async function generateCatalog({ logger } = {}) {
  const log = (msg) => (logger?.info ? logger.info(msg) : console.log(msg));

  const [versions, recipeCatalog, capabilityCatalog, releaseIndex, metadata, inventory] =
    await Promise.all([
      resolveVersions(),
      loadRecipeCatalog(),
      loadCapabilityCatalog(),
      loadReleaseIndex(),
      loadFirstPartyMetadata(),
      loadArtifactInventory(),
    ]);

  const recipes = (recipeCatalog.recipes ?? []).map((r) => ({
    id: r.id,
    name: r.name,
    category: r.category,
    availability: r.availability,
    maturity: r.maturity,
    scope: r.scope,
    description: r.description,
    primaryOutcome: r.primaryOutcome,
    firstCommand: r.firstCommand,
    verificationQuery: r.verificationQuery,
    capabilities: r.capabilities ?? [],
    artifacts: r.artifacts ?? [],
    runtimeTypes: r.runtimeTypes ?? [],
    deploymentTargets: r.deploymentTargets ?? [],
    nativePosture: r.nativePosture,
    externalPrerequisites: r.externalPrerequisites ?? [],
    bootstrapRequirements: r.bootstrapRequirements ?? [],
    trustStatement: r.trustStatement,
    documentation: r.documentation ?? null,
    documentationUrl: docLink(r.documentation),
  }));

  const capabilities = (capabilityCatalog.capabilities ?? []).map((c) => ({
    id: c.id,
    name: c.name,
    category: c.category,
    availability: c.availability,
    maturity: c.maturity,
    scope: c.scope,
    selectable: c.selectable,
    description: c.description,
    provides: c.provides ?? [],
    requires: c.requires ?? [],
    implies: c.implies ?? [],
    conflicts: c.conflicts ?? [],
    artifacts: c.artifacts ?? [],
    runtimeTypes: c.runtimeTypes ?? [],
    nativePosture: c.nativePosture,
    externalPrerequisites: c.externalPrerequisites ?? [],
    properties: c.properties ?? {},
    documentation: c.documentation ?? null,
    documentationUrl: docLink(c.documentation),
  }));

  const artifacts = (capabilityCatalog.artifacts ?? []).map((a) => ({
    id: a.id,
    availability: a.availability,
    bundleId: a.bundleId,
    nativePosture: a.nativePosture,
    runtimeTypes: a.runtimeTypes ?? [],
    deploymentTargets: a.deploymentTargets ?? [],
  }));

  const modules = (inventory.artifacts ?? []).map((m) => ({
    modulePath: m.modulePath,
    directory: m.directory,
    artifactId: m.artifactId,
    publicationType: m.publicationType,
    pluginBundleId: m.pluginBundleId ?? null,
    bundleArtifactId: m.bundleArtifactId ?? null,
    sourceUrl: githubUrl(m.directory),
  }));

  const configuration = [];
  for (const owner of metadata) {
    for (const p of owner.properties ?? []) {
      configuration.push({
        key: p.key,
        owner: p.owner ?? owner.id,
        type: p.type,
        defaultValue: p.defaultValue,
        allowedValues: p.allowedValues ?? [],
        scope: p.scope,
        changePolicy: p.changePolicy,
        secret: Boolean(p.secret),
        coverage: p.coverage,
        description: p.description,
        constraints: {
          minimum: p.minimum ?? null,
          maximum: p.maximum ?? null,
          minimumUtf8Bytes: p.minimumUtf8Bytes ?? null,
          maximumUtf8Bytes: p.maximumUtf8Bytes ?? null,
          maximumItems: p.maximumItems ?? null,
        },
      });
    }
  }
  configuration.sort((a, b) => a.key.localeCompare(b.key));

  const distributions = (releaseIndex.distributions ?? []).map((d) => ({
    id: d.id,
    runtimeType: d.runtimeType,
    archivePattern: d.archivePattern,
    tooling: d.tooling,
    platforms: d.platforms ?? [],
    artifacts: d.artifacts ?? [],
  }));

  const catalog = {
    schemaVersion: 'yano-x-docsite-catalog-v1',
    source: 'Generated from the Yano X repository at documentation build time.',
    versions,
    counts: {
      recipes: recipes.length,
      capabilities: capabilities.length,
      artifacts: artifacts.length,
      modules: modules.length,
      configurationProperties: configuration.length,
      runtimePlugins: modules.filter((m) => m.publicationType === 'runtime-plugin').length,
    },
    recipes,
    capabilities,
    artifacts,
    modules,
    configuration,
    distributions,
    dxSchemaVersion: releaseIndex.schemaVersion ?? null,
    dxSchemaStatus: releaseIndex.schemaStatus ?? null,
  };

  log(
    `[catalog] ${catalog.counts.recipes} recipes, ${catalog.counts.capabilities} capabilities, ` +
    `${catalog.counts.artifacts} runtime artifacts, ${catalog.counts.modules} modules, ` +
    `${catalog.counts.configurationProperties} properties (yano ${versions.yanoVersion})`,
  );

  return catalog;
}

export async function writeCatalog({ outDir, logger, catalog } = {}) {
  const data = catalog ?? (await generateCatalog({ logger }));
  const dest = path.join(outDir, 'ai/catalog.json');
  await fs.mkdir(path.dirname(dest), { recursive: true });
  await fs.writeFile(dest, `${JSON.stringify(data, null, 2)}\n`, 'utf8');
  if (logger?.info) logger.info(`[catalog] wrote ${dest}`);
  return dest;
}

// ---------------------------------------------------------------------------
// Markdown renderers
// ---------------------------------------------------------------------------

const cell = (value) => String(value ?? '').replace(/\|/g, '\\|');
const code = (value) => (value ? `\`${cell(value)}\`` : '—');
const codeList = (values) =>
  values && values.length ? values.map((v) => `\`${cell(v)}\``).join(', ') : '—';

function table(headers, rows, align) {
  const sep = headers.map((_, i) => (align?.[i] === 'r' ? '---:' : '---'));
  return [
    `| ${headers.join(' | ')} |`,
    `|${sep.map((s) => s).join('|')}|`,
    ...rows.map((r) => `| ${r.join(' | ')} |`),
  ].join('\n');
}

export function renderRecipes(catalog) {
  const rows = catalog.recipes.map((r) => [
    r.documentationUrl ? `[${code(r.id)}](${r.documentationUrl})` : code(r.id),
    cell(r.name),
    code(r.availability),
    code(r.maturity),
    cell(r.primaryOutcome),
  ]);
  return table(
    ['Recipe', 'Name', 'Availability', 'Maturity', 'Primary outcome'],
    rows,
  );
}

export function renderRecipeDetails(catalog) {
  const out = [];
  for (const r of catalog.recipes) {
    out.push(`### \`${r.id}\` — ${r.name}`);
    out.push('');
    out.push(r.description);
    out.push('');
    out.push(`- **Outcome:** ${r.primaryOutcome}`);
    out.push(`- **Availability / maturity:** \`${r.availability}\` / \`${r.maturity}\``);
    out.push(`- **Capabilities:** ${codeList(r.capabilities)}`);
    out.push(`- **Runtime artifacts:** ${codeList(r.artifacts)}`);
    out.push(`- **Runtimes:** ${codeList(r.runtimeTypes)} · **Deployment:** ${codeList(r.deploymentTargets)}`);
    if (r.externalPrerequisites.length) {
      out.push(`- **External prerequisites:** ${codeList(r.externalPrerequisites)}`);
    }
    if (r.bootstrapRequirements.length) {
      out.push(`- **Bootstrap requirements:** ${codeList(r.bootstrapRequirements)}`);
    }
    if (r.documentationUrl) {
      // Label with the destination, so a site route does not masquerade as a repo path.
      const label = r.documentationUrl.startsWith('/') ? r.documentationUrl : r.documentation;
      out.push(`- **Reference:** [${label}](${r.documentationUrl})`);
    }
    out.push('');
    out.push('```bash');
    out.push(`./yano.sh appchain init --non-interactive \\`);
    out.push(`  --recipe ${r.id} --network devnet --members 3 --runtime jvm \\`);
    out.push(`  --output ${r.id}-chain`);
    out.push('```');
    out.push('');
  }
  return out.join('\n');
}

export function renderCapabilities(catalog) {
  const byCategory = new Map();
  for (const c of catalog.capabilities) {
    if (!byCategory.has(c.category)) byCategory.set(c.category, []);
    byCategory.get(c.category).push(c);
  }
  const out = [];
  for (const [category, caps] of byCategory) {
    out.push(`### \`${category}\``);
    out.push('');
    out.push(table(
      ['Capability', 'Availability', 'Maturity', 'Runtimes', 'Requires artifacts', 'Description'],
      caps.map((c) => [
        c.documentationUrl ? `[${code(c.id)}](${c.documentationUrl})` : code(c.id),
        code(c.availability),
        code(c.maturity),
        codeList(c.runtimeTypes),
        codeList(c.artifacts),
        cell(c.description),
      ]),
    ));
    out.push('');
  }
  return out.join('\n');
}

export function renderArtifacts(catalog) {
  return table(
    ['Artifact', 'Availability', 'Plugin bundle id', 'Runtimes', 'Native posture'],
    catalog.artifacts.map((a) => [
      code(a.id),
      code(a.availability),
      code(a.bundleId),
      codeList(a.runtimeTypes),
      code(a.nativePosture),
    ]),
  );
}

export function renderModules(catalog) {
  const byType = new Map();
  for (const m of catalog.modules) {
    if (!byType.has(m.publicationType)) byType.set(m.publicationType, []);
    byType.get(m.publicationType).push(m);
  }
  const order = ['runtime-plugin', 'library', 'application', 'platform', 'tooling'];
  const types = [...byType.keys()].sort(
    (a, b) => (order.indexOf(a) + 1 || 99) - (order.indexOf(b) + 1 || 99),
  );
  const out = [];
  for (const type of types) {
    const mods = byType.get(type);
    out.push(`### \`${type}\` (${mods.length})`);
    out.push('');
    out.push(table(
      ['Gradle module', 'Artifact id', 'Plugin bundle id', 'Source'],
      mods.map((m) => [
        code(m.modulePath),
        code(m.artifactId),
        code(m.pluginBundleId),
        `[${cell(m.directory)}](${m.sourceUrl})`,
      ]),
    ));
    out.push('');
  }
  return out.join('\n');
}

export function renderConfiguration(catalog) {
  const byOwner = new Map();
  for (const p of catalog.configuration) {
    if (!byOwner.has(p.owner)) byOwner.set(p.owner, []);
    byOwner.get(p.owner).push(p);
  }
  const out = [];
  for (const [owner, props] of byOwner) {
    out.push(`### \`${owner}\``);
    out.push('');
    out.push(table(
      ['Property', 'Type', 'Default', 'Allowed', 'Scope', 'Change policy', 'Description'],
      props.map((p) => [
        code(p.key),
        code(p.type),
        p.defaultValue === null || p.defaultValue === undefined ? '—' : code(p.defaultValue),
        p.allowedValues.length ? codeList(p.allowedValues) : '—',
        code(p.scope),
        code(p.changePolicy),
        cell(p.description),
      ]),
    ));
    out.push('');
  }
  return out.join('\n');
}

export function renderDistributions(catalog) {
  return table(
    ['Distribution', 'Runtime', 'Archive', 'Platforms', 'Bundled artifacts'],
    catalog.distributions.map((d) => [
      code(d.id),
      code(d.runtimeType),
      code(d.archivePattern),
      codeList(d.platforms),
      String(d.artifacts.length),
    ]),
  );
}

export function renderVersions(catalog) {
  const v = catalog.versions;
  return table(
    ['Value', 'Current'],
    [
      ['Yano X version', code(v.yanoXVersion)],
      ['Yano host version', code(v.yanoVersion)],
      ['Maven group', code(v.group)],
      ['Java', code(v.javaVersion)],
      ['Base Yano JVM ZIP', `[\`yano-${v.yanoVersion}.zip\`](${v.yanoJvmZipUrl})`],
    ],
  );
}

// ---------------------------------------------------------------------------
// Anchor injection
// ---------------------------------------------------------------------------

const RENDERERS = {
  recipes: renderRecipes,
  'recipe-details': renderRecipeDetails,
  capabilities: renderCapabilities,
  artifacts: renderArtifacts,
  modules: renderModules,
  configuration: renderConfiguration,
  distributions: renderDistributions,
  versions: renderVersions,
};

function injectBetweenAnchors(body, name, replacement) {
  const start = `<!-- catalog:${name}-start -->`;
  const end = `<!-- catalog:${name}-end -->`;
  const startIdx = body.indexOf(start);
  if (startIdx === -1) return body;
  const endIdx = body.indexOf(end, startIdx + start.length);
  if (endIdx === -1) return body;
  return (
    body.slice(0, startIdx + start.length) +
    '\n\n' + replacement.trim() + '\n\n' +
    body.slice(endIdx)
  );
}

/**
 * Replace every `<!-- catalog:<name>-start/end -->` block this body contains.
 *
 * The operation is idempotent: it rewrites only the region between the two
 * anchors and leaves the anchors themselves in place, so a page can be
 * regenerated any number of times. This is the same convention
 * `docs/appchain/CAPABILITIES.md` already uses in the repository.
 */
export function injectCatalogBlocks(body, catalog) {
  if (!catalog) return body;
  let out = body;
  for (const [name, render] of Object.entries(RENDERERS)) {
    if (out.includes(`<!-- catalog:${name}-start -->`)) {
      out = injectBetweenAnchors(out, name, render(catalog));
    }
  }
  return out;
}

/** Names a page may reference; used to report a typo'd anchor as a build error. */
export const CATALOG_BLOCK_NAMES = Object.keys(RENDERERS);
