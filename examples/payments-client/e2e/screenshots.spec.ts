/**
 * Screenshots of the main screens for the README and the PR (npm run screenshots), taken on a DevKit devnet with the
 * node from e2e/devkit-node.sh. Run the E2E first so DevKit accounts 0 and 1 are registered and funded; account 2 is
 * left unregistered for the onboarding screen. The explorer screenshots need no wallet:
 * npm run screenshots -- -g explorer
 */
import { expect, test, type Page } from '@playwright/test';

const DEVKIT = process.env.DEVKIT_WALLET_URL ?? 'http://localhost:10000';
const PAGE = `/?devkit=${encodeURIComponent(`${DEVKIT}/wallet-sdk.js`)}`;
const DIR = 'docs/screenshots';

async function activeAccount(id: string): Promise<void> {
  const response = await fetch(`${DEVKIT}/api/v1/wallet/active-account`, {
    method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ accountId: id })
  });
  expect(response.ok).toBeTruthy();
}

async function connect(page: Page): Promise<string> {
  await page.getByTestId('connect-wallet').click();
  await page.getByTestId('wallet-yacidevkit').click();
  await expect(page.getByTestId('account-id')).toHaveText(/^acct_test1[02-9ac-hj-np-z]{51}$/);
  return (await page.getByTestId('account-id').textContent())!;
}

const latest = (page: Page) => page.getByTestId('activity').locator('[data-testid^="receipt-"]').first();

async function shot(page: Page, name: string, fullPage = false): Promise<void> {
  // Toasts would cover the content, and a full-page capture keeps the sticky header where the page is scrolled to.
  const hide = await page.addStyleTag({ content: '[data-testid="toasts"] { display: none !important; }' });
  if (fullPage) await page.evaluate(() => window.scrollTo(0, 0));
  await page.waitForTimeout(400);
  await page.screenshot({ path: `${DIR}/${name}.png`, fullPage, animations: 'disabled' });
  await hide.evaluate((node) => (node as Element).remove());
}

