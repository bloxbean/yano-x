/**
 * The explorer's view of a finalized message: a strict, bounded decoder for the asset-payments ledger's message
 * forms, and the checks that bind a receipt or deposit record to the message it is shown with.
 *
 *   asset-payments.intents        PaymentsMessageV1 [1, 0, cose_sign1, cose_key] / [1, 1, envelope, signature]
 *                                 / [1, 2, current_sign1, current_key, new_sign1, new_key], over IntentEnvelopeV1
 *   ~l1/<observer>                L1Observation [1, observer_id, [0, tx_id] / [1, epoch], ordinal, slot, block_hash,
 *                                 claim], the claim an AssetDepositClaimV1 or a PayoutObservationV1
 *   ~governance/asset-payments    PaymentsGovernanceV1 [1, sequence, [0, vault_id, status] / [1] / [2]]
 *
 * Every input is node data. A body is decoded only up to MAX_BODY_BYTES and MAX_DEPTH, every error is caught, and
 * a message that does not decode is an "unknown message" with its topic and size. Nothing decoded here is verified:
 * only a state proof of the receipt or deposit record makes an outcome verified, and the explorer says so.
 */
import { readAll, type Item } from './cbor';
import { MAX_BODY_BYTES, type ChainMessage } from './chain';
import { parseCoseSign1 } from './cose';
import { describeCode } from './errors';
import { fromHex, toHex } from './hex';
import { ACTIONS, INTENT_TAG, INTENT_TOPIC, intentId as computeIntentId, type IntentDomain } from './intents';
import type { Fact } from './proof';

/** Deeper than any form above (the session limits are three levels down). */
export const MAX_DEPTH = 8;
export const GOVERNANCE_TOPIC = '~governance/asset-payments';
export const ASSET_STATUSES = ['ACTIVE', 'DEPOSITS_PAUSED', 'TRANSFERS_PAUSED', 'WIND_DOWN', 'RETIRED'] as const;
export const DEPOSIT_STATUSES = ['CREDITED', 'UNCREDITED', 'UNATTRIBUTED', 'OPERATIONAL'] as const;
export const DEPOSIT_REASONS = ['NONE', 'NOT_A_DEPOSIT', 'FLOOR_TOP_UP', 'DATUM_MALFORMED', 'UNKNOWN_VAULT',
  'DEPOSITS_PAUSED', 'UNREGISTERED_ASSET', 'MIXED_ASSETS', 'ZERO_AMOUNT', 'UNKNOWN_BENEFICIARY', 'BALANCE_OVERFLOW',
  'INCONSISTENT_VALUE'] as const;

export type TxKind = 'register' | 'transfer' | 'session-pay' | 'session-authorize' | 'revoke' | 'withdraw' | 'rotate'
  | 'deposit' | 'payout' | 'observation' | 'governance' | 'unknown';

export const KIND_LABELS: Record<TxKind, string> = {
  register: 'Register', transfer: 'Transfer', 'session-pay': 'Session pay', 'session-authorize': 'Session authorize',
  revoke: 'Revoke session', withdraw: 'Withdraw', rotate: 'Rotate owner', deposit: 'Deposit (L1)',
  payout: 'Payout (L1)', observation: 'L1 observation', governance: 'Governance', unknown: 'Unknown message',
};

export interface SessionTerms {
  sessionKey: string;
  actions: string[];
  limits: { vaultId: string; perPayment: bigint; cumulative: bigint }[];
  recipients: string[];
  notAfterMs: bigint;
}

