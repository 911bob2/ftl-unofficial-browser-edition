import assert from 'node:assert/strict';
import {indexDat,sha256,unpackResources} from '../web/dat.mjs';
function dat(name='data/test.xml',body='hello'){
 const names=new TextEncoder().encode(name+'\0'),bytes=new TextEncoder().encode(body),packed=new Uint8Array(36+names.length+bytes.length),view=new DataView(packed.buffer);view.setUint32(0,0x504b470a);view.setUint16(4,16);view.setUint16(6,20);view.setUint32(8,1);view.setUint32(12,names.length);view.setUint32(24,36+names.length);view.setUint32(28,bytes.length);view.setUint32(32,bytes.length);packed.set(names,36);packed.set(bytes,36+names.length);return packed;
}
assert.equal(await sha256(new TextEncoder().encode('abc')),'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad');
const good=dat();assert.deepEqual(await indexDat(new Blob([good])),[{name:'data/test.xml',offset:50,length:5}]);
for(const name of ['../bad','/absolute','a\\b','control\u0001'])await assert.rejects(indexDat(new Blob([dat(name)])),/Unsafe/);
await assert.rejects(indexDat(new Blob([good.slice(0,-1)])),/bounds/);
const bad=good.slice();new DataView(bad.buffer).setUint32(20,0x01000000);await assert.rejects(indexDat(new Blob([bad])),/Compressed/);
const overlap=good.slice();new DataView(overlap.buffer).setUint32(24,16);await assert.rejects(indexDat(new Blob([overlap])),/bounds/);
const unterminated=good.slice();unterminated[49]=65;await assert.rejects(indexDat(new Blob([unterminated])),/name/);
assert.throws(()=>unpackResources(new ArrayBuffer(8)),/Invalid/);
console.log('PASS DAT digest, index, traversal, truncation, flags, bounds and names');
