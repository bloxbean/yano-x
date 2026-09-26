import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import {fileURLToPath} from 'node:url';
import {isDeepStrictEqual} from 'node:util';
import {emitDocument,emptyDraft,importDocument} from '../main/web/binding-draft.mjs';
import {addBinding,addClause,addComponent,componentReferences,moveBinding,removeClause,renameComponent,
  setAssignment,setLimit,updateComponent} from '../main/web/binding-edit.mjs';
import {checkDraft} from '../main/web/binding-check.mjs';
import {importAuthoringCatalog} from '../main/web/binding-catalog.mjs';
import {autoLayout,exportLayout,graphModel,importLayout} from '../main/web/binding-layout.mjs';
import {parseYaml,typedTree} from '../main/web/studio-yaml.mjs';
import {parseJson,stringifyJson} from '../main/web/lossless-json.mjs';

const here=path.dirname(fileURLToPath(import.meta.url));
const repo=path.resolve(here,'../../../..');
const corpus=[
  ...fs.readdirSync(path.join(repo,'examples/bindings')).filter(name=>name.endsWith('.yaml')).map(name=>path.join(repo,'examples/bindings',name)),
  ...fs.readdirSync(path.join(here,'fixtures/corpus')).filter(name=>name.endsWith('.yaml'))
    .map(name=>path.join(here,'fixtures/corpus',name))];
const catalog=importAuthoringCatalog(fs.readFileSync(path.join(here,'../main/web/binding-authoring-catalog.json')));
const sorted=tree=>tree.map?{map:tree.map.map(([key,value])=>[key,sorted(value)]).sort(([a],[b])=>a<b?-1:a>b?1:0)}
  :tree.seq?{seq:tree.seq.map(sorted)}:tree;
const load=file=>{ const result=importDocument(fs.readFileSync(file,'utf8')); assert.equal(result.state,'editable',file); return result.draft; };

test('every corpus and example document round-trips without losing a construct',()=>{
  assert.ok(corpus.length>=10);
  for(const file of corpus) {
    const text=fs.readFileSync(file,'utf8');
    const draft=load(file);
    const emitted=emitDocument(draft);
    const again=importDocument(emitted);
    assert.equal(again.state,'editable',file);
    assert.ok(isDeepStrictEqual(again.draft,draft),`${file}: draft changes after emit/import`);
    assert.equal(emitDocument(again.draft),emitted,`${file}: emission is not idempotent`);
    // Same YAML data up to key order; list order and every authored value survive.
    assert.deepEqual(sorted(typedTree(parseYaml(emitted).root)),sorted(typedTree(parseYaml(text).root)),file);
  }
});

test('optional-field presence, list order, int64 extremes and authored bytes spelling are preserved',()=>{
  const text=`components:
  - {id: a, machine: kv-registry, config: {}}
  - {id: b, machine: doc-trail}
bindings:
  - id: z
    from: {component: a, event: kv-registry.entry-put.v1}
    when: []
    to:
      component: b
      command: append
      map:
        reference: {literal: "r"}
        entityId: {literal: {bytesHex: 'ABcd'}}
        entryHash: {literal: -9223372036854775808}
limits: {maxExpressionWorkPerBlock: 9, maxCascadeDepth: 3}
`;
  const {draft}=importDocument(text);
  assert.equal(draft.wrapped,false);
  assert.deepEqual(draft.components[0].config,[]);
  assert.equal(draft.components[1].config,null);
  assert.deepEqual(draft.bindings[0].when,[]);
  assert.deepEqual(draft.bindings[0].to.mapping.assignments.map(value=>value.field),['reference','entityId','entryHash']);
  assert.equal(draft.bindings[0].to.mapping.assignments[1].source.value.hex,'ABcd');
  assert.equal(draft.bindings[0].to.mapping.assignments[2].source.value.value,-9223372036854775808n);
  assert.deepEqual(draft.limits.map(value=>value.name),['maxExpressionWorkPerBlock','maxCascadeDepth']);
  const emitted=emitDocument(draft);
  assert.match(emitted,/config: \{\}/);
  assert.match(emitted,/when: \[\]/);
  assert.match(emitted,/bytesHex: ABcd|bytesHex: "ABcd"/);
  assert.match(emitted,/-9223372036854775808/);
  assert.doesNotMatch(emitted,/topic|fromHeight|workflowFromHeight|gate/);
});

