/**
 * Checks the unsigned L1 deposit the payments API built before the wallet signs it (#109 §3.1, ADR-059 D5). The node
 * is not trusted with the transaction: the client decodes the CBOR itself and refuses it unless it spends only the
 * wallet's own UTxOs, pays exactly one output to the vault with the exact deposit datum, asset and quantity, sends
 * everything else back to the wallet's change address, and pays a bounded fee. The review shows the decoded values,
 * and the transaction id is computed here.
 */
import { blake2b } from '@noble/hashes/blake2.js';
import { arrayElementSpans, concat, head, read, readAll, type Item } from './cbor';
import { equalBytes, fromHex, toHex } from './hex';

export interface DepositExpectation {
  /** The wallet's own CIP-30 getUtxos() entries. */
  walletUtxos: string[];
  /** The wallet's change address (raw bytes): change, refunds and the datum's refund address. */
  changeAddress: Uint8Array;
  /** The vault address (raw bytes) from the configured node's vault registry, not from the build response. */
  vaultAddress: Uint8Array;
  asset: { policyId: string; assetName: string; ada: boolean };
  /** The base units requested: lovelace for an ADA vault, the token quantity otherwise. */
  quantity: bigint;
  /** A token vault: the lovelace requested with the token (the output may carry more, up to its min-UTxO). */
  lovelace: bigint;
  /** The exact inline datum the vault output must carry. */
  datum: Uint8Array;
  /** The highest fee the client accepts. */
  maxFee: bigint;
}

export interface DecodedDeposit {
  transactionId: string;
  inputs: string[];
  fee: bigint;
  ttl: bigint;
  outputIndex: number;
  /** For an ADA vault, the credited amount; for a token vault, the min-ADA credit. */
  outputLovelace: bigint;
  /** The vault asset's quantity in the vault output (lovelace for an ADA vault). */
  quantity: bigint;
  /** Lovelace back to the change address. */
  change: bigint;
}

export class DepositMismatch extends Error {}

/** A token deposit may carry the requested lovelace or more, up to this, so its output meets min-UTxO. */
export const MAX_TOKEN_OUTPUT_LOVELACE = 5_000_000n;
/** The body keys a plain deposit has: inputs, outputs, fee, ttl and an optional validity start. */
const BODY_KEYS = new Set([0n, 1n, 2n, 3n, 8n]);

/** DepositDatumV1 exactly as the ledger's encoder writes it: #6.121([beneficiary, refund_address, refund_key]). */
export function depositDatum(beneficiaryAccount: string, refundAddress: Uint8Array, refundKeyHash: string): Uint8Array {
  const bstr = (bytes: Uint8Array) => concat(head(2, BigInt(bytes.length)), bytes);
  if (refundAddress.length < 1 || refundAddress.length > 64) throw new Error('refund address must be 1-64 bytes');
  return concat(Uint8Array.of(0xd8, 0x79, 0x83), bstr(fromHex(beneficiaryAccount, 28)), bstr(refundAddress),
    bstr(fromHex(refundKeyHash, 28)));
}

function fail(reason: string): never {
  throw new DepositMismatch(`refusing to sign the deposit: ${reason}`);
}

function uint(item: Item | undefined, what: string): bigint {
  if (!item || item.kind !== 'uint') fail(`${what} is not an unsigned integer`);
  return item.value;
}

function bytes(item: Item | undefined, what: string): Uint8Array {
  if (!item || item.kind !== 'bytes') fail(`${what} is not a byte string`);
  return item.value;
}

/** Refuses a map that repeats a key: entry() reads only the first, so a second copy would go unchecked. */
function uniqueKeys(map: Extract<Item, { kind: 'map' }>, what: string): void {
  const seen = new Set<string>();
  for (const [key] of map.entries) {
    let id: string;
    if (key.kind === 'uint') id = `u${key.value}`;
    else if (key.kind === 'bytes') id = `b${toHex(key.value)}`;
    else fail(`${what} has a key that is neither an integer nor bytes`);
    if (seen.has(id)) fail(`${what} repeats a key`);
    seen.add(id);
  }
}

