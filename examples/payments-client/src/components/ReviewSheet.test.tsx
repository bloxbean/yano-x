// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { depositReviewFields, intentReviewFields, transferFields } from '../flows';
import { bech32Encode } from '../lib/bech32';
import { fromHex } from '../lib/hex';
import { accountIdBech32, parseRecipient } from '../lib/ids';
import { ACCOUNT, INFO, USDM } from '../test/fixtures';
import { ReviewSheet } from './ReviewSheet';

afterEach(cleanup);

function row(label: string): string {
  const fields = screen.getByTestId('review-fields');
  const term = within(fields).getByText(label, { selector: 'dt' });
  return term.nextElementSibling!.textContent!;
}

describe('the review sheet', () => {
  it('shows a transfer\'s decoded values in full: base units, AssetId, chain and genesis', () => {
    const to = 'cd'.repeat(28);
    const resolved = { recipient: parseRecipient(accountIdBech32(to, 0), 0), accountId: to, registered: true, height: 7 };
    const fields = intentReviewFields(transferFields(USDM, 12_345_678n, resolved, 0), ACCOUNT.accountId, INFO.intentDomain,
      Date.now() + 60_000, 'ee'.repeat(32));
    const approve = vi.fn();
    const cancel = vi.fn();
    render(<ReviewSheet request={{ kind: 'intent', title: 'Send', fields, bytesHex: 'a1b2c3' }}
      onApprove={approve} onCancel={cancel} />);
    expect(row('Send')).toBe('12.345678 USDM');
    expect(row('Amount (base units)')).toBe('12345678');
    expect(row('Asset id')).toBe(`${'f0'.repeat(28)}.5553444d`);
    expect(row('To account')).toBe('acct_test1ehxumnwdehxumnwdehxumnwdehxumnwdehxumnwdehxu6zx4ljj');
    expect(row('To account (hex)')).toBe(to);
    expect(row('Recipient check')).toBe('registered at height 7');
    expect(screen.queryByText('To address (as entered)')).toBeNull();
    expect(row('Account')).toBe('acct_test1vcrt0df7w82f374q78707ggk70cs63djxtrmt57cy92g7suhzfu');
    expect(row('Account (hex)')).toBe(ACCOUNT.accountId);
    expect(row('Chain (intent domain)')).toBe('payments-devnet-1, application asset-payments');
    expect(row('Genesis id')).toBe('6c'.repeat(32));
    expect(row('Network')).toBe('magic 42, address network id 0');
    expect(row('Intent id')).toBe('ee'.repeat(32));
    expect(screen.getByTestId('review-bytes').textContent).toBe('a1b2c3');
    expect(screen.getByText(/as hex, not as a Cardano transaction/)).toBeTruthy();
    fireEvent.click(screen.getByTestId('review-sign'));
    expect(approve).toHaveBeenCalledOnce();
    fireEvent.click(screen.getByTestId('review-cancel'));
    expect(cancel).toHaveBeenCalledOnce();
  });

  it('shows a deposit as decoded from the transaction', () => {
    const change = fromHex(ACCOUNT.addressHex);
    const fields = depositReviewFields(USDM, { transactionId: '99'.repeat(32), inputs: ['a#0', 'b#1'], fee: 180_000n,
      ttl: 12_200n, outputIndex: 0, outputLovelace: 1_500_000n, quantity: 25_000_000n, change: 40_000_000n },
    ACCOUNT.accountId, change, 0);
    render(<ReviewSheet request={{ kind: 'deposit', title: 'L1 deposit', fields, bytesHex: '84a4' }}
      onApprove={() => {}} onCancel={() => {}} />);
    expect(row('Deposit')).toBe('25 USDM');
    expect(row('Amount (base units)')).toBe('25000000');
    expect(row('Asset id')).toBe(`${'f0'.repeat(28)}.5553444d`);
    expect(row('Lovelace in the vault output')).toBe('1.5 ADA');
    expect(row('Credited to (datum)')).toBe('acct_test1vcrt0df7w82f374q78707ggk70cs63djxtrmt57cy92g7suhzfu');
    expect(row('Credited to (hex)')).toBe(ACCOUNT.accountId);
    expect(row('Inputs')).toBe('2, all from this wallet');
    expect(row('Fee')).toBe('0.18 ADA');
    expect(row('Transaction id')).toBe('99'.repeat(32));
    expect(screen.getByTestId('review-sign').textContent).toMatch(/signTx/);
  });

  it('shows a payment to an address with both the address and the account it pays, as bech32 and hex', () => {
    const address = bech32Encode('addr_test', fromHex(ACCOUNT.addressHex));
    const resolved = { recipient: parseRecipient(address, 0), accountId: ACCOUNT.accountId, registered: true,
      ownerMatches: true, height: 9 };
    render(<ReviewSheet request={{ kind: 'intent', title: 'Send', fields: transferFields(USDM, 1n, resolved, 0),
      bytesHex: 'a1' }} onApprove={() => {}} onCancel={() => {}} />);
    expect(row('To address (as entered)')).toBe(address);
    expect(row('To account')).toBe('acct_test1vcrt0df7w82f374q78707ggk70cs63djxtrmt57cy92g7suhzfu');
    expect(row('To account (hex)')).toBe(ACCOUNT.accountId);
    expect(row('Recipient check')).toBe('registered at height 9; the address\'s key is the account\'s current owner');
  });
});
