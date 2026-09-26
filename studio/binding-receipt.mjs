/**
 * Bounded, version-aware decoder for canonical `BindingReceiptV1` bytes (ADR-031.2 contract C6).
 *
 * Studio renders receipt outcomes only from canonical bytes. The decoder accepts exactly the version-one receipt
 * shape: definite-length arrays, shortest-form integers and lengths, UTF-8 text, bytes, booleans and null. Maps,
 * tags, floating point, indefinite lengths, trailing data and every non-canonical form are rejected. The decoded
 * view carried by an imported report must equal the bytes field by field; any contradiction rejects the report.
 *
 * Decoding is not authentication. A receipt proves nothing unless it is separately bound to a trusted state root
 * and finality certificate, which Studio never claims. This module executes no transition and encodes nothing.
 */

export const RECEIPT_LIMITS = Object.freeze({maxBytes: 65_536, maxDepth: 72, maxItems: 65_536,
  maxSteps: 257, maxEvents: 257, maxConditions: 256, maxCode: 127});

const NAME = /^[a-zA-Z][a-zA-Z0-9_.-]{0,126}$/;
const INT64_MAX = 2n ** 63n - 1n;
const STEP_STATUSES = Object.freeze(['PLANNED', 'EFFECT_PLANNED', 'REJECTED']);

export class ReceiptError extends Error {
  constructor(code, message) {
    super(message);
    this.name = 'ReceiptError';
    this.code = code;
  }
}

const invalid = message => new ReceiptError('RECEIPT_INVALID', message);

/** Parses lower- or upper-case hexadecimal with an even number of digits. */
export function hexToBytes(hex, maximumBytes = RECEIPT_LIMITS.maxBytes) {
  if (typeof hex !== 'string' || hex.length % 2 || !/^[0-9a-fA-F]*$/.test(hex)) throw invalid('Receipt hex is malformed');
  if (hex.length / 2 > maximumBytes) throw invalid('Receipt exceeds its byte limit');
  const bytes = new Uint8Array(hex.length / 2);
  for (let i = 0; i < bytes.length; i++) bytes[i] = Number.parseInt(hex.slice(i * 2, i * 2 + 2), 16);
  return bytes;
}

export function bytesToHex(bytes) {
  let result = '';
  for (const value of bytes) result += value.toString(16).padStart(2, '0');
  return result;
}

/** Strict canonical CBOR subset decoder; integers are BigInt, byte strings are Uint8Array. */
function decodeCbor(bytes) {
  if (!(bytes instanceof Uint8Array) || bytes.length === 0 || bytes.length > RECEIPT_LIMITS.maxBytes) {
    throw invalid('Receipt is empty or exceeds its byte limit');
  }
  let offset = 0;
  let items = 0;
  // ignoreBOM keeps a leading U+FEFF in text (Java keeps it too); the default would silently strip it.
  const decoder = new TextDecoder('utf-8', {fatal: true, ignoreBOM: true});
  const argument = info => {
    if (info < 24) return BigInt(info);
    if (info > 27) throw invalid('Indefinite or reserved CBOR length');
    const size = 1 << (info - 24);
    if (offset + size > bytes.length) throw invalid('Truncated CBOR argument');
    let value = 0n;
    for (let i = 0; i < size; i++) value = (value << 8n) | BigInt(bytes[offset++]);
    const minimum = [24n, 0x100n, 0x10000n, 0x100000000n][info - 24];
    if (value < minimum) throw invalid('Non-canonical CBOR integer or length');
    return value;
  };
  const read = depth => {
    if (depth > RECEIPT_LIMITS.maxDepth || ++items > RECEIPT_LIMITS.maxItems) throw invalid('CBOR nesting or item limit exceeded');
    if (offset >= bytes.length) throw invalid('Truncated CBOR value');
    const header = bytes[offset++];
    const major = header >> 5;
    const info = header & 31;
    if (major === 7) {
      if (info === 20) return false;
      if (info === 21) return true;
      if (info === 22) return null;
      throw invalid('Unsupported CBOR simple or floating-point value');
    }
    if (major === 5) throw invalid('CBOR maps are not part of a binding receipt');
    if (major === 6) throw invalid('CBOR tags are not permitted');
    const value = argument(info);
    if (major === 0) return value;
    if (major === 1) return -1n - value;
    if (value > BigInt(bytes.length - offset)) throw invalid('CBOR length exceeds the remaining input');
    const length = Number(value);
    if (major === 2) { const result = bytes.slice(offset, offset + length); offset += length; return result; }
    if (major === 3) {
      let text;
      try { text = decoder.decode(bytes.subarray(offset, offset + length)); }
      catch { throw invalid('Receipt text is not valid UTF-8'); }
      offset += length;
      return text;
    }
    const result = [];
    for (let i = 0; i < length; i++) result.push(read(depth + 1));
    return result;
  };
  const root = read(0);
  if (offset !== bytes.length) throw invalid('Trailing data after the receipt');
  return root;
}

