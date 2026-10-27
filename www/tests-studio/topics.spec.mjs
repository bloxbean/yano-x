import {test, expect} from '@playwright/test';
import fs from 'node:fs/promises';
import AxeBuilder from '@axe-core/playwright';

const OWNER = 'aa'.repeat(32);
const WRITER = 'bb'.repeat(32);

// Prepared by :tooling:studio:prepareStudio. Studio writes topics.yaml only: no node, key or network request.
test('The topics editor writes the topics.yaml the CLI plans and names the next commands', async ({page}) => {
  const errors = [];
  const requests = [];
  page.on('pageerror', error => errors.push(error.message));
  page.on('request', request => requests.push(request.url()));
  await page.goto('/studio/topics.html');
  await expect(page.getByRole('heading', {name: 'Write a topics change'})).toBeVisible();
  await expect(page.locator('#next-steps')).toContainText('yano.sh appchain topics approve topics.plan.json');

  // A member vote creates the top-level namespace: no expiry, owners and a policy.
  await page.getByLabel('Chain ID').fill('arcade');
  await page.getByLabel('Owner public keys (64 hex, one per line)').fill(OWNER);
  await page.getByLabel('Writer public keys (one per line; empty means anyone may write)').fill(WRITER);
  await page.getByLabel('Payload schema (CDDL; empty means no schema)').fill('root = { frames: uint }');
  await expect(page.locator('#expiry-field')).toBeHidden();
  await page.getByRole('button', {name: 'Write this change into topics.yaml'}).click();
  await expect(page.locator('#notice')).toContainText('topics.yaml is ready');
  const downloadEvent = page.waitForEvent('download');
  await page.getByRole('button', {name: 'Download topics.yaml'}).click();
  const download = await downloadEvent;
  expect(download.suggestedFilename()).toBe('topics.yaml');
  const yaml = await fs.readFile(await download.path(), 'utf8');
  expect(yaml).toContain('chainId: arcade');
  expect(yaml).toContain('authority: governance');
  expect(yaml).toContain(OWNER);
  expect(yaml).toContain('schemaCddl:');
  expect(yaml).not.toContain('notAfter');

  // An owner change has an expiry and is signed, not voted; recovery is always a member vote.
  await page.getByLabel('Operation', {exact: true}).selectOption('update');
  await page.getByLabel('Authority', {exact: true}).fill('games/');
  await expect(page.locator('#owners-fields')).toBeHidden();
  await expect(page.locator('#expiry-field')).toBeVisible();
  await page.getByRole('button', {name: 'Write this change into topics.yaml'}).click();
  await expect(page.locator('#document')).toHaveValue(/notAfter: \d+/);
  await expect(page.locator('#next-steps')).toContainText('topics sign topics.plan.json --key-file');
  await expect(page.locator('#next-steps')).not.toContainText('approve');
  await page.getByLabel('Operation', {exact: true}).selectOption('recover');
  await expect(page.getByLabel('Authority', {exact: true})).toHaveValue('governance');

  // Mistakes are named in words; nothing is written.
  await page.getByLabel('Operation', {exact: true}).selectOption('create');
  await page.getByLabel('Target namespace or topic').fill('games/rides/');
  await page.getByRole('button', {name: 'Write this change into topics.yaml'}).click();
  await expect(page.locator('#notice')).toHaveText('A member vote creates a top-level namespace');
  await expect(page.locator('#notice')).toHaveAttribute('data-error', 'true');

  expect(errors).toEqual([]);
  expect(requests.every(url => url.startsWith(new URL(page.url()).origin))).toBe(true);
  expect(await page.locator('input[type=password]').count()).toBe(0);
  const accessibility = await new AxeBuilder({page}).withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze();
  expect(accessibility.violations).toEqual([]);
  await page.setViewportSize({width: 390, height: 844});
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});

test('An imported topics.yaml is checked and its handoff follows its authorities', async ({page}) => {
  await page.goto('/studio/topics.html');
  const document = ['schemaVersion: 1', 'kind: Topics', 'chainId: arcade', 'commands:',
    '  - target: games/', '    operation: retire', '    authority: games/', `    nonce: "${'44'.repeat(16)}"`,
    '    notAfter: 1900000000000', ''].join('\n');
  await page.locator('#yaml-file').setInputFiles({name: 'retire.yaml', mimeType: 'text/yaml',
    buffer: Buffer.from(document)});
  await expect(page.locator('#notice')).toHaveText('Imported retire.yaml.');
  await expect(page.getByLabel('Chain ID')).toHaveValue('arcade');
  await expect(page.locator('#next-steps')).toContainText('topics submit retire.signed.json');
  const downloadEvent = page.waitForEvent('download');
  await page.getByRole('button', {name: 'Download retire.yaml'}).click();
  expect((await downloadEvent).suggestedFilename()).toBe('retire.yaml');

  await page.locator('#yaml-file').setInputFiles({name: 'bad.yaml', mimeType: 'text/yaml',
    buffer: Buffer.from(document.replace('kind: Topics', 'kind: Blueprint'))});
  await expect(page.locator('#notice')).toHaveText('Expected kind: Topics, schemaVersion: 1');
});
