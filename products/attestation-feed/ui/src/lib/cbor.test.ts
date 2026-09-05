import { describe, expect, it } from 'vitest';
import { asSigned, asUnsigned, decodeCbor, encodeCbor, fromHex, toHex } from './cbor';

describe('bounded CBOR', () => {
  it('round-trips integers of both signs, bytes, text, arrays, and a tagged value', () => {
    const value = [1n, -1825n, 'degC', Uint8Array.of(1, 2), [0n, -1n], { tag: 121n, value: [7n] }];
    const encoded = encodeCbor(value);
    expect(toHex(encoded)).toBe('86013907206464656743420102820020d8798107');
    expect(decodeCbor(encoded)).toEqual(value);
    expect(asSigned(decodeCbor(fromHex('390720')))).toBe(-1825n);
    expect(asSigned(decodeCbor(fromHex('3b7ffffffffffffffe')))).toBe(-((1n << 63n) - 1n));
    expect(() => asSigned(decodeCbor(fromHex('3b7fffffffffffffff')))).toThrow('bounds');
    expect(() => asSigned(decodeCbor(fromHex('3bffffffffffffffff')))).toThrow('bounds');
    expect(() => asUnsigned(-1n)).toThrow('unsigned');
  });

  it('refuses non-canonical and unsupported encodings', () => {
    expect(() => decodeCbor(fromHex('1800'))).toThrow('Non-canonical');
    expect(() => decodeCbor(fromHex('a0'))).toThrow('major type 5');
    expect(() => decodeCbor(fromHex('9f'))).toThrow('Indefinite');
    expect(() => decodeCbor(fromHex('0102'))).toThrow('trailing');
    expect(() => decodeCbor(new Uint8Array(0))).toThrow('empty');
  });
});
