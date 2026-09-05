import { asArray, asBytes, asSigned, asText, asUnsigned, decodeCbor, encodeCbor, fromHex, toHex, utf8 } from './cbor';
import { sha256Hex } from './hash';
import type { AnswerDocument, FeedValue, ObservationValue, RecordValue, RoundDocument } from './types';

export const RULE_ID = 'feed-aggregation-v1';
export const DATUM_ID = 'feed-datum-candidate-v1';
export const ROUND_CLOSED = 1;
export const ROUND_NO_QUORUM = 2;
export const FEED_ID = /^[a-z0-9][a-z0-9._-]{0,31}$/;
export const SOURCE_ID = /^[a-z][a-z0-9-]{0,62}$/;
const PPM = 1_000_000n;
const MAX_SOURCES = 16;

// ------------------------------------------------------------------ identifiers

export function parseFeedInput(input: string): string {
  const text = input.trim();
  if (!text) throw new Error('Enter a feed id');
  if (!FEED_ID.test(text)) throw new Error('A feed id is at most 32 lowercase letters, digits, . _ -');
  return text;
}

export function parseRoundInput(input: string): string {
  const text = input.trim().toLowerCase();
  if (!text || text === 'latest') return 'latest';
  if (!/^(0|[1-9][0-9]{0,18})$/.test(text)) throw new Error('A round is a non-negative integer, or "latest"');
  return text;
}

export function keyText(keyHex: string): string {
  return new TextDecoder().decode(fromHex(keyHex));
}

// ------------------------------------------------------------------ values

export function decodeFeed(valueHex: string): FeedValue {
  const items = asArray(decodeCbor(fromHex(valueHex)), 13);
  requireVersion(items[0]);
  const sources = asArray(items[6]).map((item) => asText(item));
  if (sources.length < 1 || sources.length > MAX_SOURCES) throw new Error('A feed lists 1-16 sources');
  return {
    description: asText(items[1]), unit: asText(items[2]), scale: asUnsigned(items[3]),
    epochStart: asSigned(items[4]), roundSeconds: asSigned(items[5]), sources,
    minimumSources: asUnsigned(items[7]), maximumDeviationPpm: asSigned(items[8]),
    maximumDeviationAbsolute: asSigned(items[9]), minimumValue: asSigned(items[10]),
    maximumValue: asSigned(items[11]), status: asUnsigned(items[12])
  };
}

export function decodeObservation(valueHex: string): ObservationValue {
  const items = asArray(decodeCbor(fromHex(valueHex)), 5);
  requireVersion(items[0]);
  return {
    value: asSigned(items[1]), observedAt: asSigned(items[2]),
    evidenceSha256: toHex(asBytes(items[3])), note: asText(items[4])
  };
}

export function decodeRecord(valueHex: string): RecordValue {
  const items = asArray(decodeCbor(fromHex(valueHex)), 8);
  requireVersion(items[0]);
  return {
    status: asUnsigned(items[1]), closedAtHeight: asUnsigned(items[2]), aggregate: asSigned(items[3]),
    scale: asUnsigned(items[4]), acceptedSources: asArray(items[5]).map((item) => asText(item)),
    policySha256: toHex(asBytes(items[6], 32)), datumSha256: toHex(asBytes(items[7]))
  };
}

function requireVersion(item: unknown) {
  if (asUnsigned(item as never) !== 1) throw new Error('Unsupported feed value version');
}

export function roundStart(feed: FeedValue, round: bigint): bigint {
  return feed.epochStart + round * feed.roundSeconds;
}

export function roundEnd(feed: FeedValue, round: bigint): bigint {
  return roundStart(feed, round + 1n) - 1n;
}

export function roundOf(feed: FeedValue, observedAt: bigint): bigint {
  if (observedAt < feed.epochStart) throw new Error('The time precedes the feed\'s epoch start');
  return (observedAt - feed.epochStart) / feed.roundSeconds;
}

// ------------------------------------------------------------------ aggregation (feed-aggregation-v1)

export type Disposition = 'ACCEPTED' | 'OUTLIER' | 'ABSENT' | 'REVOKED' | 'FOREIGN_WRITER' | 'EQUIVOCATED'
  | 'WRONG_ROUND' | 'OUT_OF_RANGE';

export interface SourceInput {
  sourceId: string;
  value: ObservationValue | null;
  revoked: boolean;
  writerActorId: string | null;
  revision: number;
}

export interface SourceResult {
  sourceId: string;
  disposition: Disposition;
  value: bigint | null;
  observedAt: bigint | null;
}

export interface AggregationResult {
  status: number;
  statusName: 'CLOSED' | 'NO_QUORUM';
  aggregate: bigint;
  scale: number;
  acceptedSources: string[];
  sources: SourceResult[];
  candidateCount: number;
}

