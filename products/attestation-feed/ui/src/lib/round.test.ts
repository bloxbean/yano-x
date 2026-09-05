import { describe, expect, it } from 'vitest';
import goldenRound from './fixtures/golden-round.json';
import goldenOpenRound from './fixtures/golden-open-round.json';
import goldenWrongRound from './fixtures/golden-wrong-round.json';
import { fromHex, toHex } from './cbor';
import { decimal, scaled } from './model';
import {
  aggregate, checkBundle, datumOf, decodeFeed, decodeObservation, decodeRecord, encodeDatum, parseFeedInput,
  parseRoundInput, permittedDeviation, roundEnd, roundOf, roundStart, type SourceInput
} from './round';
import type { FeedValue, RoundDocument } from './types';

const closed = goldenRound as unknown as RoundDocument;
const open = goldenOpenRound as unknown as RoundDocument;
const wrong = goldenWrongRound as unknown as RoundDocument;

const FEED: FeedValue = {
  description: 'Cold store 7 air temperature', unit: 'degC', scale: 2, epochStart: 1_790_000_000n, roundSeconds: 60n,
  sources: ['source-alpha', 'source-beta', 'source-gamma'], minimumSources: 2, maximumDeviationPpm: 20_000n,
  maximumDeviationAbsolute: 50n, minimumValue: -4_000n, maximumValue: 1_000n, status: 0
};
const ROUND = 7n;
const IN_ROUND = roundStart(FEED, ROUND) + 10n;

function good(sourceId: string, value: bigint, observedAt = IN_ROUND, writer = sourceId, revision = 1): SourceInput {
  return { sourceId, value: { value, observedAt, evidenceSha256: '', note: '' }, revoked: false, writerActorId: writer, revision };
}

describe('identifiers and numbers', () => {
  it('parses feed and round inputs and exact decimals', () => {
    expect(parseFeedInput(' coldstore-7 ')).toBe('coldstore-7');
    expect(() => parseFeedInput('Cold')).toThrow('feed id');
    expect(parseRoundInput('')).toBe('latest');
    expect(parseRoundInput('42')).toBe('42');
    expect(() => parseRoundInput('007')).toThrow('round');
    expect(decimal(-1825n, 2)).toBe('-18.25');
    expect(decimal(5n, 2)).toBe('0.05');
    expect(decimal(0n, 3)).toBe('0.000');
    expect(scaled('-18.25', 2)).toBe(-1825n);
    expect(scaled('-18.2', 2)).toBe(-1820n);
    expect(scaled('7', 0)).toBe(7n);
    expect(() => scaled('-18.255', 2)).toThrow('decimal place');
    expect(() => scaled('abc', 2)).toThrow('decimal');
  });

  it('maps times to rounds on the calendar', () => {
    expect(roundOf(FEED, 1_790_000_059n)).toBe(0n);
    expect(roundOf(FEED, 1_790_000_060n)).toBe(1n);
    expect(roundStart(FEED, 7n)).toBe(1_790_000_420n);
    expect(roundEnd(FEED, 7n)).toBe(1_790_000_479n);
    expect(() => roundOf(FEED, 1n)).toThrow('epoch');
  });
});

describe('feed-aggregation-v1 vectors (the same as the JVM tests)', () => {
  it('takes the lower median of accepted sources with one outlier excluded', () => {
    const result = aggregate(FEED, ROUND, [good('source-alpha', -1825n), good('source-beta', -1810n), good('source-gamma', -900n)]);
    expect(result.statusName).toBe('CLOSED');
    expect(result.sources[2].disposition).toBe('OUTLIER');
    expect(result.acceptedSources).toEqual(['source-alpha', 'source-beta']);
    expect(result.aggregate).toBe(-1825n);
    expect(result.candidateCount).toBe(3);
  });

  it('breaks ties by source id and enforces quorum', () => {
    expect(aggregate(FEED, ROUND, [good('source-alpha', -1800n), good('source-beta', -1820n), good('source-gamma', -1790n)]).aggregate).toBe(-1800n);
    const strict = { ...FEED, minimumSources: 3 };
    expect(aggregate(strict, ROUND, [good('source-alpha', -1825n), good('source-beta', -1810n), good('source-gamma', -900n)]).statusName).toBe('NO_QUORUM');
  });

  it('exposes every disposition', () => {
    const result = aggregate(FEED, ROUND, [
      good('source-alpha', -1825n, IN_ROUND, 'source-beta'),
      good('source-beta', -1825n, IN_ROUND, 'source-beta', 2),
      { sourceId: 'source-gamma', value: null, revoked: false, writerActorId: null, revision: 0 }
    ]);
    expect(result.sources.map((source) => source.disposition)).toEqual(['FOREIGN_WRITER', 'EQUIVOCATED', 'ABSENT']);
    expect(result.statusName).toBe('NO_QUORUM');
    const window = aggregate(FEED, ROUND, [
      good('source-alpha', -1825n, roundEnd(FEED, ROUND) + 1n),
      good('source-beta', -4001n),
      { sourceId: 'source-gamma', value: null, revoked: true, writerActorId: null, revision: 0 }
    ]);
    expect(window.sources.map((source) => source.disposition)).toEqual(['WRONG_ROUND', 'OUT_OF_RANGE', 'REVOKED']);
    const boundary = aggregate(FEED, ROUND, [
      good('source-alpha', -1825n, roundStart(FEED, ROUND)), good('source-beta', -1800n, roundEnd(FEED, ROUND)),
      { sourceId: 'source-gamma', value: null, revoked: false, writerActorId: null, revision: 0 }
    ]);
    expect(boundary.aggregate).toBe(-1825n);
  });

  it('uses the larger of the relative and absolute tolerance, without overflow', () => {
    expect(permittedDeviation(FEED, -1810n)).toBe(50n);
    expect(permittedDeviation(FEED, -100_000n)).toBe(2_000n);
    const max = (1n << 63n) - 1n;
    const wide = { ...FEED, minimumSources: 1, maximumDeviationPpm: 1_000_000n, maximumDeviationAbsolute: 0n, minimumValue: -max, maximumValue: max };
    const extreme = aggregate(wide, ROUND, [good('source-alpha', max), good('source-beta', -max), good('source-gamma', 0n)]);
    expect(extreme.aggregate).toBe(0n);
    expect(extreme.acceptedSources).toEqual(['source-gamma']);
    expect(() => aggregate(FEED, ROUND, [good('source-beta', 1n), good('source-alpha', 1n), good('source-gamma', 1n)])).toThrow('order');
  });
});

