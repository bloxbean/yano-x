/**
 * Import and matching for `yano-x-binding-report-v1` (ADR-031.2 contract C2).
 *
 * Reports are unauthenticated local files; anyone can edit one. Studio validates their structure, decodes every
 * receipt from its canonical bytes and rejects any contradiction with the carried decoded view. Matching compares
 * exact identities and input digests with the loaded catalog and the bytes Studio would export now. A match only
 * shows that the same inputs were used; it never turns a report into an attestation or deployment approval.
 */
import {decodeUtf8Strict,expectArray,expectBoolean,expectDecimalInt64,expectObject,expectSmallInteger,expectString,
  isObject,JsonInputError,parseJson} from './lossless-json.mjs';
import {catalogIdentity,identity,validateReceiptCodes} from './binding-catalog.mjs';
import {decodeBindingReceipt,hexToBytes,receiptViewDifferences} from './binding-receipt.mjs';
import {DIAGNOSTIC_CODES,DIAGNOSTIC_PARTS} from './binding-diagnostic-codes.mjs';
import {sha256Hex} from './sha256.mjs';

export const REPORT_SCHEMA = 'yano-x-binding-report-v1';
export const REPORT_LIMITS = Object.freeze({maxBytes: 16 * 1024 * 1024, maxMessages: 4096, maxDiagnostics: 256,
  maxEffects: 4096, maxReceiptCodes: 256});

const SHA256 = /^[0-9a-f]{64}$/;
const HEX = /^(?:[0-9a-f]{2})*$/;
const ROLES = ['document', 'context', 'fixture', 'priorResult'];
const DISPOSITIONS = ['executed', 'replay-existing-receipt', 'duplicate-in-fixture'];
const DOCUMENT_FORMATS = ['yaml', 'ir-hex'];
// Diagnostics may name invalid authored keys; Java allows long names, so bound generously and display truncated.
const NAME_BOUND = 65_536;

function inputs(value) {
  const roles = new Set();
  return Object.freeze(expectArray(value, '$.inputs', 8).map((input, index) => {
    const at = `$.inputs[${index}]`;
    expectObject(input, at, ['role', 'state', 'bytes', 'sha256']);
    const role = expectString(input.role, `${at}.role`, 32);
    const state = expectString(input.state, `${at}.state`, 16);
    if (!ROLES.includes(role) || !['read', 'not-read', 'oversized'].includes(state)) {
      throw new JsonInputError('CONTRACT_FORMAT', `${at} role or state is unknown`, {path: at});
    }
    if (roles.has(role)) throw new JsonInputError('REPORT_CONTRADICTION', `${at} repeats the ${role} input`, {path: at});
    roles.add(role);
    const read = state === 'read';
    if (read !== (input.sha256 !== null) || read !== (input.bytes !== null)) {
      throw new JsonInputError('CONTRACT_FORMAT', `${at} digest does not match its state`, {path: at});
    }
    return Object.freeze({role, state, bytes: read ? expectDecimalInt64(input.bytes, `${at}.bytes`) : null,
      sha256: read ? expectString(input.sha256, `${at}.sha256`, 64, SHA256) : null});
  }));
}

