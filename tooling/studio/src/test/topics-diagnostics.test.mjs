import assert from 'node:assert/strict';
import test from 'node:test';
import fs from 'node:fs';
import {diagnoseComposite, readComposite, readPending, topicStateKey}
  from '../../build/studio/topics-diagnostics.mjs';
import {parseJson, stringifyJson} from '../main/web/lossless-json.mjs';
import {configurationKey} from '../main/web/binding-catalog.mjs';
import {encodeWire, hex} from '../../../../sdk/javascript/topics.mjs';
const fixtures = parseJson(fs.readFileSync(new URL('../../../devtools/src/test/resources/topics/plan-v1.json', import.meta.url), 'utf8'));
const hash = '77'.repeat(32), key = '11'.repeat(32);
const composite = {components: [{id: 'rides', machine: 'ordered-log', topic: 'games/rides'}],
  bindings: [{id: 'copy', to: {component: 'rides'}}]};
function snapshot() {
  const value = structuredClone(fixtures[0].snapshot);
  const entry = value.entries['games/'];
  entry.active.content.policy = {writers: {keys: null}, delegate: [], schema: '', maxPayloadBytes: null, maxDelegationMs: null};
  entry.active.content.retired = false; entry.pending = 0n;
  value.inspectedNames = ['games/', 'games/rides'];
  return value;
}
function change(state, {schema = hash, protectedTopic = true, retired = false} = {}) {
  const entry = state.entries['games/']; entry.head = BigInt(entry.active.revision) + 1n; entry.pending = 1n;
  const content = structuredClone(entry.active.content);
  content.policy.schema = schema; content.retired = retired;
  if (protectedTopic) content.policy.writers = {keys: {threshold: 1, keys: [key]}};
  return {name: 'games/', revision: entry.head, activation: BigInt(state.height) + 2n, content};
}
function queued(item) {
  const c = item.content, p = c.policy;
  const keys = k => [k.threshold, k.keys.map(v => Uint8Array.from(Buffer.from(v, 'hex')))];
  const wire = [item.revision, item.activation, 'games/', 1, [keys(c.owners),
    [p.writers === null ? null : p.writers.keys ? keys(p.writers.keys) : 0, p.delegate?.map(v => Uint8Array.from(Buffer.from(v, 'hex'))) ?? null,
      p.maxDelegationMs, p.schema === null ? null : Uint8Array.from(Buffer.from(p.schema, 'hex')), p.maxPayloadBytes], c.retired], new Uint8Array(32)];
  return hex(encodeWire('queued-revision', wire));
}
test('diagnostics read bare and wrapped composites without changing schema declarations', () => {
  const source = `composite: {components: [{id: rides, machine: ordered-log, topic: games/rides, payloadSchemas: ["${hash}"]}], bindings: []}`;
  assert.deepEqual(readComposite(source).components[0].payloadSchemas, [hash]);
  assert.throws(() => readComposite('{"components":[],"bindings":[]}'), /1..64/);
});
test('active and pending policies diagnose inherited schemas and protected crossings at activation', () => {
  const state = snapshot(), pending = change(state);
  assert.ok(diagnoseComposite(composite, state).checks.some(c => c.code === 'TOPIC_PENDING_INCOMPLETE'));
  const result = diagnoseComposite(composite, state, {pending: [pending]});
  assert.ok(result.checks.every(c => c.phase === `pending@${pending.activation}`));
  assert.deepEqual(result.checks.map(c => c.code), ['RULE_PAYLOAD_SCHEMA_MISMATCH', 'RULE_TOPIC_POLICY_CROSSING']);
  const allowed = structuredClone(composite); allowed.components[0].payloadSchemas = [hash];
  allowed.bindings[0].crossesTopicPolicy = true;
  assert.equal(diagnoseComposite(allowed, state, {pending: [pending]}).status, 'TOPICS_OK');
  assert.equal(state.entries['games/'].active.content.policy.schema, '', 'projection does not mutate the snapshot');
});
test('retired and unregistered components warn, even with writer crossing permission', () => {
  const state = snapshot(); state.entries['games/'].active.content.retired = true;
  const document = structuredClone(composite); document.components.push({id: 'lost', machine: 'ordered-log'});
  document.bindings[0].crossesTopicPolicy = true;
  const codes = diagnoseComposite(document, state).checks.map(c => c.code);
  assert.ok(codes.includes('TOPIC_RETIRED')); assert.ok(codes.includes('TOPIC_UNREGISTERED'));
  assert.ok(codes.includes('TOPIC_SNAPSHOT_INCOMPLETE'));
});
test('same-height revisions apply as a group before inherited policy diagnostics', () => {
  const state = snapshot(), parent = change(state);
  const child = structuredClone(state.entries['games/']); child.name = 'games/rides'; child.pending = 1n;
  child.active.content.policy.schema = ''; state.entries[child.name] = child;
  const childChange = {name: child.name, revision: child.head, activation: parent.activation,
    content: structuredClone(child.active.content)};
  const checks = diagnoseComposite(composite, state, {pending: [childChange, parent]}).checks;
  assert.equal(checks.filter(c => c.code === 'RULE_PAYLOAD_SCHEMA_MISMATCH').length, 0);
});
test('matching catalog descriptors identify typed schemas; opaque plugins get a compatibility advisory', () => {
  const state = snapshot(); state.entries['games/'].active.content.policy.schema = hash;
  const document = structuredClone(composite); document.components[0].machine = 'balances';
  const instance = {status: 'available', commands: [{layout: 'ARRAY_WITH_OPCODE'}]};
  const catalog = {context: {chainId: state.chainId}, instancesByKey: new Map([[configurationKey('balances', {}), instance]])};
  assert.ok(diagnoseComposite(document, state, {catalog}).checks.some(c => c.code === 'TOPIC_SCHEMA_INCOMPATIBLE'));
  assert.ok(diagnoseComposite(document, state).checks.some(c => c.code === 'TOPIC_SCHEMA_COMPATIBILITY_CHECK'));
});
test('queued reads are pinned to the snapshot height and checked against its root', async () => {
  const state = snapshot(), pending = change(state), paths = [];
  const result = await readPending(state, async path => {
    paths.push(path); return {stateRoot: state.stateRoot, presence: 'PRESENT', valueHex: queued(pending)};
  });
  assert.deepEqual(parseJson(stringifyJson(result.pending)), parseJson(stringifyJson([pending])));
  assert.equal(paths[0], `/state/entry/${topicStateKey('queued', 'games/', pending.revision)}?height=${state.height}`);
  await assert.rejects(readPending(state, async () => ({stateRoot: '00'.repeat(32), presence: 'PRESENT'})), /inspected root/);
  const bad = {...pending, revision: pending.revision + 1n};
  await assert.rejects(readPending(state, async () => ({stateRoot: state.stateRoot, presence: 'PRESENT', valueHex: queued(bad)})), /identity/);
});
test('pending history scans have per-entry and total bounds, and missing records are explicit', async () => {
  const state = snapshot(); change(state); state.entries['games/'].head += 300n;
  let reads = 0;
  const result = await readPending(state, async () => { reads++; });
  assert.equal(reads, 0); assert.equal(result.checks[0].code, 'TOPIC_PENDING_INSPECTION_LIMIT');
  const small = snapshot(); change(small);
  const absent = await readPending(small, async () => ({stateRoot: small.stateRoot, presence: 'ABSENT'}));
  assert.ok(diagnoseComposite(composite, small, absent).checks.some(c => c.code === 'TOPIC_PENDING_INCOMPLETE'));
  await assert.rejects(readPending(small, async () => {}, {maxSlots: 0}), /bound/);
});