describe('the candidate datum', () => {
  it('encodes the pinned vector of the JVM test', async () => {
    const digest = fromHex('ab'.repeat(32));
    const datum = encodeDatum({
      chainIdHash: fromHex(await sha('attestation-feed-chain')), feedIdHash: fromHex(await sha('coldstore-7')),
      round: 7n, aggregate: -1825n, scale: 2, roundEnd: 1_790_000_479n, closedAtHeight: 12, stateRoot: digest, acceptedCount: 2
    });
    expect(toHex(datum)).toBe('d8798a5820' + await sha('attestation-feed-chain') + '5820' + await sha('coldstore-7')
      + '07' + '390720' + '02' + '1a6ab13d5f' + '0c' + '5820' + 'ab'.repeat(32) + '02' + '01');
  });
});

describe('the golden bundles', () => {
  it('binds the closed round, recomputes it, and agrees with the record', async () => {
    const check = checkBundle(closed);
    expect(check.notes).toEqual([]);
    expect(check.flags).toEqual([]);
    expect(check.binding).toBe('BOUND');
    expect(check.status).toBe('CLOSED');
    expect(check.answers).toHaveLength(5);
    expect(check.answers.every((answer) => answer.bound)).toBe(true);
    expect(check.certSignatures).toBeGreaterThanOrEqual(2);
    expect(check.result?.aggregate).toBe(-1825n);
    expect(check.result?.acceptedSources).toEqual(['source-alpha', 'source-beta']);
    expect(check.result?.sources[2].disposition).toBe('OUTLIER');
    expect(check.agrees).toBe(true);
    expect(check.sameHeight).toBe(true);
    expect(check.record?.datumSha256).toHaveLength(64);
    const datum = await datumOf(closed, check.feed!, check.result!);
    expect(datum.sha256).toBe(check.record?.datumSha256);
    expect(datum.hex.startsWith('d8798a')).toBe(true);
  });

  it('decodes the starter values', () => {
    const feed = closed.answers.find((answer) => answer.collection === 'feeds')!;
    const decoded = decodeFeed(feed.entry!.valueHex);
    expect(decoded.unit).toBe('degC');
    expect(decoded.sources).toEqual(['source-alpha', 'source-beta', 'source-gamma']);
    expect(decoded.minimumValue).toBe(-4000n);
    const observation = closed.answers.find((answer) => answer.collection === 'observations')!;
    expect(decodeObservation(observation.entry!.valueHex).value).toBe(-1825n);
    const record = closed.answers.find((answer) => answer.collection === 'rounds')!;
    expect(decodeRecord(record.entry!.valueHex).acceptedSources).toEqual(['source-alpha', 'source-beta']);
    expect(() => decodeRecord(observation.entry!.valueHex)).toThrow();
  });

  it('shows the dispositions of an open round and catches a wrong record', () => {
    const openCheck = checkBundle(open);
    expect(openCheck.binding).toBe('BOUND');
    expect(openCheck.status).toBe('OPEN');
    expect(openCheck.result?.statusName).toBe('NO_QUORUM');
    expect(openCheck.result?.sources.map((source) => source.disposition)).toEqual(['EQUIVOCATED', 'FOREIGN_WRITER', 'OUT_OF_RANGE']);
    const wrongCheck = checkBundle(wrong);
    expect(wrongCheck.binding).toBe('MISMATCH');
    expect(wrongCheck.flags).toContain('RECORD_DISAGREES');
    expect(wrongCheck.agrees).toBe(false);
    expect(wrongCheck.record?.aggregate).toBe(-1799n);
    expect(wrongCheck.result?.aggregate).toBe(-1800n);
  });

  it('flags a relabelled or stripped bundle', () => {
    const relabelled = { ...closed, round: 8 };
    const check = checkBundle(relabelled);
    expect(check.binding).toBe('MISMATCH');
    expect(check.notes.some((note) => note.includes('rounds/coldstore-7/8'))).toBe(true);
    const stripped = {
      ...closed,
      answers: closed.answers.map((answer) => answer.collection === 'rounds'
        ? { ...answer, facts: answer.facts.filter((fact) => fact.name !== 'approval-consumption') } : answer)
    };
    const strippedCheck = checkBundle(stripped);
    expect(strippedCheck.binding).toBe('MISMATCH');
    expect(strippedCheck.flags).toContain('UNPROVEN_APPROVAL');
    expect(checkBundle({ ...closed, type: 'dpp-passport-v1' } as unknown as RoundDocument).binding).toBe('MISMATCH');
  });
});

async function sha(text: string): Promise<string> {
  const { sha256Hex } = await import('./hash');
  return sha256Hex(new TextEncoder().encode(text));
}
