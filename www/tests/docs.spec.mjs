import { test, expect } from '@playwright/test';
import fs from 'node:fs';
import path from 'node:path';

function htmlFiles(dir) {
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap(entry => {
    const file = path.join(dir, entry.name);
    return entry.isDirectory() ? htmlFiles(file) : file.endsWith('.html') ? [file] : [];
  });
}
const dist = path.resolve('dist');
const diagramPages = htmlFiles(dist).filter(file => fs.readFileSync(file, 'utf8').includes('class="mermaid"'));

for (const file of diagramPages) {
  const route = '/' + path.relative(dist, file).replaceAll(path.sep, '/').replace(/index\.html$/, '');
  test(`diagrams render and survive theme changes: ${route}`, async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.goto(route);
    const diagrams = page.locator('pre.mermaid');
    const count = await diagrams.count();
    expect(count).toBeGreaterThan(0);
    const sources = await diagrams.evaluateAll(nodes => nodes.map(node => node.dataset.source || node.textContent));
    for (const theme of ['light', 'dark', 'light']) {
      await page.evaluate(theme => {
        document.documentElement.dataset.theme = theme;
      }, theme);
      await expect(diagrams.locator('svg')).toHaveCount(count);
      await expect(diagrams.locator('.error-text')).toHaveCount(0);
      await expect.poll(() => diagrams.evaluateAll(nodes => nodes.every(node =>
        node.dataset.renderedTheme === document.documentElement.dataset.theme))).toBe(true);
      expect(await diagrams.evaluateAll(nodes => nodes.map(node => node.dataset.source))).toEqual(sources);
    }
    // Exercise changes arriving while the asynchronous renderer is still busy.
    await page.evaluate(async () => {
      for (const theme of ['dark', 'light', 'dark', 'light', 'dark']) {
        document.documentElement.dataset.theme = theme;
        await new Promise(resolve => setTimeout(resolve, 5));
      }
    });
    await expect.poll(() => diagrams.evaluateAll(nodes => nodes.every(node =>
      node.dataset.renderedTheme === 'dark'))).toBe(true);
    await expect(diagrams.locator('.error-text')).toHaveCount(0);
    expect(errors).toEqual([]);
  });
}

test('landing examples and learning path work on mobile', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/');
  await expect(page.getByRole('heading', { level: 1 })).toContainText('Verifiable results.');
  await page.getByRole('button', { name: 'Approval workflows', exact: true }).click();
  await expect(page.locator('#path-coordinate')).toBeVisible();
  await expect(page.locator('#path-record')).toBeHidden();
  await page.getByRole('button', { name: /Submit/ }).click();
  await expect(page.locator('#stage-description')).toContainText('Acceptance does not yet mean');
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.getByRole('link', { name: 'Docs', exact: true }).click();
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('Your path through Yano X');
});

test('illustration tabs explain each capability and support keyboard navigation', async ({ page }) => {
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await page.goto('/');
  const demo = page.locator('.chain-demo');
  await expect(page.getByRole('tab', { name: 'Event log', exact: true })).toHaveAttribute('aria-selected', 'true');
  await page.getByRole('tab', { name: 'Registry', exact: true }).click();
  await expect(demo.locator('[data-command]')).toHaveText('assets / A-100');
  await expect(demo.locator('[data-guide]')).toHaveAttribute('href', '/state-machines/authenticated-map/');
  await page.getByRole('tab', { name: 'Registry', exact: true }).press('ArrowRight');
  await expect(page.getByRole('tab', { name: 'Roles', exact: true })).toBeFocused();
  await expect(demo.locator('.example-facts')).toContainText('Two distinct organizations approve');
  await expect(demo.locator('#stage-description')).toContainText('separate checks');
  await page.getByRole('tab', { name: 'Roles', exact: true }).press('End');
  await expect(page.getByRole('tab', { name: 'Observations', exact: true })).toHaveAttribute('aria-selected', 'true');
  await expect(demo.locator('#stage-description')).toContainText('not that a parcel physically arrived');
  await demo.getByRole('button', { name: 'Next step' }).click();
  await expect(demo).toHaveAttribute('data-stage', '3');
  await page.getByRole('tab', { name: 'Observations', exact: true }).press('Home');
  await expect(demo.locator('[data-command]')).toHaveText('order.created');
  await expect(demo).toHaveAttribute('data-stage', '2');
});

test('branding and illustration tabs fit mobile and docs themes', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/');
  for (const width of [390, 320]) {
    await page.setViewportSize({ width, height: 844 });
    for (const name of ['Event log', 'Registry', 'Roles', 'Observations']) {
      const tab = page.getByRole('tab', { name, exact: true });
      await tab.click();
      expect(await tab.evaluate(node => node.scrollWidth <= node.clientWidth)).toBe(true);
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    }
  }
  await page.goto('/concepts/observations/');
  await expect(page.locator('header .yano-brand .wordmark')).toHaveText('yanox');
  await expect(page.locator('link[rel~="icon"]')).toHaveAttribute('href', '/favicon.svg');
  for (const theme of ['light', 'dark']) {
    await page.evaluate(theme => document.documentElement.dataset.theme = theme, theme);
    await expect(page.locator('header .yano-brand img')).toBeVisible();
    expect(await page.locator('header .yano-brand img').evaluate(img => img.complete && img.naturalWidth > 0)).toBe(true);
  }
});
