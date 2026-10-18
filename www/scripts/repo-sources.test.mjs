import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parseGradleProperties, readReleasedGradleProperties } from './repo-sources.mjs';

const tagFails = () => assert.fail('a release version must not read a tag');

test('a release version in gradle.properties is quoted as it is', async () => {
  const current = { version: '0.1.0-pre4', yanoVersion: '0.1.0-pre18' };
  assert.deepEqual(await readReleasedGradleProperties({ current, readTag: tagFails }), current);
});

test('a snapshot version quotes the latest release tag instead', async () => {
  const current = { version: '0.1.0-pre4-SNAPSHOT', yanoVersion: '0.1.0-pre18-3c73341' };
  const released = { version: '0.1.0-pre3', yanoVersion: '0.1.0-pre17', tag: 'v0.1.0-pre3' };
  assert.deepEqual(await readReleasedGradleProperties({ current, readTag: () => released }), released);
});

test('a release tag that records a snapshot version is refused', async () => {
  const current = { version: '0.1.0-pre4-SNAPSHOT' };
  const released = { version: '0.1.0-pre3-SNAPSHOT', tag: 'v0.1.0-pre3' };
  await assert.rejects(readReleasedGradleProperties({ current, readTag: () => released }), /v0\.1\.0-pre3/);
});

test('gradle.properties parsing skips comments and blank lines', () => {
  assert.deepEqual(parseGradleProperties('# note\n\nversion=1.0\n! old\nyanoVersion = 2.0\n'),
    { version: '1.0', yanoVersion: '2.0' });
});