export interface DecodedTx {
  /** `${height}:${index}`, unique on the chain. */
  key: string;
  messageId: string;
  height: number;
  index: number;
  topic: string;
  sender: string;
  senderSeq: string;
  size: number;
  bodyHex: string;
  kind: TxKind;
  /** Why the message, or the action body inside a decoded envelope, did not decode. */
  error?: string;
  /** The account that signed an intent. */
  from?: string;
  /** The receiving account: a transfer's recipient, a deposit's beneficiary. */
  to?: string;
  /** The L1 address (raw hex) a withdrawal pays. */
  toAddress?: string;
  /** A transfer names its asset by id; withdrawals, deposits and payouts name the vault. */
  asset?: { policyId: string; assetName: string };
  vaultId?: string;
  /** Base units of the asset (lovelace for ADA). */
  amount?: bigint;
  /** Lovelace that travels with a token: a withdrawal's vault lovelace, a deposit output's lovelace. */
  lovelace?: bigint;
  // Intents
  intentId?: string;
  action?: string;
  authorizer?: 'OWNER' | 'SESSION';
  grantId?: string;
  nonce?: string;
  notAfterMs?: bigint;
  domain?: IntentDomain;
  envelopeHex?: string;
  session?: SessionTerms;
  newOwner?: { credentialKind: number; keyHash: string };
  // L1 observations
  observerId?: string;
  l1TxId?: string;
  outputIndex?: number;
  slot?: bigint;
  l1BlockHash?: string;
  depositId?: string;
  deposit?: { shape: number; beneficiary: 'ACCOUNT' | 'MALFORMED' | 'NONE'; otherAssetCount: bigint };
  payout?: { claimIds: string[] };
  epoch?: bigint;
  // Governance
  governance?: { sequence: bigint; command: 'ASSET_STATUS' | 'PAUSE' | 'RESUME'; vaultId?: string; status?: string };
}

/** What the decoder needs to know about the chain: its topics. */
export interface DecodeContext {
  intentTopic: string;
  depositTopic?: string;
  payoutTopic?: string;
}

export function contextOf(info: { intentTopic?: string; depositObserverId?: string; payoutObserverId?: string }):
  DecodeContext {
  return {
    intentTopic: info.intentTopic || INTENT_TOPIC,
    depositTopic: info.depositObserverId ? `~l1/${info.depositObserverId}` : undefined,
    payoutTopic: info.payoutObserverId ? `~l1/${info.payoutObserverId}` : undefined,
  };
}

// ------------------------------------------------------------------ strict item readers

class Malformed extends Error {}

function fail(what: string): never {
  throw new Malformed(what);
}

/** Throws when {@code item} nests deeper than MAX_DEPTH (checked level by level, so a wide item costs no stack). */
function checkDepth(item: Item, level = 0): void {
  if (level > MAX_DEPTH) fail('nested too deeply');
  if (item.kind === 'array') for (const child of item.items) checkDepth(child, level + 1);
  else if (item.kind === 'map') for (const [key, value] of item.entries) {
    checkDepth(key, level + 1);
    checkDepth(value, level + 1);
  } else if (item.kind === 'tag') checkDepth(item.item, level + 1);
}

/** One CBOR item spanning all of {@code bytes}, within the size and depth bounds. */
export function readBounded(bytes: Uint8Array): Item {
  if (bytes.length > MAX_BODY_BYTES) fail(`larger than ${MAX_BODY_BYTES} bytes`);
  let item: Item;
  try {
    item = readAll(bytes);
  } catch (error) {
    fail(`not CBOR (${error instanceof Error ? error.message : 'unreadable'})`);
  }
  checkDepth(item);
  return item;
}

function array(item: Item | undefined, min: number, max: number, what: string): Item[] {
  if (!item || item.kind !== 'array' || item.items.length < min || item.items.length > max) fail(what);
  return item.items;
}

function uint(item: Item | undefined, what: string): bigint {
  if (!item || item.kind !== 'uint') fail(what);
  return item.value;
}

function small(item: Item | undefined, what: string): number {
  const value = uint(item, what);
  if (value > BigInt(Number.MAX_SAFE_INTEGER)) fail(what);
  return Number(value);
}

function bytes(item: Item | undefined, what: string, length?: number): Uint8Array {
  if (!item || item.kind !== 'bytes' || (length !== undefined && item.value.length !== length)) fail(what);
  return item.value;
}

function text(item: Item | undefined, what: string, max = 64): string {
  if (!item || item.kind !== 'text' || item.value.length > max) fail(what);
  return item.value;
}

// ------------------------------------------------------------------ intents

interface Envelope {
  domain: IntentDomain;
  action: string;
  accountId: string;
  authorizer: 'OWNER' | 'SESSION';
  grantId?: string;
  nonce: string;
  notAfterMs: bigint;
  body: Uint8Array;
}

