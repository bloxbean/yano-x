/**
 * Import and lookup for `yano-x-binding-authoring-catalog-v1` (ADR-031.2 contract C1).
 *
 * A catalog is untrusted editing assistance produced by `appchain bindings catalog`. Validation is strict and
 * bounded; it loads no code and installs nothing. Studio never normalizes configuration: a component uses an
 * instance only when its machine id and authored configuration equal the instance's authored configuration
 * exactly (order-insensitive, typed). Identity fields let Studio tell whether a report came from the same
 * tool, host, plugin catalog and context; equal identities do not establish who produced either file.
 */
import {decodeUtf8Strict,expectArray,expectBoolean,expectDecimalInt64,expectObject,expectSmallInteger,expectString,
  isObject,JsonInputError,parseJson} from './lossless-json.mjs';

export const CATALOG_SCHEMA = 'yano-x-binding-authoring-catalog-v1';
export const SUPPORTED_LANGUAGE = Object.freeze({irVersion: 1n, functionCatalog: 'yano-x-binding-functions-v1',
  expressionDialect: 'yano-x-cel-v1'});
export const CATALOG_LIMITS = Object.freeze({maxBytes: 8 * 1024 * 1024, maxSelectors: 512, maxInstances: 256,
  maxEvents: 256, maxCommands: 256, maxFields: 256, maxSettings: 256});

const SHA256 = /^[0-9a-f]{64}$/;
const NAME = /^[a-zA-Z][a-zA-Z0-9_.-]{0,126}$/;
const MACHINE = /^[a-z][a-z0-9-]{0,62}$/;
const TYPES = ['integer', 'text', 'bytes', 'boolean'];
// Any bounded selector text without control characters; only binding identifiers can be composed.
const SELECTOR = /^[^\u0000-\u001f\u007f-\u009f\u2028\u2029]{1,256}$/;
const STATUSES = ['available', 'requires-configuration', 'construction-failed', 'descriptor-failed', 'not-composable'];
export const RECEIPT_CATEGORIES = Object.freeze(['target-rejection', 'resource-exhaustion', 'evaluation-error',
  'replay-or-conflict', 'contract-violation']);
export const RECEIPT_ORIGINS = Object.freeze(['engine', 'kernel', 'either']);
export const RECEIPT_LEVELS = Object.freeze(['source', 'step', 'condition', 'mapping', 'effect']);
export const LOCATION_CERTAINTY = Object.freeze(['always', 'never', 'ambiguous']);
const LAYOUTS = ['ARRAY_WITH_OPCODE', 'MAP', 'RAW_BYTES', 'ARRAY'];

const nullable = (value, check) => value === null ? null : check(value);

/**
 * Validates a typed scalar: `{type:"integer",value:"<decimal>"}`, `{type:"text",value}`,
 * `{type:"bytes",hex}` (lowercase) or `{type:"boolean",value}`. Integers become BigInt.
 */
export function typedScalar(value, path) {
  expectObject(value, path, ['type'], ['value', 'hex']);
  const type = expectString(value.type, `${path}.type`, 16);
  if (type === 'integer') {
    expectObject(value, path, ['type', 'value']);
    return Object.freeze({type, value: expectDecimalInt64(value.value, `${path}.value`)});
  }
  if (type === 'text') {
    expectObject(value, path, ['type', 'value']);
    return Object.freeze({type, value: expectString(value.value, `${path}.value`, 131_072)});
  }
  if (type === 'bytes') {
    expectObject(value, path, ['type', 'hex']);
    return Object.freeze({type, hex: expectString(value.hex, `${path}.hex`, 131_072, /^(?:[0-9a-f]{2})*$/)});
  }
  if (type === 'boolean') {
    expectObject(value, path, ['type', 'value']);
    return Object.freeze({type, value: expectBoolean(value.value, `${path}.value`)});
  }
  throw new JsonInputError('CONTRACT_FORMAT', `${path}.type is not a scalar type`, {path});
}

