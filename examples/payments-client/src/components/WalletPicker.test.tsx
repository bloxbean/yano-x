// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { connectWallet, installedWallets } from '../lib/wallet';
import { WalletPicker } from './WalletPicker';

afterEach(() => {
  cleanup();
  delete (window as { cardano?: unknown }).cardano;
});

function inject(wallets: Record<string, unknown>): void {
  (window as unknown as { cardano: Record<string, unknown> }).cardano = wallets;
}

describe('the wallet picker', () => {
  it('lists the injected CIP-30 wallets', () => {
    inject({ yano: { name: 'Yano Wallet', enable: async () => ({}) }, eternl: { name: 'Eternl', enable: async () => ({}) } });
    render(<WalletPicker wallets={installedWallets()} onConnect={async () => {}} onClose={() => {}} />);
    expect(screen.getByTestId('wallet-yano').textContent).toMatch(/Yano Wallet/);
    expect(screen.getByTestId('wallet-eternl').textContent).toMatch(/Eternl/);
  });

  it('refuses a wallet without signData with a clear message', async () => {
    inject({ nosign: { name: 'No signData', enable: async () => ({ getNetworkId: async () => 0 }) } });
    const close = vi.fn();
    render(<WalletPicker wallets={installedWallets()} onClose={close}
      onConnect={async (descriptor, stake) => { await connectWallet(descriptor, 0, stake); }} />);
    fireEvent.click(screen.getByTestId('wallet-nosign'));
    const error = await screen.findByTestId('wallet-error');
    expect(error.textContent).toMatch(/^Unsupported wallet: .*signData/);
    expect(error.getAttribute('role')).toBe('alert');
    expect(close).not.toHaveBeenCalled();
  });

  it('refuses a wallet on another network', async () => {
    inject({ other: { name: 'Mainnet wallet', enable: async () => ({ signData: async () => ({}), getNetworkId: async () => 1 }) } });
    render(<WalletPicker wallets={installedWallets()} onClose={() => {}}
      onConnect={async (descriptor) => { await connectWallet(descriptor, 0); }} />);
    fireEvent.click(screen.getByTestId('wallet-other'));
    expect((await screen.findByTestId('wallet-error')).textContent).toMatch(/network id 1.*network id 0/);
  });

  it('explains how to get a wallet when none is injected', () => {
    render(<WalletPicker wallets={[]} onConnect={async () => {}} onClose={() => {}} />);
    expect(screen.getByTestId('wallet-list').textContent).toMatch(/No CIP-30 wallet found/);
  });
});
