// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { bech32Encode } from '../../lib/bech32';
import { fromHex } from '../../lib/hex';
import { accountIdBech32 } from '../../lib/ids';
import { credentialFromAddress } from '../../lib/intents';
import { ACCOUNT, INFO } from '../../test/fixtures';

const RECIPIENT_KEY = '0e'.repeat(28);
const RECIPIENT_ADDRESS = bech32Encode('addr_test', fromHex(`60${RECIPIENT_KEY}`));
const RECIPIENT_ACCOUNT = credentialFromAddress(fromHex(`60${RECIPIENT_KEY}`)).accountId;

const api = {
  chainId: INFO.chainId,
  address: vi.fn(),
  account: vi.fn(),
};

vi.mock('../../state', () => ({
  usePayments: () => ({ api, info: INFO, account: { accountId: ACCOUNT.accountId }, flow: vi.fn(), track: vi.fn(),
    toast: vi.fn() }),
  useAccountView: () => ({ data: undefined }),
}));

const { SendFlow } = await import('./SendFlow');

afterEach(cleanup);
beforeEach(() => {
  HTMLDialogElement.prototype.showModal ??= function showModal(this: HTMLDialogElement) { this.setAttribute('open', ''); };
  api.address.mockReset().mockImplementation(async (hex: string) => ({
    accountId: credentialFromAddress(fromHex(hex)).accountId, networkMatches: true }));
  api.account.mockReset().mockImplementation(async (id: string) => ({ accountId: id, registered: id === RECIPIENT_ACCOUNT,
    owner: { credentialKind: 'PAYMENT_KEY', keyHash: RECIPIENT_KEY }, committed: { height: 7 } }));
});

function renderSend() {
  render(<QueryClientProvider client={new QueryClient()}><SendFlow onClose={() => {}} /></QueryClientProvider>);
  fireEvent.change(screen.getByTestId('transfer-amount'), { target: { value: '1' } });
}

function typeTo(text: string) {
  fireEvent.change(screen.getByTestId('transfer-to'), { target: { value: text } });
}

describe('the Send form\'s recipient', () => {
  it('resolves an address to its account before review: bech32, hex and registration', async () => {
    renderSend();
    typeTo(RECIPIENT_ADDRESS);
    await waitFor(() => expect(screen.getByTestId('recipient-status').textContent)
      .toMatch(/^address resolves to account acct_test1.+ \(current owner matches\)$/));
    expect(screen.getByTestId('recipient-address').textContent).toBe(RECIPIENT_ADDRESS);
    expect(screen.getByTestId('recipient-account').textContent).toBe(accountIdBech32(RECIPIENT_ACCOUNT, 0));
    expect(screen.getByTestId('recipient-account').getAttribute('data-hex')).toBe(RECIPIENT_ACCOUNT);
    expect(screen.getByTestId('recipient-account-hex').textContent).toBe(RECIPIENT_ACCOUNT);
    expect(screen.getByTestId('transfer-go').hasAttribute('disabled')).toBe(false);
  });

  it('accepts the account id itself, as bech32 or hex', async () => {
    renderSend();
    typeTo(RECIPIENT_ACCOUNT);
    await waitFor(() => expect(screen.getByTestId('recipient-status').textContent).toMatch(/· registered$/));
    expect(api.address).not.toHaveBeenCalled();
  });

  it('refuses an unregistered account, another network and a bad checksum, and keeps Review disabled', async () => {
    renderSend();
    typeTo(accountIdBech32('ab'.repeat(28), 0));
    await waitFor(() => expect(screen.getByTestId('recipient-problem').textContent).toMatch(/not registered/));
    expect(screen.getByTestId('transfer-go').hasAttribute('disabled')).toBe(true);
    typeTo(accountIdBech32(RECIPIENT_ACCOUNT, 1));
    expect(screen.getByTestId('recipient-problem').textContent).toBe('This is a mainnet account id; this ledger is on testnet.');
    typeTo(bech32Encode('addr', fromHex(`61${RECIPIENT_KEY}`)));
    expect(screen.getByTestId('recipient-problem').textContent).toBe('This is a mainnet address; this ledger is on testnet.');
    typeTo(`${RECIPIENT_ADDRESS.slice(0, -1)}${RECIPIENT_ADDRESS.endsWith('q') ? 'p' : 'q'}`);
    expect(screen.getByTestId('recipient-problem').textContent).toMatch(/bad checksum/);
    expect(screen.getByTestId('transfer-go').hasAttribute('disabled')).toBe(true);
  });
});