export function decodeEnvelope(envelope: Uint8Array): Envelope {
  const items = array(readBounded(envelope), 12, 12, 'the intent envelope is not a 12-element array');
  if (text(items[0], 'intent tag') !== INTENT_TAG) fail('not a yano-intent-v1 envelope');
  const action = text(items[6], 'intent action');
  if (!(ACTIONS as readonly string[]).includes(action)) fail(`unknown action ${action}`);
  const who = array(items[8], 1, 2, 'intent authorizer');
  const kind = small(who[0], 'intent authorizer');
  if (!((kind === 0 && who.length === 1) || (kind === 1 && who.length === 2))) fail('intent authorizer');
  return {
    domain: {
      networkMagic: small(items[1], 'network magic'),
      addressNetworkId: small(items[2], 'address network id'),
      chainId: text(items[3], 'chain id', 128),
      genesisId: toHex(bytes(items[4], 'genesis id', 32)),
      applicationId: text(items[5], 'application id', 128),
    },
    action,
    accountId: toHex(bytes(items[7], 'account id', 28)),
    authorizer: kind === 1 ? 'SESSION' : 'OWNER',
    ...(kind === 1 ? { grantId: toHex(bytes(who[1], 'grant id', 32)) } : {}),
    nonce: toHex(bytes(items[9], 'nonce', 16)),
    notAfterMs: uint(items[10], 'expiry'),
    body: bytes(items[11], 'intent body'),
  };
}

/** The envelope a COSE_Sign1 signed: its payload, which must be the envelope itself (not its hash). */
function signedEnvelope(coseSign1: Uint8Array): Uint8Array {
  let cose;
  try {
    cose = parseCoseSign1(coseSign1);
  } catch (error) {
    fail(`the COSE_Sign1 is malformed (${error instanceof Error ? error.message : 'unreadable'})`);
  }
  if (cose.hashed === true || !cose.payload) fail('the COSE_Sign1 carries no plain payload');
  return cose.payload;
}

function assetOf(item: Item | undefined): { policyId: string; assetName: string } {
  const pair = array(item, 2, 2, 'asset id');
  return { policyId: toHex(bytes(pair[0], 'policy id')), assetName: toHex(bytes(pair[1], 'asset name')) };
}

/** The action body's fields; a body that does not decode leaves the envelope's fields and sets an error. */
function bodyFields(action: string, body: Uint8Array): Partial<DecodedTx> {
  const items = (min: number, max: number) => array(readBounded(body), min, max, `the ${action} body is malformed`);
  switch (action) {
    case 'REGISTER_ACCOUNT':
      items(0, 0);
      return {};
    case 'TRANSFER': {
      const [asset, to, amount] = items(3, 3);
      return { asset: assetOf(asset), to: toHex(bytes(to, 'recipient account', 28)), amount: uint(amount, 'amount') };
    }
    case 'AUTHORIZE_SESSION': {
      const [key, actions, limits, recipients, notAfter] = items(5, 5);
      return {
        session: {
          sessionKey: toHex(bytes(key, 'session key', 32)),
          actions: array(actions, 0, 16, 'session actions').map((entry) => text(entry, 'session action')),
          limits: array(limits, 0, 64, 'session limits').map((entry) => {
            const [vault, per, total] = array(entry, 3, 3, 'session limit');
            return { vaultId: toHex(bytes(vault, 'limit vault', 32)), perPayment: uint(per, 'per-payment limit'),
              cumulative: uint(total, 'cumulative limit') };
          }),
          recipients: array(recipients, 0, 256, 'session recipients')
            .map((entry) => toHex(bytes(entry, 'session recipient', 28))),
          notAfterMs: uint(notAfter, 'session expiry'),
        },
      };
    }
    case 'REVOKE_SESSION':
      return { grantId: toHex(bytes(items(1, 1)[0], 'grant id', 32)) };
    case 'REQUEST_WITHDRAWAL': {
      const [vault, recipient, amount, lovelace] = items(4, 4);
      const address = bytes(recipient, 'withdrawal address');
      if (address.length < 1 || address.length > 128) fail('withdrawal address');
      return { vaultId: toHex(bytes(vault, 'vault id', 32)), toAddress: toHex(address), amount: uint(amount, 'amount'),
        lovelace: uint(lovelace, 'vault lovelace') };
    }
    case 'ROTATE_OWNER': {
      const [kind, keyHash] = items(2, 2);
      return { newOwner: { credentialKind: small(kind, 'credential kind'), keyHash: toHex(bytes(keyHash, 'key hash', 28)) } };
    }
    default:
      fail(`unknown action ${action}`);
  }
}

