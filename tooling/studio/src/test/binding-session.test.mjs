import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import {fileURLToPath} from 'node:url';
import {importAuthoringCatalog} from '../main/web/binding-catalog.mjs';
import {addBinding,moveBinding,renameComponent,setAssignment} from '../main/web/binding-edit.mjs';
import {BindingSession,EXPORT_NAMES,fileText,SessionError} from '../main/web/binding-session.mjs';
import {readBlueprint} from '../main/web/binding-blueprint.mjs';
import {parseJson,stringifyJson} from '../main/web/lossless-json.mjs';
import {sha256TextHex} from '../main/web/sha256.mjs';
import {typedTree,parseYaml} from '../main/web/studio-yaml.mjs';

const here=path.dirname(fileURLToPath(import.meta.url));
const repository=path.resolve(here,'../../../..');
const starter=name=>fs.readFileSync(path.join(repository,'examples/bindings',name),'utf8');
const web=name=>fs.readFileSync(path.join(here,'../main/web',name));
const fixture=name=>fs.readFileSync(path.join(here,'fixtures',name),'utf8');
const catalog=()=>importAuthoringCatalog(web('binding-authoring-catalog.json'));

test('a new document is editable, exportable under a fixed name and bound to its catalog',()=>{
  const session=new BindingSession({catalog:catalog(),catalogOrigin:'bundled',catalogName:'reference'});
  assert.equal(session.mode,'editable');
  assert.equal(session.exportDocument().name,EXPORT_NAMES.document);
  assert.equal(session.exportedDocumentSha256(),sha256TextHex(session.text));
  assert.equal(session.binding.contextSha256,session.catalog.context.sha256);
  assert.throws(()=>session.originalDocument(),{code:'NO_ORIGINAL'});
});

test('a failure inside the advisory checks is reported as one note, never thrown to the editor',()=>{
  const session=new BindingSession({catalog:catalog(),catalogOrigin:'bundled'});
  const failing={...session.document.draft,get bindings(){ throw new TypeError('synthetic check failure'); }};
  session.document={...session.document,draft:failing};
  assert.deepEqual(session.diagnostics().map(value=>[value.code,value.severity,value.segments]),
    [['CHECKS_INCOMPLETE','info',[]]]);
  assert.match(session.diagnostics()[0].message,/synthetic check failure.*the CLI still validates/);
});

