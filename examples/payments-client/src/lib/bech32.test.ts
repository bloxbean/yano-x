import { describe, expect, it } from 'vitest';
import { addressToBech32, Bech32Error, bech32Decode, bech32DecodeWords, bech32Encode, wordsToBytes } from './bech32';
import { fromHex, toHex } from './hex';

function failure(text: string): string {
  try {
    bech32DecodeWords(text);
  } catch (error) {
    return error instanceof Bech32Error ? error.reason : 'other';
  }
  return 'accepted';
}

describe('BIP-173 test vectors', () => {
  it('accepts every valid checksum vector', () => {
    for (const text of [
      'A12UEL5L',
      'a12uel5l',
      'an83characterlonghumanreadablepartthatcontainsthenumber1andtheexcludedcharactersbio1tt5tgs',
      'abcdef1qpzry9x8gf2tvdw0s3jn54khce6mua7lmqqqxw',
      `11${'q'.repeat(82)}c8247j`,
      'split1checkupstagehandshakeupstreamerranterredcaperred2y9e3w',
      '?1ezyfcl',
    ]) {
      expect(failure(text), text).toBe('accepted');
    }
    expect(bech32DecodeWords('A12UEL5L')).toEqual({ prefix: 'a', words: [] });
    expect(bech32DecodeWords('abcdef1qpzry9x8gf2tvdw0s3jn54khce6mua7lmqqqxw').words)
      .toEqual(Array.from({ length: 32 }, (_, i) => i));
  });

  it('refuses every invalid checksum vector, for its reason', () => {
    expect(failure('\x201nwldj5')).toBe('format'); // HRP character out of range (trimmed: an empty HRP)
    expect(failure('\x7f1axkwrx')).toBe('character');
    expect(failure('\x801eym55h')).toBe('character');
    expect(failure('pzry9x0s0muk')).toBe('format'); // no separator
    expect(failure('1pzry9x0s0muk')).toBe('format'); // empty HRP
    expect(failure('x1b4n0q5v')).toBe('character'); // invalid data character
    expect(failure('li1dgmt3')).toBe('format'); // checksum too short
    expect(failure('de1lg7wt\xff')).toBe('character'); // invalid character in the checksum
    expect(failure('A1G7SGD8')).toBe('checksum'); // checksum computed over the uppercase HRP
    expect(failure('10a06t8')).toBe('format'); // empty HRP
    expect(failure('1qzzfhee')).toBe('format'); // empty HRP
  });

  it('lifts only the 90-character limit, as CIP-5 does for Cardano addresses', () => {
    expect(failure('an84characterslonghumanreadablepartthatcontainsthenumber1andtheexcludedcharactersbio1569pvx'))
      .toBe('accepted');
  });

  it('decodes the segwit address vectors to their witness programs (5-to-8-bit conversion)', () => {
    const program = (text: string) => {
      const { words } = bech32DecodeWords(text);
      return { version: words[0], program: toHex(wordsToBytes(words.slice(1))) };
    };
    expect(program('BC1QW508D6QEJXTDG4Y5R3ZARVARY0C5XW7KV8F3T4'))
      .toEqual({ version: 0, program: '751e76e8199196d454941c45d1b3a323f1433bd6' });
    expect(program('tb1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3q0sl5k7'))
      .toEqual({ version: 0, program: '1863143c14c5166804bd19203356da136c985678cd4d27a1b8c6329604903262' });
    expect(failure('bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t5')).toBe('checksum');
    expect(failure('tb1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3q0sL5k7')).toBe('mixed-case');
    expect(() => program('bc1zw508d6qejxtdg4y5r3zarvaryvqyzf3du')).toThrow(/padding/);
    expect(() => program('tb1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3pjxtptv')).toThrow(/padding/);
  });
});

describe('the codec', () => {
  it('round-trips bytes of every length through encode and decode', () => {
    for (let length = 0; length <= 64; length++) {
      const bytes = Uint8Array.from({ length }, (_, i) => (i * 37 + length) & 0xff);
      const text = bech32Encode('acct_test', bytes);
      expect(bech32Decode(text)).toEqual({ prefix: 'acct_test', bytes });
      expect(bech32Decode(text.toUpperCase())).toEqual({ prefix: 'acct_test', bytes });
    }
  });

  it('refuses mixed case, a changed character and a changed prefix', () => {
    const text = bech32Encode('acct_test', fromHex('cd'.repeat(28)));
    expect(failure(text.slice(0, 12) + text.slice(12, 13).toUpperCase() + text.slice(13))).toBe('mixed-case');
    expect(failure(text.slice(0, -1) + (text.endsWith('q') ? 'p' : 'q'))).toBe('checksum');
    expect(failure(text.replace('acct_test', 'acct'))).toBe('checksum');
  });

  it('writes CIP-19 addresses with the prefix of their header', () => {
    expect(addressToBech32(fromHex('619493315cd92eb5d8c4304e67b7e16ae36d61d34502694657811a2c8e')))
      .toBe('addr1vx2fxv2umyhttkxyxp8x0dlpdt3k6cwng5pxj3jhsydzers66hrl8');
    expect(addressToBech32(fromHex('e0337b62cfff6403a06a3acbc34f8c46003c69fe79a3628cefa9c47251')))
      .toBe('stake_test1uqehkck0lajq8gr28t9uxnuvgcqrc6070x3k9r8048z8y5gssrtvn');
  });
});