function entry(map: Item, key: bigint): Item | undefined {
  if (map.kind !== 'map') return undefined;
  return map.entries.find(([k]) => k.kind === 'uint' && k.value === key)?.[1];
}

function outpoint(item: Item, what: string): string {
  if (item.kind !== 'array' || item.items.length !== 2) fail(`${what} is not [tx_id, index]`);
  return `${toHex(bytes(item.items[0], `${what} tx id`))}#${uint(item.items[1], `${what} index`)}`;
}

interface Value {
  coin: bigint;
  assets: { policyId: string; assetName: string; quantity: bigint }[];
}

function value(item: Item | undefined): Value {
  if (item?.kind === 'uint') return { coin: item.value, assets: [] };
  if (!item || item.kind !== 'array' || item.items.length !== 2 || item.items[1].kind !== 'map') {
    fail('an output value is malformed');
  }
  const assets: Value['assets'] = [];
  const policies = item.items[1] as Extract<Item, { kind: 'map' }>;
  uniqueKeys(policies, 'an output multi-asset');
  for (const [policy, names] of policies.entries) {
    if (names.kind !== 'map') fail('an output multi-asset is malformed');
    uniqueKeys(names, 'an output multi-asset');
    for (const [name, quantity] of names.entries) {
      assets.push({ policyId: toHex(bytes(policy, 'policy id')), assetName: toHex(bytes(name, 'asset name')),
        quantity: uint(quantity, 'asset quantity') });
    }
  }
  return { coin: uint(item.items[0], 'output lovelace'), assets };
}

interface Output {
  address: Uint8Array;
  value: Value;
  inlineDatum: Uint8Array | null;
  /** A datum hash or a reference script: never in a deposit or its change. */
  extra: boolean;
}

function output(item: Item, index: number): Output {
  if (item.kind === 'array') {
    if (item.items.length < 2 || item.items.length > 3) fail(`output ${index} is malformed`);
    return { address: bytes(item.items[0], 'output address'), value: value(item.items[1]), inlineDatum: null,
      extra: item.items.length === 3 };
  }
  if (item.kind !== 'map') fail(`output ${index} is malformed`);
  uniqueKeys(item, `output ${index}`);
  for (const [key] of item.entries) {
    if (key.kind !== 'uint' || key.value > 3n) fail(`output ${index} has an unknown field`);
  }
  let inlineDatum: Uint8Array | null = null;
  let extra = entry(item, 3n) !== undefined;
  const option = entry(item, 2n);
  if (option) {
    if (option.kind !== 'array' || option.items.length !== 2) fail(`output ${index} has a malformed datum`);
    const kind = uint(option.items[0], 'datum option');
    const datum = option.items[1];
    if (kind === 1n && datum.kind === 'tag' && datum.tag === 24n && datum.item.kind === 'bytes') {
      inlineDatum = datum.item.value;
    } else {
      extra = true;
    }
  }
  return { address: bytes(entry(item, 0n), 'output address'), value: value(entry(item, 1n)), inlineDatum, extra };
}

/** The outpoints ("txid#index") of CIP-30 getUtxos() entries, each [transaction_input, transaction_output]. */
export function walletOutpoints(utxos: string[]): Set<string> {
  const out = new Set<string>();
  for (const hex of utxos) {
    const pair = readAll(fromHex(hex));
    if (pair.kind !== 'array' || pair.items.length !== 2) throw new Error('the wallet returned a malformed UTxO');
    out.add(outpoint(pair.items[0], 'a wallet UTxO'));
  }
  return out;
}