test.describe.serial('screenshots', () => {
  let bob = '';
  let bobAddress = '';

  test('welcome, wallet picker and the unsupported-wallet message', async ({ page }) => {
    await page.goto(PAGE);
    await expect(page.getByTestId('connect-wallet')).toBeEnabled();
    await shot(page, '01-welcome', true);
    await page.addInitScript(() => {
      (window as unknown as { cardano: Record<string, unknown> }).cardano = {
        nosign: { name: 'No signData', enable: async () => ({ getNetworkId: async () => 0 }) }
      };
    });
    await page.goto(PAGE);
    await page.getByTestId('connect-wallet').click();
    await expect(page.getByTestId('wallet-yacidevkit')).toBeVisible();
    await page.getByTestId('wallet-nosign').click();
    await expect(page.getByTestId('wallet-error')).toBeVisible();
    await shot(page, '02-wallet-picker');
  });

  test('onboarding and the register review', async ({ page }) => {
    await activeAccount('1');
    await page.goto(PAGE);
    bob = await connect(page);
    bobAddress = (await page.getByTestId('wallet-address').textContent())!;
    await activeAccount('2');
    await page.goto(PAGE);
    await connect(page);
    await expect(page.getByTestId('onboarding')).toBeVisible();
    await shot(page, '03-onboarding');
    await page.getByTestId('register').click();
    await expect(page.getByTestId('review')).toBeVisible();
    await shot(page, '04-review-register');
    await page.getByTestId('review-cancel').click();
  });

  test('dashboard, flows and panels', async ({ page }) => {
    await activeAccount('0');
    await page.goto(PAGE);
    await connect(page);
    await expect(page.getByTestId('registration')).toHaveText(/^Registered/);

    // Revoke sessions an earlier run left behind (their keys are not in this fresh browser).
    const stale = page.getByTestId('sessions').locator('[data-testid^="grant-"]');
    await page.waitForTimeout(1_500);
    while (await stale.count() > 0) {
      const id = (await stale.first().getAttribute('data-testid'))!;
      await stale.first().getByTestId('session-revoke').click();
      await page.getByTestId('review-sign').click();
      await expect(page.getByTestId(id)).toHaveCount(0);
    }

    // Deposit: the sheet, then the live status once credited.
    await page.getByTestId('action-deposit').click();
    await page.getByTestId('deposit-amount').fill('10');
    await shot(page, '05-deposit');
    await page.getByTestId('deposit-go').click();
    await expect(page.getByTestId('review')).toBeVisible();
    await shot(page, '06-review-deposit');
    await page.getByTestId('review-sign').click();
    await expect(page.getByTestId('deposit-state')).toHaveText(/^CREDITED/, { timeout: 180_000 });
    await shot(page, '07-deposit-credited');
    await page.getByTestId('modal-close').click();

    // Send to Bob's wallet address: the form with the resolved account, then the review naming both.
    await page.getByTestId('action-send').click();
    await page.getByTestId('transfer-to').fill(bobAddress);
    await page.getByTestId('transfer-amount').fill('2.5');
    await expect(page.getByTestId('recipient-account')).toHaveText(bob);
    await shot(page, '08-send-by-address');
    await page.getByTestId('transfer-go').click();
    await expect(page.getByTestId('review')).toBeVisible();
    await shot(page, '08-review-transfer');
    await page.getByTestId('review-sign').click();
    await expect(latest(page).getByTestId('outcome')).toHaveText(/^ACCEPTED/);

    // A session with one payment made without a wallet prompt.
    await page.getByTestId('action-session').click();
    await page.getByTestId('session-per').fill('1');
    await page.getByTestId('session-total').fill('5');
    await shot(page, '09-session-authorize');
    await page.getByTestId('session-go').click();
    await page.getByTestId('review-sign').click();
    await expect(latest(page).getByTestId('outcome')).toHaveText(/^ACCEPTED/);
    const grant = page.getByTestId('sessions').locator('[data-testid^="grant-"]', { hasText: 'key in this browser' }).first();
    await grant.getByTestId('session-pay-to').fill(bob);
    await grant.getByTestId('session-pay-amount').fill('0.75');
    await grant.getByTestId('session-pay').click();
    await expect(grant.getByTestId('grant-usage')).toHaveText(/^0\.75 \//);
    await page.getByTestId('balances').locator('[data-testid^="balance-"]').first().getByTestId('proof-verify').click();
    await expect(page.getByTestId('proof-verdict').first()).toContainText('verified');
    await shot(page, '10-dashboard', true);

    await page.getByTestId('tab-withdrawals').click();
    await expect(page.getByTestId('withdrawals').getByTestId('lifecycle').first()).toBeVisible();
    await page.getByTestId('tab-withdrawals').evaluate((tab) => {
      tab.scrollIntoView({ block: 'start' });
      window.scrollBy(0, -96);
    });
    await shot(page, '11-withdrawals');

    await page.getByTestId('action-withdraw').click();
    await page.getByTestId('withdraw-amount').fill('3');
    await shot(page, '12-withdraw');
    await page.getByTestId('modal-close').click();

    await page.getByTestId('action-pay-request').click();
    await page.getByTestId('payreq-input').fill(`${new URL(page.url()).origin}/#/pay?v=1&chain=payments-devkit-1&to=${bob}`
      + '&asset=lovelace&amount=4200000&memo=Order%20%231042');
    await expect(page.getByTestId('payreq-preview')).toBeVisible();
    await shot(page, '13-pay-request');
    await page.getByTestId('modal-close').click();

    await page.setViewportSize({ width: 390, height: 844 });
    await page.evaluate(() => window.scrollTo(0, 0));
    await shot(page, '14-phone', true);

    // Leave no live session behind for the next run.
    await page.getByTestId('tab-sessions').click();
    await grant.getByTestId('session-revoke').click();
    await page.getByTestId('review-sign').click();
    await expect(latest(page).getByTestId('outcome')).toHaveText(/^ACCEPTED/);
  });

  test('explorer: overview, block, transaction and account (no wallet)', async ({ page }) => {
    await page.goto('/#/explorer');
    const txs = page.getByTestId('latest-transactions').getByTestId('tx-row');
    await expect(txs.first()).toBeVisible();
    await expect(page.getByTestId('stream-mode').first()).toHaveText('Live');
    // Every visible row has settled on its outcome.
    await expect(page.getByTestId('latest-transactions').locator('.skeleton')).toHaveCount(0);
    await txs.first().getByTestId('proof-verify').click();
    await expect(txs.first().getByTestId('proof-verdict')).toHaveText('Verified');
    await shot(page, 'explorer-overview');

    // The block of the newest session payment (an E2E run leaves one), else the newest block.
    const pays = page.getByTestId('latest-transactions').locator('[data-testid="tx-row"][data-kind="session-pay"]');
    await ((await pays.count()) ? pays.first() : txs.first()).getByTestId('tx-position').click();
    await expect(page.getByTestId('block-facts')).toBeVisible();
    await expect(page.getByTestId('block-transactions').locator('.skeleton')).toHaveCount(0);
    await shot(page, 'explorer-block', true);

    await page.getByTestId('block-transactions').getByTestId('tx-kind').first().click();
    await expect(page.getByTestId('tx-facts')).toBeVisible();
    await page.getByTestId('record-panel').getByTestId('proof-verify').click();
    await expect(page.getByTestId('record-panel').getByTestId('proof-verdict')).toContainText('verified at height');
    await page.getByTestId('raw-body').locator('summary').click();
    await shot(page, 'explorer-tx', true);

    await page.getByTestId('tx-facts').getByTestId('detail-account').first().click();
    await expect(page.getByTestId('account-transactions').getByTestId('tx-row').first()).toBeVisible();
    await expect(page.getByTestId('account-page').locator('.skeleton')).toHaveCount(0);
    await page.getByTestId('explorer-balances').getByTestId('proof-verify').first().click();
    await expect(page.getByTestId('explorer-balances').getByTestId('proof-verdict').first()).toContainText('verified');
    await shot(page, 'explorer-account');
  });
});
