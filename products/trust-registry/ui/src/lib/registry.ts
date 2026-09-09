import { asArray, asBytes, asText, asUnsigned, decodeCbor, encodeCbor, fromHex, toHex, utf8, type CborValue } from './cbor';
import { blake2b256 } from './message-proof';
import type { Collection, DecodedValue, IssuerValue, MapEntry, StatusListValue, StatusValue, SubjectValue } from './types';

/** ADR-049 §2.1: the registry profile's collections and policies. */
export const COLLECTIONS: Collection[] = ['issuers', 'schemas', 'status', 'status-lists', 'subjects'];
export const POLICY_OF: Record<Collection, string> = {
  subjects: 'registrar-write',
  schemas: 'registrar-write',
  status: 'issuer-write',
  'status-lists': 'issuer-write',
  issuers: 'issuer-onboarding'
};
export const AUTH_GOVERNED_ROLE = 3;
export const AUTH_APPROVAL = 4;
export const MAP_COMPONENT = 'authenticated-map';
export const ACTORS_COMPONENT = 'domain-actors';
export const APPROVALS_COMPONENT = 'role-approvals';
export const COMMAND_TOPIC = 'authenticated-map.command.v1';
export const MIN_BIT_LENGTH = 131_072;
export const MAX_BIT_LENGTH = 1_048_576;

const IDENTIFIER = /^[A-Za-z0-9._:~-]{1,128}$/;
const LIST_ID = /^[A-Za-z0-9._:~-]{1,64}$/;
const ROLE_ID = /^[a-z][a-z0-9-]{0,62}$/;
const NAMESPACE_FRAMEWORK = 0;
const NAMESPACE_MAP = 1;
const INTERNAL_GENESIS = 'yano-authenticated-map-internal-v1';
const INTERNAL_RECEIPTS = 'yano-authenticated-map-receipts-v1';
const INTERNAL_CONSUMPTIONS = 'yano-authenticated-map-direct-consumption-v1';
const COMPOSITE_PREFIX = utf8('yano-composite-state-v1\0');
const ACTION_DOMAIN = utf8('yano:authenticated-map:action:v1\0');

// ------------------------------------------------------------------ keys

export function requireIdentifier(value: string, name: string): string {
  if (!IDENTIFIER.test(value)) throw new Error(`${name} must match ${IDENTIFIER.source}`);
  return value;
}

export function requireListId(value: string): string {
  if (!LIST_ID.test(value)) throw new Error(`The list id must match ${LIST_ID.source}`);
  return value;
}

export function requireRoleId(value: string, name: string): string {
  if (!ROLE_ID.test(value)) throw new Error(`${name} must match ${ROLE_ID.source}`);
  return value;
}

/** Application key of a registry entry, as text bytes. */
export function applicationKey(collection: Collection, id: string, index?: number): Uint8Array {
  switch (collection) {
    case 'subjects':
    case 'issuers':
      return utf8(requireIdentifier(id, `The ${collection === 'subjects' ? 'subject' : 'issuer'} id`));
    case 'status-lists':
      return utf8(requireListId(id));
    case 'schemas':
      if (!/^[A-Za-z0-9._:~-]{1,64}$/.test(id)) throw new Error('The schema id must match [A-Za-z0-9._:~-]{1,64}');
      return utf8(id);
    case 'status': {
      requireListId(id);
      if (index === undefined || !Number.isInteger(index) || index < 0 || index >= MAX_BIT_LENGTH) {
        throw new Error('The status index must be an integer within the list bound');
      }
      return utf8(`${id}/${index}`);
    }
  }
}

/** `version:u8 || namespace:u8 || collectionLen:u16 || collection || keyLen:u32 || key`. */
export function canonicalKey(namespace: number, collection: string, key: Uint8Array): Uint8Array {
  const collectionBytes = utf8(collection);
  const out = new Uint8Array(2 + 2 + collectionBytes.length + 4 + key.length);
  const view = new DataView(out.buffer);
  out[0] = 1;
  out[1] = namespace;
  view.setUint16(2, collectionBytes.length);
  out.set(collectionBytes, 4);
  view.setUint32(4 + collectionBytes.length, key.length);
  out.set(key, 8 + collectionBytes.length);
  return out;
}