function typedMap(value, path) {
  if (!isObject(value)) throw new JsonInputError('CONTRACT_TYPE', `${path} must be an object`, {path});
  const names = Object.keys(value);
  if (names.length > CATALOG_LIMITS.maxSettings) throw new JsonInputError('CONTRACT_LIMIT', `${path} is too large`, {path});
  const result = Object.create(null);
  for (const name of names) {
    expectString(name, `${path} key`, 127, NAME);
    result[name] = typedScalar(value[name], `${path}.${name}`);
  }
  return Object.freeze(result);
}

/** Canonical, order-insensitive text key of one typed scalar. */
export function scalarKey(scalar) {
  if (scalar.type === 'bytes') return `bytes:${scalar.hex}`;
  if (scalar.type === 'integer') return `integer:${scalar.value.toString()}`;
  if (scalar.type === 'boolean') return `boolean:${scalar.value}`;
  return `text:${JSON.stringify(scalar.value)}`;
}

/** Canonical instance key: machine id plus exact authored typed configuration in name order. */
export function configurationKey(machineId, configuration) {
  const names = Object.keys(configuration).sort();
  return `${machineId}\u0000${names.map(name => `${JSON.stringify(name)}=${scalarKey(configuration[name])}`).join('\u0000')}`;
}

function fields(value, path) {
  return Object.freeze(expectArray(value, path, CATALOG_LIMITS.maxFields).map((field, index) => {
    const at = `${path}[${index}]`;
    expectObject(field, at, ['name', 'type', 'required'], ['role']);
    const result = {name: expectString(field.name, `${at}.name`, 127, NAME),
      type: expectString(field.type, `${at}.type`, 16), required: expectBoolean(field.required, `${at}.required`)};
    if (!TYPES.includes(result.type)) throw new JsonInputError('CONTRACT_FORMAT', `${at}.type is unknown`, {path: at});
    if (field.role !== undefined) {
      result.role = expectString(field.role, `${at}.role`, 16);
      if (!['data', 'evidence'].includes(result.role)) throw new JsonInputError('CONTRACT_FORMAT', `${at}.role is unknown`, {path: at});
    }
    return Object.freeze(result);
  }));
}

function diagnostic(value, path) {
  expectObject(value, path, ['code', 'severity', 'message', 'detailMayContainInput', 'location'], ['detail']);
  expectString(value.code, `${path}.code`, 64, /^[A-Z][A-Z0-9_]{0,63}$/);
  expectString(value.message, `${path}.message`, 512);
  if (value.detail !== undefined) expectString(value.detail, `${path}.detail`, 512);
  expectBoolean(value.detailMayContainInput, `${path}.detailMayContainInput`);
  if (!isObject(value.location)) throw new JsonInputError('CONTRACT_TYPE', `${path}.location must be an object`, {path});
  return Object.freeze({code: value.code, message: value.message, detail: value.detail ?? null,
    detailMayContainInput: value.detailMayContainInput, location: value.location});
}

/**
 * Validates a receipt-code table. Category, origin, levels and location certainty are closed enumerations; see
 * {@link clauseLocation} for when a code may point at a clause.
 */
