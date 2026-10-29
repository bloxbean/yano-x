import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';
import {checkCommand, nextSteps, readTopics, topicsYaml} from '../../build/studio/topics-model.mjs';
import {parseJson, stringifyJson} from '../main/web/lossless-json.mjs';

// Every document the CLI plans in its own fixtures is one Studio reads, writes back and hands over unchanged.
const fixtureUrl = new URL('../../../devtools/src/test/resources/topics/plan-v1.json', import.meta.url);
const fixtures = parseJson(fs.readFileSync(fixtureUrl, 'utf8'));
for (const fixture of fixtures) {
  test(`Studio reads and writes the CLI's document unchanged: ${fixture.name}`, () => {
    assert.deepEqual(readTopics(topicsYaml(fixture.document)), fixture.document);
  });
}

const owner = 'aa'.repeat(32);
const base = {target: 'games/', operation: 'create', authority: 'governance', nonce: '44'.repeat(16),
  owners: {threshold: 1n, keys: [owner]}, policy: {writers: 'open', schema: 'none'}};

test('A command uses exactly the fields its operation and authority need', () => {
  assert.equal(checkCommand(structuredClone(base)).target, 'games/');
  assert.throws(() => checkCommand({...base, privateKey: 'secret'}), /Unknown command field: privateKey/);
  assert.throws(() => checkCommand({...base, target: 'games/rides/'}), /top-level namespace/);
  assert.throws(() => checkCommand({...base, notAfter: 5n}), /no expiry/);
  assert.throws(() => checkCommand({...base, authority: 'games/', operation: 'update', owners: undefined}),
    /expiry/);
  assert.throws(() => checkCommand({...base, operation: 'recover', authority: 'games/', notAfter: 5n}),
    /Recovery is a member vote/);
  assert.throws(() => checkCommand({...base, operation: 'retire'}), /retire does not change owners/);
  assert.throws(() => checkCommand({...base, owners: {threshold: 2n, keys: [owner]}}), /more signatures/);
  assert.throws(() => checkCommand({...base, owners: {threshold: 1n, keys: ['AB']}}), /64 lowercase hex/);
  assert.throws(() => checkCommand({...base, policy: {schema: 'none', schemaCddl: 'root = uint'}}),
    /schema or schemaCddl/);
  assert.throws(() => checkCommand({...base, nonce: 'short'}), /nonce/);
});

test('A document is checked as a whole and keeps lossless integers', () => {
  const document = {schemaVersion: 1n, kind: 'Topics', chainId: 'arcade', commands: [base]};
  const text = topicsYaml(document);
  assert.deepEqual(readTopics(text), parseJson(stringifyJson(document)));
  assert.throws(() => readTopics(topicsYaml({...document, commands: []})), /1 to 64 commands/);
  assert.throws(() => readTopics(topicsYaml({...document, commands: [base, base]})), /its own target/);
  assert.throws(() => readTopics(topicsYaml({...document, kind: 'Blueprint'})), /kind: Topics/);
  assert.throws(() => topicsYaml({...document, schemaVersion: Number.MAX_SAFE_INTEGER + 1}), /whole number/);
});

test('The handoff names the CLI steps the document needs', () => {
  const vote = {commands: [{authority: 'governance'}]};
  assert.deepEqual(nextSteps(vote, 'games.yaml'), ['yano.sh appchain topics plan games.yaml',
    '# every operator, from their node directory:', 'yano.sh appchain topics approve games.plan.json']);
  const owners = nextSteps({commands: [{authority: 'games/'}]});
  assert.deepEqual(owners, ['yano.sh appchain topics plan topics.yaml', '# each signer, with their own key file:',
    'yano.sh appchain topics sign topics.plan.json --key-file <seed file>',
    'yano.sh appchain topics submit topics.signed.json']);
  assert.equal(nextSteps({commands: [{authority: 'games/'}, {authority: 'governance'}]}).length, 6);
});