function diagnostics(value) {
  return Object.freeze(expectArray(value, '$.diagnostics', REPORT_LIMITS.maxDiagnostics).map((item, index) => {
    const at = `$.diagnostics[${index}]`;
    expectObject(item, at, ['code', 'severity', 'message', 'detailMayContainInput', 'location'], ['detail']);
    const location = expectObject(item.location, `${at}.location`, [], ['input', 'pathSegments', 'path', 'line',
      'column', 'componentIndex', 'setting', 'bindingIndex', 'bindingId', 'clauseIndex', 'part', 'targetField',
      'argumentPath', 'expressionLine', 'expressionColumn']);
    const small = (field, max) => location[field] === undefined ? null
      : expectSmallInteger(location[field], `${at}.location.${field}`, 0, max);
    const segments = location.pathSegments === undefined ? null
      : expectArray(location.pathSegments, `${at}.location.pathSegments`, 64).map((segment, n) =>
        typeof segment === 'bigint' ? expectSmallInteger(segment, `${at}.location.pathSegments[${n}]`, 0, 65_536)
          : expectString(segment, `${at}.location.pathSegments[${n}]`, NAME_BOUND));
    const code = expectString(item.code, `${at}.code`, 64, /^[A-Z][A-Z0-9_]{0,63}$/);
    if (expectString(item.severity, `${at}.severity`, 16) !== 'error') {
      throw new JsonInputError('CONTRACT_FORMAT', `${at}.severity is unknown`, {path: at});
    }
    const part = location.part === undefined ? null : expectString(location.part, `${at}.location.part`, 32);
    if (part !== null && !DIAGNOSTIC_PARTS.includes(part)) {
      throw new JsonInputError('CONTRACT_FORMAT', `${at}.location.part is unknown`, {path: at});
    }
    // Studio shows its own controlled text for a known code; the file's message is kept only as reported text.
    const known = Object.hasOwn(DIAGNOSTIC_CODES, code);
    return Object.freeze({
      code, known, severity: 'error',
      controlledMessage: known ? DIAGNOSTIC_CODES[code] : null,
      reportedMessage: expectString(item.message, `${at}.message`, 512),
      detail: item.detail === undefined ? null : expectString(item.detail, `${at}.detail`, 512),
      detailMayContainInput: expectBoolean(item.detailMayContainInput, `${at}.detailMayContainInput`),
      location: Object.freeze({
        input: location.input === undefined ? null : expectString(location.input, `${at}.location.input`, 32),
        pathSegments: segments === null ? null : Object.freeze(segments),
        path: location.path === undefined ? null : expectString(location.path, `${at}.location.path`, 64 * NAME_BOUND),
        line: small('line', 1_000_000), column: small('column', 1_000_000),
        componentIndex: small('componentIndex', 16), bindingIndex: small('bindingIndex', 256),
        clauseIndex: small('clauseIndex', 8),
        bindingId: location.bindingId === undefined ? null : expectString(location.bindingId, `${at}.location.bindingId`, NAME_BOUND),
        setting: location.setting === undefined ? null : expectString(location.setting, `${at}.location.setting`, NAME_BOUND),
        part,
        targetField: location.targetField === undefined ? null
          : expectString(location.targetField, `${at}.location.targetField`, NAME_BOUND),
        argumentPath: location.argumentPath === undefined ? null : Object.freeze(expectArray(location.argumentPath,
          `${at}.location.argumentPath`, 8).map((n, i) => expectSmallInteger(n, `${at}.location.argumentPath[${i}]`, 0, 7))),
        expressionLine: small('expressionLine', 100_000), expressionColumn: small('expressionColumn', 100_000)
      })
    });
  }));
}

