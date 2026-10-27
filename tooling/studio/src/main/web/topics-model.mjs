/** Topic policy authoring. Output is unsigned and advisory until the host accepts it. */
import {decodeWire, encodeWire, blake2b256, hex, unhex, isValidEntryName, covers, verifyDelegation}
  from './assets/topics/topics.mjs';
import {compileCddl} from './topic-cddl.mjs';
import {parseYaml, emitYaml, YamlMap} from './studio-yaml.mjs';
import {parseJson, stringifyJson} from './lossless-json.mjs';
const fail = message => { throw new Error(message); };
const fields = (value, allowed) => {
  if (!value || typeof value !== 'object' || Array.isArray(value)) fail('Expected an object');
  for (const key of Object.keys(value)) if (!allowed.includes(key)) fail(`Unknown field: ${key}`);
};
const text = (object, key) => typeof object?.[key] === 'string' && object[key].trim()
  ? object[key] : fail(`Required text: ${key}`);
const integer = (value, min = 0n) => {
  if (!['bigint', 'number'].includes(typeof value) || typeof value === 'number' && !Number.isSafeInteger(value)
      || BigInt(value) < min || BigInt(value) > 9223372036854775807n) fail('Expected bounded integer');
  return BigInt(value);
};
const hash = s => (unhex(s, 32), s);
const increment = n => integer(integer(n) + 1n);
const copy = value => parseJson(stringifyJson(value));
function keyList(keys) {
  if (!Array.isArray(keys) || keys.length > 16) fail('Invalid key list');
  const sorted = keys.map(hash).sort();
  if (new Set(sorted).size !== sorted.length) fail('Duplicate key');
  return sorted;
}
function keySet(value) {
  fields(value, ['threshold', 'keys']);
  const keys = keyList(value.keys), threshold = integer(value.threshold, 1n);
  if (threshold > keys.length) fail('Invalid threshold');
  return {threshold, keys};
}
const keyWire = value => [value.threshold, value.keys.map(k => unhex(k, 32))];
const policyWire = p => [p.writers === null ? null : p.writers.keys === null ? 0 : keyWire(p.writers.keys),
  p.delegate === null ? null : p.delegate.map(k => unhex(k, 32)), p.maxDelegationMs,
  p.schema === null ? null : unhex(p.schema), p.maxPayloadBytes];