function intentKind(action: string, authorizer: 'OWNER' | 'SESSION'): TxKind {
  switch (action) {
    case 'REGISTER_ACCOUNT': return 'register';
    case 'TRANSFER': return authorizer === 'SESSION' ? 'session-pay' : 'transfer';
    case 'AUTHORIZE_SESSION': return 'session-authorize';
    case 'REVOKE_SESSION': return 'revoke';
    case 'REQUEST_WITHDRAWAL': return 'withdraw';
    case 'ROTATE_OWNER': return 'rotate';
    default: return 'unknown';
  }
}

function decodeIntent(body: Uint8Array): Partial<DecodedTx> {
  const items = array(readBounded(body), 4, 6, 'not a payments message');
  if (uint(items[0], 'payments message version') !== 1n) fail('unknown payments message version');
  const form = uint(items[1], 'payments message kind');
  let envelopeBytes: Uint8Array;
  if (form === 0n && items.length === 4) {
    envelopeBytes = signedEnvelope(bytes(items[2], 'COSE_Sign1'));
    bytes(items[3], 'COSE_Key');
  } else if (form === 1n && items.length === 4) {
    envelopeBytes = bytes(items[2], 'envelope');
    bytes(items[3], 'session signature', 64);
  } else if (form === 2n && items.length === 6) {
    envelopeBytes = signedEnvelope(bytes(items[2], 'current owner COSE_Sign1'));
    for (const i of [3, 4, 5]) bytes(items[i], 'rotation part');
  } else {
    fail('unknown payments message kind');
  }
  const envelope = decodeEnvelope(envelopeBytes);
  if ((form === 1n) !== (envelope.authorizer === 'SESSION')) fail('the signature form does not match the authorizer');
  const fields: Partial<DecodedTx> = {
    kind: intentKind(envelope.action, envelope.authorizer),
    intentId: computeIntentId(envelopeBytes),
    envelopeHex: toHex(envelopeBytes),
    action: envelope.action,
    from: envelope.accountId,
    authorizer: envelope.authorizer,
    ...(envelope.grantId ? { grantId: envelope.grantId } : {}),
    nonce: envelope.nonce,
    notAfterMs: envelope.notAfterMs,
    domain: envelope.domain,
  };
  try {
    return { ...fields, ...bodyFields(envelope.action, envelope.body) };
  } catch (error) {
    // The ledger rejects such an intent (BAD_BODY); the envelope is still worth showing.
    return { ...fields, error: error instanceof Error ? error.message : 'the action body is malformed' };
  }
}

// ------------------------------------------------------------------ L1 observations

function decodeDepositClaim(items: Item[]): Partial<DecodedTx> {
  // [1, deposit_id, vault_id, tx_id, output_index, slot, block_hash, shape, beneficiary, registered_quantity,
  //  lovelace, other_asset_count, value_hash]
  const beneficiary = array(items[8], 1, 2, 'deposit beneficiary');
  const tag = small(beneficiary[0], 'deposit beneficiary');
  let account: string | undefined;
  if (tag === 0 && beneficiary.length === 2) account = toHex(bytes(beneficiary[1], 'beneficiary account', 28));
  else if (!((tag === 1 && beneficiary.length === 2) || (tag === 2 && beneficiary.length === 1))) {
    fail('deposit beneficiary');
  }
  bytes(items[12], 'deposit value hash', 32);
  return {
    kind: 'deposit',
    depositId: toHex(bytes(items[1], 'deposit id', 32)),
    vaultId: toHex(bytes(items[2], 'vault id', 32)),
    l1TxId: toHex(bytes(items[3], 'L1 transaction id', 32)),
    outputIndex: small(items[4], 'output index'),
    ...(account ? { to: account } : {}),
    deposit: { shape: small(items[7], 'output shape'), beneficiary: tag === 0 ? 'ACCOUNT' : tag === 1 ? 'MALFORMED'
      : 'NONE', otherAssetCount: uint(items[11], 'other asset count') },
    amount: uint(items[9], 'registered quantity'),
    lovelace: uint(items[10], 'lovelace'),
  };
}

