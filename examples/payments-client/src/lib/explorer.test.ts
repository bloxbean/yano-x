import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import {
  DEPOSIT_ID, DEPOSIT_MESSAGE, DEPOSIT_RECORD, LIVE_ACCOUNT, LIVE_ADA_VAULT, MEMBER, WITHDRAW_INTENT_ID,
  WITHDRAW_MESSAGE, WITHDRAW_RECEIPT
} from '../test/explorer-fixtures';
import { encode, type CborValue } from './cbor';
import { MAX_BODY_BYTES, parseMessage, type ChainMessage } from './chain';
import {
  contextOf, decodeMessage, depositKeyHex, depositOutcome, GOVERNANCE_TOPIC, involves, receiptKeyHex, receiptOutcome,
  type DecodedTx
} from './explorer';
import { fromHex, toHex } from './hex';
import { encodeEnvelope, transferBody, type IntentDomain } from './intents';

const VECTORS = fileURLToPath(new URL(
  '../../../../ledgers/asset-payments/ledger/src/test/resources/asset-payments/intent-vectors-v1.json',
  import.meta.url));
const vectors = JSON.parse(readFileSync(VECTORS, 'utf8')) as {
  domain: IntentDomain;
  intents: { name: string; action: string; accountId: string; authorizer: { kind: string; grantId?: string };
    intentId: string; messageBodyHex: string; body: Record<string, unknown> }[];
};

const CTX = contextOf({ intentTopic: 'asset-payments.intents', depositObserverId: 'deposits', payoutObserverId: 'payouts' });

function msg(topic: string, body: Uint8Array | string, height = 5, index = 0): ChainMessage {
  const bodyHex = typeof body === 'string' ? body : toHex(body);
  return { messageId: 'ab'.repeat(32), topic, sender: MEMBER, senderSeq: '1', bodyHex, size: bodyHex.length / 2,
    height, index };
}

const decode = (message: ChainMessage): DecodedTx => decodeMessage(message, CTX);
const vector = (name: string) => vectors.intents.find((entry) => entry.name === name)!;

