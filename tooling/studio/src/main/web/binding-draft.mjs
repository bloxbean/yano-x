/**
 * Authored binding-document model for the guided editor (ADR-031.2 contract C5).
 *
 * The draft mirrors the YAML authoring schema exactly: component, binding, clause, `in`-list, function-argument
 * and assignment order; the presence of every optional field; the `composite:` wrapper; CEL source text and bytes
 * literals as written. It never stores normalized configuration, catalog defaults or IR. Round-trip equivalence
 * is decided by the Java compiler (identical canonical IR under the same catalog and context), not here.
 *
 * Import accepts the closed schema the compiler accepts, plus one incomplete form the editor itself creates (an
 * empty field map, which the compiler rejects with its own diagnostic). Anything this model cannot represent
 * exactly is refused with the compiler's path so the editor opens the document read-only rather than dropping
 * content.
 * Nothing here evaluates expressions, encodes IR or decides validity; local checks are advisory.
 */
import {emitYaml,flow,parseYaml,YamlInputError,YamlMap} from './studio-yaml.mjs';

export const OPERATORS = Object.freeze(['eq', 'ne', 'lt', 'le', 'gt', 'ge', 'in', 'exists', 'absent']);
export const LIMIT_NAMES = Object.freeze(['maxCascadeDepth', 'maxDerivedPerSourceMessage', 'maxDerivedPerBlock',
  'maxEventPayloadBytes', 'maxLookupsPerCondition', 'maxFunctionCallsPerMapping', 'maxFunctionInputBytes',
  'maxExpressionNodes', 'maxExpressionDepth', 'maxExpressionValueBytes', 'maxExpressionWorkPerCascade',
  'maxExpressionWorkPerBlock']);

/** Unrepresentable or schema-invalid import; `path` uses compiler-style segments. */
export class DraftImportError extends Error {
  constructor(code, message, segments, node) {
    super(`${renderPath(segments)}: ${message}`);
    this.name = 'DraftImportError';
    this.code = code;
    this.segments = Object.freeze([...segments]);
    this.line = node?.line;
    this.column = node?.column;
  }
}

/** Renders path segments in the compiler's `$.bindings[0].to.map.x` notation (display only). */
export function renderPath(segments) {
  return `$${segments.map(segment => typeof segment === 'number' ? `[${segment}]` : `.${segment}`).join('')}`;
}

const fail = (code, message, segments, node) => { throw new DraftImportError(code, message, segments, node); };

function entries(node, segments, allowed) {
  if (node?.kind !== 'map') fail('EXPECTED_OBJECT', 'expected object', segments, node);
  const result = new Map();
  for (const entry of node.entries) {
    if (!allowed.includes(entry.key.text)) {
      fail('UNKNOWN_FIELD', 'unknown field', [...segments, entry.key.text], entry.key);
    }
    result.set(entry.key.text, entry.value);
  }
  return result;
}

function required(map, key, segments, parent) {
  if (!map.has(key)) fail('REQUIRED_FIELD', 'required field', [...segments, key], parent);
  return map.get(key);
}

function text(node, segments) {
  if (node?.kind !== 'scalar' || node.type !== 'text') fail('EXPECTED_TEXT', 'expected text', segments, node);
  return node.value;
}

function integer(node, segments) {
  if (node?.kind !== 'scalar' || node.type !== 'integer') fail('EXPECTED_INTEGER', 'expected int64', segments, node);
  return node.value;
}

function sequence(node, segments, maximum) {
  if (node?.kind !== 'seq' || node.items.length > maximum) fail('EXPECTED_ARRAY', 'expected bounded array', segments, node);
  return node.items;
}

function exactlyOne(map, segments, options, node) {
  const present = options.filter(option => map.has(option));
  if (present.length !== 1) fail('EXACTLY_ONE_REQUIRED', `expected exactly one of [${options.join(', ')}]`, segments, node);
  return present[0];
}

function mustBeTrue(node, segments) {
  if (node?.kind !== 'scalar' || node.type !== 'boolean' || node.value !== true) {
    fail('MUST_BE_TRUE', 'must be true; use the opposite operator', segments, node);
  }
  return true;
}

