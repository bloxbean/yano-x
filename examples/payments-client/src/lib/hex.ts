/** Lowercase hex, the only form the payments API and the ledger's vectors use. */
export function toHex(bytes: Uint8Array): string {
  let out = '';
  for (const byte of bytes) out += byte.toString(16).padStart(2, '0');
  return out;
}

export function fromHex(hex: string, expectedLength?: number): Uint8Array {
  const value = hex.trim();
  if (value.length % 2 !== 0 || !/^[0-9a-fA-F]*$/.test(value)) throw new Error('expected even-length hex');
  const out = new Uint8Array(value.length / 2);
  for (let i = 0; i < out.length; i++) out[i] = parseInt(value.slice(2 * i, 2 * i + 2), 16);
  if (expectedLength !== undefined && out.length !== expectedLength) {
    throw new Error(`expected ${expectedLength} bytes, got ${out.length}`);
  }
  return out;
}

export function equalBytes(left: Uint8Array, right: Uint8Array): boolean {
  if (left.length !== right.length) return false;
  for (let i = 0; i < left.length; i++) if (left[i] !== right[i]) return false;
  return true;
}

export function randomBytes(length: number): Uint8Array {
  const out = new Uint8Array(length);
  crypto.getRandomValues(out);
  return out;
}
