/**
 * The host's public app-chain read routes (tip, blocks, messages, the SSE stream), as the explorer reads them. Every
 * response is untrusted node data: it is checked field by field here, so a view never meets a value of the wrong
 * type or an unbounded string. A malformed message inside a block is kept (marked malformed) rather than dropped, so
 * the explorer shows it as an unknown message instead of hiding it.
 */

/** The node's default max-message-bytes; a body above it is not decoded. */
export const MAX_BODY_BYTES = 64 * 1024;
/** At most this many messages of one block are read. */
export const MAX_BLOCK_MESSAGES = 10_000;
const MAX_TOPIC = 256;

export interface Tip {
  height: number;
  stateRoot: string;
}

export interface BlockSummary {
  height: number;
  timestamp: number;
  stateRoot: string;
  messageCount: number;
  certSignatures: number;
}

export interface BlockPage {
  tipHeight: number;
  blocks: BlockSummary[];
}

export interface ChainMessage {
  messageId: string;
  topic: string;
  sender: string;
  senderSeq: string;
  bodyHex: string;
  /** The body's size in bytes as the node reported it (kept for an oversized body, which is not decoded). */
  size: number;
  height: number;
  index: number;
  /** Why the node's message entry was not well formed; its body is not decoded then. */
  malformed?: string;
}

export interface Block {
  height: number;
  chainId: string;
  prevHash: string;
  timestamp: number;
  messagesRoot: string;
  stateRoot: string;
  proposer: string;
  certSignatures: number;
  messages: ChainMessage[];
}

export interface MessageRef {
  height: number;
  index: number;
  messageId: string;
}

export class MalformedResponse extends Error {
  constructor(what: string) {
    super(`the node returned a malformed ${what}`);
  }
}

type Json = Record<string, unknown>;

function object(value: unknown, what: string): Json {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) throw new MalformedResponse(what);
  return value as Json;
}

export function isCount(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0;
}

function count(value: unknown, what: string): number {
  if (!isCount(value)) throw new MalformedResponse(what);
  return value;
}

export function isHex(value: unknown, bytes?: number): value is string {
  return typeof value === 'string' && value.length % 2 === 0 && /^[0-9a-f]*$/.test(value)
    && (bytes === undefined || value.length === 2 * bytes);
}

function hex(value: unknown, what: string, bytes?: number): string {
  if (!isHex(value, bytes)) throw new MalformedResponse(what);
  return value;
}

/** A short display string: anything that is not a string becomes empty, a long one is cut. */
function text(value: unknown, max = MAX_TOPIC): string {
  return typeof value === 'string' ? value.slice(0, max) : '';
}

export function parseTip(raw: unknown): Tip {
  const body = object(raw, 'tip');
  return { height: count(body.height, 'tip height'), stateRoot: hex(body.stateRoot, 'tip state root', 32) };
}

export function parseBlockSummary(raw: unknown): BlockSummary {
  const body = object(raw, 'block summary');
  return {
    height: count(body.height, 'block height'),
    timestamp: count(body.timestamp, 'block timestamp'),
    stateRoot: hex(body.stateRoot, 'block state root', 32),
    messageCount: count(body.messageCount, 'block message count'),
    certSignatures: count(body.certSignatures, 'block signature count'),
  };
}

export function parseBlockPage(raw: unknown): BlockPage {
  const body = object(raw, 'block page');
  if (!Array.isArray(body.blocks) || body.blocks.length > 1_000) throw new MalformedResponse('block page');
  return { tipHeight: count(body.tipHeight, 'tip height'), blocks: body.blocks.map(parseBlockSummary) };
}

/**
 * One message entry. The position comes from the caller when the entry sits in a block (the block's height and the
 * entry's place in its list), so a node cannot place a block's message elsewhere.
 */
export function parseMessage(raw: unknown, position?: { height: number; index: number }): ChainMessage {
  const body = typeof raw === 'object' && raw !== null && !Array.isArray(raw) ? raw as Json : {};
  const height = position?.height ?? body.height;
  const index = position?.index ?? body.index;
  const problems: string[] = [];
  if (!isHex(body.messageId, 32)) problems.push('message id');
  if (typeof body.topic !== 'string' || body.topic.length > MAX_TOPIC) problems.push('topic');
  if (!isHex(body.bodyHex)) problems.push('body');
  else if ((body.bodyHex as string).length > 2 * MAX_BODY_BYTES) problems.push('body size');
  if (!isCount(height) || !isCount(index)) problems.push('position');
  const seq = body.senderSeq;
  return {
    messageId: isHex(body.messageId, 32) ? body.messageId : '',
    topic: text(body.topic),
    sender: isHex(body.sender) ? body.sender.slice(0, 128) : '',
    senderSeq: typeof seq === 'number' || typeof seq === 'string' ? String(seq).slice(0, 40) : '',
    // An oversized body is kept only by its length, for the size shown with an unknown message.
    bodyHex: isHex(body.bodyHex) && body.bodyHex.length <= 2 * MAX_BODY_BYTES ? body.bodyHex : '',
    size: typeof body.bodyHex === 'string' ? Math.floor(body.bodyHex.length / 2) : 0,
    height: isCount(height) ? height : 0,
    index: isCount(index) ? index : 0,
    ...(problems.length ? { malformed: problems.join(', ') } : {}),
  };
}

export function parseBlock(raw: unknown): Block {
  const body = object(raw, 'block');
  const height = count(body.height, 'block height');
  if (!Array.isArray(body.messages)) throw new MalformedResponse('block message list');
  return {
    height,
    chainId: text(body.chainId),
    prevHash: hex(body.prevHash, 'previous block hash', 32),
    timestamp: count(body.timestamp, 'block timestamp'),
    messagesRoot: hex(body.messagesRoot, 'messages root', 32),
    stateRoot: hex(body.stateRoot, 'block state root', 32),
    proposer: hex(body.proposer, 'block proposer'),
    certSignatures: count(body.certSignatures, 'block signature count'),
    messages: body.messages.slice(0, MAX_BLOCK_MESSAGES).map((message, index) => parseMessage(message, { height, index })),
  };
}

/** The order the explorer lists messages in: newest block first, then the block's own order reversed. */
export function newestFirst(left: { height: number; index: number }, right: { height: number; index: number }): number {
  return right.height - left.height || right.index - left.index;
}
