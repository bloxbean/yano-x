// Browser, keyboard and accessibility gates for the guided binding editor (ADR-031.2 M1).
import { test, expect } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { parseYaml, typedTree } from '../../tooling/studio/src/main/web/studio-yaml.mjs';
import { readBlueprint } from '../../tooling/studio/src/main/web/binding-blueprint.mjs';

const repo = path.resolve('..');
const starter = name => fs.readFileSync(path.join(repo, 'examples/bindings', name), 'utf8');
const studioFixture = name => fs.readFileSync(path.join(repo, 'tooling/studio/src/test/fixtures', name), 'utf8');
const tree = text => typedTree(parseYaml(text).root);
const ORIGIN = 'http://127.0.0.1:4342';

async function open(page) {
  const problems = [];
  const requests = [];
  page.on('pageerror', error => problems.push(error.message));
  page.on('console', message => { if (message.type() === 'error') problems.push(message.text()); });
  page.on('request', request => requests.push({ url: request.url(), method: request.method() }));
  await page.goto('/studio/bindings.html');
  await expect(page.locator('html')).toHaveAttribute('data-ready', 'true');
  return { problems, requests };
}

async function activeId(page) {
  return page.evaluate(() => document.activeElement?.id ?? '');
}

/** Waits until the editor has redrawn after the last change, as a person waits for the form to update. */
async function settle(page) {
  await expect(page.locator('html')).not.toHaveAttribute('data-render-pending', 'true');
}

/** Moves focus with the Tab key only until the element with `id` is focused. */
async function tabTo(page, id, limit = 600) {
  for (let step = 0; step < limit; step++) {
    await settle(page);
    if (await activeId(page) === id) return;
    await page.keyboard.press('Tab');
  }
  throw new Error(`#${id} was not reachable with Tab`);
}

async function focusIs(page, id) {
  await settle(page);
  await expect.poll(() => activeId(page)).toBe(id);
}

async function renameWithKeyboard(page, buttonId, value) {
  await tabTo(page, buttonId);
  await page.keyboard.press('Enter');
  await expect(page.locator('#dialog')).toBeVisible();
  // The dialog opens in its text field with the current id selected; Enter accepts.
  await focusIs(page, 'rename-input');
  await page.keyboard.type(value);
  await page.keyboard.press('Enter');
  await expect(page.locator('#dialog')).toBeHidden();
}

async function loadStarter(page, id) {
  await page.locator('#starter-select').selectOption(id);
  await page.locator('#load-starter').click();
  await expect(page.locator('#yaml-text')).toHaveValue(starter(`${id}.yaml`));
}

async function axe(page, state) {
  const results = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'])
    .analyze();
  const violations = results.violations.map(violation =>
    `${state}: ${violation.id} (${violation.impact}) ${violation.nodes.slice(0, 3).map(node => node.target.join(' ')).join(', ')}`);
  expect(violations).toEqual([]);
}

async function downloadText(page, trigger) {
  const [download] = await Promise.all([page.waitForEvent('download'), trigger()]);
  return { name: download.suggestedFilename(), text: fs.readFileSync(await download.path(), 'utf8') };
}

const KEYBOARD_EXPORT = path.join(repo, 'tooling/studio/build/browser-export/keyboard-registry-to-audit.yaml');

test('a complete registry-to-audit document can be authored with the keyboard alone', async ({ page }) => {
  fs.rmSync(KEYBOARD_EXPORT, { force: true });
  const { problems, requests } = await open(page);
  await tabTo(page, 'new-document');
  await page.keyboard.press('Enter');

  for (const [index, machine, id] of [[0, 'kv-registry', 'records'], [1, 'doc-trail', 'audit']]) {
    await tabTo(page, 'add-component');
    await page.keyboard.press('Enter');
    await focusIs(page, `c${index}-machine`);
    await page.keyboard.type(machine);
    await page.keyboard.press('Tab');
    await renameWithKeyboard(page, `c${index}-rename`, id);
    await expect(page.locator(`#select-component-${index}`)).toContainText(id);
  }

  await tabTo(page, 'add-binding');
  await page.keyboard.press('Enter');
  await focusIs(page, 'b0-source');
  await tabTo(page, 'b0-event');
  await page.keyboard.type('kv-registry.entry-p');
  await expect(page.locator('#b0-event')).toHaveValue('kv-registry.entry-put.v1');
  await renameWithKeyboard(page, 'b0-rename', 'audit-record');

  await tabTo(page, 'b0-add-expression');
  await page.keyboard.press('Enter');
  await tabTo(page, 'b0-w0-expr');
  await page.keyboard.type('event.valueLength < 100');
  await page.keyboard.press('Tab');

  await tabTo(page, 'b0-target');
  await page.keyboard.type('audit');
  await expect(page.locator('#b0-target')).toHaveValue('audit');
  await tabTo(page, 'b0-command');
  await page.keyboard.type('append');
  await expect(page.locator('#b0-command')).toHaveValue('append');

  await tabTo(page, 'b0-map-entityId-map');
  await page.keyboard.press('Enter');
  await tabTo(page, 'b0-map-entityId-kind');
  await page.keyboard.type('Function');
  await expect(page.locator('#b0-map-entityId-kind')).toHaveValue('fn');
  await tabTo(page, 'b0-map-entityId-fn');
  await page.keyboard.type('hex');
  await expect(page.locator('#b0-map-entityId-fn')).toHaveValue('hex');
  await expect(page.locator('#b0-map-entityId-a0-field')).toHaveValue('key');

  await tabTo(page, 'b0-map-entryHash-map');
  await page.keyboard.press('Enter');
  await tabTo(page, 'b0-map-entryHash-field');
  await page.keyboard.type('valueH');
  await expect(page.locator('#b0-map-entryHash-field')).toHaveValue('valueHash');

  await tabTo(page, 'b0-map-reference-map');
  await page.keyboard.press('Enter');
  await tabTo(page, 'b0-map-reference-literal-value');
  await page.keyboard.type('published');
  await page.keyboard.press('Tab');

  await tabTo(page, 'download-document');
  const exported = await downloadText(page, () => page.keyboard.press('Enter'));
  expect(exported.name).toBe('bindings.yaml');
  expect(tree(exported.text)).toEqual(tree(starter('registry-to-audit.yaml')));
  fs.mkdirSync(path.dirname(KEYBOARD_EXPORT), { recursive: true });
  fs.writeFileSync(KEYBOARD_EXPORT, exported.text);

  expect(problems).toEqual([]);
  expect(requests.every(request => request.method === 'GET' && request.url.startsWith(`${ORIGIN}/studio/`))).toBe(true);
  expect(await page.evaluate(async () => ({ local: localStorage.length, session: sessionStorage.length,
    cookies: document.cookie, databases: (await indexedDB.databases()).length }))).toEqual(
    { local: 0, session: 0, cookies: '', databases: 0 });
});

