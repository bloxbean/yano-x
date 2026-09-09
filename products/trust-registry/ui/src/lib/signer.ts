/**
 * In-browser actor signing for governed authenticated-map writes (ADR-053 §2.1, BROWSER_KEY).
 *
 * The key handling is the Evidence Desk's (ADR-048 §4): the seed is parsed once, imported through
 * WebCrypto Ed25519, held in a closure, never placed in component state, storage, logs, or URLs,
 * and zeroed as soon as it has been imported. What differs is what gets signed: the map's
 * `MapActorAuthorizationV1` preimage rather than a role-workflow statement, so this module signs
 * raw bytes and the command encoder below builds them.
 *
 * The encoders mirror `AuthenticatedMapAuthorizationContract` and `AuthenticatedMapContract` in
 * `state-machines/stdlib-contracts`. Their bytes are pinned to vectors the Java signer produces,
 * so a drift on either side fails a test rather than a node.
 */
import { encodeCbor, type CborValue } from './cbor';
import { blake2b256 } from './message-proof';

const TEXT_ENCODER = new TextEncoder();
const PKCS8_PREFIX = hexToBytes('302e020100300506032b657004220420');

export const CODEC_VERSION = 1n;
export const AUTH_GOVERNED_ROLE = 3;
export const EVIDENCE_ACTOR = 0;
export const SIGNATURE_ED25519 = 0;
export const OP_PUT = 0;
export const OP_PUT_IF_ABSENT = 1;
export const OP_COMPARE_AND_SET = 2;
export const OP_REVOKE = 4;

const ACTION_DOMAIN = TEXT_ENCODER.encode('yano:authenticated-map:action:v1\0');
const ACTOR_DOMAIN = TEXT_ENCODER.encode('yano:authenticated-map:actor-authorization:v1\0');

export interface Mutation {
  operation: number;
  collectionId: string;
  applicationKey: Uint8Array;
  value: Uint8Array;
  expectedRevision: bigint;
  expectedValueHash: Uint8Array;
  newController: Uint8Array;
}

export function put(collectionId: string, applicationKey: Uint8Array, value: Uint8Array): Mutation {
  return mutation(OP_PUT, collectionId, applicationKey, value, 0n);
}

export function revoke(collectionId: string, applicationKey: Uint8Array, expectedRevision: bigint): Mutation {
  return mutation(OP_REVOKE, collectionId, applicationKey, new Uint8Array(0), expectedRevision);
}

function mutation(
  operation: number,
  collectionId: string,
  applicationKey: Uint8Array,
  value: Uint8Array,
  expectedRevision: bigint
): Mutation {
  return {
    operation,
    collectionId,
    applicationKey,
    value,
    expectedRevision,
    expectedValueHash: new Uint8Array(0),
    newController: new Uint8Array(0)
  };
}

function encodeMutation(item: Mutation): CborValue {
  return [
    BigInt(item.operation),
    item.collectionId,
    item.applicationKey,
    item.value,
    item.expectedRevision,
    item.expectedValueHash,
    item.newController
  ];
}

/** One assignment per mutation: the governed-role kind under the collection's policy. */
function encodeAssignment(mutationIndex: number, policyId: string): CborValue {
  return [BigInt(mutationIndex), BigInt(AUTH_GOVERNED_ROLE), policyId, 1n];
}

export function encodeAction(batch: boolean, mutations: Mutation[], policyId: string): Uint8Array {
  return encodeCbor([
    CODEC_VERSION,
    batch ? 1n : 0n,
    mutations.map(encodeMutation),
    mutations.map((_, index) => encodeAssignment(index, policyId))
  ]);
}

export function actionCommitment(batch: boolean, mutations: Mutation[], policyId: string): Uint8Array {
  const encoded = encodeAction(batch, mutations, policyId);
  const preimage = new Uint8Array(ACTION_DOMAIN.length + encoded.length);
  preimage.set(ACTION_DOMAIN);
  preimage.set(encoded, ACTION_DOMAIN.length);
  return blake2b256(preimage);
}