function decodePayout(items: Item[]): Partial<DecodedTx> {
  // [1, 0, domain_id, vault_id, tx_id, slot, block_hash, shard_index, shard_output_index, source, [+ claim_id],
  //  [+ payout], summary]
  return {
    kind: 'payout',
    vaultId: toHex(bytes(items[3], 'vault id', 32)),
    l1TxId: toHex(bytes(items[4], 'L1 transaction id', 32)),
    payout: { claimIds: array(items[10], 1, 256, 'payout claim ids').map((id) => toHex(bytes(id, 'claim id', 32))) },
  };
}

function decodeObservation(body: Uint8Array, ctx: DecodeContext, topic: string): Partial<DecodedTx> {
  const items = array(readBounded(body), 7, 7, 'not an L1 observation');
  if (uint(items[0], 'observation version') !== 1n) fail('unknown observation version');
  const anchor = array(items[2], 2, 2, 'observation anchor');
  const anchorTag = small(anchor[0], 'observation anchor');
  const fields: Partial<DecodedTx> = {
    kind: 'observation',
    observerId: text(items[1], 'observer id', 128),
    slot: uint(items[4], 'slot'),
    l1BlockHash: toHex(bytes(items[5], 'L1 block hash')),
  };
  if (anchorTag === 0) fields.l1TxId = toHex(bytes(anchor[1], 'anchor transaction', 32));
  else if (anchorTag === 1) fields.epoch = uint(anchor[1], 'anchor epoch');
  else fail('unknown observation anchor');
  uint(items[3], 'event ordinal');
  const claimBytes = bytes(items[6], 'observation claim');
  let claim: Item[];
  try {
    claim = array(readBounded(claimBytes), 1, 32, 'claim');
  } catch {
    return fields;
  }
  // Only this chain's deposit and payout topics carry the claims the ledger acts on.
  if (topic === ctx.depositTopic && claim.length === 13 && claim[1]?.kind === 'bytes') {
    const deposit = decodeDepositClaim(claim);
    if (fields.l1TxId !== deposit.l1TxId) fail("the deposit claim's transaction is not the observation's anchor");
    return { ...fields, ...deposit };
  }
  if (topic === ctx.payoutTopic && claim.length === 13 && claim[1]?.kind === 'uint') {
    return { ...fields, ...decodePayout(claim) };
  }
  return fields;
}

// ------------------------------------------------------------------ governance

function decodeGovernance(body: Uint8Array): Partial<DecodedTx> {
  const items = array(readBounded(body), 3, 3, 'not a governance command');
  if (uint(items[0], 'governance version') !== 1n) fail('unknown governance version');
  const action = array(items[2], 1, 3, 'governance action');
  const code = small(action[0], 'governance action');
  const sequence = uint(items[1], 'governance sequence');
  if (code === 0 && action.length === 3) {
    const status = small(action[2], 'asset status');
    if (status >= ASSET_STATUSES.length) fail('unknown asset status');
    return { kind: 'governance', vaultId: toHex(bytes(action[1], 'vault id', 32)),
      governance: { sequence, command: 'ASSET_STATUS', vaultId: toHex(bytes(action[1], 'vault id', 32)),
        status: ASSET_STATUSES[status] } };
  }
  if ((code === 1 || code === 2) && action.length === 1) {
    return { kind: 'governance', governance: { sequence, command: code === 1 ? 'PAUSE' : 'RESUME' } };
  }
  fail('unknown governance action');
}

// ------------------------------------------------------------------ entry point

/** Decodes one finalized message. Never throws: anything it cannot read is an unknown message with the reason. */
export function decodeMessage(message: ChainMessage, ctx: DecodeContext): DecodedTx {
  const base: DecodedTx = {
    key: `${message.height}:${message.index}`, messageId: message.messageId, height: message.height,
    index: message.index, topic: message.topic, sender: message.sender, senderSeq: message.senderSeq,
    size: message.size, bodyHex: message.bodyHex, kind: 'unknown',
  };
  if (message.malformed) return { ...base, error: `the node's message entry is malformed (${message.malformed})` };
  if (message.size > MAX_BODY_BYTES) return { ...base, error: `larger than ${MAX_BODY_BYTES} bytes` };
  try {
    const body = fromHex(message.bodyHex);
    if (message.topic === ctx.intentTopic) return { ...base, ...decodeIntent(body) };
    if (message.topic.startsWith('~l1/')) return { ...base, ...decodeObservation(body, ctx, message.topic) };
    if (message.topic === GOVERNANCE_TOPIC) return { ...base, ...decodeGovernance(body) };
    return { ...base, error: 'not a topic this ledger reads' };
  } catch (error) {
    return { ...base, kind: 'unknown', error: error instanceof Malformed || error instanceof Error ? error.message
      : 'undecodable' };
  }
}

