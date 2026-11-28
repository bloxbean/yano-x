/**
 * What the explorer's search box was given: a block height, a 32-byte id (a message id or an intent id), a 28-byte
 * account id (bech32 acct1… / acct_test1…, or hex), or a Cardano address (bech32, or its raw bytes in hex). Parsing is
 * local and refuses an id or address of the other network; resolving an id or an address asks the node (and an
 * address's account id is also derived here and must match, lib/recipient.ts).
 */
import { ApiError } from './api';
import type { Block } from './chain';
import { decodeMessage, type DecodeContext } from './explorer';
import { fromHex, toHex } from './hex';
import { IdError, parseAccountId, parseAddress } from './ids';
import { credentialFromAddress } from './intents';
import { resolveAddress } from './recipient';

export type SearchQuery =
  | { kind: 'height'; height: number }
  | { kind: 'id'; hex: string }
  | { kind: 'account'; accountId: string }
  | { kind: 'address'; addressHex: string }
  | { kind: 'invalid'; reason: string };

function reason(error: unknown): string {
  return error instanceof IdError ? error.message : 'That is not a valid id or address.';
}

export function parseSearch(input: string, networkId: number): SearchQuery {
  const text = input.trim();
  if (!text) return { kind: 'invalid', reason: 'Enter a block height, a transaction or intent id, an account id or an address.' };
  if (text.length > 256) return { kind: 'invalid', reason: 'That is too long for any id or address.' };
  if (/^#?\d+$/.test(text)) {
    const height = Number(text.replace('#', ''));
    return Number.isSafeInteger(height) ? { kind: 'height', height } : { kind: 'invalid', reason: 'That height is too large.' };
  }
  const hex = text.toLowerCase().replace(/^0x/, '');
  if (/^[0-9a-f]+$/.test(hex)) {
    if (hex.length === 64) return { kind: 'id', hex };
    if (hex.length === 56) return { kind: 'account', accountId: hex };
    // A raw Shelley address: 29 bytes (enterprise, reward) or 57 (base); pointer addresses fall in between.
    if (hex.length % 2 === 0 && hex.length >= 58 && hex.length <= 128) {
      try {
        return { kind: 'address', addressHex: toHex(parseAddress(hex, networkId)) };
      } catch (error) {
        return { kind: 'invalid', reason: reason(error) };
      }
    }
    return { kind: 'invalid', reason: 'Hex input must be a 64-character id, a 56-character account id or an address.' };
  }
  if (/^acct(_test)?1/i.test(text)) {
    try {
      return { kind: 'account', accountId: parseAccountId(text, networkId) };
    } catch (error) {
      return { kind: 'invalid', reason: reason(error) };
    }
  }
  if (/^(addr|stake)(_test)?1/i.test(text)) {
    try {
      return { kind: 'address', addressHex: toHex(parseAddress(text, networkId)) };
    } catch (error) {
      return { kind: 'invalid', reason: reason(error) };
    }
  }
  return { kind: 'invalid', reason: 'Not a block height, id, account id or Cardano address.' };
}

/** The node reads a search resolves through (a PaymentsApi). */
export interface SearchApi {
  message(messageId: string): Promise<{ messageId: string }>;
  receipt(intentId: string): Promise<{ found: boolean; receipt?: { height: number } }>;
  block(height: number): Promise<Block>;
  address(addressHex: string): Promise<{ accountId: string; networkMatches: boolean }>;
  /** Read only when the node names another account than the address derives to (its owner must be the key). */
  account?(accountId: string): Promise<{ registered: boolean; owner?: { credentialKind: string; keyHash: string } }>;
}

export type SearchTarget =
  | { page: 'block'; height: number }
  | { page: 'tx'; messageId: string }
  | { page: 'account'; accountId: string };

function notFound(error: unknown): boolean {
  return error instanceof ApiError && error.status === 404;
}

/**
 * Where a query leads. A 32-byte id is a message id, or else an intent id: its receipt names the height, and the
 * message there whose envelope hashes to that id (computed here, not taken from the node) is the one shown.
 */
export async function resolveSearch(api: SearchApi, query: SearchQuery, ctx: DecodeContext): Promise<SearchTarget> {
  switch (query.kind) {
    case 'invalid':
      throw new Error(query.reason);
    case 'height':
      return { page: 'block', height: query.height };
    case 'account':
      return { page: 'account', accountId: query.accountId };
    case 'address': {
      let credential;
      try {
        credential = credentialFromAddress(fromHex(query.addressHex));
      } catch (error) {
        throw new Error(`This address has no key that can own an account (${error instanceof Error ? error.message : 'unsupported'}).`);
      }
      // The account id is derived here too; a node that names another account is followed only if that account's
      // current owner is this address's key.
      const resolved = await resolveAddress(api, query.addressHex, credential);
      return { page: 'account', accountId: resolved.accountId };
    }
    case 'id': {
      try {
        const message = await api.message(query.hex);
        if (message.messageId === query.hex) return { page: 'tx', messageId: query.hex };
      } catch (error) {
        if (!notFound(error)) throw error;
      }
      const receipt = await api.receipt(query.hex);
      const height = receipt.found ? receipt.receipt?.height : undefined;
      if (height !== undefined && Number.isSafeInteger(height) && height > 0) {
        const block = await api.block(height);
        const match = block.messages.map((message) => decodeMessage(message, ctx)).find((tx) => tx.intentId === query.hex);
        if (match?.messageId) return { page: 'tx', messageId: match.messageId };
      }
      throw new Error('No message or intent with this id on the chain.');
    }
  }
}
