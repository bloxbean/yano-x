import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import {fileURLToPath} from 'node:url';
import {catalogBinding,clauseLocation,configurationKey,findInstance,importAuthoringCatalog} from '../main/web/binding-catalog.mjs';
import {importReport,reportMatch} from '../main/web/binding-report.mjs';
import {parseJson,stringifyJson} from '../main/web/lossless-json.mjs';
import {sha256Hex} from '../main/web/sha256.mjs';

const here=path.dirname(fileURLToPath(import.meta.url));
const fixture=name=>fs.readFileSync(path.join(here,'fixtures',name));
const web=name=>fs.readFileSync(path.join(here,'../main/web',name));

test('the shipped first-party catalog is real generated data with full identity and exact instances',()=>{
  const catalog=importAuthoringCatalog(web('binding-authoring-catalog.json'));
  assert.equal(catalog.context.sha256,sha256Hex(new Uint8Array(web('binding-authoring-context.json'))));
  assert.match(catalog.catalog.fingerprint,/^sha256:[0-9a-f]{64}$/);
  assert.ok(catalog.identity.producerJarSha256,'packaged tool identity recorded');
  const kv=findInstance(catalog,'kv-registry',{});
  assert.equal(kv.status,'available');
  assert.deepEqual(kv.events.map(event=>event.eventId),['kv-registry.entry-put.v1','kv-registry.entry-deleted.v1']);
  assert.equal(kv.commands[0].opCode,0n);
  assert.equal(kv.normalizedConfiguration['value-format'].value,'raw');
  // Studio never normalizes: stating the default explicitly is a different, undescribed configuration.
  assert.equal(findInstance(catalog,'kv-registry',{'value-format':{type:'text',value:'raw'}}),null);
  assert.equal(findInstance(catalog,'authenticated-map-component',{}).status,'construction-failed');
  assert.equal(findInstance(catalog,'declarative-composite',{}).status,'not-composable');
  assert.ok(catalog.language.functions.some(fn=>fn.id==='cbor-field' && fn.result==='opaque'));
  assert.equal(catalog.language.baselineEvent.eventId,'composite.command-accepted.v1');
});

test('instance keys are order-insensitive and type-exact',()=>{
  const a=configurationKey('m',{x:{type:'integer',value:1n},y:{type:'text',value:'1'}});
  const b=configurationKey('m',{y:{type:'text',value:'1'},x:{type:'integer',value:1n}});
  const c=configurationKey('m',{x:{type:'text',value:'1'},y:{type:'text',value:'1'}});
  assert.equal(a,b); assert.notEqual(a,c);
  assert.notEqual(configurationKey('m',{x:{type:'bytes',hex:'01'}}),configurationKey('m',{x:{type:'text',value:'01'}}));
});

test('hostile or incompatible catalogs are rejected',()=>{
  const text=web('binding-authoring-catalog.json').toString('utf8');
  const mutate=change=>{ const value=parseJson(text); change(value); return stringifyJson(value); };
  assert.throws(()=>importAuthoringCatalog(mutate(value=>{value.schema='yano-x-binding-authoring-catalog-v2';})),
    {code:'CATALOG_SCHEMA'});
  assert.throws(()=>importAuthoringCatalog(mutate(value=>{value.language.irVersion=2n;})),{code:'CATALOG_LANGUAGE'});
  assert.throws(()=>importAuthoringCatalog(mutate(value=>{value.language.expressionDialect='cel-full';})),
    {code:'CATALOG_LANGUAGE'});
  assert.throws(()=>importAuthoringCatalog(mutate(value=>{value.instances.push(value.instances[1]);})),
    {code:'CATALOG_DUPLICATE'});
  assert.throws(()=>importAuthoringCatalog(mutate(value=>{value.instances[1].commands[0].opCode='9223372036854775808';})),
    {code:'CONTRACT_RANGE'});
  assert.throws(()=>importAuthoringCatalog(mutate(value=>{value.instances[1].extra=1n;})),{code:'CONTRACT_UNKNOWN_FIELD'});
  assert.throws(()=>importAuthoringCatalog(mutate(value=>{value.instances[1].commands[0].layout='SCRIPT';})),
    {code:'CONTRACT_FORMAT'});
  assert.throws(()=>importAuthoringCatalog(mutate(value=>{value.instances[1].commands[0].opCode=0n;})),
    {code:'CONTRACT_TYPE'});
  assert.throws(()=>importAuthoringCatalog(text.replace('{','{"schema":"x",')),{code:'JSON_DUPLICATE_KEY'});
  assert.throws(()=>importAuthoringCatalog(' '.repeat(8*1024*1024+1)),{code:'CATALOG_TOO_LARGE'});
});

