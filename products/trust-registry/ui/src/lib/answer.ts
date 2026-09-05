import type { YanoApi } from './api';
import { fromHex, toHex, utf8 } from './cbor';
import {
  ACTORS_COMPONENT,
  APPROVALS_COMPONENT,
  AUTH_GOVERNED_ROLE,
  COMMAND_TOPIC,
  consumptionPhysicalKey,
  decodeCommand,
  decodeConsumption,
  decodeEntry,
  decodeReceiptResult,
  decodeValue,
  encodeReceiptQueryHex,
  entryPhysicalKey,
  genesisMarkerPhysicalKey,
  receiptPhysicalKey,
  roleKey
} from './registry';
import type {
  AnswerFact,
  Binding,
  Collection,
  DiscoveredChain,
  MapEntry,
  Presence,
  Provenance,
  RegistryAnswer,
  StateProofEnvelope
} from './types';

/**
 * Assembles the same answer the JVM client assembles (ADR-049 §2.2): the entry's state proof
 * at the answer height, the receipt and the actor-side records for governed writes, the map
 * genesis marker, and the evidence bundle carrying the certified block. The browser checks
 * that every proof names one root and that the finalized block agrees; the MPF paths and the
 * finality certificate are verified by `yano-trust verify` on the export.
 */
export async function readAnswer(
  api: YanoApi,
  chain: DiscoveredChain,
  collection: Collection,
  key: Uint8Array,
  requestedHeight?: number
): Promise<RegistryAnswer> {
  const chainId = chain.summary.chainId;
  const notes: string[] = [];
  const height = requestedHeight ?? (await api.chainStatus(chainId)).tipHeight ?? chain.summary.tipHeight;
  if (!height || height < 1) throw new Error('The chain has not finalized a block yet');
  const entryKey = entryPhysicalKey(collection, key);
  const entryProof = await requireProof(api, chainId, entryKey, height);
  let entry: MapEntry | null = null;
  if (entryProof.presence === 'PRESENT') {
    if (!entryProof.valueHex) throw new Error('The present state proof carries no value');
    entry = decodeEntry(entryProof.valueHex);
  } else if (entryProof.presence !== 'ABSENT') {
    throw new Error(`Unexpected state proof presence ${entryProof.presence}`);
  }
  const presence: Presence = entry === null ? 'ABSENT' : entry.status;
  const facts: AnswerFact[] = [{ name: 'entry', keyHex: toHex(entryKey), valueHex: entryProof.valueHex ?? null, proof: entryProof }];
  let provenance: Provenance = entry === null ? { kind: 'NONE' } : { kind: 'GENESIS' };
  let actionCommitmentHex: string | null = null;
  let authorizationEvidenceHex: string | null = null;

  if (entry && entry.lastMutationHeight > 0) {
    const located = await locate(api, chainId, entry, collection, key);
    if (!located) {
      notes.push(`No applied command for revision ${entry.revision} was found in block ${entry.lastMutationHeight}`);
    } else {
      const receiptKey = receiptPhysicalKey(fromHex(located.messageIdHex));
      const receiptProof = await requireProof(api, chainId, receiptKey, height);
      facts.push({ name: 'receipt', keyHex: toHex(receiptKey), valueHex: receiptProof.valueHex ?? null, proof: receiptProof });
      actionCommitmentHex = located.command.actionCommitmentHex;
      provenance = { kind: 'RECEIPT', messageIdHex: located.messageIdHex, appliedHeight: located.appliedHeight };
      const assignment = located.command.assignments[located.index];
      const evidence = assignment && assignment.kind === AUTH_GOVERNED_ROLE
        ? located.command.evidence[assignment.evidenceHandle - 1] : undefined;
      if (evidence?.actor) {
        authorizationEvidenceHex = evidence.bytesHex;
        const consumptionKey = consumptionPhysicalKey(evidence.actor.actorId, fromHex(evidence.actor.authorizationIdHex));
        const consumptionProof = await requireProof(api, chainId, consumptionKey, height);
        if (!consumptionProof.valueHex) throw new Error('The direct consumption record is not present');
        const consumption = decodeConsumption(consumptionProof.valueHex);
        facts.push({ name: 'direct-consumption', keyHex: toHex(consumptionKey), valueHex: consumptionProof.valueHex, proof: consumptionProof });
        await addPresent(api, chainId, facts, 'direct-policy', roleKey(APPROVALS_COMPONENT, `d/${consumption.policyId}/r/${consumption.policyRevision}`), height);
        await addPresent(api, chainId, facts, 'direct-policy-current', roleKey(APPROVALS_COMPONENT, `d/${consumption.policyId}/current`), height);
        await addPresent(api, chainId, facts, 'actor', roleKey(ACTORS_COMPONENT, `a/${consumption.actorId}/r/${consumption.actorRevision}`), height);
        await addPresent(api, chainId, facts, 'actor-current', roleKey(ACTORS_COMPONENT, `a/${consumption.actorId}/current`), height);
        await addPresent(api, chainId, facts, 'organization', roleKey(ACTORS_COMPONENT, `o/${consumption.organizationId}/r/${consumption.organizationRevision}`), height);
        await addPresent(api, chainId, facts, 'genesis-marker', genesisMarkerPhysicalKey(), height);
        provenance = {
          kind: 'DIRECT_ROLE', messageIdHex: consumption.messageIdHex, appliedHeight: consumption.appliedHeight,
          actorId: consumption.actorId, organizationId: consumption.organizationId, keyId: consumption.keyId,
          policyId: consumption.policyId, policyRevision: consumption.policyRevision, role: consumption.role
        };
      }
    }
  }

  const block = await api.block(chainId, height);
  if (!block) throw new Error(`Block ${height} is not retained by the node`);
  const blockHashHex = entryProof.block?.blockHash ?? '';
  let binding: Binding = 'BOUND';
  for (const fact of facts) {
    if (fact.proof.committedHeight !== height || fact.proof.stateRoot !== entryProof.stateRoot
        || fact.proof.chainId !== chainId || fact.proof.key !== fact.keyHex
        || fact.proof.genesisId !== chain.identity.genesisId
        || (fact.proof.block?.blockHash ?? '') !== blockHashHex) {
      binding = 'MISMATCH';
      notes.push(`Fact ${fact.name} does not name the answer's chain, genesis, height, root, and block`);
    }
  }
  if (block.stateRoot !== entryProof.stateRoot || block.height !== height) {
    binding = 'MISMATCH';
    notes.push('The finalized block at the answer height carries a different state root');
  }
  if (!blockHashHex) {
    binding = 'MISMATCH';
    notes.push('The state proof carries no certified block header');
  }
  const evidenceMessage = block.messages[0]?.messageId;
  const evidence = evidenceMessage ? await api.evidence(chainId, evidenceMessage) : null;
  if (!evidence) {
    binding = 'MISMATCH';
    notes.push('The evidence bundle of the answer block is unavailable; the export cannot be verified offline');
  }

  return {
    chainId,
    profile: entryProof.profile,
    genesisIdHex: entryProof.genesisId,
    height,
    stateRootHex: entryProof.stateRoot,
    blockHashHex,
    collection,
    keyHex: toHex(key),
    keyText: printable(key),
    presence,
    entry,
    decoded: entry && entry.status === 'ACTIVE' ? decodeValue(collection, entry.valueHex) : null,
    provenance,
    actionCommitmentHex,
    authorizationEvidenceHex,
    facts,
    evidence: evidence ?? {},
    binding,
    certSignatures: entryProof.finalityCertificate?.signatures.length ?? 0,
    notes
  };
}