const array = (value, size, label) => {
  if (!Array.isArray(value) || (size !== undefined && value.length !== size)) throw invalid(`${label} has the wrong shape`);
  return value;
};
const text = (value, label) => { if (typeof value !== 'string') throw invalid(`${label} must be text`); return value; };
const name = (value, label) => { if (!NAME.test(text(value, label))) throw invalid(`${label} is not a valid identifier`); return value; };
const integer = (value, minimum, maximum, label) => {
  if (typeof value !== 'bigint' || value < BigInt(minimum) || value > BigInt(maximum)) throw invalid(`${label} is out of range`);
  return value;
};
const bytes32 = (value, label) => {
  if (!(value instanceof Uint8Array) || value.length !== 32) throw invalid(`${label} must contain 32 bytes`);
  return bytesToHex(value);
};

/**
 * Decodes canonical version-one receipt bytes into the display view.
 *
 * @param {Uint8Array} bytes exactly the bytes the engine stored under the receipt key
 * @returns {object} typed view: `height` is a BigInt, ordinals and indexes are numbers, identifiers are hex text
 */
export function decodeBindingReceipt(bytes) {
  const root = array(decodeCbor(bytes), 7, 'Receipt');
  if (root[0] !== 1n) throw new ReceiptError('RECEIPT_VERSION', 'Unsupported binding receipt version');
  const status = text(root[3], 'Receipt status');
  if (status !== 'ACCEPTED' && status !== 'REJECTED') throw invalid('Unknown receipt status');
  const failed = root[4] === null ? null : Number(integer(root[4], -2147483648, 2147483647, 'Failed step ordinal'));
  const code = text(root[5], 'Receipt code');
  if (code.length > RECEIPT_LIMITS.maxCode) throw invalid('Receipt code exceeds its limit');
  if (status === 'ACCEPTED' ? failed !== null || code !== '' : failed === null || failed < 0 || failed > 256 || code === '') {
    throw invalid('Receipt outcome fields are inconsistent');
  }
  const rawSteps = array(root[6], undefined, 'Receipt steps');
  if (rawSteps.length > RECEIPT_LIMITS.maxSteps) throw invalid('Receipt has too many steps');
  const steps = rawSteps.map((value, index) => {
    const step = array(value, 10, `Step ${index}`);
    const events = array(step[5], undefined, `Step ${index} events`);
    const conditions = array(step[6], undefined, `Step ${index} conditions`);
    const stepStatus = text(step[7], `Step ${index} status`);
    const stepCode = text(step[8], `Step ${index} code`);
    if (!STEP_STATUSES.includes(stepStatus) || stepCode.length > RECEIPT_LIMITS.maxCode) {
      throw invalid(`Step ${index} status or code is invalid`);
    }
    if (events.length > RECEIPT_LIMITS.maxEvents || conditions.length > RECEIPT_LIMITS.maxConditions) {
      throw invalid(`Step ${index} exceeds its trace limit`);
    }
    if (typeof step[9] !== 'boolean') throw invalid(`Step ${index} raw-body marker must be boolean`);
    return {
      ordinal: Number(integer(step[0], 0, 256, `Step ${index} ordinal`)),
      depth: Number(integer(step[1], 0, 33, `Step ${index} depth`)),
      bindingId: step[2] === null ? null : name(step[2], `Step ${index} binding`),
      targetComponentId: name(step[3], `Step ${index} target`),
      messageIdHex: bytes32(step[4], `Step ${index} message id`),
      eventsProduced: events.map((event, position) => name(event, `Step ${index} event ${position}`)),
      conditions: conditions.map((condition, position) => {
        const pair = array(condition, 2, `Step ${index} condition ${position}`);
        return {bindingId: name(pair[0], `Step ${index} condition ${position} binding`),
          failedClause: Number(integer(pair[1], -1, 7, `Step ${index} condition ${position} clause`))};
      }),
      status: stepStatus,
      code: stepCode,
      rawBody: step[9]
    };
  });
  return {
    version: 1,
    sourceMessageIdHex: bytes32(root[1], 'Source message id'),
    height: integer(root[2], 0n, INT64_MAX, 'Receipt height'),
    status,
    failedStepOrdinal: failed,
    code,
    steps
  };
}

