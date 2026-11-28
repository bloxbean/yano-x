import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { encode } from './cbor';
import { parseCoseSign1 } from './cose';
import { fromHex, toHex } from './hex';
import {
  bodyFromJson, credentialFromAddress, encodeEnvelope, intentId, ownerMessage, sessionMessage,
  type Action, type Authorizer, type IntentDomain
} from './intents';

/** The Java golden vectors: the single source of truth for every byte below. */
const VECTORS = fileURLToPath(new URL(
  '../../../../ledgers/asset-payments/ledger/src/test/resources/asset-payments/intent-vectors-v1.json',
  import.meta.url));

interface Vector {
  name: string;
  action: Action;
  accountId: string;
  authorizer: Authorizer;
  nonce: string;
  notAfterMs: number;
  body: Record<string, unknown>;
  bodyHex: string;
  envelopeHex: string;
  intentId: string;
  coseSign1Hex?: string;
  coseKeyHex?: string;
  sessionSignatureHex?: string;
  messageBodyHex: string;
}

const vectors = JSON.parse(readFileSync(VECTORS, 'utf8')) as {
  domain: IntentDomain;
  accounts: { addressHex: string; credentialKind: number; keyHash: string; accountId: string }[];
  intents: Vector[];
  sessionSeedHex: string;
  sessionPublicKeyHex: string;
};

describe('intent encoding matches the Java vectors', () => {
  it('covers every action', () => {
    expect(new Set(vectors.intents.map((vector) => vector.action))).toEqual(new Set([
      'REGISTER_ACCOUNT', 'TRANSFER', 'AUTHORIZE_SESSION', 'REVOKE_SESSION', 'REQUEST_WITHDRAWAL', 'ROTATE_OWNER'
    ]));
  });

  for (const vector of vectors.intents) {
    it(`${vector.name}: body, envelope, intent id and message`, () => {
      const body = bodyFromJson(vector.action, vector.body);
      expect(toHex(body)).toBe(vector.bodyHex);
      const envelope = encodeEnvelope({
        domain: vectors.domain, action: vector.action, accountId: vector.accountId,
        authorizer: vector.authorizer, nonce: vector.nonce, notAfterMs: vector.notAfterMs, body
      });
      expect(toHex(envelope)).toBe(vector.envelopeHex);
      expect(intentId(envelope)).toBe(vector.intentId);
      const message = vector.authorizer.kind === 'SESSION'
        ? sessionMessage(envelope, fromHex(vector.sessionSignatureHex!))
        : ownerMessage(fromHex(vector.coseSign1Hex!), fromHex(vector.coseKeyHex!));
      expect(toHex(message)).toBe(vector.messageBodyHex);
    });
  }

  it('derives account ids from payment, base and reward addresses', () => {
    for (const account of vectors.accounts) {
      const credential = credentialFromAddress(fromHex(account.addressHex));
      expect(credential.kind).toBe(account.credentialKind);
      expect(credential.keyHash).toBe(account.keyHash);
      expect(credential.accountId).toBe(account.accountId);
    }
  });

  it('refuses script and Byron addresses', () => {
    const script = fromHex(vectors.accounts[0].addressHex);
    script[0] = 0x70;
    expect(() => credentialFromAddress(script)).toThrow(/no key credential/);
    const byron = new Uint8Array(40);
    byron[0] = 0x82;
    expect(() => credentialFromAddress(byron)).toThrow();
  });

  it('reads the payload and address a COSE_Sign1 commits to', () => {
    const owner = vectors.intents.find((vector) => vector.name === 'register')!;
    const view = parseCoseSign1(fromHex(owner.coseSign1Hex!));
    expect(toHex(view.payload!)).toBe(owner.envelopeHex);
    expect(toHex(view.protectedAddress!)).toBe(vectors.accounts[0].addressHex);
  });

  it('signs a session intent with WebCrypto Ed25519 to the vector signature', async () => {
    const session = vectors.intents.find((vector) => vector.authorizer.kind === 'SESSION')!;
    // PKCS#8 for a raw Ed25519 seed (RFC 8410).
    const pkcs8 = new Uint8Array([...fromHex('302e020100300506032b657004220420'), ...fromHex(vectors.sessionSeedHex)]);
    const key = await crypto.subtle.importKey('pkcs8', pkcs8, { name: 'Ed25519' }, false, ['sign']);
    const signature = new Uint8Array(await crypto.subtle.sign('Ed25519', key, new Uint8Array(fromHex(session.envelopeHex))));
    expect(toHex(signature)).toBe(session.sessionSignatureHex);
  });
});

describe('canonical CBOR subset', () => {
  it('uses the shortest heads', () => {
    expect(toHex(encode([0, 23, 24, 255, 256, 65535, 65536, 4294967295, 4294967296n]))).toBe(
      '89' + '00' + '17' + '1818' + '18ff' + '190100' + '19ffff' + '1a00010000' + '1affffffff' + '1b0000000100000000');
    expect(toHex(encode(['', new Uint8Array(0), []]))).toBe('836040' + '80');
    expect(() => encode([1n << 64n])).toThrow();
    expect(() => encode([-1])).toThrow();
  });
});
