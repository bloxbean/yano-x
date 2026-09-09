/**
 * In-browser actor signing (ADR-048 §4). The seed is held in a closure, never in component state,
 * storage, logs, or URLs, and is zeroed on release. Signing uses WebCrypto Ed25519; when the
 * runtime lacks it the caller falls back to the CLI instructions.
 */
import { fromHex, toHex } from './cbor';
import { statementPreimage } from './roles';
import type { ActorStatement, SignedActorCommand } from './types';

const PKCS8_PREFIX = fromHex('302e020100300506032b657004220420');
const SPKI_PREFIX_LENGTH = 12;

export interface ActorSigner {
  readonly publicKeyHex: string;
  sign(statement: ActorStatement): Promise<SignedActorCommand>;
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
  return fromHex(normalized);
}

/** Imports the seed once, derives the public key, and returns a signer that owns the seed. */
export async function createSigner(seed: Uint8Array): Promise<ActorSigner> {
  if (seed.length !== 32) throw new Error('The seed must be exactly 32 bytes');
  const subtle = globalThis.crypto?.subtle;
  if (!subtle) throw new Error('WebCrypto is unavailable in this browser');
  const pkcs8 = new Uint8Array(PKCS8_PREFIX.length + 32);
  pkcs8.set(PKCS8_PREFIX);
  pkcs8.set(seed, PKCS8_PREFIX.length);
  let privateKey: CryptoKey;
  let publicKeyHex: string;
  try {
    privateKey = await subtle.importKey('pkcs8', pkcs8 as BufferSource, { name: 'Ed25519' }, true, ['sign']);
    const jwk = await subtle.exportKey('jwk', privateKey);
    if (!jwk.x) throw new Error('missing public coordinate');
    publicKeyHex = toHex(base64UrlToBytes(jwk.x));
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
    publicKeyHex,
    async sign(statement: ActorStatement): Promise<SignedActorCommand> {
      if (released) throw new Error('The signer was released');
      const signature = new Uint8Array(await subtle.sign({ name: 'Ed25519' }, privateKey, statementPreimage(statement) as BufferSource));
      if (signature.length !== 64) throw new Error('Unexpected signature length');
      return { statement, signatureHex: toHex(signature) };
    },
    release() {
      released = true;
    }
  };
}

/** Derives the raw 32-byte public key from an SPKI export; exported for tests. */
export function rawFromSpki(spki: Uint8Array): Uint8Array {
  if (spki.length !== SPKI_PREFIX_LENGTH + 32) throw new Error('Unexpected SPKI length');
  return spki.slice(SPKI_PREFIX_LENGTH);
}

function base64UrlToBytes(value: string): Uint8Array {
  const base64 = value.replace(/-/g, '+').replace(/_/g, '/');
  const padded = base64 + '='.repeat((4 - (base64.length % 4)) % 4);
  const binary = atob(padded);
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index++) bytes[index] = binary.charCodeAt(index);
  return bytes;
}
