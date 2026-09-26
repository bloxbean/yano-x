import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';
import {isDeepStrictEqual} from 'node:util';
import {emitYaml,flow,isSafePlainText,parseYaml,quoteYaml,typedTree,YamlInputError,YamlMap}
  from '../main/web/studio-yaml.mjs';

const oraclePath=process.env.STUDIO_YAML_ORACLE;

test('every document Studio accepts is read identically by the Java binding parser',()=>{
  assert.ok(oraclePath,'STUDIO_YAML_ORACLE must name the Java oracle; run through Gradle testStudio');
  const oracle=JSON.parse(fs.readFileSync(oraclePath,'utf8'));
  assert.equal(oracle.schema,'yano-x-studio-yaml-oracle-v1');
  assert.ok(oracle.cases.length>3000,'oracle corpus unexpectedly small');
  let accepted=0; let conservative=0;
  const divergences=[];
  for(const item of oracle.cases) {
    const text=item.input.charCodeAt(0)===0xfeff?item.input.slice(1):item.input;
    let tree;
    try { tree=typedTree(parseYaml(text).root); }
    catch(error) {
      assert.ok(error instanceof YamlInputError,`${item.name}: parser failed without a typed error: ${error.stack}`);
      if(item.java.accepted) conservative++;
      continue;
    }
    accepted++;
    if(!item.java.accepted) divergences.push(`${item.name}: Studio accepts a document Java rejects`);
    else if(!isDeepStrictEqual(tree,item.java.tree)) divergences.push(`${item.name}: typed trees differ`);
    // The project renderer reads blueprints with its own mapper; spliced composites must read the same there.
    if(!item.blueprint?.accepted) divergences.push(`${item.name}: blueprint parser rejects a Studio document`);
    else if(!isDeepStrictEqual(tree,item.blueprint.tree)) divergences.push(`${item.name}: blueprint trees differ`);
  }
  assert.deepEqual(divergences,[]);
  assert.ok(accepted>400,'too few accepted documents to be meaningful');
  assert.ok(conservative>0,'expected Studio to refuse ambiguous documents that Java accepts');
  for(const example of ['procurement.yaml','attestation.yaml','dpp-approval.yaml','feed-approval.yaml']) {
    const item=oracle.cases.find(value=>value.name===`example:${example}`);
    assert.ok(item?.java.accepted,example);
    assert.deepEqual(typedTree(parseYaml(item.input).root),item.java.tree,example);
  }
});

test('ambiguous YAML 1.1 plain scalars are refused with a location instead of being guessed',()=>{
  for(const value of ['017','0x1F','1_000','+5','1.5','.inf','yes','No','ON','y','~','Null','True','2001-12-14','-x',
    'a b','é','/x','x:y','x,y']) {
    assert.throws(()=>parseYaml(`a: ${value}\n`),error=>error.code==='YAML_AMBIGUOUS_SCALAR'
      && error.line===1 && error.column===4,value);
  }
  assert.deepEqual(typedTree(parseYaml('a: -9223372036854775808\nb: 9223372036854775807\n').root),
    {map:[['a',{int:'-9223372036854775808'}],['b',{int:'9223372036854775807'}]]});
  assert.throws(()=>parseYaml('a: 9223372036854775808\n'),{code:'YAML_NUMBER_RANGE'});
  assert.throws(()=>parseYaml('a: -9223372036854775809\n'),{code:'YAML_NUMBER_RANGE'});
});

