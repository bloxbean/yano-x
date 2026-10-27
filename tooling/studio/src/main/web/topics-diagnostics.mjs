/** Advisory topic diagnostics over one root-pinned snapshot; never a runtime validity decision. */
import {applicableNames, resolvePolicy} from './topics-model.mjs';
import {parseJson} from './lossless-json.mjs';
import {parseYaml} from './studio-yaml.mjs';
import {findInstance} from './binding-catalog.mjs';
import {blake2b256, decodeWire, hex, unhex, isValidTopic} from './assets/topics/topics.mjs';
const utf8 = new TextEncoder();
const fail = message => { throw new Error(message); };
const clone = value => structuredClone(value);
const integer = value => {
  if (!['bigint', 'number'].includes(typeof value) || typeof value === 'number' && !Number.isSafeInteger(value)
      || value < 0 || BigInt(value) > 9223372036854775807n) fail('Invalid registry integer');
  return BigInt(value);
};
const compare = (a, b) => a < b ? -1 : a > b ? 1 : 0;
const warning = (code, subject, phase, topic, detail) => ({code, subject, phase, topic, detail});
const yamlValue = node => node.kind === 'map' ? Object.fromEntries(node.entries.map(e => [e.key.text, yamlValue(e.value)]))
  : node.kind === 'seq' ? node.items.map(yamlValue) : node.value;

