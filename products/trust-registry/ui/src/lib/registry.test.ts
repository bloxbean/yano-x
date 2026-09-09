import { describe, expect, it } from 'vitest';
import goldenAnswer from './fixtures/golden-answer.json';
import goldenList from './fixtures/golden-status-list.json';
import { fromHex, toHex, utf8 } from './cbor';
import { sha256Hex } from './hash';
import {
  applicationKey,
  bitAt,
  canonicalKey,
  componentKey,
  consumptionPhysicalKey,
  decodeConsumption,
  decodeEncodedList,
  decodeEntry,
  decodeReceipt,
  decodeValue,
  discoverRegistry,
  entryPhysicalKey,
  evaluateTrqp,
  genesisMarkerPhysicalKey,
  receiptPhysicalKey,
  setCount
} from './registry';

type Fact = { name: string; keyHex: string; valueHex?: string };
const facts = (goldenAnswer as { facts: Fact[] }).facts;
const fact = (name: string) => facts.find((item) => item.name === name)!;

describe('registry keys', () => {
  it('derives the physical entry key the JVM client proved', () => {
    const key = applicationKey('status', 'list-1', 5);
    expect(new TextDecoder().decode(key)).toBe('list-1/5');
    expect(toHex(entryPhysicalKey('status', key))).toBe(fact('entry').keyHex);
  });

  it('derives receipt, consumption, and genesis marker keys', () => {
    const receipt = decodeReceipt(fact('receipt').valueHex!);
    expect(toHex(receiptPhysicalKey(fromHex(receipt.messageIdHex)))).toBe(fact('receipt').keyHex);
    const consumption = decodeConsumption(fact('direct-consumption').valueHex!);
    expect(toHex(consumptionPhysicalKey(consumption.actorId, fromHex(consumption.authorizationIdHex))))
      .toBe(fact('direct-consumption').keyHex);
    expect(toHex(genesisMarkerPhysicalKey())).toBe(fact('genesis-marker').keyHex);
    expect(toHex(componentKey('domain-actors', utf8(`a/${consumption.actorId}/current`))))
      .toBe(fact('actor-current').keyHex);
    expect(toHex(componentKey('role-approvals', utf8(`d/${consumption.policyId}/r/${consumption.policyRevision}`))))
      .toBe(fact('direct-policy').keyHex);
    expect(toHex(componentKey('role-approvals', utf8(`d/${consumption.policyId}/current`))))
      .toBe(fact('direct-policy-current').keyHex);
    expect(toHex(componentKey('domain-actors', utf8(`o/${consumption.organizationId}/r/${consumption.organizationRevision}`))))
      .toBe(fact('organization').keyHex);
    expect(toHex(canonicalKey(1, 'subjects', utf8('x')))).toBe('01010008' + toHex(utf8('subjects')) + '00000001' + '78');
    expect(() => applicationKey('subjects', 'has/slash')).toThrow(/match/);
    expect(() => applicationKey('status', 'list-1', 1_048_576)).toThrow(/bound/);
  });
});

describe('registry records', () => {
  it('decodes the golden entry, value, receipt, and consumption', () => {
    const entry = decodeEntry(fact('entry').valueHex!);
    expect(entry.status).toBe('ACTIVE');
    expect(entry.revision).toBe(1);
    expect(decodeValue('status', entry.valueHex)).toEqual({ kind: 'status', value: { bit: 1, reasonCode: 3 } });
    const receipt = decodeReceipt(fact('receipt').valueHex!);
    expect(receipt.applied).toBe(true);
    expect(receipt.results.some((result) => result.collection === 'status' && result.revision === 1)).toBe(true);
    const consumption = decodeConsumption(fact('direct-consumption').valueHex!);
    expect(consumption.actorId).toBe('issuer-a');
    expect(consumption.role).toBe('issuer');
    expect(consumption.policyId).toBe('issuer-write');
    expect(consumption.messageIdHex).toBe(receipt.messageIdHex);
    expect(decodeValue('issuers', 'ff')).toEqual({ kind: 'opaque', valueHex: 'ff' });
  });

  it('recognizes the registry profile in a genesis and rejects others', () => {
    expect(discoverRegistry(null)).toEqual({ registry: false, collections: null, mapGenesisIdHex: null });
    expect(discoverRegistry('80').registry).toBe(false);
  });

  it('evaluates TRQP questions', () => {
    const issuer = { framework: 'f', authorizations: ['issue:credential'], validFromHeight: 10, validUntilHeight: 20 };
    expect(evaluateTrqp('ABSENT', null, 'f', 'issue:credential', 15).authorized).toBe(false);
    expect(evaluateTrqp('REVOKED', issuer, 'f', 'issue:credential', 15).reason).toMatch(/revoked/);
    expect(evaluateTrqp('ACTIVE', issuer, 'g', 'issue:credential', 15).authorized).toBe(false);
    expect(evaluateTrqp('ACTIVE', issuer, 'f', 'revoke:credential', 15).reason).toMatch(/not granted/);
    expect(evaluateTrqp('ACTIVE', issuer, 'f', 'issue:credential', 9).reason).toMatch(/not yet valid/);
    expect(evaluateTrqp('ACTIVE', issuer, 'f', 'issue:credential', 21).reason).toMatch(/expired/);
    expect(evaluateTrqp('ACTIVE', issuer, 'f', 'issue:credential', 15).authorized).toBe(true);
  });
});

describe('status lists', () => {
  it('decodes the served bitstring and re-derives the chain hash', async () => {
    const document = goldenList as { credentialSubject: { encodedList: string; statusSize: number }; 'x-yano': { bitLength: number; bitstringSha256: string; setCount: number } };
    const raw = await decodeEncodedList(document.credentialSubject.encodedList, document['x-yano'].bitLength);
    expect(raw.length).toBe(document['x-yano'].bitLength / 8);
    expect(await sha256Hex(raw)).toBe(document['x-yano'].bitstringSha256);
    expect(setCount(raw)).toBe(document['x-yano'].setCount);
    expect(bitAt(raw, 5)).toBe(true);
    expect(bitAt(raw, 8)).toBe(true);
    expect(bitAt(raw, 9)).toBe(false);
    await expect(decodeEncodedList('z' + document.credentialSubject.encodedList.slice(1), 131_072)).rejects.toThrow(/multibase/);
    await expect(decodeEncodedList(document.credentialSubject.encodedList, 131_072 + 8)).rejects.toThrow(/length/);
  });
});