test('forbidden constructs fail closed with stable codes',()=>{
  const cases={
    'a: &x 1\nb: *x\n':'YAML_UNSUPPORTED_CONSTRUCT', 'a: !!str 5\n':'YAML_UNSUPPORTED_CONSTRUCT',
    'a: |\n  x\n':'YAML_UNSUPPORTED_CONSTRUCT', 'a: plain\n  more\n':'YAML_INDENTATION',
    'a: "x\n  y"\n':'YAML_UNSUPPORTED_CONSTRUCT', 'a: 1\na: 2\n':'YAML_DUPLICATE_KEY',
    'a: {b: 1, b: 2}\n':'YAML_DUPLICATE_KEY', 'a: 1\n---\nb: 2\n':'YAML_MULTIPLE_DOCUMENTS',
    '%YAML 1.1\n---\na: 1\n':'YAML_UNSUPPORTED_CONSTRUCT', 'a:\tb\n':'YAML_UNSUPPORTED_CHARACTER',
    'a: "\\/"\n':'YAML_INVALID_ESCAPE', 'a: "\\ud800"\n':'YAML_INVALID_ESCAPE', 'a:\nb: 1\n':'YAML_EMPTY_VALUE',
    '? a\n: b\n':'YAML_UNSUPPORTED_CONSTRUCT', 'a: [1, ]\n':'YAML_UNSUPPORTED_CONSTRUCT', '':'YAML_EMPTY',
    'on: 1\n':'YAML_AMBIGUOUS_SCALAR', '<<: 1\n':'YAML_AMBIGUOUS_SCALAR', 'a/b: 1\n':'YAML_AMBIGUOUS_SCALAR',
    'a: [b: 1]\n':'YAML_UNSUPPORTED_CONSTRUCT', 'a: 1\r2\n':'YAML_UNSUPPORTED_CHARACTER'
  };
  for(const [text,code] of Object.entries(cases)) assert.throws(()=>parseYaml(text),{code},JSON.stringify(text));
  const nel=`# x${String.fromCharCode(0x85)}a: 1\n`;
  assert.throws(()=>parseYaml(nel),{code:'YAML_UNSUPPORTED_CHARACTER'});
  for(const code of [0x2028,0x2029,0xfffe,0xffff,0xfeff,0x7f,0x9f])
    assert.throws(()=>parseYaml(`a: "x${String.fromCharCode(code)}"\n`),{code:'YAML_UNSUPPORTED_CHARACTER'},code.toString(16));
  assert.throws(()=>parseYaml(`${'k'.repeat(257)}: 1\n`),{code:'YAML_SCALAR_LIMIT'});
  assert.doesNotThrow(()=>parseYaml(`${'k'.repeat(256)}: 1\n`));
  // SnakeYAML limits implicit keys by source span, so escaped keys are bounded by source too (M0 review).
  const escaped=count=>`"${'\\u0041'.repeat(count)}": 1\n`;
  assert.doesNotThrow(()=>parseYaml(escaped(85)));
  assert.throws(()=>parseYaml(escaped(86)),{code:'YAML_SCALAR_LIMIT'});
  assert.throws(()=>parseYaml(`a: {${escaped(171).trimEnd()}}\n`),{code:'YAML_SCALAR_LIMIT'});
  assert.throws(()=>emitYaml(new YamlMap([['\u0001'.repeat(100),1n]])),{code:'YAML_SCALAR_LIMIT'});
});

test('size, depth and token limits match the compiler edges',()=>{
  const nested=depth=>`a: ${'['.repeat(depth-1)}1${']'.repeat(depth-1)}\n`;
  assert.doesNotThrow(()=>parseYaml(nested(48)));
  assert.throws(()=>parseYaml(nested(49)),{code:'YAML_TOO_DEEP'});
  const tokens=count=>`a: [${Array(count).fill('1').join(',')}]\n`;
  assert.doesNotThrow(()=>parseYaml(tokens(32_763)));
  assert.throws(()=>parseYaml(tokens(32_764)),{code:'YAML_TOKEN_LIMIT'});
  assert.throws(()=>parseYaml('a: 1\n'.padEnd(262_145,' ')),{code:'YAML_TOO_LARGE'});
  assert.doesNotThrow(()=>parseYaml(`a: "${'x'.repeat(131_072)}"\n`));
  assert.throws(()=>parseYaml(`a: "${'x'.repeat(131_073)}"\n`),{code:'YAML_SCALAR_LIMIT'});
});

test('source spans and positions identify authored nodes',()=>{
  const text='chains:\n  - chainId: one\n    composite: {components: []}\n';
  const {root}=parseYaml(text);
  const composite=root.entries[0].value.items[0].entries[1].value;
  assert.equal(text.slice(composite.start,composite.end),'{components: []}');
  assert.equal(composite.line,3);
  assert.equal(composite.column,16);
  assert.equal(parseYaml('a: 1\r\nb: 2\r\n').lineEnding,'\r\n');
});