function rehearsal(value) {
  const at = '$.result.rehearsal';
  expectObject(value, at, ['continuationOnly', 'fromPriorResult', 'priorPostStateSha256', 'assumptions',
    'executionIdentity', 'assurance', 'postStateSha256', 'stateChangeCount', 'messages', 'effects']);
  const assumptions = expectObject(value.assumptions, `${at}.assumptions`, ['height', 'timestamp', 'stateRootHex',
    'pendingEffects']);
  const seen = new Map();
  const height = expectDecimalInt64(assumptions.height, `${at}.assumptions.height`);
  const messages = expectArray(value.messages, `${at}.messages`, REPORT_LIMITS.maxMessages).map((message, index) => {
    const m = `${at}.messages[${index}]`;
    expectObject(message, m, ['messageIndex', 'messageIdHex', 'topic', 'disposition', 'receiptHex', 'receipt']);
    const disposition = expectString(message.disposition, `${m}.disposition`, 32);
    if (!DISPOSITIONS.includes(disposition)) throw new JsonInputError('CONTRACT_FORMAT', `${m}.disposition is unknown`, {path: m});
    const decoded = decodeBindingReceipt(hexToBytes(expectString(message.receiptHex, `${m}.receiptHex`, 131_072, HEX)));
    const differences = receiptViewDifferences(decoded, message.receipt);
    if (differences.length) {
      throw new JsonInputError('REPORT_CONTRADICTION',
        `${m}: receipt bytes and decoded view disagree (${differences.slice(0, 4).join(', ')})`, {path: m});
    }
    const messageIdHex = expectString(message.messageIdHex, `${m}.messageIdHex`, 64, SHA256);
    if (decoded.sourceMessageIdHex !== messageIdHex) {
      throw new JsonInputError('REPORT_CONTRADICTION', `${m}: receipt is for a different source message`, {path: m});
    }
    if (expectSmallInteger(message.messageIndex, `${m}.messageIndex`, 0, 4095) !== index) {
      throw new JsonInputError('REPORT_CONTRADICTION', `${m}.messageIndex is not its position`, {path: m});
    }
    // A duplicate repeats an earlier fixture message (and its receipt); anything else is the first occurrence of
    // its id. An executed message's receipt is this block's; a replayed receipt comes from an earlier block.
    if ((disposition === 'duplicate-in-fixture') !== seen.has(messageIdHex)) {
      throw new JsonInputError('REPORT_CONTRADICTION', `${m}.disposition contradicts earlier message ids`, {path: m});
    }
    if (disposition === 'duplicate-in-fixture' && seen.get(messageIdHex) !== message.receiptHex) {
      throw new JsonInputError('REPORT_CONTRADICTION', `${m}: a duplicate must show its first occurrence's receipt`, {path: m});
    }
    if (disposition === 'executed' && decoded.height !== height) {
      throw new JsonInputError('REPORT_CONTRADICTION', `${m}: an executed receipt must be at the block height`, {path: m});
    }
    if (disposition === 'replay-existing-receipt' && decoded.height >= height) {
      throw new JsonInputError('REPORT_CONTRADICTION', `${m}: a replayed receipt must come from an earlier height`, {path: m});
    }
    if (!seen.has(messageIdHex)) seen.set(messageIdHex, message.receiptHex);
    return Object.freeze({messageIndex: index, messageIdHex, topic: expectString(message.topic, `${m}.topic`, 127),
      disposition, receipt: decoded});
  });
  const effects = expectArray(value.effects, `${at}.effects`, REPORT_LIMITS.maxEffects).map((effect, index) => {
    const e = `${at}.effects[${index}]`;
    expectObject(effect, e, ['effectId', 'type', 'payloadHex', 'gate', 'result', 'scope', 'expiryBlocks']);
    return Object.freeze({effectId: expectString(effect.effectId, `${e}.effectId`, 512),
      type: expectString(effect.type, `${e}.type`, 127), payloadHex: expectString(effect.payloadHex, `${e}.payloadHex`,
        16 * 1024 * 1024, HEX), gate: expectString(effect.gate, `${e}.gate`, 32),
      result: expectString(effect.result, `${e}.result`, 32), scope: expectString(effect.scope, `${e}.scope`, 512),
      expiryBlocks: expectDecimalInt64(effect.expiryBlocks, `${e}.expiryBlocks`)});
  });
  return Object.freeze({
    continuationOnly: expectBoolean(value.continuationOnly, `${at}.continuationOnly`),
    fromPriorResult: expectBoolean(value.fromPriorResult, `${at}.fromPriorResult`),
    priorPostStateSha256: value.priorPostStateSha256 === null ? null
      : expectString(value.priorPostStateSha256, `${at}.priorPostStateSha256`, 64, SHA256),
    postStateSha256: expectString(value.postStateSha256, `${at}.postStateSha256`, 64, SHA256),
    assumptions: Object.freeze({height: expectDecimalInt64(assumptions.height, `${at}.assumptions.height`),
      timestamp: expectDecimalInt64(assumptions.timestamp, `${at}.assumptions.timestamp`),
      stateRootHex: expectString(assumptions.stateRootHex, `${at}.assumptions.stateRootHex`, 64, SHA256),
      pendingEffects: expectDecimalInt64(assumptions.pendingEffects, `${at}.assumptions.pendingEffects`)}),
    executionIdentity: expectString(value.executionIdentity, `${at}.executionIdentity`, 64, SHA256),
    assurance: expectString(value.assurance, `${at}.assurance`, 512),
    stateChangeCount: expectSmallInteger(value.stateChangeCount, `${at}.stateChangeCount`, 0, 1_000_000),
    messages: Object.freeze(messages), effects: Object.freeze(effects)
  });
}

