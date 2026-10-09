import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import {fileURLToPath} from 'node:url';
import {emitDocument,importDocument} from '../main/web/binding-draft.mjs';
import {addAttachment,componentReferences,moveAttachment,removeRule,renameBinding,renameComponent,renameRule,
  ruleReferences,setAttachmentParameter,setRuleParameter,setRuleRead} from '../main/web/binding-edit.mjs';
import {checkDraft} from '../main/web/binding-check.mjs';
import {importAuthoringCatalog} from '../main/web/binding-catalog.mjs';
import {explainMessage} from '../main/web/binding-explain.mjs';
import {importReport} from '../main/web/binding-report.mjs';

// ADR-031.3 admission rules in Studio: the draft represents them exactly, edits keep references consistent, the
// advisory checks mirror the compiler's rule diagnostics, and reports explain rule refusals and their rollback.
const here = path.dirname(fileURLToPath(import.meta.url));
const repo = path.join(here, '../../../..');
const catalog = importAuthoringCatalog(fs.readFileSync(path.join(here, '../main/web/binding-authoring-catalog.json')));
const example = name => fs.readFileSync(path.join(repo, 'examples/bindings', name), 'utf8');
const load = name => {
  const imported = importDocument(example(name));
  assert.equal(imported.state, 'editable', imported.error?.message);
  return imported.draft;
};
const codes = draft => checkDraft(draft, catalog).map(value => value.code);
// Configured components have no exact descriptor in the shipped --all catalog, which is an info note only.
const advisories = draft => checkDraft(draft, catalog).filter(value => value.severity === 'advisory');

test('rules, attachments and scoped sources import and emit without loss', () => {
  const draft = load('procurement-admission.yaml');
  assert.deepEqual(draft.rules.map(rule => rule.id), ['registered-supplier', 'minimum-quorum', 'only-via-binding']);
  assert.deepEqual(draft.components[1].admission, [{rule: 'registered-supplier', params: null}]);
  assert.deepEqual(draft.components[2].admission[0].params, [{name: 'minimum', value: {type: 'integer', value: 2n}}]);
  assert.deepEqual(draft.rules[0].require[0].key, {kind: 'context', name: 'sender'});
  assert.deepEqual(draft.rules[1].params, [{name: 'minimum', type: 'integer', default: {type: 'integer', value: 2n}}]);
  const again = importDocument(emitDocument(draft));
  assert.equal(again.state, 'editable');
  assert.deepEqual(again.draft, draft);
  // A binding field comparison is not a rule clause.
  const refused = importDocument(example('procurement-admission.yaml')
    .replace("- expr: 'command.required >= params.minimum'", '- {field: required, eq: 2}'));
  assert.equal(refused.state, 'read-only');
});

test('renames keep attachments, binding parameters and rule lookups consistent', () => {
  const draft = load('procurement-admission.yaml');
  const renamed = renameRule(draft, 0, 'supplier-only');
  assert.equal(renamed.components[1].admission[0].rule, 'supplier-only');
  assert.deepEqual(ruleReferences(renamed, 'supplier-only').map(reference => reference.componentId), ['orders']);
  const binding = renameBinding(draft, draft.bindings.findIndex(value => value.id === 'approved-to-audit'), 'audit-it');
  assert.deepEqual(binding.components[3].admission[0].params, [{name: 'binding', value: {type: 'text', value: 'audit-it'}}]);
  assert.ok(componentReferences(draft, 'suppliers').some(reference => reference.role === 'rule-lookup'));
  assert.equal(renameComponent(draft, 0, 'vendors').rules[0].require[0].component, 'vendors');
  assert.deepEqual(advisories(binding), []);
});

test('attachment and parameter edits keep authored order and never add defaults', () => {
  const draft = load('procurement-admission.yaml');
  const stated = setAttachmentParameter(draft, 2, 0, 'minimum', {type: 'integer', value: 3n});
  assert.deepEqual(stated.components[2].admission[0].params, [{name: 'minimum', value: {type: 'integer', value: 3n}}]);
  const omitted = setAttachmentParameter(draft, 2, 0, 'minimum', null);
  assert.deepEqual(omitted.components[2].admission[0].params, []);
  assert.deepEqual(advisories(omitted), [], 'an omitted value uses the declared default');
  const twice = addAttachment(draft, 1, {rule: 'minimum-quorum', params: null});
  assert.deepEqual(moveAttachment(twice, 1, 1, -1).components[1].admission.map(entry => entry.rule),
    ['minimum-quorum', 'registered-supplier']);
  const declared = setRuleParameter(draft, 0, 'region', {type: 'text', default: null});
  assert.deepEqual(declared.rules[0].params, [{name: 'region', type: 'text', default: null}]);
  assert.ok(codes(declared).includes('RULE_PARAMETER_MISSING'));
});

