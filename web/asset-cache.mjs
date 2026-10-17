const cacheName='ftl-test-resources-v1';
const hashPattern=/^[a-f0-9]{64}$/i;
const pending=new Map();

// Call only after fetching a fresh, authorized manifest. This stores asset bytes,
// never credentials, saves or a manifest that could authorize an offline launch.
export async function createAssetCache(manifest,fetchBytes){
 if(!hashPattern.test(manifest.bootHash))throw Error('Invalid game resource bundle hash');
 const bootHash=manifest.bootHash.toLowerCase(),bootKey='/api/assets/boot?hash='+bootHash;
 const audio=new Map(manifest.audio.map(a=>[a.hash.toLowerCase(),a]));
 const audioKey=hash=>'/api/assets/audio/'+hash;
 const allowed=new Set([bootKey,...[...audio.keys()].map(audioKey)]);
 let cache;
 try{
  cache=await globalThis.caches?.open(cacheName);
  if(cache){
   const obsolete=(await cache.keys()).filter(request=>{const url=new URL(request.url);return !allowed.has(url.pathname+url.search);});
   await Promise.all(obsolete.map(request=>cache.delete(request)));
  }
 }catch{cache=null;}
 async function read(key,path,hash,length){
  let promise=pending.get(key);
  if(!promise){
   promise=(async()=>{
    if(cache)try{
     const stored=await cache.match(key);
     if(stored){const bytes=await stored.arrayBuffer();if(bytes.byteLength===length)return bytes;await cache.delete(key);}
    }catch{cache=null;}
    const bytes=await fetchBytes(path);
    if(bytes.byteLength!==length)throw Error('Incomplete game resource download; retry the tests');
    if(globalThis.crypto?.subtle){
     const digest=new Uint8Array(await crypto.subtle.digest('SHA-256',bytes));
     const actual=[...digest].map(byte=>byte.toString(16).padStart(2,'0')).join('');
     if(actual!==hash)throw Error('Game resources changed or failed verification; reload Tests and retry');
     if(cache)try{await cache.put(key,new Response(bytes));}catch{cache=null;}
    }
    return bytes;
   })();
   pending.set(key,promise);
  }
  try{return await promise;}finally{if(pending.get(key)===promise)pending.delete(key);}
 }
 return {
  fetchBoot:()=>read(bootKey,'/api/assets/boot',bootHash,manifest.bootBytes),
  async fetchAudio(asset){
   const hash=asset.hash.toLowerCase(),entry=audio.get(hash);
   if(!entry)throw Error('Audio resource is absent from the current manifest');
   // decodeAudioData detaches its input. Each consumer owns a separate buffer,
   // including callers sharing the same in-flight download.
   return (await read(audioKey(hash),'/api/assets/audio/'+entry.hash,hash,entry.length)).slice(0);
  }
 };
}

// Server game files are only for accounts allowed to use them. Signing out, and any page shown to a visitor
// without that right, removes the copy so the next person on this browser profile cannot read it.
export async function clearServerAssets(){try{await globalThis.caches?.delete(cacheName);}catch{}}