/** Whether a decoded message names an account: as signer, recipient, beneficiary or session recipient. */
export function involves(tx: DecodedTx, accountId: string): boolean {
  return tx.from === accountId || tx.to === accountId || !!tx.session?.recipients.includes(accountId);
}

// ------------------------------------------------------------------ outcomes bound to the message

const ascii = new TextEncoder();
export const receiptKeyHex = (intentId: string): string => toHex(ascii.encode('ap/receipt/')) + intentId;
export const depositKeyHex = (depositId: string): string => toHex(ascii.encode('ap/deposit/')) + depositId;

export interface ReceiptValue {
  intentId: string;
  accountId: string;
  action: string;
  amount: bigint;
  outcome: 'ACCEPTED' | 'REJECTED';
  reason: string;
  height: number;
}

/** ReceiptV1 [1, intent_id, account_id, action, asset / [], amount, outcome, reason, height] from its state value. */
export function decodeReceiptValue(valueHex: string): ReceiptValue {
  const items = array(readBounded(fromHex(valueHex)), 9, 9, 'the receipt record is malformed');
  if (uint(items[0], 'receipt version') !== 1n) fail('unknown receipt version');
  array(items[4], 0, 2, 'receipt asset');
  const outcome = small(items[6], 'receipt outcome');
  if (outcome > 1) fail('receipt outcome');
  return {
    intentId: toHex(bytes(items[1], 'receipt intent id', 32)),
    accountId: toHex(bytes(items[2], 'receipt account', 28)),
    action: text(items[3], 'receipt action'),
    amount: uint(items[5], 'receipt amount'),
    outcome: outcome === 0 ? 'ACCEPTED' : 'REJECTED',
    reason: text(items[7], 'receipt reason'),
    height: small(items[8], 'receipt height'),
  };
}

export interface DepositValue {
  depositId: string;
  status: string;
  reason: string;
  creditedAmount: bigint;
  creditedLovelace: bigint;
  height: number;
}

/** DepositRecordV1 [1, deposit_id, vault_id, tx_id, index, slot, height, shape, status, reason, …, credited_amount,
 *  credited_lovelace] from its state value. */
export function decodeDepositValue(valueHex: string): DepositValue {
  const items = array(readBounded(fromHex(valueHex)), 17, 17, 'the deposit record is malformed');
  if (uint(items[0], 'deposit record version') !== 1n) fail('unknown deposit record version');
  const status = small(items[8], 'deposit status');
  const reason = small(items[9], 'deposit reason');
  if (status >= DEPOSIT_STATUSES.length || reason >= DEPOSIT_REASONS.length) fail('unknown deposit status');
  return {
    depositId: toHex(bytes(items[1], 'deposit id', 32)),
    status: DEPOSIT_STATUSES[status],
    reason: DEPOSIT_REASONS[reason],
    creditedAmount: uint(items[15], 'credited amount'),
    creditedLovelace: uint(items[16], 'credited lovelace'),
    height: small(items[6], 'deposit height'),
  };
}

export type OutcomeState = 'accepted' | 'rejected' | 'credited' | 'uncredited' | 'none' | 'pending' | 'inconsistent';

export interface Outcome {
  state: OutcomeState;
  /** The ledger's code (a rejection or deposit reason), when there is one. */
  code?: string;
  /** A readable explanation. */
  text: string;
  /** The state fact a Verify check proves: the record's key and value at the view's height and root. */
  fact?: Fact;
}

interface RecordView {
  committed: { height: number; stateRoot: string };
  found: boolean;
  proof: { keyHex: string; valueHex: string | null };
}

function factOfView(view: RecordView): Fact {
  return { keyHex: view.proof.keyHex, valueHex: view.proof.valueHex, height: view.committed.height,
    stateRoot: view.committed.stateRoot };
}

