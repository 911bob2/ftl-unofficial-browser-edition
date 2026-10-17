// Debug-log storage for local-only mode: the same session/record shapes as /api/debug/*, kept in this browser.
// debug.mjs keeps recording into its own queue and "uploads" batches through api(); in local mode that call lands
// here and is stored in IndexedDB instead of on the server. Logs belong to this device and are never uploaded.
import {diagnosticArchive,diagnosticCheckpointFiles} from './diagnostic-export.mjs';
const DB_NAME='ftl-local-logs',QUOTA=64*1024*1024,REDACT=/password|cookie|token|authorization|lease|credential/i;
let dbPromise;
function database(){
 return dbPromise??=new Promise((resolve,reject)=>{
  if(!globalThis.indexedDB)return reject(Error('Browser storage is unavailable.'));
  const request=indexedDB.open(DB_NAME,1);
  request.onupgradeneeded=()=>{request.result.createObjectStore('sessions',{keyPath:'id'});request.result.createObjectStore('records',{keyPath:'key',autoIncrement:true}).createIndex('bySession','session');};
  request.onsuccess=()=>{request.result.onversionchange=()=>{request.result.close();dbPromise=null;};resolve(request.result);};
  request.onerror=()=>reject(request.error);
 }).catch(e=>{dbPromise=null;throw e;});
}
async function run(stores,mode,action){
 const db=await database();
 return new Promise((resolve,reject)=>{const tx=db.transaction(stores,mode);let result;action(tx,value=>result=value);tx.oncomplete=()=>resolve(result);tx.onerror=tx.onabort=()=>reject(tx.error);});
}
const request=(r,done)=>{r.onsuccess=()=>done(r.result);};
const sessions=()=>run(['sessions'],'readonly',(tx,done)=>request(tx.objectStore('sessions').getAll(),done));
const sessionRecords=id=>run(['records'],'readonly',(tx,done)=>request(tx.objectStore('records').index('bySession').getAll(id),done));
const missing=()=>Object.assign(Error('Unknown diagnostic session'),{status:404});
function redact(value,depth=0){
 if(depth>20)return '[depth limit]';if(value==null||typeof value!=='object')return value;
 if(Array.isArray(value))return value.map(v=>redact(v,depth+1));
 return Object.fromEntries(Object.entries(value).map(([k,v])=>[k,REDACT.test(k)?'[redacted]':redact(v,depth+1)]));
}
const text=(value,max)=>typeof value==='string'?value.slice(0,max):'';
async function get(id){const session=await run(['sessions'],'readonly',(tx,done)=>request(tx.objectStore('sessions').get(id),done));if(!session)throw missing();return session;}

