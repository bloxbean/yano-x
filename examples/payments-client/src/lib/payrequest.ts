/**
 * Payment request links: a merchant (or anyone) asks for an exact amount of one asset to one account on one chain.
 * The link is only a pre-filled transfer. Paying it runs the ordinary TRANSFER flow, whose review shows the decoded
 * values, so a link can never make the wallet sign anything the review does not show.
 *
 *   <page>#/pay?v=1&chain=<chainId>&to=<recipient>&asset=<lovelace | policyId.assetNameHex>&amount=<base units>&memo=
 *
 * The recipient is the requester's bech32 account id (acct1… / acct_test1…) in links this client makes; a 56-hex
 * account id (older links) or a Cardano address on the chain's network is accepted too, and an address is resolved
 * to its account before the review, like a typed Send recipient. The amount is in base units, so a link does not
 * depend on the node's display decimals; the asset is the full AssetId, never a ticker. The memo is the requester's
 * own text: it is shown as unverified and is not signed.
 */
import { IdError, parseRecipient, type Recipient } from './ids';

export interface PayRequestInput {
  chainId: string;
  /** The recipient as the link carries it: a bech32 account id, normally. */
  to: string;
  /** "lovelace" or "<policyId hex>.<assetName hex>". */
  asset: string;
  amount: bigint;
  memo: string;
}

export interface PayRequest extends PayRequestInput {
  /** The parsed recipient: an account id (as hex) or an address whose account is resolved before paying. */
  recipient: Recipient;
}

export const MAX_MEMO = 120;
const MAX_U64 = (1n << 64n) - 1n;

/** The asset id text a request uses for a vault asset. */
export function requestAsset(asset: { ada: boolean; policyId: string; assetName: string }): string {
  return asset.ada ? 'lovelace' : `${asset.policyId}.${asset.assetName}`;
}

export function payRequestLink(base: string, request: PayRequestInput): string {
  const params = new URLSearchParams({ v: '1', chain: request.chainId, to: request.to, asset: request.asset,
    amount: request.amount.toString() });
  if (request.memo) params.set('memo', request.memo.slice(0, MAX_MEMO));
  return `${base.replace(/#.*$/, '')}#/pay?${params}`;
}

/** The query part of a pasted link, a scanned QR payload, or a bare "pay?..." string. */
function queryOf(text: string): string {
  const value = text.trim();
  const hash = value.indexOf('#/pay?');
  if (hash >= 0) return value.slice(hash + '#/pay?'.length);
  if (value.startsWith('/pay?')) return value.slice('/pay?'.length);
  if (value.startsWith('pay?')) return value.slice('pay?'.length);
  if (value.startsWith('?')) return value.slice(1);
  throw new Error('not a payment request link');
}

/**
 * Parses a payment request; throws with the reason when it is malformed. The recipient is checked against the chain's
 * network (its address network id): an id or address of the other network is refused.
 */
export function parsePayRequest(text: string, networkId: number): PayRequest {
  const params = new URLSearchParams(queryOf(text));
  if (params.get('v') !== '1') throw new Error('unsupported payment request version');
  const chainId = params.get('chain') ?? '';
  if (!chainId) throw new Error('the request names no chain');
  const to = (params.get('to') ?? '').trim();
  let recipient: Recipient;
  try {
    recipient = parseRecipient(to, networkId);
  } catch (error) {
    throw new Error(`the request's recipient is not an account id or address: ${error instanceof IdError
      ? error.message : 'unreadable'}`);
  }
  const asset = (params.get('asset') ?? '').toLowerCase();
  if (asset !== 'lovelace' && !/^[0-9a-f]{56}\.([0-9a-f]{2}){0,32}$/.test(asset)) {
    throw new Error('the request\'s asset is not an asset id');
  }
  const amountText = params.get('amount') ?? '';
  if (!/^[1-9][0-9]*$/.test(amountText)) throw new Error('the request\'s amount is not a positive integer');
  const amount = BigInt(amountText);
  if (amount > MAX_U64) throw new Error('the request\'s amount is too large');
  const memo = (params.get('memo') ?? '').slice(0, MAX_MEMO);
  return { chainId, to, recipient, asset, amount, memo };
}