export function validateReceiptCodes(value, path) {
  const seen = new Set();
  return Object.freeze(expectArray(value, path, 256).map((code, index) => {
    const at = `${path}[${index}]`;
    expectObject(code, at, ['code', 'category', 'origin', 'levels', 'locatesLastCondition']);
    const result = {code: expectString(code.code, `${at}.code`, 64, /^[A-Z][A-Z0-9_]{0,63}$/),
      category: expectString(code.category, `${at}.category`, 32), origin: expectString(code.origin, `${at}.origin`, 16),
      levels: Object.freeze(expectArray(code.levels, `${at}.levels`, RECEIPT_LEVELS.length).map((level, n) =>
        expectString(level, `${at}.levels[${n}]`, 16))),
      locatesLastCondition: expectString(code.locatesLastCondition, `${at}.locatesLastCondition`, 16)};
    if (!RECEIPT_CATEGORIES.includes(result.category) || !RECEIPT_ORIGINS.includes(result.origin)
        || !LOCATION_CERTAINTY.includes(result.locatesLastCondition) || !result.levels.length
        || result.levels.some(level => !RECEIPT_LEVELS.includes(level)) || new Set(result.levels).size !== result.levels.length) {
      throw new JsonInputError('CONTRACT_FORMAT', `${at} category, origin, levels or location certainty is unknown`, {path: at});
    }
    if (seen.has(result.code)) throw new JsonInputError('CONTRACT_FORMAT', `${at}.code is repeated`, {path: at});
    seen.add(result.code);
    return Object.freeze(result);
  }));
}

/**
 * Whether a rejected step's last condition record may be presented as the failing clause (contract C6). Only an
 * `always` code proves it, and only on a step with at least one condition record: a kernel rejection is decided
 * before any condition of its step is evaluated, so a kernel code that imitates an engine code has none.
 *
 * @param {object|undefined} entry receipt-code table entry for the step's code, if known
 * @param {object} step decoded receipt step
 * @returns {boolean}
 */
export function clauseLocation(entry, step) {
  return Boolean(entry) && entry.locatesLastCondition === 'always' && step.status === 'REJECTED'
    && step.conditions.length > 0;
}

const LIMIT_NAMES = ['maxCascadeDepth', 'maxDerivedPerSourceMessage', 'maxDerivedPerBlock', 'maxEventPayloadBytes',
  'maxLookupsPerCondition', 'maxFunctionCallsPerMapping', 'maxFunctionInputBytes', 'maxExpressionNodes',
  'maxExpressionDepth', 'maxExpressionValueBytes', 'maxExpressionWorkPerCascade', 'maxExpressionWorkPerBlock'];