/** Scalar literal: int64, text, boolean or `{bytesHex: text}` exactly as authored. */
function scalar(node, segments) {
  if (node?.kind === 'scalar') {
    if (node.type === 'integer') return {type: 'integer', value: node.value};
    if (node.type === 'boolean') return {type: 'boolean', value: node.value};
    return {type: 'text', value: node.value};
  }
  if (node?.kind === 'map') {
    const map = entries(node, segments, ['bytesHex']);
    const hex = text(required(map, 'bytesHex', segments, node), [...segments, 'bytesHex']);
    if (hex.length > 131_072) fail('INVALID_BYTES_LITERAL', 'byte literal limit', segments, node);
    if (hex.length % 2 || !/^[0-9a-fA-F]*$/.test(hex)) fail('INVALID_BYTES_LITERAL', 'invalid bytesHex literal', segments, node);
    return {type: 'bytes', hex};
  }
  return fail('EXPECTED_SCALAR', 'expected int64, text, boolean, or {bytesHex: hexadecimal-text}', segments, node);
}

function source(node, segments, depth) {
  if (depth > 2) fail('FUNCTION_NESTING_LIMIT', 'function nesting limit', segments, node);
  const map = entries(node, segments, ['field', 'literal', 'fn', 'args', 'expr']);
  const kind = exactlyOne(map, segments, ['field', 'literal', 'fn', 'expr'], node);
  if (kind !== 'fn' && map.has('args')) fail('ONLY_FUNCTIONS_ACCEPT_ARGS', 'only functions accept args', [...segments, 'args'], node);
  if (kind === 'field') return {kind, name: text(map.get('field'), [...segments, 'field'])};
  if (kind === 'literal') return {kind, value: scalar(map.get('literal'), [...segments, 'literal'])};
  if (kind === 'expr') return {kind, text: text(map.get('expr'), [...segments, 'expr'])};
  const fn = text(map.get('fn'), [...segments, 'fn']);
  const args = sequence(required(map, 'args', segments, node), [...segments, 'args'], 8)
    .map((item, index) => source(item, [...segments, 'args', index], depth + 1));
  return {kind, fn, args};
}

function clause(node, segments) {
  if (node?.kind === 'map' && node.entries.some(entry => entry.key.text === 'expr')) {
    const map = entries(node, segments, ['expr']);
    return {kind: 'expr', text: text(map.get('expr'), [...segments, 'expr'])};
  }
  if (node?.kind === 'map' && node.entries.some(entry => entry.key.text === 'lookup')) {
    entries(node, segments, ['lookup']);
    const lookupPath = [...segments, 'lookup'];
    const lookupNode = node.entries[0].value;
    const map = entries(lookupNode, lookupPath, ['component', 'key', 'exists', 'absent', 'eq']);
    const expectation = exactlyOne(map, lookupPath, ['exists', 'absent', 'eq'], lookupNode);
    const operand = expectation === 'eq' ? source(map.get('eq'), [...lookupPath, 'eq'], 0) : null;
    if (expectation !== 'eq') mustBeTrue(map.get(expectation), [...lookupPath, expectation]);
    return {kind: 'lookup', component: text(required(map, 'component', segments, lookupNode), [...lookupPath, 'component']),
      key: source(required(map, 'key', segments, lookupNode), [...lookupPath, 'key'], 0), expectation, operand};
  }
  const map = entries(node, segments, ['field', ...OPERATORS]);
  const operator = exactlyOne(map, segments, OPERATORS, node);
  const field = text(required(map, 'field', segments, node), [...segments, 'field']);
  let operand;
  if (operator === 'in') {
    operand = sequence(map.get('in'), [...segments, 'in'], 64).map(item => scalar(item, [...segments, 'in']));
  } else if (operator === 'exists' || operator === 'absent') {
    operand = mustBeTrue(map.get(operator), [...segments, operator]);
  } else operand = scalar(map.get(operator), [...segments, operator]);
  return {kind: 'field', field, operator, operand};
}

function mapping(map, segments, parent) {
  const kind = exactlyOne(map, segments, ['map', 'rawBody'], parent);
  if (kind === 'rawBody') return {kind: 'raw', field: text(map.get('rawBody'), [...segments, 'rawBody'])};
  const node = map.get('map');
  if (node?.kind === 'scalar' && node.type === 'text' && node.value === 'identity') return {kind: 'identity'};
  // An empty map is an incomplete draft the editor creates while mapping fields; it emits and re-imports
  // losslessly, and the compiler rejects it with its own diagnostic, so it is kept rather than refused.
  if (node?.kind !== 'map' || node.entries.length > 16) {
    fail('EXPECTED_OBJECT', 'expected field map', [...segments, 'map'], node);
  }
  return {kind: 'fields', assignments: node.entries.map(entry => ({field: entry.key.text,
    source: source(entry.value, [...segments, 'map', entry.key.text], 0)}))};
}

