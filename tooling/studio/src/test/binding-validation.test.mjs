import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import {fileURLToPath} from 'node:url';
import {importAuthoringCatalog} from '../main/web/binding-catalog.mjs';
import {importDocument} from '../main/web/binding-draft.mjs';
import {importReport} from '../main/web/binding-report.mjs';
import {sha256Hex} from '../main/web/sha256.mjs';
import {assessReports,assuranceState,failureStage,handoffCommands,handoffText,HANDOFF_NAMES} from '../main/web/binding-validation.mjs';
import {BindingSession} from '../main/web/binding-session.mjs';
import {parseJson,stringifyJson} from '../main/web/lossless-json.mjs';

const here=path.dirname(fileURLToPath(import.meta.url));
const scenario=name=>fs.readFileSync(path.join(here,'fixtures/scenarios',name));
const web=name=>fs.readFileSync(path.join(here,'../main/web',name));
const fixture=name=>fs.readFileSync(path.join(here,'fixtures',name));
const example=(starter,block)=>fs.readFileSync(path.join(here,'../../../../examples/bindings/fixtures',starter,`fixture-${block}.json`));

test('handoff commands use fixed names, export the catalog first and chain consecutive blocks',()=>{
  assert.deepEqual(handoffCommands(0),[
    './yano.sh appchain bindings catalog bindings.yaml --plugins-directory <plugins-directory> --context context.json > catalog.json',
    './yano.sh appchain bindings validate bindings.yaml --plugins-directory <plugins-directory> --context context.json --report validate-report.json']);
  const three=handoffCommands(3);
  assert.equal(three.length,5);
  assert.match(three[2],/--fixture fixture-1\.json --report dry-run-report-1\.json > result-1\.json$/);
  assert.doesNotMatch(three[2],/--prior-result/);
  assert.match(three[4],/--fixture fixture-3\.json --prior-result result-2\.json --report dry-run-report-3\.json > result-3\.json$/);
  assert.throws(()=>handoffCommands(17),RangeError);
  assert.throws(()=>handoffCommands(1.5),RangeError);
  const text=handoffText(2);
  assert.match(text,/Reports are unauthenticated files/);
  assert.match(text,/Import catalog\.json into Studio first/);
  assert.match(text,/Nothing here submits to a node/);
  assert.equal(HANDOFF_NAMES.handoff,'bindings-handoff.txt');
});

test('re-importing a fixture name with different content makes rehearsals of the earlier content stale',()=>{
  const session=new BindingSession({catalog:importAuthoringCatalog(web('binding-authoring-catalog.json')),catalogOrigin:'bundled'});
  session.loadText(scenario('approval.yaml'),{name:'bindings.yaml',origin:'file'});
  for(const block of [1,2,3,4]) session.importReportFile(scenario(`approval-report-${block}.json`),`dry-run-report-${block}.json`);
  const importFixture=(bytes,name)=>session.importFixtureFile(new Uint8Array(bytes),name);
  for(const block of [1,2,3,4]) assert.equal(importFixture(example('approval-to-audit',block),`fixture-${block}.json`),false);
  const assess=()=>assessReports({reports:session.reports,fixtureDigests:session.fixtureDigests(),
    replacedFixtures:session.replacedFixtures(),catalog:session.catalog,documentSha256:session.exportedDocumentSha256(),
    draft:session.draft});
  assert.equal(assuranceState(assess()).state,'rehearsed');
  // The same name with a changed timestamp replaces the active content; it is never kept alongside it.
  const changed=Buffer.from(example('approval-to-audit',1).toString('utf8').replace('"timestamp" : 1000','"timestamp" : 1001'));
  assert.equal(importFixture(changed,'fixture-1.json'),true);
  assert.equal(session.fixtures.size,4);
  assert.equal(session.fixtureDigests().size,4);
  const replaced=assess();
  const blocks=replaced.sequences[0].blocks;
  assert.deepEqual(blocks.map(block=>[block.match.state,block.match.rehearsal]),
    [['matching','stale'],['matching','unverified'],['matching','unverified'],['matching','unverified']]);
  assert.ok(blocks[0].match.reasons.includes('its fixture fixture-1.json was replaced by a file with different content'));
  assert.equal(replaced.entries.find(entry=>entry.name==='dry-run-report-1.json').match.rehearsal,'stale');
  assert.equal(assuranceState(replaced).state,'matching-report');
  assert.match(assuranceState(replaced).label,/ 4 rehearsal blocks are stale or unverified\.$/);
  // Importing the original content again restores the verified chain; forgetting fixtures forgets the history too.
  assert.equal(importFixture(example('approval-to-audit',1),'fixture-1.json'),true);
  assert.equal(assuranceState(assess()).state,'rehearsed');
  assert.doesNotMatch(assuranceState(assess()).label,/stale or unverified/);
  // A later block replaced: earlier blocks stay verified, and the status names the others instead of hiding them.
  const third=Buffer.from(example('approval-to-audit',3).toString('utf8').replace('"timestamp" : 3000','"timestamp" : 3001'));
  assert.notEqual(third.toString('utf8'),example('approval-to-audit',3).toString('utf8'));
  importFixture(third,'fixture-3.json');
  const later=assess();
  assert.deepEqual(later.sequences[0].blocks.map(block=>block.match.rehearsal),['matching','matching','stale','unverified']);
  assert.equal(assuranceState(later).state,'rehearsed');
  assert.match(assuranceState(later).label,/ 2 rehearsal blocks are stale or unverified\.$/);
  importFixture(example('approval-to-audit',3),'fixture-3.json');
  // The same content under two names that were both replaced is reported under both names.
  importFixture(example('approval-to-audit',2),'copy-of-2.json');
  importFixture(changed,'copy-of-2.json');
  importFixture(changed,'fixture-2.json');
  assert.ok(assess().sequences[0].blocks[1].match.reasons.includes(
    'its fixture, imported as fixture-2.json and copy-of-2.json, was replaced by files with different content'));
  importFixture(example('approval-to-audit',2),'fixture-2.json');
  session.clearFixtures();
  assert.equal(session.replacedFixtures().size,0);
  assert.ok(assess().sequences[0].blocks.every(block=>block.match.rehearsal==='unverified'));
});

