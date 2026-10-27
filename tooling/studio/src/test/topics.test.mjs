import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';
import {compileTopicPlan, readTopics, topicsYaml, namespaceTree}
  from '../../build/studio/topics-model.mjs';
import {topicWarnings} from '../../build/studio/topics-diagnostics.mjs';
import {compileCddl} from '../../build/studio/topic-cddl.mjs';
import {parseJson, stringifyJson} from '../main/web/lossless-json.mjs';
import {hex, unhex, decodeWire, encodeWire} from '../../../../sdk/javascript/topics.mjs';

const fixtureUrl = new URL('../../../devtools/src/test/resources/topics/plan-v1.json', import.meta.url);
const fixtures = parseJson(fs.readFileSync(fixtureUrl, 'utf8'));
for (const fixture of fixtures) {
  test(`Topics Studio compiles byte-identical Java plan: ${fixture.name}`, async () => {
    const actual = await compileTopicPlan(fixture.document, fixture.snapshot);
    assert.deepEqual(parseJson(stringifyJson(actual)), fixture.plan);
    assert.deepEqual(readTopics(topicsYaml(fixture.document)), fixture.document);
  });
}
test('Topic authoring refuses unknown fields and stale or disabled snapshots', async () => {
  const {document, snapshot} = fixtures[0];
  await assert.rejects(compileTopicPlan({...document, privateKey: 'secret'}, snapshot), /Unknown field/);
  await assert.rejects(compileTopicPlan(document, {...snapshot, config: null}), /disabled/);
  await assert.rejects(compileTopicPlan(document, {...snapshot, chainId: 'wrong'}), /chainId/);
  const update = fixtures.find(f => f.name.includes('update'));
  const entries = structuredClone(update.snapshot.entries);
  entries[update.document.commands[0].target].pending = 1n;
  await assert.rejects(compileTopicPlan(update.document, {...update.snapshot, entries}), /PENDING/);
});
test('Namespace tree and uncovered-topic diagnostics are deterministic', () => {
  const snapshot = fixtures[0].snapshot;
  assert.equal(namespaceTree(snapshot.entries)[0].name, 'games/');
  assert.ok(topicWarnings({components: [{id: 'missing', topic: 'unknown/write'}]}, snapshot)
    .some(message => /Register a covering entry/.test(message)));
});

test('Guided form numbers emit as lossless YAML integers', () => {
  const draft = {schemaVersion: 1, kind: 'Topics', chainId: 'demo', commands: []};
  assert.equal(readTopics(topicsYaml(draft)).schemaVersion, 1n);
  assert.throws(() => topicsYaml({...draft, schemaVersion: Number.MAX_SAFE_INTEGER + 1}), /integer/);
});
test('CDDL compiler handles choices, full integer range, controls and repeated arrays', () => {
  assert.equal(hex(compileCddl('root = uint')), '82018400006130743138343436373434303733373039353531363135');
  assert.deepEqual(compileCddl('root = tstr / uint'), compileCddl('root = uint / tstr'));
  for (const bad of ['root = root', 'root = uint .le -1', 'root = { a: uint, a: tstr }',
    'root = uint / uint', 'root = uint .regexp "x"', 'root = [2*1 uint]', 'root = 01', 'root = "\\uD800"']) {
    assert.throws(() => compileCddl(bad), undefined, bad);
  }
});

test('CDDL controls never mutate a cached named type', () => {
  assert.deepEqual(compileCddl('t = uint root = [t .le 5, t]'),
    compileCddl('root = [uint .le 5, uint]'));
  assert.deepEqual(compileCddl('t = uint root = {a: t .le 5, b: t .ge 10}'),
    compileCddl('root = {a: uint .le 5, b: uint .ge 10}'));
});

test('CDDL map-key byte limits, native range intersection and whitespace match Java', () => {
  assert.doesNotThrow(() => compileCddl(`root = {"${'é'.repeat(128)}": uint}`));
  assert.throws(() => compileCddl(`root = {"${'é'.repeat(129)}": uint}`), /map key/);
  assert.throws(() => compileCddl(`root = {"${'a'.repeat(257)}": uint}`), /map key/);
  assert.equal(hex(compileCddl('root = 0..18446744073709551616')),
    '82018400026130743138343436373434303733373039353531363135');
  assert.equal(hex(compileCddl('root = -18446744073709551617..0')),
    '8201840002752d31383434363734343037333730393535313631366130');
  for (const separator of ['\u00a0', '\u2007', '\u202f', '\ufeff']) {
    assert.throws(() => compileCddl(`root${separator}= uint`), /unsupported syntax/);
  }
  assert.deepEqual(compileCddl('root\u001c= uint'), compileCddl('root = uint'));
});

