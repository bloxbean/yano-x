/**
 * Reads what a CIP-30 signData result claims, so the client can refuse to submit anything other than the exact
 * envelope the user approved (#109 §3.1: no silent downgrade). The ledger verifies the signature itself; this only
 * checks the payload and the signing address before submission.
 */
import { readAll, type Item } from './cbor';

export interface CoseSign1View {
  protectedAddress: Uint8Array | null;
  payload: Uint8Array | null;
  hashed: boolean | null;
}

export function parseCoseSign1(bytes: Uint8Array): CoseSign1View {
  let item: Item = readAll(bytes);
  if (item.kind === 'tag') {
    if (item.tag !== 18n) throw new Error('COSE_Sign1 must be untagged or tag 18');
    item = item.item;
  }
  if (item.kind !== 'array' || item.items.length !== 4) throw new Error('COSE_Sign1 is a 4-element array');
  const [protectedItem, unprotectedItem, payloadItem] = item.items;
  if (protectedItem.kind !== 'bytes') throw new Error('COSE_Sign1 protected header is a byte string');
  let protectedAddress: Uint8Array | null = null;
  if (protectedItem.value.length > 0) {
    const header = readAll(protectedItem.value);
    if (header.kind !== 'map') throw new Error('COSE_Sign1 protected header is a map');
    for (const [key, value] of header.entries) {
      if (key.kind === 'text' && key.value === 'address' && value.kind === 'bytes') protectedAddress = value.value;
    }
  }
  let hashed: boolean | null = null;
  if (unprotectedItem.kind === 'map') {
    for (const [key, value] of unprotectedItem.entries) {
      if (key.kind === 'text' && key.value === 'hashed' && value.kind === 'simple') hashed = value.value === 21;
    }
  }
  const payload = payloadItem.kind === 'bytes' ? payloadItem.value : null;
  return { protectedAddress, payload, hashed };
}