export interface AuthorizationFields {
  authorizationId: Uint8Array;
  chainId: string;
  genesisId: Uint8Array;
  actionCommitment: Uint8Array;
  coveredMutationIndexes: number[];
  policyId: string;
  policyRevision: bigint;
  actorId: string;
  actorRevision: bigint;
  keyId: string;
  publicKey: Uint8Array;
  issuedHeight: bigint;
  deadlineHeight: bigint;
}

export function unsignedStatement(fields: AuthorizationFields): Uint8Array {
  const covered = [...fields.coveredMutationIndexes].sort((left, right) => left - right);
  if (new Set(covered).size !== covered.length || covered.some((index) => index < 0)) {
    throw new Error('Covered mutation indexes must be distinct and non-negative');
  }
  return encodeCbor([
    CODEC_VERSION,
    fields.authorizationId,
    fields.chainId,
    fields.genesisId,
    fields.actionCommitment,
    covered.map((index) => BigInt(index)),
    fields.policyId,
    fields.policyRevision,
    fields.actorId,
    fields.actorRevision,
    fields.keyId,
    fields.publicKey,
    fields.issuedHeight,
    fields.deadlineHeight,
    BigInt(SIGNATURE_ED25519)
  ]);
}

/** The domain, the statement length as four big-endian bytes, then the statement. */
export function signingPreimage(statement: Uint8Array): Uint8Array {
  const preimage = new Uint8Array(ACTOR_DOMAIN.length + 4 + statement.length);
  preimage.set(ACTOR_DOMAIN);
  new DataView(preimage.buffer).setUint32(ACTOR_DOMAIN.length, statement.length, false);
  preimage.set(statement, ACTOR_DOMAIN.length + 4);
  return preimage;
}

function encodeAuthorization(statement: Uint8Array, signature: Uint8Array): Uint8Array {
  return encodeCbor([CODEC_VERSION, statement, signature]);
}

/** The full governed command: the action, then the actor authorization as evidence. */
export function encodeCommand(action: Uint8Array, authorization: Uint8Array): Uint8Array {
  return encodeCbor([
    CODEC_VERSION,
    action,
    [[BigInt(EVIDENCE_ACTOR), authorization]]
  ]);
}

export interface ActorSigner {
  readonly publicKeyHex: string;
  readonly publicKey: Uint8Array;
  signBytes(preimage: Uint8Array): Promise<Uint8Array>;
  release(): void;
}

export function webCryptoEd25519Available(): boolean {
  return typeof globalThis.crypto?.subtle?.importKey === 'function';
}

/** Parses a 32-byte seed from hex text (whitespace tolerated) or from raw file bytes. */
export function parseSeed(input: string | Uint8Array): Uint8Array {
  if (input instanceof Uint8Array) {
    if (input.length === 32) return new Uint8Array(input);
    input = new TextDecoder().decode(input);
  }
  const normalized = input.replace(/\s+/g, '').toLowerCase();
  if (!/^[0-9a-f]{64}$/.test(normalized)) {
    throw new Error('The seed must be 32 bytes as 64 hex characters, or a 32-byte raw file');
  }
  return hexToBytes(normalized);
}

/** Imports the seed once, derives the public key, and returns a signer that owns the key. */
export async function createSigner(seed: Uint8Array): Promise<ActorSigner> {
  if (seed.length !== 32) throw new Error('The seed must be exactly 32 bytes');
  const subtle = globalThis.crypto?.subtle;
  if (!subtle) throw new Error('WebCrypto is unavailable in this browser');
  const pkcs8 = new Uint8Array(PKCS8_PREFIX.length + 32);
  pkcs8.set(PKCS8_PREFIX);
  pkcs8.set(seed, PKCS8_PREFIX.length);
  let privateKey: CryptoKey;
  let publicKey: Uint8Array;
  try {
    const extractable = await subtle.importKey('pkcs8', pkcs8 as BufferSource, { name: 'Ed25519' }, true, ['sign']);
    const jwk = await subtle.exportKey('jwk', extractable);
    if (!jwk.x) throw new Error('missing public coordinate');
    publicKey = base64UrlToBytes(jwk.x);
    // Re-import without extractability so the private scalar cannot be exported afterwards.
    privateKey = await subtle.importKey('pkcs8', pkcs8 as BufferSource, { name: 'Ed25519' }, false, ['sign']);
  } catch (cause) {
    throw new Error(`This browser cannot sign with Ed25519 through WebCrypto (${cause instanceof Error ? cause.message : String(cause)})`);
  } finally {
    pkcs8.fill(0);
    seed.fill(0);
  }
  let released = false;
  return {
    publicKey,
    publicKeyHex: toHex(publicKey),
    async signBytes(preimage: Uint8Array): Promise<Uint8Array> {
      if (released) throw new Error('The key was locked');
      const signature = new Uint8Array(await subtle.sign({ name: 'Ed25519' }, privateKey, preimage as BufferSource));
      if (signature.length !== 64) throw new Error('Unexpected signature length');
      return signature;
    },
    release() {
      released = true;
    }
  };
}

