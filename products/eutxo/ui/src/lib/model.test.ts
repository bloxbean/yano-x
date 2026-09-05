import { describe, expect, it } from 'vitest';
import {
  adaToLovelace,
  canonicalIdentifier,
  canonicalOutpoint,
  formatLovelace,
  isEutxoChain,
  withLiveStatus,
  validateDeposit
} from './model';

describe('EUTxO product model', () => {
  it('discovers eligibility from the capability manifest', () => {
    expect(isEutxoChain({
      capabilityManifest: {
        schemaVersion: 1,
        applicationId: 'payments',
        applicationVersion: '1',
        manifestDigest: 'aa',
        components: [{ id: 'eutxo-ledger', version: '1' }]
      }
    })).toBe(true);
    expect(isEutxoChain({ stateMachine: 'ordered-log' })).toBe(false);
  });

  it('keeps ADA conversion exact to six decimal places', () => {
    expect(adaToLovelace('12.345678')).toBe(12_345_678);
    expect(adaToLovelace('0.000001')).toBe(1);
    expect(adaToLovelace('1.0000001')).toBeNull();
    expect(formatLovelace('12345678')).toBe('12.345678 ADA');
  });

  it('merges refreshed chain height and root into the displayed summary', () => {
    const discovered = {
      summary: { chainId: 'payments', tipHeight: 0, stateRoot: '00'.repeat(32) },
      status: { chainId: 'payments', running: true, tipHeight: 0 },
      bridge: null,
      bridgeState: 'disabled' as const
    };
    const refreshed = withLiveStatus(discovered, {
      chainId: 'payments', running: true, tipHeight: 7, stateRoot: 'ab'.repeat(32)
    });

    expect(refreshed.summary.tipHeight).toBe(7);
    expect(refreshed.summary.stateRoot).toBe('ab'.repeat(32));
    expect(refreshed.status.tipHeight).toBe(7);
    expect(() => withLiveStatus(discovered, { chainId: 'another-chain' }))
      .toThrow('does not match');
  });

  it('validates identifiers, outpoints, and bridge limits', () => {
    const id = 'ab'.repeat(32);
    expect(canonicalIdentifier(id)).toBe(id);
    expect(canonicalOutpoint(`${id}#17`)).toEqual({ transactionId: id, outputIndex: 17 });
    const bridge = {
      chainId: 'payments', vaultAddress: 'vault', vaultScriptHash: 'hash',
      withdrawalAddress: 'withdrawal', bridgeEpoch: 1, maxDepositLovelace: 10_000_000,
      withdrawalsPaused: false, stabilityDepth: 10
    };
    expect(validateDeposit(999_999, bridge)).toContain('at least 1 ADA');
    expect(validateDeposit(10_000_001, bridge)).toContain('caps deposits');
    expect(validateDeposit(5_000_000, bridge)).toBeNull();
  });
});
