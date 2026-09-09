// @vitest-environment node
//
// Opt-in end-to-end run of the desk flows against a live document-review chain (ADR-048 §9, §10).
// Skipped unless EVIDENCE_DESK_LIVE_NODE_URL names a node origin (for example http://127.0.0.1:7070).
// Optional: EVIDENCE_DESK_LIVE_API_KEY, EVIDENCE_DESK_LIVE_API_PREFIX (default /api/v1),
// EVIDENCE_DESK_LIVE_CHAIN (default document-review-chain). Uses the showcase demo actor seeds,
// sha256("yano-showcase-demo-actor:<actorId>"), which are showcase-only material.
import { createHash } from 'node:crypto';
import { describe, expect, it } from 'vitest';
import { DOCUMENT_REVIEW, actionCommitmentHex } from './adapters';
import { YanoApi } from './api';
import { toHex } from './cbor';
import {
  decisionStatement,
  documentReviewCommand,
  matchSigningKey,
  proposalIds,
  proposalStatement,
  readActor,
  readDocumentHead,
  readPolicy,
  readProposal,
  readReceipt,
  releaseBodyHex,
  scanCommands,
  submitStatement,
  awaitFinalized
} from './desk';
import { clauseProgress, encodeSignedCommand } from './roles';
import { createSigner } from './signer';
import type { ActiveConnection } from './types';

const nodeUrl = process.env.EVIDENCE_DESK_LIVE_NODE_URL?.trim() ?? '';
const apiPrefix = process.env.EVIDENCE_DESK_LIVE_API_PREFIX?.trim() || '/api/v1';
const chainId = process.env.EVIDENCE_DESK_LIVE_CHAIN?.trim() || 'document-review-chain';
const apiKey = process.env.EVIDENCE_DESK_LIVE_API_KEY ?? '';

const connection: ActiveConnection = {
  endpointId: 'live', nodeUrl, apiPrefix, apiBase: `${nodeUrl}${apiPrefix}`, apiKey, label: 'live'
};

function demoSeed(actorId: string): Uint8Array {
  return new Uint8Array(createHash('sha256').update(`yano-showcase-demo-actor:${actorId}`).digest());
}

describe.skipIf(!nodeUrl)('live desk flow on a document-review chain', () => {
  it('proposes, decides, releases, and reads every record back with its proof', async () => {
    const api = new YanoApi(connection, fetch);
    const chain = (await api.discoverChains()).find((candidate) => candidate.summary.chainId === chainId);
    expect(chain, `chain ${chainId} must carry the role components`).toBeDefined();
    expect(chain!.adapter).toBe('document-review');

    const tip = () => api.chainStatus(chainId).then((status) => status.tipHeight ?? 0);
    const policy = await readPolicy(api, chainId, DOCUMENT_REVIEW.policyId);
    expect(policy?.proof).toBe('BOUND');

    const identityFor = async (actorId: string) => {
      const actor = await readActor(api, chainId, actorId);
      expect(actor?.proof).toBe('BOUND');
      const signer = await createSigner(demoSeed(actorId));
      const identity = matchSigningKey(actor!.record, signer.publicKeyHex, await tip());
      expect(identity, `${actorId} must sign with its ACTIVE key`).not.toBeNull();
      return { signer, identity: identity! };
    };
    const issuer = await identityFor('issuer-a');
    const auditorA = await identityFor('auditor-a');
    const auditorB = await identityFor('auditor-b');

    // Propose: the release inputs are fixed now and hashed into the payload.
    const suffix = Date.now().toString(36);
    const proposalId = `desk-live-${suffix}`;
    const documentHashHex = toHex(new Uint8Array(createHash('sha256').update(`desk live ${suffix}`).digest()));
    const command = documentReviewCommand(proposalId, policy!.record, `document-${suffix}`, documentHashHex, `desk://live/${suffix}`);
    const propose = proposalStatement(chainId, issuer.identity, policy!.record, 'document-review', proposalId,
      actionCommitmentHex(command), await tip());
    const proposed = await submitStatement(api, chainId, toHex(encodeSignedCommand(await issuer.signer.sign(propose))));
    expect(proposed.result?.record.resultCode).toBe('ACCEPTED');
    expect(proposed.result?.proof).toBe('BOUND');

    let proposal = await readProposal(api, chainId, proposalId);
    expect(proposal?.record.status).toBe('PENDING');
    expect(proposal?.record.payloadHashHex).toBe(actionCommitmentHex(command));

    // Negative: the issuer holds no auditor role.
    const issuerApprove = decisionStatement(chainId, issuer.identity, proposal!.record, 'APPROVE', 'independent-auditors', await tip());
    const mismatch = await submitStatement(api, chainId, toHex(encodeSignedCommand(await issuer.signer.sign(issuerApprove))));
    expect(mismatch.result?.record.resultCode).toBe('ROLE_MISMATCH');

    // First auditor approves; the same statement again is an exact replay.
    const approveA = decisionStatement(chainId, auditorA.identity, proposal!.record, 'APPROVE', 'independent-auditors', await tip());
    const signedA = toHex(encodeSignedCommand(await auditorA.signer.sign(approveA)));
    expect((await submitStatement(api, chainId, signedA)).result?.record.resultCode).toBe('ACCEPTED');
    // A second submission carries the same body but a new envelope, so it finalizes as a new message.
    expect((await submitStatement(api, chainId, signedA)).result?.record.resultCode).toBe('EXACT_REPLAY');

    proposal = await readProposal(api, chainId, proposalId);
    expect(proposal?.record.status).toBe('PENDING');
    expect(clauseProgress(policy!.record, proposal!.record.decisions)[0].distinctCount).toBe(1);

    // Second organization approves; the clause is satisfied.
    const approveB = decisionStatement(chainId, auditorB.identity, proposal!.record, 'APPROVE', 'independent-auditors', await tip());
    expect((await submitStatement(api, chainId, toHex(encodeSignedCommand(await auditorB.signer.sign(approveB))))).result?.record.resultCode).toBe('ACCEPTED');
    proposal = await readProposal(api, chainId, proposalId);
    expect(proposal?.record.status).toBe('APPROVED');
    expect(proposal?.proof).toBe('BOUND');

    // Release with the same inputs; the receipt and the document head bind to one root.
    const submitted = await api.submitMessage(chainId, DOCUMENT_REVIEW.topic, releaseBodyHex(command, proposal!.record));
    const finalized = await awaitFinalized(api, chainId, submitted.messageId);
    const receipt = await readReceipt(api, chainId, proposalId, finalized.height);
    expect(receipt?.proof).toBe('BOUND');
    expect(receipt?.record.actionCommitmentHex).toBe(proposal!.record.payloadHashHex);
    expect(receipt?.record.messageIdHex).toBe(submitted.messageId);
    const head = await readDocumentHead(api, chainId, command.documentEntityId, finalized.height);
    expect(head?.proof).toBe('BOUND');
    expect(head?.record.count).toBe(1);
    expect(head?.stateRootHex).toBe(receipt?.stateRootHex);

    // Wrong inputs never reach the node.
    expect(() => releaseBodyHex({ ...command, documentRef: 'other' }, proposal!.record)).toThrow('payload hash');

    // The bounded scan lists the proposal with its release.
    const scan = await scanCommands(api, chainId, await tip(), 'document-review');
    expect(proposalIds(scan.commands)).toContain(proposalId);
    expect(scan.commands.some((entry) => entry.proposalId === proposalId && entry.action === 'RELEASE')).toBe(true);

    issuer.signer.release();
    auditorA.signer.release();
    auditorB.signer.release();
  }, 240_000);
});
