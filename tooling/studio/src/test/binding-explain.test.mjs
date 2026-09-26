import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import {fileURLToPath} from 'node:url';
import {importDocument} from '../main/web/binding-draft.mjs';
import {explainMessage,rehearsalSequences,visibleText} from '../main/web/binding-explain.mjs';
import {importReport} from '../main/web/binding-report.mjs';
import {decodeBindingReceipt,hexToBytes} from '../main/web/binding-receipt.mjs';
import {parseJson,stringifyJson} from '../main/web/lossless-json.mjs';

const here=path.dirname(fileURLToPath(import.meta.url));
const scenario=name=>fs.readFileSync(path.join(here,'fixtures/scenarios',name));
const fixture=name=>fs.readFileSync(path.join(here,'fixtures',name));
const report=name=>importReport(scenario(name));
const draftOf=name=>importDocument(scenario(name).toString('utf8')).draft;
const explain=(imported,index,draft=null)=>explainMessage(imported.result.rehearsal.messages[index],
  {receiptCodes:imported.receiptCodes,effects:imported.result.rehearsal.effects,draft});

test('an accepted cascade is committed step by step and a false clause is a skip, not a failure',()=>{
  const cascade=report('cascade-report.json');
  const draft=draftOf('cascade.yaml');
  const ran=explain(cascade,0,draft);
  assert.equal(ran.outcome,'committed');
  assert.deepEqual(ran.steps.map(step=>[step.ordinal,step.depth,step.target,step.status]),
    [[0,0,'records','committed'],[1,1,'audit','committed']]);
  assert.deepEqual(ran.steps[0].conditions.map(value=>value.kind),['fired']);
  assert.equal(ran.code,null);
  const skipped=explain(cascade,1,draft);
  assert.equal(skipped.outcome,'committed');
  assert.equal(skipped.steps.length,1);
  assert.equal(skipped.steps[0].conditions[0].kind,'skipped');
  assert.equal(skipped.steps[0].conditions[0].label,'Condition 1 was false; the binding was skipped');
  assert.deepEqual(skipped.notVisited,[]);
});

test('a rejected cascade never shows a planned step as committed and names what it cannot locate',()=>{
  const rollback=report('rollback-report.json');
  for(const index of [0,1]) {
    const view=explain(rollback,index,draftOf('rollback.yaml'));
    assert.equal(view.outcome,'rolled-back');
    assert.match(view.headline,/nothing from this cascade committed, including the source command/);
    assert.deepEqual(view.steps.map(step=>step.status),['planned-not-committed','rejected']);
    assert.equal(view.steps[0].label,'Planned, not committed');
    assert.equal(view.code.code,'MAPPING_MISSING_FIELD');
    assert.equal(view.code.categoryLabel,'evaluation error');
    assert.ok(view.notes.includes('The receipt does not record which mapped field or lookup source failed.'));
    assert.equal(view.truncated,false);
  }
  // The second write to the same key also fails: the first write was rolled back, not committed.
  assert.equal(rollback.result.rehearsal.messages[1].receipt.status,'REJECTED');
  const budget=explain(report('budget-report.json'),0,draftOf('budget.yaml'));
  assert.equal(budget.code.code,'EXPRESSION_CAPACITY_EXCEEDED');
  assert.equal(budget.code.categoryLabel,'resource exhaustion');
  assert.deepEqual(budget.steps.map(step=>[step.ordinal,step.status]),[[0,'rejected']]);
});

test('multi-block rehearsals keep empty blocks, consecutive heights and the continuation chain',()=>{
  const reports=[4,2,3,1].map(block=>report(`approval-report-${block}.json`));
  const [sequence,...others]=rehearsalSequences(reports);
  assert.equal(others.length,0);
  assert.equal(sequence.complete,true);
  assert.deepEqual(sequence.blocks.map(block=>[block.height,block.chain,block.empty]),
    [[1n,'start',false],[2n,'linked',false],[3n,'linked',true],[4n,'linked',false]]);
  const approved=explainMessage(sequence.blocks[3].messages[0],{receiptCodes:reports[0].receiptCodes,
    draft:draftOf('approval.yaml')});
  assert.equal(approved.outcome,'committed');
  assert.deepEqual(approved.steps.map(step=>[step.bindingId,step.target]),[[null,'reviews'],['record-approved','audit']]);
  const firstVote=explainMessage(sequence.blocks[1].messages[0],{receiptCodes:reports[0].receiptCodes,
    draft:draftOf('approval.yaml')});
  assert.deepEqual(firstVote.notVisited,['record-approved'],'one vote does not approve the item');
  const text=stringifyJson(parseJson(scenario('approval-report-3.json').toString('utf8')));
  const broken=parseJson(text); broken.result.rehearsal.priorPostStateSha256='0'.repeat(64);
  assert.equal(rehearsalSequences([reports[3],reports[1],importReport(stringifyJson(broken)),reports[0]])[0].complete,false);
  assert.deepEqual(rehearsalSequences([reports[3],reports[0]])[0].blocks.map(block=>block.chain),['start','gap']);
  // Rehearsals of a different program are never chained into this sequence.
  const mixed=rehearsalSequences([...reports,report('rollback-report.json')]);
  assert.equal(mixed.length,2);
  assert.deepEqual(mixed.map(value=>value.blocks.length),[4,1]);
  assert.ok(mixed.every(value=>value.complete));
});

