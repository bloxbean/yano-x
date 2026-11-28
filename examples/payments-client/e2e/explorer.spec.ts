/**
 * The public explorer end to end, without a wallet: the overview's blocks and transactions, a block page, a
 * transaction page with its receipt proof verified, an account page, and search by height, account id and address.
 * Needs e2e/devkit-node.sh running with some activity on the chain (run payments.spec.ts first on a fresh chain).
 */
import { expect, test } from '@playwright/test';

const NODE = process.env.PAYMENTS_NODE_URL ?? 'http://localhost:7470';
const DEVKIT = process.env.DEVKIT_WALLET_URL ?? 'http://localhost:10000';

async function reachable(url: string): Promise<boolean> {
  try {
    return (await fetch(url, { signal: AbortSignal.timeout(3_000) })).ok;
  } catch {
    return false;
  }
}

/** A DevKit account's address, when the DevKit wallet runs (for the address search). */
async function devkitAddress(): Promise<string | null> {
  try {
    const response = await fetch(`${DEVKIT}/api/v1/wallet/accounts`, { signal: AbortSignal.timeout(3_000) });
    const body = (await response.json()) as { accounts: { address: string }[] };
    return body.accounts[0]?.address ?? null;
  } catch {
    return null;
  }
}

test.describe.serial('the public explorer', () => {
  test.beforeAll(async () => {
    test.skip(!(await reachable(`${NODE}/api/v1/app-chain/chains`)), 'the asset-payments node is not running');
  });

  test('overview, block, transaction and account pages without a wallet', async ({ page }) => {
    await page.goto('/');
    await page.getByTestId('nav-explorer').click();
    await expect(page).toHaveURL(/#\/explorer$/);
    // No wallet is connected or asked for.
    await expect(page.getByTestId('connect-wallet')).toBeVisible();
    await expect(page.getByTestId('as-reported')).toContainText('as reported by the node');

    await expect(page.getByTestId('tip-height')).toHaveText(/^\d+$/);
    const blocks = page.getByTestId('latest-blocks').getByTestId('block-row');
    await expect(blocks.first()).toBeVisible();
    const txs = page.getByTestId('latest-transactions').getByTestId('tx-row');
    await expect(txs.first()).toBeVisible();
    await expect(page.getByTestId('stream-mode').first()).toHaveText(/Live|Polling|Connecting/);
    // Every intent row settles on an outcome read from its receipt.
    const intent = txs.filter({ has: page.locator('[data-testid="outcome"]', { hasText: /Accepted|Rejected/ }) }).first();
    await expect(intent).toBeVisible();

    // A block: its header and its decoded messages.
    const height = (await blocks.first().getByTestId('block-link').textContent())!.replace('#', '');
    await blocks.first().getByTestId('block-link').click();
    await expect(page).toHaveURL(new RegExp(`#/explorer/block/${height}$`));
    await expect(page.getByTestId('block-facts')).toContainText('State root');
    const blockTxs = page.getByTestId('block-transactions').getByTestId('tx-row');
    await expect(blockTxs.first()).toBeVisible();

    // A transaction: decoded fields, raw body, and its record's proof verified at its height.
    await page.goBack();
    const accepted = txs.filter({ has: page.locator('[data-testid="outcome"]', { hasText: 'Accepted' }) }).first();
    await accepted.getByTestId('tx-kind').click();
    await expect(page).toHaveURL(/#\/explorer\/tx\/[0-9a-f]{64}$/);
    await expect(page.getByTestId('tx-facts')).toContainText('Intent id');
    await page.getByTestId('raw-body').locator('summary').click();
    await expect(page.getByTestId('raw-body').locator('pre')).toHaveText(/^[0-9a-f ]+$/);
    await expect(page.getByTestId('record-panel').getByTestId('outcome')).toHaveText('Accepted');
    await page.getByTestId('record-panel').getByTestId('proof-verify').click();
    await expect(page.getByTestId('record-panel').getByTestId('proof-verdict')).toContainText('verified at height');

    // The signing account: balances with proofs, its transactions from the block scan.
    await page.getByTestId('tx-facts').getByTestId('detail-account').first().click();
    await expect(page).toHaveURL(/#\/explorer\/account\/[0-9a-f]{56}$/);
    await expect(page.getByTestId('explorer-registration')).toHaveText('Registered');
    await expect(page.getByTestId('explorer-balances').locator('[data-testid^="balance-"]').first()).toBeVisible();
    await expect(page.getByTestId('account-transactions').getByTestId('tx-row').first()).toBeVisible();
    await expect(page.getByTestId('scan-range')).toContainText('scanned');
  });

  test('search by height, account id and address', async ({ page }) => {
    await page.goto('/#/explorer');
    await expect(page.getByTestId('tip-height')).toHaveText(/^\d+$/);

    await page.getByTestId('explorer-search').fill('1');
    await page.getByTestId('explorer-search-go').click();
    await expect(page).toHaveURL(/#\/explorer\/block\/1$/);
    await expect(page.getByTestId('block-height')).toHaveText('#1');

    // An account id from a transaction row.
    await page.goto('/#/explorer');
    const account = page.getByTestId('latest-transactions').getByTestId('party-account').first();
    const href = (await account.getAttribute('href'))!;
    const accountId = href.split('/').pop()!;
    await page.getByTestId('explorer-search').fill(accountId);
    await page.getByTestId('explorer-search-go').click();
    await expect(page.getByTestId('explorer-account-id')).toHaveText(accountId);

    await page.getByTestId('explorer-search').fill('not-a-thing');
    await page.getByTestId('explorer-search-go').click();
    await expect(page.getByTestId('search-error')).toBeVisible();

    const address = await devkitAddress();
    test.skip(!address, 'the DevKit wallet is not running (address search)');
    await page.getByTestId('explorer-search').fill(address!);
    await page.getByTestId('explorer-search-go').click();
    await expect(page).toHaveURL(/#\/explorer\/account\/[0-9a-f]{56}$/);
    await expect(page.getByTestId('explorer-account-id')).toHaveText(/^[0-9a-f]{56}$/);
  });
});