test('advisory checks mirror the compiler for rules', () => {
  assert.deepEqual(advisories(load('procurement-admission.yaml')), []);
  assert.deepEqual(advisories(load('balances-transfer-limit.yaml')), []);
  const draft = load('procurement-admission.yaml');
  assert.ok(codes(removeRule(draft, 0)).includes('RULE_UNKNOWN'));
  assert.ok(codes(addAttachment(draft, 1, {rule: 'registered-supplier', params: null})).includes('RULE_DUPLICATE'));
  const detached = structuredClone(draft);
  detached.components[3].admission = null;
  assert.ok(codes(detached).includes('RULE_UNATTACHED'));
  const wrongTarget = setAttachmentParameter(draft, 3, 0, 'binding', {type: 'text', value: 'order-to-approval'});
  assert.ok(codes(wrongTarget).includes('RULE_PARAMETER_TYPE'));
  const unknown = setAttachmentParameter(draft, 3, 0, 'extra', {type: 'integer', value: 1n});
  assert.ok(codes(unknown).includes('RULE_PARAMETER_UNKNOWN'));
  const fact = structuredClone(draft);
  fact.rules[0].require.push({kind: 'expr', text: '"operator" in facts.roles'});
  assert.ok(codes(fact).includes('RULE_FACT_UNKNOWN'), 'kv-registry declares no facts');
  const command = structuredClone(draft);
  command.rules[1].command = 'transfer';
  assert.ok(codes(command).includes('RULE_COMMAND_UNKNOWN'));
  const deny = structuredClone(draft);
  deny.rules[0].deny = 'ADMISSION_RULE_DENIED';
  assert.ok(codes(deny).includes('RULE_DENY_CODE'));
  const scoped = structuredClone(draft);
  scoped.bindings[0].to.mapping.assignments[0].source = {kind: 'param', name: 'minimum'};
  assert.ok(codes(scoped).includes('RULE_SCOPE_INVALID'), 'bindings cannot read rule scopes');
});

test('reports explain a rule refusal, its clause and the rollback of a derived refusal', () => {
  const scenarios = path.join(here, 'fixtures/scenarios');
  const explain = file => {
    const imported = importReport(fs.readFileSync(path.join(scenarios, file)));
    return explainMessage(imported.result.rehearsal.messages[0], {receiptCodes: imported.receiptCodes});
  };
  const unregistered = explain('procurement-admission-report-1.json');
  assert.match(unregistered.headline, /Refused by admission rule registered-supplier .*NOT_A_REGISTERED_SUPPLIER/);
  assert.match(unregistered.steps[0].rules.label, /refused by rule registered-supplier at clause 1/i);
  const derived = explain('quorum-report-2.json');
  assert.match(derived.headline, /minimum-quorum/);
  assert.ok(derived.notes.some(note => /depth 1.*source command/s.test(note)));
  assert.equal(derived.steps.at(-1).rules.failure.denyCode, 'QUORUM_TOO_LOW');
  const accepted = explain('procurement-admission-report-3.json');
  assert.equal(accepted.outcome, 'committed');
  assert.ok(accepted.steps.every(step => step.rules === null || step.rules.failure === null));
});