/** Composite physical key: `yano-composite-state-v1\0 || len8(component) || component || len16(local) || local`. */
export function componentKey(component: string, local: Uint8Array): Uint8Array {
  const componentBytes = utf8(component);
  const out = new Uint8Array(COMPOSITE_PREFIX.length + 1 + componentBytes.length + 2 + local.length);
  let offset = 0;
  out.set(COMPOSITE_PREFIX, offset); offset += COMPOSITE_PREFIX.length;
  out[offset++] = componentBytes.length;
  out.set(componentBytes, offset); offset += componentBytes.length;
  out[offset++] = local.length >> 8;
  out[offset++] = local.length & 0xff;
  out.set(local, offset);
  return out;
}

export function entryPhysicalKey(collection: Collection, key: Uint8Array): Uint8Array {
  return componentKey(MAP_COMPONENT, canonicalKey(NAMESPACE_MAP, collection, key));
}

export function receiptPhysicalKey(messageId: Uint8Array): Uint8Array {
  return componentKey(MAP_COMPONENT, canonicalKey(NAMESPACE_FRAMEWORK, INTERNAL_RECEIPTS, messageId));
}

export function genesisMarkerPhysicalKey(): Uint8Array {
  return componentKey(MAP_COMPONENT, canonicalKey(NAMESPACE_FRAMEWORK, INTERNAL_GENESIS, utf8('genesis')));
}

export function consumptionPhysicalKey(actorId: string, authorizationId: Uint8Array): Uint8Array {
  const actor = utf8(actorId);
  const key = new Uint8Array(1 + 2 + actor.length + authorizationId.length);
  key[0] = 1;
  key[1] = actor.length >> 8;
  key[2] = actor.length & 0xff;
  key.set(actor, 3);
  key.set(authorizationId, 3 + actor.length);
  return componentKey(MAP_COMPONENT, canonicalKey(NAMESPACE_FRAMEWORK, INTERNAL_CONSUMPTIONS, key));
}

export function roleKey(component: string, local: string): Uint8Array {
  return componentKey(component, utf8(local));
}

// ------------------------------------------------------------------ records

/** `[1, status, revision, controller, value, logicalValueHash, createdHeight, lastMutationHeight]`. */
export function decodeEntry(valueHex: string): MapEntry {
  const items = asArray(decodeCbor(fromHex(valueHex)), 8);
  if (asUnsigned(items[0]) !== 1) throw new Error('Unsupported map entry version');
  const status = asUnsigned(items[1]);
  if (status !== 0 && status !== 1) throw new Error('Unknown map entry status');
  return {
    status: status === 0 ? 'ACTIVE' : 'REVOKED',
    revision: asUnsigned(items[2]),
    controllerHex: toHex(asBytes(items[3])),
    valueHex: toHex(asBytes(items[4])),
    logicalValueHashHex: toHex(asBytes(items[5], 32)),
    createdHeight: asUnsigned(items[6]),
    lastMutationHeight: asUnsigned(items[7])
  };
}

export function decodeValue(collection: Collection, valueHex: string): DecodedValue {
  const bytes = fromHex(valueHex);
  try {
    switch (collection) {
      case 'subjects': {
        const items = asArray(decodeCbor(bytes), 4);
        requireVersion(items[0]);
        const value: SubjectValue = {
          controllerOrganizationId: asText(items[1]), kind: asText(items[2]), metadataHashHex: toHex(asBytes(items[3], 32))
        };
        return { kind: 'subject', value };
      }
      case 'status': {
        const items = asArray(decodeCbor(bytes), 3);
        requireVersion(items[0]);
        const value: StatusValue = { bit: asUnsigned(items[1]), reasonCode: asUnsigned(items[2]) };
        if (value.bit > 1) throw new Error('bit');
        return { kind: 'status', value };
      }
      case 'status-lists': {
        const items = asArray(decodeCbor(bytes), 5);
        requireVersion(items[0]);
        const value: StatusListValue = {
          purpose: asText(items[1]), bitLength: asUnsigned(items[2]),
          listSha256Hex: toHex(asBytes(items[3], 32)), publishedHeight: asUnsigned(items[4])
        };
        return { kind: 'status-list', value };
      }
      case 'issuers': {
        const items = asArray(decodeCbor(bytes), 5);
        requireVersion(items[0]);
        const value: IssuerValue = {
          framework: asText(items[1]),
          authorizations: asArray(items[2]).map((item) => asText(item)),
          validFromHeight: asUnsigned(items[3]), validUntilHeight: asUnsigned(items[4])
        };
        return { kind: 'issuer', value };
      }
      default:
        return { kind: 'opaque', valueHex };
    }
  } catch {
    return { kind: 'opaque', valueHex };
  }
}