async function create(body){
 if(body.continuationOf)await get(body.continuationOf);
 const session={id:crypto.randomUUID().replaceAll('-',''),createdUtc:new Date().toISOString(),updatedUtc:new Date().toISOString(),finishedUtc:null,build:text(body.build,128),device:text(body.device,256),ship:'',advancedEdition:null,continuationOf:body.continuationOf??null,records:0,errors:0,droppedRecords:0,droppedServerRecords:0,lastClientSequence:0,bytes:0};
 await run(['sessions'],'readwrite',tx=>tx.objectStore('sessions').put(session));
 return {id:session.id,createdUtc:session.createdUtc,lastClientSequence:0};
}
// Keep the store inside its quota: whole old sessions go first, then the oldest records of the active one.
async function makeRoom(active,incoming){
 let all=await sessions(),used=all.reduce((n,s)=>n+s.bytes,0);
 if(used+incoming<=QUOTA)return;
 for(const old of all.filter(s=>s.id!==active.id).sort((a,b)=>(a.finishedUtc?0:1)-(b.finishedUtc?0:1)||a.createdUtc.localeCompare(b.createdUtc))){
  if(used+incoming<=QUOTA)break;used-=old.bytes;await remove(old.id);
 }
 if(used+incoming<=QUOTA)return;
 const own=await run(['records'],'readonly',(tx,done)=>request(tx.objectStore('records').index('bySession').getAll(active.id),done));
 let dropped=0;
 await run(['records'],'readwrite',tx=>{const store=tx.objectStore('records');for(const r of own){if(used+incoming<=QUOTA)break;used-=r.bytes;active.bytes-=r.bytes;active.droppedRecords++;dropped++;store.delete(r.key);}});
 return dropped;
}
async function ingest(id,batch){
 if(!Array.isArray(batch?.records)||batch.records.length>512)throw Object.assign(Error('At most 512 records per batch'),{status:400});
 const session=await get(id),stored=[];let last=session.lastClientSequence;
 for(const record of batch.records){
  if(!record||typeof record!=='object'||!Number.isSafeInteger(record.sequence)||record.sequence<1)throw Object.assign(Error('Invalid sequence'),{status:400});
  if(record.sequence<=session.lastClientSequence)continue;
  if(record.sequence<=last)throw Object.assign(Error('Invalid record order or size'),{status:400});
  last=record.sequence;
  const safe={sessionId:id,sequence:record.sequence,source:'client',receivedUtc:new Date().toISOString(),severity:text(record.severity,16),category:text(record.category,64),timestamp:text(record.timestamp,64),tick:record.tick??null,eventName:text(record.eventName,128),payload:record.payload===undefined?null:redact(record.payload)};
  const bytes=JSON.stringify(safe).length;
  stored.push({session:id,index:session.records+stored.length+1+session.droppedRecords,record:safe,bytes});
  if(safe.eventName==='checkpoint.state'&&safe.payload&&typeof safe.payload==='object'){session.ship=text(safe.payload.ship,128);if(typeof safe.payload.ae==='boolean')session.advancedEdition=safe.payload.ae;}
  if(safe.eventName==='build.loaded'&&safe.payload&&typeof safe.payload==='object')session.build=text(safe.payload.build,128);
  if(safe.severity==='error')session.errors++;
 }
 if(stored.length){
  const incoming=stored.reduce((n,r)=>n+r.bytes,0);
  await makeRoom(session,incoming);
  session.records+=stored.length;session.bytes+=incoming;session.lastClientSequence=last;session.updatedUtc=new Date().toISOString();
  await run(['sessions','records'],'readwrite',tx=>{const records=tx.objectStore('records');for(const r of stored)records.add(r);tx.objectStore('sessions').put(session);});
 }
 return {acknowledgedSequence:session.lastClientSequence,droppedRecords:session.droppedRecords,bytes:session.bytes};
}
async function remove(id){
 await run(['sessions','records'],'readwrite',tx=>{
  tx.objectStore('sessions').delete(id);
  const records=tx.objectStore('records'),cursor=records.index('bySession').openKeyCursor(IDBKeyRange.only(id));
  cursor.onsuccess=()=>{const c=cursor.result;if(c){records.delete(c.primaryKey);c.continue();}};
 });
}
async function removeAll(){await run(['sessions','records'],'readwrite',tx=>{tx.objectStore('sessions').clear();tx.objectStore('records').clear();});}
async function list(){
 const all=(await sessions()).sort((a,b)=>b.createdUtc.localeCompare(a.createdUtc));
 return {available:true,usedBytes:all.reduce((n,s)=>n+s.bytes,0),quotaBytes:QUOTA,sessions:all};
}
async function records(id,query){
 const session=await get(id),after=Number(query.get('after')??0),category=query.get('category'),severity=query.get('severity'),search=(query.get('search')??'').toLowerCase(),markers=query.get('markers')==='true';
 const from=query.get('from')?Date.parse(query.get('from')):null,to=query.get('to')?Date.parse(query.get('to')):null;
 const rows=(await sessionRecords(id)).sort((a,b)=>a.index-b.index).filter(r=>{
  const record=r.record,time=Date.parse(record.timestamp||record.receivedUtc);
  return r.index>after&&(!category||record.category===category)&&(!severity||record.severity===severity)&&(!search||JSON.stringify(record).toLowerCase().includes(search))&&(!markers||record.eventName==='issue.mark')&&(from===null||time>=from)&&(to===null||time<=to);
 }).slice(0,200).map(r=>({index:r.index,record:r.record}));
 return {id,droppedRecords:session.droppedRecords,droppedServerRecords:0,records:rows};
}
export async function exportBlob(id){
 const session=await get(id),rows=(await sessionRecords(id)).sort((a,b)=>a.index-b.index).map(r=>r.record);
 const files={'metadata.json':JSON.stringify({id:session.id,build:session.build,device:session.device,ship:session.ship,advancedEdition:session.advancedEdition,continuationOf:session.continuationOf,createdUtc:session.createdUtc,finishedUtc:session.finishedUtc,droppedRecords:session.droppedRecords,records:session.records,errors:session.errors,storage:'browser'},null,2),
  'summary.txt':`FTL debug session ${session.id}\nBuild: ${session.build}\nRecords: ${session.records}\nErrors: ${session.errors}\nRotated records: ${session.droppedRecords}\nStored in this browser only.\n`,
  'records.jsonl':rows.map(r=>JSON.stringify(r)+'\n').join('')};
 for(const [name,xml] of Object.entries(diagnosticCheckpointFiles(rows)))files[name.replace(/^browser\//,'')]=xml;
 return diagnosticArchive(null,files);
}
// The subset of /api/debug/* that debug.mjs uses.
export async function handle(route,method,body,query=''){
 const parts=route.split('/').filter(Boolean),params=new URLSearchParams(query);  // ['debug','sessions',id?,action?]
 if(parts[1]!=='sessions')throw Object.assign(Error('Not found'),{status:404});
 const id=parts[2],action=parts[3];
 if(!id&&method==='POST')return create(body??{});
 if(!id&&method==='GET')return list();
 if(!id&&method==='DELETE'){await removeAll();return null;}
 if(action==='batches'&&method==='POST')return ingest(id,body);
 if(action==='finish'&&method==='POST'){const session=await get(id);session.finishedUtc??=new Date().toISOString();await run(['sessions'],'readwrite',tx=>tx.objectStore('sessions').put(session));return null;}
 if(action==='records'&&method==='GET')return records(id,params);
 if(!action&&method==='DELETE'){await get(id);await remove(id);return null;}
 throw Object.assign(Error('Not found'),{status:404});
}
