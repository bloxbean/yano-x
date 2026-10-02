import { test, expect } from '@playwright/test';

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
  await expect(demo.locator('#stage-description')).toContainText('issuer proposes a document release');
  await page.getByRole('tab', { name: 'Roles', exact: true }).press('End');
  await expect(page.getByRole('tab', { name: 'Observations', exact: true })).toHaveAttribute('aria-selected', 'true');
  await expect(demo.locator('#stage-description')).toContainText('requests a delivery observation');
  await demo.getByRole('button', { name: 'Next step' }).click();
  await expect(demo).toHaveAttribute('data-stage', '1');
  await page.getByRole('tab', { name: 'Observations', exact: true }).press('Home');
  await expect(demo.locator('[data-command]')).toHaveText('order.created');
  await expect(demo).toHaveAttribute('data-stage', '0');
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

test('the project status banner appears once on the landing page and on docs pages', async ({ page }) => {
  for (const route of ['/', '/start-here/what-is-an-app-ledger/', '/concepts/consensus-and-finality/', '/404']) {
    await page.goto(route);
    const banner = page.getByRole('complementary', { name: 'Project status' });
    await expect(banner).toHaveCount(1);
    await expect(banner).toContainText('Active development');
    await expect(banner).toContainText('working toward its first developer preview');
    await expect(banner.getByRole('link', { name: 'Follow on GitHub ↗' }))
      .toHaveAttribute('href', 'https://github.com/bloxbean/yano-x');
  }
  await page.setViewportSize({ width: 360, height: 800 });
  await page.goto('/start-here/');
  await expect(page.getByRole('complementary', { name: 'Project status' })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});
