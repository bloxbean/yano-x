import { blake2b } from '@noble/hashes/blake2.js';
import { describe, expect, it } from 'vitest';
import { concat, encode, head } from './cbor';
import { depositDatum, DepositMismatch, verifyDeposit, type DepositExpectation } from './deposit';
import { fromHex, toHex } from './hex';

// Hand-built CBOR in the shapes cardano-client-lib emits for the payments API's deposit.
const map = (...pairs: [Uint8Array, Uint8Array][]) => concat(head(5, BigInt(pairs.length)), ...pairs.flat());
const arr = (...items: Uint8Array[]) => concat(head(4, BigInt(items.length)), ...items);
const tag = (n: number, item: Uint8Array) => concat(head(6, BigInt(n)), item);
const u = (n: number | bigint) => encode(BigInt(n));
const b = (bytes: Uint8Array) => encode(bytes);

const keyHash = '11'.repeat(28);
const account = '22'.repeat(28);
const change = fromHex('60' + keyHash);
const vault = fromHex('70' + '33'.repeat(28));
const attacker = fromHex('60' + '44'.repeat(28));
const txA = fromHex('aa'.repeat(32));
const txB = fromHex('bb'.repeat(32));
const policy = '55'.repeat(28);
const datum = depositDatum(account, change, keyHash);

const utxo = (tx: Uint8Array, index: number, lovelace: number) => toHex(arr(arr(b(tx), u(index)), arr(b(change),
  u(lovelace))));
const walletUtxos = [utxo(txA, 0, 50_000_000), utxo(txB, 1, 3_000_000)];

interface Shape {
  inputs?: Uint8Array[];
  vaultValue?: Uint8Array;
  vaultDatum?: Uint8Array;
  vaultAddress?: Uint8Array;
  extraOutputs?: Uint8Array[];
  fee?: number;
  ttl?: number | null;
  extraBody?: [Uint8Array, Uint8Array][];
}

function deposit(shape: Shape = {}): { hex: string; body: Uint8Array } {
  const vaultOut = map([u(0), b(shape.vaultAddress ?? vault)], [u(1), shape.vaultValue ?? u(10_000_000)],
    [u(2), arr(u(1), tag(24, b(shape.vaultDatum ?? datum)))]);
  const changeOut = arr(b(change), u(39_800_000));
  const fields: [Uint8Array, Uint8Array][] = [
    [u(0), tag(258, arr(...(shape.inputs ?? [arr(b(txA), u(0))])))],
    [u(1), arr(vaultOut, changeOut, ...(shape.extraOutputs ?? []))],
    [u(2), u(shape.fee ?? 200_000)]];
  if (shape.ttl !== null) fields.push([u(3), u(shape.ttl ?? 12_200)]);
  fields.push(...(shape.extraBody ?? []));
  const body = map(...fields);
  return { hex: toHex(concat(Uint8Array.of(0x84), body, Uint8Array.of(0xa0, 0xf5, 0xf6))), body };
}

const ada: DepositExpectation = {
  walletUtxos, changeAddress: change, vaultAddress: vault, asset: { policyId: '', assetName: '', ada: true },
  quantity: 10_000_000n, lovelace: 0n, datum, maxFee: 2_000_000n
};

function refused(hex: string, expected: DepositExpectation, reason: RegExp): void {
  expect(() => verifyDeposit(hex, expected)).toThrow(DepositMismatch);
  expect(() => verifyDeposit(hex, expected)).toThrow(reason);
}

describe('unsigned deposit check', () => {
  it('decodes a well-formed deposit and computes its transaction id locally', () => {
    const { hex, body } = deposit();
    const decoded = verifyDeposit(hex, ada);
    expect(decoded.transactionId).toBe(toHex(blake2b(body, { dkLen: 32 })));
    expect(decoded).toMatchObject({ inputs: ['aa'.repeat(32) + '#0'], fee: 200_000n, ttl: 12_200n, outputIndex: 0,
      quantity: 10_000_000n, outputLovelace: 10_000_000n, change: 39_800_000n });
  });

  it('builds the deposit datum byte for byte as the ledger encodes it', () => {
    expect(toHex(datum)).toBe('d87983581c' + account + '581d' + toHex(change) + '581c' + keyHash);
  });

  it('refuses a tampered vault output', () => {
    refused(deposit({ vaultValue: u(100_000_000) }).hex, ada, /deposits 100000000, not 10000000/);
    refused(deposit({ vaultDatum: depositDatum('99'.repeat(28), change, keyHash) }).hex, ada, /exact deposit datum/);
    refused(deposit({ vaultValue: arr(u(10_000_000), map([b(fromHex(policy)), map([b(fromHex('01')), u(1)])])) }).hex,
      ada, /carries tokens/);
  });

  it('refuses a vault address other than the trusted one, and a payment to anyone else', () => {
    refused(deposit({ vaultAddress: attacker }).hex, ada, /neither the vault nor your change/);
    refused(deposit({ extraOutputs: [arr(b(attacker), u(5_000_000))] }).hex, ada, /output 2 pays an address/);
    refused(deposit().hex, { ...ada, vaultAddress: fromHex('70' + '66'.repeat(28)) }, /neither the vault/);
  });

  it("refuses inputs that are not the wallet's own UTxOs", () => {
    refused(deposit({ inputs: [arr(b(fromHex('cc'.repeat(32))), u(0))] }).hex, ada, /not one of this wallet's/);
    refused(deposit({ inputs: [arr(b(txA), u(1))] }).hex, ada, /not one of this wallet's/);
  });

  it('refuses a fee above the cap, a missing TTL and any field a deposit never has', () => {
    refused(deposit({ fee: 5_000_000 }).hex, ada, /above this client's cap/);
    refused(deposit({ ttl: null }).hex, ada, /no validity upper bound/);
    // Certificates (4), withdrawals (5), mint (9) and required signers (14) could act on the wallet's other keys.
    for (const key of [4, 5, 9, 14]) refused(deposit({ extraBody: [[u(key), arr()]] }).hex, ada, /never has/);
  });

  it('checks a token deposit: exactly the vault token, its quantity and bounded lovelace', () => {
    const token = { ...ada, asset: { policyId: policy, assetName: '5553444d', ada: false }, quantity: 400n,
      lovelace: 1_500_000n };
    const value = (name: string, quantity: number, lovelace = 1_500_000) => arr(u(lovelace),
      map([b(fromHex(policy)), map([b(fromHex(name)), u(quantity)])]));
    expect(verifyDeposit(deposit({ vaultValue: value('5553444d', 400) }).hex, token)).toMatchObject({
      quantity: 400n, outputLovelace: 1_500_000n });
    refused(deposit({ vaultValue: value('45555243', 400) }).hex, token, /exactly the vault's token/);
    refused(deposit({ vaultValue: value('5553444d', 4_000) }).hex, token, /deposits 4000, not 400/);
    refused(deposit({ vaultValue: value('5553444d', 400, 50_000_000) }).hex, token, /lovelace, not the 1500000/);
  });
});