/**
 * Compares a decoded receipt with the decoded view carried by a report (`ReceiptViewV1`).
 * The report view uses decimal text for the int64 height; everything else must match exactly.
 *
 * @returns {string[]} contradiction descriptions; empty when the representations agree
 */
export function receiptViewDifferences(decoded, view) {
  const differences = [];
  const expect = (path, actual, reported) => {
    if (actual !== reported) differences.push(path);
  };
  if (!view || typeof view !== 'object') return ['receipt view is missing'];
  const topKeys = ['version', 'sourceMessageIdHex', 'height', 'status', 'failedStepOrdinal', 'code', 'steps'];
  if (Object.keys(view).sort().join() !== [...topKeys].sort().join()) differences.push('receipt view fields');
  expect('version', 1n, view.version);
  expect('sourceMessageIdHex', decoded.sourceMessageIdHex, view.sourceMessageIdHex);
  expect('height', decoded.height.toString(), view.height);
  expect('status', decoded.status, view.status);
  expect('failedStepOrdinal', decoded.failedStepOrdinal === null ? null : BigInt(decoded.failedStepOrdinal),
    view.failedStepOrdinal);
  expect('code', decoded.code, view.code);
  if (!Array.isArray(view.steps) || view.steps.length !== decoded.steps.length) {
    differences.push('steps');
    return differences;
  }
  const stepKeys = ['ordinal', 'depth', 'bindingId', 'targetComponentId', 'messageIdHex', 'eventsProduced',
    'conditions', 'status', 'code', 'rawBody'];
  decoded.steps.forEach((step, index) => {
    const reported = view.steps[index];
    const path = `steps[${index}]`;
    if (!reported || typeof reported !== 'object'
        || Object.keys(reported).sort().join() !== [...stepKeys].sort().join()) {
      differences.push(`${path} fields`);
      return;
    }
    expect(`${path}.ordinal`, BigInt(step.ordinal), reported.ordinal);
    expect(`${path}.depth`, BigInt(step.depth), reported.depth);
    expect(`${path}.bindingId`, step.bindingId, reported.bindingId);
    expect(`${path}.targetComponentId`, step.targetComponentId, reported.targetComponentId);
    expect(`${path}.messageIdHex`, step.messageIdHex, reported.messageIdHex);
    expect(`${path}.status`, step.status, reported.status);
    expect(`${path}.code`, step.code, reported.code);
    expect(`${path}.rawBody`, step.rawBody, reported.rawBody);
    if (!Array.isArray(reported.eventsProduced) || reported.eventsProduced.length !== step.eventsProduced.length
        || step.eventsProduced.some((event, position) => reported.eventsProduced[position] !== event)) {
      differences.push(`${path}.eventsProduced`);
    }
    if (!Array.isArray(reported.conditions) || reported.conditions.length !== step.conditions.length
        || step.conditions.some((condition, position) => {
          const other = reported.conditions[position];
          return !other || Object.keys(other).sort().join() !== 'bindingId,failedClause'
            || other.bindingId !== condition.bindingId || other.failedClause !== BigInt(condition.failedClause);
        })) {
      differences.push(`${path}.conditions`);
    }
  });
  return differences;
}
