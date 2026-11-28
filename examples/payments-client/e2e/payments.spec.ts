/**
 * The reference client end to end with Yaci DevKit's built-in CIP-30 wallet (pre-funded devnet accounts): register,
 * deposit (signTx), transfer (signData), session authorize / pay without a wallet prompt / limit refusal / revoke,
 * withdraw, a verified balance proof, and the unsupported-wallet message. Needs DevKit and e2e/devkit-node.sh running.
 */
import { expect, test, type Page } from '@playwright/test';

const DEVKIT = process.env.DEVKIT_WALLET_URL ?? 'http://localhost:10000';
const NODE = process.env.PAYMENTS_NODE_URL ?? 'http://localhost:7470';
const PAGE = `/?devkit=${encodeURIComponent(`${DEVKIT}/wallet-sdk.js`)}`;

async function reachable(url: string): Promise<boolean> {
  try {
    return (await fetch(url, { signal: AbortSignal.timeout(3_000) })).ok;
  } catch {
    return false;
  }
}

async function activeAccount(id: string): Promise<void> {
  const response = await fetch(`${DEVKIT}/api/v1/wallet/active-account`, {
    method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ accountId: id })
  });
  expect(response.ok).toBeTruthy();
}

async function connect(page: Page): Promise<string> {
  await page.getByTestId('wallet-yacidevkit').click();
  await expect(page.getByTestId('account-id')).toHaveText(/^[0-9a-f]{56}$/);
  return (await page.getByTestId('account-id').textContent())!;
}

/** Approves the in-app review and waits for this intent's receipt. */
async function signAndWait(page: Page, outcome: RegExp): Promise<void> {
  await page.getByTestId('review-sign').click();
  await expect(page.getByTestId('receipts').locator('tr').nth(1).getByTestId('outcome')).toHaveText(outcome);
}

async function available(page: Page): Promise<string> {
  await page.getByTestId('refresh').click();
  return (await page.getByTestId('balances').locator('tr').nth(1).getByTestId('available').textContent())!;
}

async function register(page: Page): Promise<void> {
  const registration = page.getByTestId('registration');
  await expect(registration).toBeVisible();
  if ((await registration.textContent())!.startsWith('Registered')) return;
  await page.getByTestId('register').click();
  await signAndWait(page, /^ACCEPTED/);
  await expect(registration).toHaveText(/^Registered/);
}

test.describe.serial('asset-payments reference client on a DevKit devnet', () => {
  let bob = '';

  test.beforeAll(async () => {
    test.skip(!(await reachable(`${DEVKIT}/api/v1/wallet/health`)), 'Yaci DevKit wallet is not running');
    test.skip(!(await reachable(`${NODE}/api/v1/app-chain/chains`)), 'the asset-payments node is not running');
  });

  test('a second DevKit account registers so it can receive', async ({ page }) => {
    await activeAccount('1');
    await page.goto(PAGE);
    bob = await connect(page);
    await register(page);
  });

  test('register, deposit, transfer, session, revoke, withdraw and proofs', async ({ page }) => {
    await activeAccount('0');
    await page.goto(PAGE);
    await connect(page);
    await register(page);

    // Deposit 25 ADA from L1: built by the domain API, signed with signTx, submitted by the wallet.
    const before = Number(await available(page));
    await page.getByTestId('deposit-amount').fill('25');
    await page.getByTestId('deposit-go').click();
    await page.getByTestId('review-sign').click();
    await expect(page.getByTestId('deposit-state')).toHaveText(/^CREDITED 25 ADA/, { timeout: 180_000 });
    await expect.poll(async () => Number(await available(page))).toBe(before + 25);

    // An owner-signed transfer.
    await page.getByTestId('transfer-to').fill(bob);
    await page.getByTestId('transfer-amount').fill('3');
    await page.getByTestId('transfer-go').click();
    await signAndWait(page, /^ACCEPTED/);
    await expect.poll(async () => Number(await available(page))).toBe(before + 22);

    // A session: 1 ADA per payment, 3 in total; one payment without a wallet prompt, then one over the limit.
    await page.getByTestId('session-per').fill('1');
    await page.getByTestId('session-total').fill('3');
    await page.getByTestId('session-go').click();
    await signAndWait(page, /^ACCEPTED/);
    const grant = page.getByTestId('sessions').locator('tr[data-testid^="grant-"]').first();
    await expect(grant).toBeVisible();
    await grant.getByTestId('session-pay-to').fill(bob);
    await grant.getByTestId('session-pay-amount').fill('0.5');
    await grant.getByTestId('session-pay').click();
    await expect(page.getByTestId('review')).not.toBeVisible();
    await expect(page.getByTestId('receipts').locator('tr').nth(1).getByTestId('outcome')).toHaveText(/^ACCEPTED/);
    await expect(page.getByTestId('sessions').getByTestId('grant-usage').first()).toHaveText(/^0\.5 \/ 3/);
    const grantAgain = page.getByTestId('sessions').locator('tr[data-testid^="grant-"]').first();
    await grantAgain.getByTestId('session-pay-to').fill(bob);
    await grantAgain.getByTestId('session-pay-amount').fill('1.5');
    await grantAgain.getByTestId('session-pay').click();
    await expect(page.getByTestId('receipts').locator('tr').nth(1).getByTestId('outcome'))
      .toHaveText('REJECTED SESSION_LIMIT');
    await page.getByTestId('sessions').getByTestId('session-revoke').first().click();
    await signAndWait(page, /^ACCEPTED/);
    await expect(page.getByTestId('sessions').locator('tr[data-testid^="grant-"]')).toHaveCount(0);

    // Withdraw 5 ADA: reserved on the ledger, then RESERVED until a settlement pays it.
    await page.getByTestId('withdraw-amount').fill('5');
    await page.getByTestId('withdraw-go').click();
    await signAndWait(page, /^ACCEPTED/);
    await expect(page.getByTestId('withdrawals').getByTestId('lifecycle').first()).toHaveText(/RESERVED|SETTLING|PAID/);
    await page.getByTestId('refresh').click();
    await expect(page.getByTestId('balances').locator('tr').nth(1).getByTestId('reserved')).toHaveText(/^[1-9]/);

    // The balance proof at the view's committed height verifies and binds the shown value.
    await page.getByTestId('balances').locator('tr').nth(1).getByRole('button', { name: 'Verify' }).click();
    await expect(page.getByTestId('balances').locator('tr').nth(1)).toContainText('verified at height');
  });

  test('a wallet without signData is refused with a clear message', async ({ page }) => {
    await page.addInitScript(() => {
      (window as unknown as { cardano: Record<string, unknown> }).cardano = {
        nosign: { name: 'No signData', enable: async () => ({ getNetworkId: async () => 0 }) }
      };
    });
    await page.goto(PAGE);
    await page.getByTestId('wallet-nosign').click();
    await expect(page.getByTestId('wallet-error')).toHaveText(/^Unsupported wallet: .*signData/);
  });
});