export interface Mutation { operation: number; collection: string; keyHex: string; valueHex: string }
export interface Assignment { mutationIndex: number; kind: number; policyId: string; evidenceHandle: number }
export interface ActorAuthorization {
  authorizationIdHex: string; chainId: string; genesisIdHex: string; actionCommitmentHex: string;
  covered: number[]; policyId: string; policyRevision: number; actorId: string; actorRevision: number;
  keyId: string; publicKeyHex: string; issuedHeight: number; deadlineHeight: number;
}
export interface Evidence { kind: number; bytesHex: string; actor: ActorAuthorization | null }
export interface MapCommand {
  actionCommitmentHex: string; batch: boolean; mutations: Mutation[]; assignments: Assignment[]; evidence: Evidence[];
}

/** Command envelope `[1, actionBytes, [[kind, evidenceBytes]...]]`; the commitment covers the raw action bytes. */
export function decodeCommand(bodyHex: string): MapCommand {
  const envelope = asArray(decodeCbor(fromHex(bodyHex)), 3);
  requireVersion(envelope[0]);
  const actionBytes = asBytes(envelope[1]);
  const action = asArray(decodeCbor(actionBytes), 4);
  requireVersion(action[0]);
  const mutations = asArray(action[2]).map((item) => {
    const fields = asArray(item, 7);
    return {
      operation: asUnsigned(fields[0]), collection: asText(fields[1]),
      keyHex: toHex(asBytes(fields[2])), valueHex: toHex(asBytes(fields[3]))
    } satisfies Mutation;
  });
  const assignments = asArray(action[3]).map((item) => {
    const fields = asArray(item, 4);
    return {
      mutationIndex: asUnsigned(fields[0]), kind: asUnsigned(fields[1]),
      policyId: asText(fields[2]), evidenceHandle: asUnsigned(fields[3])
    } satisfies Assignment;
  });
  const evidence = asArray(envelope[2]).map((item) => {
    const fields = asArray(item, 2);
    const kind = asUnsigned(fields[0]);
    const bytes = asBytes(fields[1]);
    return { kind, bytesHex: toHex(bytes), actor: kind === 0 ? decodeActorAuthorization(bytes) : null } satisfies Evidence;
  });
  const preimage = new Uint8Array(ACTION_DOMAIN.length + actionBytes.length);
  preimage.set(ACTION_DOMAIN);
  preimage.set(actionBytes, ACTION_DOMAIN.length);
  return {
    actionCommitmentHex: toHex(blake2b256(preimage)),
    batch: asUnsigned(action[1]) === 1,
    mutations, assignments, evidence
  };
}

/** `[1, statement(15), signature]` where the statement is the actor's signed authorization. */
export function decodeActorAuthorization(bytes: Uint8Array): ActorAuthorization {
  const wrapper = asArray(decodeCbor(bytes), 3);
  requireVersion(wrapper[0]);
  const s = asArray(decodeCbor(asBytes(wrapper[1])), 15);
  requireVersion(s[0]);
  return {
    authorizationIdHex: toHex(asBytes(s[1], 32)), chainId: asText(s[2]), genesisIdHex: toHex(asBytes(s[3], 32)),
    actionCommitmentHex: toHex(asBytes(s[4], 32)), covered: asArray(s[5]).map((item) => asUnsigned(item)),
    policyId: asText(s[6]), policyRevision: asUnsigned(s[7]), actorId: asText(s[8]), actorRevision: asUnsigned(s[9]),
    keyId: asText(s[10]), publicKeyHex: toHex(asBytes(s[11], 32)), issuedHeight: asUnsigned(s[12]), deadlineHeight: asUnsigned(s[13])
  };
}

export interface Receipt {
  messageIdHex: string; height: number; applied: boolean; errorCode: number; batchCommitmentHex: string;
  results: Array<{ collection: string; keyHex: string; status: 'ACTIVE' | 'REVOKED'; revision: number; logicalValueHashHex: string }>;
}

