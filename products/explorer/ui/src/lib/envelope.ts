import { asArray, asBytes, asText, asUnsigned, decodeCbor, encodeCbor, fromHex, toHex, type CborValue } from './cbor';
import { blake2b256 } from './message-proof';

/** The signed message envelope as the canonical block carries it. */
export interface SignedEnvelope {
  version: number;
  messageIdHex: string;
  chainId: string;
  topic: string;
  senderHex: string;
  senderSeq: number;
  expiresAt: number;
  bodyHex: string;
  authScheme: number;
  authProofHex: string;
}

export type SignedBody = Omit<SignedEnvelope, 'version' | 'messageIdHex' | 'authScheme' | 'authProofHex'>;

/**
 * Block layouts the walker understands, by block version (item 0):
 * v2 (Yano pre13, 12 items): [version, chainId, height, prevHash, l1Slot, l1BlockHash, timestamp,
 *   messagesRoot, stateRoot, messages, proposer, cert]
 * v3 (Yano pre14, 15 items): [version, chainId, height, consensusContextDigest, view, prevHash,
 *   l1Slot, l1BlockHash, timestamp, messagesRoot, stateRoot, messages, proposer, justification, cert]
 * Each message is [version, messageId, chainId, topic, sender, senderSeq, expiresAt, body,
 * [authScheme, authProof]] in both.
 */
const BLOCK_LAYOUTS: Record<number, { items: number; height: number; messagesRoot: number; stateRoot: number; messages: number }> = {
  2: { items: 12, height: 2, messagesRoot: 7, stateRoot: 8, messages: 9 },
  3: { items: 15, height: 2, messagesRoot: 9, stateRoot: 10, messages: 11 }
};

function decodeBlock(blockCborHex: string): { block: CborValue[]; layout: (typeof BLOCK_LAYOUTS)[number] } {
  const block = asArray(decodeCbor(fromHex(blockCborHex)));
  const version = asUnsigned(block[0]);
  const layout = BLOCK_LAYOUTS[version];
  if (!layout) throw new Error(`Unsupported block version ${version}`);
  asArray(block, layout.items);
  return { block, layout };
}

/** Header fields of a canonical block without hashing it or touching its certificate. */
export function extractBlockHeader(blockCborHex: string): { chainId: string; height: number; messagesRootHex: string; stateRootHex: string; messageCount: number } {
  const { block, layout } = decodeBlock(blockCborHex);
  return {
    chainId: asText(block[1]),
    height: asUnsigned(block[layout.height]),
    messagesRootHex: toHex(asBytes(block[layout.messagesRoot], 32)),
    stateRootHex: toHex(asBytes(block[layout.stateRoot], 32)),
    messageCount: asArray(block[layout.messages]).length
  };
}

/** Reads the signed envelope at one message index out of a canonical block. */
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

/** Canonical signed body: [chainId, topic, sender, senderSeq, expiresAt, body]. */
export function signedBodyBytes(envelope: SignedBody): Uint8Array {
  return encodeCbor([
    envelope.chainId,
    envelope.topic,
    fromHex(envelope.senderHex),
    BigInt(envelope.senderSeq),
    BigInt(envelope.expiresAt),
    fromHex(envelope.bodyHex)
  ]);
}

export function computeMessageIdHex(envelope: SignedBody): string {
  return toHex(blake2b256(signedBodyBytes(envelope)));
}