function target(node, segments) {
  if (node?.kind === 'map' && node.entries.some(entry => entry.key.text === 'effect')) {
    entries(node, segments, ['effect']);
    const effectNode = node.entries[0].value;
    const effectPath = [...segments, 'effect'];
    const map = entries(effectNode, effectPath, ['type', 'gate', 'result', 'expiryBlocks', 'map', 'rawBody']);
    return {kind: 'effect', type: text(required(map, 'type', segments, effectNode), [...effectPath, 'type']),
      gate: map.has('gate') ? text(map.get('gate'), [...segments, 'gate']) : null,
      result: map.has('result') ? text(map.get('result'), [...segments, 'result']) : null,
      expiryBlocks: map.has('expiryBlocks') ? integer(map.get('expiryBlocks'), [...segments, 'expiryBlocks']) : null,
      mapping: mapping(map, effectPath, effectNode)};
  }
  const map = entries(node, segments, ['component', 'command', 'map', 'rawBody']);
  return {kind: 'command', component: text(required(map, 'component', segments, node), [...segments, 'component']),
    command: text(required(map, 'command', segments, node), [...segments, 'command']),
    mapping: mapping(map, segments, node)};
}

/**
 * Converts a Studio YAML tree into a draft, accepting only the closed authoring schema.
 *
 * @param {object} root parsed root node from `parseYaml`
 * @returns {object} draft
 * @throws {DraftImportError} with compiler-style path segments when the tree cannot be represented exactly
 */
export function draftFromTree(root) {
  let wrapped = false;
  let body = root;
  let prefix = [];
  if (root?.kind === 'map' && root.entries.some(entry => entry.key.text === 'composite')) {
    entries(root, [], ['composite']);
    wrapped = true;
    body = root.entries[0].value;
    prefix = ['composite'];
    if (body?.kind === 'map' && body.entries.some(entry => entry.key.text === 'composite')) {
      fail('NESTED_WRAPPER', 'nested wrappers are not supported', prefix, body);
    }
  }
  const map = entries(body, prefix, ['components', 'bindings', 'limits', 'workflowFromHeight']);
  const components = sequence(required(map, 'components', prefix, body), [...prefix, 'components'], 16)
    .map((node, index) => {
      const at = [...prefix, 'components', index];
      const fields = entries(node, at, ['id', 'machine', 'topic', 'config', 'maxEffectsPerBlock', 'fromHeight']);
      let config = null;
      if (fields.has('config')) {
        const configNode = fields.get('config');
        if (configNode?.kind !== 'map' || configNode.entries.length > 64) {
          fail('EXPECTED_OBJECT', 'expected bounded map', [...at, 'config'], configNode);
        }
        config = configNode.entries.map(entry => ({name: entry.key.text,
          value: scalar(entry.value, [...at, 'config', entry.key.text])}));
      }
      return {id: text(required(fields, 'id', at, node), [...at, 'id']),
        machine: text(required(fields, 'machine', at, node), [...at, 'machine']),
        topic: fields.has('topic') ? text(fields.get('topic'), [...at, 'topic']) : null,
        config,
        maxEffectsPerBlock: fields.has('maxEffectsPerBlock')
          ? integer(fields.get('maxEffectsPerBlock'), [...at, 'maxEffectsPerBlock']) : null,
        fromHeight: fields.has('fromHeight') ? integer(fields.get('fromHeight'), [...at, 'fromHeight']) : null};
    });
  const bindings = sequence(required(map, 'bindings', prefix, body), [...prefix, 'bindings'], 256).map((node, index) => {
    const at = [...prefix, 'bindings', index];
    const fields = entries(node, at, ['id', 'from', 'when', 'to']);
    const fromNode = required(fields, 'from', at, node);
    const from = entries(fromNode, [...at, 'from'], ['component', 'event']);
    return {id: text(required(fields, 'id', at, node), [...at, 'id']),
      from: {component: text(required(from, 'component', [...at, 'from'], fromNode), [...at, 'from', 'component']),
        event: text(required(from, 'event', [...at, 'from'], fromNode), [...at, 'from', 'event'])},
      when: fields.has('when') ? sequence(fields.get('when'), [...at, 'when'], 8)
        .map((item, c) => clause(item, [...at, 'when', c])) : null,
      to: target(required(fields, 'to', at, node), [...at, 'to'])};
  });
  let limits = null;
  if (map.has('limits')) {
    const limitsNode = map.get('limits');
    const limitMap = entries(limitsNode, [...prefix, 'limits'], LIMIT_NAMES);
    limits = [...limitMap.entries()].map(([name, value]) => ({name, value: integer(value, [...prefix, 'limits', name])}));
  }
  return {wrapped, components, bindings, limits,
    workflowFromHeight: map.has('workflowFromHeight')
      ? integer(map.get('workflowFromHeight'), [...prefix, 'workflowFromHeight']) : null};
}