/**
 * Validates an imported report file, including receipt byte/view consistency.
 *
 * @param {Uint8Array|string} input exact report bytes or text
 * @returns {object} frozen report with BigInt int64 values and decoded receipts
 * @throws {JsonInputError|ReceiptError} for malformed, oversized, contradictory or unknown-schema reports
 */
export function importReport(input) {
  const size = typeof input === 'string' ? input.length : input.byteLength;
  if (size > REPORT_LIMITS.maxBytes) throw new JsonInputError('REPORT_TOO_LARGE', 'Report exceeds 16 MiB');
  const text = typeof input === 'string' ? input : decodeUtf8Strict(input);
  const root = parseJson(text, {maxCharacters: REPORT_LIMITS.maxBytes, maxDepth: 48, maxStringCharacters: 16_777_216});
  if (!isObject(root) || root.schema !== REPORT_SCHEMA) throw new JsonInputError('REPORT_SCHEMA', 'Not a yano-x-binding-report-v1 file');
  expectObject(root, '$', ['schema', 'operation', 'operationOutcome', 'assurance', 'producer', 'host',
    'authoringEnvironment', 'catalog', 'inputs', 'documentFormat', 'result', 'diagnostics', 'receiptCodes']);
  const operation = expectString(root.operation, '$.operation', 16);
  const outcome = expectString(root.operationOutcome, '$.operationOutcome', 16);
  if (!['compile', 'validate', 'dry-run'].includes(operation) || !['completed', 'failed'].includes(outcome)) {
    throw new JsonInputError('CONTRACT_FORMAT', 'Report operation or outcome is unknown');
  }
  let result = null;
  if (root.result !== null) {
    if (outcome !== 'completed') throw new JsonInputError('CONTRACT_FORMAT', 'A failed operation cannot carry a result');
    const value = expectObject(root.result, '$.result', ['irHex', 'irSha256', 'profile', 'counts'], ['rehearsal']);
    result = Object.freeze({
      irHex: expectString(value.irHex, '$.result.irHex', 131_072, HEX),
      irSha256: expectString(value.irSha256, '$.result.irSha256', 64, SHA256),
      profile: value.profile === null ? null : Object.freeze((() => {
        const profile = expectObject(value.profile, '$.result.profile', ['digestHex', 'schemaVersion', 'executionVersion']);
        return {digestHex: expectString(profile.digestHex, '$.result.profile.digestHex', 64, SHA256),
          schemaVersion: expectSmallInteger(profile.schemaVersion, '$.result.profile.schemaVersion', 1, 1000),
          executionVersion: expectString(profile.executionVersion, '$.result.profile.executionVersion', 64)};
      })()),
      counts: Object.freeze((() => {
        const counts = expectObject(value.counts, '$.result.counts', ['components', 'bindings']);
        return {components: expectSmallInteger(counts.components, '$.result.counts.components', 1, 16),
          bindings: expectSmallInteger(counts.bindings, '$.result.counts.bindings', 0, 256)};
      })()),
      rehearsal: value.rehearsal === undefined ? null : rehearsal(value.rehearsal)
    });
    if ((operation === 'dry-run') !== (result.rehearsal !== null)) {
      throw new JsonInputError('CONTRACT_FORMAT', 'Only a completed dry-run report carries a rehearsal');
    }
    if (sha256Hex(hexToBytes(result.irHex)) !== result.irSha256) {
      throw new JsonInputError('REPORT_CONTRADICTION', '$.result.irSha256 is not the digest of $.result.irHex');
    }
  } else if (outcome === 'completed') {
    throw new JsonInputError('CONTRACT_FORMAT', 'A completed operation must carry a result');
  }
  const receiptCodes = validateReceiptCodes(root.receiptCodes, '$.receiptCodes');
  return Object.freeze({
    operation, operationOutcome: outcome,
    assurance: expectString(root.assurance, '$.assurance', 512),
    identity: identity(root),
    catalog: root.catalog === null ? null : catalogIdentity(root.catalog, '$.catalog'),
    inputs: inputs(root.inputs),
    documentFormat: (() => {
      const format = expectString(root.documentFormat, '$.documentFormat', 16);
      if (!DOCUMENT_FORMATS.includes(format)) throw new JsonInputError('CONTRACT_FORMAT', '$.documentFormat is unknown');
      return format;
    })(),
    result,
    diagnostics: diagnostics(root.diagnostics),
    receiptCodes: Object.freeze(receiptCodes)
  });
}