/** ADR-052 §2.1, the same integer rules as the JVM's `Aggregation`; inputs follow the feed's source order. */
export function aggregate(feed: FeedValue, round: bigint, inputs: SourceInput[]): AggregationResult {
  if (inputs.length !== feed.sources.length) throw new Error('One input per configured source is required');
  const start = roundStart(feed, round);
  const end = roundEnd(feed, round);
  const results: SourceResult[] = [];
  const candidates: SourceResult[] = [];
  inputs.forEach((input, index) => {
    if (input.sourceId !== feed.sources[index]) throw new Error('Inputs must follow the feed\'s source order');
    const result = dispose(feed, input, start, end);
    results.push(result);
    if (result.disposition === 'ACCEPTED') candidates.push(result);
  });
  const candidateCount = candidates.length;
  if (candidateCount < feed.minimumSources) return noQuorum(feed, results, candidateCount);
  candidates.sort(byValueThenId);
  const reference = lowerMedian(candidates);
  const permitted = permittedDeviation(feed, reference);
  const retained: SourceResult[] = [];
  for (const candidate of candidates) {
    const deviation = abs((candidate.value as bigint) - reference);
    if (deviation > permitted) {
      const index = results.findIndex((result) => result.sourceId === candidate.sourceId);
      results[index] = { ...candidate, disposition: 'OUTLIER' };
    } else {
      retained.push(candidate);
    }
  }
  if (retained.length < feed.minimumSources) return noQuorum(feed, results, candidateCount);
  return {
    status: ROUND_CLOSED, statusName: 'CLOSED', aggregate: lowerMedian(retained), scale: feed.scale,
    acceptedSources: retained.map((result) => result.sourceId).sort(), sources: results, candidateCount
  };
}

export function permittedDeviation(feed: FeedValue, reference: bigint): bigint {
  const relative = (abs(reference) * feed.maximumDeviationPpm) / PPM;
  return relative > feed.maximumDeviationAbsolute ? relative : feed.maximumDeviationAbsolute;
}

function lowerMedian(sorted: SourceResult[]): bigint {
  return sorted[Math.floor((sorted.length - 1) / 2)].value as bigint;
}

function byValueThenId(left: SourceResult, right: SourceResult): number {
  const l = left.value as bigint;
  const r = right.value as bigint;
  if (l !== r) return l < r ? -1 : 1;
  return left.sourceId < right.sourceId ? -1 : left.sourceId > right.sourceId ? 1 : 0;
}

function abs(value: bigint): bigint {
  return value < 0n ? -value : value;
}

function dispose(feed: FeedValue, input: SourceInput, start: bigint, end: bigint): SourceResult {
  const source = input.sourceId;
  if (input.revoked) return { sourceId: source, disposition: 'REVOKED', value: null, observedAt: null };
  if (!input.value) return { sourceId: source, disposition: 'ABSENT', value: null, observedAt: null };
  const { value, observedAt } = input.value;
  if (!input.writerActorId || input.writerActorId !== source) return { sourceId: source, disposition: 'FOREIGN_WRITER', value, observedAt };
  if (input.revision !== 1) return { sourceId: source, disposition: 'EQUIVOCATED', value, observedAt };
  if (observedAt < start || observedAt > end) return { sourceId: source, disposition: 'WRONG_ROUND', value, observedAt };
  if (value < feed.minimumValue || value > feed.maximumValue) return { sourceId: source, disposition: 'OUT_OF_RANGE', value, observedAt };
  return { sourceId: source, disposition: 'ACCEPTED', value, observedAt };
}

function noQuorum(feed: FeedValue, results: SourceResult[], candidateCount: number): AggregationResult {
  return {
    status: ROUND_NO_QUORUM, statusName: 'NO_QUORUM', aggregate: 0n, scale: feed.scale,
    acceptedSources: [], sources: results, candidateCount
  };
}

/** The aggregation input one observation answer represents for a configured source. */
export function inputOf(sourceId: string, answer: AnswerDocument | undefined): SourceInput {
  if (!answer || answer.presence === 'ABSENT' || !answer.entry) {
    return { sourceId, value: null, revoked: answer?.presence === 'REVOKED', writerActorId: null, revision: 0 };
  }
  let value: ObservationValue | null = null;
  try {
    value = decodeObservation(answer.entry.valueHex);
  } catch {
    value = null;
  }
  return { sourceId, value, revoked: false, writerActorId: answer.provenance.actorId ?? null, revision: answer.entry.revision };
}

// ------------------------------------------------------------------ the candidate datum

