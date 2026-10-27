import {test, expect} from '@playwright/test';
import fs from 'node:fs/promises';
import AxeBuilder from '@axe-core/playwright';

// Prepared by :tooling:studio:prepareStudio. No live node or private key is needed.
test('Topics policy editor exports the CLI-compatible unsigned plan without signing', async ({page}) => {
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.goto('/studio/topics.html');
  await expect(page.getByRole('heading', {name: 'Protect a topic'})).toBeVisible();
  await page.getByRole('button', {name: 'Load example'}).click();
  await expect(page.getByText('Height 10 · generation')).toBeVisible();
  await page.getByRole('button', {name: 'Preview plan', exact: true}).click();
  await expect(page.locator('#plan-preview')).toContainText('statementHex');
  await expect(page.locator('#plan-preview')).toContainText('TopicsPlan');
  const downloadEvent = page.waitForEvent('download');
  await page.getByRole('button', {name: 'Download unsigned plan'}).click();
  const download = await downloadEvent;
  const plan = JSON.parse(await fs.readFile(await download.path(), 'utf8'));
  expect(plan.commands[0].signatures).toEqual([]);
  expect(plan.commands[0].statementHex).toMatch(/^[0-9a-f]+$/);
  expect(plan.document.commands[0].policy.schemaCddl).toContain('frames');
  await page.getByLabel('Target namespace or topic').fill('games/neon-rush/');
  await page.getByLabel('Operation', {exact: true}).selectOption('create');
  await page.getByRole('button', {name: 'Replace draft with this change'}).click();
  await page.getByRole('button', {name: 'Preview plan', exact: true}).click();
  await expect(page.locator('#plan-preview')).toContainText('games/neon-rush/');
  expect(errors).toEqual([]);
  expect(await page.locator('input[type=password]').count()).toBe(0);
  const accessibility = await new AxeBuilder({page}).withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze();
  expect(accessibility.violations).toEqual([]);
  await page.setViewportSize({width: 390, height: 844});
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});

test('Topic inspector distinguishes complete policy proofs and entry-only responses', async ({page}) => {
  const routes = [];
  await page.route('http://localhost:7170/api/v1/**', async route => {
    routes.push(route.request().url());
    await route.fulfill({status: 200, contentType: 'application/json', headers: {'access-control-allow-origin': '*'},
      body: JSON.stringify({height: 11, parentRootHex: '33'.repeat(32), proofs: [], outcome: {kind: 'UNREGISTERED_REJECT'}})});
  });
  await page.goto('/studio/topics.html');
  await page.getByRole('button', {name: 'Load example'}).click();
  await page.getByLabel('Peer API URL').fill('http://localhost:7170');
  await page.getByRole('button', {name: 'Resolve effective policy'}).click();
  await expect(page.locator('#inspection')).toContainText('topic-policy-v1');
  await expect(page.locator('#inspection')).toContainText('peer-response');
  await page.getByRole('button', {name: 'Entry proof', exact: true}).click();
  await expect(page.locator('#inspection')).toContainText('topic-entry-state-proof');
  expect(routes.some(url => url.includes('/topics/proof?'))).toBe(true);
  expect(routes.some(url => url.includes('/state/proof/'))).toBe(true);
});

test('Revocation export matches the CLI and refuses a draft changed during credential verification', async ({page}) => {
  await page.goto('/studio/topics.html');
  const fixtures = await (await page.request.get('/studio/assets/topics-plan-v1.json')).json();
  const fixture = fixtures.find(value => value.name === 'issuer-revoke');
  await page.locator('#plan-file').setInputFiles({name: 'revoke-plan.json', mimeType: 'application/json',
    buffer: Buffer.from(JSON.stringify(fixture.plan))});
  await expect(page.getByText('Height 10 · generation')).toBeVisible();
  await page.getByRole('button', {name: 'Preview plan', exact: true}).click();
  await expect(page.locator('#plan-preview')).toContainText('~issuer');
  const downloaded = page.waitForEvent('download');
  await page.getByRole('button', {name: 'Download unsigned plan'}).click();
  expect(JSON.parse(await fs.readFile(await (await downloaded).path(), 'utf8'))).toEqual(fixture.plan);

  await page.getByLabel('Operation', {exact: true}).selectOption('revoke');
  await page.getByLabel('Authority', {exact: true}).fill('~issuer');
  await page.getByLabel('Target namespace or topic').fill(fixture.document.commands[0].target);
  await page.getByLabel('Command expires at (Unix ms)').fill('1000000');
  await page.getByLabel('Public delegation credential to revoke (hex)').fill(fixture.document.commands[0].delegationHex);
  await page.getByRole('button', {name: 'Replace draft with this change'}).click();
  await page.getByRole('button', {name: 'Preview plan', exact: true}).click();
  await expect(page.locator('#plan-preview')).toContainText('statementHex');
  expect(JSON.parse(await page.locator('#plan-preview').textContent()).commands[0].requiredSigners)
    .toEqual(fixture.plan.commands[0].requiredSigners);

  await page.getByText('Edit or import topics.yaml', {exact: true}).click();
  await expect(page.locator('#document')).toBeVisible();
  const staleDownloads = [];
  page.on('download', download => staleDownloads.push(download));
  await page.evaluate(() => {
    const original = crypto.subtle.verify.bind(crypto.subtle);
    crypto.subtle.verify = async (...args) => {
      window.releaseTopicVerification = null;
      await new Promise(resolve => { window.releaseTopicVerification = resolve; });
      return original(...args);
    };
  });
  await page.getByRole('button', {name: 'Download unsigned plan'}).click();
  await page.waitForFunction(() => typeof window.releaseTopicVerification === 'function');
  await page.locator('#document').fill('{}');
  await page.evaluate(() => window.releaseTopicVerification());
  await expect(page.locator('#notice')).toContainText('draft or snapshot changed');
  await expect(page.getByRole('button', {name: 'Download unsigned plan'})).toBeEnabled();
  expect(staleDownloads).toEqual([]);
});
