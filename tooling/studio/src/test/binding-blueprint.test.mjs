import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import {fileURLToPath} from 'node:url';
import {draftForChain,readBlueprint,spliceComposite} from '../main/web/binding-blueprint.mjs';
import {addClause,moveBinding} from '../main/web/binding-edit.mjs';
import {parseYaml,typedTree} from '../main/web/studio-yaml.mjs';

const here=path.dirname(fileURLToPath(import.meta.url));
const text=fs.readFileSync(path.join(here,'fixtures/blueprint-two-chains.yaml'),'utf8');

test('only the selected composite value changes; every other byte and comment survives',()=>{
  const blueprint=readBlueprint(text);
  assert.deepEqual(blueprint.composites.map(value=>value.chainId),['workflow']);
  const draft=addClause(draftForChain(blueprint,1),0,{kind:'expr',text:'event.valueLength < 100'});
  const spliced=spliceComposite(blueprint,1,draft);
  const before=text.split('\n'); const after=spliced.split('\n');
  const start=before.findIndex(line=>line.includes('# Registry values are audited.'));
  assert.deepEqual(after.slice(0,start),before.slice(0,start),'everything before the composite is byte-identical');
  assert.ok(spliced.includes('      # trailing comment belongs to the chain, not the composite'));
  assert.ok(spliced.includes('  name: "orders-and-audit"   # project name'));
  assert.ok(spliced.includes('        components:\n          - id: records'));
  assert.ok(spliced.includes('expr: "event.valueLength < 100"'));
  assert.deepEqual(draftForChain(readBlueprint(spliced),1),draft);
});

test('flow-style composites are spliced as blocks and verified',()=>{
  const flowText=text.replace(/      composite:\n(?:        .*\n|        #.*\n)+/,
    '      composite: {components: [{id: records, machine: kv-registry}], bindings: []}  # inline\n');
  const blueprint=readBlueprint(flowText);
  const draft=draftForChain(blueprint,1);
  const spliced=spliceComposite(blueprint,1,draft);
  assert.match(spliced,/      composite:\r?\n        components:\n          - id: records/);
  assert.ok(spliced.includes('# inline'));
  assert.deepEqual(draftForChain(readBlueprint(spliced),1),draft);
});

test('CRLF blueprints keep their line endings',()=>{
  const crlf=text.replaceAll('\n','\r\n');
  const blueprint=readBlueprint(crlf);
  const spliced=spliceComposite(blueprint,1,moveBinding(draftForChain(blueprint,1),0,0));
  assert.ok(!/[^\r]\n/.test(spliced),'no bare line feeds introduced');
});

test('unsupported or ambiguous blueprints are refused rather than rewritten',()=>{
  assert.throws(()=>readBlueprint(text.replace('network: "devnet"','network: devnet\n  extra: &anchor 1')),{code:'YAML_UNSUPPORTED_CONSTRUCT'});
  assert.throws(()=>readBlueprint(text.replace('chainId: "ledger"','chainId: "workflow"')),{code:'BLUEPRINT_CHAIN_IDS'});
  assert.throws(()=>draftForChain(readBlueprint(text),0),{code:'BLUEPRINT_CHAIN'});
  assert.throws(()=>readBlueprint('x'.repeat(1024*1024+1)),{code:'BLUEPRINT_TOO_LARGE'});
});