test('starter text is kept byte for byte until an acknowledged structured edit',()=>{
  const session=new BindingSession({catalog:catalog()});
  const text=starter('registry-to-audit.yaml');
  assert.equal(session.loadText(new TextEncoder().encode(text),{name:'registry-to-audit.yaml',origin:'starter'}),'editable');
  assert.equal(session.exportDocument().text,text);
  assert.equal(session.formattingWouldChange,true,'comments would be lost');
  assert.throws(()=>session.edit(draft=>moveBinding(addBinding(draft,draft.bindings[0]),1,-1)),
    {code:'FORMATTING_UNACKNOWLEDGED'});
  assert.equal(session.text,text,'a refused edit changes nothing');
  session.acknowledgeFormatting();
  session.edit(draft=>setAssignment(draft,0,'reference',{kind:'literal',value:{type:'text',value:'reviewed'}}));
  assert.match(session.text,/reference: \{literal: reviewed\}/);
  assert.doesNotMatch(session.text,/# Studio starter/);
  assert.equal(session.originalDocument().text,text,'the original stays downloadable');
  assert.deepEqual(session.diagnostics().filter(value=>value.severity==='advisory'),[]);
  session.reset();
  assert.equal(session.text,text);
  assert.equal(session.formattingWouldChange,true,'reset requires a fresh acknowledgement');
});

test('invalid text keeps the last valid draft, blocks forms and export, and can be reverted',()=>{
  const session=new BindingSession();
  session.loadText(starter('approval-to-audit.yaml'),{name:'a.yaml',origin:'file'});
  const draft=session.draft;
  assert.equal(session.setText('composite:\n  components: [\n'),'syntax-error');
  assert.equal(session.mode,'text-invalid');
  assert.equal(session.draft,draft,'the structured draft is not silently updated');
  assert.equal(session.editable,false);
  assert.throws(()=>session.exportDocument(),{code:'TEXT_INVALID'});
  assert.equal(session.exportedDocumentSha256(),null);
  assert.throws(()=>session.edit(value=>value),{code:'TEXT_INVALID'});
  assert.equal(session.setText('composite: &x\n  components: []\n  bindings: []\n'),'unsupported');
  assert.equal(session.document.error.code,'YAML_UNSUPPORTED_CONSTRUCT');
  session.revertText();
  assert.equal(session.mode,'editable');
  assert.deepEqual(typedTree(parseYaml(session.text).root),typedTree(parseYaml(starter('approval-to-audit.yaml')).root));
  // A valid text edit becomes the draft and keeps the author's own text.
  const edited=starter('approval-to-audit.yaml').replace('literal: approved','literal: signed-off');
  assert.equal(session.setText(edited),'editable');
  assert.equal(session.exportDocument().text,edited);
  assert.equal(session.draft.bindings[0].to.mapping.assignments[2].source.value.value,'signed-off');
});

test('unsupported imports open read-only with an exact location and only the original is downloadable',()=>{
  const session=new BindingSession();
  const text='composite:\n  components: []\n  bindings: []\n  futureField: 1\n';
  assert.equal(session.loadText(text,{name:'future.yaml',origin:'file'}),'read-only');
  assert.equal(session.draft,null);
  assert.deepEqual(session.document.error.segments,['composite','futureField']);
  assert.equal(session.document.error.line,4);
  assert.throws(()=>session.exportDocument(),{code:'READ_ONLY'});
  assert.throws(()=>session.setText('composite: {}\n'),{code:'READ_ONLY'});
  assert.throws(()=>session.edit(value=>value),{code:'READ_ONLY'});
  assert.equal(session.originalDocument().text,text);
  assert.equal(session.loadText('a: [\n',{name:'broken.yaml',origin:'file'}),'syntax-error');
  assert.throws(()=>session.loadText(new Uint8Array([0xc3,0x28]),{name:'x',origin:'file'}),{code:'INVALID_UTF8'});
  assert.throws(()=>fileText(new Uint8Array(1024*1024+1)),{code:'FILE_TOO_LARGE'});
});

test('layout moves never change the document, its digest or its identity',()=>{
  const session=new BindingSession({catalog:catalog()});
  session.loadText(starter('registry-to-audit.yaml'),{name:'r.yaml',origin:'starter'});
  const digest=session.exportedDocumentSha256();
  const before=session.graph();
  assert.deepEqual(before.nodes.map(node=>node.id),['component:records','component:audit']);
  session.moveNode('component:audit',999.6,-5_000_000);
  assert.deepEqual(session.graph().positions.get('component:audit'),{x:1000,y:-1_000_000});
  assert.equal(session.exportedDocumentSha256(),digest);
  const layout=session.exportLayout();
  assert.equal(layout.name,EXPORT_NAMES.layout);
  assert.doesNotMatch(layout.text,/audit-record|kv-registry/,'the sidecar holds presentation only');
  session.resetLayout();
  session.importLayout(new TextEncoder().encode(layout.text));
  assert.deepEqual(session.graph().positions.get('component:audit'),{x:1000,y:-1_000_000});
  assert.equal(session.exportedDocumentSha256(),digest);
});

test('reordering bindings or renaming a component changes the exported bytes',()=>{
  const session=new BindingSession();
  session.loadText(starter('registry-to-audit.yaml'),{name:'r.yaml',origin:'starter'});
  session.acknowledgeFormatting();
  session.edit(draft=>addBinding(draft,{...draft.bindings[0],id:'audit-second'}));
  const digest=session.exportedDocumentSha256();
  session.edit(draft=>moveBinding(draft,1,-1));
  assert.notEqual(session.exportedDocumentSha256(),digest);
  assert.deepEqual(session.draft.bindings.map(binding=>binding.id),['audit-second','audit-record']);
  session.edit(draft=>renameComponent(draft,1,'journal'));
  assert.ok(session.draft.bindings.every(binding=>binding.to.component==='journal'));
});

test('a draft bound to one catalog does not borrow descriptors from another context',()=>{
  const reference=catalog();
  const session=new BindingSession({catalog:reference});
  session.loadText(starter('registry-to-audit.yaml'),{name:'r.yaml',origin:'starter'});
  assert.equal(session.diagnostics().some(value=>value.code==='DESCRIPTOR_UNAVAILABLE'),false);
  const other=parseJson(web('binding-authoring-catalog.json').toString('utf8'));
  other.context.sha256='0'.repeat(64);
  const imported=session.importCatalog(new TextEncoder().encode(stringifyJson(other)),'other.json');
  assert.equal(session.binding.contextSha256,'0'.repeat(64));
  assert.equal(session.catalog,imported);
  // Checking against a stale binding finds nothing rather than guessing.
  session.binding={...session.binding,contextSha256:reference.context.sha256};
  assert.ok(session.diagnostics().some(value=>value.code==='DESCRIPTOR_UNAVAILABLE'));
  assert.throws(()=>session.importCatalog(new TextEncoder().encode('{}'),'bad.json'),{code:'CATALOG_SCHEMA'});
  assert.equal(session.catalog,imported,'a rejected catalog leaves the current one in place');
});

test('blueprint composites are edited in place and every other byte is preserved',()=>{
  const text=fixture('blueprint-two-chains.yaml');
  const session=new BindingSession();
  const chains=session.importBlueprint(text,'appchain.yaml');
  assert.deepEqual(chains.map(chain=>chain.chainId),['workflow']);
  assert.equal(session.document.origin,'blueprint');
  assert.equal(session.draft.wrapped,false);
  // Unedited, the blueprint exports byte for byte; its composite carries a comment, so edits need acknowledgement.
  assert.equal(session.exportBlueprint().text,text);
  assert.equal(session.originalDocument().text,text);
  assert.equal(session.formattingWouldChange,true);
  assert.throws(()=>session.edit(draft=>draft),{code:'FORMATTING_UNACKNOWLEDGED'});
  session.acknowledgeFormatting();
  session.edit(draft=>setAssignment(draft,0,Object.keys({x:1})[0],{kind:'field',name:'key'}));
  const exported=session.exportBlueprint();
  assert.equal(exported.name,EXPORT_NAMES.blueprint);
  const before=readBlueprint(text); const after=readBlueprint(exported.text);
  assert.equal(after.composites[0].chainId,'workflow');
  assert.ok(exported.text.startsWith(text.slice(0,before.composites[0].node.start-40)),'bytes before the composite are unchanged');
  session.setText('components: [\n');
  assert.throws(()=>session.exportBlueprint(),{code:'TEXT_INVALID'});
  assert.throws(()=>new BindingSession().exportBlueprint(),error=>error instanceof SessionError && error.code==='NO_BLUEPRINT');
});

test('path edits change one value only; revisions separate leaf from structural edits (M1 review regression)',async()=>{
  const {setValue}=await import('../main/web/binding-edit.mjs');
  const {parseInt64}=await import('../main/web/binding-editor.mjs');
  const session=new BindingSession();
  session.loadText(starter('registry-to-audit.yaml'),{name:'r.yaml',origin:'starter'});
  session.acknowledgeFormatting();
  const draft=session.draft;
  const first=setValue(draft,['bindings',0,'to','command'],'append-v2');
  const second=setValue(first,['bindings',0,'to','component'],'records');
  assert.equal(second.bindings[0].to.command,'append-v2','a later leaf edit keeps an earlier one');
  assert.equal(draft.bindings[0].to.command,'append','drafts are values');
  assert.throws(()=>setValue(draft,['bindings',0,'to','newField'],1),RangeError);
  assert.throws(()=>setValue(draft,['bindings',9,'id'],'x'),RangeError);
  assert.throws(()=>setValue(draft,[],1),RangeError);
  const before={revision:session.revision,structural:session.structuralRevision};
  session.edit(value=>setValue(value,['bindings',0,'to','command'],'append'),{structural:false});
  assert.equal(session.revision,before.revision+1);
  assert.equal(session.structuralRevision,before.structural,'leaf edits leave the structure revision');
  session.edit(value=>moveBinding(addBinding(value,{...value.bindings[0],id:'b2'}),1,-1));
  assert.equal(session.structuralRevision,session.revision,'structural edits move it');
  assert.equal(parseInt64('-9223372036854775808'),-(2n**63n));
  assert.equal(parseInt64('9223372036854775808'),null);
  assert.equal(parseInt64('-0'),null);
  assert.equal(parseInt64('01'),null);
});

test('structured edits are refused unless the result reopens identically (M1 review regression)',()=>{
  const session=new BindingSession();
  session.loadText(starter('registry-to-audit.yaml'),{name:'r.yaml',origin:'starter'});
  session.acknowledgeFormatting();
  const text=session.text;
  const huge={kind:'literal',value:{type:'text',value:'x'.repeat(131_073)}};
  assert.throws(()=>session.edit(draft=>setAssignment(draft,0,'reference',huge)),{code:'EDIT_UNREPRESENTABLE'});
  const deep={kind:'fn',fn:'hex',args:[{kind:'fn',fn:'sha-256',args:[{kind:'fn',fn:'hex',args:[{kind:'fn',fn:'hex',
    args:[{kind:'field',name:'key'}]}]}]}]};
  assert.throws(()=>session.edit(draft=>setAssignment(draft,0,'entityId',deep)),{code:'EDIT_UNREPRESENTABLE'});
  assert.equal(session.text,text,'refused edits change nothing');
  // Incomplete but representable drafts (an empty field map) are accepted and reopen as editable.
  session.edit(draft=>addBinding(draft,{id:'draft-2',from:{component:'records',event:''},when:null,
    to:{kind:'command',component:'',command:'',mapping:{kind:'fields',assignments:[]}}}));
  const reopened=new BindingSession();
  assert.equal(reopened.loadText(session.exportDocument().text,{name:'x',origin:'file'}),'editable');
});

test('repairing an invalid import is unsaved work, and reset returns to the original (M1 review regression)',()=>{
  const session=new BindingSession();
  const broken='components: [\n';
  assert.equal(session.loadText(broken,{name:'b.yaml',origin:'file'}),'syntax-error');
  assert.equal(session.dirty,false);
  assert.equal(session.canRevertText,false,'there is no valid draft to revert to');
  assert.equal(session.setText('components: [{id: repaired, machine: kv-registry}]\nbindings: []\n'),'editable');
  assert.equal(session.dirty,true,'the repair is protected');
  assert.equal(session.setText('components: ['),'syntax-error');
  assert.equal(session.canRevertText,true);
  session.revertText();
  assert.equal(session.draft.components[0].id,'repaired');
  session.reset();
  assert.equal(session.mode,'text-invalid','broken syntax stays editable as text, with no draft');
  assert.equal(session.draft,null);
  assert.equal(session.text,broken);
  assert.equal(session.dirty,false);
});

test('the original download keeps every imported byte, including a BOM (M1 review regression)',()=>{
  const session=new BindingSession();
  const bytes=new Uint8Array([0xef,0xbb,0xbf,...new TextEncoder().encode('components: []\nbindings: []\nfuture: 1\n')]);
  assert.equal(session.loadText(bytes,{name:'bom.yaml',origin:'file'}),'read-only');
  assert.deepEqual([...session.originalDocument().bytes],[...bytes]);
});

test('blueprints stay attached to the document they opened (M1 review regression)',()=>{
  const text=fixture('blueprint-two-chains.yaml');
  const second=text+text.slice(text.indexOf('    - chainId: "workflow"')).replace('chainId: "workflow"','chainId: "second"');
  const session=new BindingSession();
  session.importBlueprint(text,'a.yaml');
  assert.equal(session.document.origin,'blueprint');
  const chains=session.importBlueprint(second,'b.yaml');
  assert.deepEqual(chains.map(chain=>chain.chainId),['workflow','second']);
  assert.equal(session.document.origin,'blueprint-pending','the previous blueprint document is closed');
  assert.equal(session.draft,null);
  assert.throws(()=>session.exportBlueprint(),{code:'NO_BLUEPRINT'});
  assert.equal(session.originalDocument().text,second);
  session.selectChain(chains[1].chainIndex);
  assert.equal(session.document.name,'b.yaml · chain second');
  session.reset();
  assert.equal(session.document.name,'b.yaml · chain second');
  // An unsupported construct in the composite opens read-only with its location; only the blueprint downloads.
  const unsupported=text.replace('entryHash: {field: valueHash}','entryHash: {field: valueHash, futureKey: 1}');
  session.importBlueprint(unsupported,'c.yaml');
  assert.equal(session.mode,'read-only');
  assert.equal(session.document.error.code,'UNKNOWN_FIELD');
  assert.equal(session.originalDocument().text,unsupported);
  assert.throws(()=>new BindingSession().originalDocument(),{code:'NO_ORIGINAL'});
});
