import { asArray, asBytes, asText, asUnsigned, decodeCbor, encodeCbor, fromHex, toHex, utf8, type CborValue } from './cbor';
import { blake2b256 } from './message-proof';
import type { AppendCommand, SignedEnvelope, TrailHeadValue } from './types';

export const DOC_TRAIL_TOPIC = 'doc-trail.command.v1';
export const DOC_TRAIL_STATE_MACHINE = 'doc-trail';
const MAX_ENTITY_BYTES = 254;
const HEX_32 = /^[0-9a-f]{64}$/;

/** Canonical doc-trail append command: [entityId, entryHash, reference]. */
export function encodeAppendCommand(entityId: string, entryHashHex: string, reference = ''): Uint8Array {
  validateEntityId(entityId);
  if (!HEX_32.test(entryHashHex)) throw new Error('The entry hash must be 32 bytes of lowercase hex');
  return encodeCbor([entityId, fromHex(entryHashHex), reference]);
}

export function decodeAppendCommand(bodyHex: string): AppendCommand {
  const items = asArray(decodeCbor(fromHex(bodyHex)), 3);
  const entityId = asText(items[0]);
  validateEntityId(entityId);
  const entryHash = asBytes(items[1]);
  if (entryHash.length === 0) throw new Error('The command entry hash is empty');
  return { entityId, entryHashHex: toHex(entryHash), reference: asText(items[2]) };
}

/** State value of an entity: [count, headHash]. */
export function decodeTrailHead(valueHex: string): TrailHeadValue {
  const items = asArray(decodeCbor(fromHex(valueHex)), 2);
  return { count: asUnsigned(items[0]), headHashHex: toHex(asBytes(items[1], 32)) };
}

export function entityKeyHex(entityId: string): string {
  validateEntityId(entityId);
  return toHex(utf8(`e/${entityId}`));
}

export function derivedEntityId(entryHashHex: string): string {
  return `sha256:${entryHashHex}`;
}

export function validateEntityId(entityId: string): void {
  if (!entityId || entityId.trim() !== entityId || utf8(entityId).length > MAX_ENTITY_BYTES) {
    throw new Error('The entity id must be non-empty, untrimmed-free text of at most 254 UTF-8 bytes');
  }
}

/**
 * Block layouts the walker understands, by block version (item 0):
 * v2 (Yano pre13, 12 items): [version, chainId, height, prevHash, l1Slot, l1BlockHash, timestamp,
 *   messagesRoot, stateRoot, messages, proposer, cert]
 * v3 (Yano pre14, 15 items): [version, chainId, height, consensusContextDigest, view, prevHash,
 *   l1Slot, l1BlockHash, timestamp, messagesRoot, stateRoot, messages, proposer, justification, cert]
 * Each message is [version, messageId, chainId, topic, sender, senderSeq, expiresAt, body,
 * [authScheme, authProof]] in both.
 */
const BLOCK_LAYOUTS: Record<number, { items: number; stateRoot: number; messages: number }> = {
  2: { items: 12, stateRoot: 8, messages: 9 },
  3: { items: 15, stateRoot: 10, messages: 11 }
};

function decodeBlock(blockCborHex: string): { block: CborValue[]; layout: { items: number; stateRoot: number; messages: number } } {
  const block = asArray(decodeCbor(fromHex(blockCborHex)));
  const version = asUnsigned(block[0]);
  const layout = BLOCK_LAYOUTS[version];
  if (!layout) throw new Error(`Unsupported block version ${version}`);
  asArray(block, layout.items);
  return { block, layout };
}

/**
 * Reads the signed envelope at one message index out of a canonical block,
 * without hashing the block or touching its certificate.
 */
export function extractEnvelope(blockCborHex: string, index: number): SignedEnvelope {
  const { block, layout } = decodeBlock(blockCborHex);
  const messages = asArray(block[layout.messages]);
  if (!Number.isInteger(index) || index < 0 || index >= messages.length) {
    throw new Error('The message index is outside the evidence block');
  }
  const message = asArray(messages[index], 9);
  const auth = asArray(message[8], 2);
  return {
    version: asUnsigned(message[0]),
    messageIdHex: toHex(asBytes(message[1], 32)),
    chainId: asText(message[2]),
    topic: asText(message[3]),
    senderHex: toHex(asBytes(message[4])),
    senderSeq: asUnsigned(message[5]),
    expiresAt: asUnsigned(message[6]),
    bodyHex: toHex(asBytes(message[7])),
    authScheme: asUnsigned(auth[0]),
    authProofHex: toHex(asBytes(auth[1]))
  };
}

/** State root of a canonical block, used for the derived anchor reference. */
export function extractBlockStateRootHex(blockCborHex: string): string {
  const { block, layout } = decodeBlock(blockCborHex);
  return toHex(asBytes(block[layout.stateRoot], 32));
}

/** Canonical signed body: [chainId, topic, sender, senderSeq, expiresAt, body]. */
export function signedBodyBytes(envelope: Omit<SignedEnvelope, 'version' | 'messageIdHex' | 'authScheme' | 'authProofHex'>): Uint8Array {
  return encodeCbor([
    envelope.chainId,
    envelope.topic,
    fromHex(envelope.senderHex),
    BigInt(envelope.senderSeq),
    BigInt(envelope.expiresAt),
    fromHex(envelope.bodyHex)
  ]);
}

export function computeMessageIdHex(envelope: Omit<SignedEnvelope, 'version' | 'messageIdHex' | 'authScheme' | 'authProofHex'>): string {
  return toHex(blake2b256(signedBodyBytes(envelope)));
}