test('emitter output stays inside the profile and round-trips exactly',()=>{
  const value=YamlMap.of(
    ['composite',YamlMap.of(
      ['components',[YamlMap.of(['id','records'],['machine','kv-registry']),
        flow(YamlMap.of(['id','audit'],['machine','doc-trail']))]],
      ['text',['yes','017','','a b','tab\there','quote"slash\\',`nel${String.fromCharCode(0x85)}`,'é😀',
        'procurement-approved:','kv-registry.entry-put.v1']],
      ['numbers',[0n,-9223372036854775808n,9223372036854775807n]],
      ['flags',flow([true,false])],
      ['nested',flow(YamlMap.of(['fn','hex'],['args',[flow(YamlMap.of(['field','key']))]]))],
      ['empty',YamlMap.of()],['none',[]],['odd key',1n],['017',2n])]);
  const text=emitYaml(value,{header:['generated',`multi\nline${String.fromCharCode(0x2028)}comment`]});
  const tree=typedTree(parseYaml(text).root);
  const again=emitYaml(value,{header:['generated',`multi\nline${String.fromCharCode(0x2028)}comment`]});
  assert.equal(text,again,'emission is deterministic');
  const composite=tree.map[0][1].map;
  assert.deepEqual(composite.find(([key])=>key==='text')[1].seq.map(item=>item.text),
    ['yes','017','','a b','tab\there','quote"slash\\',`nel${String.fromCharCode(0x85)}`,'é😀',
      'procurement-approved:','kv-registry.entry-put.v1']);
  assert.deepEqual(composite.find(([key])=>key==='numbers')[1].seq.map(item=>item.int),
    ['0','-9223372036854775808','9223372036854775807']);
  assert.ok(text.includes('    - {id: audit, machine: doc-trail}'));
  assert.ok(text.includes('"odd key": 1'));
  assert.equal(parseYaml(emitYaml(value,{lineEnding:'\r\n'})).lineEnding,'\r\n');
  assert.equal(isSafePlainText('yes'),false);
  assert.equal(isSafePlainText('kv-registry'),true);
  assert.equal(quoteYaml('\u0000'),'"\\u0000"');
});

test('every first-party example and construct-corpus document is inside the Studio YAML profile',async()=>{
  const {fileURLToPath}=await import('node:url');
  const path=await import('node:path');
  const here=path.dirname(fileURLToPath(import.meta.url));
  const repo=path.resolve(here,'../../../..');
  const files=[
    ...fs.readdirSync(path.join(repo,'examples/bindings')).filter(name=>name.endsWith('.yaml')).map(name=>path.join(repo,'examples/bindings',name)),
    ...fs.readdirSync(path.join(here,'fixtures/corpus')).filter(name=>name.endsWith('.yaml'))
      .map(name=>path.join(here,'fixtures/corpus',name)),
    path.join(here,'fixtures/tutorial-bindings.yaml')];
  assert.ok(files.length>=9);
  for(const file of files) assert.doesNotThrow(()=>parseYaml(fs.readFileSync(file,'utf8')),file);
});

test('separator, marker and position regressions from review are refused precisely',()=>{
  assert.throws(()=>parseYaml('a: 1\n"b" x\n'),{code:'YAML_SYNTAX',line:2});
  assert.throws(()=>parseYaml('a: {b\n  : 1}\n'),{code:'YAML_UNSUPPORTED_CONSTRUCT',line:1});
  assert.throws(()=>parseYaml('---#comment\na: 1\n'),error=>error.code!==undefined);
  assert.throws(()=>parseYaml('a: 1\nb:\t2\n'),{code:'YAML_UNSUPPORTED_CHARACTER',line:2,column:3});
  assert.throws(()=>parseYaml('a: 1\nb 2\n'),{code:'YAML_SYNTAX',line:2});
  assert.throws(()=>parseYaml("'a' : 1\n"),{code:'YAML_SYNTAX'});
});

test('hostile whitespace is handled in linear time',()=>{
  const started=performance.now();
  for(const text of [`a: x${' '.repeat(262_130)}x\n`,`a: [x${' '.repeat(262_120)}x]\n`,`a: x${' '.repeat(262_000)}\n`,
    `a: ${'{'.repeat(40)}${' '.repeat(262_000)}\n`]) {
    try { parseYaml(text); } catch { /* rejection is fine; time is what matters */ }
  }
  assert.ok(performance.now()-started<2000,'parsing hostile whitespace must stay fast');
});