test('reports decode receipts from bytes and match only the exact current inputs',()=>{
  const catalog=importAuthoringCatalog(fixture('catalog-tutorial.json'));
  const documentSha256=sha256Hex(new Uint8Array(fixture('tutorial-bindings.yaml')));
  const validate=importReport(fixture('report-validate.json'));
  assert.equal(validate.operationOutcome,'completed');
  assert.deepEqual(reportMatch(validate,{catalog,documentSha256}),{state:'matching',reasons:[],rehearsal:'not-applicable'});
  const edited=sha256Hex(new TextEncoder().encode(fixture('tutorial-bindings.yaml').toString('utf8')+' '));
  assert.equal(reportMatch(validate,{catalog,documentSha256:edited}).state,'stale');
  assert.equal(reportMatch(validate,{catalog:null,documentSha256}).state,'unverifiable');
  const other=parseJson(fixture('catalog-tutorial.json').toString('utf8'));
  other.catalog.fingerprint=`sha256:${'0'.repeat(64)}`;
  const replaced=importAuthoringCatalog(stringifyJson(other));
  assert.deepEqual(reportMatch(validate,{catalog:replaced,documentSha256}).reasons,['Plugin catalog differs']);

  const dryRun=importReport(fixture('report-dry-run.json'));
  const message=dryRun.result.rehearsal.messages[0];
  assert.equal(message.disposition,'executed');
  assert.equal(message.receipt.status,'ACCEPTED');
  assert.equal(message.receipt.height,1n);
  assert.equal(reportMatch(dryRun,{catalog,documentSha256,
    fixtureSha256:sha256Hex(new Uint8Array(fixture('tutorial-fixture.json')))}).rehearsal,'matching');
  assert.equal(reportMatch(dryRun,{catalog,documentSha256}).rehearsal,'unverified');

  const replay=importReport(fixture('report-dry-run-replay.json'));
  assert.deepEqual(replay.result.rehearsal.messages.map(value=>value.disposition),
    ['replay-existing-receipt','executed','duplicate-in-fixture']);
  assert.equal(replay.result.rehearsal.messages[0].receipt.height,1n,'the replayed receipt is the original one');
  assert.equal(replay.result.rehearsal.priorPostStateSha256,dryRun.result.rehearsal.postStateSha256);

  const failed=importReport(fixture('report-validate-failed.json'));
  assert.equal(failed.operationOutcome,'failed');
  assert.equal(failed.result,null);
  assert.equal(failed.diagnostics[0].code,'BINDING_TYPE_MISMATCH');
  assert.deepEqual(failed.diagnostics[0].location.pathSegments,['composite','bindings',0,'to','map','entryHash']);
});