test('the keyboard-authored document compiles to the same IR and profile as the starter', async () => {
  const cli = process.env.BINDING_CLI;
  const bundles = process.env.BINDING_BUNDLES;
  test.skip(!cli || !bundles, 'BINDING_CLI and BINDING_BUNDLES are provided by :tooling:studio:browserTestStudio');
  const exported = KEYBOARD_EXPORT;
  expect(fs.existsSync(exported), 'written by the keyboard test in this run').toBe(true);
  const work = fs.mkdtempSync(path.join(os.tmpdir(), 'studio-roundtrip-'));
  try {
    const plugins = path.join(work, 'plugins');
    fs.mkdirSync(plugins);
    for (const bundle of bundles.split(path.delimiter)) fs.copyFileSync(bundle, path.join(plugins, path.basename(bundle)));
    const context = path.join(repo, 'tooling/studio/src/main/web/binding-authoring-context.json');
    const run = (command, document) => execFileSync(cli, ['bindings', command, document, '--plugins-directory', plugins,
      '--context', context], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] });
    const original = path.join(repo, 'examples/bindings/registry-to-audit.yaml');
    expect(run('compile', exported)).toBe(run('compile', original));
    const profile = document => JSON.parse(run('validate', document)).operationalStatus.activeProfileDigest;
    expect(profile(exported)).toMatch(/^[0-9a-f]{64}$/);
    expect(profile(exported)).toBe(profile(original));
  } finally {
    fs.rmSync(work, { recursive: true, force: true });
  }
});

test('pages have no automatically detectable accessibility violations in their main states', async ({ page }) => {
  await open(page);
  await axe(page, 'empty document');
  await loadStarter(page, 'registry-to-audit');
  await page.locator('#select-component-0').click();
  await axe(page, 'component form');
  await page.locator('#select-binding-0').click();
  await axe(page, 'binding form');
  await page.locator('#select-limits').click();
  await axe(page, 'limits form');
  await page.locator('#tab-yaml').click();
  await axe(page, 'YAML view');
  await page.locator('#tab-graph').click();
  await expect(page.locator('.graph-node')).toHaveCount(2);
  await axe(page, 'graph view');
  await page.locator('#tab-form').click();
  await page.locator('#select-binding-0').click();
  await page.locator('#b0-rename').click();
  await expect(page.locator('#dialog')).toBeVisible();
  await axe(page, 'rename dialog');
  await page.keyboard.press('Escape');
  await expect(page.locator('#dialog')).toBeHidden();
  await page.goto('/studio/index.html');
  await expect(page.getByRole('link', { name: 'Bindings' })).toHaveAttribute('href', 'bindings.html');
});

