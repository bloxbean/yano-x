// @vitest-environment node
//
// Opt-in end-to-end run of the browser attest and verify flows against a live doc-trail chain.
// Skipped unless ATTEST_LIVE_NODE_URL names a node origin (for example http://127.0.0.1:7070).
// Optional: ATTEST_LIVE_API_KEY, ATTEST_LIVE_API_PREFIX (default /api/v1), ATTEST_LIVE_CHAIN
// (default documents-chain). This is the same sequence the page runs, without the DOM.
import { describe, expect, it } from 'vitest';
import { YanoApi } from './api';
import { assembleCertificate, confirmWithNode, parseCertificate, verifyLocally } from './certificate';
import {
  DOC_TRAIL_TOPIC, decodeTrailHead, encodeAppendCommand, entityKeyHex, extractBlockStateRootHex,
  extractEnvelope, validateEntityId
} from './envelope';
import { sha256Hex } from './hash';
import { toHex } from './cbor';
import { isDocTrailChain } from './model';
import type { ActiveConnection, FinalizedMessage } from './types';

const nodeUrl = process.env.ATTEST_LIVE_NODE_URL?.trim() ?? '';
const apiPrefix = process.env.ATTEST_LIVE_API_PREFIX?.trim() || '/api/v1';
const chainId = process.env.ATTEST_LIVE_CHAIN?.trim() || 'documents-chain';
const apiKey = process.env.ATTEST_LIVE_API_KEY ?? '';

const connection: ActiveConnection = {
  endpointId: 'live',
  nodeUrl,
  apiPrefix,
  apiBase: `${nodeUrl}${apiPrefix}`,
  apiKey,
  label: 'live'
};

async function awaitFinalized(api: YanoApi, messageId: string): Promise<FinalizedMessage> {
  const deadline = Date.now() + 120_000;
  while (Date.now() < deadline) {
    const finalized = await api.finalizedMessage(chainId, messageId);
    if (finalized && finalized.height > 0) return finalized;
    await new Promise((resolve) => setTimeout(resolve, 1000));
  }
  throw new Error(`message ${messageId} was not finalized in time`);
}

describe.skipIf(!nodeUrl)('live attest flow against a doc-trail chain', () => {
  it('discovers the chain, attests a document, and verifies the certificate', async () => {
    const api = new YanoApi(connection, fetch);

    const discovered = await api.discoverChains();
    const chain = discovered.find((candidate) => candidate.summary.chainId === chainId);
    expect(chain, `chain ${chainId} must run doc-trail`).toBeDefined();
    expect(isDocTrailChain(chain!.status)).toBe(true);

    const document = new TextEncoder().encode(`attest live run ${Date.now()}\n`);
    const digestHex = await sha256Hex(document);
    const entityId = `live-${Date.now()}`;
    validateEntityId(entityId);
    const command = encodeAppendCommand(entityId, digestHex, 'live vitest run');

    const submitted = await api.submitMessage(chainId, DOC_TRAIL_TOPIC, toHex(command));
    expect(submitted.messageId).toMatch(/^[0-9a-f]{64}$/);
    const finalized = await awaitFinalized(api, submitted.messageId);

    const [proof, evidence, status] = await Promise.all([
      api.messageProof(chainId, submitted.messageId),
      api.evidence(chainId, submitted.messageId),
      api.chainStatus(chainId)
    ]);
    expect(proof.blockHeight).toBe(finalized.height);
    expect(proof.messageIndex).toBe(finalized.index);
    expect(evidence.messageId).toBe(submitted.messageId);

    const envelope = extractEnvelope(evidence.blocksCbor[0], finalized.index);
    expect(envelope.messageIdHex).toBe(submitted.messageId);

    const stateProof = await api.stateProof(chainId, entityKeyHex(entityId), finalized.height);
    expect(stateProof?.presence).toBe('PRESENT');
    const trailHead = decodeTrailHead(stateProof!.valueHex!);
    expect(trailHead.count).toBe(1);

    let anchorMode: string | null = null;
    let lastRoot: string | null = null;
    if (evidence.anchor) {
      const commitment = await api.anchorCommitment(chainId);
      anchorMode = commitment?.mode ?? status.anchor?.mode ?? 'unknown';
      lastRoot = extractBlockStateRootHex(evidence.blocksCbor[evidence.blocksCbor.length - 1]);
    }

    const issued = assembleCertificate({
      chainId,
      applicationId: status.capabilityManifest?.applicationId ?? null,
      finalized,
      envelope,
      messageProof: proof,
      evidence,
      stateProof,
      trailHead,
      anchorMode,
      lastBlockStateRootHex: lastRoot,
      metadata: { fileName: 'live.txt', sizeBytes: document.byteLength, mediaType: 'text/plain' }
    });
    expect(issued.subject.entityId).toBe(entityId);
    expect(issued.status).toBe(evidence.anchor ? 'ANCHORED' : 'FINALIZED');

    // The download round-trip: what the browser writes must parse and verify again.
    const reparsed = parseCertificate(JSON.stringify(issued, null, 2));
    const local = await verifyLocally(reparsed, document);
    const failed = local.checks.filter((check) => check.state === 'FAIL');
    expect(failed, JSON.stringify(failed)).toEqual([]);
    expect(local.digest).toBe('MATCH');
    expect(local.consistent).toBe(true);
    expect(['VERIFIED', 'UNAVAILABLE']).toContain(local.signature);

    const confirmation = await confirmWithNode(api, reparsed);
    expect(confirmation.block.state).toBe('PASS');
    expect(['PASS', 'SKIPPED', 'UNAVAILABLE']).toContain(confirmation.anchor.state);

    const tampered = await verifyLocally(reparsed, new TextEncoder().encode('other bytes'));
    expect(tampered.digest).toBe('MISMATCH');
  }, 180_000);
});