test('unrepresentable or invalid imports fail closed with an exact path',()=>{
  const cases={
    'components: []\nbindings: []\nextra: 1\n':['read-only','UNKNOWN_FIELD',['extra']],
    'components: [{id: a, machine: m, colour: red}]\nbindings: []\n':['read-only','UNKNOWN_FIELD',['components',0,'colour']],
    'components: []\nbindings: [{id: b, from: {component: a, event: e}, when: [{field: f, exists: false}], to: {component: a, command: c, rawBody: f}}]\n':
      ['read-only','MUST_BE_TRUE',['bindings',0,'when',0,'exists']],
    'components: [{id: "5", machine: 7}]\nbindings: []\n':['read-only','EXPECTED_TEXT',['components',0,'machine']],
    'components: [{id: a, machine: m, config: [1]}]\nbindings: []\n':['read-only','EXPECTED_OBJECT',['components',0,'config']],
    'composite: {composite: {components: [], bindings: []}}\n':['read-only','NESTED_WRAPPER',['composite']],
    'components: [{id: a, machine: m, maxEffectsPerBlock: yes}]\nbindings: []\n':['read-only','YAML_AMBIGUOUS_SCALAR',null],
    'components: &x []\nbindings: []\n':['read-only','YAML_UNSUPPORTED_CONSTRUCT',null],
    'components: [\nbindings: []\n':['syntax-error',null,null]
  };
  for(const [text,[state,code,segments]] of Object.entries(cases)) {
    const result=importDocument(text);
    assert.equal(result.state,state,text);
    if(code) assert.equal(result.error.code,code,text);
    if(segments) assert.deepEqual(result.error.segments,segments,text);
  }
});

test('edit operations are explicit, preview references and never reorder or add defaults',()=>{
  const draft=load(path.join(repo,'examples/bindings/procurement.yaml'));
  assert.deepEqual(componentReferences(draft,'suppliers').map(ref=>ref.role),['lookup']);
  assert.deepEqual(componentReferences(draft,'reviews').map(ref=>ref.role),['target','source']);
  const governed=load(path.join(repo,'examples/bindings/dpp-approval.yaml'));
  const mentions=componentReferences(governed,'actors').filter(ref=>ref.role==='configuration-mention');
  assert.deepEqual(mentions.map(ref=>ref.segments.join('.')),
    ['composite.components.1.config.actor-component','composite.components.2.config.actors']);
  assert.equal(renameComponent(governed,0,'people').components[1].config[0].value.value,'actors',
    'configuration is never rewritten by a rename');
  const renamed=renameComponent(draft,2,'approvals');
  assert.equal(renamed.bindings[0].to.component,'approvals');
  assert.equal(renamed.bindings[1].from.component,'approvals');
  assert.equal(draft.bindings[0].to.component,'reviews','edits are immutable');
  assert.throws(()=>updateComponent(draft,0,{id:'x'}),/renameComponent/);
  const reordered=moveBinding(draft,0,1);
  assert.deepEqual(reordered.bindings.map(value=>value.id),['review-to-audit','order-to-review','audit-to-outbox']);
  assert.notEqual(emitDocument(reordered),emitDocument(draft));
  assert.throws(()=>moveBinding(draft,0,-1),RangeError);
  const withLimit=setLimit(draft,'maxLookupsPerCondition',3n);
  assert.deepEqual(withLimit.limits.map(value=>value.name),['maxCascadeDepth','maxDerivedPerSourceMessage','maxLookupsPerCondition']);
  assert.deepEqual(setLimit(withLimit,'maxLookupsPerCondition',null).limits,draft.limits);
  const fresh=addBinding(addComponent(addComponent(emptyDraft(),{id:'records',machine:'kv-registry'}),
    {id:'audit',machine:'doc-trail'}),{id:'audit-record',from:{component:'records',event:'kv-registry.entry-put.v1'},
    when:null,to:{kind:'command',component:'audit',command:'append',mapping:{kind:'fields',assignments:[]}}});
  let built=setAssignment(fresh,0,'entityId',{kind:'fn',fn:'hex',args:[{kind:'field',name:'key'}]});
  built=setAssignment(built,0,'entryHash',{kind:'field',name:'valueHash'});
  built=setAssignment(built,0,'reference',{kind:'literal',value:{type:'text',value:'published'}});
  built=addClause(built,0,{kind:'expr',text:'event.valueLength < 100'});
  const starter=load(path.join(repo,'examples/bindings/registry-to-audit.yaml'));
  assert.ok(isDeepStrictEqual(built,starter),'forms build exactly the starter document');
  assert.equal(removeClause(built,0,0).bindings[0].when.length,0);
});

