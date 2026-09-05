import { asArray, asBytes, asText, asUnsigned, decodeCbor, fromHex, toHex, utf8 } from './cbor';
import { sha256Hex } from './hash';
import type {
  AnswerDocument,
  CertificateValue,
  ClaimValue,
  DisclosureDocument,
  EventValue,
  PassportDocument,
  ProductValue,
  VersionValue
} from './types';

export const CLAIM_COMMITMENT_DOMAIN = 'yano-dpp-claim-commitment-v1';
export const STATUS_NAMES = ['DRAFT', 'ACTIVE', 'INACTIVE', 'REPLACED', 'RETIRED'];
export const KNOWN_EVENT_TYPES = ['MANUFACTURED', 'SHIPPED', 'RECEIVED', 'INSPECTED', 'REPAIRED', 'RECYCLED'];
const PRODUCT_ID = /^[A-Za-z0-9._:~-]{1,64}$/;
const GTIN = /^[0-9]{8}$|^[0-9]{12,14}$/;
const SERIAL = /^[A-Za-z0-9._-]{1,20}$/;

// ------------------------------------------------------------------ identifiers

/** The product id of a GS1 Digital Link path: the GTIN zero-padded to 14 digits. */
export function gs1ProductId(gtin: string, serial?: string): string {
  if (!GTIN.test(gtin)) throw new Error('A GTIN has 8, 12, 13, or 14 digits');
  const padded = gtin.padStart(14, '0');
  if (!serial) return `gtin:${padded}`;
  if (!SERIAL.test(serial)) throw new Error('The serial must be letters, digits, dot, underscore, or dash');
  return `gtin:${padded}:21:${serial}`;
}