const IDENTITY_FIELDS = Object.freeze([
  ['producerTool', 'Tool'], ['producerVersion', 'Tool version'], ['producerJarSha256', 'Tool build'],
  ['linkedCompositeJarSha256', 'Linked composite build'], ['hostVersion', 'Host version'],
  ['hostCoreApiJarSha256', 'Host API build'], ['hostRuntimeJarSha256', 'Host runtime build'],
  ['authoringEnvironment', 'Authoring environment']]);

/**
 * Decides whether an imported report describes the current draft. Every identity field must be recorded and
 * equal; an unrecorded value makes the result unverifiable, a different one makes it stale.
 *
 * @param {object} report imported report
 * @param {object} current `{catalog, documentSha256, fixtureSha256?, priorPostStateSha256?}`; the prior digest is
 *        the `postStateSha256` of the report this rehearsal continues
 * @returns {{state: 'matching'|'stale'|'unverifiable', reasons: string[], rehearsal: string}} `state` concerns
 *          validation inputs; `rehearsal` is `matching`, `stale`, `unverified` or `not-applicable`, and is
 *          `matching` only when `state` is also `matching`
 */
export function reportMatch(report, current) {
  const reasons = [];
  let unverifiable = false;
  const catalog = current.catalog;
  const input = role => report.inputs.find(value => value.role === role);
  const equal = (label, left, right) => {
    if (left === null || right === null || left === undefined || right === undefined) {
      unverifiable = true;
      reasons.push(`${label} is not recorded`);
    } else if (left !== right) reasons.push(`${label} differs`);
  };
  if (!catalog) {
    unverifiable = true;
    reasons.push('no authoring catalog is loaded');
  } else {
    for (const [field, label] of IDENTITY_FIELDS) equal(label, report.identity[field], catalog.identity[field]);
    equal('Plugin catalog', report.catalog?.fingerprint ?? null, catalog.catalog.fingerprint);
    equal('Plugin API level', report.catalog ? `${report.catalog.pluginApiMajor}.${report.catalog.pluginApiLevel}` : null,
      `${catalog.catalog.pluginApiMajor}.${catalog.catalog.pluginApiLevel}`);
    equal('Context', input('context')?.sha256 ?? null, catalog.context.sha256);
  }
  if (!current.documentSha256) {
    unverifiable = true;
    reasons.push('the current draft cannot be exported');
  } else equal('Document', input('document')?.sha256 ?? null, current.documentSha256);
  const differs = reasons.some(reason => reason.endsWith('differs'));
  let rehearsal = 'not-applicable';
  const run = report.result?.rehearsal;
  if (run) {
    const checks = [[input('fixture')?.sha256 ?? null, current.fixtureSha256 ?? null]];
    if (run.fromPriorResult) checks.push([run.priorPostStateSha256, current.priorPostStateSha256 ?? null]);
    rehearsal = checks.some(([left, right]) => left && right && left !== right) ? 'stale'
      : checks.every(([left, right]) => left && right && left === right) ? 'matching' : 'unverified';
    // A rehearsal can only describe the draft if its validation inputs do.
    if (differs) rehearsal = 'stale';
    else if (unverifiable && rehearsal === 'matching') rehearsal = 'unverified';
  }
  return Object.freeze({state: differs ? 'stale' : unverifiable ? 'unverifiable' : 'matching',
    reasons: Object.freeze(reasons), rehearsal});
}
