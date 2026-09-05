import { describe, expect, it } from 'vitest';
import goldenPassport from './fixtures/golden-passport.json';
import goldenDisclosure from './fixtures/golden-disclosure.json';
import {
  checkBundle,
  checkDisclosure,
  claimCommitmentHex,
  decodeCertificate,
  decodeClaim,
  decodeEvent,
  decodeProduct,
  decodeVersion,
  digitalLinkPath,
  gs1ProductId,
  parseDisclosure,
  parseProductInput,
  productIdOfKey
} from './passport';
import type { DisclosureDocument, PassportDocument } from './types';

const bundle = goldenPassport as unknown as PassportDocument;
const disclosure = goldenDisclosure as unknown as DisclosureDocument;

describe('product identifiers', () => {
  it('maps GS1 Digital Links and GTINs to padded product ids', () => {
    expect(gs1ProductId('9506000134352')).toBe('gtin:09506000134352');
    expect(parseProductInput('https://passports.example.com/01/09506000134352/21/SN-42')).toBe('gtin:09506000134352:21:SN-42');
    expect(parseProductInput('/01/9506000134352')).toBe('gtin:09506000134352');
    expect(parseProductInput('09506000134352')).toBe('gtin:09506000134352');
    expect(parseProductInput(' gtin:09506000134352 ')).toBe('gtin:09506000134352');
    expect(() => parseProductInput('a/b')).toThrow('product id');
    expect(() => parseProductInput('')).toThrow('Enter');
    expect(digitalLinkPath('gtin:09506000134352')).toBe('/01/09506000134352');
    expect(digitalLinkPath('gtin:09506000134352:21:SN-42')).toBe('/01/09506000134352/21/SN-42');
    expect(digitalLinkPath('sku:abc')).toBeNull();
  });

  it('attributes keys to products', () => {
    const hex = (text: string) => Array.from(new TextEncoder().encode(text)).map((b) => b.toString(16).padStart(2, '0')).join('');
    expect(productIdOfKey('products', hex('gtin:1'))).toBe('gtin:1');
    expect(productIdOfKey('claims', hex('gtin:1/type/id'))).toBe('gtin:1');
    expect(productIdOfKey('events', hex('gtin:1/e-1'))).toBe('gtin:1');
    expect(productIdOfKey('certificates', hex('cert-1'))).toBeNull();
    expect(productIdOfKey('claims', hex('junk'))).toBeNull();
  });
});

describe('the golden passport bundle', () => {
  it('binds every answer and fact to one chain, genesis, height, root, and block', () => {
    const check = checkBundle(bundle);
    expect(check.notes).toEqual([]);
    expect(check.binding).toBe('BOUND');
    expect(check.answers).toHaveLength(bundle.answers.length);
    expect(check.answers.every((answer) => answer.bound && answer.belongs)).toBe(true);
    expect(check.certSignatures).toBeGreaterThanOrEqual(2);
    const certificate = check.answers.find((answer) => answer.collection === 'certificates');
    expect(certificate?.presence).toBe('REVOKED');
  });

  it('decodes the starter values', () => {
    const product = bundle.answers.find((answer) => answer.collection === 'products')!;
    const decoded = decodeProduct(product.entry!.valueHex);
    expect(decoded.manufacturerOrganizationId).toBe('acme-manufacturing');
    expect(decoded.currentVersion).toBe(2);
    expect(decoded.status).toBe(1);
    const version = bundle.answers.find((answer) => answer.collection === 'product-versions')!;
    expect(decodeVersion(version.entry!.valueHex).mediaType).toBe('application/json');
    const claims = bundle.answers.filter((answer) => answer.collection === 'claims');
    expect(decodeClaim(claims[0].entry!.valueHex).text).toBe('42 %');
    expect(decodeClaim(claims[1].entry!.valueHex).visibility).toBe(1);
    const event = bundle.answers.find((answer) => answer.collection === 'events')!;
    expect(decodeEvent(event.entry!.valueHex).eventType).toBe('MANUFACTURED');
    expect(() => decodeCertificate(event.entry!.valueHex)).toThrow();
  });

  it('flags a relabelled or tampered bundle', () => {
    const relabelled = { ...bundle, productId: 'gtin:09506000134369' };
    const check = checkBundle(relabelled);
    expect(check.binding).toBe('MISMATCH');
    expect(check.answers.filter((answer) => !answer.belongs).length).toBeGreaterThan(0);
    const tampered = { ...bundle, stateRoot: '00'.repeat(32) };
    expect(checkBundle(tampered).binding).toBe('MISMATCH');
    const stripped = {
      ...bundle,
      answers: bundle.answers.map((answer) => answer.collection === 'certificates'
        ? { ...answer, facts: answer.facts.filter((fact) => fact.name !== 'approval-consumption') } : answer)
    };
    expect(checkBundle(stripped).answers.find((answer) => answer.collection === 'certificates')?.bound).toBe(false);
  });

  it('checks a disclosure against the committed claim', async () => {
    expect(await claimCommitmentHex(disclosure.saltHex, disclosure.text)).toBe(disclosure.commitment);
    const parsed = parseDisclosure(JSON.stringify(disclosure));
    expect((await checkDisclosure(bundle, parsed)).outcome).toBe('MATCH');
    expect((await checkDisclosure(bundle, { ...parsed, saltHex: '00'.repeat(32) })).outcome).toBe('MISMATCH');
    expect((await checkDisclosure(bundle, { ...parsed, claimType: 'recycled-content', claimId: 'rc-1' })).outcome).toBe('NOT_COMMITTED');
    expect((await checkDisclosure(bundle, { ...parsed, claimId: 'cf-9' })).outcome).toBe('ABSENT');
    expect((await checkDisclosure(bundle, { ...parsed, productId: 'gtin:1' })).outcome).toBe('WRONG_PRODUCT');
    expect(() => parseDisclosure('{"type":"other"}')).toThrow('dpp-disclosure-v1');
  });
});