test('a replacement report restores its continuation without deleting history, in every import order',()=>{
  const catalog=importAuthoringCatalog(web('binding-authoring-catalog.json'));
  const documentText=scenario('approval.yaml');
  const original=[1,2,3,4].map(block=>({name:`old-${block}`,report:importReport(scenario(`approval-report-${block}.json`))}));
  const session=new BindingSession({catalog});
  for(const block of [1,2,3,4]) session.importFixtureFile(example('approval-to-audit',block),`fixture-${block}.json`);
  const changed=Buffer.concat([example('approval-to-audit',2),Buffer.from('\n')]);
  session.importFixtureFile(changed,'fixture-2.json');
  const replacement=parseJson(scenario('approval-report-2.json').toString('utf8'));
  replacement.inputs.find(input=>input.role==='fixture').sha256=sha256Hex(changed);
  replacement.inputs.find(input=>input.role==='fixture').bytes=String(changed.length);
  const updated={name:'new-2',report:importReport(stringifyJson(replacement))};
  const permutations=values=>values.length===0?[[]]:values.flatMap((value,index)=>
    permutations(values.filter((_,i)=>i!==index)).map(rest=>[value,...rest]));
  for(const reports of permutations([...original,updated])) {
    const assessment=assessReports({reports,fixtureDigests:session.fixtureDigests(),
      replacedFixtures:session.replacedFixtures(),catalog,documentSha256:sha256Hex(documentText),
      draft:importDocument(documentText.toString('utf8')).draft});
    const blocks=new Map(assessment.sequences[0].blocks.map(block=>[block.name,block]));
    assert.equal(blocks.size,5,'history stays visible, once per report');
    assert.equal(blocks.get('old-2').match.rehearsal,'stale');
    for(const name of ['old-1','new-2','old-3','old-4']) {
      assert.equal(blocks.get(name).match.rehearsal,'matching',`${name}: ${reports.map(entry=>entry.name)}`);
    }
    assert.equal(blocks.get('new-2').chain,'linked');
    assert.equal(assessment.sequences[0].complete,true);
  }
});

test('matching state bytes never bypass missing, stale or differently identified predecessor inputs',()=>{
  const catalog=importAuthoringCatalog(web('binding-authoring-catalog.json'));
  const documentText=scenario('approval.yaml');
  const original=[1,2,3,4].map(block=>({name:`block-${block}`,report:importReport(scenario(`approval-report-${block}.json`))}));
  const fixtureDigests=new Set([1,2,3,4].map(block=>sha256Hex(example('approval-to-audit',block))));
  const assess=reports=>assessReports({reports,fixtureDigests,catalog,documentSha256:sha256Hex(documentText),
    draft:importDocument(documentText.toString('utf8')).draft}).sequences.flatMap(sequence=>sequence.blocks);
  const variants=[
    value=>{value.inputs.find(input=>input.role==='fixture').sha256='a'.repeat(64);},
    value=>{value.producer.jarSha256='b'.repeat(64);},
    value=>{value.result.rehearsal.executionIdentity='c'.repeat(64);},
    value=>{value.result.rehearsal.postStateSha256='d'.repeat(64);},
    value=>{value.result.rehearsal.priorPostStateSha256='e'.repeat(64);}
  ];
  for(const change of variants) {
    const value=parseJson(scenario('approval-report-2.json').toString('utf8'));
    change(value);
    const alternative={name:'alternative-2',report:importReport(stringifyJson(value))};
    // Without a verified height-2 predecessor, height 3 and 4 cannot be verified.
    const missing=assess([original[0],alternative,...original.slice(2)]);
    for(const name of ['block-3','block-4']) assert.notEqual(missing.find(block=>block.name===name).match.rehearsal,'matching');
    // An unrelated/stale branch must not poison a complete verified path, in either import order.
    for(const reports of [[alternative,...original],[...original,alternative]]) {
      const restored=assess(reports);
      for(const name of ['block-1','block-2','block-3','block-4']) {
        assert.equal(restored.find(block=>block.name===name).match.rehearsal,'matching');
      }
    }
  }
});