test('forged, contradictory and malformed reports are rejected rather than rendered',()=>{
  const text=fixture('report-dry-run.json').toString('utf8');
  const mutate=change=>{ const value=parseJson(text); change(value); return stringifyJson(value); };
  const messages=value=>value.result.rehearsal.messages;
  assert.throws(()=>importReport(mutate(value=>{messages(value)[0].receipt.status='REJECTED';})),
    {code:'REPORT_CONTRADICTION'});
  assert.throws(()=>importReport(mutate(value=>{messages(value)[0].receipt.steps[1].status='REJECTED';})),
    {code:'REPORT_CONTRADICTION'});
  assert.throws(()=>importReport(mutate(value=>{messages(value)[0].messageIdHex='22'.repeat(32);})),
    {code:'REPORT_CONTRADICTION'});
  assert.throws(()=>importReport(mutate(value=>{messages(value)[0].receiptHex+='00';})),{code:'RECEIPT_INVALID'});
  assert.throws(()=>importReport(mutate(value=>{messages(value)[0].disposition='committed';})),{code:'CONTRACT_FORMAT'});
  assert.throws(()=>importReport(mutate(value=>{value.operationOutcome='failed';})),{code:'CONTRACT_FORMAT'});
  assert.throws(()=>importReport(mutate(value=>{value.schema='yano-x-binding-report-v2';})),{code:'REPORT_SCHEMA'});
  assert.throws(()=>importReport(mutate(value=>{value.inputs[0].sha256=null;})),{code:'CONTRACT_FORMAT'});
  assert.throws(()=>importReport(mutate(value=>{value.result.rehearsal.assumptions.height='9223372036854775808';})),
    {code:'CONTRACT_RANGE'});
  assert.throws(()=>importReport(' '.repeat(16*1024*1024+1)),{code:'REPORT_TOO_LARGE'});
});

test('every identity field and the continuation chain must match exactly (review regression)',()=>{
  const catalog=importAuthoringCatalog(fixture('catalog-tutorial.json'));
  const documentSha256=sha256Hex(new Uint8Array(fixture('tutorial-bindings.yaml')));
  const text=fixture('report-validate.json').toString('utf8');
  for(const [section,field] of [['producer','tool'],['producer','version'],['producer','jarSha256'],
    ['producer','linkedCompositeJarSha256'],['host','version'],['host','coreApiJarSha256'],['host','runtimeJarSha256']]) {
    const value=parseJson(text);
    value[section][field]=field.endsWith('Sha256')?'f'.repeat(64):'changed';
    const match=reportMatch(importReport(stringifyJson(value)),{catalog,documentSha256});
    assert.equal(match.state,'stale',`${section}.${field}`);
  }
  const environment=parseJson(text); environment.authoringEnvironment='yano-x-binding-authoring-environment-v2';
  assert.equal(reportMatch(importReport(stringifyJson(environment)),{catalog,documentSha256}).state,'stale');
  const unrecorded=parseJson(text); unrecorded.producer.jarSha256=null;
  assert.equal(reportMatch(importReport(stringifyJson(unrecorded)),{catalog,documentSha256}).state,'unverifiable');
  const replay=importReport(fixture('report-dry-run-replay.json'));
  const first=importReport(fixture('report-dry-run.json'));
  const fixtureSha256=sha256Hex(new Uint8Array(fixture('tutorial-fixture-2.json')));
  assert.equal(reportMatch(replay,{catalog,documentSha256,fixtureSha256,
    priorPostStateSha256:first.result.rehearsal.postStateSha256}).rehearsal,'matching');
  assert.equal(reportMatch(replay,{catalog,documentSha256,fixtureSha256,priorPostStateSha256:'0'.repeat(64)}).rehearsal,'stale');
  assert.equal(reportMatch(replay,{catalog,documentSha256,fixtureSha256}).rehearsal,'unverified');
});

