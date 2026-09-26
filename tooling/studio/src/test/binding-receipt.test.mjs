import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';
import {parseJson} from '../main/web/lossless-json.mjs';
import {decodeBindingReceipt,hexToBytes,ReceiptError,receiptViewDifferences} from '../main/web/binding-receipt.mjs';

const oraclePath=process.env.STUDIO_RECEIPT_ORACLE;

test('Studio receipt decoding accepts exactly what BindingReceiptV1.decode accepts and yields the same view',()=>{
  assert.ok(oraclePath,'STUDIO_RECEIPT_ORACLE must name the Java oracle; run through Gradle testStudio');
  const oracle=parseJson(fs.readFileSync(oraclePath,'utf8'),{maxCharacters:64*1024*1024,maxEntries:10_000_000});
  assert.equal(oracle.schema,'yano-x-studio-receipt-oracle-v1');
  const divergences=[];
  let accepted=0;
  for(const item of oracle.cases) {
    let decoded=null;
    try { decoded=decodeBindingReceipt(hexToBytes(item.hex)); }
    catch(error) {
      assert.ok(error instanceof ReceiptError,`${item.name}: untyped failure ${error.stack}`);
    }
    if(Boolean(decoded)!==item.accepted) { divergences.push(`${item.name}: acceptance differs`); continue; }
    if(!decoded) continue;
    accepted++;
    const differences=receiptViewDifferences(decoded,item.view);
    if(differences.length) divergences.push(`${item.name}: ${differences.join(', ')}`);
  }
  assert.deepEqual(divergences,[]);
  assert.ok(accepted>100 && accepted<oracle.cases.length,'oracle must contain accepted and rejected receipts');
});

test('a contradictory decoded view is detected field by field',()=>{
  const oracle=parseJson(fs.readFileSync(oraclePath,'utf8'),{maxCharacters:64*1024*1024,maxEntries:10_000_000});
  const item=oracle.cases.find(value=>value.name==='synthetic.rejected.child');
  const decoded=decodeBindingReceipt(hexToBytes(item.hex));
  assert.deepEqual(receiptViewDifferences(decoded,item.view),[]);
  const forged=structuredClone(item.view);
  forged.steps[1].status='PLANNED';
  forged.height='1';
  assert.deepEqual(receiptViewDifferences(decoded,forged),['height','steps[1].status']);
  const extra=structuredClone(item.view); extra.note='x';
  assert.deepEqual(receiptViewDifferences(decoded,extra),['receipt view fields']);
  assert.equal(decoded.height,9223372036854775807n);
  assert.equal(decoded.status,'REJECTED');
  assert.equal(decoded.steps[0].status,'PLANNED');
});

test('receipt bounds and versions fail closed',()=>{
  assert.throws(()=>decodeBindingReceipt(new Uint8Array(65_537)),{code:'RECEIPT_INVALID'});
  assert.throws(()=>decodeBindingReceipt(new Uint8Array()),{code:'RECEIPT_INVALID'});
  assert.throws(()=>hexToBytes('abc'),{code:'RECEIPT_INVALID'});
  // [2, ...] with the right shape but an unknown version.
  const oracle=parseJson(fs.readFileSync(oraclePath,'utf8'),{maxCharacters:64*1024*1024,maxEntries:10_000_000});
  const bytes=hexToBytes(oracle.cases.find(value=>value.name==='receipt.accepted').hex);
  bytes[1]=0x02;
  assert.throws(()=>decodeBindingReceipt(bytes),{code:'RECEIPT_VERSION'});
});

test('receipt text keeps a leading U+FEFF and is rejected like Java (review regression)',()=>{
  const hex='87015820'+'00'.repeat(32)+'016befbbbf4143434550544544f66080';
  assert.throws(()=>decodeBindingReceipt(hexToBytes(hex)),{code:'RECEIPT_INVALID'});
});