// ADR-031.4: rule reads and write-view quantifiers.
test('rule reads import, emit, edit and rename without loss', () => {
  const draft = load('asset-governed-limits.yaml');
  const tier = draft.rules.find(rule => rule.id === 'tier-limit');
  assert.deepEqual(tier.reads, [{name: 'holder', component: 'registry', namespace: 'holders',
    key: {kind: 'context', name: 'sender'}}]);
  assert.equal(draft.rules.find(rule => rule.id === 'governed-transfer-limit').reads[0].key.kind, 'literal');
  const again = importDocument(emitDocument(draft));
  assert.equal(again.state, 'editable');
  assert.deepEqual(again.draft, draft);
  const index = draft.rules.indexOf(tier);
  const added = setRuleRead(draft, index, 'limits', {component: 'registry', namespace: 'settings',
    key: {kind: 'literal', value: {type: 'text', value: 'transfer'}}});
  assert.deepEqual(added.rules[index].reads.map(read => read.name), ['holder', 'limits']);
  assert.equal(setRuleRead(setRuleRead(added, index, 'limits', null), index, 'holder', null).rules[index].reads, null);
  assert.ok(componentReferences(draft, 'registry').some(reference => reference.role === 'rule-read'));
  assert.equal(renameComponent(draft, draft.components.findIndex(value => value.id === 'registry'), 'ledger')
    .rules[index].reads[0].component, 'ledger');
  // Quantified clauses are ordinary expressions to the draft.
  const passport = load('dpp-namespace-isolation.yaml');
  assert.match(passport.rules[0].require[0].text, /^writes\.all\(w, /);
  assert.deepEqual(importDocument(emitDocument(passport)).draft, passport);
});

test('read checks mirror the compiler: declared components, namespaces, names and at most four reads', () => {
  const draft = load('asset-governed-limits.yaml');
  const index = draft.rules.findIndex(rule => rule.id === 'tier-limit');
  const unknown = setRuleRead(draft, index, 'holder', {component: 'nowhere', namespace: 'holders',
    key: {kind: 'context', name: 'sender'}});
  assert.ok(codes(unknown).includes('RULE_READ_UNKNOWN_COMPONENT'));
  let many = draft;
  for (const name of ['a', 'b', 'c', 'd']) {
    many = setRuleRead(many, index, name, {component: 'registry', namespace: 'holders',
      key: {kind: 'literal', value: {type: 'text', value: name}}});
  }
  assert.ok(codes(many).includes('EXPECTED_OBJECT'));
  const reserved = setRuleRead(draft, index, 'in', {component: 'registry', namespace: 'holders',
    key: {kind: 'context', name: 'sender'}});
  assert.ok(codes(reserved).includes('DOCUMENT_STRUCTURE_INVALID'));
  // With the catalog: a kernel without views has nothing to read, a namespace must be declared, and a read key
  // may read only declared facts.
  const logged = importDocument(`composite:
  components:
    - {id: log, machine: ordered-log}
    - id: points
      machine: balances
      admission:
        - rule: reads-log
  rules:
    - id: reads-log
      command: transfer
      deny: NOT_READY
      reads:
        entry: {component: log, key: {literal: k}}
        other: {component: points, namespace: nope, key: {literal: k}}
        keyed: {component: points, key: {fact: nope}}
      require:
        - expr: 'reads.entry.present'
  bindings: []
`);
  assert.equal(logged.state, 'editable', logged.error?.message);
  const found = checkDraft(logged.draft, catalog).map(value => `${value.code} ${value.segments.join('.')}`);
  assert.ok(found.includes('RULE_READ_UNKNOWN_NAMESPACE composite.rules.0.reads.entry'), found.join('\n'));
  assert.ok(found.includes('RULE_READ_UNKNOWN_NAMESPACE composite.rules.0.reads.other.namespace'), found.join('\n'));
  assert.ok(found.includes('RULE_FACT_UNKNOWN composite.rules.0.reads.keyed.key'), found.join('\n'));
});

test('a governed map exports value views, a write view and coverage that the checks accept', () => {
  const feedCatalog = importAuthoringCatalog(fs.readFileSync(path.join(here,
    'fixtures/scenarios/catalog-feed-slot-rules.json')));
  const map = feedCatalog.instances.find(instance => instance.machineId === 'authenticated-map-component');
  assert.equal(map.status, 'available');
  assert.deepEqual(map.ruleValueViews.map(view => view.namespace), ['feeds', 'observations']);
  assert.deepEqual(map.ruleValueViews[0].valueFields.map(field => field.name), ['max', 'min', 'status']);
  assert.deepEqual(map.ruleWriteFields.map(field => field.name), ['collection', 'key', 'keyText', 'op', 'hasValue',
    'valueLength', 'expectedRevision']);
  assert.deepEqual(map.ruleWriteCoverageFields.map(field => field.name), ['coverage', 'actorId',
    'actorOrganizationId', 'actorRoles']);
  const draft = load('feed-slot-rules.yaml');
  const readIssues = checkDraft(draft, feedCatalog).filter(value => value.code.startsWith('RULE_READ'));
  assert.deepEqual(readIssues, []);
  const index = draft.rules.findIndex(rule => rule.id === 'feed-open-and-in-range');
  const wrong = setRuleRead(draft, index, 'feed', {component: 'registry', namespace: 'rounds',
    key: {kind: 'literal', value: {type: 'text', value: 'main'}}});
  assert.ok(checkDraft(wrong, feedCatalog).some(value => value.code === 'RULE_READ_UNKNOWN_NAMESPACE'));
});

test('the catalog carries value views and write views for rule pickers', () => {
  const map = catalog.instances.find(instance => instance.machineId === 'authenticated-map-component'
    && instance.status === 'available');
  const balances = catalog.instances.find(instance => instance.machineId === 'balances'
    && instance.status === 'available');
  assert.deepEqual(balances.ruleValueViews, [{namespace: '', fields: [{name: 'balance', type: 'integer'}],
    valueFields: []}]);
  assert.equal(balances.ruleWriteFields, undefined);
  if (map) {
    assert.deepEqual(map.ruleWriteFields.map(field => field.name), ['collection', 'key', 'keyText', 'op', 'hasValue',
      'valueLength', 'expectedRevision']);
    assert.deepEqual(map.ruleWriteCoverageFields.map(field => field.name), ['coverage', 'actorId',
      'actorOrganizationId', 'actorRoles']);
  }
  const shipped = JSON.parse(fs.readFileSync(path.join(here, '../main/web/binding-authoring-catalog.json'), 'utf8'));
  const bad = shipped.instances.find(instance => instance.machineId === 'balances' && instance.status === 'available');
  bad.ruleValueViews = [{namespace: 'Bad', fields: [], valueFields: []}];
  assert.throws(() => importAuthoringCatalog(new TextEncoder().encode(JSON.stringify(shipped))), /namespace/);
});