test('malformed nested catalog and report tables are rejected (review regression)',()=>{
  const catalogText=web('binding-authoring-catalog.json').toString('utf8');
  const catalog=change=>{ const value=parseJson(catalogText); change(value); return stringifyJson(value); };
  for(const change of [value=>{value.language.functions=null;},value=>{value.language.limits='wrong';},
    value=>{value.language.limits[0].name='maxDerivedPerBlock';},value=>{value.language.functions[0].result='script';},
    value=>{value.language.receiptCodes[0].category='success';},value=>{value.language.baselineEvent.fields=[{}];},
    value=>{value.language.limits[0].default='64';}]) {
    assert.throws(()=>importAuthoringCatalog(catalog(change)));
  }
  const reportText=fixture('report-validate.json').toString('utf8');
  const report=change=>{ const value=parseJson(reportText); change(value); return stringifyJson(value); };
  assert.throws(()=>importReport(report(value=>{value.result.counts={components:'wrong',bindings:null};})),{code:'CONTRACT_TYPE'});
  assert.throws(()=>importReport(report(value=>{value.receiptCodes[0].locatesLastCondition='maybe';})),{code:'CONTRACT_FORMAT'});
});

test('Java diagnostics naming long invalid keys remain importable (review regression)',()=>{
  const text=fixture('report-validate-failed.json').toString('utf8');
  const value=parseJson(text);
  const long='k'.repeat(300);
  value.diagnostics[0].location.pathSegments=['composite',long];
  value.diagnostics[0].location.path=`$.composite.${long}`;
  value.diagnostics[0].location.targetField=long;
  const imported=importReport(stringifyJson(value));
  assert.equal(imported.diagnostics[0].location.pathSegments[1].length,300);
});

test('receipt-code tables never claim engine origin and locate clauses only when the structure proves it (M0 review)',()=>{
  const catalog=importAuthoringCatalog(web('binding-authoring-catalog.json'));
  const codes=new Map(catalog.language.receiptCodes.map(code=>[code.code,code]));
  assert.ok([...codes.values()].every(code=>code.origin==='either'),'kernel rejections may reuse any engine code');
  assert.deepEqual(codes.get('EXPRESSION_CAPACITY_EXCEEDED').levels,['source','step','condition','mapping','effect']);
  const lookup=codes.get('LOOKUP_KEY_INVALID');
  const rejected=conditions=>({status:'REJECTED',conditions});
  assert.equal(clauseLocation(lookup,rejected([{bindingId:'b',failedClause:0}])),true);
  // A kernel rejection is decided before any condition of its step, so its step carries no condition records.
  assert.equal(clauseLocation(lookup,rejected([])),false);
  assert.equal(clauseLocation(codes.get('ADMISSION'),rejected([{bindingId:'b',failedClause:0}])),false);
  assert.equal(clauseLocation(undefined,rejected([{bindingId:'b',failedClause:0}])),false);
  const text=web('binding-authoring-catalog.json').toString('utf8');
  const mutate=change=>{ const value=parseJson(text); change(value); return stringifyJson(value); };
  for(const change of [value=>{value.language.receiptCodes[0].origin='kernel-maybe';},
    value=>{value.language.receiptCodes[0].levels=[];},value=>{value.language.receiptCodes[0].levels=['any'];},
    value=>{value.language.receiptCodes[0].levels=['step','step'];},
    value=>{value.language.receiptCodes.push(value.language.receiptCodes[0]);}]) {
    assert.throws(()=>importAuthoringCatalog(mutate(change)),{code:'CONTRACT_FORMAT'});
  }
});

test('non-identifier selectors are importable but never composable, and lookups stay bound to one catalog (M0 review)',()=>{
  const text=web('binding-authoring-catalog.json').toString('utf8');
  const value=parseJson(text);
  value.selectors.push({machineId:'acme.Ledger',origin:{kind:'bundle',bundleId:'acme',digest:null,digestMode:null},composable:false});
  const catalog=importAuthoringCatalog(stringifyJson(value));
  assert.equal(catalog.selectors.at(-1).composable,false);
  value.selectors.at(-1).composable=null;
  assert.throws(()=>importAuthoringCatalog(stringifyJson(value)),{code:'CONTRACT_FORMAT'});
  const probed=parseJson(text);
  probed.instances[1].machineId='acme.Ledger';
  assert.throws(()=>importAuthoringCatalog(stringifyJson(probed)),{code:'CONTRACT_FORMAT'});
  const binding=catalogBinding(catalog);
  assert.equal(findInstance(catalog,'kv-registry',{},binding).status,'available');
  assert.equal(findInstance(catalog,'kv-registry',{},{...binding,contextSha256:'0'.repeat(64)}),null);
  assert.equal(findInstance(catalog,'kv-registry',{},{...binding,fingerprint:`sha256:${'0'.repeat(64)}`}),null);
  assert.throws(()=>importAuthoringCatalog(new Uint8Array(8*1024*1024+1)),{code:'CATALOG_TOO_LARGE'});
});