/** The `trust-registry-answer-v1` document `yano-trust verify` accepts. */
export function exportAnswer(answer: RegistryAnswer): string {
  const document: Record<string, unknown> = {
    schemaVersion: 1,
    type: 'trust-registry-answer-v1',
    chainId: answer.chainId,
    profile: answer.profile,
    genesisId: answer.genesisIdHex,
    height: answer.height,
    stateRoot: answer.stateRootHex,
    blockHash: answer.blockHashHex,
    collection: answer.collection,
    keyHex: answer.keyHex,
    ...(answer.keyText === null ? {} : { key: answer.keyText }),
    presence: answer.presence,
    ...(answer.entry === null ? {} : {
      entry: {
        status: answer.entry.status,
        revision: answer.entry.revision,
        controllerHex: answer.entry.controllerHex,
        valueHex: answer.entry.valueHex,
        logicalValueHash: answer.entry.logicalValueHashHex,
        createdHeight: answer.entry.createdHeight,
        lastMutationHeight: answer.entry.lastMutationHeight
      }
    }),
    provenance: {
      kind: answer.provenance.kind,
      ...(answer.provenance.messageIdHex ? { messageId: answer.provenance.messageIdHex } : {}),
      ...(answer.provenance.appliedHeight ? { appliedHeight: answer.provenance.appliedHeight } : {}),
      ...(answer.provenance.actorId ? {
        actorId: answer.provenance.actorId,
        organizationId: answer.provenance.organizationId,
        keyId: answer.provenance.keyId,
        policyId: answer.provenance.policyId,
        policyRevision: answer.provenance.policyRevision,
        role: answer.provenance.role
      } : {})
    },
    ...(answer.actionCommitmentHex ? { actionCommitmentHex: answer.actionCommitmentHex } : {}),
    ...(answer.authorizationEvidenceHex ? { authorizationEvidenceHex: answer.authorizationEvidenceHex } : {}),
    facts: answer.facts.map((fact) => ({
      name: fact.name,
      keyHex: fact.keyHex,
      ...(fact.valueHex === null ? {} : { valueHex: fact.valueHex }),
      proof: fact.proof
    })),
    evidence: answer.evidence
  };
  return JSON.stringify(document, null, 2);
}

