import test from 'node:test';
import assert from 'node:assert/strict';
import { decodeCanonicalValue, encodeCanonicalValue } from '../canonical-value.mjs';
import { decodeCbor, encodeCbor, hex } from '../cbor.mjs';

test('application maps sort encoded keys and preserve the full integer domain', () => {
  assert.equal(hex(encodeCanonicalValue({ sku: 'A', qty: 5 })), 'a2637174790563736b756141');
  assert.equal(hex(encodeCanonicalValue(new Map([['z', 0], [24, -1], [false, null], [10, true]]))),
    'a40af5181820617a00f4f6');
  assert.equal(hex(encodeCanonicalValue((1n << 64n) - 1n)), '1bffffffffffffffff');
  assert.equal(hex(encodeCanonicalValue(-(1n << 64n))), '3bffffffffffffffff');
  assert.throws(() => encodeCanonicalValue(1n << 64n), /native CBOR/);
  assert.throws(() => encodeCanonicalValue(-(1n << 64n) - 1n), /native CBOR/);
  assert.throws(() => encodeCanonicalValue(Number.MAX_SAFE_INTEGER + 1), /safe integer/);
  assert.throws(() => encodeCanonicalValue(1.5), /safe integer/);
});

test('equal canonical keys and malformed strings fail before producing a map', () => {
  assert.throws(() => encodeCanonicalValue(new Map([[1, 'a'], [1n, 'b']])), /duplicate/);
  assert.throws(() => encodeCanonicalValue(new Map([[Uint8Array.of(1), 1], [Uint8Array.of(1), 2]])), /duplicate/);
  assert.throws(() => encodeCanonicalValue('\ud800'), /Unicode/);
});

test('schema IR can use core bounds without widening topic wire CBOR', () => {
  let nested = 0;
  for (let i = 0; i < 32; i++) nested = [nested];
  assert.equal(encodeCanonicalValue(nested).length, 33);
  assert.throws(() => encodeCanonicalValue([nested]), /structural/);
  assert.throws(() => encodeCbor(nested), /structural/);
  assert.throws(() => decodeCbor(encodeCanonicalValue(nested)), /structural/);
  assert.equal(encodeCanonicalValue(new Array(65535).fill(0)).length, 65538);
  assert.throws(() => encodeCanonicalValue(new Array(65536).fill(0)), /structural/);
  assert.throws(() => encodeCanonicalValue(new Uint8Array(65534), { maximumBytes: 65536 }), /byte bound/);
  assert.equal(encodeCanonicalValue(new Uint8Array(65532), { maximumBytes: 65536 }).length, 65535);
  assert.throws(() => encodeCanonicalValue(new Map([['a'.repeat(40000), 1], ['b'.repeat(40000), 2]]),
    { maximumBytes: 65536 }), /keys exceed/);
});

test('bounded value decoding matches encoding and refuses malformed maps and lengths', () => {
  const value = new Map([['a', [-(1n << 64n), (1n << 64n) - 1n, true, null, Uint8Array.of(2)]]]);
  assert.deepEqual(decodeCanonicalValue(encodeCanonicalValue(value)), value);
  for (const encoded of ['a2616201616102', 'a2616101616102', 'a163616263', '9bffffffffffffffff',
    'c101', '7f6161ff', 'fb3ff0000000000000', '1817', '6100ff', '61ff']) {
    assert.throws(() => decodeCanonicalValue(Uint8Array.from(encoded.match(/../g), byte => parseInt(byte, 16))),
      undefined, encoded);
  }
});