test('a matching four-block rehearsal is assessed as rehearsed, block by block',()=>{
  const catalog=importAuthoringCatalog(web('binding-authoring-catalog.json'));
  const documentText=scenario('approval.yaml');
  const reports=[3,1,4,2].map(block=>({name:`dry-run-report-${block}.json`,report:importReport(scenario(`approval-report-${block}.json`))}));
  const fixtureDigests=new Set([1,2,3,4].map(block=>sha256Hex(new Uint8Array(example('approval-to-audit',block)))));
  const draft=importDocument(documentText.toString('utf8')).draft;
  const assessment=assessReports({reports,fixtureDigests,catalog,documentSha256:sha256Hex(new Uint8Array(documentText)),draft});
  assert.equal(assessment.sequences[0].complete,true);
  assert.deepEqual(assessment.sequences[0].blocks.map(block=>[block.name,block.match.state,block.match.rehearsal]),
    [1,2,3,4].map(block=>[`dry-run-report-${block}.json`,'matching','matching']));
  assert.equal(assessment.sequences[0].blocks[2].empty,true);
  assert.equal(assessment.sequences[0].blocks[3].messages[0].outcome,'committed');
  assert.equal(assuranceState(assessment).state,'rehearsed');
  // Without the fixture files the rehearsal inputs are unverified, but validation still matches.
  const unverified=assessReports({reports,fixtureDigests:new Set(),catalog,documentSha256:sha256Hex(new Uint8Array(documentText)),draft});
  assert.ok(unverified.sequences[0].blocks.every(block=>block.match.rehearsal==='unverified'));
  assert.equal(assuranceState(unverified).state,'matching-report');
  // An edited draft makes every report stale, and explanations stop naming draft bindings.
  const stale=assessReports({reports,fixtureDigests,catalog,documentSha256:'0'.repeat(64),draft});
  assert.ok(stale.sequences[0].blocks.every(block=>block.match.state==='stale' && block.match.rehearsal==='stale'));
  assert.deepEqual(stale.sequences[0].blocks[1].messages[0].notVisited,[]);
  assert.equal(assuranceState(stale).state,'stale-report');
  assert.equal(assuranceState(assessReports({reports:[],fixtureDigests,catalog,documentSha256:null,draft})).state,'draft');
  // A report for another program forms its own sequence after the matching one.
  const mixed=assessReports({reports:[{name:'other.json',report:importReport(scenario('rollback-report.json'))},...reports],
    fixtureDigests,catalog,documentSha256:sha256Hex(new Uint8Array(documentText)),draft});
  assert.deepEqual(mixed.sequences.map(sequence=>sequence.blocks.length),[4,1]);
  assert.equal(mixed.sequences[1].blocks[0].match.state,'stale');
});

test('failed validation, failed dry-runs and partial chains never yield a stronger assurance (M2 review)',()=>{
  const catalog=importAuthoringCatalog(web('binding-authoring-catalog.json'));
  const fixtures=path.join(here,'fixtures');
  const invalid=fs.readFileSync(path.join(fixtures,'tutorial-bindings.yaml'),'utf8').replace('{field: valueHash}','{fn: hex, args: [{field: key}]}');
  const failedText=fs.readFileSync(path.join(fixtures,'report-validate-failed.json'),'utf8');
  const failed=assessReports({reports:[{name:'validate-report.json',report:importReport(failedText)}],fixtureDigests:new Set(),
    catalog,documentSha256:sha256Hex(new TextEncoder().encode(invalid)),draft:importDocument(invalid).draft});
  assert.equal(failed.validation.match.state,'matching');
  assert.equal(assuranceState(failed).state,'rejected-report');
  // A dry-run that failed before executing still shows its diagnostics.
  const failedDryRun=failedText.replace('"operation" : "validate"','"operation" : "dry-run"');
  assert.notEqual(failedDryRun,failedText);
  const dryRun=assessReports({reports:[{name:'dry-run-report-1.json',report:importReport(failedDryRun)}],fixtureDigests:new Set(),
    catalog,documentSha256:sha256Hex(new TextEncoder().encode(invalid)),draft:null});
  assert.equal(dryRun.validation?.report.operation,'dry-run');
  assert.equal(dryRun.validation.report.diagnostics.length,1);
  // Blocks 3 and 4 without block 1-2: block 4 links to block 3, but block 3's own predecessor is missing.
  const documentText=scenario('approval.yaml');
  const partial=assessReports({reports:[3,4].map(block=>({name:`r${block}`,report:importReport(scenario(`approval-report-${block}.json`))})),
    fixtureDigests:new Set([3,4].map(block=>sha256Hex(new Uint8Array(example('approval-to-audit',block))))),catalog,
    documentSha256:sha256Hex(new Uint8Array(documentText)),draft:importDocument(documentText.toString('utf8')).draft});
  assert.equal(partial.sequences[0].complete,false);
  assert.deepEqual(partial.sequences[0].blocks.map(block=>[block.chain,block.match.rehearsal]),
    [['continues-unknown','unverified'],['linked','unverified']]);
  assert.equal(assuranceState(partial).state,'matching-report');
});