/** Validates the exported authoring-language tables; every nested type and enumeration is checked. */
function languageTables(language) {
  const functions = expectArray(language.functions, '$.language.functions', 64).map((fn, index) => {
    const at = `$.language.functions[${index}]`;
    expectObject(fn, at, ['id', 'minArguments', 'maxArguments', 'arguments', 'homogeneous', 'result', 'bound']);
    const argumentTypes = expectArray(fn.arguments, `${at}.arguments`, 8).map((position, p) =>
      Object.freeze(expectArray(position, `${at}.arguments[${p}]`, 5).map((type, t) => {
        const value = expectString(type, `${at}.arguments[${p}][${t}]`, 16);
        if (![...TYPES, 'any'].includes(value)) throw new JsonInputError('CONTRACT_FORMAT', `${at} argument type is unknown`, {path: at});
        return value;
      })));
    const result = expectString(fn.result, `${at}.result`, 32);
    if (![...TYPES, 'same-as-arguments', 'opaque'].includes(result)) {
      throw new JsonInputError('CONTRACT_FORMAT', `${at}.result is unknown`, {path: at});
    }
    const minimum = expectSmallInteger(fn.minArguments, `${at}.minArguments`, 1, 8);
    const maximum = expectSmallInteger(fn.maxArguments, `${at}.maxArguments`, minimum, 8);
    if (!argumentTypes.length) throw new JsonInputError('CONTRACT_FORMAT', `${at}.arguments is empty`, {path: at});
    return Object.freeze({id: expectString(fn.id, `${at}.id`, 127, NAME), minArguments: minimum, maxArguments: maximum,
      arguments: Object.freeze(argumentTypes), homogeneous: expectBoolean(fn.homogeneous, `${at}.homogeneous`), result,
      bound: expectString(fn.bound, `${at}.bound`, 256)});
  });
  const operators = expectArray(language.expressionOperators, '$.language.expressionOperators', 64).map((op, index) => {
    const at = `$.language.expressionOperators[${index}]`;
    expectObject(op, at, ['cel', 'ir', 'operands', 'result']);
    return Object.freeze(Object.fromEntries(['cel', 'ir', 'operands', 'result'].map(key =>
      [key, expectString(op[key], `${at}.${key}`, 128)])));
  });
  const limits = expectArray(language.limits, '$.language.limits', LIMIT_NAMES.length).map((limit, index) => {
    const at = `$.language.limits[${index}]`;
    expectObject(limit, at, ['name', 'default', 'minimum', 'maximum']);
    if (limit.name !== LIMIT_NAMES[index]) throw new JsonInputError('CONTRACT_FORMAT', `${at}.name is not in wire order`, {path: at});
    const value = {name: limit.name, default: expectDecimalInt64(limit.default, `${at}.default`),
      minimum: expectDecimalInt64(limit.minimum, `${at}.minimum`), maximum: expectDecimalInt64(limit.maximum, `${at}.maximum`)};
    if (value.minimum > value.default || value.default > value.maximum) {
      throw new JsonInputError('CONTRACT_RANGE', `${at} default is outside its range`, {path: at});
    }
    return Object.freeze({...value, default: value.default.toString(), minimum: value.minimum.toString(),
      maximum: value.maximum.toString()});
  });
  if (limits.length !== LIMIT_NAMES.length) throw new JsonInputError('CONTRACT_FORMAT', '$.language.limits is incomplete');
  const structural = expectObject(language.structuralLimits, '$.language.structuralLimits', [], Object.keys(language.structuralLimits ?? {}));
  const structuralLimits = Object.create(null);
  for (const [key, value] of Object.entries(structural)) {
    structuralLimits[key] = typeof value === 'bigint' ? expectSmallInteger(value, `$.language.structuralLimits.${key}`, 0, 1 << 30)
      : expectString(value, `$.language.structuralLimits.${key}`, 128);
  }
  const baseline = expectObject(language.baselineEvent, '$.language.baselineEvent', ['eventId', 'fields', 'materialized']);
  return Object.freeze({
    functions: Object.freeze(functions), expressionOperators: Object.freeze(operators), limits: Object.freeze(limits),
    structuralLimits: Object.freeze(structuralLimits),
    baselineEvent: Object.freeze({eventId: expectString(baseline.eventId, '$.language.baselineEvent.eventId', 127, NAME),
      fields: fields(baseline.fields, '$.language.baselineEvent.fields'),
      materialized: expectString(baseline.materialized, '$.language.baselineEvent.materialized', 128)}),
    receiptCodes: validateReceiptCodes(language.receiptCodes, '$.language.receiptCodes')
  });
}

/** Validates the identity header shared by catalogs and reports. */
export function identity(root, path = '$') {
  const producer = expectObject(root.producer, `${path}.producer`, ['tool', 'version', 'jarSha256', 'linkedCompositeJarSha256']);
  const host = expectObject(root.host, `${path}.host`, ['version', 'coreApiJarSha256', 'runtimeJarSha256']);
  const hash = (value, at) => nullable(value, text => expectString(text, at, 64, SHA256));
  return Object.freeze({
    producerTool: expectString(producer.tool, `${path}.producer.tool`, 64),
    producerVersion: expectString(producer.version, `${path}.producer.version`, 64),
    producerJarSha256: hash(producer.jarSha256, `${path}.producer.jarSha256`),
    linkedCompositeJarSha256: hash(producer.linkedCompositeJarSha256, `${path}.producer.linkedCompositeJarSha256`),
    hostVersion: expectString(host.version, `${path}.host.version`, 64),
    hostCoreApiJarSha256: hash(host.coreApiJarSha256, `${path}.host.coreApiJarSha256`),
    hostRuntimeJarSha256: hash(host.runtimeJarSha256, `${path}.host.runtimeJarSha256`),
    authoringEnvironment: expectString(root.authoringEnvironment, `${path}.authoringEnvironment`, 128)
  });
}

