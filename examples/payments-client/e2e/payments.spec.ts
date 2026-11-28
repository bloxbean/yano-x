/**
 * The reference client end to end with Yaci DevKit's built-in CIP-30 wallet (pre-funded devnet accounts): register,
 * deposit (signTx), transfer (signData), a payment request, session authorize / pay without a wallet prompt / limit
 * refusal / revoke, withdraw, verified balance and receipt proofs, and the unsupported-wallet message. Needs DevKit and
 * e2e/devkit-node.sh running, with the node caught up with DevKit's L1 tip.
 */
import { expect, test, type Locator, type Page } from '@playwright/test';

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
  await page.getByTestId('connect-wallet').click();
  await page.getByTestId('wallet-yacidevkit').click();
  await expect(page.getByTestId('account-id')).toHaveText(/^[0-9a-f]{56}$/);
  return (await page.getByTestId('account-id').textContent())!;
}

/** The newest entry in the activity feed. */
function latest(page: Page): Locator {
  return page.getByTestId('activity').locator('[data-testid^="receipt-"]').first();
}

/** Approves the in-app review and waits for this intent's receipt. */
async function signAndWait(page: Page, outcome: RegExp): Promise<void> {
  await page.getByTestId('review-sign').click();
  await expect(latest(page).getByTestId('outcome')).toHaveText(outcome);
}

function firstBalance(page: Page): Locator {
  return page.getByTestId('balances').locator('[data-testid^="balance-"]').first();
}

