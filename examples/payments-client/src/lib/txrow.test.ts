import { describe, expect, it } from 'vitest';
import { ADA, USDM } from '../test/fixtures';
import type { DecodedTx } from './explorer';
import { accountIdBech32, shortId } from './ids';
import { ago, rowView } from './txrow';

const base: DecodedTx = {
  key: '4:0', messageId: 'ab'.repeat(32), height: 4, index: 0, topic: 'asset-payments.intents', sender: '01',
  senderSeq: '1', size: 300, bodyHex: '', kind: 'unknown'
};
const from = 'aa'.repeat(28);
const to = 'bb'.repeat(28);

describe('explorer row mapping', () => {
  it('shows a transfer in the registry\'s display units', () => {
    const view = rowView({ ...base, kind: 'transfer', from, to, asset: { policyId: USDM.asset.policyId,
      assetName: USDM.asset.assetName }, amount: 12_345_678n }, [ADA, USDM]);
    expect(view).toMatchObject({ title: 'Transfer', amount: '12.345678', ticker: 'USDM' });
    const bech32 = accountIdBech32(from, 0);
    expect(view.from).toEqual({ account: from, text: shortId(bech32, 6), title: `${bech32}\n${from}` });
    expect(view.from?.text).toMatch(/^acct_test1[02-9ac-hj-np-z]{6}…[02-9ac-hj-np-z]{6}$/);
    expect(rowView({ ...base, kind: 'register', from }, [], 1).from?.text).toMatch(/^acct1/);
    expect(view.to?.account).toBe(to);
  });

  it('shows an asset the registry does not name in base units with its id', () => {
    const view = rowView({ ...base, kind: 'session-pay', from, to, asset: { policyId: 'cc'.repeat(28), assetName: '41' },
      amount: 5n, grantId: 'dd'.repeat(32) }, [ADA]);
    expect(view).toMatchObject({ title: 'Session pay', amount: '5', ticker: 'cccc…cccc.41', extra: 'session dddd…dddd' });
  });

  it('shows a deposit from its L1 output to the beneficiary, with the L1 transaction', () => {
    const view = rowView({ ...base, kind: 'deposit', vaultId: ADA.vaultId, to, amount: 25_000_000n, lovelace: 25_000_000n,
      outputIndex: 1, l1TxId: 'ee'.repeat(32), deposit: { shape: 0, beneficiary: 'ACCOUNT', otherAssetCount: 0n } }, [ADA]);
    expect(view).toMatchObject({ title: 'Deposit (L1)', amount: '25', ticker: 'ADA', l1TxId: 'ee'.repeat(32) });
    expect(view.from?.text).toBe('L1 output #1');
    expect(view.extra).toBeUndefined();
  });

  it('shows a token withdrawal to its bech32 address with the lovelace that travels with it', () => {
    const view = rowView({ ...base, kind: 'withdraw', from, vaultId: USDM.vaultId, amount: 2_000_000n, lovelace: 1_500_000n,
      toAddress: '600d6a577e9441ad8ed9663931906e4d43ece8f82c712b1d0235affb06' }, [ADA, USDM]);
    expect(view).toMatchObject({ amount: '2', ticker: 'USDM', extra: '+ 1.5 ADA' });
    expect(view.to?.title).toMatch(/^addr_test1/);
  });

  it('shows a session grant\'s first limit', () => {
    const view = rowView({ ...base, kind: 'session-authorize', from, session: { sessionKey: '12'.repeat(32),
      actions: ['TRANSFER'], limits: [{ vaultId: ADA.vaultId, perPayment: 1_000_000n, cumulative: 3_000_000n }],
      recipients: [], notAfterMs: 0n } }, [ADA]);
    expect(view).toMatchObject({ title: 'Session authorize', amount: '1', ticker: 'ADA', extra: 'per payment, 3 ADA total' });
  });

  it('shows a governance command and an unknown message with its topic and size', () => {
    expect(rowView({ ...base, kind: 'governance', governance: { sequence: 2n, command: 'ASSET_STATUS', vaultId: USDM.vaultId,
      status: 'DEPOSITS_PAUSED' } }, [USDM]).to?.text).toBe('USDM → DEPOSITS_PAUSED');
    expect(rowView({ ...base, topic: 'orders', error: 'not CBOR' }, []).note).toBe('orders · 300 bytes · not CBOR');
  });
});

describe('ages', () => {
  it('reads seconds, minutes, hours and days', () => {
    const now = 1_000_000_000;
    expect(ago(now + 5_000, now)).toBe('just now');
    expect(ago(now - 12_000, now)).toBe('12s ago');
    expect(ago(now - 5 * 60_000, now)).toBe('5m ago');
    expect(ago(now - 3 * 3_600_000, now)).toBe('3h ago');
    expect(ago(now - 3 * 86_400_000, now)).toBe('3d ago');
  });
});