/** Validates the plugin catalog identity: fingerprint, plugin API level and bundle inventory. */
export function catalogIdentity(value, path) {
  expectObject(value, path, ['fingerprint', 'pluginApi', 'bundles']);
  expectString(value.fingerprint, `${path}.fingerprint`, 128, /^sha256:[0-9a-f]{64}$/);
  const api = expectObject(value.pluginApi, `${path}.pluginApi`, ['major', 'level']);
  return Object.freeze({
    fingerprint: value.fingerprint,
    pluginApiMajor: expectSmallInteger(api.major, `${path}.pluginApi.major`, 0, 1_000_000),
    pluginApiLevel: expectSmallInteger(api.level, `${path}.pluginApi.level`, 0, 1_000_000),
    bundles: Object.freeze(expectArray(value.bundles, `${path}.bundles`, 512).map((bundle, index) => {
      const at = `${path}.bundles[${index}]`;
      expectObject(bundle, at, ['id', 'version', 'digest', 'digestMode', 'selected']);
      return Object.freeze({id: expectString(bundle.id, `${at}.id`, 256), version: expectString(bundle.version, `${at}.version`, 128),
        digest: nullable(bundle.digest, text => expectString(text, `${at}.digest`, 256)),
        digestMode: nullable(bundle.digestMode, text => expectString(text, `${at}.digestMode`, 64)),
        selected: expectBoolean(bundle.selected, `${at}.selected`)});
    }))
  });
}

/**
 * Validates an imported catalog file.
 *
 * @param {Uint8Array|string} input exact file bytes (preferred) or decoded text
 * @returns {object} frozen catalog with BigInt integers and an `instancesByKey` lookup map
 * @throws {JsonInputError} for malformed, oversized, unknown-schema or unsupported-language catalogs
 */
