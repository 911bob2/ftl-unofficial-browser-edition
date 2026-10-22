// Save storage for local-only mode. Nothing here talks to the server.
//
// IndexedDB is the primary store. Every commit is one transaction that writes the current save and a history entry
// together, so a save is either fully replaced or untouched. A copy of the latest save is also kept in localStorage
// (one setItem, which is atomic) for two cases IndexedDB cannot cover: the browser clears or blocks IndexedDB, and a
// tab that is closed before its asynchronous commit finishes (the unload path can only write localStorage
// synchronously). On load the copy with the higher revision wins and the other store is repaired. Cookies are not
// used: a cookie holds about 4 KB and is sent with every request.
export const LOCAL_ID='local-device';
const DB_NAME='ftl-local-saves',MIRROR_KEY='ftl-local-save:v1',KEEP=10,MAX_BYTES=16*1024*1024;
let dbPromise;

function database(){
 return dbPromise??=new Promise((resolve,reject)=>{
  if(!globalThis.indexedDB)return reject(Error('Browser storage is unavailable.'));
  let request;try{request=indexedDB.open(DB_NAME,1);}catch(e){return reject(e);}
  request.onupgradeneeded=()=>{request.result.createObjectStore('current');request.result.createObjectStore('history',{keyPath:'revision'});};
  request.onsuccess=()=>{request.result.onversionchange=()=>{request.result.close();dbPromise=null;};request.result.onclose=()=>{dbPromise=null;};resolve(request.result);};
  request.onerror=()=>reject(request.error??Error('Browser storage is unavailable.'));
  request.onblocked=()=>reject(Error('Close other FTL tabs to update browser storage.'));
 }).catch(e=>{dbPromise=null;throw e;});
}
const conflict=message=>Object.assign(Error(message),{status:409});
export const valid=r=>!!r&&typeof r==='object'&&r.schema===1&&Number.isSafeInteger(r.revision)&&r.revision>=0&&typeof r.engineBuild==='string'&&typeof r.assetVersion==='string'&&(r.run==null||typeof r.run==='string')&&(r.profile==null||typeof r.profile==='string');
export const hasProgress=r=>!!r&&(!!r.run||!!r.profile);
const envelope=r=>({schema:1,engineBuild:r.engineBuild,assetVersion:r.assetVersion,revision:r.revision,run:r.run??null,profile:r.profile??null});

function readMirror(){try{const raw=localStorage.getItem(MIRROR_KEY);if(!raw)return null;const record=JSON.parse(raw);return valid(record)?record:null;}catch{return null;}}
function writeMirror(record){
 // A failed write (quota, storage blocked) leaves the previous copy in place: an older save is better than none.
 try{const text=JSON.stringify(record);if(text.length>MAX_BYTES/4)return false;localStorage.setItem(MIRROR_KEY,text);return true;}catch{return false;}
}
async function readCurrent(){
 const db=await database();
 return new Promise((resolve,reject)=>{const tx=db.transaction('current','readonly'),request=tx.objectStore('current').get('save');tx.oncomplete=()=>resolve(request.result??null);tx.onerror=tx.onabort=()=>reject(tx.error);});
}
// One transaction: compare the stored revision, replace the current save, append history, prune history.
async function commit(record,expected){
 const db=await database();
 return new Promise((resolve,reject)=>{
  const tx=db.transaction(['current','history'],'readwrite'),current=tx.objectStore('current'),history=tx.objectStore('history');
  let failure=null;
  const get=current.get('save');
  get.onsuccess=()=>{
   const existing=get.result;
   // A missing or unreadable current record (cleared storage) is not a conflict: the caller's copy is the newest one.
   if(expected!==null&&valid(existing)&&existing.revision!==expected){failure=conflict('A newer save exists in another tab. Reload before saving.');tx.abort();return;}
   current.put(record,'save');history.put(record);
   history.delete(IDBKeyRange.upperBound(record.revision-KEEP));
  };
  tx.oncomplete=()=>resolve();
  tx.onerror=tx.onabort=()=>reject(failure??tx.error??Error('The browser could not store the save.'));
 });
}

