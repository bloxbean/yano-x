import { beforeEach, describe, expect, it } from 'vitest';
import { connectWallet, installedWallets } from './wallet';

describe('CIP-30 wallet boundary', () => {
  beforeEach(() => {
    window.cardano = {};
  });

  it('discovers injected wallets without lower-casing provider keys', () => {
    Object.defineProperty(window.cardano, 'LaceWallet', {
      enumerable: false,
      configurable: true,
      value: { name: 'Lace', enable: async () => ({}) }
    });
    expect(installedWallets()).toEqual([{ key: 'LaceWallet', name: 'Lace', icon: '' }]);
  });

  it('refuses a wallet on the wrong Cardano network', async () => {
    window.cardano = {
      test: {
        name: 'Test wallet',
        enable: async () => ({
          getNetworkId: async () => 1,
          getChangeAddress: async () => 'address',
          signTx: async () => 'witness'
        })
      }
    };
    await expect(connectWallet({ key: 'test', name: 'Test wallet', icon: '' }, 'preprod'))
      .rejects.toThrow('does not match preprod');
  });
});
