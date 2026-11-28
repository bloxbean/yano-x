import { describe, expect, it } from 'vitest';
import { ACCOUNT } from '../test/fixtures';
import { bech32Encode } from './bech32';
import { fromHex } from './hex';
import { accountIdBech32 } from './ids';
import { parsePayRequest, payRequestLink, requestAsset } from './payrequest';

const to = 'ab'.repeat(28);
const toBech32 = accountIdBech32(to, 0);
const usdm = `${'f0'.repeat(28)}.5553444d`;

describe('payment request links', () => {
  it('round-trips through a link with the bech32 account id, the full asset id and base units', () => {
    const link = payRequestLink('https://pay.example/app/?chain=x#/old', {
      chainId: 'payments-1', to: toBech32, asset: usdm, amount: 12_500_000n, memo: 'Order #1042'
    });
    expect(link.startsWith('https://pay.example/app/?chain=x#/pay?v=1&')).toBe(true);
    expect(link).toContain(`to=${toBech32}&`);
    expect(parsePayRequest(link, 0)).toEqual({ chainId: 'payments-1', to: toBech32,
      recipient: { kind: 'account', accountId: to, typed: toBech32 }, asset: usdm, amount: 12_500_000n, memo: 'Order #1042' });
  });

  it('still accepts an old link with a hex account id, and a Cardano address as the recipient', () => {
    expect(parsePayRequest(`#/pay?v=1&chain=c&to=${to.toUpperCase()}&asset=lovelace&amount=5`, 0).recipient)
      .toEqual({ kind: 'account', accountId: to, typed: to.toUpperCase() });
    const address = bech32Encode('addr_test', fromHex(ACCOUNT.addressHex));
    expect(parsePayRequest(`#/pay?v=1&chain=c&to=${address}&asset=lovelace&amount=5`, 0).recipient)
      .toMatchObject({ kind: 'address', addressHex: ACCOUNT.addressHex });
  });

  it('accepts a bare QR payload and names ADA as lovelace', () => {
    expect(requestAsset({ ada: true, policyId: '', assetName: '' })).toBe('lovelace');
    expect(parsePayRequest(`pay?v=1&chain=c&to=${to}&asset=lovelace&amount=5`, 0).amount).toBe(5n);
  });

  it('refuses malformed requests', () => {
    const base = `#/pay?v=1&chain=c&to=${toBech32}&asset=lovelace&amount=5`;
    expect(() => parsePayRequest('hello', 0)).toThrow(/not a payment request/);
    expect(() => parsePayRequest(base.replace('v=1', 'v=2'), 0)).toThrow(/version/);
    expect(() => parsePayRequest(base.replace('chain=c', 'chain='), 0)).toThrow(/no chain/);
    expect(() => parsePayRequest(base.replace(toBech32, 'addr_test1xyz'), 0)).toThrow(/recipient/);
    expect(() => parsePayRequest(base.replace('lovelace', 'USDM'), 0)).toThrow(/asset id/);
    expect(() => parsePayRequest(base.replace('amount=5', 'amount=1.5'), 0)).toThrow(/positive integer/);
    expect(() => parsePayRequest(base.replace('amount=5', 'amount=0'), 0)).toThrow(/positive integer/);
    expect(() => parsePayRequest(base.replace('amount=5', `amount=${1n << 64n}`), 0)).toThrow(/too large/);
  });

  it('refuses a recipient of the other network and a bad checksum, saying which', () => {
    const base = `#/pay?v=1&chain=c&to=${toBech32}&asset=lovelace&amount=5`;
    expect(() => parsePayRequest(base, 1)).toThrow(/testnet account id; this ledger is on mainnet/);
    expect(() => parsePayRequest(base.replace(toBech32, accountIdBech32(to, 1)), 0))
      .toThrow(/mainnet account id; this ledger is on testnet/);
    expect(() => parsePayRequest(base.replace(toBech32, `${toBech32.slice(0, -1)}${toBech32.endsWith('q') ? 'p' : 'q'}`), 0))
      .toThrow(/bad checksum/);
  });

  it('caps the unverified memo', () => {
    const request = parsePayRequest(`?v=1&chain=c&to=${to}&asset=lovelace&amount=5&memo=${'x'.repeat(500)}`, 0);
    expect(request.memo).toHaveLength(120);
  });
});
