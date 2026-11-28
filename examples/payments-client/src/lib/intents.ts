/**
 * Client-side encoder for asset-payments intents (ADR-059 D13 §3, D14). Every function mirrors one Java codec
 * byte for byte and is tested against ledgers/asset-payments/ledger/src/test/resources/asset-payments/
 * intent-vectors-v1.json, so the wallet signs exactly what the ledger verifies:
 *
 *   IntentEnvelopeV1   ["yano-intent-v1", network_magic, address_network_id, chain_id, genesis_id, application_id,
 *                       action, account_id(28), authorizer, nonce(16), not_after_ms, body]
 *   authorizer         [0] (owner) / [1, grant_id(32)] (session)
 *   IntentId           blake2b-256("yano-asset-gateway-intent-v1\0" || envelope bytes)
 *   AccountId          blake2b-224("acct\0" || credential kind || key hash)
 *   PaymentsMessageV1  [1, 0, cose_sign1, cose_key] / [1, 1, envelope, signature(64)]
 */
import { blake2b } from '@noble/hashes/blake2.js';
import { concat, encode, type CborValue } from './cbor';
import { fromHex, toHex } from './hex';

export const INTENT_TAG = 'yano-intent-v1';
export const INTENT_TOPIC = 'asset-payments.intents';
export const ACTIONS = [
  'REGISTER_ACCOUNT', 'AUTHORIZE_SESSION', 'REVOKE_SESSION', 'ROTATE_OWNER', 'TRANSFER', 'REQUEST_WITHDRAWAL'
] as const;
export type Action = (typeof ACTIONS)[number];

export interface IntentDomain {
  networkMagic: number;
  addressNetworkId: number;
  chainId: string;
  genesisId: string;
  applicationId: string;
}

export type Authorizer = { kind: 'OWNER' } | { kind: 'SESSION'; grantId: string };

export interface AssetRef {
  policyId: string;
  assetName: string;
}

export interface Envelope {
  domain: IntentDomain;
  action: Action;
  accountId: string;
  authorizer: Authorizer;
  nonce: string;
  notAfterMs: bigint | number;
  body: Uint8Array;
}

const ascii = new TextEncoder();
const INTENT_ID_TAG = ascii.encode('yano-asset-gateway-intent-v1\0');
const ACCOUNT_TAG = ascii.encode('acct\0');

export function encodeEnvelope(envelope: Envelope): Uint8Array {
  const { domain } = envelope;
  const who: CborValue[] = envelope.authorizer.kind === 'SESSION'
    ? [1, fromHex(envelope.authorizer.grantId, 32)]
    : [0];
  return encode([
    INTENT_TAG,
    BigInt(domain.networkMagic),
    domain.addressNetworkId,
    domain.chainId,
    fromHex(domain.genesisId, 32),
    domain.applicationId,
    envelope.action,
    fromHex(envelope.accountId, 28),
    who,
    fromHex(envelope.nonce, 16),
    BigInt(envelope.notAfterMs),
    envelope.body
  ]);
}

export function intentId(envelopeBytes: Uint8Array): string {
  return toHex(blake2b(concat(INTENT_ID_TAG, envelopeBytes), { dkLen: 32 }));
}

// ------------------------------------------------------------------ action bodies (PaymentsBodies)

export const registerBody = (): Uint8Array => encode([]);

export function transferBody(asset: AssetRef, toAccount: string, amount: bigint): Uint8Array {
  if (amount <= 0n) throw new Error('a transfer amount is positive');
  return encode([[fromHex(asset.policyId), fromHex(asset.assetName)], fromHex(toAccount, 28), amount]);
}

export interface SessionLimit {
  vaultId: string;
  perPayment: bigint;
  cumulative: bigint;
}

export function authorizeSessionBody(sessionKey: Uint8Array, actions: string[], limits: SessionLimit[],
                                     recipients: string[], notAfterMs: bigint | number): Uint8Array {
  if (sessionKey.length !== 32) throw new Error('a session key is 32 bytes');
  for (const limit of limits) {
    if (limit.perPayment <= 0n || limit.cumulative <= 0n || limit.perPayment > limit.cumulative) {
      throw new Error('limits are positive and the per-payment limit is at most the cumulative one');
    }
  }
  return encode([
    sessionKey,
    actions,
    limits.map((limit) => [fromHex(limit.vaultId, 32), limit.perPayment, limit.cumulative]),
    recipients.map((recipient) => fromHex(recipient, 28)),
    BigInt(notAfterMs)
  ]);
}

export const revokeSessionBody = (grantId: string): Uint8Array => encode([fromHex(grantId, 32)]);

