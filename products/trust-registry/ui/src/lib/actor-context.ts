/**
 * Reading an actor's signing context from the chain (ADR-053 §2.1). Browser signing needs the
 * actor's current revision and key id, the policy's current revision and authorization lifetime,
 * and the map genesis id, all of which the node already serves as ordinary state proofs. The
 * console reads them itself so BROWSER_KEY never depends on a gateway.
 *
 * The record layouts mirror `ActorRecordV1`, `ActorKeyEpochV1`, and `DirectRolePolicyV1` in
 * `capabilities/role-workflow-contracts`.
 */
import { asArray, asBytes, asText, asUnsigned, decodeCbor, fromHex, toHex } from './cbor';
import { ACTORS_COMPONENT, APPROVALS_COMPONENT, roleKey } from './registry';

/** `RecordStatus.ACTIVE` is code 0; SUSPENDED is 1 and REVOKED is 2. */
export const RECORD_ACTIVE = 0;

export interface ActorKeyEpoch {
  keyId: string;
  publicKeyHex: string;
  validFromHeight: bigint;
  validUntilHeight: bigint;
  status: number;
}

export interface ActorRecord {
  actorId: string;
  organizationId: string;
  revision: bigint;
  status: number;
  roles: string[];
  keys: ActorKeyEpoch[];
}

export interface DirectPolicy {
  policyId: string;
  revision: bigint;
  status: number;
  requiredRole: string;
  maximumAuthorizationLifetimeBlocks: bigint;
}

/** A `current` record holds the revision as eight big-endian bytes. */
export function decodePointer(valueHex: string): bigint {
  const bytes = fromHex(valueHex);
  if (bytes.length !== 8) throw new Error('A current pointer is eight bytes');
  let value = 0n;
  for (const byte of bytes) value = (value << 8n) | BigInt(byte);
  return value;
}

export function decodeActorRecord(valueHex: string): ActorRecord {
  const value = asArray(decodeCbor(fromHex(valueHex)), 8);
  if (asUnsigned(value[0]) !== 1) throw new Error('Unsupported actor record version');
  return {
    actorId: asText(value[1]),
    organizationId: asText(value[2]),
    revision: BigInt(asUnsigned(value[3])),
    status: asUnsigned(value[4]),
    roles: asArray(value[5]).map(asText),
    keys: asArray(value[6]).map((entry) => {
      const key = asArray(entry, 5);
      return {
        keyId: asText(key[0]),
        publicKeyHex: toHex(asBytes(key[1], 32)),
        validFromHeight: BigInt(asUnsigned(key[2])),
        validUntilHeight: BigInt(asUnsigned(key[3])),
        status: asUnsigned(key[4])
      };
    })
  };
}

export function decodeDirectPolicy(valueHex: string): DirectPolicy {
  const value = asArray(decodeCbor(fromHex(valueHex)), 6);
  if (asUnsigned(value[0]) !== 1) throw new Error('Unsupported policy record version');
  return {
    policyId: asText(value[1]),
    revision: BigInt(asUnsigned(value[2])),
    status: asUnsigned(value[3]),
    requiredRole: asText(value[4]),
    maximumAuthorizationLifetimeBlocks: BigInt(asUnsigned(value[5]))
  };
}

/**
 * The key epoch to sign with: active, in force at this height, and open ended or not yet expired.
 * The chain applies the same rule, so a key this rejects would be rejected there too.
 */
export function activeKey(record: ActorRecord, height: bigint): ActorKeyEpoch {
  const usable = record.keys.filter((key) => key.status === RECORD_ACTIVE
    && key.validFromHeight <= height
    && (key.validUntilHeight === 0n || key.validUntilHeight >= height));
  if (usable.length === 0) {
    throw new Error(`${record.actorId} has no active key at height ${height}`);
  }
  return usable[usable.length - 1];
}

export function actorCurrentKey(actorId: string): Uint8Array {
  return roleKey(ACTORS_COMPONENT, `a/${actorId}/current`);
}

export function actorRevisionKey(actorId: string, revision: bigint): Uint8Array {
  return roleKey(ACTORS_COMPONENT, `a/${actorId}/r/${revision}`);
}

export function directPolicyCurrentKey(policyId: string): Uint8Array {
  return roleKey(APPROVALS_COMPONENT, `d/${policyId}/current`);
}

export function directPolicyRevisionKey(policyId: string, revision: bigint): Uint8Array {
  return roleKey(APPROVALS_COMPONENT, `d/${policyId}/r/${revision}`);
}