export function importAuthoringCatalog(input) {
  const size = typeof input === 'string' ? input.length : input.byteLength;
  if (size > CATALOG_LIMITS.maxBytes) throw new JsonInputError('CATALOG_TOO_LARGE', 'Catalog exceeds 8 MiB');
  const text = typeof input === 'string' ? input : decodeUtf8Strict(input);
  const root = parseJson(text, {maxCharacters: CATALOG_LIMITS.maxBytes, maxDepth: 48, maxStringCharacters: 2_097_152});
  if (!isObject(root) || root.schema !== CATALOG_SCHEMA) {
    throw new JsonInputError('CATALOG_SCHEMA', 'Not a yano-x-binding-authoring-catalog-v1 file');
  }
  expectObject(root, '$', ['schema', 'assurance', 'producer', 'host', 'authoringEnvironment', 'catalog', 'context',
    'document', 'language', 'selectors', 'instances', 'effects']);
  const language = expectObject(root.language, '$.language', ['irVersion', 'functionCatalog', 'expressionDialect',
    'functions', 'expressionOperators', 'limits', 'structuralLimits', 'baselineEvent', 'receiptCodes']);
  if (language.irVersion !== SUPPORTED_LANGUAGE.irVersion || language.functionCatalog !== SUPPORTED_LANGUAGE.functionCatalog
      || language.expressionDialect !== SUPPORTED_LANGUAGE.expressionDialect) {
    throw new JsonInputError('CATALOG_LANGUAGE', 'Catalog language version is not supported by this Studio');
  }
  const context = expectObject(root.context, '$.context', ['sha256', 'bytes', 'chainId']);
  expectString(context.sha256, '$.context.sha256', 64, SHA256);
  const selectors = expectArray(root.selectors, '$.selectors', CATALOG_LIMITS.maxSelectors).map((selector, index) => {
    const at = `$.selectors[${index}]`;
    expectObject(selector, at, ['machineId', 'origin', 'composable']);
    const machineId = expectString(selector.machineId, `${at}.machineId`, 256, SELECTOR);
    const composable = nullable(selector.composable, value => expectBoolean(value, `${at}.composable`));
    if (!MACHINE.test(machineId) && composable !== false) {
      throw new JsonInputError('CONTRACT_FORMAT', `${at} is not a binding identifier but is not marked non-composable`, {path: at});
    }
    const origin = expectObject(selector.origin, `${at}.origin`, ['kind'], ['bundleId', 'digest', 'digestMode']);
    if (!['builtin', 'bundle'].includes(origin.kind)) throw new JsonInputError('CONTRACT_FORMAT', `${at}.origin.kind is unknown`, {path: at});
    return Object.freeze({machineId, composable, origin: Object.freeze({kind: origin.kind,
      bundleId: origin.bundleId === undefined ? null : expectString(origin.bundleId, `${at}.origin.bundleId`, 256),
      digest: nullable(origin.digest ?? null, value => expectString(value, `${at}.origin.digest`, 256)),
      digestMode: nullable(origin.digestMode ?? null, value => expectString(value, `${at}.origin.digestMode`, 64))})});
  });
  const instances = expectArray(root.instances, '$.instances', CATALOG_LIMITS.maxInstances).map((instance, index) => {
    const at = `$.instances[${index}]`;
    expectObject(instance, at, ['machineId', 'basis', 'authoredConfiguration', 'status'], ['componentIds',
      'configurationDescriptor', 'applicationVersion', 'normalizedConfiguration', 'events', 'commands',
      'rawBodyTarget', 'readParticipants', 'diagnostic']);
    const result = {
      machineId: expectString(instance.machineId, `${at}.machineId`, 256, SELECTOR),
      basis: expectString(instance.basis, `${at}.basis`, 16),
      status: expectString(instance.status, `${at}.status`, 32),
      authoredConfiguration: typedMap(instance.authoredConfiguration, `${at}.authoredConfiguration`),
      componentIds: Object.freeze(instance.componentIds === undefined ? []
        : expectArray(instance.componentIds, `${at}.componentIds`, 16).map((id, n) =>
          expectString(id, `${at}.componentIds[${n}]`, 63, MACHINE)))
    };
    if (!['document', 'explicit', 'all'].includes(result.basis) || !STATUSES.includes(result.status)) {
      throw new JsonInputError('CONTRACT_FORMAT', `${at} basis or status is unknown`, {path: at});
    }
    if (result.status !== 'not-composable' && !MACHINE.test(result.machineId)) {
      throw new JsonInputError('CONTRACT_FORMAT', `${at} probes a selector that is not a binding identifier`, {path: at});
    }
    if (instance.configurationDescriptor !== undefined) {
      result.configurationDescriptor = Object.freeze(expectArray(instance.configurationDescriptor,
        `${at}.configurationDescriptor`, CATALOG_LIMITS.maxSettings).map((setting, n) => {
        const s = `${at}.configurationDescriptor[${n}]`;
        expectObject(setting, s, ['name', 'type', 'required', 'default']);
        return Object.freeze({name: expectString(setting.name, `${s}.name`, 127, NAME),
          type: expectString(setting.type, `${s}.type`, 16), required: expectBoolean(setting.required, `${s}.required`),
          default: nullable(setting.default, value => typedScalar(value, `${s}.default`))});
      }));
    }
    if (instance.diagnostic !== undefined) result.diagnostic = diagnostic(instance.diagnostic, `${at}.diagnostic`);
    if (result.status === 'available') {
      result.normalizedConfiguration = typedMap(instance.normalizedConfiguration, `${at}.normalizedConfiguration`);
      result.applicationVersion = expectString(instance.applicationVersion, `${at}.applicationVersion`, 128);
      result.events = Object.freeze(expectArray(instance.events, `${at}.events`, CATALOG_LIMITS.maxEvents)
        .map((event, n) => {
          const e = `${at}.events[${n}]`;
          expectObject(event, e, ['eventId', 'fields']);
          return Object.freeze({eventId: expectString(event.eventId, `${e}.eventId`, 127, NAME),
            fields: fields(event.fields, `${e}.fields`)});
        }));
      result.commands = Object.freeze(expectArray(instance.commands, `${at}.commands`, CATALOG_LIMITS.maxCommands)
        .map((command, n) => {
          const c = `${at}.commands[${n}]`;
          expectObject(command, c, ['commandName', 'layout', 'opCode', 'fields']);
          const layout = expectString(command.layout, `${c}.layout`, 32);
          if (!LAYOUTS.includes(layout)) throw new JsonInputError('CONTRACT_FORMAT', `${c}.layout is unknown`, {path: c});
          return Object.freeze({commandName: expectString(command.commandName, `${c}.commandName`, 127, NAME), layout,
            opCode: expectDecimalInt64(command.opCode, `${c}.opCode`), fields: fields(command.fields, `${c}.fields`)});
        }));
      result.rawBodyTarget = expectString(instance.rawBodyTarget, `${at}.rawBodyTarget`, 32);
      if (!['allowed', 'forbidden-evidence'].includes(result.rawBodyTarget)) {
        throw new JsonInputError('CONTRACT_FORMAT', `${at}.rawBodyTarget is unknown`, {path: at});
      }
      result.readParticipants = Object.freeze(expectArray(instance.readParticipants, `${at}.readParticipants`, 16)
        .map((id, n) => expectString(id, `${at}.readParticipants[${n}]`, 63, MACHINE)));
    }
    return Object.freeze(result);
  });
  const instancesByKey = new Map();
  for (const instance of instances) {
    const key = configurationKey(instance.machineId, instance.authoredConfiguration);
    if (instancesByKey.has(key)) throw new JsonInputError('CATALOG_DUPLICATE', 'Catalog repeats an instance', {path: '$.instances'});
    instancesByKey.set(key, instance);
  }
  return Object.freeze({
    identity: identity(root),
    catalog: catalogIdentity(root.catalog, '$.catalog'),
    context: Object.freeze({sha256: context.sha256, bytes: expectDecimalInt64(context.bytes, '$.context.bytes'),
      chainId: expectString(context.chainId, '$.context.chainId', 127)}),
    document: root.document === null ? null : expectObject(root.document, '$.document', ['sha256', 'bytes']),
    language: Object.freeze({irVersion: language.irVersion, functionCatalog: language.functionCatalog,
      expressionDialect: language.expressionDialect, ...languageTables(language)}),
    selectors: Object.freeze(selectors),
    instances: Object.freeze(instances),
    instancesByKey,
    assurance: expectString(root.assurance, '$.assurance', 512)
  });
}

/**
 * Finds the instance describing a component, using only exact equality of machine id and authored configuration.
 * When `expected` is given, the catalog must still be the one the caller bound its draft to: a catalog for another
 * context or plugin catalog describes nothing about this draft.
 *
 * @param {object} catalog imported catalog
 * @param {string} machineId authored machine selector
 * @param {object} configuration authored typed configuration (name → typed scalar), not normalized
 * @param {{contextSha256: string, fingerprint: string}} [expected] identity the draft is being checked against
 * @returns {object|null} the matching instance, or null when this configuration is not described
 */
export function findInstance(catalog, machineId, configuration, expected) {
  if (!catalog) return null;
  if (expected && (expected.contextSha256 !== catalog.context.sha256 || expected.fingerprint !== catalog.catalog.fingerprint)) {
    return null;
  }
  return catalog.instancesByKey.get(configurationKey(machineId, configuration)) ?? null;
}

/** Identity a draft binds to when it starts using a catalog; see {@link findInstance}. */
export function catalogBinding(catalog) {
  return Object.freeze({contextSha256: catalog.context.sha256, fingerprint: catalog.catalog.fingerprint});
}