describe('decoding intents', () => {
  const expected: Record<string, string> = {
    register: 'register', 'transfer-ada': 'transfer', 'transfer-token-max-u64': 'transfer',
    'authorize-session': 'session-authorize', 'revoke-session': 'revoke', 'request-withdrawal': 'withdraw',
    'rotate-owner': 'rotate', 'session-transfer': 'session-pay',
  };
  for (const entry of vectors.intents) {
    it(`${entry.name}: the Java golden message decodes to ${expected[entry.name]} with its intent id`, () => {
      const tx = decode(msg('asset-payments.intents', entry.messageBodyHex));
      expect(tx.error).toBeUndefined();
      expect(tx.kind).toBe(expected[entry.name]);
      expect(tx.action).toBe(entry.action);
      expect(tx.intentId).toBe(entry.intentId);
      expect(tx.from).toBe(entry.accountId);
      expect(tx.authorizer).toBe(entry.authorizer.kind);
      expect(tx.domain).toEqual(vectors.domain);
    });
  }

  it('reads each action body', () => {
    const transfer = decode(msg('asset-payments.intents', vector('transfer-ada').messageBodyHex));
    expect(transfer.to).toBe(vector('transfer-ada').body.toAccount);
    expect(transfer.amount).toBe(1_500_000n);
    expect(transfer.asset).toEqual({ policyId: '', assetName: '' });
    expect(decode(msg('asset-payments.intents', vector('transfer-token-max-u64').messageBodyHex)).amount)
      .toBe((1n << 64n) - 1n);
    const session = decode(msg('asset-payments.intents', vector('authorize-session').messageBodyHex));
    expect(session.session?.actions).toEqual(['TRANSFER']);
    expect(session.session?.limits[0].perPayment).toBeGreaterThan(0n);
    expect(decode(msg('asset-payments.intents', vector('revoke-session').messageBodyHex)).grantId).toBe('a1'.repeat(32));
    const withdraw = decode(msg('asset-payments.intents', vector('request-withdrawal').messageBodyHex));
    expect(withdraw.toAddress).toBe(vector('request-withdrawal').body.recipient);
    expect(withdraw.vaultId).toBe(vector('request-withdrawal').body.vaultId);
    const pay = decode(msg('asset-payments.intents', vector('session-transfer').messageBodyHex));
    expect(pay.grantId).toBe('a1'.repeat(32));
    expect(pay.amount).toBe(250_000n);
    expect(decode(msg('asset-payments.intents', vector('rotate-owner').messageBodyHex)).newOwner?.keyHash)
      .toBe('8a95c8ed588306ea88860b54eb0c65e77dfab999789cc5e6ca008799');
  });

  it('decodes a withdrawal captured from a devnet chain', () => {
    const tx = decode(WITHDRAW_MESSAGE);
    expect(tx).toMatchObject({ kind: 'withdraw', intentId: WITHDRAW_INTENT_ID, from: LIVE_ACCOUNT,
      vaultId: LIVE_ADA_VAULT, amount: 1_000_000_000n, lovelace: 0n, authorizer: 'OWNER', key: '10:0' });
    expect(tx.toAddress).toMatch(/^00c8c476/);
    expect(involves(tx, LIVE_ACCOUNT)).toBe(true);
  });

  it('keeps the envelope of an intent whose body does not decode (the ledger rejects it as BAD_BODY)', () => {
    const envelope = encodeEnvelope({ domain: vectors.domain, action: 'TRANSFER', accountId: 'cd'.repeat(28),
      authorizer: { kind: 'SESSION', grantId: 'a1'.repeat(32) }, nonce: '00'.repeat(16), notAfterMs: 1n,
      body: encode([1, 2]) });
    const tx = decode(msg('asset-payments.intents', encode([1, 1, envelope, new Uint8Array(64)])));
    expect(tx.kind).toBe('session-pay');
    expect(tx.from).toBe('cd'.repeat(28));
    expect(tx.intentId).toMatch(/^[0-9a-f]{64}$/);
    expect(tx.error).toMatch(/TRANSFER body/);
  });

  it('refuses a session-signed form over an owner envelope', () => {
    const envelope = encodeEnvelope({ domain: vectors.domain, action: 'TRANSFER', accountId: 'cd'.repeat(28),
      authorizer: { kind: 'OWNER' }, nonce: '00'.repeat(16), notAfterMs: 1n,
      body: transferBody({ policyId: '', assetName: '' }, 'ef'.repeat(28), 5n) });
    const tx = decode(msg('asset-payments.intents', encode([1, 1, envelope, new Uint8Array(64)])));
    expect(tx.kind).toBe('unknown');
    expect(tx.error).toMatch(/authorizer/);
  });
});