test('advisory checks use exact catalog instances and never claim validity',()=>{
  const starter=load(path.join(repo,'examples/bindings/registry-to-audit.yaml'));
  assert.deepEqual(checkDraft(starter,catalog),[]);
  const broken=setAssignment(setAssignment(starter,0,'entityId',{kind:'field',name:'key'}),0,'extra',
    {kind:'literal',value:{type:'text',value:'x'}});
  const codes=checkDraft(broken,catalog).map(value=>value.code);
  assert.ok(codes.includes('BINDING_TYPE_MISMATCH'));
  assert.ok(codes.includes('UNKNOWN_TARGET_FIELD'));
  const configured=updateComponent(starter,0,{config:[{name:'value-format',value:{type:'text',value:'raw'}}]});
  assert.deepEqual(checkDraft(configured,catalog).map(value=>value.code),['DESCRIPTOR_UNAVAILABLE']);
  const evidence=parseJson(fs.readFileSync(path.join(here,'../main/web/binding-authoring-catalog.json'),'utf8'));
  const trail=evidence.instances.find(value=>value.machineId==='doc-trail');
  trail.commands[0].fields[1].role='evidence';
  const evidenceCatalog=importAuthoringCatalog(stringifyJson(evidence));
  const copied=checkDraft(starter,evidenceCatalog).map(value=>value.code);
  assert.deepEqual(copied,[],'a direct field copy satisfies an evidence field');
  const computed=setAssignment(starter,0,'entryHash',{kind:'fn',fn:'sha-256',args:[{kind:'field',name:'value'}]});
  assert.ok(checkDraft(computed,evidenceCatalog).some(value=>value.code==='BINDING_EVIDENCE_UNSATISFIABLE'));
  const cyclic=addBinding(starter,{id:'back',from:{component:'audit',event:'doc-trail.entry-appended.v1'},when:null,
    to:{kind:'command',component:'records',command:'put',mapping:{kind:'fields',assignments:[]}}});
  assert.ok(checkDraft(cyclic,catalog).some(value=>value.code==='CYCLIC_BINDING_GRAPH'));
});

test('an incomplete function call is reported by arity, never by a crash',()=>{
  const text=fs.readFileSync(path.join(repo,'examples/bindings/registry-to-audit.yaml'),'utf8')
    .replace('reference: {literal: published}','reference: {fn: concat, args: []}');
  const imported=importDocument(text);
  assert.equal(imported.state,'editable');
  // Only the existing arity diagnostic: an argument-less call has no result type, so no type mismatch is claimed.
  assert.deepEqual(checkDraft(imported.draft,catalog).map(value=>[value.code,value.message,value.segments]),
    [['FUNCTION_ARITY','concat takes 2 to 8 arguments',['composite','bindings',0,'to','map','reference']]]);
});

test('line feeds and tabs in expressions and text values are not reported as hidden characters',()=>{
  const base=fs.readFileSync(path.join(repo,'examples/bindings/registry-to-audit.yaml'),'utf8');
  const codes=text=>checkDraft(importDocument(text).draft,catalog).map(value=>value.code);
  assert.deepEqual(codes(base.replace("expr: 'event.valueLength < 100'",'expr: "event.valueLength\\n\\t< 100"')
    .replace('reference: {literal: published}','reference: {literal: "line1\\nline2"}')),[]);
  assert.deepEqual(codes(base.replace('reference: {literal: published}','reference: {literal: "a\\rb"}')),
    ['HIDDEN_CHARACTERS']);
  assert.deepEqual(codes(base.replace('{id: audit, machine: doc-trail}','{id: audit, machine: "doc-trail\\tx"}'))
    .filter(code=>code==='HIDDEN_CHARACTERS'),['HIDDEN_CHARACTERS']);
});

test('graph layout is presentation only and bounded',()=>{
  const draft=load(path.join(repo,'examples/bindings/procurement.yaml'));
  const model=graphModel(draft);
  assert.deepEqual(model.edges.map(edge=>edge.id),['order-to-review','review-to-audit','audit-to-outbox']);
  assert.ok(model.nodes.some(node=>node.id==='effect:audit-to-outbox' && node.kind==='effect'));
  const positions=autoLayout(model);
  assert.equal(positions.get('component:orders').x<positions.get('component:reviews').x,true);
  const layout={nodes:new Map([['component:orders',{x:500,y:-20}]]),collapsed:new Set(['component:audit'])};
  const exported=exportLayout(layout);
  const imported=importLayout(exported);
  assert.deepEqual(imported.nodes.get('component:orders'),{x:500,y:-20});
  assert.equal(emitDocument(draft),emitDocument(load(path.join(repo,'examples/bindings/procurement.yaml'))),
    'layout never changes the document');
  assert.doesNotMatch(exported,/kv-registry|orders\.command|value/,'layout carries no document content');
  assert.throws(()=>importLayout('{"schema":"yano-x-binding-layout-v1","nodes":{"component:a":{"x":1000001,"y":0}}}'),
    {code:'CONTRACT_RANGE'});
  assert.throws(()=>importLayout('{"schema":"other","nodes":{}}'),{code:'LAYOUT_SCHEMA'});
  assert.throws(()=>importLayout('{"schema":"yano-x-binding-layout-v1","nodes":{"<script>":{"x":1,"y":1}}}'),
    {code:'CONTRACT_FORMAT'});
  const cycle=graphModel({components:[{id:'a',machine:'m'},{id:'b',machine:'m'}],bindings:[
    {id:'x',from:{component:'a',event:'e'},when:null,to:{kind:'command',component:'b',command:'c',mapping:{kind:'raw',field:'f'}}},
    {id:'y',from:{component:'b',event:'e'},when:null,to:{kind:'command',component:'a',command:'c',mapping:{kind:'raw',field:'f'}}}]});
  assert.equal(autoLayout(cycle).size,2,'cycles terminate');
});