interface Located { messageIdHex: string; appliedHeight: number; command: ReturnType<typeof decodeCommand>; index: number }

async function locate(api: YanoApi, chainId: string, entry: MapEntry, collection: Collection, key: Uint8Array): Promise<Located | null> {
  const block = await api.block(chainId, entry.lastMutationHeight);
  if (!block) throw new Error(`Block ${entry.lastMutationHeight} is not retained by the node`);
  const keyHex = toHex(key);
  for (const message of block.messages) {
    if (message.topic !== COMMAND_TOPIC) continue;
    let command;
    try {
      command = decodeCommand(message.bodyHex);
    } catch {
      continue;
    }
    const index = command.mutations.findIndex((mutation) => mutation.collection === collection && mutation.keyHex === keyHex);
    if (index < 0) continue;
    const result = await api.query(chainId, 'authenticated-map/receipt-v1', encodeReceiptQueryHex(message.messageId));
    const receipt = decodeReceiptResult(result.payloadHex);
    if (!receipt || !receipt.applied) continue;
    const produced = receipt.results.some((item) => item.collection === collection && item.keyHex === keyHex
      && item.revision === entry.revision && item.status === entry.status);
    if (produced) return { messageIdHex: message.messageId, appliedHeight: receipt.height, command, index };
  }
  return null;
}

async function requireProof(api: YanoApi, chainId: string, physicalKey: Uint8Array, height: number): Promise<StateProofEnvelope> {
  const proof = await api.stateProof(chainId, toHex(physicalKey), height);
  if (!proof) throw new Error(`The node retains no state proof at height ${height}`);
  return proof;
}

async function addPresent(api: YanoApi, chainId: string, facts: AnswerFact[], name: string, physicalKey: Uint8Array, height: number): Promise<void> {
  const proof = await requireProof(api, chainId, physicalKey, height);
  if (proof.presence !== 'PRESENT' || !proof.valueHex) throw new Error(`The ${name} record is not present on the chain`);
  facts.push({ name, keyHex: toHex(physicalKey), valueHex: proof.valueHex, proof });
}

function printable(key: Uint8Array): string | null {
  for (const byte of key) {
    if (byte < 0x20 || byte > 0x7e) return null;
  }
  return new TextDecoder().decode(key);
}

export function keyBytesOf(text: string): Uint8Array {
  return utf8(text);
}
