/**
 * Adds a CIP-30 signTx witness set to the unsigned deposit the payments API built. The body bytes are kept exactly
 * (the transaction id is their hash); only the empty witness set is replaced.
 */
import { arrayElementSpans, concat, read } from './cbor';

export function assembleSignedTx(unsignedTx: Uint8Array, witnessSet: Uint8Array): Uint8Array {
  const { headerEnd, spans } = arrayElementSpans(unsignedTx);
  if (spans.length !== 4 && spans.length !== 3) throw new Error('a transaction is a 3- or 4-element array');
  const [start, end] = spans[1];
  const current = unsignedTx.slice(start, end);
  if (!(current.length === 1 && current[0] === 0xa0)) {
    throw new Error('the unsigned transaction already carries witnesses; merging them is not supported');
  }
  const parsed = read(witnessSet, 0);
  if (parsed.end !== witnessSet.length || parsed.item.kind !== 'map') {
    throw new Error('the wallet returned something other than a witness set map');
  }
  return concat(unsignedTx.slice(0, headerEnd), unsignedTx.slice(spans[0][0], spans[0][1]), witnessSet,
    unsignedTx.slice(end));
}
