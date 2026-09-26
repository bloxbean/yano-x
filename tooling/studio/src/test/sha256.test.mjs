import assert from 'node:assert/strict';
import {createHash,randomBytes} from 'node:crypto';
import test from 'node:test';
import {sha256Hex,sha256TextHex} from '../main/web/sha256.mjs';

test('SHA-256 matches Node crypto across block boundaries and random inputs',()=>{
  const lengths=[0,1,55,56,57,63,64,65,119,120,127,128,1000,65_537];
  for(const length of lengths) {
    const bytes=new Uint8Array(randomBytes(length));
    assert.equal(sha256Hex(bytes),createHash('sha256').update(bytes).digest('hex'),String(length));
  }
  for(let i=0;i<200;i++) {
    const bytes=new Uint8Array(randomBytes(i*7%512));
    assert.equal(sha256Hex(bytes),createHash('sha256').update(bytes).digest('hex'));
  }
  assert.equal(sha256TextHex('abc'),'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad');
  assert.equal(sha256TextHex('é😀'),createHash('sha256').update('é😀','utf8').digest('hex'));
  assert.throws(()=>sha256Hex('text'),TypeError);
});
