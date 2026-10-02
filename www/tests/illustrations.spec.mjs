import { test, expect } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import fs from 'node:fs';
import path from 'node:path';

function htmlFiles(dir) {
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap(entry => {
    const file = path.join(dir, entry.name);
    return entry.isDirectory() ? htmlFiles(file) : file.endsWith('.html') ? [file] : [];
  });
}

const dist = path.resolve('dist');
const routes = htmlFiles(dist)
  .filter(file => fs.readFileSync(file, 'utf8').includes('data-yx-illustration='))
  .map(file => '/' + path.relative(dist, file).replaceAll(path.sep, '/').replace(/index\.html$/, ''));

test('at least one page carries an illustration', () => {
  expect(routes.length).toBeGreaterThan(0);
});

for (const route of routes) {
  test(`illustrations work with keyboard, scenarios, and views: ${route}`, async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.goto(route);
    for (const figure of await page.locator('[data-yx-illustration="steps"]').all()) {
      await expect(figure).toHaveClass(/is-ready/);
      const caption = figure.locator('[data-caption]');
      const chips = figure.locator('.yx-chip');
      const steps = figure.locator('.yx-scn:not([hidden]) .yx-steps > li');
      await expect(chips).toHaveCount(await steps.count());
      await expect(chips.first()).toHaveAttribute('aria-pressed', 'true');
      await expect(figure.locator('[data-prev]')).toBeDisabled();

      await figure.locator('[data-next]').click();
      await expect(chips.nth(1)).toHaveAttribute('aria-pressed', 'true');
      await figure.focus();
      await page.keyboard.press('ArrowRight');
      await expect(chips.nth(2)).toHaveAttribute('aria-pressed', 'true');
      await page.keyboard.press('ArrowLeft');
      await expect(chips.nth(1)).toHaveAttribute('aria-pressed', 'true');
      await expect(caption).toContainText(await steps.nth(1).locator('.yx-steps__title').innerText());

      const last = (await chips.count()) - 1;
      await chips.nth(last).click();
      await expect(figure.locator('[data-next]')).toBeDisabled();

      const scenarios = figure.locator('[data-scenario-button]');
      for (let i = 1; i < await scenarios.count(); i++) {
        await scenarios.nth(i).click();
        await expect(scenarios.nth(i)).toHaveAttribute('aria-pressed', 'true');
        const visible = figure.locator('.yx-scn:not([hidden])');
        await expect(visible).toHaveCount(1);
        await expect(chips).toHaveCount(await visible.locator('.yx-steps > li').count());
        await expect(chips.first()).toHaveAttribute('aria-pressed', 'true');
      }
      if (await scenarios.count()) await scenarios.first().click();

      const views = figure.locator('[data-view-button]');
      if (await views.count() > 1) {
        const before = await caption.textContent();
        await views.nth(1).click();
        await expect(caption).not.toHaveText(before);
        await views.first().click();
        await expect(caption).toHaveText(before);
      }
    }
    expect(errors).toEqual([]);
  });

  test(`illustrations honour reduced motion: ${route}`, async ({ page }) => {
    await page.emulateMedia({ reducedMotion: 'reduce' });
    await page.goto(route);
    for (const figure of await page.locator('[data-yx-illustration="steps"]').all()) {
      const play = figure.locator('[data-play]');
      await expect(play).toHaveText('Next step');
      await play.click();
      await expect(figure.locator('.yx-chip').nth(1)).toHaveAttribute('aria-pressed', 'true');
    }
  });

  test(`illustrations fit phones and both themes: ${route}`, async ({ page }) => {
    for (const width of [360, 1280]) {
      await page.setViewportSize({ width, height: 900 });
      await page.goto(route);
      for (const theme of ['light', 'dark']) {
        await page.evaluate(theme => { document.documentElement.dataset.theme = theme; }, theme);
        expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
        for (const figure of await page.locator('[data-yx-illustration]').all()) {
          expect(await figure.evaluate(node => node.scrollWidth <= node.clientWidth + 1)).toBe(true);
          const svgs = figure.locator('svg.yx-diagram__svg');
          if (await svgs.count()) await expect(svgs.filter({ visible: true })).toHaveCount(1);
        }
      }
    }
  });

  test(`illustrations pass axe checks: ${route}`, async ({ page }) => {
    await page.emulateMedia({ reducedMotion: 'reduce' });
    await page.goto(route);
    for (const theme of ['light', 'dark']) {
      await page.evaluate(theme => { document.documentElement.dataset.theme = theme; }, theme);
      const results = await new AxeBuilder({ page }).include('.yx-ill').analyze();
      expect(results.violations.map(v => `${theme}: ${v.id} — ${v.nodes.map(n => n.target).join(', ')}`)).toEqual([]);
    }
  });
}

test('illustrations are readable without JavaScript', async ({ browser }) => {
  const context = await browser.newContext({ javaScriptEnabled: false });
  const page = await context.newPage();
  for (const route of routes) {
    await page.goto(route);
    for (const figure of await page.locator('[data-yx-illustration="steps"]').all()) {
      await expect(figure.locator('[data-controls]')).toBeHidden();
      await expect(figure.locator('.yx-steps').first()).toBeVisible();
      await expect(figure.locator('.yx-card.is-end').first()).toBeVisible();
    }
    for (const figure of await page.locator('[data-yx-illustration="diagram"]').all()) {
      await expect(figure.locator('svg.yx-diagram__svg').first()).toBeVisible();
    }
  }
  await context.close();
});

test('the old app chain route redirects to the app ledger page', async ({ page }) => {
  await page.goto('/start-here/what-is-an-app-chain/');
  await expect(page).toHaveURL(/\/start-here\/what-is-an-app-ledger\/$/);
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('What is an app ledger?');
});