/** Decodes the unsigned deposit and checks it against what the user asked for; throws DepositMismatch otherwise. */
export function verifyDeposit(unsignedTxHex: string, expected: DepositExpectation): DecodedDeposit {
  const tx = fromHex(unsignedTxHex);
  let spans: [number, number][];
  try {
    spans = arrayElementSpans(tx).spans;
  } catch {
    fail('it is not a transaction');
  }
  if (spans.length !== 4) fail('it is not a [body, witnesses, valid, auxiliary data] transaction');
  const part = (i: number) => tx.slice(spans[i][0], spans[i][1]);
  if (toHex(part(1)) !== 'a0') fail('it already carries witnesses');
  if (toHex(part(2)) !== 'f5') fail('it is not marked valid');
  if (toHex(part(3)) !== 'f6') fail('it carries auxiliary data');
  const bodyBytes = part(0);
  const body = read(bodyBytes, 0).item;
  if (body.kind !== 'map') fail('its body is not a map');
  uniqueKeys(body, 'its body');
  for (const [key] of body.entries) {
    // Certificates, withdrawals, mints, collateral, required signers, votes and proposals have no place in a deposit.
    if (key.kind !== 'uint' || !BODY_KEYS.has(key.value)) fail(`its body has field ${key.kind === 'uint' ? key.value
      : '?'}, which a deposit never has`);
  }

  let inputList = entry(body, 0n);
  if (inputList?.kind === 'tag' && inputList.tag === 258n) inputList = inputList.item;
  if (!inputList || inputList.kind !== 'array' || inputList.items.length === 0) fail('it has no inputs');
  const own = walletOutpoints(expected.walletUtxos);
  const inputs = inputList.items.map((item, i) => outpoint(item, `input ${i}`));
  for (const input of inputs) if (!own.has(input)) fail(`input ${input} is not one of this wallet's UTxOs`);
  if (new Set(inputs).size !== inputs.length) fail('it spends an input twice');

  const outputList = entry(body, 1n);
  if (!outputList || outputList.kind !== 'array') fail('it has no outputs');
  const outputs = outputList.items.map(output);
  let outputIndex = -1;
  let change = 0n;
  outputs.forEach((out, i) => {
    if (equalBytes(out.address, expected.vaultAddress)) {
      if (outputIndex >= 0) fail('it pays the vault more than once');
      outputIndex = i;
    } else if (equalBytes(out.address, expected.changeAddress)) {
      if (out.extra || out.inlineDatum) fail(`change output ${i} carries a datum or a script`);
      change += out.value.coin;
    } else {
      fail(`output ${i} pays an address that is neither the vault nor your change address`);
    }
  });
  if (outputIndex < 0) fail('it does not pay the vault');
  const deposit = outputs[outputIndex];
  if (deposit.extra || !deposit.inlineDatum || !equalBytes(deposit.inlineDatum, expected.datum)) {
    fail('the vault output does not carry the exact deposit datum for your account');
  }
  let quantity: bigint;
  if (expected.asset.ada) {
    if (deposit.value.assets.length !== 0) fail('the ADA deposit carries tokens');
    quantity = deposit.value.coin;
  } else {
    const [token, ...others] = deposit.value.assets;
    if (!token || others.length !== 0 || token.policyId !== expected.asset.policyId
      || token.assetName !== expected.asset.assetName) {
      fail("the vault output does not carry exactly the vault's token");
    }
    quantity = token.quantity;
    const most = expected.lovelace > MAX_TOKEN_OUTPUT_LOVELACE ? expected.lovelace : MAX_TOKEN_OUTPUT_LOVELACE;
    if (deposit.value.coin < expected.lovelace || deposit.value.coin > most) {
      fail(`the vault output carries ${deposit.value.coin} lovelace, not the ${expected.lovelace} asked for`);
    }
  }
  if (quantity !== expected.quantity) fail(`the vault output deposits ${quantity}, not ${expected.quantity}`);

  const fee = uint(entry(body, 2n), 'the fee');
  if (fee > expected.maxFee) fail(`the fee of ${fee} lovelace is above this client's cap of ${expected.maxFee}`);
  const ttl = entry(body, 3n);
  if (!ttl) fail('it has no validity upper bound (TTL)');
  return {
    transactionId: toHex(blake2b(bodyBytes, { dkLen: 32 })),
    inputs, fee, ttl: uint(ttl, 'the TTL'), outputIndex, outputLovelace: deposit.value.coin, quantity, change
  };
}
