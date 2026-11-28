/** BIP-173 bech32 (not bech32m), without the 90-character limit, as Cardano addresses use it (CIP-5). */
const CHARSET = 'qpzry9x8gf2tvdw0s3jn54khce6mua7l';
const GENERATORS = [0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3];

function polymod(values: number[]): number {
  let checksum = 1;
  for (const value of values) {
    const top = checksum >>> 25;
    checksum = ((checksum & 0x1ffffff) << 5) ^ value;
    for (let i = 0; i < 5; i++) if ((top >>> i) & 1) checksum ^= GENERATORS[i];
  }
  return checksum;
}

function expand(prefix: string): number[] {
  const out: number[] = [];
  for (const char of prefix) out.push(char.charCodeAt(0) >> 5);
  out.push(0);
  for (const char of prefix) out.push(char.charCodeAt(0) & 31);
  return out;
}

function convert(data: ArrayLike<number>, from: number, to: number, pad: boolean): number[] {
  let accumulator = 0;
  let bits = 0;
  const out: number[] = [];
  const max = (1 << to) - 1;
  for (let i = 0; i < data.length; i++) {
    accumulator = (accumulator << from) | data[i];
    bits += from;
    while (bits >= to) {
      bits -= to;
      out.push((accumulator >> bits) & max);
    }
  }
  if (pad && bits > 0) out.push((accumulator << (to - bits)) & max);
  else if (!pad && (bits >= from || ((accumulator << (to - bits)) & max))) throw new Error('invalid bech32 padding');
  return out;
}

export function bech32Encode(prefix: string, bytes: Uint8Array): string {
  const data = convert(bytes, 8, 5, true);
  const checksum = polymod([...expand(prefix), ...data, 0, 0, 0, 0, 0, 0]) ^ 1;
  const tail = [0, 1, 2, 3, 4, 5].map((i) => (checksum >> (5 * (5 - i))) & 31);
  return prefix + '1' + [...data, ...tail].map((value) => CHARSET[value]).join('');
}

export function bech32Decode(text: string): { prefix: string; bytes: Uint8Array } {
  const value = text.trim().toLowerCase();
  const separator = value.lastIndexOf('1');
  if (separator < 1 || separator + 7 > value.length) throw new Error('not a bech32 string');
  const prefix = value.slice(0, separator);
  const data: number[] = [];
  for (const char of value.slice(separator + 1)) {
    const index = CHARSET.indexOf(char);
    if (index < 0) throw new Error('invalid bech32 character');
    data.push(index);
  }
  if (polymod([...expand(prefix), ...data]) !== 1) throw new Error('invalid bech32 checksum');
  return { prefix, bytes: Uint8Array.from(convert(data.slice(0, -6), 5, 8, false)) };
}

/** The CIP-5 prefix of a raw Shelley address. */
export function addressPrefix(address: Uint8Array): string {
  const type = address[0] >> 4;
  const mainnet = (address[0] & 0x0f) === 1;
  if (type === 14 || type === 15) return mainnet ? 'stake' : 'stake_test';
  return mainnet ? 'addr' : 'addr_test';
}

export function addressToBech32(address: Uint8Array): string {
  return bech32Encode(addressPrefix(address), address);
}
