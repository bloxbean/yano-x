import { describe, expect, it } from 'vitest';
import { encode } from './cbor';
import { fromHex, toHex } from './hex';
import { assembleSignedTx } from './tx';
import { formatUnits, toBaseUnits } from './amounts';

describe('signed transaction assembly', () => {
  it('replaces only the empty witness set and keeps the body bytes', () => {
    // [body {0: [], 1: [], 2: 170000}, {}, true, null] as CCL serializes an unsigned Conway transaction.
    const body = fromHex('a300800180021a00029810');
    const unsigned = new Uint8Array([0x84, ...body, 0xa0, 0xf5, 0xf6]);
    const witnesses = fromHex('a10081825820' + '11'.repeat(32) + '5840' + '22'.repeat(64));
    const signed = assembleSignedTx(unsigned, witnesses);
    expect(toHex(signed)).toBe('84' + toHex(body) + toHex(witnesses) + 'f5f6');
  });

  it('refuses an unsigned transaction that already has witnesses, and a non-map witness set', () => {
    const body = fromHex('a300800180021a00029810');
    const witnessed = new Uint8Array([0x84, ...body, 0xa1, 0x00, 0x80, 0xf5, 0xf6]);
    expect(() => assembleSignedTx(witnessed, fromHex('a0'))).toThrow(/already carries witnesses/);
    const unsigned = new Uint8Array([0x84, ...body, 0xa0, 0xf5, 0xf6]);
    expect(() => assembleSignedTx(unsigned, encode([1]))).toThrow(/witness set/);
  });
});

describe('amounts', () => {
  it('converts display units to integer base units without floating point', () => {
    expect(toBaseUnits('12.5', 6)).toBe(12_500_000n);
    expect(toBaseUnits('18446744073709.551615', 6)).toBe(18446744073709551615n);
    expect(() => toBaseUnits('1.0000001', 6)).toThrow();
    expect(() => toBaseUnits('-1', 6)).toThrow();
    expect(formatUnits('12500000', 6)).toBe('12.5');
    expect(formatUnits(7n, 0)).toBe('7');
  });
});

describe('bech32 addresses', async () => {
  const { addressToBech32, bech32Decode } = await import('./bech32');
  it('round-trips a DevKit base address', () => {
    const bech32 = 'addr_test1qryvgass5dsrf2kxl3vgfz76uhp83kv5lagzcp29tcana68ca5aqa6swlq6llfamln09tal7n5kvt4275ckwedpt4v7q48uhex';
    const { prefix, bytes } = bech32Decode(bech32);
    expect(prefix).toBe('addr_test');
    expect(toHex(bytes)).toBe('00c8c47610a36034aac6fc58848bdae5c278d994ff502c05455e3b3ee8f8ed3a0eea0ef835ffa7bbfcde55f7fe9d2cc5d55ea62cecb42bab3c');
    expect(addressToBech32(bytes)).toBe(bech32);
    expect(() => bech32Decode(bech32.slice(0, -1) + 'q')).toThrow(/checksum/);
  });
});