/** `[1, messageId, height, status, errorCode, batchCommitment, resultCommitment, results]`. */
export function decodeReceipt(valueHex: string): Receipt {
  const items = asArray(decodeCbor(fromHex(valueHex)), 8);
  requireVersion(items[0]);
  return {
    messageIdHex: toHex(asBytes(items[1], 32)), height: asUnsigned(items[2]), applied: asUnsigned(items[3]) === 0,
    errorCode: asUnsigned(items[4]), batchCommitmentHex: toHex(asBytes(items[5], 32)),
    results: asArray(items[7]).map((item) => {
      const fields = asArray(item, 5);
      return {
        collection: asText(fields[0]), keyHex: toHex(asBytes(fields[1])),
        status: asUnsigned(fields[2]) === 0 ? 'ACTIVE' as const : 'REVOKED' as const,
        revision: asUnsigned(fields[3]), logicalValueHashHex: toHex(asBytes(fields[4], 32))
      };
    })
  };
}

/** Receipt query `[1, messageId]` and its result `[1, committedHeight, stateRoot, messageId, presence, receiptBytes]`. */
export function encodeReceiptQueryHex(messageIdHex: string): string {
  return toHex(encodeCbor([1n, fromHex(messageIdHex)]));
}

export function decodeReceiptResult(payloadHex: string): Receipt | null {
  const items = asArray(decodeCbor(fromHex(payloadHex)), 6);
  requireVersion(items[0]);
  if (asUnsigned(items[4]) === 0) return null;
  return decodeReceipt(toHex(asBytes(items[5])));
}

export interface Consumption {
  actorId: string; authorizationIdHex: string; actionCommitmentHex: string; appliedHeight: number; messageIdHex: string;
  policyId: string; policyRevision: number; actorRevision: number; organizationId: string; organizationRevision: number;
  role: string; keyId: string;
}

/** The one-use consumption record, 16 items. */
export function decodeConsumption(valueHex: string): Consumption {
  const s = asArray(decodeCbor(fromHex(valueHex)), 16);
  requireVersion(s[0]);
  return {
    actorId: asText(s[1]), authorizationIdHex: toHex(asBytes(s[2], 32)), actionCommitmentHex: toHex(asBytes(s[3], 32)),
    appliedHeight: asUnsigned(s[4]), messageIdHex: toHex(asBytes(s[5], 32)), policyId: asText(s[7]),
    policyRevision: asUnsigned(s[8]), actorRevision: asUnsigned(s[9]), organizationId: asText(s[10]),
    organizationRevision: asUnsigned(s[11]), role: asText(s[12]), keyId: asText(s[13])
  };
}

// ------------------------------------------------------------------ discovery

export interface Discovery { registry: boolean; collections: string[] | null; mapGenesisIdHex: string | null }

/**
 * Reads the map genesis returned by `capabilities-v1` (item 11 is the collection list, each
 * `[4, id, authorization, policyId, ...]`) and says whether the registry profile is declared.
 */
export function discoverRegistry(genesisHex: string | null): Discovery {
  if (!genesisHex) return { registry: false, collections: null, mapGenesisIdHex: null };
  const mapGenesisIdHex = mapGenesisId(genesisHex);
  try {
    const items = asArray(decodeCbor(fromHex(genesisHex)));
    if (items.length < 12) return { registry: false, collections: [], mapGenesisIdHex };
    const declared = new Map<string, { authorization: number; policyId: string }>();
    for (const item of asArray(items[11])) {
      const fields = asArray(item);
      if (fields.length < 4) continue;
      declared.set(asText(fields[1]), { authorization: asUnsigned(fields[2]), policyId: asText(fields[3]) });
    }
    const registry = COLLECTIONS.every((collection) => {
      const entry = declared.get(collection);
      if (!entry || entry.policyId !== POLICY_OF[collection]) return false;
      return collection === 'issuers' ? entry.authorization === AUTH_APPROVAL : entry.authorization === AUTH_GOVERNED_ROLE;
    });
    return { registry, collections: [...declared.keys()], mapGenesisIdHex };
  } catch {
    return { registry: false, collections: [], mapGenesisIdHex };
  }
}

/**
 * The map genesis id: blake2b-256 over the domain `yano-appchain-genesis-v1\0` and the genesis
 * bytes, which is what `AuthenticatedMapContract.genesisId` computes and what actor
 * authorizations are signed with. It is not the chain's state commitment identity: on a composite
 * runtime that identity is the application-profile-bound derivative of this id.
 */