async function available(page: Page): Promise<string> {
  await page.getByTestId('refresh').click();
  return (await firstBalance(page).getByTestId('available').textContent())!;
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
  let request = '';

  test.beforeAll(async () => {
    test.skip(!(await reachable(`${DEVKIT}/api/v1/wallet/health`)), 'Yaci DevKit wallet is not running');
    test.skip(!(await reachable(`${NODE}/api/v1/app-chain/chains`)), 'the asset-payments node is not running');
  });

  test('a second DevKit account registers and makes a payment request', async ({ page }) => {
    await activeAccount('1');
    await page.goto(PAGE);
    bob = await connect(page);
    await register(page);

    // A merchant-style request for 1 ADA: the link names the chain, the account, the asset id and base units.
    await page.getByTestId('action-pay-request').click();
    await page.getByTestId('payreq-tab-request').click();
    await page.getByTestId('payreq-amount').fill('1');
    await page.getByTestId('payreq-memo').fill('Coffee');
    await expect(page.getByTestId('payreq-link')).toContainText(`to=${bob}`);
    request = (await page.getByTestId('payreq-link').textContent())!;
    expect(request).toContain('asset=lovelace&amount=1000000');
  });

  test('register, deposit, transfer, pay request, session, revoke, withdraw and proofs', async ({ page }) => {
    await activeAccount('0');
    await page.goto(PAGE);
    await connect(page);
    await register(page);

    // Deposit 25 ADA from L1: built by the domain API, decoded and checked here, signed with signTx.
    const before = Number(await available(page));
    await page.getByTestId('action-deposit').click();
    await page.getByTestId('deposit-amount').fill('25');
    await page.getByTestId('deposit-go').click();
    await expect(page.getByTestId('review-fields')).toContainText('25000000');
    await page.getByTestId('review-sign').click();
    await expect(page.getByTestId('deposit-state')).toHaveText(/^CREDITED 25 ADA/, { timeout: 180_000 });
    await page.getByTestId('modal-close').click();
    await expect.poll(async () => Number(await available(page))).toBe(before + 25);

    // An owner-signed transfer; the review shows base units and the full asset id.
    await page.getByTestId('action-send').click();
    await page.getByTestId('transfer-to').fill(bob);
    await page.getByTestId('transfer-amount').fill('3');
    await page.getByTestId('transfer-go').click();
    await expect(page.getByTestId('review-fields')).toContainText('3000000');
    await expect(page.getByTestId('review-fields')).toContainText('lovelace (ADA)');
    await signAndWait(page, /^ACCEPTED/);
    await expect.poll(async () => Number(await available(page))).toBe(before + 22);

    // Pay Bob's request: a preview with the unverified memo, then the ordinary transfer review.
    await page.getByTestId('action-pay-request').click();
    await page.getByTestId('payreq-input').fill(request);
    await expect(page.getByTestId('payreq-preview')).toContainText('1 ADA');
    await expect(page.getByTestId('payreq-preview')).toContainText('unverified');
    await page.getByTestId('payreq-pay').click();
    await expect(page.getByTestId('review-fields')).toContainText('Memo (from the request, unverified)');
    await signAndWait(page, /^ACCEPTED/);
    await expect.poll(async () => Number(await available(page))).toBe(before + 21);

    // A session: 1 ADA per payment, 3 in total; one payment without a wallet prompt, then one over the limit.
    await page.getByTestId('action-session').click();
    await page.getByTestId('session-per').fill('1');
    await page.getByTestId('session-total').fill('3');
    await page.getByTestId('session-go').click();
    await signAndWait(page, /^ACCEPTED/);
    // The new grant is the one whose key is in this browser (an earlier run may have left others).
    const grant = page.getByTestId('sessions').locator('[data-testid^="grant-"]', { hasText: 'key in this browser' }).first();
    await expect(grant).toBeVisible();
    const grantId = (await grant.getAttribute('data-testid'))!;
    await grant.getByTestId('session-pay-to').fill(bob);
    await grant.getByTestId('session-pay-amount').fill('0.5');
    await grant.getByTestId('session-pay').click();
    await expect(page.getByTestId('review')).not.toBeVisible();
    await expect(latest(page).getByTestId('outcome')).toHaveText(/^ACCEPTED/);
    await expect(grant.getByTestId('grant-usage')).toHaveText(/^0\.5 \/ 3/);
    await grant.getByTestId('session-pay-amount').fill('1.5');
    await grant.getByTestId('session-pay').click();
    await expect(latest(page).getByTestId('outcome')).toHaveText('REJECTED SESSION_LIMIT');
    await expect(latest(page).getByTestId('outcome-detail')).toContainText('limit');
    await grant.getByTestId('session-revoke').click();
    await signAndWait(page, /^ACCEPTED/);
    await expect(page.getByTestId(grantId)).toHaveCount(0);

    // Withdraw 5 ADA: reserved on the ledger, then RESERVED until a settlement pays it.
    await page.getByTestId('action-withdraw').click();
    await page.getByTestId('withdraw-amount').fill('5');
    await page.getByTestId('withdraw-go').click();
    await signAndWait(page, /^ACCEPTED/);
    await page.getByTestId('tab-withdrawals').click();
    await expect(page.getByTestId('withdrawals').getByTestId('lifecycle').first()).toHaveText(/RESERVED|SETTLING|PAID/);
    await page.getByTestId('refresh').click();
    await expect(firstBalance(page).getByTestId('reserved')).toHaveText(/^[1-9]/);

    // The balance proof at the view's committed height verifies and binds the shown value; so does a receipt's.
    await firstBalance(page).getByTestId('proof-verify').click();
    await expect(firstBalance(page).getByTestId('proof-verdict')).toContainText('verified at height');
    await latest(page).getByTestId('proof-verify').click();
    await expect(latest(page).getByTestId('proof-verdict')).toContainText('verified at height');
  });

  test('a wallet without signData is refused with a clear message', async ({ page }) => {
    await page.addInitScript(() => {
      (window as unknown as { cardano: Record<string, unknown> }).cardano = {
        nosign: { name: 'No signData', enable: async () => ({ getNetworkId: async () => 0 }) }
      };
    });
    await page.goto(PAGE);
    await page.getByTestId('connect-wallet').click();
    await page.getByTestId('wallet-nosign').click();
    await expect(page.getByTestId('wallet-error')).toHaveText(/^Unsupported wallet: .*signData/);
  });
});