test('continuation candidates require both the preceding height and state digest',()=>{
  const reports=[1,2,3,4].map(block=>report(`approval-report-${block}.json`));
  const independent=structuredClone(reports[1]);
  independent.result.rehearsal.fromPriorResult=false;
  independent.result.rehearsal.priorPostStateSha256=null;
  const [history]=rehearsalSequences([reports[3],independent,...reports]);
  const start=history.blocks.find(block=>block.report===independent);
  assert.equal(start.chain,'start','a separate start never implicitly continues an adjacent report');
  assert.deepEqual(start.predecessors,[]);
  const next=history.blocks.find(block=>block.report===reports[2]);
  assert.equal(next.predecessors.length,2);
  assert.ok(next.predecessors.includes(independent));
  assert.ok(next.predecessors.includes(reports[1]));
  const wrongHeight=structuredClone(reports[1]);
  wrongHeight.result.rehearsal.assumptions.height=1n;
  const [missing]=rehearsalSequences([wrongHeight,reports[2],reports[3]]);
  assert.equal(missing.blocks[1].chain,'gap');
  assert.deepEqual(missing.blocks[1].predecessors,[],'equal state at the wrong height is not a predecessor');
  assert.equal(missing.complete,false);
});

test('replays and duplicates are never presented as this block’s execution',()=>{
  const replay=importReport(fixture('report-dry-run-replay.json'));
  const views=[0,1,2].map(index=>explain(replay,index));
  assert.deepEqual(views.map(view=>view.outcome),['replayed','committed','duplicate']);
  assert.ok(views[0].steps.every(step=>step.status==='earlier'),'replayed steps are not this block’s commits');
  assert.match(views[0].steps[0].label,/stored receipt of height 1, not executed again/);
  assert.match(views[0].headline,/not this block’s outcome/);
});

test('truncated history, kernel codes and hostile text are explained honestly',()=>{
  const oracle=parseJson(fs.readFileSync(process.env.STUDIO_RECEIPT_ORACLE,'utf8'),{maxCharacters:64*1024*1024,maxEntries:10_000_000});
  const codes=report('cascade-report.json').receiptCodes;
  const receipt=name=>decodeBindingReceipt(hexToBytes(oracle.cases.find(value=>value.name===name).hex));
  const message=value=>({messageIndex:0,messageIdHex:value.sourceMessageIdHex,topic:'t',disposition:'executed',receipt:value});
  const truncated=explainMessage(message(receipt('synthetic.truncated')),{receiptCodes:codes});
  assert.equal(truncated.truncated,true);
  assert.equal(truncated.code.code,'RECEIPT_CAPACITY_EXCEEDED');
  assert.match(truncated.notes.join(' '),/History truncated/);
  assert.equal(truncated.steps[1].conditions[0].kind,'failed-here');
  assert.equal(truncated.steps[1].conditions[0].label,'Failed with LOOKUP_KEY_INVALID while evaluating condition 2');
  const kernel=explainMessage(message(receipt('synthetic.rejected.child')),{receiptCodes:codes});
  assert.equal(kernel.code.known,false);
  assert.match(kernel.notes.join(' '),/target component’s own rejection/);
  assert.ok(kernel.steps.every(step=>step.conditions.every(condition=>condition.kind!=='failed-here')));
  assert.equal(visibleText('ok‮gnp.exe'),'ok\\u{202e}gnp.exe');
  assert.equal(visibleText('a\u0000b⁦c'),'a\\u{0000}b\\u{2066}c');
});

