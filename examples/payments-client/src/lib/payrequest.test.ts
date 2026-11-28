import { describe, expect, it } from 'vitest';
import { parsePayRequest, payRequestLink, requestAsset } from './payrequest';

const to = 'ab'.repeat(28);
const usdm = `${'f0'.repeat(28)}.5553444d`;

describe('payment request links', () => {
  it('round-trips through a link with the full asset id and base units', () => {
    const link = payRequestLink('https://pay.example/app/?chain=x#/old', {
      chainId: 'payments-1', toAccount: to, asset: usdm, amount: 12_500_000n, memo: 'Order #1042'
    });
    expect(link.startsWith('https://pay.example/app/?chain=x#/pay?v=1&')).toBe(true);
    expect(parsePayRequest(link)).toEqual({ chainId: 'payments-1', toAccount: to, asset: usdm, amount: 12_500_000n,
      memo: 'Order #1042' });
  });

  it('accepts a bare QR payload and names ADA as lovelace', () => {
    expect(requestAsset({ ada: true, policyId: '', assetName: '' })).toBe('lovelace');
    expect(parsePayRequest(`pay?v=1&chain=c&to=${to}&asset=lovelace&amount=5`).amount).toBe(5n);
  });

  it('refuses malformed requests', () => {
    const base = `#/pay?v=1&chain=c&to=${to}&asset=lovelace&amount=5`;
    expect(() => parsePayRequest('hello')).toThrow(/not a payment request/);
    expect(() => parsePayRequest(base.replace('v=1', 'v=2'))).toThrow(/version/);
    expect(() => parsePayRequest(base.replace('chain=c', 'chain='))).toThrow(/no chain/);
    expect(() => parsePayRequest(base.replace(to, 'addr_test1xyz'))).toThrow(/recipient/);
    expect(() => parsePayRequest(base.replace('lovelace', 'USDM'))).toThrow(/asset id/);
    expect(() => parsePayRequest(base.replace('amount=5', 'amount=1.5'))).toThrow(/positive integer/);
    expect(() => parsePayRequest(base.replace('amount=5', 'amount=0'))).toThrow(/positive integer/);
    expect(() => parsePayRequest(base.replace('amount=5', `amount=${1n << 64n}`))).toThrow(/too large/);
  });

  it('caps the unverified memo', () => {
    const request = parsePayRequest(`?v=1&chain=c&to=${to}&asset=lovelace&amount=5&memo=${'x'.repeat(500)}`);
    expect(request.memo).toHaveLength(120);
  });
});