test('the status comes from every matching report, whatever the import order (M2 review)',()=>{
  const catalog=importAuthoringCatalog(web('binding-authoring-catalog.json'));
  const documentText=scenario('cascade.yaml');
  const base={catalog,documentSha256:sha256Hex(new Uint8Array(documentText)),draft:importDocument(documentText.toString('utf8')).draft};
  const completed={name:'dry-run-report-1.json',report:importReport(scenario('cascade-report.json'))};
  const failed={name:'dry-run-report-2.json',report:importReport(scenario('fixture-failure-report.json'))};
  assert.equal(failureStage(failed.report),'rehearsal');
  for(const reports of [[completed,failed],[failed,completed]]) {
    const unverified=assuranceState(assessReports({...base,reports,fixtureDigests:new Set()}));
    assert.equal(unverified.state,'matching-report','a fixture failure never blames the document');
    assert.match(unverified.label,/1 matching dry-run report failed on the fixture or continuation/);
    const rehearsed=assuranceState(assessReports({...base,reports,
      fixtureDigests:new Set([sha256Hex(new Uint8Array(example('registry-to-audit',1)))])}));
    assert.equal(rehearsed.state,'rehearsed');
    assert.match(rehearsed.label,/failed on the fixture or continuation/);
  }
  const assessment=assessReports({...base,reports:[completed,failed],fixtureDigests:new Set()});
  assert.deepEqual(assessment.checks.map(entry=>[entry.name,entry.stage]),[['dry-run-report-2.json','rehearsal']]);
  // A blueprint composite is never described as validated.
  const blueprint=assuranceState(assessment,{origin:'blueprint'});
  assert.doesNotMatch(blueprint.label,/validated/);
  assert.match(blueprint.label,/project rendering derives its own context/);
});

test('a report whose receipt-code table differs from the catalog is not trusted (M2 review)',()=>{
  const catalog=importAuthoringCatalog(web('binding-authoring-catalog.json'));
  const text=scenario('cascade-report.json').toString('utf8');
  const forged=parseJson(text);
  forged.receiptCodes.find(code=>code.code==='EXPRESSION_TYPE_ERROR').locatesLastCondition='always';
  const documentText=scenario('cascade.yaml');
  const assessment=assessReports({reports:[{name:'forged.json',report:importReport(stringifyJson(forged))}],
    fixtureDigests:new Set(),catalog,documentSha256:sha256Hex(new Uint8Array(documentText)),draft:null});
  assert.equal(assessment.entries[0].match.state,'unverifiable');
  assert.match(assessment.entries[0].match.reasons.join(),/receipt-code table differs/);
});

test('reports contradicting their own heights or duplicates are rejected, and duplicates are not imported twice (M2 review)',()=>{
  const text=fixture('report-dry-run-replay.json');
  const mutate=change=>{ const value=parseJson(text.toString('utf8')); change(value); return stringifyJson(value); };
  const messages=value=>value.result.rehearsal.messages;
  assert.throws(()=>importReport(mutate(value=>{value.result.rehearsal.assumptions.height='7';})),{code:'REPORT_CONTRADICTION'});
  assert.throws(()=>importReport(mutate(value=>{messages(value)[0].disposition='executed';})),{code:'REPORT_CONTRADICTION'});
  assert.throws(()=>importReport(mutate(value=>{messages(value)[2].receiptHex=messages(value)[0].receiptHex;
    messages(value)[2].receipt=messages(value)[0].receipt;})),{code:'REPORT_CONTRADICTION'});
  const session=new BindingSession();
  session.importReportFile(new Uint8Array(text),'a.json');
  assert.throws(()=>session.importReportFile(new Uint8Array(text),'b.json'),{code:'DUPLICATE_REPORT'});
  session.clearReports();
  assert.equal(session.reports.length,0);
});