/**
 * Imports document text. Syntax errors and unrepresentable documents are reported, never partially loaded.
 *
 * @returns {{state:'editable', draft, lineEnding} | {state:'syntax-error'|'read-only', error}}
 */
export function importDocument(text) {
  let parsed;
  try { parsed = parseYaml(text); }
  catch (error) {
    if (error instanceof YamlInputError) {
      const unsupported = ['YAML_UNSUPPORTED_CONSTRUCT', 'YAML_AMBIGUOUS_SCALAR', 'YAML_UNSUPPORTED_CHARACTER',
        'YAML_EMPTY_VALUE', 'YAML_INVALID_ESCAPE'].includes(error.code);
      return {state: unsupported ? 'read-only' : 'syntax-error', error};
    }
    throw error;
  }
  try {
    return {state: 'editable', draft: draftFromTree(parsed.root), lineEnding: parsed.lineEnding};
  } catch (error) {
    if (error instanceof DraftImportError) return {state: 'read-only', error};
    throw error;
  }
}

// ---------------------------------------------------------------------------
// Emission
// ---------------------------------------------------------------------------

function emitScalar(value) {
  if (value.type === 'bytes') return flow(YamlMap.of(['bytesHex', value.hex]));
  return value.value;
}

function emitSource(value) {
  switch (value.kind) {
    case 'field': return flow(YamlMap.of(['field', value.name]));
    case 'literal': return flow(YamlMap.of(['literal', emitScalar(value.value)]));
    case 'expr': return flow(YamlMap.of(['expr', value.text]));
    default: return flow(YamlMap.of(['fn', value.fn], ['args', value.args.map(emitSource)]));
  }
}

function emitClause(value) {
  if (value.kind === 'expr') return flow(YamlMap.of(['expr', value.text]));
  if (value.kind === 'lookup') {
    const expectation = value.expectation === 'eq' ? ['eq', emitSource(value.operand)] : [value.expectation, true];
    return flow(YamlMap.of(['lookup', flow(YamlMap.of(['component', value.component], ['key', emitSource(value.key)],
      expectation))]));
  }
  const operand = value.operator === 'in' ? flow(value.operand.map(emitScalar))
    : value.operator === 'exists' || value.operator === 'absent' ? true : emitScalar(value.operand);
  return flow(YamlMap.of(['field', value.field], [value.operator, operand]));
}

function emitMapping(value) {
  if (value.kind === 'identity') return [['map', 'identity']];
  if (value.kind === 'raw') return [['rawBody', value.field]];
  return [['map', new YamlMap(value.assignments.map(assignment => [assignment.field, emitSource(assignment.source)]))]];
}

function emitTarget(value) {
  if (value.kind === 'command') {
    return new YamlMap([['component', value.component], ['command', value.command], ...emitMapping(value.mapping)]);
  }
  return YamlMap.of(['effect', new YamlMap([['type', value.type], ['gate', value.gate ?? undefined],
    ['result', value.result ?? undefined], ['expiryBlocks', value.expiryBlocks ?? undefined],
    ...emitMapping(value.mapping)])]);
}

/** Deterministic YAML for a draft; only authored optional fields are written. */
export function emitDocument(draft, options = {}) {
  const components = draft.components.map(component => new YamlMap([
    ['id', component.id], ['machine', component.machine], ['topic', component.topic ?? undefined],
    ['config', component.config === null ? undefined
      : flow(new YamlMap(component.config.map(setting => [setting.name, emitScalar(setting.value)])))],
    ['maxEffectsPerBlock', component.maxEffectsPerBlock ?? undefined],
    ['fromHeight', component.fromHeight ?? undefined]]));
  const bindings = draft.bindings.map(binding => new YamlMap([
    ['id', binding.id],
    ['from', flow(YamlMap.of(['component', binding.from.component], ['event', binding.from.event]))],
    ['when', binding.when === null ? undefined : binding.when.map(emitClause)],
    ['to', emitTarget(binding.to)]]));
  const body = new YamlMap([['components', components], ['bindings', bindings],
    ['limits', draft.limits === null ? undefined : new YamlMap(draft.limits.map(limit => [limit.name, limit.value]))],
    ['workflowFromHeight', draft.workflowFromHeight ?? undefined]]);
  return emitYaml(draft.wrapped ? YamlMap.of(['composite', body]) : body, options);
}

/** A structurally independent copy (drafts are treated as immutable values by the editor). */
export function cloneDraft(draft) {
  return structuredClone(draft);
}

/** Empty wrapped draft for a new document. */
export function emptyDraft() {
  return {wrapped: true, components: [], bindings: [], limits: null, workflowFromHeight: null};
}