test('CDDL expansion across shallow named rules is bounded while 128 aliases remain valid', () => {
  const rules = Array.from({length: 128}, (_, i) => [i ? `r${i}` : 'root', i === 127 ? 'uint' : `r${i + 1}`]);
  const nested = rules.map(([name, next]) => `${name} = ${'['.repeat(60)}${next}${']'.repeat(60)}`).join('\n');
  assert.throws(() => compileCddl(nested), /CDDL: expansion nesting limit/);
  const aliases = rules.map(([name, next]) => `${name} = ${next}`).join('\n');
  assert.deepEqual(compileCddl(aliases), compileCddl('root = uint'));
});

test('Governance recovery can cancel pending revisions but cannot silently ignore expiry', async () => {
  const {snapshot} = fixtures[0];
  const entries = structuredClone(snapshot.entries);
  entries['games/'].pending = 1n;
  const document = {schemaVersion: 1, kind: 'Topics', chainId: snapshot.chainId, commands: [{
    target: 'games/', operation: 'recover', authority: 'governance', nonce: '55'.repeat(16),
    owners: entries['games/'].active.content.owners
  }]};
  assert.match((await compileTopicPlan(document, {...snapshot, entries})).commands[0].warning, /Pending/);
  document.commands[0].notAfter = 123n;
  await assert.rejects(compileTopicPlan(document, snapshot), /notAfter/);
});

test('Revocation binds the credential signature to the exact scope, chain and generation for every authority', async () => {
  for (const name of ['owner-revoke', 'issuer-revoke']) {
    const original = fixtures.find(f => f.name === name);
    for (const change of ['signature', 'scope', 'chain', 'generation']) {
      const {document, snapshot} = structuredClone(original), command = document.commands[0];
      if (change === 'signature') {
        const delegation = decodeWire('delegation', unhex(command.delegationHex));
        delegation[6][0] ^= 1;
        command.delegationHex = hex(encodeWire('delegation', delegation));
      } else if (change === 'scope') command.target = 'games/other/';
      else if (change === 'chain') document.chainId = snapshot.chainId = 'another-chain';
      else snapshot.generationId = '55'.repeat(32);
      await assert.rejects(compileTopicPlan(document, snapshot), /Invalid delegation/, `${name}: ${change}`);
    }
  }
});

test('Revocation authorizes an active issuer or an owner, including a retired owner, without policy previews', async () => {
  const issuer = structuredClone(fixtures.find(f => f.name === 'issuer-revoke'));
  issuer.snapshot.entries['games/'].active.content.policy.delegate = [];
  await assert.rejects(compileTopicPlan(issuer.document, issuer.snapshot), /not a delegator/);
  issuer.document.commands[0].authority = 'games/';
  issuer.snapshot.entries['games/'].active.content.retired = true;
  const plan = await compileTopicPlan(issuer.document, issuer.snapshot), step = plan.commands[0];
  assert.deepEqual(step.requiredSigners, issuer.snapshot.entries['games/'].active.content.owners);
  assert.equal(decodeWire('command-statement', unhex(step.statementHex))[5], null);
  assert.equal(Object.hasOwn(step, 'earliestActivation'), false);
  assert.equal(Object.hasOwn(step, 'afterActivation'), false);
  const pending = structuredClone(issuer.snapshot.entries['games/']);
  pending.name = issuer.document.commands[0].target; pending.head = 2n; pending.pending = 1n;
  issuer.snapshot.entries[pending.name] = pending;
  await assert.rejects(compileTopicPlan(issuer.document, issuer.snapshot), /PENDING/);
});

test('Revocation rejects malformed credentials and operation fields instead of silently ignoring them', async () => {
  const original = fixtures.find(f => f.name === 'owner-revoke');
  for (const change of ['missing', 'malformed', 'oversized', 'owners', 'policy', 'governance', 'unrelated']) {
    const {document, snapshot} = structuredClone(original), command = document.commands[0];
    if (change === 'missing') delete command.delegationHex;
    else if (change === 'malformed') command.delegationHex = '00';
    else if (change === 'oversized') command.delegationHex = '00'.repeat(4097);
    else if (change === 'owners') command.owners = snapshot.entries['games/'].active.content.owners;
    else if (change === 'policy') command.policy = {};
    else command.authority = change === 'governance' ? 'governance' : 'other/';
    await assert.rejects(compileTopicPlan(document, snapshot), undefined, change);
  }
  const update = structuredClone(fixtures.find(f => f.name === 'owner-update-schema'));
  update.document.commands[0].authority = '~issuer';
  await assert.rejects(compileTopicPlan(update.document, update.snapshot), /only revoke/);
});

test('Asynchronous revocation verification snapshots the document and authority before yielding', async () => {
  const fixture = fixtures.find(f => f.name === 'issuer-revoke');
  const {document, snapshot} = structuredClone(fixture);
  const pending = compileTopicPlan(document, snapshot);
  document.commands[0].nonce = '99'.repeat(16);
  document.commands[0].delegationHex = '00';
  snapshot.generationId = '88'.repeat(32);
  snapshot.entries['games/'].active.content.policy.delegate = [];
  assert.deepEqual(parseJson(stringifyJson(await pending)), fixture.plan);
});
