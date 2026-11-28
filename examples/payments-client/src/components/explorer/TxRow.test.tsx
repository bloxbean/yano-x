// @vitest-environment jsdom
import { cleanup, render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { contextOf, decodeMessage, depositOutcome, receiptOutcome } from '../../lib/explorer';
import {
  DEPOSIT_ID, DEPOSIT_MESSAGE, DEPOSIT_RECORD, LIVE_ACCOUNT, WITHDRAW_INTENT_ID, WITHDRAW_MESSAGE, WITHDRAW_RECEIPT
} from '../../test/explorer-fixtures';
import { ADA } from '../../test/fixtures';
import { TxRow } from './TxRow';

afterEach(cleanup);

const CTX = contextOf({ intentTopic: 'asset-payments.intents', depositObserverId: 'deposits' });
const VAULT = { ...ADA, vaultId: '05599f61be9ad060edb28e3064d144a319a1b28f03191d58ef3a8620e0a50166' };
const NOW = 1_795_878_203_197 + 65_000;

function renderRow(ui: React.ReactElement) {
  render(<ul>{ui}</ul>);
  return screen.getByTestId('tx-row');
}

describe('an explorer transaction row', () => {
  it('shows a withdrawal: kind, parties, amount, outcome, position and age, with links built from checked ids', () => {
    const tx = decodeMessage(WITHDRAW_MESSAGE, CTX);
    const row = renderRow(<TxRow tx={tx} vaults={[VAULT]} now={NOW} timestamp={1_795_878_203_197}
      outcome={receiptOutcome(WITHDRAW_INTENT_ID, 10, WITHDRAW_RECEIPT)} verify={<button type="button">Verify</button>} />);
    expect(within(row).getByTestId('tx-kind').textContent).toBe('Withdraw');
    expect(within(row).getByTestId('tx-kind').getAttribute('href')).toBe(`#/explorer/tx/${WITHDRAW_MESSAGE.messageId}`);
    expect(within(row).getByTestId('tx-position').textContent).toBe('#10·0');
    expect(within(row).getByTestId('tx-position').getAttribute('href')).toBe('#/explorer/block/10');
    expect(row.textContent).toContain('1m ago');
    expect(within(row).getByTestId('party-account').getAttribute('href'))
      .toBe('#/explorer/account/acct_test1h8srr6086wultxhxturgqcyglxn3m4nxymq3dw826pewvzu7whj');
    expect(within(row).getByTestId('party-account').getAttribute('data-hex')).toBe(LIVE_ACCOUNT);
    expect(within(row).getByTestId('party-account').textContent).toBe('acct_test1h8srr6…zu7whj');
    expect(within(row).getByTestId('tx-parties').textContent).toMatch(/addr_test1/);
    expect(within(row).getByTestId('tx-amount').textContent).toBe('1000ADA');
    expect(within(row).getByTestId('outcome').textContent).toBe('Accepted');
    expect(within(row).getByText('Verify')).toBeTruthy();
  });

  it('shows a rejection with its code and readable reason', () => {
    const tx = decodeMessage(WITHDRAW_MESSAGE, CTX);
    const row = renderRow(<TxRow tx={tx} vaults={[VAULT]} now={NOW}
      outcome={{ state: 'rejected', code: 'INSUFFICIENT_BALANCE', text: 'The available balance is too low for this amount.' }} />);
    expect(within(row).getByTestId('outcome').textContent).toBe('Rejected');
    expect(within(row).getByTestId('outcome-code').textContent).toBe('INSUFFICIENT_BALANCE');
    expect(within(row).getByTestId('outcome-reason').textContent).toMatch(/balance is too low/);
  });

  it('links a deposit\'s L1 transaction through the explorer template', () => {
    const tx = decodeMessage(DEPOSIT_MESSAGE, CTX);
    const row = renderRow(<TxRow tx={tx} vaults={[VAULT]} now={NOW} explorerTxUrl="https://scan.example/tx/{tx}"
      outcome={depositOutcome(DEPOSIT_ID, 1, DEPOSIT_RECORD)} />);
    expect(row.getAttribute('data-kind')).toBe('deposit');
    expect(within(row).getByTestId('tx-amount').textContent).toBe('25ADA');
    expect(within(row).getByTestId('outcome').textContent).toBe('Uncredited');
    expect(within(row).getByTestId('l1-link').getAttribute('href'))
      .toBe('https://scan.example/tx/3f9db60cb6b5b4d9c0f9d0f45956844d1b3ba30e5718ecee84056ec92e200d05');
  });

  it('renders an undecodable message as an unknown message, as text', () => {
    const tx = decodeMessage({ ...WITHDRAW_MESSAGE, topic: '<img src=x onerror=alert(1)>', bodyHex: 'ff', size: 1 }, CTX);
    const row = renderRow(<TxRow tx={tx} vaults={[]} now={NOW} />);
    expect(within(row).getByTestId('tx-kind').textContent).toBe('Unknown message');
    expect(within(row).getByTestId('tx-note').textContent).toBe('<img src=x onerror=alert(1)> · 1 bytes · not a topic this ledger reads');
    expect(row.querySelector('img')).toBeNull();
    expect(within(row).getByTestId('outcome').textContent).toBe('—');
  });
});
