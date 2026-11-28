/**
 * The strict CBOR subset of the ADR-059 contracts: definite-length arrays, byte strings, text strings and
 * unsigned integers, each in its shortest (preferred) form. Encoding "as built" in this subset is the
 * deterministic encoding the Java codecs (AssetCbor) produce, so the bytes a wallet signs here are exactly
 * the bytes the ledger decodes.
 *
 * The reader below is a separate, permissive walker for wallet output (COSE, witness sets, transactions):
 * it never re-encodes what it reads, it only locates items.
 */
export type CborValue = bigint | number | Uint8Array | string | CborValue[];

const MAX_U64 = (1n << 64n) - 1n;
const utf8 = new TextEncoder();

export function encode(value: CborValue): Uint8Array {
  const chunks: Uint8Array[] = [];
  write(value, chunks);
  let length = 0;
  for (const chunk of chunks) length += chunk.length;
  const out = new Uint8Array(length);
  let offset = 0;
  for (const chunk of chunks) {
    out.set(chunk, offset);
    offset += chunk.length;
  }
  return out;
}

function write(value: CborValue, out: Uint8Array[]): void {
  if (typeof value === 'number') {
    if (!Number.isSafeInteger(value) || value < 0) throw new Error(`not an unsigned integer: ${value}`);
    out.push(head(0, BigInt(value)));
  } else if (typeof value === 'bigint') {
    if (value < 0n || value > MAX_U64) throw new Error('unsigned integer out of the u64 range');
    out.push(head(0, value));
  } else if (value instanceof Uint8Array) {
    out.push(head(2, BigInt(value.length)), value);
  } else if (typeof value === 'string') {
    const bytes = utf8.encode(value);
    out.push(head(3, BigInt(bytes.length)), bytes);
  } else if (Array.isArray(value)) {
    out.push(head(4, BigInt(value.length)));
    for (const item of value) write(item, out);
  } else {
    throw new Error('value is outside the contract CBOR subset');
  }
}

/** The shortest head for major type {@code major} and argument {@code n}. */
export function head(major: number, n: bigint): Uint8Array {
  const type = major << 5;
  if (n < 24n) return Uint8Array.of(type | Number(n));
  if (n < 0x100n) return Uint8Array.of(type | 24, Number(n));
  if (n < 0x10000n) return Uint8Array.of(type | 25, Number(n >> 8n), Number(n & 0xffn));
  if (n < 0x100000000n) {
    return Uint8Array.of(type | 26, ...[24n, 16n, 8n, 0n].map((shift) => Number((n >> shift) & 0xffn)));
  }
  return Uint8Array.of(type | 27, ...[56n, 48n, 40n, 32n, 24n, 16n, 8n, 0n].map((s) => Number((n >> s) & 0xffn)));
}

// ------------------------------------------------------------------ reader

export type Item =
  | { kind: 'uint'; value: bigint }
  | { kind: 'nint'; value: bigint }
  | { kind: 'bytes'; value: Uint8Array }
  | { kind: 'text'; value: string }
  | { kind: 'array'; items: Item[] }
  | { kind: 'map'; entries: [Item, Item][] }
  | { kind: 'tag'; tag: bigint; item: Item }
  | { kind: 'simple'; value: number };

export interface Read {
  item: Item;
  /** The offset just past this item. */
  end: number;
}

const MAX_DEPTH = 32;
const utf8Decoder = new TextDecoder('utf-8', { fatal: true });