export function encodeDatum(fields: {
  chainIdHash: Uint8Array; feedIdHash: Uint8Array; round: bigint; aggregate: bigint; scale: number;
  roundEnd: bigint; closedAtHeight: number; stateRoot: Uint8Array; acceptedCount: number;
}): Uint8Array {
  return encodeCbor({
    tag: 121n,
    value: [fields.chainIdHash, fields.feedIdHash, fields.round, fields.aggregate, BigInt(fields.scale),
      fields.roundEnd, BigInt(fields.closedAtHeight), fields.stateRoot, BigInt(fields.acceptedCount), 1n]
  });
}

export async function datumOf(bundle: RoundDocument, feed: FeedValue, result: AggregationResult): Promise<{ hex: string; sha256: string }> {
  const datum = encodeDatum({
    chainIdHash: fromHex(await sha256Hex(utf8(bundle.chainId))),
    feedIdHash: fromHex(await sha256Hex(utf8(bundle.feedId))),
    round: BigInt(bundle.round), aggregate: result.aggregate, scale: result.scale,
    roundEnd: roundEnd(feed, BigInt(bundle.round)), closedAtHeight: bundle.observationHeight,
    stateRoot: fromHex(bundle.stateRoot), acceptedCount: result.acceptedSources.length
  });
  return { hex: toHex(datum), sha256: await sha256Hex(datum) };
}

// ------------------------------------------------------------------ bundle checks

export interface AnswerBinding {
  label: string;
  collection: string;
  key: string;
  presence: string;
  provenance: string;
  height: number;
  facts: number;
  bound: boolean;
  notes: string[];
}

export interface BundleCheck {
  binding: 'BOUND' | 'MISMATCH';
  answers: AnswerBinding[];
  notes: string[];
  flags: string[];
  certSignatures: number;
  feed: FeedValue | null;
  feedAnswer: AnswerDocument | null;
  observations: AnswerDocument[];
  recordAnswer: AnswerDocument | null;
  result: AggregationResult | null;
  record: RecordValue | null;
  status: string;
  agrees: boolean | null;
  sameHeight: boolean | null;
}

/**
 * The browser-side checks of a bundle (ADR-052 §2.5): every answer and fact names the bundle's
 * chain and genesis, the feed and the observations sit at the observation height under one root,
 * one observation per configured source, the record at or after that height with its approval
 * consumption; then the round is recomputed with the same integer rules and compared with the
 * record. MPF paths and the finality certificate are verified by `yano-feed verify` on the export.
 */