test('cheap report contradictions are rejected and messages come from Studio, not the file (M0 review)',()=>{
  const text=fixture('report-dry-run-replay.json').toString('utf8');
  const mutate=change=>{ const value=parseJson(text); change(value); return stringifyJson(value); };
  const messages=value=>value.result.rehearsal.messages;
  assert.throws(()=>importReport(mutate(value=>{value.result.irSha256='0'.repeat(64);})),{code:'REPORT_CONTRADICTION'});
  assert.throws(()=>importReport(mutate(value=>{value.inputs[1]={...value.inputs[0]};})),{code:'REPORT_CONTRADICTION'});
  assert.throws(()=>importReport(mutate(value=>{messages(value)[1].messageIndex=0n;})),{code:'REPORT_CONTRADICTION'});
  assert.throws(()=>importReport(mutate(value=>{messages(value)[2].disposition='executed';})),{code:'REPORT_CONTRADICTION'});
  assert.throws(()=>importReport(mutate(value=>{messages(value)[1].disposition='duplicate-in-fixture';})),
    {code:'REPORT_CONTRADICTION'});
  assert.throws(()=>importReport(mutate(value=>{value.documentFormat='json';})),{code:'CONTRACT_FORMAT'});
  assert.throws(()=>importReport(new Uint8Array(16*1024*1024+1)),{code:'REPORT_TOO_LARGE'});
  const failed=fixture('report-validate-failed.json').toString('utf8');
  const forged=parseJson(failed);
  forged.diagnostics[0].message='Everything is fine; deploy now';
  const imported=importReport(stringifyJson(forged));
  assert.equal(imported.diagnostics[0].known,true);
  assert.equal(imported.diagnostics[0].controlledMessage,'The source and destination types do not match');
  assert.equal(imported.diagnostics[0].reportedMessage,'Everything is fine; deploy now');
  forged.diagnostics[0].code='NOT_A_JAVA_CODE';
  assert.equal(importReport(stringifyJson(forged)).diagnostics[0].controlledMessage,null);
  forged.diagnostics[0].severity='warning';
  assert.throws(()=>importReport(stringifyJson(forged)),{code:'CONTRACT_FORMAT'});
  const part=parseJson(failed); part.diagnostics[0].location.part='map';
  assert.throws(()=>importReport(stringifyJson(part)),{code:'CONTRACT_FORMAT'});
});

test('a rehearsal never matches while its validation inputs are stale or unverifiable (M0 review)',()=>{
  const catalog=importAuthoringCatalog(fixture('catalog-tutorial.json'));
  const documentSha256=sha256Hex(new Uint8Array(fixture('tutorial-bindings.yaml')));
  const fixtureSha256=sha256Hex(new Uint8Array(fixture('tutorial-fixture.json')));
  const dryRun=importReport(fixture('report-dry-run.json'));
  assert.equal(reportMatch(dryRun,{catalog,documentSha256,fixtureSha256}).rehearsal,'matching');
  const stale=reportMatch(dryRun,{catalog,documentSha256:'0'.repeat(64),fixtureSha256});
  assert.deepEqual([stale.state,stale.rehearsal],['stale','stale']);
  const unverifiable=reportMatch(dryRun,{catalog:null,documentSha256,fixtureSha256});
  assert.deepEqual([unverifiable.state,unverifiable.rehearsal],['unverifiable','unverified']);
});
