/**
 * Bounded canonical CBOR for the subset the attestation feed console needs: integers of both
 * signs, byte strings, text strings, definite-length arrays, and one tagged value (the Plutus
 * constructor tag of the candidate datum).
 *
 * The encoder emits the shortest integer forms and definite lengths, which is what the Yano
 * contracts produce. The decoder is read-only and refuses everything outside that subset:
 * indefinite lengths, maps, floats, and non-minimal integer encodings.
 */

export interface Tagged {
  tag: bigint;
  value: CborValue;
}

export type CborValue = bigint | Uint8Array | string | CborValue[] | Tagged;

const MAX_DEPTH = 8;
const MAX_ITEMS = 20_000;
export const MAX_DECODE_BYTES = 5 * 1024 * 1024;

const TEXT_ENCODER = new TextEncoder();
const TEXT_DECODER = new TextDecoder('utf-8', { fatal: true });

export function encodeCbor(value: CborValue): Uint8Array {
  const parts: Uint8Array[] = [];
  write(value, parts, 0);
  const length = parts.reduce((total, part) => total + part.length, 0);
  const result = new Uint8Array(length);
  let offset = 0;
  for (const part of parts) {
    result.set(part, offset);
    offset += part.length;
  }
  return result;
}

export function decodeCbor(bytes: Uint8Array): CborValue {
  if (bytes.length === 0 || bytes.length > MAX_DECODE_BYTES) {
    throw new Error('CBOR input is empty or exceeds the decode bound');
  }
  const reader = { bytes, offset: 0, items: 0 };
  const value = read(reader, 0);
  if (reader.offset !== bytes.length) throw new Error('CBOR input has trailing bytes');
  return value;
}

export function asArray(value: CborValue, length?: number): CborValue[] {
  if (!Array.isArray(value) || (length !== undefined && value.length !== length)) {
    throw new Error(`Expected a CBOR array${length === undefined ? '' : ` of ${length} items`}`);
  }
  return value;
}

export function asBytes(value: CborValue, length?: number): Uint8Array {
  if (!(value instanceof Uint8Array) || (length !== undefined && value.length !== length)) {
    throw new Error(`Expected a CBOR byte string${length === undefined ? '' : ` of ${length} bytes`}`);
  }
  return value;
}

export function asText(value: CborValue): string {
  if (typeof value !== 'string') throw new Error('Expected a CBOR text string');
  return value;
}

export function asUnsigned(value: CborValue): number {
  if (typeof value !== 'bigint' || value < 0n) throw new Error('Expected a CBOR unsigned integer');
  if (value > BigInt(Number.MAX_SAFE_INTEGER)) throw new Error('CBOR integer exceeds browser bounds');
  return Number(value);
}

/** A signed integer within ±(2^63 − 1), as the feed profile bounds every value. */
export function asSigned(value: CborValue): bigint {
  if (typeof value !== 'bigint') throw new Error('Expected a CBOR integer');
  const bound = (1n << 63n) - 1n;
  if (value > bound || value < -bound) throw new Error('CBOR integer exceeds the feed bounds');
  return value;
}

export function toHex(value: Uint8Array): string {
  return Array.from(value, (item) => item.toString(16).padStart(2, '0')).join('');
}

export function fromHex(value: string): Uint8Array {
  if (!/^(?:[0-9a-f]{2})*$/.test(value)) throw new Error('Expected lowercase hex');
  return Uint8Array.from({ length: value.length / 2 },
    (_, index) => Number.parseInt(value.slice(index * 2, index * 2 + 2), 16));
}

export function utf8(value: string): Uint8Array {
  return TEXT_ENCODER.encode(value);
}