/** Reads the composite portion without rewriting any authoring content. */
export function readComposite(source) {
  const value = source.trim().startsWith('{') ? parseJson(source) : yamlValue(parseYaml(source).root);
  const composite = value.composite ?? value;
  compositeNames(composite);
  return composite;
}
export function compositeNames(composite) {
  if (!Array.isArray(composite?.components) || !composite.components.length || composite.components.length > 64) {
    fail('Diagnostics require 1..64 composite components');
  }
  if (composite.bindings !== undefined && (!Array.isArray(composite.bindings) || composite.bindings.length > 256)) {
    fail('Diagnostics require at most 256 bindings');
  }
  const names = new Set(), ids = new Set();
  for (const component of composite.components) {
    if (typeof component.id !== 'string' || !/^[a-z][a-z0-9-]{0,62}$/.test(component.id) || ids.has(component.id)) {
      fail('Components require unique identifiers');
    }
    ids.add(component.id);
    const topic = component.topic ?? `${component.id}.command.v1`;
    if (!isValidTopic(topic)) fail('Invalid component topic');
    applicableNames(topic).forEach(name => names.add(name));
    if (component.payloadSchemas !== undefined && (!Array.isArray(component.payloadSchemas)
        || component.payloadSchemas.length > 16 || component.payloadSchemas.some(h => !/^[0-9a-f]{64}$/.test(h)))) {
      fail('Invalid component payloadSchemas');
    }
  }
  return [...names].sort(compare);
}
export function topicStateKey(kind, name, revision) {
  if (!['entry', 'queued', 'history', 'freed'].includes(kind)) fail('Unsupported topic state key');
  const suffix = revision === undefined ? '' : integer(revision).toString(16).padStart(16, '0');
  return hex(utf8.encode(`~yano/topics/v1/${kind}/`)) + hex(blake2b256(utf8.encode(name))) + suffix;
}
function queuedContent(value) {
  const [owners, p, retired] = value;
  const keySet = k => ({threshold: k[0], keys: k[1].map(hex)});
  return {owners: keySet(owners), retired, policy: {
    writers: p[0] === null ? null : {keys: p[0] === 0 ? null : keySet(p[0])},
    delegate: p[1]?.map(hex) ?? null, maxDelegationMs: p[2], schema: p[3] === null ? null : hex(p[3]),
    maxPayloadBytes: p[4]
  }};
}
/** All network reads are explicit, bounded and checked against the inspected committed root. */
export async function readPending(snapshot, request, {maxSlots = 512, maxSlotsPerEntry = 256} = {}) {
  if (!Number.isSafeInteger(maxSlots) || maxSlots < 1 || maxSlots > 4096
      || !Number.isSafeInteger(maxSlotsPerEntry) || maxSlotsPerEntry < 1 || maxSlotsPerEntry > 256) {
    fail('Invalid pending inspection bound');
  }
  unhex(snapshot.stateRoot, 32);
  const height = integer(snapshot.height), pending = [], checks = [];
  let slots = 0;
  for (const entry of Object.values(snapshot.entries).sort((a, b) => compare(a.name, b.name))) {
    if (integer(entry.pending) === 0n) continue;
    const first = entry.active ? integer(entry.active.revision) + 1n : 1n, head = integer(entry.head);
    const count = head - first + 1n;
    if (count < 1n || count > BigInt(maxSlotsPerEntry) || count > BigInt(maxSlots - slots)) {
      checks.push(warning('TOPIC_PENDING_INSPECTION_LIMIT', entry.name, 'pending', '',
        'Pending history exceeds the inspection bound. Inspect with the CLI before activation.'));
      continue;
    }
    slots += Number(count);
    for (let revision = first; revision <= head; revision++) {
      const result = await request(`/state/entry/${topicStateKey('queued', entry.name, revision)}?height=${height}`);
      if (result.stateRoot !== snapshot.stateRoot) fail('Pending revision is not from the inspected root');
      if (!['PRESENT', 'ABSENT', 'TOMBSTONED'].includes(result.presence)) fail('Invalid pending revision presence');
      if (result.presence !== 'PRESENT') continue;
      const bytes = unhex(result.valueHex);
      if (bytes.length > 4096) fail('Pending revision exceeds its byte bound');
      const wire = decodeWire('queued-revision', bytes);
      if (integer(wire[0]) !== revision || integer(wire[1]) <= height) fail('Pending revision identity differs');
      pending.push({name: entry.name, revision: wire[0], activation: wire[1], content: queuedContent(wire[4])});
    }
  }
  return {pending, checks};
}
function descriptor(component, catalog, snapshot) {
  if (!catalog || catalog.context.chainId !== snapshot.chainId) return null;
  const config = Object.create(null);
  for (const [name, value] of Object.entries(component.config ?? {})) {
    if (value && typeof value === 'object' && typeof value.bytesHex === 'string') {
      config[name] = {type: 'bytes', hex: value.bytesHex.toLowerCase()};
    } else if (typeof value === 'bigint' || typeof value === 'number') config[name] = {type: 'integer', value};
    else if (typeof value === 'boolean') config[name] = {type: 'boolean', value};
    else if (typeof value === 'string') config[name] = {type: 'text', value};
    else return null;
  }
  const instance = findInstance(catalog, component.machine, config);
  return instance?.status === 'available' ? instance : null;
}
export function diagnoseComposite(composite, snapshot, {pending = [], checks: initial = [], catalog = null} = {}) {
  const names = compositeNames(composite);
  if (!snapshot?.entries || Object.keys(snapshot.entries).length > 16384 || pending.length > 4096) {
    fail('Invalid or oversized topic snapshot');
  }
  const checks = [...initial], projected = clone(snapshot.entries), height = integer(snapshot.height);
  const known = new Set(snapshot.inspectedNames ?? Object.keys(snapshot.entries));
  if (names.some(name => !known.has(name))) checks.push(warning('TOPIC_SNAPSHOT_INCOMPLETE', '', 'active', '',
    'Some component namespaces were not inspected. Refresh from the peer before relying on these diagnostics.'));
  const counts = new Map();
  const revisions = new Set();
  for (const item of pending) {
    const entry = projected[item.name], key = `${item.name}:${integer(item.revision)}`;
    if (!entry || integer(item.activation) <= height || integer(item.revision) > integer(entry.head)
        || integer(item.revision) <= integer(entry.active?.revision ?? 0) || revisions.has(key)) {
      fail('Invalid pending revision projection');
    }
    revisions.add(key); counts.set(item.name, (counts.get(item.name) ?? 0n) + 1n);
  }
  for (const entry of Object.values(projected)) {
    if (integer(entry.pending) !== (counts.get(entry.name) ?? 0n)) checks.push(warning('TOPIC_PENDING_INCOMPLETE',
      entry.name, 'pending', '', 'Pending revisions are not fully inspected. Fetch them from the same root.'));
  }
  function evaluate(phase) {
    const policies = new Map();
    for (const component of composite.components) {
      const topic = component.topic ?? `${component.id}.command.v1`;
      const outcome = resolvePolicy(topic, {...snapshot, entries: projected});
      policies.set(component.id, {topic, outcome});
      const add = (code, detail) => checks.push(warning(code, component.id, phase, topic, detail));
      if (outcome.kind === 'UNREGISTERED_REJECT') add('TOPIC_UNREGISTERED', 'Register a covering entry before accepting writes.');
      if (!outcome.policy) continue;
      const p = outcome.policy;
      if (p.retired) add('TOPIC_RETIRED', 'This component topic is retired.');
      if (p.schema) {
        if (!(component.payloadSchemas ?? []).includes(p.schema)) add('RULE_PAYLOAD_SCHEMA_MISMATCH',
          `Effective schema ${p.schema} is not declared in payloadSchemas.`);
        const instance = descriptor(component, catalog, snapshot);
        if (instance && (!instance.commands.length || instance.commands.some(c => c.layout !== 'RAW_BYTES'))) {
          add('TOPIC_SCHEMA_INCOMPATIBLE', 'This catalog instance has typed commands and cannot use a topic schema.');
        } else if (!instance && component.machine !== 'ordered-log') add('TOPIC_SCHEMA_COMPATIBILITY_CHECK',
          'Import the matching plugin catalog to check typed-command compatibility.');
      }
    }
    for (const binding of composite.bindings ?? []) {
      const target = policies.get(binding.to?.component);
      if (target?.outcome.policy?.writers.keys && binding.crossesTopicPolicy !== true) {
        checks.push(warning('RULE_TOPIC_POLICY_CROSSING', binding.id ?? '', phase, target.topic,
          'The derived target is protected; this binding has no crossesTopicPolicy permission.'));
      }
    }
  }
  evaluate('active');
  const ordered = [...pending].sort((a, b) => compare(integer(a.activation), integer(b.activation))
    || compare(a.name, b.name) || compare(integer(a.revision), integer(b.revision)));
  for (let i = 0; i < ordered.length; i++) {
    const item = ordered[i];
    projected[item.name] = {...projected[item.name], active: {revision: item.revision, activeFrom: item.activation,
      ownersSince: item.revision, content: clone(item.content)}};
    if (i + 1 === ordered.length || integer(ordered[i + 1].activation) !== integer(item.activation)) {
      evaluate(`pending@${item.activation}`);
    }
  }
  return {status: checks.length ? 'TOPICS_WARNINGS' : 'TOPICS_OK', advisory: true, trust: 'peer-response',
    projection: 'Queued proposals under current configuration; authority changes or superseding revisions can cancel them.',
    height: snapshot.height, stateRoot: snapshot.stateRoot, checks};
}
/** Text-only convenience for compact displays; the structured checks retain phase and code. */
export function topicWarnings(composite, snapshot) {
  return diagnoseComposite(composite, snapshot).checks.map(c => `${c.subject}: ${c.detail}`);
}