export function mapGenesisId(genesisHex: string): string | null {
  try {
    const genesis = fromHex(genesisHex);
    const domain = new Uint8Array([...'yano-appchain-genesis-v1'].map((c) => c.charCodeAt(0)).concat(0));
    const input = new Uint8Array(domain.length + genesis.length);
    input.set(domain);
    input.set(genesis, domain.length);
    return toHex(blake2b256(input));
  } catch {
    return null;
  }
}

// ------------------------------------------------------------------ status lists and TRQP

/** Decodes a served `encodedList` (multibase `u` + base64url of GZIP) into raw bitstring bytes. */
export async function decodeEncodedList(encodedList: string, bitLength: number): Promise<Uint8Array> {
  if (!encodedList.startsWith('u')) throw new Error('encodedList must be multibase base64url (u...)');
  if (!Number.isInteger(bitLength) || bitLength < MIN_BIT_LENGTH || bitLength > MAX_BIT_LENGTH) {
    throw new Error('The bit length is outside the profile bound');
  }
  const compressed = base64UrlDecode(encodedList.slice(1));
  const raw = await gunzip(compressed, Math.ceil(MAX_BIT_LENGTH / 8) + 1);
  if (raw.length !== Math.ceil(bitLength / 8)) throw new Error('The bitstring length does not match the list bit length');
  return raw;
}

/** Bounded GZIP decompression through the platform stream; never more than `maxBytes` is kept. */
async function gunzip(compressed: Uint8Array, maxBytes: number): Promise<Uint8Array> {
  if (typeof DecompressionStream === 'undefined') throw new Error('This browser cannot decompress GZIP');
  const source = new ReadableStream<Uint8Array>({
    start(controller) {
      controller.enqueue(compressed);
      controller.close();
    }
  });
  // The platform typings disagree on the buffer generic between the source and the transform.
  const transform = new DecompressionStream('gzip') as unknown as ReadableWritablePair<Uint8Array, Uint8Array>;
  const reader = source.pipeThrough(transform).getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    if (!value) continue;
    total += value.length;
    if (total > maxBytes) {
      await reader.cancel();
      throw new Error('The decompressed list exceeds the profile bound');
    }
    chunks.push(value);
  }
  const out = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    out.set(chunk, offset);
    offset += chunk.length;
  }
  return out;
}

export function bitAt(raw: Uint8Array, index: number): boolean {
  return (raw[index >> 3] & (0x80 >> (index & 7))) !== 0;
}

export function setCount(raw: Uint8Array): number {
  let count = 0;
  for (const byte of raw) {
    let value = byte;
    while (value) { count += value & 1; value >>= 1; }
  }
  return count;
}

export interface TrqpAnswer { authorized: boolean; reason: string }

export function evaluateTrqp(
  presence: 'ACTIVE' | 'REVOKED' | 'ABSENT',
  issuer: IssuerValue | null,
  framework: string,
  authorization: string,
  height: number
): TrqpAnswer {
  if (presence === 'ABSENT') return { authorized: false, reason: 'entity is not registered' };
  if (presence === 'REVOKED') return { authorized: false, reason: 'entity registration was revoked' };
  if (!issuer) return { authorized: false, reason: 'entity record does not decode as an issuer' };
  if (issuer.framework !== framework) return { authorized: false, reason: `entity is registered under framework ${issuer.framework}` };
  if (!issuer.authorizations.includes(authorization)) return { authorized: false, reason: 'authorization is not granted' };
  if (height < issuer.validFromHeight) return { authorized: false, reason: `registration is not yet valid at height ${height}` };
  if (issuer.validUntilHeight !== 0 && height > issuer.validUntilHeight) {
    return { authorized: false, reason: `registration expired at height ${issuer.validUntilHeight}` };
  }
  return { authorized: true, reason: 'authorized' };
}

function base64UrlDecode(value: string): Uint8Array {
  const normalized = value.replace(/-/g, '+').replace(/_/g, '/');
  const padded = normalized + '='.repeat((4 - (normalized.length % 4)) % 4);
  const binary = atob(padded);
  const out = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) out[i] = binary.charCodeAt(i);
  return out;
}

function requireVersion(item: CborValue): void {
  if (asUnsigned(item) !== 1) throw new Error('Unsupported record version');
}
