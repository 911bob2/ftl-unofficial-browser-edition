const cacheName='ftl-test-resources-v1';
const hashPattern=/^[a-f0-9]{64}$/i;
const pending=new Map();
// A download that arrives short or garbled (a slow or dropped connection) is fetched again this many times in all.
const downloadAttempts=3;

async function sha256(bytes){return [...new Uint8Array(await crypto.subtle.digest('SHA-256',bytes))].map(byte=>byte.toString(16).padStart(2,'0')).join('');}

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
 // Bytes are used only when they have the manifest's exact length and SHA-256, whether they come from the cache
 // or the network; only such bytes are cached. Without Web Crypto (an insecure origin) nothing is cached.
 const canVerify=()=>!!globalThis.crypto?.subtle;
 async function read(key,path,hash,length){
  let promise=pending.get(key);
  if(!promise){
   promise=(async()=>{
    if(cache)try{
     const stored=await cache.match(key);
     if(stored){
      const bytes=await stored.arrayBuffer();
      // Hashing the 138 MB bundle takes well under a second (about 70 ms with SHA extensions).
      if(bytes.byteLength===length&&canVerify()&&await sha256(bytes)===hash)return bytes;
      await cache.delete(key);
     }
    }catch{cache=null;}
    let failure;
    for(let attempt=0;attempt<downloadAttempts;attempt++){
     let bytes;
     // HTTP refusals (signed out, account changed) are final; a dropped connection is worth another try.
     try{bytes=await fetchBytes(path);}catch(e){if(e?.status||attempt+1===downloadAttempts)throw e;failure=e;continue;}
     if(bytes.byteLength!==length){failure=Error('Incomplete game resource download; retry the tests');continue;}
     if(!canVerify())return bytes;
     if(await sha256(bytes)!==hash){failure=Error('Game resources changed or failed verification; reload Tests and retry');continue;}
     if(cache)try{await cache.put(key,new Response(bytes));}catch{cache=null;}
     return bytes;
    }
    throw failure;
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