describe('decoding L1 observations and governance', () => {
  it('decodes a deposit observation captured from a devnet chain', () => {
    const tx = decode(DEPOSIT_MESSAGE);
    expect(tx).toMatchObject({ kind: 'deposit', observerId: 'deposits', depositId: DEPOSIT_ID, vaultId: LIVE_ADA_VAULT,
      to: LIVE_ACCOUNT, amount: 25_000_000n, lovelace: 25_000_000n, outputIndex: 0, slot: 53_388n,
      l1TxId: '3f9db60cb6b5b4d9c0f9d0f45956844d1b3ba30e5718ecee84056ec92e200d05' });
    expect(tx.from).toBeUndefined();
    expect(involves(tx, LIVE_ACCOUNT)).toBe(true);
    expect(involves(tx, 'ee'.repeat(28))).toBe(false);
  });

  it('reads a deposit claim only on the chain\'s deposit topic', () => {
    const tx = decode({ ...DEPOSIT_MESSAGE, topic: '~l1/other' });
    expect(tx.kind).toBe('observation');
    expect(tx.l1TxId).toBe('3f9db60cb6b5b4d9c0f9d0f45956844d1b3ba30e5718ecee84056ec92e200d05');
    expect(tx.depositId).toBeUndefined();
  });

  it('decodes a payout observation', () => {
    const claim = encode([1, 0, fromHex('11'.repeat(32)), fromHex(LIVE_ADA_VAULT), fromHex('22'.repeat(32)), 9,
      fromHex('33'.repeat(32)), 0, 0, 0, [fromHex('44'.repeat(32)), fromHex('55'.repeat(32))], [], []]);
    const body = encode([1, 'payouts', [0, fromHex('22'.repeat(32))], 0, 9, fromHex('33'.repeat(32)), claim]);
    const tx = decode(msg('~l1/payouts', body));
    expect(tx).toMatchObject({ kind: 'payout', vaultId: LIVE_ADA_VAULT, l1TxId: '22'.repeat(32) });
    expect(tx.payout?.claimIds).toEqual(['44'.repeat(32), '55'.repeat(32)]);
  });

  it('decodes governance commands', () => {
    expect(decode(msg(GOVERNANCE_TOPIC, encode([1, 3, [1]]))).governance).toEqual({ sequence: 3n, command: 'PAUSE' });
    expect(decode(msg(GOVERNANCE_TOPIC, encode([1, 4, [2]]))).governance?.command).toBe('RESUME');
    const status = decode(msg(GOVERNANCE_TOPIC, encode([1, 0, [0, fromHex(LIVE_ADA_VAULT), 3]])));
    expect(status).toMatchObject({ kind: 'governance', governance: { command: 'ASSET_STATUS', status: 'WIND_DOWN',
      vaultId: LIVE_ADA_VAULT } });
    expect(decode(msg(GOVERNANCE_TOPIC, encode([1, 0, [0, fromHex(LIVE_ADA_VAULT), 9]]))).kind).toBe('unknown');
  });
});

describe('malformed and hostile input never throws', () => {
  const cases: [string, ChainMessage][] = [
    ['random bytes', msg('asset-payments.intents', 'ff00ff00')],
    ['a truncated message', msg('asset-payments.intents', WITHDRAW_MESSAGE.bodyHex.slice(0, 200))],
    ['trailing bytes', msg('asset-payments.intents', `${WITHDRAW_MESSAGE.bodyHex}00`)],
    ['an empty body', msg('asset-payments.intents', '')],
    ['an array claiming 2^64 items', msg('asset-payments.intents', '9bffffffffffffffff00')],
    ['a string running past the input', msg('~l1/deposits', '5bffffffffffffffff')],
    ['invalid UTF-8 text', msg(GOVERNANCE_TOPIC, '8301628080')],
    ['an unknown message form', msg('asset-payments.intents', encode([1, 7, new Uint8Array(1), new Uint8Array(1)]))],
    ['another version', msg(GOVERNANCE_TOPIC, encode([2, 0, [1]]))],
    ['a topic this ledger does not read', msg('orders', encode([1]))],
  ];
  for (const [name, message] of cases) {
    it(`${name} is an unknown message with a reason`, () => {
      const tx = decode(message);
      expect(tx.kind).toBe('unknown');
      expect(tx.error).toBeTruthy();
      expect(tx.size).toBe(message.size);
    });
  }

  it('refuses CBOR nested deeper than the bound', () => {
    let value: CborValue = 1;
    for (let i = 0; i < 20; i++) value = [value];
    const tx = decode(msg(GOVERNANCE_TOPIC, encode(value)));
    expect(tx.kind).toBe('unknown');
    expect(tx.error).toMatch(/nested too deeply/);
  });

  it('does not decode an oversized body and keeps its size', () => {
    const big = parseMessage({ messageId: 'ab'.repeat(32), topic: 'asset-payments.intents', bodyHex: '00'.repeat(MAX_BODY_BYTES + 1),
      height: 1, index: 0 });
    expect(big.bodyHex).toBe('');
    expect(big.size).toBe(MAX_BODY_BYTES + 1);
    const tx = decode(big);
    expect(tx.kind).toBe('unknown');
    expect(tx.size).toBe(MAX_BODY_BYTES + 1);
  });

  it('keeps a malformed node entry as an unknown message', () => {
    const entry = parseMessage({ messageId: 42, topic: { evil: true }, bodyHex: 'zz' }, { height: 3, index: 1 });
    expect(entry.malformed).toMatch(/message id.*topic.*body/);
    const tx = decode(entry);
    expect(tx).toMatchObject({ kind: 'unknown', height: 3, index: 1, topic: '' });
    expect(tx.error).toMatch(/malformed/);
  });
});