export interface CommandContext {
  chainId: string;
  genesisId: Uint8Array;
  policyId: string;
  policyRevision: bigint;
  actorId: string;
  actorRevision: bigint;
  keyId: string;
  issuedHeight: bigint;
  deadlineHeight: bigint;
  authorizationId: Uint8Array;
}

/**
 * Signs one governed command in the browser and returns the bytes to submit. The action is
 * committed first, the commitment is bound into the actor's one-use authorization, and the
 * authorization travels with the action as its evidence.
 */
export async function signCommand(
  signer: ActorSigner,
  mutations: Mutation[],
  context: CommandContext,
  batch = mutations.length > 1
): Promise<Uint8Array> {
  if (mutations.length === 0) throw new Error('A command needs at least one mutation');
  const commitment = actionCommitment(batch, mutations, context.policyId);
  const statement = unsignedStatement({
    authorizationId: context.authorizationId,
    chainId: context.chainId,
    genesisId: context.genesisId,
    actionCommitment: commitment,
    coveredMutationIndexes: mutations.map((_, index) => index),
    policyId: context.policyId,
    policyRevision: context.policyRevision,
    actorId: context.actorId,
    actorRevision: context.actorRevision,
    keyId: context.keyId,
    publicKey: signer.publicKey,
    issuedHeight: context.issuedHeight,
    deadlineHeight: context.deadlineHeight
  });
  const signature = await signer.signBytes(signingPreimage(statement));
  return encodeCommand(
    encodeAction(batch, mutations, context.policyId),
    encodeAuthorization(statement, signature)
  );
}

export function randomAuthorizationId(): Uint8Array {
  const bytes = new Uint8Array(32);
  globalThis.crypto.getRandomValues(bytes);
  return bytes;
}

/**
 * The registry's application keys are ASCII by profile rule. Building them here rather than with
 * a `TextEncoder` also keeps the bytes in this realm, which matters under test runners that hand
 * out a different global.
 */
export function asciiKey(value: string): Uint8Array {
  const bytes = new Uint8Array(value.length);
  for (let index = 0; index < value.length; index++) {
    const code = value.charCodeAt(index);
    if (code > 0x7f) throw new Error('An application key must be ASCII');
    bytes[index] = code;
  }
  return bytes;
}

export function toHex(bytes: Uint8Array): string {
  let hex = '';
  for (const byte of bytes) hex += byte.toString(16).padStart(2, '0');
  return hex;
}

export function hexToBytes(hex: string): Uint8Array {
  const normalized = hex.trim().toLowerCase();
  if (normalized.length % 2 !== 0 || !/^[0-9a-f]*$/.test(normalized)) {
    throw new Error('Expected an even-length hex string');
  }
  const bytes = new Uint8Array(normalized.length / 2);
  for (let index = 0; index < bytes.length; index++) {
    bytes[index] = Number.parseInt(normalized.slice(index * 2, index * 2 + 2), 16);
  }
  return bytes;
}

function base64UrlToBytes(value: string): Uint8Array {
  const base64 = value.replace(/-/g, '+').replace(/_/g, '/');
  const padded = base64 + '='.repeat((4 - (base64.length % 4)) % 4);
  const binary = atob(padded);
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index++) bytes[index] = binary.charCodeAt(index);
  return bytes;
}
