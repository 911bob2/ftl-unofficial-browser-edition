import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {createAssetCache,clearServerAssets} from '../web/asset-cache.mjs';

const boot=new Uint8Array([1,2,3,4]),sound=new Uint8Array([5,6,7]);
const hash=bytes=>createHash('sha256').update(bytes).digest('hex');
const manifest={assetVersion:'same-DAT',bootHash:hash(boot),bootBytes:boot.length,audio:[{hash:hash(sound),length:sound.length}]};
const absolute=key=>new URL(typeof key==='string'?key:key.url,'http://localhost').href;
function storage(){
 const entries=new Map();
 const cache={
  async match(key){return entries.get(absolute(key))?.clone();},
  async put(key,response){entries.set(absolute(key),response.clone());},
  async delete(key){return entries.delete(absolute(key));},
  async keys(){return [...entries.keys()].map(url=>new Request(url));}
 };
 return {cache,entries,async open(){return cache;}};
}
const original=Object.getOwnPropertyDescriptor(globalThis,'caches');
const setStorage=value=>Object.defineProperty(globalThis,'caches',{value,configurable:true});
const bytes=async path=>(path==='/api/assets/boot'?boot:sound).slice().buffer;
const checks=[];
async function check(label,action){await action();checks.push(label);console.log('PASS '+label);}
try{
 await check('Persistent instances reuse boot and lazy audio bytes',async()=>{
  const store=storage();setStorage(store);let calls=0;const fetch=path=>{calls++;return bytes(path);};
  const first=await createAssetCache(manifest,fetch);assert.deepEqual(new Uint8Array(await first.fetchBoot()),boot);assert.equal(calls,1);
  assert.equal(store.entries.size,1);await first.fetchAudio(manifest.audio[0]);assert.equal(calls,2);
  const next=await createAssetCache(manifest,fetch);await next.fetchBoot();await next.fetchAudio(manifest.audio[0]);assert.equal(calls,2);
 });
 await check('Concurrent audio requests share one download and survive decode detachment',async()=>{
  setStorage(storage());let calls=0;const fetch=async path=>{calls++;await new Promise(resolve=>setTimeout(resolve,10));return bytes(path);};
  const assets=await createAssetCache(manifest,fetch),otherRun=await createAssetCache(manifest,fetch);
  const [first,second]=await Promise.all([assets.fetchAudio(manifest.audio[0]),otherRun.fetchAudio(manifest.audio[0])]);
  assert.equal(calls,1);assert.notEqual(first,second);structuredClone(first,{transfer:[first]});assert.equal(first.byteLength,0);
  assert.deepEqual(new Uint8Array(second),sound);assert.deepEqual(new Uint8Array(await assets.fetchAudio(manifest.audio[0])),sound);assert.equal(calls,1);
 });
 await check('Changed boot/audio hashes invalidate and prune without changing DAT version',async()=>{
  const store=storage();setStorage(store);const old=await createAssetCache(manifest,bytes);await old.fetchBoot();await old.fetchAudio(manifest.audio[0]);
  const changedBoot=new Uint8Array([9,2,3,4]),changedSound=new Uint8Array([8,6,7]);
  const changed={...manifest,bootHash:hash(changedBoot),audio:[{hash:hash(changedSound),length:changedSound.length}]};let calls=0;
  const next=await createAssetCache(changed,async path=>{calls++;return (path==='/api/assets/boot'?changedBoot:changedSound).slice().buffer;});
  assert.equal(store.entries.size,0);await next.fetchBoot();await next.fetchAudio(changed.audio[0]);assert.equal(calls,2);assert.equal(store.entries.size,2);
 });
 await check('Rejected downloads are not cached and can be retried',async()=>{
  const store=storage();setStorage(store);let calls=0;
  const assets=await createAssetCache(manifest,async path=>{if(++calls===1)throw Error('Connection lost');return bytes(path);});
  await assert.rejects(assets.fetchBoot(),/Connection lost/);assert.equal(store.entries.size,0);await assets.fetchBoot();assert.equal(calls,2);
 });
 await check('Incomplete and wrong-hash downloads are rejected before persistence',async()=>{
  for(const invalid of [new Uint8Array([1]),new Uint8Array([9,9,9,9])]){
   const store=storage();setStorage(store);let calls=0;
   const assets=await createAssetCache(manifest,async path=>++calls===1?invalid.slice().buffer:bytes(path));
   await assert.rejects(assets.fetchBoot(),/Incomplete|verification/);assert.equal(store.entries.size,0);await assets.fetchBoot();assert.equal(calls,2);
  }
 });
 await check('Truncated cache entries are replaced from the network',async()=>{
  const store=storage();setStorage(store);const first=await createAssetCache(manifest,bytes);await first.fetchBoot();
  const key=[...store.entries.keys()][0];store.entries.set(key,new Response(new Uint8Array([1])));let calls=0;
  const next=await createAssetCache(manifest,async path=>{calls++;return bytes(path);});assert.deepEqual(new Uint8Array(await next.fetchBoot()),boot);assert.equal(calls,1);
 });
 await check('Unavailable storage and open/read/write/prune failures fall back to network',async()=>{
  for(const operation of ['unavailable','open','match','put','keys','delete']){
   const store=storage();if(operation==='delete')store.entries.set('http://localhost/obsolete',new Response('old'));
   if(operation==='open')store.open=async()=>{throw Error('Storage denied');};
   else if(operation!=='unavailable')store.cache[operation]=async()=>{throw Error('Storage denied');};
   setStorage(operation==='unavailable'?undefined:store);let calls=0;
   const assets=await createAssetCache(manifest,async path=>{calls++;return bytes(path);});
   assert.deepEqual(new Uint8Array(await assets.fetchBoot()),boot);await assets.fetchBoot();assert.equal(calls,2,operation);
  }
 });
 await check('Invalid manifests and audio outside the manifest are rejected',async()=>{
  setStorage(storage());await assert.rejects(createAssetCache({...manifest,bootHash:'invalid'},bytes),/bundle hash/);
  const assets=await createAssetCache(manifest,()=>{throw Error('Unexpected network request');});
  await assert.rejects(assets.fetchAudio({hash:'a'.repeat(64)}),/current manifest/);
 });
 await check('Clearing removes the whole server asset cache and tolerates missing storage',async()=>{
  const deleted=[];setStorage({...storage(),async delete(name){deleted.push(name);return true;}});
  await clearServerAssets();assert.deepEqual(deleted,['ftl-test-resources-v1']);
  setStorage({async delete(){throw Error('Storage denied');}});await clearServerAssets();
  setStorage(undefined);await clearServerAssets();
 });
 console.log(checks.length+' resource cache checks passed');
}finally{if(original)Object.defineProperty(globalThis,'caches',original);else delete globalThis.caches;}