/** Reads one complete item starting at {@code offset}. Indefinite lengths are accepted. */
export function read(bytes: Uint8Array, offset = 0, depth = 0): Read {
  if (depth > MAX_DEPTH) throw new Error('CBOR nesting too deep');
  if (offset >= bytes.length) throw new Error('unexpected end of CBOR');
  const initial = bytes[offset];
  const major = initial >> 5;
  const info = initial & 0x1f;
  let position = offset + 1;
  if (info === 31) {
    if (major === 2 || major === 3) {
      const parts: Uint8Array[] = [];
      while (bytes[position] !== 0xff) {
        const part = read(bytes, position, depth + 1);
        if (part.item.kind !== (major === 2 ? 'bytes' : 'text')) throw new Error('bad indefinite string chunk');
        parts.push(part.item.kind === 'bytes' ? part.item.value : utf8.encode((part.item as { value: string }).value));
        position = part.end;
      }
      const joined = concat(...parts);
      return {
        item: major === 2 ? { kind: 'bytes', value: joined } : { kind: 'text', value: utf8Decoder.decode(joined) },
        end: position + 1
      };
    }
    if (major === 4 || major === 5) {
      const items: Item[] = [];
      while (bytes[position] !== 0xff) {
        const next = read(bytes, position, depth + 1);
        items.push(next.item);
        position = next.end;
        if (position >= bytes.length) throw new Error('unterminated indefinite item');
      }
      return { item: major === 4 ? { kind: 'array', items } : { kind: 'map', entries: pairs(items) }, end: position + 1 };
    }
    throw new Error('invalid indefinite length');
  }
  const [argument, afterHead] = readArgument(bytes, position, info);
  position = afterHead;
  switch (major) {
    case 0:
      return { item: { kind: 'uint', value: argument }, end: position };
    case 1:
      return { item: { kind: 'nint', value: -1n - argument }, end: position };
    case 2:
    case 3: {
      const length = Number(argument);
      if (position + length > bytes.length) throw new Error('CBOR string runs past the input');
      const slice = bytes.slice(position, position + length);
      return {
        item: major === 2 ? { kind: 'bytes', value: slice } : { kind: 'text', value: utf8Decoder.decode(slice) },
        end: position + length
      };
    }
    case 4:
    case 5: {
      const count = Number(argument) * (major === 5 ? 2 : 1);
      const items: Item[] = [];
      for (let i = 0; i < count; i++) {
        const next = read(bytes, position, depth + 1);
        items.push(next.item);
        position = next.end;
      }
      return { item: major === 4 ? { kind: 'array', items } : { kind: 'map', entries: pairs(items) }, end: position };
    }
    case 6: {
      const inner = read(bytes, position, depth + 1);
      return { item: { kind: 'tag', tag: argument, item: inner.item }, end: inner.end };
    }
    default:
      return { item: { kind: 'simple', value: info }, end: position };
  }
}

function readArgument(bytes: Uint8Array, position: number, info: number): [bigint, number] {
  if (info < 24) return [BigInt(info), position];
  const size = info === 24 ? 1 : info === 25 ? 2 : info === 26 ? 4 : info === 27 ? 8 : -1;
  if (size < 0) throw new Error('reserved CBOR additional information');
  if (position + size > bytes.length) throw new Error('unexpected end of CBOR');
  let value = 0n;
  for (let i = 0; i < size; i++) value = (value << 8n) | BigInt(bytes[position + i]);
  return [value, position + size];
}

function pairs(items: Item[]): [Item, Item][] {
  const out: [Item, Item][] = [];
  for (let i = 0; i + 1 < items.length; i += 2) out.push([items[i], items[i + 1]]);
  return out;
}

/** Reads exactly one item spanning all of {@code bytes}. */
export function readAll(bytes: Uint8Array): Item {
  const { item, end } = read(bytes, 0);
  if (end !== bytes.length) throw new Error('trailing bytes after the CBOR item');
  return item;
}

/** The byte offsets [start, end) of each element of the top-level definite array in {@code bytes}. */
export function arrayElementSpans(bytes: Uint8Array): { headerEnd: number; spans: [number, number][] } {
  const initial = bytes[0];
  if (initial >> 5 !== 4 || (initial & 0x1f) === 31) throw new Error('expected a definite CBOR array');
  const [count, headerEnd] = readArgument(bytes, 1, initial & 0x1f);
  const spans: [number, number][] = [];
  let position = headerEnd;
  for (let i = 0; i < Number(count); i++) {
    const { end } = read(bytes, position);
    spans.push([position, end]);
    position = end;
  }
  if (position !== bytes.length) throw new Error('trailing bytes after the CBOR array');
  return { headerEnd, spans };
}

export function concat(...parts: Uint8Array[]): Uint8Array {
  let length = 0;
  for (const part of parts) length += part.length;
  const out = new Uint8Array(length);
  let offset = 0;
  for (const part of parts) {
    out.set(part, offset);
    offset += part.length;
  }
  return out;
}