test('structured edits ask before rewriting imported formatting and keep the original', async ({ page }) => {
  await open(page);
  await loadStarter(page, 'registry-to-audit');
  await page.locator('#select-binding-0').click();
  await page.locator('#b0-map-reference-literal-value').fill('reviewed');
  await page.locator('#b0-map-reference-literal-value').press('Tab');
  await expect(page.locator('#dialog')).toBeVisible();
  await expect(page.locator('#dialog')).toContainText('Comments, quoting and spacing');
  await page.locator('#dialog-cancel').click();
  await expect(page.locator('#yaml-text')).toHaveValue(starter('registry-to-audit.yaml'));
  await page.locator('#b0-map-reference-literal-value').fill('reviewed');
  await page.locator('#b0-map-reference-literal-value').press('Tab');
  await page.locator('#dialog-accept').click();
  await expect(page.locator('#yaml-text')).toHaveValue(/reference: \{literal: reviewed\}/);
  await expect(page.locator('#yaml-text')).not.toHaveValue(/# Studio starter/);
  const original = await downloadText(page, () => page.locator('#download-original').click());
  expect(original.text).toBe(starter('registry-to-audit.yaml'));
});

test('values with line breaks are edited as escaped strings and never lose characters', async ({ page }) => {
  await open(page);
  const { importDocument } = await import('../../tooling/studio/src/main/web/binding-draft.mjs');
  const text = starter('registry-to-audit.yaml')
    .replace('reference: {literal: published}', 'reference: {literal: "line1\\nline2"}')
    .replace("expr: 'event.valueLength < 100'", 'expr: "event.valueLength <\\r\\n100"')
    .replace('{id: audit, machine: doc-trail}', '{id: audit, machine: "doc-trail "}');
  await page.locator('#import-yaml').setInputFiles({ name: 'breaks.yaml', mimeType: 'text/yaml', buffer: Buffer.from(text) });

  // A name field trims what is typed, so a value with surrounding spaces is escaped too, without suggestions.
  await page.locator('#select-component-0').click();
  await expect(page.locator('#c0-machine')).toHaveValue('kv-registry');
  await expect(page.locator('#c0-machine')).toHaveAttribute('list', 'machine-options');
  await page.locator('#select-component-1').click();
  await expect(page.locator('#c1-machine')).toHaveValue('"doc-trail "');
  await expect(page.locator('#c1-machine')).not.toHaveAttribute('list', /.*/);
  await page.locator('#select-binding-0').click();
  const current = async () => importDocument(await page.locator('#yaml-text').inputValue()).draft.bindings[0];
  const reference = binding => binding.to.mapping.assignments.find(item => item.field === 'reference').source.value.value;

  // Text with line feeds opens in a textarea, which holds them exactly. A textarea would turn a CR into LF, so a value
  // with a carriage return is escaped as a quoted JSON string instead.
  const literal = page.locator('#b0-map-reference-literal-value');
  const expression = page.locator('#b0-w0-expr');
  expect(await literal.evaluate(element => element.tagName)).toBe('TEXTAREA');
  await expect(literal).toHaveValue('line1\nline2');
  await expect(expression).toHaveValue('"event.valueLength <\\r\\n100"');
  await expect(expression).toHaveAttribute('aria-describedby', /b0-w0-expr-escaped/);
  await expect(page.locator('#b0-w0-expr-escaped')).toContainText('quoted JSON string');
  await axe(page, 'escaped values');

  // The review's reproduction: append "!" to the imported "line1\nline2" with the keyboard.
  await literal.focus();
  await page.keyboard.press('ControlOrMeta+End');
  await page.keyboard.type('!');
  await literal.press('Tab');
  await page.locator('#dialog-accept').click();
  await settle(page);
  expect(reference(await current())).toBe('line1\nline2!');
  expect((await current()).when[0].text).toBe('event.valueLength <\r\n100');

  await expression.fill('"event.valueLength <\\r\\n99"');
  await expression.press('Tab');
  await settle(page);
  expect((await current()).when[0].text).toBe('event.valueLength <\r\n99');

  // Escaped text that is not a quoted string is refused, marked invalid and changes nothing.
  await expression.fill('event.valueLength < 98');
  await expression.press('Tab');
  await expect(page.locator('#alert')).toContainText('quoted JSON string');
  await expect(expression).toHaveAttribute('aria-invalid', 'true');
  expect((await current()).when[0].text).toBe('event.valueLength <\r\n99');

  // Removing every line break returns the literal to a single-line input on the next redraw.
  await literal.fill('single line');
  await literal.press('Tab');
  await settle(page);
  expect(await literal.evaluate(element => element.tagName)).toBe('INPUT');
  await expect(literal).toHaveValue('single line');
  expect(reference(await current())).toBe('single line');
});

test('typing in the YAML view keeps a CRLF document\'s line endings', async ({ page }) => {
  await open(page);
  const crlf = starter('registry-to-audit.yaml').replace(/\n/g, '\r\n');
  await page.locator('#import-yaml').setInputFiles({ name: 'crlf.yaml', mimeType: 'text/yaml', buffer: Buffer.from(crlf) });
  await page.locator('#tab-yaml').click();
  const yaml = page.locator('#yaml-text');
  await yaml.fill((await yaml.inputValue()).replace('published', 'reviewed'));
  await yaml.press('Tab');
  await settle(page);
  const exported = await downloadText(page, () => page.locator('#download-document').click());
  expect(exported.text).toBe(crlf.replace('published', 'reviewed'));
  // No formatting acknowledgement is needed: the canonical form keeps the same line ending.
  await page.locator('#tab-form').click();
  await page.locator('#select-binding-0').click();
  await page.locator('#b0-map-reference-literal-value').fill('checked');
  await page.locator('#b0-map-reference-literal-value').press('Tab');
  const dialog = page.locator('#dialog');
  if (await dialog.isVisible()) await page.locator('#dialog-accept').click();
  await settle(page);
  const after = await downloadText(page, () => page.locator('#download-document').click());
  expect(after.text).toContain('reference: {literal: checked}\r\n');
  expect(after.text.replace(/\r\n/g, '')).not.toContain('\n');
});

test('an incomplete function call is flagged by arity and the editor keeps working', async ({ page }) => {
  const { problems } = await open(page);
  const text = starter('registry-to-audit.yaml').replace('reference: {literal: published}', 'reference: {fn: concat, args: []}');
  await page.locator('#import-yaml').setInputFiles({ name: 'incomplete.yaml', mimeType: 'text/yaml', buffer: Buffer.from(text) });
  await settle(page);
  await expect(page.locator('#checks')).toContainText('FUNCTION_ARITY');
  await expect(page.locator('#download-document')).toBeEnabled();
  const exported = await downloadText(page, () => page.locator('#download-document').click());
  expect(exported.text).toBe(text);
  expect(problems).toEqual([]);
});

test('invalid YAML text never updates the draft or reaches an export', async ({ page }) => {
  await open(page);
  await loadStarter(page, 'approval-to-audit');
  await page.locator('#tab-yaml').click();
  await page.locator('#yaml-text').fill('composite:\n  components: [\n');
  await expect(page.locator('#mode-badge')).toHaveText('Text not valid');
  await expect(page.locator('#mode-banner')).toContainText('Forms show the last valid draft');
  await expect(page.locator('#download-document')).toBeDisabled();
  await page.locator('#tab-form').click();
  await page.locator('#select-binding-0').click();
  await expect(page.locator('#editor-title')).toHaveText('Binding 1 · record-approved');
  // Controls inherit the disabled state of the form's fieldset.
  await expect(page.locator('#b0-map-reference-literal-value')).toBeDisabled();
  await expect(page.locator('#b0-rename')).toBeDisabled();
  await page.locator('#banner-revert').click();
  await expect(page.locator('#mode-badge')).toHaveText('Editable draft');
  await expect(page.locator('#download-document')).toBeEnabled();
  expect(tree(await page.locator('#yaml-text').inputValue())).toEqual(tree(starter('approval-to-audit.yaml')));
});

test('unsupported imports stay read-only and only the original can be downloaded', async ({ page }) => {
  const { requests } = await open(page);
  const text = 'composite:\n  components: []\n  bindings: []\n  futureField: 1\n';
  await page.locator('#import-yaml').setInputFiles({ name: 'future.yaml', mimeType: 'text/yaml', buffer: Buffer.from(text) });
  await expect(page.locator('#mode-badge')).toHaveText('Read-only');
  await expect(page.locator('#mode-banner')).toContainText('$.composite.futureField');
  await expect(page.locator('#mode-banner')).toContainText('line 4');
  await expect(page.locator('#download-document')).toBeDisabled();
  await expect(page.locator('#add-component')).toBeDisabled();
  await page.locator('#tab-yaml').click();
  await expect(page.locator('#yaml-text')).toHaveJSProperty('readOnly', true);
  const original = await downloadText(page, () => page.locator('#download-original').click());
  expect(original.text).toBe(text);
  expect(requests.every(request => request.method === 'GET' && request.url.startsWith(`${ORIGIN}/studio/`))).toBe(true);
});

test('moving graph nodes changes only presentation; reordering bindings changes the program', async ({ page }) => {
  await open(page);
  await loadStarter(page, 'registry-to-audit');
  await page.locator('#tab-graph').click();
  const node = page.locator('[id="graph-node-component%3Arecords"]');
  const before = await node.getAttribute('transform');
  await node.focus();
  for (let step = 0; step < 3; step++) await page.keyboard.press('ArrowRight');
  await expect(node).not.toHaveAttribute('transform', before);
  await expect(page.locator('#yaml-text')).toHaveValue(starter('registry-to-audit.yaml'));
  const layout = await downloadText(page, () => page.locator('#layout-export').click());
  expect(layout.name).toBe('bindings.layout.json');
  expect(layout.text).toContain('component:records');
  expect(layout.text).not.toContain('kv-registry');
  await expect(page.locator('#graph-text-0')).toContainText('audit-record: when records publishes kv-registry.entry-put.v1');

  await page.locator('#tab-form').click();
  await page.locator('#add-binding').click();
  await page.locator('#dialog-accept').click();
  await expect(page.locator('#select-binding-1')).toBeVisible();
  await page.locator('#select-binding-1').click();
  await page.locator('#b1-up').click();
  await expect(page.locator('#select-binding-0')).toContainText('binding-2');
  const yaml = await page.locator('#yaml-text').inputValue();
  expect(yaml.indexOf('id: binding-2')).toBeLessThan(yaml.indexOf('id: audit-record'));
});

test('a blueprint composite is edited in place and every other chain is preserved', async ({ page }) => {
  const { requests } = await open(page);
  const text = studioFixture('blueprint-two-chains.yaml');
  await page.locator('#import-blueprint').setInputFiles({ name: 'appchain.yaml', mimeType: 'text/yaml', buffer: Buffer.from(text) });
  await expect(page.locator('#document-panel')).toContainText('chain workflow');
  await page.locator('#select-binding-0').click();
  const unchanged = await downloadText(page, () => page.locator('#download-blueprint').click());
  expect(unchanged.text).toBe(text);
  await page.locator('#b0-map-reference-literal-value').fill('changed');
  await page.locator('#b0-map-reference-literal-value').press('Tab');
  // The composite carries a comment the splice cannot keep, so the first edit asks first.
  await expect(page.locator('#dialog')).toContainText('Comments, quoting and spacing');
  await page.locator('#dialog-accept').click();
  await expect(page.locator('#yaml-text')).toHaveValue(/literal: changed/);
  const exported = await downloadText(page, () => page.locator('#download-blueprint').click());
  expect(exported.name).toBe('appchain.yaml');
  const before = readBlueprint(text);
  const after = readBlueprint(exported.text);
  const chains = value => typedTree(value.root).map.find(([name]) => name === 'spec')[1].map
    .find(([name]) => name === 'chains')[1].seq;
  expect(chains(after)[0]).toEqual(chains(before)[0]);
  expect(exported.text.slice(0, before.composites[0].node.start - 60)).toBe(text.slice(0, before.composites[0].node.start - 60));
  expect(exported.text).toContain('# trailing comment belongs to the chain, not the composite');
  expect(after.composites[0].chainId).toBe('workflow');
  await page.locator('#tab-validate').click();
  await expect(page.locator('#panel-validate')).toContainText('project rendering derives the chain’s real context');
  expect(requests.every(request => request.method === 'GET' && request.url.startsWith(`${ORIGIN}/studio/`))).toBe(true);
});

test('changes fired before the form redraws never overwrite each other or hit the wrong item', async ({ page }) => {
  await open(page);
  await loadStarter(page, 'registry-to-audit');
  await page.locator('#select-binding-0').click();
  await page.locator('#b0-map-reference-literal-value').fill('first');
  await page.locator('#b0-map-reference-literal-value').press('Tab');
  await page.locator('#dialog-accept').click();
  await settle(page);
  // Two leaf controls of one drawn form change within one task, before any redraw.
  await page.evaluate(() => {
    const fire = (id, value) => {
      const element = document.getElementById(id);
      element.value = value;
      element.dispatchEvent(new Event('change', { bubbles: true }));
    };
    fire('b0-map-reference-literal-value', 'second');
    fire('b0-map-entryHash-field', 'value');
  });
  await settle(page);
  await expect(page.locator('#yaml-text')).toHaveValue(/reference: \{literal: second\}/);
  await expect(page.locator('#yaml-text')).toHaveValue(/entryHash: \{field: value\}/);
  // A structural change followed by a control from the stale form is refused, not applied elsewhere.
  await page.evaluate(() => {
    document.getElementById('b0-map-entityId-remove').click();
    const stale = document.getElementById('b0-map-reference-literal-value');
    stale.value = 'stale';
    stale.dispatchEvent(new Event('change', { bubbles: true }));
  });
  await settle(page);
  await expect(page.locator('#status')).toContainText('was not applied');
  await expect(page.locator('#yaml-text')).not.toHaveValue(/entityId/);
  await expect(page.locator('#yaml-text')).toHaveValue(/reference: \{literal: second\}/);
});

test('same-shaped reorders and delayed confirmations never apply a stale control to another item', async ({ page }) => {
  await open(page);
  const text = starter('registry-to-audit.yaml').replace("when: [{expr: 'event.valueLength < 100'}]",
    "when: [{expr: 'event.valueLength < 100'}, {expr: 'event.valueLength > 1'}]");
  await page.locator('#import-yaml').setInputFiles({ name: 'two.yaml', mimeType: 'text/yaml', buffer: Buffer.from(text) });
  await page.locator('#select-binding-0').click();
  await page.locator('#b0-w0-expr').fill('event.valueLength < 99');
  await page.locator('#b0-w0-expr').press('Tab');
  await page.locator('#dialog-accept').click();
  await settle(page);
  // Move clause 2 above clause 1 and, before the redraw, edit the old first-clause control.
  await page.evaluate(() => {
    document.getElementById('b0-w1-up').click();
    const stale = document.getElementById('b0-w0-expr');
    stale.value = 'stale';
    stale.dispatchEvent(new Event('change', { bubbles: true }));
  });
  await settle(page);
  await expect(page.locator('#status')).toContainText('was not applied');
  const yaml = await page.locator('#yaml-text').inputValue();
  expect(yaml).not.toContain('stale');
  expect(yaml.indexOf('event.valueLength > 1')).toBeLessThan(yaml.indexOf('event.valueLength < 99'));

  // A confirmation opened before a structural change is refused instead of removing whatever is now at its index.
  await page.locator('#b0-remove').click();
  await expect(page.locator('#dialog')).toBeVisible();
  await page.evaluate(() => document.getElementById('add-binding').click());
  await settle(page);
  await page.locator('#dialog-accept').click();
  await settle(page);
  await expect(page.locator('#status')).toContainText('was not applied');
  await expect(page.locator('#binding-list li')).toHaveCount(2);
  await expect(page.locator('#select-binding-0')).toContainText('audit-record');
});

test('repairing an invalid import counts as unsaved work', async ({ page }) => {
  await open(page);
  await page.locator('#import-yaml').setInputFiles({ name: 'broken.yaml', mimeType: 'text/yaml', buffer: Buffer.from('components: [\n') });
  await expect(page.locator('#mode-badge')).toHaveText('Text not valid');
  await expect(page.locator('#banner-revert')).toHaveCount(0);
  await page.locator('#tab-yaml').click();
  await expect(page.locator('#revert-text')).toBeDisabled();
  await page.locator('#yaml-text').fill('components: [{id: repaired, machine: kv-registry}]\nbindings: []\n');
  await expect(page.locator('#mode-badge')).toHaveText('Editable draft');
  await page.locator('#new-document').click();
  await expect(page.locator('#dialog')).toContainText('Replace the current draft?');
  await page.locator('#dialog-cancel').click();
  await expect(page.locator('#select-component-0')).toContainText('repaired');
});

const scenarioFile = name => ({ name, mimeType: 'application/json',
  buffer: fs.readFileSync(path.join(repo, 'tooling/studio/src/test/fixtures/scenarios', name)) });

test('imported rehearsal reports explain a multi-block approval and turn stale after an edit', async ({ page }) => {
  const { requests } = await open(page);
  await loadStarter(page, 'approval-to-audit');
  await page.locator('#tab-validate').click();
  await expect(page.locator('#assurance-state')).toContainText('local checks only');
  await page.locator('#import-reports').setInputFiles([4, 2, 1, 3].map(block => scenarioFile(`approval-report-${block}.json`)));
  await expect(page.locator('.report-entry')).toHaveCount(4);
  await expect(page.locator('#assurance-state')).toContainText('Imported matching CLI report');
  await page.locator('#import-fixtures').setInputFiles([1, 2, 3, 4].map(block =>
    path.join(repo, `examples/bindings/fixtures/approval-to-audit/fixture-${block}.json`)));
  await expect(page.locator('#assurance-state')).toContainText('Rehearsed source outcomes');
  const blocks = page.locator('.block');
  await expect(blocks).toHaveCount(4);
  await expect(blocks.nth(0)).toContainText('Block 1');
  await expect(blocks.nth(2)).toContainText('No messages in this block');
  await expect(blocks.nth(3)).toContainText('Accepted: all 2 recorded steps committed together');
  await expect(blocks.nth(1)).toContainText('No step produced the source event of: record-approved');
  await blocks.nth(3).getByRole('button', { name: 'Open record-approved' }).click();
  await expect(page.locator('#editor-title')).toHaveText('Binding 1 · record-approved');

  // Re-importing a fixture name with different content replaces it: rehearsals of the earlier content are stale.
  const first = fs.readFileSync(path.join(repo, 'examples/bindings/fixtures/approval-to-audit/fixture-1.json'), 'utf8');
  await page.locator('#tab-validate').click();
  await page.locator('#import-fixtures').setInputFiles({ name: 'fixture-1.json', mimeType: 'application/json',
    buffer: Buffer.from(first.replace('"timestamp" : 1000', '"timestamp" : 1001')) });
  await expect(page.locator('#status')).toContainText('Replaced the earlier fixture-1.json');
  await expect(page.locator('#assurance-state')).toContainText('Imported matching CLI report');
  await expect(page.locator('#assurance-state')).not.toContainText('Rehearsed');
  await expect(page.locator('.block').nth(0)).toContainText('fixture-1.json was replaced by a file with different content');
  await page.locator('#import-fixtures').setInputFiles(path.join(repo,
    'examples/bindings/fixtures/approval-to-audit/fixture-1.json'));
  await expect(page.locator('#assurance-state')).toContainText('Rehearsed source outcomes');
  await page.locator('#tab-form').click();

  // Any edit makes every report stale; stale reports never link into the draft.
  await page.locator('#b0-map-reference-literal-value').fill('changed');
  await page.locator('#b0-map-reference-literal-value').press('Tab');
  await page.locator('#dialog-accept').click();
  await page.locator('#tab-validate').click();
  await expect(page.locator('#assurance-state')).toContainText('Stale or unverifiable report');
  await expect(page.locator('.block').nth(3).getByRole('button', { name: 'Open record-approved' })).toHaveCount(0);
  await axe(page, 'validate view');
  // Removing a report keeps keyboard focus in the list and announces the new status.
  await page.locator('#remove-report-0').click();
  await expect(page.locator('.report-entry')).toHaveCount(3);
  await focusIs(page, 'remove-report-0');
  await expect(page.locator('#status')).toContainText('Removed');
  expect(requests.every(request => request.method === 'GET' && request.url.startsWith(`${ORIGIN}/studio/`))).toBe(true);
});

test('a browser-exported document rehearsed by the real CLI imports back as a matching rehearsal', async ({ page }) => {
  const cli = process.env.BINDING_CLI;
  const bundles = process.env.BINDING_BUNDLES;
  test.skip(!cli || !bundles, 'BINDING_CLI and BINDING_BUNDLES are provided by :tooling:studio:browserTestStudio');
  await open(page);
  await loadStarter(page, 'approval-to-audit');
  await page.locator('#tab-validate').click();
  const work = fs.mkdtempSync(path.join(os.tmpdir(), 'studio-handoff-'));
  try {
    // Everything the CLI needs comes from the page: the document, the tutorial context and the example fixtures.
    const exported = await downloadText(page, () => page.locator('#handoff-download-document').click());
    fs.writeFileSync(path.join(work, exported.name), exported.text);
    const context = await downloadText(page, () => page.locator('#handoff-context').click());
    fs.writeFileSync(path.join(work, context.name), context.text);
    const downloads = [];
    page.on('download', download => downloads.push(download));
    await page.locator('#handoff-fixtures').click();
    await expect.poll(() => downloads.length).toBe(4);
    for (const download of downloads) fs.copyFileSync(await download.path(), path.join(work, download.suggestedFilename()));
    await expect(page.locator('#handoff-blocks')).toHaveValue('4');
    const commands = (await page.locator('#handoff-commands').textContent()).split('\n');
    expect(commands.length).toBe(6);
    const plugins = path.join(work, 'plugins');
    fs.mkdirSync(plugins);
    for (const bundle of bundles.split(path.delimiter)) fs.copyFileSync(bundle, path.join(plugins, path.basename(bundle)));
    // Run exactly the commands Studio showed, with the launcher and plugin directory substituted.
    for (const command of commands) {
      const [argv, redirect] = command.split(' > ');
      const args = argv.split(' ').slice(2).map(arg => arg === '<plugins-directory>' ? plugins : arg);
      const output = execFileSync(cli, args, { cwd: work, encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] });
      if (redirect) fs.writeFileSync(path.join(work, redirect), output);
    }
    await page.locator('#import-catalog').setInputFiles(path.join(work, 'catalog.json'));
    await expect(page.locator('#catalog-panel')).toContainText('catalog.json');
    await page.locator('#import-reports').setInputFiles(['validate-report.json',
      ...[1, 2, 3, 4].map(block => `dry-run-report-${block}.json`)].map(name => path.join(work, name)));
    await page.locator('#import-fixtures').setInputFiles([1, 2, 3, 4].map(block => path.join(work, `fixture-${block}.json`)));
    await expect(page.locator('#assurance-state')).toContainText('Rehearsed source outcomes');
    await expect(page.locator('.block')).toHaveCount(4);
    await expect(page.locator('.block').nth(3)).toContainText('Accepted: all 2 recorded steps committed together');
    // A byte-level fixture edit needs a fresh report. Keep the historical report while importing the rerun:
    // the replacement still continues block 1, and blocks 3-4 continue through its matching state.
    fs.appendFileSync(path.join(work, 'fixture-2.json'), '\n');
    await page.locator('#import-fixtures').setInputFiles(path.join(work, 'fixture-2.json'));
    const [argv, redirect] = commands[3].split(' > ');
    const args = argv.split(' ').slice(2).map(arg => arg === '<plugins-directory>' ? plugins : arg);
    const output = execFileSync(cli, args, { cwd: work, encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] });
    fs.writeFileSync(path.join(work, redirect), output);
    await page.locator('#import-reports').setInputFiles(path.join(work, 'dry-run-report-2.json'));
    const histories = page.locator('.block');
    await expect(histories).toHaveCount(5);
    await expect(histories.nth(1)).toContainText('fixture or continuation differs');
    for (const index of [0, 2, 3, 4]) {
      await expect(histories.nth(index)).toContainText('fixture and continuation verified');
    }
    await expect(histories.nth(2)).toContainText('continues an imported report at the preceding height');
    await expect(page.locator('#assurance-state')).toContainText('1 rehearsal block is stale or unverified');
    // Deleting only the replacement removes the verified path; the retained old report cannot stand in for it.
    await page.locator('#remove-report-5').click();
    await expect(page.locator('.block')).toHaveCount(4);
    await expect(page.locator('.block').nth(2)).toContainText('rehearsal inputs not verified');
    await expect(page.locator('.block').nth(3)).toContainText('rehearsal inputs not verified');
  } finally {
    fs.rmSync(work, { recursive: true, force: true });
  }
});