describe('outcomes bound to the message', () => {
  it('reads an accepted receipt from the record value its proof covers', () => {
    const outcome = receiptOutcome(WITHDRAW_INTENT_ID, 10, WITHDRAW_RECEIPT);
    expect(outcome.state).toBe('accepted');
    expect(outcome.fact).toEqual({ keyHex: receiptKeyHex(WITHDRAW_INTENT_ID), valueHex: WITHDRAW_RECEIPT.proof.valueHex,
      height: 10, stateRoot: WITHDRAW_RECEIPT.committed.stateRoot });
  });

  it('refuses a receipt view for another key, another intent, or one that disagrees with its value', () => {
    const other = 'cd'.repeat(32);
    expect(receiptOutcome(other, 10, WITHDRAW_RECEIPT).state).toBe('inconsistent');
    expect(receiptOutcome(other, 10, { ...WITHDRAW_RECEIPT, proof: { ...WITHDRAW_RECEIPT.proof,
      keyHex: receiptKeyHex(other) } }).text).toMatch(/another intent/);
    const lying = { ...WITHDRAW_RECEIPT, receipt: { ...WITHDRAW_RECEIPT.receipt, outcome: 'REJECTED', reason: 'X' } };
    expect(receiptOutcome(WITHDRAW_INTENT_ID, 10, lying).state).toBe('inconsistent');
    expect(receiptOutcome(WITHDRAW_INTENT_ID, 10, { found: 'yes' }).state).toBe('inconsistent');
  });

  it('reports a rejection with its readable reason', () => {
    // [1, intent, account, "TRANSFER", [], 5, outcome 1, "UNKNOWN_RECIPIENT", 9]
    const value = toHex(encode([1, fromHex(WITHDRAW_INTENT_ID), fromHex(LIVE_ACCOUNT), 'TRANSFER', [], 5, 1,
      'UNKNOWN_RECIPIENT', 9]));
    const view = { ...WITHDRAW_RECEIPT, proof: { ...WITHDRAW_RECEIPT.proof, valueHex: value }, receipt: undefined };
    const outcome = receiptOutcome(WITHDRAW_INTENT_ID, 9, view);
    expect(outcome).toMatchObject({ state: 'rejected', code: 'UNKNOWN_RECIPIENT' });
    expect(outcome.text).toMatch(/not registered/);
  });

  it('waits for a view behind the block, and reports no receipt once the view has passed it', () => {
    const absent = { ...WITHDRAW_RECEIPT, found: false, proof: { ...WITHDRAW_RECEIPT.proof, valueHex: null } };
    expect(receiptOutcome(WITHDRAW_INTENT_ID, 11, absent).state).toBe('pending');
    expect(receiptOutcome(WITHDRAW_INTENT_ID, 10, absent).state).toBe('none');
  });

  it('reads a deposit record from its value, bound to the claim\'s deposit id', () => {
    const outcome = depositOutcome(DEPOSIT_ID, 1, DEPOSIT_RECORD);
    expect(outcome).toMatchObject({ state: 'uncredited', code: 'UNKNOWN_BENEFICIARY' });
    expect(outcome.fact?.keyHex).toBe(depositKeyHex(DEPOSIT_ID));
    expect(depositOutcome('ee'.repeat(32), 1, DEPOSIT_RECORD).state).toBe('inconsistent');
    expect(depositOutcome(DEPOSIT_ID, 1, { ...DEPOSIT_RECORD, deposit: { ...DEPOSIT_RECORD.deposit, status: 'CREDITED' } })
      .state).toBe('inconsistent');
  });
});