export function requestWithdrawalBody(vaultId: string, recipient: Uint8Array, amount: bigint,
                                      vaultLovelace: bigint): Uint8Array {
  if (recipient.length < 1 || recipient.length > 128) throw new Error('a recipient address is 1-128 bytes');
  if (amount === 0n && vaultLovelace === 0n) throw new Error('a withdrawal pays something');
  return encode([fromHex(vaultId, 32), recipient, amount, vaultLovelace]);
}

/** {@code [credential_kind, key_hash]}, WalletAuthorizer.rotationBody. */
export function rotateOwnerBody(credentialKind: number, keyHash: string): Uint8Array {
  return encode([credentialKind, fromHex(keyHash, 28)]);
}

/** The body for the JSON form the domain API's intents/encode route accepts (PaymentsIntentJson). */
export function bodyFromJson(action: Action, json: Record<string, unknown>): Uint8Array {
  const text = (key: string): string => String(json[key]);
  switch (action) {
    case 'REGISTER_ACCOUNT':
      return registerBody();
    case 'TRANSFER':
      return transferBody(json.asset as AssetRef, text('toAccount'), BigInt(text('amount')));
    case 'AUTHORIZE_SESSION':
      return authorizeSessionBody(fromHex(text('sessionKey'), 32), json.actions as string[],
        (json.limits as Record<string, string>[]).map((limit) => ({
          vaultId: limit.vaultId, perPayment: BigInt(limit.perPayment), cumulative: BigInt(limit.cumulative)
        })), json.recipients as string[], BigInt(json.notAfterMs as number));
    case 'REVOKE_SESSION':
      return revokeSessionBody(text('grantId'));
    case 'REQUEST_WITHDRAWAL':
      return requestWithdrawalBody(text('vaultId'), fromHex(text('recipient')), BigInt(text('amount')),
        BigInt(text('vaultLovelace')));
    case 'ROTATE_OWNER': {
      const credential = credentialFromAddress(fromHex(text('address')));
      return rotateOwnerBody(credential.kind, credential.keyHash);
    }
  }
}

// ------------------------------------------------------------------ credentials (CardanoCredential)

export const PAYMENT_KEY = 0;
export const STAKE_KEY = 1;

export interface Credential {
  kind: number;
  keyHash: string;
  addressNetworkId: number;
  accountId: string;
}

/**
 * The credential CIP-30 signs with for a raw Shelley address (CIP-0030 l.354): the payment key for base (types 0,
 * 2), pointer (4) and enterprise (6) addresses, the stake key for a reward address (14). Script payment parts and
 * Byron addresses have no key a wallet signs with and are refused.
 */
export function credentialFromAddress(address: Uint8Array): Credential {
  if (address.length < 29) throw new Error('address too short');
  const type = address[0] >> 4;
  const network = address[0] & 0x0f;
  let kind: number;
  if (type === 0 || type === 2) {
    if (address.length !== 57) throw new Error('address length does not match its type');
    kind = PAYMENT_KEY;
  } else if (type === 6) {
    if (address.length !== 29) throw new Error('address length does not match its type');
    kind = PAYMENT_KEY;
  } else if (type === 4) {
    requirePointer(address);
    kind = PAYMENT_KEY;
  } else if (type === 14) {
    if (address.length !== 29) throw new Error('address length does not match its type');
    kind = STAKE_KEY;
  } else {
    throw new Error(`address type ${type} has no key credential a CIP-30 wallet signs with`);
  }
  const keyHash = address.slice(1, 29);
  return { kind, keyHash: toHex(keyHash), addressNetworkId: network, accountId: accountId(kind, keyHash) };
}

function requirePointer(address: Uint8Array): void {
  let offset = 29;
  for (let i = 0; i < 3; i++) {
    let read = 0;
    for (;;) {
      if (offset >= address.length || ++read > 10) throw new Error('malformed pointer address');
      if ((address[offset++] & 0x80) === 0) break;
    }
  }
  if (offset !== address.length) throw new Error('malformed pointer address');
}

export function accountId(kind: number, keyHash: Uint8Array): string {
  return toHex(blake2b(concat(ACCOUNT_TAG, Uint8Array.of(kind), keyHash), { dkLen: 28 }));
}

// ------------------------------------------------------------------ the ledger message (PaymentsMessageV1)

export function ownerMessage(coseSign1: Uint8Array, coseKey: Uint8Array): Uint8Array {
  return encode([1, 0, coseSign1, coseKey]);
}

export function sessionMessage(envelopeBytes: Uint8Array, signature: Uint8Array): Uint8Array {
  if (signature.length !== 64) throw new Error('an Ed25519 signature is 64 bytes');
  return encode([1, 1, envelopeBytes, signature]);
}