test('diagnostics from a matching failed validation navigate to the authored field', async ({ page }) => {
  await open(page);
  const invalid = fs.readFileSync(path.join(repo, 'tooling/studio/src/test/fixtures/tutorial-bindings.yaml'), 'utf8')
    .replace('{field: valueHash}', '{fn: hex, args: [{field: key}]}');
  await page.locator('#import-yaml').setInputFiles({ name: 'bindings.yaml', mimeType: 'text/yaml', buffer: Buffer.from(invalid) });
  await page.locator('#tab-validate').click();
  await page.locator('#import-reports').setInputFiles({ name: 'validate-report.json', mimeType: 'application/json',
    buffer: fs.readFileSync(path.join(repo, 'tooling/studio/src/test/fixtures/report-validate-failed.json')) });
  await expect(page.locator('#validation-heading')).toHaveText('CLI results');
  await expect(page.locator('#check-heading-0')).toHaveText('validate-report.json · validate failed: the document was rejected');
  await expect(page.locator('#assurance-state')).toContainText('the CLI rejected exactly the current inputs');
  await expect(page.locator('#panel-validate')).toContainText('The CLI rejected exactly the current inputs');
  await expect(page.locator('#panel-validate')).toContainText('The source and destination types do not match');
  await page.locator('#diagnostic-go-0-0').click();
  await expect(page.locator('#editor-title')).toHaveText('Binding 1 · audit-record');
  await expect.poll(() => activeId(page)).toMatch(/^b0-map-entryHash/);
});