function wellFormed(view: unknown): view is RecordView {
  const v = view as RecordView | undefined;
  return !!v && typeof v === 'object' && typeof v.found === 'boolean' && !!v.committed && !!v.proof
    && Number.isSafeInteger(v.committed.height) && typeof v.committed.stateRoot === 'string'
    && typeof v.proof.keyHex === 'string' && (v.proof.valueHex === null || typeof v.proof.valueHex === 'string');
}

/**
 * The outcome of an intent from its receipt view. The view must be for this intent's receipt key, and the outcome is
 * read from the record value the proof covers (not the view's JSON), whose intent id must be this one. A JSON outcome
 * that disagrees with the value is reported as inconsistent.
 */
export function receiptOutcome(intentId: string, txHeight: number, raw: unknown): Outcome {
  if (!wellFormed(raw)) return { state: 'inconsistent', text: 'The node returned a malformed receipt view.' };
  const view = raw as RecordView & { receipt?: { outcome?: unknown; reason?: unknown } };
  if (view.proof.keyHex !== receiptKeyHex(intentId)) {
    return { state: 'inconsistent', text: 'The node returned the receipt of another intent.' };
  }
  const fact = factOfView(view);
  if (!view.found || view.proof.valueHex === null) {
    if (view.found) return { state: 'inconsistent', text: 'The receipt view has no record value.', fact };
    return view.committed.height < txHeight
      ? { state: 'pending', text: `The node's view (height ${view.committed.height}) is behind this block.` }
      : { state: 'none', text: 'No receipt: the ledger did not execute this intent (unauthenticated or replayed).', fact };
  }
  let value: ReceiptValue;
  try {
    value = decodeReceiptValue(view.proof.valueHex);
  } catch (error) {
    return { state: 'inconsistent', text: `The receipt record does not decode: ${(error as Error).message}.`, fact };
  }
  if (value.intentId !== intentId) return { state: 'inconsistent', text: 'The receipt record names another intent.', fact };
  if (view.receipt && (view.receipt.outcome !== value.outcome || (view.receipt.reason ?? '') !== value.reason)) {
    return { state: 'inconsistent', text: 'The receipt view disagrees with its own record value.', fact };
  }
  if (value.outcome === 'ACCEPTED') return { state: 'accepted', text: `Accepted at height ${value.height}.`, fact };
  return { state: 'rejected', code: value.reason,
    text: describeCode(value.reason) ?? 'The ledger rejected it.', fact };
}

/** The outcome of an observed deposit from its record view, bound to the claim's deposit id the same way. */
export function depositOutcome(depositId: string, txHeight: number, raw: unknown): Outcome {
  if (!wellFormed(raw)) return { state: 'inconsistent', text: 'The node returned a malformed deposit view.' };
  const view = raw as RecordView & { deposit?: { status?: unknown; reason?: unknown } };
  if (view.proof.keyHex !== depositKeyHex(depositId)) {
    return { state: 'inconsistent', text: 'The node returned the record of another deposit.' };
  }
  const fact = factOfView(view);
  if (!view.found || view.proof.valueHex === null) {
    if (view.found) return { state: 'inconsistent', text: 'The deposit view has no record value.', fact };
    return view.committed.height < txHeight
      ? { state: 'pending', text: `The node's view (height ${view.committed.height}) is behind this block.` }
      : { state: 'none', text: 'No deposit record.', fact };
  }
  let value: DepositValue;
  try {
    value = decodeDepositValue(view.proof.valueHex);
  } catch (error) {
    return { state: 'inconsistent', text: `The deposit record does not decode: ${(error as Error).message}.`, fact };
  }
  if (value.depositId !== depositId) return { state: 'inconsistent', text: 'The deposit record names another deposit.', fact };
  if (view.deposit && view.deposit.status !== value.status) {
    return { state: 'inconsistent', text: 'The deposit view disagrees with its own record value.', fact };
  }
  if (value.status === 'CREDITED') return { state: 'credited', text: `Credited at height ${value.height}.`, fact };
  return { state: 'uncredited', code: value.reason === 'NONE' ? value.status : value.reason,
    text: describeCode(value.reason) ?? `The deposit is ${value.status.toLowerCase()}.`, fact };
}