test('ambiguous records, replay conflicts and missing history are worded conservatively (M2 review)',()=>{
  const codes=report('cascade-report.json').receiptCodes;
  const id='ab'.repeat(32);
  const step=(ordinal,depth,bindingId,status,code,conditions,events=[])=>({ordinal,depth,bindingId,targetComponentId:'records',
    messageIdHex:id,eventsProduced:events,conditions,status,code,rawBody:false});
  const message=receipt=>({messageIndex:0,messageIdHex:id,topic:'t',disposition:'executed',receipt});
  const charged=explainMessage(message({version:1,sourceMessageIdHex:id,height:1n,status:'REJECTED',failedStepOrdinal:0,
    code:'EXPRESSION_CAPACITY_EXCEEDED',steps:[step(0,0,null,'REJECTED','EXPRESSION_CAPACITY_EXCEEDED',
      [{bindingId:'audit-record',failedClause:-1}])]}),{receiptCodes:codes});
  assert.equal(charged.steps[0].conditions[0].kind,'maybe-failed-here');
  assert.doesNotMatch(charged.steps[0].conditions[0].label,/All conditions held/);
  const lookup=explainMessage(message({version:1,sourceMessageIdHex:id,height:1n,status:'REJECTED',failedStepOrdinal:0,
    code:'MAPPING_MISSING_FIELD',steps:[step(0,0,null,'REJECTED','MAPPING_MISSING_FIELD',[{bindingId:'audit-record',failedClause:1}])]}),
  {receiptCodes:codes});
  assert.equal(lookup.steps[0].conditions[0].kind,'maybe-failed-here','a missing lookup source is not a false clause');
  const replay=explainMessage(message({version:1,sourceMessageIdHex:id,height:1n,status:'REJECTED',failedStepOrdinal:1,
    code:'REPLAY_OR_CONFLICT',steps:[step(0,0,null,'PLANNED','',[]),step(1,1,'audit-record','REJECTED','REPLAY_OR_CONFLICT',[])]}),
  {receiptCodes:codes});
  assert.doesNotMatch(replay.headline,/at the source|different content/);
  const empty=explainMessage(message({version:1,sourceMessageIdHex:id,height:1n,status:'ACCEPTED',failedStepOrdinal:null,code:'',steps:[]}),
    {receiptCodes:codes,draft:draftOf('cascade.yaml')});
  assert.deepEqual(empty.notVisited,[]);
  assert.match(empty.notes.join(' '),/not recorded/);
  // A mapping failure stops its parent after the binding that fired: later subscribers were not evaluated there.
  const rollback=report('rollback-report.json');
  const draft=draftOf('rollback.yaml');
  const later={...draft,bindings:[...draft.bindings,{...draft.bindings[0],id:'later-subscriber'}]};
  const view=explain(rollback,0,later);
  assert.deepEqual(view.steps[0].notEvaluated,['later-subscriber']);
});

test('truncated history never implies non-execution; replay conflicts and hidden characters stay neutral (M3 review)',async()=>{
  const {isHidden}=await import('../main/web/binding-explain.mjs');
  const {displayText}=await import('../main/web/binding-dom.mjs');
  const codes=report('cascade-report.json').receiptCodes;
  const id='cd'.repeat(32);
  const step=(ordinal,depth,bindingId,status,code,conditions)=>({ordinal,depth,bindingId,targetComponentId:'records',
    messageIdHex:id,eventsProduced:[],conditions,status,code,rawBody:false});
  const message=receipt=>({messageIndex:0,messageIdHex:id,topic:'t',disposition:'executed',receipt});
  const truncated=explainMessage(message({version:1,sourceMessageIdHex:id,height:1n,status:'REJECTED',failedStepOrdinal:2,
    code:'RECEIPT_CAPACITY_EXCEEDED',steps:[step(0,0,null,'PLANNED','',[{bindingId:'a',failedClause:-1},{bindingId:'b',failedClause:-1}]),
      step(2,1,'b','REJECTED','CUSTOM_FAILURE',[])]}),{receiptCodes:codes});
  assert.equal(truncated.truncated,true);
  const a=truncated.steps[0].conditions.find(condition=>condition.bindingId==='a');
  assert.equal(a.kind,'fired-unknown');
  assert.doesNotMatch(a.label,/was not executed/);
  const replay=explainMessage(message({version:1,sourceMessageIdHex:id,height:1n,status:'REJECTED',failedStepOrdinal:1,
    code:'REPLAY_OR_CONFLICT',steps:[step(0,0,null,'PLANNED','',[]),step(1,1,'x','REJECTED','REPLAY_OR_CONFLICT',[])]}),{receiptCodes:codes});
  assert.equal(replay.headline,'Rejected with REPLAY_OR_CONFLICT: nothing from this cascade committed.');
  assert.match(replay.notes.join(' '),/a target component can also return it/);
  assert.match(replay.steps[1].label,/may concern the whole source message/);
  for(const hidden of ['͏','ﾠ','ᅟ','‮','­','️','\u{e0041}']) assert.equal(isHidden(hidden),true,hidden.codePointAt(0).toString(16));
  for(const shown of ['a','é','漢','→','…',' ']) assert.equal(isHidden(shown),false,shown);
  assert.equal(displayText('line\nnext\tcell'),'line\nnext\tcell','display text keeps line breaks and tabs');
  assert.equal(displayText('safe‮evil'),'safe\\u{202e}evil');
});
