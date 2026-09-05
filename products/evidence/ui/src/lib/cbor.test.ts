import { describe, expect, it } from 'vitest';
import { asArray, asBytes, asText, asUnsigned, decodeCbor, encodeCbor, fromHex, toHex } from './cbor';

describe('bounded canonical CBOR', () => {
  it('encodes the shortest integer, string, and array heads', () => {
    expect(toHex(encodeCbor(0n))).toBe('00');
    expect(toHex(encodeCbor(23n))).toBe('17');
    expect(toHex(encodeCbor(24n))).toBe('1818');
    expect(toHex(encodeCbor(255n))).toBe('18ff');
    expect(toHex(encodeCbor(256n))).toBe('190100');
    expect(toHex(encodeCbor(65_536n))).toBe('1a00010000');
    expect(toHex(encodeCbor(4_294_967_296n))).toBe('1b0000000100000000');
    expect(toHex(encodeCbor('abc'))).toBe('63616263');
    expect(toHex(encodeCbor(new Uint8Array([1, 2, 3])))).toBe('43010203');
    expect(toHex(encodeCbor(['e1', new Uint8Array(32), '']))).toBe(`826265315820${'00'.repeat(32)}60`.replace('8262', '8362'));
  });

  it('round trips nested arrays through the decoder', () => {
    const value = ['doc', new Uint8Array([9, 8, 7]), 1_800_000_000n, [0n, new Uint8Array(64)]];
    const decoded = asArray(decodeCbor(encodeCbor(value)), 4);
    expect(asText(decoded[0])).toBe('doc');
    expect(toHex(asBytes(decoded[1]))).toBe('090807');
    expect(asUnsigned(decoded[2])).toBe(1_800_000_000);
    expect(asArray(decoded[3], 2)).toHaveLength(2);
  });

  it('rejects non-canonical, indefinite, unsupported, and trailing input', () => {
    expect(() => decodeCbor(fromHex('1800'))).toThrow(/Non-canonical/);
    expect(() => decodeCbor(fromHex('190010'))).toThrow(/Non-canonical/);
    expect(() => decodeCbor(fromHex('9fff'))).toThrow(/Indefinite/);
    expect(() => decodeCbor(fromHex('a0'))).toThrow(/major type 5/);
    expect(() => decodeCbor(fromHex('20'))).toThrow(/major type 1/);
    expect(() => decodeCbor(fromHex('c100'))).toThrow(/major type 6/);
    expect(() => decodeCbor(fromHex('0000'))).toThrow(/trailing/);
    expect(() => decodeCbor(fromHex('5820ab'))).toThrow(/exceeds input/);
    expect(() => decodeCbor(fromHex('61ff'))).toThrow(/UTF-8/);
    expect(() => decodeCbor(new Uint8Array(0))).toThrow(/empty/);
  });

  it('refuses excessive nesting', () => {
    expect(() => decodeCbor(fromHex('81'.repeat(12) + '00'))).toThrow(/deeply/);
  });

  it('validates hex input', () => {
    expect(() => fromHex('ABCD')).toThrow(/lowercase hex/);
    expect(() => fromHex('abc')).toThrow(/lowercase hex/);
    expect(toHex(fromHex('00ff'))).toBe('00ff');
  });
});
