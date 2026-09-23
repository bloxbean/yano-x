import assert from 'node:assert/strict';
import test from 'node:test';
import { yanoJvmReleaseUrl } from './repo-sources.mjs';
import { generateCatalog, renderVersions } from './generate-catalog.mjs';

test('unpublished host identities never advertise release assets', () => {
  for (const version of ['0.1.0-pre17-SNAPSHOT', '0.1.0-pre17-local-review4',
    '0.1.0-pre17-957e53812', 'unknown', '']) {
    assert.equal(yanoJvmReleaseUrl(version), null);
    const rendered = renderVersions({ versions: {
      yanoVersion: version, yanoXVersion: 'test', group: 'org.yanoproject.x', javaVersion: '25',
      yanoJvmZipUrl: yanoJvmReleaseUrl(version),
    } });
    assert.doesNotMatch(rendered, /releases\/download/);
    assert.match(rendered, /Local build required/);
    assert.match(rendered, /-PyanoJvmDist/);
  }
});

test('released host versions retain the conventional exact-version asset URL', () => {
  assert.equal(yanoJvmReleaseUrl('0.1.0-pre17'),
    'https://github.com/bloxbean/yano/releases/download/v0.1.0-pre17/yano-0.1.0-pre17.zip');
});

test('catalog entries for imported binding guides use internal site routes', async () => {
  const catalog = await generateCatalog({ logger: { info() {} } });
  const bindings = [...catalog.recipes, ...catalog.capabilities]
    .filter((entry) => entry.documentation === 'docs/appchain/DECLARATIVE_BINDINGS_CLI.md');
  assert.ok(bindings.length >= 2, 'exercise both recipe and capability catalog entries');
  for (const entry of bindings) {
    assert.equal(entry.documentationUrl, '/reference/declarative-bindings-cli/');
  }
});