function policy(value) {
  const result = {writers: null, delegate: null, maxDelegationMs: null, schema: null, maxPayloadBytes: null};
  if (value === undefined || value === null) return {policy: result, ir: null};
  fields(value, ['writers', 'delegate', 'maxDelegationMs', 'schema', 'schemaCddl', 'maxPayloadBytes']);
  if (value.writers != null) result.writers = {keys: value.writers === 'open' ? null : keySet(value.writers)};
  if (value.delegate != null) result.delegate = keyList(value.delegate);
  for (const k of ['maxDelegationMs', 'maxPayloadBytes']) if (value[k] != null) result[k] = integer(value[k], 1n);
  let ir = null;
  if (value.schemaCddl != null) {
    if (Object.hasOwn(value, 'schema')) fail('Choose schema or schemaCddl');
    ir = compileCddl(text(value, 'schemaCddl')); result.schema = hex(blake2b256(ir));
  } else if (value.schema != null) result.schema = value.schema === 'none' ? '' : hash(value.schema);
  return {policy: result, ir};
}
export function applicableNames(topic) {
  if (!isValidEntryName(topic)) fail('Invalid topic or namespace');
  const names = [];
  for (let i = 0; i < topic.length; i++) if (topic[i] === '/') names.push(topic.slice(0, i + 1));
  if (!topic.endsWith('/')) names.push(topic);
  return names;
}
export function resolvePolicy(topic, snapshot) {
  const config = snapshot.config;
  if (!config) return {kind: 'DISABLED', policy: null, configRevision: null, sources: []};
  const p = {writers: {keys: null}, delegate: [], maxDelegationMs: config.settings.maxDelegationMs,
    schema: '', maxPayloadBytes: snapshot.maxMessageBytes, retired: false};
  const sources = [];
  for (const name of applicableNames(topic)) {
    const active = snapshot.entries[name]?.active;
    if (!active) continue;
    for (const key of ['writers', 'delegate', 'maxDelegationMs', 'schema', 'maxPayloadBytes']) {
      if (active.content.policy[key] != null) p[key] = copy(active.content.policy[key]);
    }
    p.retired ||= active.content.retired;
    sources.push({name, revision: active.revision});
  }
  if (!sources.length) return {kind: config.settings.unregisteredReject ? 'UNREGISTERED_REJECT' : 'UNREGISTERED_OPEN',
    policy: null, configRevision: config.revision, sources};
  p.delegate = p.writers.keys && integer(p.writers.keys.threshold) === 1n
    ? p.delegate.filter(k => p.writers.keys.keys.includes(k)) : [];
  if (integer(p.maxDelegationMs) > integer(config.settings.maxDelegationMs)) p.maxDelegationMs = config.settings.maxDelegationMs;
  if (integer(p.maxPayloadBytes) > integer(snapshot.maxMessageBytes)) p.maxPayloadBytes = snapshot.maxMessageBytes;
  return {kind: 'POLICY', policy: p, configRevision: config.revision, sources};
}
async function authorityKeys(command, snapshot, delegation) {
  const {authority, target, operation} = command, create = operation === 'create';
  if (authority === '~claim') {
    if (!create || !/^[^/]+\/$/.test(target) || !snapshot.config.settings.openClaims) fail('Open claim is not allowed');
    return keySet(command.owners);
  }
  if (authority === '~registrars') {
    if (!create || !/^[^/]+\/$/.test(target) || !snapshot.config.settings.registrars) fail('Registrar creation is not allowed');
    return keySet(snapshot.config.settings.registrars);
  }
  if (operation === 'revoke') {
    if (delegation[2] !== target || !await verifyDelegation(snapshot.chainId, snapshot.generationId, delegation)) {
      fail('Invalid delegation for revocation');
    }
    if (authority === '~issuer') {
      const issuer = hex(delegation[5]), outcome = resolvePolicy(target, snapshot);
      if (!outcome.policy?.delegate.includes(issuer)) fail('Issuer is not a delegator for the scope');
      return {threshold: 1n, keys: [issuer]};
    }
  } else if (authority === '~issuer') fail('Issuer may only revoke');
  if (!covers(authority, target) || create && authority === target) fail('Authority must be an ancestor or target');
  const owner = snapshot.entries[authority];
  if (!owner?.active || owner.active.content.retired && operation !== 'revoke') fail('Authority has no active unretired entry');
  return keySet(owner.active.content.owners);
}
function validateOperation(command) {
  const {operation, authority} = command;
  if (!['create', 'update', 'rotate', 'recover', 'retire', 'revoke'].includes(operation)) fail('Unknown operation');
  if (['create', 'rotate', 'recover'].includes(operation)) keySet(command.owners);
  else if (Object.hasOwn(command, 'owners')) fail('Owners are not used by this operation');
  if (!['create', 'update'].includes(operation) && Object.hasOwn(command, 'policy')) fail('Policy is not used by this operation');
  if (Object.hasOwn(command, 'delegationHex') !== (operation === 'revoke')) fail('Delegation is required only for revocation');
  if (authority === 'governance' && !['create', 'recover', 'retire'].includes(operation)) fail('Unsupported governance operation');
  if (authority === 'governance' && Object.hasOwn(command, 'notAfter')) fail('Governance commands do not use notAfter');
  if (authority !== 'governance' && operation === 'recover') fail('Recovery requires governance');
}
/** Exactly the CLI's unsigned plan; revocation credentials are verified before exposing signing bytes. */
export async function compileTopicPlan(document, inputSnapshot) {
  // WebCrypto verification yields: caller edits must not change the already-checked statement or authority.
  document = copy(document);
  fields(document, ['schemaVersion', 'kind', 'chainId', 'commands']);
  if (integer(document.schemaVersion) !== 1n || document.kind !== 'Topics') fail('Expected Topics schemaVersion 1');
  const snapshot = copy(inputSnapshot); snapshot.freedHeads ??= {};
  hash(snapshot.generationId); hash(snapshot.stateRoot); integer(snapshot.height, 1n); integer(snapshot.maxMessageBytes, 1n);
  if (text(document, 'chainId') !== snapshot.chainId) fail('chainId differs from snapshot');
  if (!Array.isArray(document.commands) || !document.commands.length || document.commands.length > 64) fail('Expected 1..64 commands');
  if (!snapshot.config) fail('Topics are disabled');
  const seen = new Set(), nonces = new Set(), commands = [];
  for (const command of document.commands) {
    fields(command, ['target', 'operation', 'authority', 'nonce', 'notAfter', 'owners', 'policy', 'delegationHex']);
    const target = text(command, 'target'), operation = text(command, 'operation'), authority = text(command, 'authority');
    if (!isValidEntryName(target) || seen.has(target)) fail('Expected unique valid targets');
    seen.add(target); unhex(command.nonce, 16);
    if (nonces.has(command.nonce)) fail('Nonce must be unique'); nonces.add(command.nonce);
    validateOperation(command);
    const existing = snapshot.entries[target], old = existing?.active?.content;
    if (existing?.pending > 0 && authority !== 'governance') fail('TOPIC_COMMAND_PENDING: replan after activation');
    const authored = policy(command.policy), p = authored.policy, ir = authored.ir;
    const step = {target, operation, authority};
    if (authority === 'governance') {
      if (!snapshot.config.settings.governance) fail('Topic governance is disabled');
      if (operation === 'create' && (!/^[^/]+\/$/.test(target) || existing)) fail('Governance creates a new top-level namespace');
      const op = operation === 'create' ? [0, target, keyWire(keySet(command.owners)), policyWire(p), ir]
        : operation === 'recover' ? [1, target, keyWire(keySet(command.owners))] : [2, target];
      step.mode = 'governance';
      step.commandHex = hex(encodeWire('governance-command', ['yano-topic-governance-v1', snapshot.chainId,
        unhex(snapshot.generationId), unhex(command.nonce), op]));
      step.endpoint = 'admin/topics/governance';
    } else {
      const revoke = operation === 'revoke';
      let delegation = null;
      if (revoke) {
        const encoded = text(command, 'delegationHex');
        if (encoded.length > 8192) fail('Delegation exceeds 4096 bytes');
        delegation = decodeWire('delegation', unhex(encoded));
      }
      if (operation === 'create' ? Boolean(existing) : !revoke && !existing) fail('TOPIC_COMMAND_STALE: target existence differs');
      const op = operation === 'create' ? [0, keyWire(keySet(command.owners)), policyWire(p), ir]
        : operation === 'update' ? [1, policyWire(p), ir]
          : operation === 'rotate' ? [2, keyWire(keySet(command.owners))] : revoke ? [4, delegation] : [3];
      const statement = ['yano-topic-command-v1', snapshot.chainId, unhex(snapshot.generationId), target, authority,
        operation === 'create' || revoke ? null : existing.head, unhex(command.nonce), integer(command.notAfter), op];
      const requiredSigners = await authorityKeys(command, snapshot, delegation);
      const bytes = encodeWire('command-statement', statement);
      step.mode = 'registry'; step.statementHex = hex(bytes); step.statementHash = hex(blake2b256(bytes));
      step.requiredSigners = requiredSigners; step.signatures = [];
    }
    step.before = resolvePolicy(target, snapshot);
    if (operation !== 'revoke') {
      if (operation !== 'create' && !old) fail('Target has no active revision');
      const revision = increment(existing ? existing.head : snapshot.freedHeads[target] ?? 0);
      const activation = integer(increment(snapshot.height) + integer(snapshot.config.settings.activationLag));
      const owners = ['create', 'rotate', 'recover'].includes(operation) ? keySet(command.owners) : old.owners;
      const changed = ['create', 'update'].includes(operation) ? p : old.policy;
      const since = ['create', 'rotate', 'recover'].includes(operation) ? revision : existing.active.ownersSince;
      const after = {...snapshot, entries: {...snapshot.entries, [target]: {name: target,
        active: {revision, activeFrom: activation, ownersSince: since,
          content: {owners, policy: changed, retired: operation === 'retire' || Boolean(old?.retired)}},
        head: revision, pending: 0, subtree: existing?.subtree ?? 0}}};
      step.earliestActivation = activation; step.afterActivation = resolvePolicy(target, after);
    }
    if (existing?.pending > 0) step.warning = 'Pending revisions exist; preview uses active policy. Replan after activation.';
    commands.push(step);
  }
  return {schemaVersion: 1, kind: 'TopicsPlan', document: copy(document), snapshot, commands};
}
function yamlValue(node) {
  if (node.kind === 'map') return Object.fromEntries(node.entries.map(e => [e.key.text, yamlValue(e.value)]));
  if (node.kind === 'seq') return node.items.map(yamlValue);
  if (node.kind === 'scalar') return node.value;
  fail('Unsupported YAML value');
}
export function readTopics(text) {
  const value = text.trim().startsWith('{') ? parseJson(text) : yamlValue(parseYaml(text).root);
  fields(value, ['schemaVersion', 'kind', 'chainId', 'commands']);
  return copy(value);
}
export function topicsYaml(document) {
  const toYaml = value => Array.isArray(value) ? value.map(toYaml)
    : value && typeof value === 'object' ? new YamlMap(Object.entries(value).map(([k, v]) => [k, toYaml(v)]))
      : typeof value === 'number' ? integer(value) : value;
  return emitYaml(toYaml(document));
}
export function namespaceTree(entries) {
  return Object.values(entries).sort((a, b) => a.name.localeCompare(b.name)).map(entry => ({
    name: entry.name, depth: entry.name.replace(/\/$/, '').split('/').length - 1,
    active: entry.active, pending: entry.pending, head: entry.head
  }));
}
