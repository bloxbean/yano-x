import assert from 'node:assert/strict';
import test from 'node:test';
import {decodeUtf8Strict,expectDecimalInt64,expectInteger,expectObject,parseJson,stringifyJson}
  from '../main/web/lossless-json.mjs';

test('int64 extremes survive parsing and serialization without Number conversion',()=>{
  const text='{"expiresAt":9223372036854775807,"minimum":-9223372036854775808,"beyond":18446744073709551616}';
  const value=parseJson(text);
  assert.equal(value.expiresAt,9223372036854775807n);
  assert.equal(value.minimum,-9223372036854775808n);
  assert.equal(value.beyond,18446744073709551616n);
  assert.equal(stringifyJson(value,0),text);
  assert.throws(()=>expectInteger(value.beyond,'$.beyond'),{code:'CONTRACT_RANGE'});
  assert.equal(expectInteger(value.minimum,'$.minimum'),-9223372036854775808n);
  // The unsafe path this contract prevents: ordinary JSON.parse rounds the same value.
  assert.notEqual(BigInt(JSON.parse(text).expiresAt),9223372036854775807n);
});

test('decimal-string int64 fields are canonical and range checked',()=>{
  assert.equal(expectDecimalInt64('-9223372036854775808','$.a'),-9223372036854775808n);
  assert.equal(expectDecimalInt64('9223372036854775807','$.a'),9223372036854775807n);
  for(const invalid of ['9223372036854775808','-9223372036854775809','01','-0','+1','1.0','',' 1'])
    assert.throws(()=>expectDecimalInt64(invalid,'$.a'),/\$\.a/,invalid);
});

test('strict grammar rejects ambiguity, duplicates, non-integers and hostile shapes',()=>{
  const cases={
    '{"a":1,"a":2}':'JSON_DUPLICATE_KEY', '1.5':'JSON_NON_INTEGER', '1e3':'JSON_NON_INTEGER',
    '[1,]':'JSON_SYNTAX', '{"a":1} x':'JSON_SYNTAX', '"\\ud800"':'JSON_SYNTAX', '"\u0001"':'JSON_SYNTAX',
    'NaN':'JSON_SYNTAX', '01':'JSON_SYNTAX', '"\\x41"':'JSON_SYNTAX', '':'JSON_SYNTAX'
  };
  for(const [text,code] of Object.entries(cases)) assert.throws(()=>parseJson(text),{code},JSON.stringify(text));
  assert.throws(()=>parseJson('['.repeat(65)+']'.repeat(65)),{code:'JSON_TOO_DEEP'});
  assert.doesNotThrow(()=>parseJson('['.repeat(64)+']'.repeat(64)));
  assert.throws(()=>parseJson('"x"',{maxCharacters:2}),{code:'JSON_TOO_LARGE'});
  assert.throws(()=>parseJson(`[${Array(11).fill(1).join(',')}]`,{maxEntries:11}),{code:'JSON_TOO_LARGE'});
  assert.throws(()=>parseJson(`${'9'.repeat(33)}`),{code:'JSON_TOO_LARGE'});
});

test('objects cannot pollute prototypes and unknown fields are rejected by contract validators',()=>{
  const value=parseJson('{"__proto__":{"polluted":true},"constructor":1}');
  assert.equal(Object.getPrototypeOf(value),null);
  assert.equal(({}).polluted,undefined);
  assert.equal(value.__proto__.polluted,true);
  assert.throws(()=>expectObject(value,'$',['constructor']),{code:'CONTRACT_UNKNOWN_FIELD'});
  assert.throws(()=>expectObject(parseJson('{}'),'$',['schema']),{code:'CONTRACT_MISSING_FIELD'});
});

test('file bytes must be valid UTF-8; the error does not echo content',()=>{
  assert.equal(decodeUtf8Strict(new TextEncoder().encode('{"a":"é"}')),'{"a":"é"}');
  assert.throws(()=>decodeUtf8Strict(new Uint8Array([0x7b,0xff,0x7d])),{code:'INVALID_UTF8'});
  assert.throws(()=>parseJson('{"secret":01}'),error=>!error.message.includes('secret'));
});