test('forged reports are rejected and handoff commands use fixed names only', async ({ page }) => {
  await open(page);
  await loadStarter(page, 'registry-to-audit');
  await page.locator('#tab-validate').click();
  const forged = JSON.parse(fs.readFileSync(path.join(repo, 'tooling/studio/src/test/fixtures/scenarios/cascade-report.json'), 'utf8'));
  forged.result.rehearsal.messages[0].receipt.status = 'REJECTED';
  await page.locator('#import-reports').setInputFiles({ name: 'forged.json', mimeType: 'application/json',
    buffer: Buffer.from(JSON.stringify(forged)) });
  await expect(page.locator('#status')).toContainText('forged.json');
  await expect(page.locator('.report-entry')).toHaveCount(0);
  await page.locator('#handoff-blocks').selectOption('3');
  await expect(page.locator('#handoff-commands')).toContainText('--fixture fixture-3.json --prior-result result-2.json');
  const handoff = await downloadText(page, () => page.locator('#handoff-download').click());
  expect(handoff.name).toBe('bindings-handoff.txt');
  expect(handoff.text).toContain('Reports are unauthenticated files');
  expect(handoff.text).not.toContain('audit-record');
  const context = await downloadText(page, () => page.locator('#handoff-context').click());
  expect(context.name).toBe('context.json');
  expect(context.text).toBe(fs.readFileSync(path.join(repo, 'tooling/studio/src/main/web/binding-authoring-context.json'), 'utf8'));
});