// Returns {record,source,error}: record is the newest valid save or null; source is 'indexeddb', 'mirror' or 'none'.
export async function load(){
 let stored=null,error=null;
 try{const record=await readCurrent();stored=valid(record)?record:null;}catch(e){error=e;}
 const mirror=readMirror();
 if(mirror&&(!stored||mirror.revision>stored.revision)){
  // Browser storage was cleared, or the last tab closed before its commit finished: restore it from the copy.
  if(!error)await commit(mirror,null).catch(()=>{});
  return {record:mirror,source:'mirror',error:error?.message??null};
 }
 return {record:stored,source:stored?'indexeddb':'none',error:error?.message??null};
}
const KINDS=['auto','manual','import'];
// Commit a new revision of the save. `expected` is the revision the caller last saw (0 for none). `kind` says what made
// it: "auto", "manual" or "import" (anything else is stored as unknown).
export async function save(state,expected,kind){
 if(!state||state.schema!==1)throw Error('Incompatible save format');
 const record={schema:1,engineBuild:state.engineBuild,assetVersion:state.assetVersion,revision:expected+1,run:state.run??null,profile:state.profile??null,savedUtc:new Date().toISOString(),kind:KINDS.includes(kind)?kind:null};
 let durable=true;
 try{await commit(record,expected);}
 catch(e){
  if(e.status===409)throw e;
  // IndexedDB failed (blocked, evicted, quota): the localStorage copy keeps the save, and the caller is told.
  if(!writeMirror(record))throw Error('This browser could not store your save. Download a backup or free up storage, then retry.');
  durable=false;
 }
 if(durable)writeMirror(record);
 return {revision:record.revision,durable};
}
// Synchronous last-resort write for page teardown: only localStorage can finish while a page is closing.
export function flushMirror(state,revision){
 if(!valid({schema:1,engineBuild:state?.engineBuild,assetVersion:state?.assetVersion,revision,run:state?.run,profile:state?.profile}))return false;
 return writeMirror({schema:1,engineBuild:state.engineBuild,assetVersion:state.assetVersion,revision,run:state.run??null,profile:state.profile??null,savedUtc:new Date().toISOString()});
}
export async function history(){
 const db=await database();
 return new Promise((resolve,reject)=>{const tx=db.transaction('history','readonly'),request=tx.objectStore('history').getAll();tx.oncomplete=()=>resolve(request.result.sort((a,b)=>b.revision-a.revision).map(r=>({revision:r.revision,createdUtc:r.savedUtc,kind:r.kind??null})));tx.onerror=tx.onabort=()=>reject(tx.error);});
}
export async function snapshot(revision){
 const db=await database();
 const record=await new Promise((resolve,reject)=>{const tx=db.transaction('history','readonly'),request=tx.objectStore('history').get(revision);tx.oncomplete=()=>resolve(request.result);tx.onerror=tx.onabort=()=>reject(tx.error);});
 if(!valid(record))throw Object.assign(Error('That recovery snapshot no longer exists.'),{status:404});
 return envelope(record);
}
// Make snapshot `revision` the current save as a new revision and delete the newer snapshots, in one transaction: the
// progress after it is discarded rather than kept as a second branch of history. `config` holds the current game files.
export async function restore(revision,expected,config){
 const db=await database();
 const record=await new Promise((resolve,reject)=>{
  const tx=db.transaction(['current','history'],'readwrite'),current=tx.objectStore('current'),history=tx.objectStore('history');
  let failure=null,result=null;
  const get=current.get('save'),old=history.get(revision);
  old.onsuccess=()=>{
   const existing=get.result,source=old.result;
   if(valid(existing)&&existing.revision!==expected){failure=conflict('A newer save exists in another tab. Reload before saving.');tx.abort();return;}
   if(!valid(source)){failure=Object.assign(Error('That recovery snapshot no longer exists.'),{status:404});tx.abort();return;}
   if(revision===expected){failure=Object.assign(Error('That snapshot is already the current save.'),{status:400});tx.abort();return;}
   if(source.engineBuild!==config.engineBuild||source.assetVersion!==config.assetVersion){failure=Object.assign(Error('That snapshot was made with other game files or another engine version.'),{status:400});tx.abort();return;}
   result={schema:1,engineBuild:source.engineBuild,assetVersion:source.assetVersion,revision:expected+1,run:source.run??null,profile:source.profile??null,savedUtc:new Date().toISOString(),kind:'restore'};
   history.delete(IDBKeyRange.lowerBound(revision,true));
   current.put(result,'save');history.put(result);
   history.delete(IDBKeyRange.upperBound(result.revision-KEEP));
  };
  tx.oncomplete=()=>resolve(result);
  tx.onerror=tx.onabort=()=>reject(failure??tx.error??Error('The browser could not store the save.'));
 });
 writeMirror(record);
 return {revision:record.revision,durable:true};
}
// The same JSON a cloud backup contains, so a backup moves between local and cloud mode unchanged.
export const backup=record=>envelope(record);
export async function remove(){
 try{localStorage.removeItem(MIRROR_KEY);}catch{}
 const db=await database();
 return new Promise((resolve,reject)=>{const tx=db.transaction(['current','history'],'readwrite');tx.objectStore('current').clear();tx.objectStore('history').clear();tx.oncomplete=()=>resolve();tx.onerror=tx.onabort=()=>reject(tx.error);});
}
export async function requestPersistence(){try{return !!await navigator.storage?.persist?.();}catch{return false;}}
export async function storageStatus(){
 const status={supported:typeof navigator.storage?.persist==='function',persisted:null,usage:null,quota:null,mirror:readMirror()!==null};
 try{status.persisted=await navigator.storage.persisted();}catch{}
 try{const estimate=await navigator.storage.estimate();status.usage=estimate.usage??null;status.quota=estimate.quota??null;}catch{}
 return status;
}
// estimate().quota is the most this site may use (Chrome: a share of the whole disk), not free space, so only usage is shown.
export function describeStorage(status){
 const lead=!status.supported?'This browser cannot keep site data persistent. Download backups.'
  :status.persisted?'Persistent storage granted.'
  :'Persistent storage not granted: the browser may clear this site’s data when the disk is low. Download backups.';
 return lead+(status.usage==null?'':' This site uses '+(status.usage/1024/1024).toFixed(1)+' MiB.');
}
