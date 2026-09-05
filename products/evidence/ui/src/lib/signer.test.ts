// @vitest-environment node
import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { toHex } from './cbor';
import { ed25519Verify } from './hash';
import { encodeSignedCommand, statementPreimage } from './roles';
import { createSigner, parseSeed, webCryptoEd25519Available } from './signer';
import type { ActorStatement } from './types';

const golden = JSON.parse(readFileSync(new URL('./fixtures/golden-role-workflow.json', import.meta.url), 'utf8')) as {
  actors: Record<string, { seedHex: string; publicKeyHex: string }>;
  propose: ActorStatement & { signatureHex: string; commandHex: string };
  approveA: ActorStatement & { signatureHex: string; commandHex: string };
};

function statementOf(entry: ActorStatement): ActorStatement {
  const { action, chainId, proposalId, policyId, policyRevision, payloadDomain, payloadHashHex,
    deadlineHeight, actorId, actorRevision, keyId, clauseId } = entry;
  return { action, chainId, proposalId, policyId, policyRevision, payloadDomain, payloadHashHex,
    deadlineHeight, actorId, actorRevision, keyId, clauseId };
}

describe.skipIf(!webCryptoEd25519Available())('browser actor signer', () => {
  it('derives the registered public key from the demo seed and reproduces the Java signature', async () => {
    for (const [actorId, entry] of [['issuer-a', golden.propose], ['auditor-a', golden.approveA]] as const) {
      const signer = await createSigner(parseSeed(golden.actors[actorId].seedHex));
      expect(signer.publicKeyHex).toBe(golden.actors[actorId].publicKeyHex);
      const signed = await signer.sign(statementOf(entry));
      expect(signed.signatureHex).toBe(entry.signatureHex);
      expect(toHex(encodeSignedCommand(signed))).toBe(entry.commandHex);
      signer.release();
      await expect(signer.sign(statementOf(entry))).rejects.toThrow('released');
    }
  });

  it('produces signatures the verifier accepts and rejects a tampered statement', async () => {
    const signer = await createSigner(parseSeed(golden.actors['auditor-b'].seedHex));
    const statement = { ...statementOf(golden.approveA), actorId: 'auditor-b', keyId: 'auditor-b-k1' };
    const signed = await signer.sign(statement);
    const publicKey = hexBytes(golden.actors['auditor-b'].publicKeyHex);
    expect(await ed25519Verify(publicKey, hexBytes(signed.signatureHex), statementPreimage(statement))).toBe(true);
    expect(await ed25519Verify(publicKey, hexBytes(signed.signatureHex),
      statementPreimage({ ...statement, deadlineHeight: statement.deadlineHeight + 1 }))).toBe(false);
  });

  it('parses seeds from hex text, whitespace-padded text, and raw bytes', () => {
    const seed = golden.actors['issuer-a'].seedHex;
    expect(toHex(parseSeed(` ${seed.toUpperCase()}\n`))).toBe(seed);
    expect(toHex(parseSeed(hexBytes(seed)))).toBe(seed);
    expect(toHex(parseSeed(new TextEncoder().encode(seed)))).toBe(seed);
    expect(() => parseSeed('abcd')).toThrow('64 hex');
  });
});

function hexBytes(value: string): Uint8Array {
  return Uint8Array.from(value.match(/../g)!.map((pair) => parseInt(pair, 16)));
}