test('lookups, in-lists, effects, limits, reordering and graph navigation also work by keyboard alone', async ({ page }) => {
  await open(page);
  await tabTo(page, 'load-starter');
  await page.keyboard.press('Enter');
  await expect(page.locator('#yaml-text')).toHaveValue(starter('registry-to-audit.yaml'));
  await tabTo(page, 'select-binding-0');
  await page.keyboard.press('Enter');
  await tabTo(page, 'b0-add-lookup');
  await page.keyboard.press('Enter');
  await focusIs(page, 'dialog-cancel');
  await page.keyboard.press('Tab');
  await page.keyboard.press('Enter');
  await tabTo(page, 'b0-w1-participant');
  await page.keyboard.type('audit');
  await tabTo(page, 'b0-add-field-clause');
  await page.keyboard.press('Enter');
  await tabTo(page, 'b0-w2-operator');
  await page.keyboard.type('in');
  await expect(page.locator('#b0-w2-operator')).toHaveValue('in');
  await tabTo(page, 'b0-w2-in0-value');
  await page.keyboard.type('01');
  await page.keyboard.press('Tab');
  await tabTo(page, 'b0-w2-in-add');
  await page.keyboard.press('Enter');
  await tabTo(page, 'b0-w2-in1-value');
  await page.keyboard.press('ControlOrMeta+A');
  await page.keyboard.type('02');
  await page.keyboard.press('Tab');

  await tabTo(page, 'add-binding');
  await page.keyboard.press('Enter');
  await tabTo(page, 'b1-event');
  await page.keyboard.type('kv-registry.entry-d');
  await tabTo(page, 'b1-target-kind-command');
  await page.keyboard.press('ArrowDown');
  await expect(page.locator('#b1-target-kind-effect')).toBeChecked();
  await tabTo(page, 'b1-effect-type');
  await page.keyboard.type('notify.v1');
  await page.keyboard.press('Tab');
  await tabTo(page, 'b1-mapping-kind');
  await page.keyboard.type('Identity');
  await expect(page.locator('#b1-mapping-kind')).toHaveValue('identity');
  // Arrow keys through the target kinds never lose authored content: switching back restores it.
  await tabTo(page, 'b1-target-kind-effect');
  await page.keyboard.press('ArrowUp');
  await expect(page.locator('#b1-target-kind-command')).toBeChecked();
  await settle(page);
  await page.keyboard.press('ArrowDown');
  await expect(page.locator('#b1-effect-type')).toHaveValue('notify.v1');
  await expect(page.locator('#b1-mapping-kind')).toHaveValue('identity');

  await tabTo(page, 'b1-up');
  await page.keyboard.press('Enter');
  await expect(page.locator('#select-binding-0')).toContainText('binding-2');
  await tabTo(page, 'select-limits');
  await page.keyboard.press('Enter');
  await tabTo(page, 'limit-maxCascadeDepth');
  await page.keyboard.type('4');
  await page.keyboard.press('Tab');
  // Tabs use a roving tabindex: Tab reaches the selected tab and arrow keys move between tabs.
  await tabTo(page, 'tab-form');
  await page.keyboard.press('ArrowRight');
  await page.keyboard.press('ArrowRight');
  await focusIs(page, 'tab-graph');
  await expect(page.locator('#panel-graph')).toBeVisible();
  await tabTo(page, 'graph-node-component%3Aaudit');
  await page.keyboard.press('Enter');
  await expect(page.locator('#editor-title')).toHaveText('Component · audit');

  await tabTo(page, 'download-document');
  const exported = await downloadText(page, () => page.keyboard.press('Enter'));
  const document = tree(exported.text).map[0][1].map;
  const [, components] = document.find(([name]) => name === 'components');
  const [, bindings] = document.find(([name]) => name === 'bindings');
  const [, limits] = document.find(([name]) => name === 'limits');
  expect(components.seq.length).toBe(2);
  expect(JSON.stringify(bindings.seq[0])).toContain('"effect"');
  expect(JSON.stringify(bindings.seq[0])).toContain('notify.v1');
  expect(JSON.stringify(bindings.seq[0])).toContain('"identity"');
  expect(JSON.stringify(bindings.seq[1])).toContain('"lookup"');
  expect(JSON.stringify(bindings.seq[1])).toContain('"in"');
  expect(exported.text).toContain('in: [{bytesHex: "01"}, {bytesHex: "02"}]');
  expect(limits).toEqual({ map: [['maxCascadeDepth', { int: '4' }]] });
});

