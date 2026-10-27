import {test, expect} from '@playwright/test';
import fs from 'node:fs/promises';
import {encodeWire, hex} from '../../sdk/javascript/topics.mjs';

test('Topics diagnoses inherited pending schema and writer changes from one committed root', async ({page}) => {
  const fixtures = JSON.parse(await fs.readFile('../tooling/devtools/src/test/resources/topics/plan-v1.json', 'utf8'));
  const snapshot = structuredClone(fixtures[1].snapshot), entry = snapshot.entries['games/'];
  entry.head = 2; entry.pending = 1;
  const owner = entry.active.content.owners, bytes = value => Uint8Array.from(Buffer.from(value, 'hex'));
  const schema = '77'.repeat(32);
  const queued = hex(encodeWire('queued-revision', [2, 12, 'games/', 1,
    [[owner.threshold, owner.keys.map(bytes)], [[1, owner.keys.map(bytes)], [], null, bytes(schema), null], false],
    new Uint8Array(32)]));
  const requests = [], errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.route('http://localhost:7170/api/v1/**', async route => {
    const url = new URL(route.request().url()); requests.push(url);
    let status = 200, body;
    if (url.pathname.endsWith('/status')) body = {chainId: snapshot.chainId,
      stateCommitment: {generationId: snapshot.generationId, version: snapshot.height, stateRoot: snapshot.stateRoot},
      consensusProfile: {maxMessageBytes: snapshot.maxMessageBytes}};
    else if (url.pathname.endsWith('/topics/config')) body = {enabled: true, config: snapshot.config};
    else if (url.pathname.includes('/topics/entries/')) {
      const name = decodeURIComponent(url.pathname.split('/topics/entries/')[1]);
      if (name === 'games/') body = entry; else {status = 404; body = {};}
    } else if (url.pathname.includes('/state/entry/')) {
      const key = url.pathname.split('/state/entry/')[1];
      body = {stateRoot: snapshot.stateRoot, presence: key.startsWith(Buffer.from('~yano/topics/v1/queued/').toString('hex')) ? 'PRESENT' : 'ABSENT'};
      if (body.presence === 'PRESENT') body.valueHex = queued;
    } else throw new Error(`Unexpected request ${url.pathname}`);
    await route.fulfill({status, contentType: 'application/json', headers: {'access-control-allow-origin': '*'}, body: JSON.stringify(body)});
  });
  await page.goto('/studio/topics.html');
  await page.getByRole('button', {name: 'Load example'}).click();
  await page.getByLabel('Peer API URL').fill('http://localhost:7170');
  await page.getByLabel('Composite document (YAML or JSON)').setInputFiles({name: 'composite.yaml', mimeType: 'text/yaml',
    buffer: Buffer.from('components: [{id: rides, machine: ordered-log, topic: games/rides}]\nbindings: [{id: copy, to: {component: rides}}]\n')});
  await page.getByRole('button', {name: 'Check active and pending policies from peer'}).click();
  await expect(page.locator('#topic-diagnostics')).toContainText('pending@12');
  await expect(page.locator('#topic-diagnostics')).toContainText('RULE_PAYLOAD_SCHEMA_MISMATCH');
  await expect(page.locator('#topic-diagnostics')).toContainText('RULE_TOPIC_POLICY_CROSSING');
  await expect(page.locator('#topic-diagnostics')).toContainText('peer-response');
  expect(requests.filter(url => url.pathname.includes('/state/entry/')).every(url => url.searchParams.get('height') === '10')).toBe(true);
  expect(errors).toEqual([]);
});

test('Topics rejects malformed freed-head presence instead of treating it as absence', async ({page}) => {
  const fixtures = JSON.parse(await fs.readFile('../tooling/devtools/src/test/resources/topics/plan-v1.json', 'utf8'));
  const snapshot = fixtures[0].snapshot;
  let freed;
  await page.route('http://localhost:7170/api/v1/**', async route => {
    const url = new URL(route.request().url());
    let status = 200, body;
    if (url.pathname.endsWith('/status')) body = {chainId: snapshot.chainId,
      stateCommitment: {generationId: snapshot.generationId, version: snapshot.height, stateRoot: snapshot.stateRoot},
      consensusProfile: {maxMessageBytes: snapshot.maxMessageBytes}};
    else if (url.pathname.endsWith('/topics/config')) body = {enabled: true, config: snapshot.config};
    else if (url.pathname.includes('/topics/entries/')) {status = 404; body = {};}
    else if (url.pathname.includes('/state/entry/')) body = {stateRoot: snapshot.stateRoot, ...freed};
    else throw new Error(`Unexpected request ${url.pathname}`);
    await route.fulfill({status, contentType: 'application/json', headers: {'access-control-allow-origin': '*'},
      body: JSON.stringify(body)});
  });
  await page.goto('/studio/topics.html');
  await page.getByRole('button', {name: 'Load example'}).click();
  await page.getByLabel('Peer API URL').fill('http://localhost:7170');
  const refresh = page.getByRole('button', {name: 'Refresh draft targets from peer'});
  for (const malformed of [{}, {presence: 'MISSING'}, {presence: 'PRESENT'}, {presence: 'PRESENT', valueHex: ''},
    {presence: 'PRESENT', valueHex: '01'}, {presence: 'ABSENT', valueHex: '0000000000000001'},
    {presence: 'TOMBSTONED', valueHex: '0000000000000001'}]) {
    freed = malformed;
    await refresh.click();
    await expect(refresh).toBeEnabled();
    await expect(page.locator('#notice')).toContainText(/Invalid (absent )?freed revision/);
    await expect(page.locator('#notice')).toHaveAttribute('data-error', 'true');
  }
  for (const valid of [{presence: 'ABSENT'}, {presence: 'TOMBSTONED', valueHex: null},
    {presence: 'PRESENT', valueHex: '0000000000000001'}]) {
    freed = valid;
    await refresh.click();
    await expect(refresh).toBeEnabled();
    await expect(page.locator('#notice')).toContainText('Snapshot loaded');
  }
});