/** A product id, a GS1 Digital Link URL, or a `/01/<gtin>[/21/<serial>]` path, as typed or scanned. */
export function parseProductInput(input: string): string {
  const text = input.trim();
  if (!text) throw new Error('Enter a product id or paste a Digital Link');
  const link = text.match(/\/01\/([0-9]{8,14})(?:\/21\/([A-Za-z0-9._-]{1,20}))?\/?(?:[?#].*)?$/);
  if (link) return gs1ProductId(link[1], link[2]);
  if (/^[0-9]{8}$|^[0-9]{12,14}$/.test(text)) return gs1ProductId(text);
  if (!PRODUCT_ID.test(text)) throw new Error('A product id is at most 64 characters of letters, digits, . _ : ~ -');
  return text;
}

/** The Digital Link path a label would encode, for GS1-identified products. */
export function digitalLinkPath(productId: string): string | null {
  const match = productId.match(/^gtin:([0-9]{14})(?::21:([A-Za-z0-9._-]{1,20}))?$/);
  if (!match) return null;
  return match[2] ? `/01/${match[1]}/21/${match[2]}` : `/01/${match[1]}`;
}

export function keyText(keyHex: string): string {
  return new TextDecoder().decode(fromHex(keyHex));
}

/** The product a key belongs to, or null for a certificate key (its product is in the value). */
export function productIdOfKey(collection: string, keyHex: string): string | null {
  const text = keyText(keyHex);
  switch (collection) {
    case 'products': return text;
    case 'product-versions': case 'events': {
      const parts = text.split('/');
      return parts.length === 2 ? parts[0] : null;
    }
    case 'claims': {
      const parts = text.split('/');
      return parts.length === 3 ? parts[0] : null;
    }
    default: return null;
  }
}

// ------------------------------------------------------------------ values

export function decodeProduct(valueHex: string): ProductValue {
  const items = asArray(decodeCbor(fromHex(valueHex)), 6);
  requireVersion(items[0]);
  return {
    manufacturerOrganizationId: asText(items[1]), status: asUnsigned(items[2]),
    currentVersion: asUnsigned(items[3]), successorProductId: asText(items[4]), passportProfileId: asText(items[5])
  };
}

export function decodeVersion(valueHex: string): VersionValue {
  const items = asArray(decodeCbor(fromHex(valueHex)), 5);
  requireVersion(items[0]);
  return {
    documentSha256: toHex(asBytes(items[1], 32)), mediaType: asText(items[2]),
    reference: asText(items[3]), byteLength: asUnsigned(items[4])
  };
}

export function decodeClaim(valueHex: string): ClaimValue {
  const items = asArray(decodeCbor(fromHex(valueHex)), 7);
  requireVersion(items[0]);
  const visibility = asUnsigned(items[1]);
  const value = asBytes(items[2]);
  return {
    visibility, valueHex: toHex(value),
    text: visibility === 0 ? new TextDecoder().decode(value) : null,
    issuerOrganizationId: asText(items[3]), validFromHeight: asUnsigned(items[4]),
    validUntilHeight: asUnsigned(items[5]), evidenceSha256: toHex(asBytes(items[6]))
  };
}

export function decodeEvent(valueHex: string): EventValue {
  const items = asArray(decodeCbor(fromHex(valueHex)), 7);
  requireVersion(items[0]);
  return {
    eventType: asText(items[1]), actorOrganizationId: asText(items[2]), observedAt: asUnsigned(items[3]),
    location: asText(items[4]), evidenceSha256: toHex(asBytes(items[5])), note: asText(items[6])
  };
}

export function decodeCertificate(valueHex: string): CertificateValue {
  const items = asArray(decodeCbor(fromHex(valueHex)), 7);
  requireVersion(items[0]);
  return {
    productId: asText(items[1]), certificateType: asText(items[2]), issuerOrganizationId: asText(items[3]),
    evidenceSha256: toHex(asBytes(items[4], 32)), validFromHeight: asUnsigned(items[5]),
    validUntilHeight: asUnsigned(items[6])
  };
}

function requireVersion(item: unknown) {
  if (asUnsigned(item as never) !== 1) throw new Error('Unsupported DPP value version');
}

// ------------------------------------------------------------------ bundle checks

export interface AnswerBinding {
  label: string;
  collection: string;
  key: string;
  presence: string;
  provenance: string;
  facts: number;
  bound: boolean;
  belongs: boolean;
  notes: string[];
}

export interface BundleCheck {
  binding: 'BOUND' | 'MISMATCH';
  answers: AnswerBinding[];
  notes: string[];
  certSignatures: number;
}

/**
 * The browser-side checks of a bundle (ADR-051 §2.5): every answer and every fact names the
 * bundle's chain, genesis, height, root, and block; every key belongs to the product. MPF paths
 * and the finality certificate are verified by `yano-dpp verify` on the export.
 */
export function checkBundle(bundle: PassportDocument): BundleCheck {
  const notes: string[] = [];
  const answers: AnswerBinding[] = [];
  let certSignatures = Number.MAX_SAFE_INTEGER;
  let allBound = true;
  if (bundle.schemaVersion !== 1 || bundle.type !== 'dpp-passport-v1') {
    return { binding: 'MISMATCH', answers, notes: ['The document is not a dpp-passport-v1 bundle'], certSignatures: 0 };
  }
  const products = bundle.answers.filter((answer) => answer.collection === 'products');
  if (products.length !== 1) {
    allBound = false;
    notes.push('The bundle must carry exactly one product answer');
  }
  for (const answer of bundle.answers) {
    const key = answer.key ?? keyText(answer.keyHex);
    const answerNotes: string[] = [];
    let bound = answer.chainId === bundle.chainId && answer.profile === bundle.profile
      && answer.genesisId === bundle.genesisId && answer.height === bundle.height
      && answer.stateRoot === bundle.stateRoot && answer.blockHash === bundle.blockHash;
    if (!bound) answerNotes.push('The answer names another chain, genesis, height, root, or block');
    if (!Array.isArray(answer.facts) || !answer.facts.length || answer.facts[0].name !== 'entry') {
      bound = false;
      answerNotes.push('The first fact must be the entry proof');
    }
    for (const fact of answer.facts ?? []) {
      const proof = fact.proof;
      const factBound = proof && proof.chainId === bundle.chainId && proof.genesisId === bundle.genesisId
        && proof.committedHeight === bundle.height && proof.stateRoot === bundle.stateRoot
        && proof.key === fact.keyHex && (proof.block?.blockHash ?? proof.blockHash) === bundle.blockHash
        && (fact.valueHex === undefined ? proof.presence !== 'PRESENT' : proof.valueHex === fact.valueHex);
      if (!factBound) {
        bound = false;
        answerNotes.push(`Fact ${fact.name} does not name the bundle's chain, genesis, height, root, block, key, and value`);
      }
      const signatures = proof?.finalityCertificate?.signatures?.length ?? 0;
      certSignatures = Math.min(certSignatures, signatures);
    }
    if ((answer.presence === 'ABSENT') !== !answer.entry) {
      bound = false;
      answerNotes.push('Presence and entry disagree');
    }
    const belongs = belongsToProduct(bundle, answer);
    if (!belongs) answerNotes.push(`The key does not belong to product ${bundle.productId}`);
    if (answer.collection === 'certificates' && answer.entry && answer.provenance.messageId
        && !answer.facts.some((fact) => fact.name === 'approval-consumption')) {
      bound = false;
      answerNotes.push('The certificate carries no approval consumption proof');
    }
    allBound &&= bound && belongs;
    answers.push({
      label: `${answer.collection}/${key}`, collection: answer.collection, key, presence: answer.presence,
      provenance: answer.provenance.kind, facts: answer.facts?.length ?? 0, bound, belongs, notes: answerNotes
    });
  }
  for (const entry of bundle.timeline ?? []) {
    if (entry.height > bundle.height) {
      allBound = false;
      notes.push('The timeline reaches beyond the passport height');
      break;
    }
  }
  return {
    binding: allBound ? 'BOUND' : 'MISMATCH', answers, notes,
    certSignatures: certSignatures === Number.MAX_SAFE_INTEGER ? 0 : certSignatures
  };
}

function belongsToProduct(bundle: PassportDocument, answer: AnswerDocument): boolean {
  const owner = productIdOfKey(answer.collection, answer.keyHex);
  if (owner !== null) return owner === bundle.productId;
  if (answer.collection !== 'certificates') return false;
  if (answer.entry && answer.presence === 'ACTIVE') {
    try {
      return decodeCertificate(answer.entry.valueHex).productId === bundle.productId;
    } catch {
      return false;
    }
  }
  return bundle.timeline.some((entry) => entry.collection === 'certificates' && entry.keyHex === answer.keyHex);
}

// ------------------------------------------------------------------ disclosures

export async function claimCommitmentHex(saltHex: string, text: string): Promise<string> {
  if (!/^[0-9a-f]{64}$/.test(saltHex)) throw new Error('The salt must be 32 bytes of lowercase hex');
  const domain = utf8(CLAIM_COMMITMENT_DOMAIN);
  const salt = fromHex(saltHex);
  const body = utf8(text);
  const preimage = new Uint8Array(domain.length + salt.length + body.length);
  preimage.set(domain);
  preimage.set(salt, domain.length);
  preimage.set(body, domain.length + salt.length);
  return sha256Hex(preimage);
}

export type DisclosureOutcome = 'MATCH' | 'MISMATCH' | 'NOT_COMMITTED' | 'ABSENT' | 'WRONG_PRODUCT';

export function parseDisclosure(json: string): DisclosureDocument {
  const value = JSON.parse(json) as DisclosureDocument;
  if (!value || value.schemaVersion !== 1 || value.type !== 'dpp-disclosure-v1') {
    throw new Error('The document is not a dpp-disclosure-v1 disclosure');
  }
  if (!/^[0-9a-f]{64}$/.test(value.saltHex) || typeof value.text !== 'string') {
    throw new Error('The disclosure needs a 32-byte salt and the claim text');
  }
  return value;
}

/** Recomputes the commitment from the disclosure and compares it with the claim in the bundle. */
export async function checkDisclosure(bundle: PassportDocument, disclosure: DisclosureDocument)
  : Promise<{ outcome: DisclosureOutcome; message: string }> {
  if (disclosure.productId !== bundle.productId || disclosure.chainId !== bundle.chainId) {
    return { outcome: 'WRONG_PRODUCT', message: `The disclosure names ${disclosure.productId} on ${disclosure.chainId}` };
  }
  const wanted = `${disclosure.productId}/${disclosure.claimType}/${disclosure.claimId}`;
  const answer = bundle.answers.find((candidate) => candidate.collection === 'claims'
    && keyText(candidate.keyHex) === wanted);
  if (!answer) return { outcome: 'ABSENT', message: `The passport carries no answer for ${wanted}` };
  if (answer.presence !== 'ACTIVE' || !answer.entry) {
    return { outcome: 'ABSENT', message: `The claim is ${answer.presence} at height ${answer.height}` };
  }
  const claim = decodeClaim(answer.entry.valueHex);
  if (claim.visibility === 0) return { outcome: 'NOT_COMMITTED', message: 'The claim is public; nothing to disclose' };
  const commitment = await claimCommitmentHex(disclosure.saltHex, disclosure.text);
  return commitment === claim.valueHex
    ? { outcome: 'MATCH', message: `The disclosed text and salt reproduce the committed value at height ${answer.height}` }
    : { outcome: 'MISMATCH', message: 'The disclosed text and salt do not reproduce the committed value' };
}