function write(value: CborValue, parts: Uint8Array[], depth: number): void {
  if (depth > MAX_DEPTH) throw new Error('CBOR value nests too deeply');
  if (typeof value === 'bigint') {
    parts.push(value < 0n ? head(1, -1n - value) : head(0, value));
  } else if (value instanceof Uint8Array) {
    parts.push(head(2, BigInt(value.length)), value);
  } else if (typeof value === 'string') {
    const encoded = utf8(value);
    parts.push(head(3, BigInt(encoded.length)), encoded);
  } else if (Array.isArray(value)) {
    parts.push(head(4, BigInt(value.length)));
    for (const item of value) write(item, parts, depth + 1);
  } else if (value && typeof value === 'object' && 'tag' in value) {
    parts.push(head(6, value.tag));
    write(value.value, parts, depth + 1);
  } else {
    throw new Error('Unsupported CBOR value');
  }
}

function head(major: number, value: bigint): Uint8Array {
  const type = major << 5;
  if (value < 24n) return Uint8Array.of(type | Number(value));
  if (value < 0x100n) return Uint8Array.of(type | 24, Number(value));
  if (value < 0x10000n) return Uint8Array.of(type | 25, Number(value >> 8n), Number(value & 0xffn));
  if (value < 0x100000000n) {
    return Uint8Array.of(type | 26, Number(value >> 24n), Number((value >> 16n) & 0xffn),
      Number((value >> 8n) & 0xffn), Number(value & 0xffn));
  }
  const result = new Uint8Array(9);
  result[0] = type | 27;
  let remaining = value;
  for (let index = 8; index >= 1; index--) {
    result[index] = Number(remaining & 0xffn);
    remaining >>= 8n;
  }
  return result;
}

interface Reader {
  bytes: Uint8Array;
  offset: number;
  items: number;
}

function read(reader: Reader, depth: number): CborValue {
  if (depth > MAX_DEPTH) throw new Error('CBOR value nests too deeply');
  if (++reader.items > MAX_ITEMS) throw new Error('CBOR value has too many items');
  if (reader.offset >= reader.bytes.length) throw new Error('Truncated CBOR value');
  const initial = reader.bytes[reader.offset++];
  const major = initial >> 5;
  const additional = initial & 31;
  const argument = readArgument(reader, additional);
  switch (major) {
    case 0:
      return argument;
    case 1:
      return -1n - argument;
    case 2: {
      const length = boundedLength(reader, argument);
      const value = reader.bytes.slice(reader.offset, reader.offset + length);
      reader.offset += length;
      return value;
    }
    case 3: {
      const length = boundedLength(reader, argument);
      const value = reader.bytes.subarray(reader.offset, reader.offset + length);
      reader.offset += length;
      try {
        return TEXT_DECODER.decode(value);
      } catch {
        throw new Error('CBOR text is not valid UTF-8');
      }
    }
    case 4: {
      const length = boundedLength(reader, argument);
      const items: CborValue[] = [];
      for (let index = 0; index < length; index++) items.push(read(reader, depth + 1));
      return items;
    }
    case 6:
      return { tag: argument, value: read(reader, depth + 1) };
    default:
      throw new Error(`Unsupported CBOR major type ${major}`);
  }
}

function readArgument(reader: Reader, additional: number): bigint {
  if (additional < 24) return BigInt(additional);
  const width = additional === 24 ? 1 : additional === 25 ? 2 : additional === 26 ? 4
    : additional === 27 ? 8 : 0;
  if (width === 0) throw new Error('Indefinite or reserved CBOR lengths are not supported');
  if (reader.offset + width > reader.bytes.length) throw new Error('Truncated CBOR argument');
  let value = 0n;
  for (let index = 0; index < width; index++) value = (value << 8n) | BigInt(reader.bytes[reader.offset++]);
  const minimum = width === 1 ? 24n : 1n << BigInt((width / 2) * 8);
  if (value < minimum) throw new Error('Non-canonical CBOR integer encoding');
  return value;
}

function boundedLength(reader: Reader, argument: bigint): number {
  if (argument > BigInt(reader.bytes.length - reader.offset)) throw new Error('CBOR length exceeds input');
  return Number(argument);
}