export function checkBundle(bundle: RoundDocument): BundleCheck {
  const notes: string[] = [];
  const flags: string[] = [];
  const answers: AnswerBinding[] = [];
  let certSignatures = Number.MAX_SAFE_INTEGER;
  let allBound = true;
  const empty = (status: string): BundleCheck => ({
    binding: 'MISMATCH', answers, notes, flags, certSignatures: 0, feed: null, feedAnswer: null, observations: [],
    recordAnswer: null, result: null, record: null, status, agrees: null, sameHeight: null
  });
  if (bundle.schemaVersion !== 1 || bundle.type !== 'feed-round-v1') {
    notes.push('The document is not a feed-round-v1 bundle');
    return empty('INVALID');
  }
  const feedAnswers = bundle.answers.filter((answer) => answer.collection === 'feeds');
  const recordAnswers = bundle.answers.filter((answer) => answer.collection === 'rounds');
  const observations = bundle.answers.filter((answer) => answer.collection === 'observations');
  if (feedAnswers.length !== 1 || recordAnswers.length !== 1) {
    notes.push('The bundle must carry exactly one feed answer and one record answer');
    return empty('INVALID');
  }
  const feedAnswer = feedAnswers[0];
  const recordAnswer = recordAnswers[0];
  const h0 = bundle.observationHeight;

  for (const answer of bundle.answers) {
    const key = answer.key ?? keyText(answer.keyHex);
    const answerNotes: string[] = [];
    let bound = answer.chainId === bundle.chainId && answer.profile === bundle.profile && answer.genesisId === bundle.genesisId;
    if (!bound) answerNotes.push('The answer names another chain, profile, or genesis');
    if (answer.collection !== 'rounds' && (answer.height !== h0 || answer.stateRoot !== bundle.stateRoot)) {
      bound = false;
      answerNotes.push(`The answer is not at the observation height ${h0} under the feed's root`);
    }
    if (answer.collection === 'rounds' && (answer.height < h0 || answer.height !== bundle.recordHeight)) {
      bound = false;
      answerNotes.push('The record is not at the bundle\'s record height at or after the observation height');
    }
    if (!Array.isArray(answer.facts) || !answer.facts.length || answer.facts[0].name !== 'entry') {
      bound = false;
      answerNotes.push('The first fact must be the entry proof');
    }
    for (const fact of answer.facts ?? []) {
      const proof = fact.proof;
      const factBound = proof && proof.chainId === bundle.chainId && proof.genesisId === bundle.genesisId
        && proof.committedHeight === answer.height && proof.stateRoot === answer.stateRoot
        && proof.key === fact.keyHex && (proof.block?.blockHash ?? proof.blockHash) === answer.blockHash
        && (fact.valueHex === undefined ? proof.presence !== 'PRESENT' : proof.valueHex === fact.valueHex);
      if (!factBound) {
        bound = false;
        answerNotes.push(`Fact ${fact.name} does not name the answer's chain, genesis, height, root, block, key, and value`);
      }
      certSignatures = Math.min(certSignatures, proof?.finalityCertificate?.signatures?.length ?? 0);
    }
    if ((answer.presence === 'ABSENT') !== !answer.entry) {
      bound = false;
      answerNotes.push('Presence and entry disagree');
    }
    if (answer.collection === 'rounds' && answer.entry && answer.provenance.messageId
        && !answer.facts.some((fact) => fact.name === 'approval-consumption')) {
      bound = false;
      flags.push('UNPROVEN_APPROVAL');
      answerNotes.push('The record carries no approval consumption proof');
    }
    allBound &&= bound;
    answers.push({
      label: `${answer.collection}/${key}`, collection: answer.collection, key, presence: answer.presence,
      provenance: answer.provenance.kind, height: answer.height, facts: answer.facts?.length ?? 0, bound, notes: answerNotes
    });
  }

  // Keys: the feed's, one observation per configured source in order, the round's.
  if (keyText(feedAnswer.keyHex) !== bundle.feedId) {
    allBound = false;
    notes.push(`The feed answer is not feeds/${bundle.feedId}`);
  }
  if (keyText(recordAnswer.keyHex) !== `${bundle.feedId}/${bundle.round}`) {
    allBound = false;
    notes.push(`The record answer is not rounds/${bundle.feedId}/${bundle.round}`);
  }
  let feed: FeedValue | null = null;
  if (feedAnswer.presence === 'ACTIVE' && feedAnswer.entry) {
    try {
      feed = decodeFeed(feedAnswer.entry.valueHex);
    } catch {
      notes.push('The feed value does not decode');
    }
  } else {
    notes.push(`The feed is ${feedAnswer.presence} at the observation height`);
  }
  let result: AggregationResult | null = null;
  if (feed) {
    if (observations.length !== feed.sources.length) {
      allBound = false;
      notes.push(`The bundle answers ${observations.length} observation(s) for ${feed.sources.length} configured source(s)`);
    } else {
      feed.sources.forEach((source, index) => {
        const wanted = `${bundle.feedId}/${bundle.round}/${source}`;
        if (keyText(observations[index].keyHex) !== wanted) {
          allBound = false;
          notes.push(`Observation ${index} is not observations/${wanted}`);
        }
      });
      try {
        result = aggregate(feed, BigInt(bundle.round), feed.sources.map((source, index) => inputOf(source, observations[index])));
      } catch (cause) {
        notes.push(cause instanceof Error ? cause.message : 'The round could not be recomputed');
      }
    }
  }

  // The record against the recomputation.
  let record: RecordValue | null = null;
  let status = 'OPEN';
  let agrees: boolean | null = null;
  let sameHeight: boolean | null = null;
  if (recordAnswer.presence === 'REVOKED') {
    status = 'REVOKED';
    flags.push('RECORD_REVOKED');
  } else if (recordAnswer.presence === 'ACTIVE' && recordAnswer.entry) {
    try {
      record = decodeRecord(recordAnswer.entry.valueHex);
      status = record.status === ROUND_CLOSED ? 'CLOSED' : 'NO_QUORUM';
      sameHeight = record.closedAtHeight === h0;
      if (recordAnswer.entry.revision !== 1) flags.push('REWRITTEN');
      if (feed && feed.status !== 0) flags.push('FEED_PAUSED');
      if (result) {
        agrees = record.status === result.status && record.aggregate === result.aggregate && record.scale === result.scale
          && record.acceptedSources.join(',') === result.acceptedSources.join(',');
        if (!sameHeight) flags.push('LATER_HEIGHT');
        else if (!agrees) flags.push('RECORD_DISAGREES');
      }
    } catch {
      status = 'MALFORMED';
      flags.push('MALFORMED');
    }
  } else if (recordAnswer.height !== h0) {
    allBound = false;
    notes.push('An open round must answer its record at the observation height');
  }
  return {
    binding: allBound && !flags.includes('RECORD_DISAGREES') && !flags.includes('REWRITTEN') ? 'BOUND' : 'MISMATCH',
    answers, notes, flags, certSignatures: certSignatures === Number.MAX_SAFE_INTEGER ? 0 : certSignatures,
    feed, feedAnswer, observations, recordAnswer, result, record, status, agrees, sameHeight
  };
}