test('broken, pending and unsupported documents are labelled honestly', async ({ page }) => {
  await open(page);
  await expect(page.locator('#download-original')).toHaveCount(0);
  await page.locator('#import-yaml').setInputFiles({ name: 'broken.yaml', mimeType: 'text/yaml', buffer: Buffer.from('components: [\n') });
  await expect(page.locator('#mode-banner')).toContainText('There is no valid draft yet');
  await expect(page.locator('#mode-banner')).not.toContainText(' at  (line');
  await expect(page.locator('#download-original')).toBeVisible();
  await page.locator('#tab-yaml').click();
  await expect(page.locator('#yaml-state')).toContainText('Not a valid document');

  const blueprint = studioFixture('blueprint-two-chains.yaml');
  const twoChains = blueprint + blueprint.slice(blueprint.indexOf('    - chainId: "workflow"')).replace('chainId: "workflow"', 'chainId: "second"');
  // An untouched broken import is not unsaved work, so no confirmation is needed.
  await page.locator('#import-blueprint').setInputFiles({ name: 'appchain.yaml', mimeType: 'text/yaml', buffer: Buffer.from(twoChains) });
  await expect(page.locator('#mode-badge')).toHaveText('Choose a chain');
  await expect(page.locator('#mode-banner')).toContainText('Choose one under Blueprint chain');
  await page.locator('#blueprint-chain').selectOption('');
  await expect(page.locator('#mode-badge')).toHaveText('Choose a chain');
  await page.locator('#blueprint-chain').selectOption({ label: 'second' });
  await expect(page.locator('#document-panel')).toContainText('chain second');

  const unsupported = blueprint.replace('entryHash: {field: valueHash}', 'entryHash: {field: valueHash, futureKey: 1}');
  await page.locator('#import-blueprint').setInputFiles({ name: 'future.yaml', mimeType: 'text/yaml', buffer: Buffer.from(unsupported) });
  await expect(page.locator('#mode-badge')).toHaveText('Read-only');
  await expect(page.locator('#mode-banner')).toContainText('futureKey');
  const original = await downloadText(page, () => page.locator('#download-original').click());
  expect(original.name).toBe('original-appchain.yaml');
  expect(original.text).toBe(unsupported);
});

test('the graph draws dangling bindings, moves nodes without dragging, and hidden characters are flagged', async ({ page }) => {
  await open(page);
  await loadStarter(page, 'registry-to-audit');
  await page.locator('#tab-yaml').click();
  const text = starter('registry-to-audit.yaml').replace('component: audit', 'component: ghost')
    .replace("'event.valueLength < 100'", '"event.valueLength < 100 \\u202e && false"');
  await page.locator('#yaml-text').fill(text);
  await settle(page);
  await expect(page.locator('#checks')).toContainText('HIDDEN_CHARACTERS');
  await page.locator('#tab-graph').click();
  await expect(page.locator('.graph-edge.dangling')).toHaveCount(1);
  await expect(page.locator('.graph-missing')).toContainText('ghost');
  const node = page.locator('[id="graph-node-component%3Arecords"]');
  const before = await node.getAttribute('transform');
  await page.locator('#move-node').selectOption('component:records');
  await page.locator('#move-right').click();
  await expect(node).not.toHaveAttribute('transform', before);
  await expect(page.locator('#yaml-text')).toHaveValue(text);
});

test('lookup expectations restore their operand when switched back', async ({ page }) => {
  await open(page);
  const text = fs.readFileSync(path.join(repo, 'tooling/studio/src/test/fixtures/corpus/conditions.yaml'), 'utf8');
  await page.locator('#import-yaml').setInputFiles({ name: 'conditions.yaml', mimeType: 'text/yaml', buffer: Buffer.from(text) });
  const draft = (await import('../../tooling/studio/src/main/web/binding-draft.mjs')).importDocument(text).draft;
  const bindingIndex = draft.bindings.findIndex(binding => (binding.when ?? []).some(clause => clause.kind === 'lookup' && clause.expectation === 'eq'));
  const clauseIndex = draft.bindings[bindingIndex].when.findIndex(clause => clause.kind === 'lookup' && clause.expectation === 'eq');
  expect(bindingIndex).toBeGreaterThanOrEqual(0);
  await page.locator(`#select-binding-${bindingIndex}`).click();
  const expectation = page.locator(`#b${bindingIndex}-w${clauseIndex}-expectation`);
  await expectation.selectOption('exists');
  const dialog = page.locator('#dialog');
  if (await dialog.isVisible()) await page.locator('#dialog-accept').click();
  await settle(page);
  await expectation.selectOption('eq');
  await settle(page);
  const after = (await import('../../tooling/studio/src/main/web/binding-draft.mjs'))
    .importDocument(await page.locator('#yaml-text').inputValue()).draft;
  expect(after.bindings[bindingIndex].when[clauseIndex].operand).toEqual(draft.bindings[bindingIndex].when[clauseIndex].operand);
});

test('the editor fits a phone-width viewport without horizontal scrolling', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await open(page);
  await loadStarter(page, 'approval-to-audit');
  await page.locator('#select-binding-0').click();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.locator('#select-limits').click();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});
