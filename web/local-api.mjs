// Local-only mode answers the paths app.mjs sends to /api/* from this browser instead of the server. The launch,
// save and log code is therefore the same for both modes; only this module and the stores behind it differ.
// Game metadata is the one thing still fetched: anonymous visitors may read exactly what an external user reads.
import * as saves from './local-saves.mjs';
import {LOCAL_ID} from './local-saves.mjs';
import * as logs from './local-logs.mjs';
export {LOCAL_ID};
const MAX_XML=16*1024*1024;
let config,heldLease=null,stolen=false;
const fail=(message,status)=>Object.assign(Error(message),{status});

export async function gameConfig(){
 if(config)return config;
 const r=await fetch('/api/public/game/config',{cache:'no-store'});
 if(!r.ok)throw fail('Game information is unavailable. Reload and retry.',r.status);
 return config=await r.json();
}
function validateXml(xml,root){
 if(xml==null)return;
 if(typeof xml!=='string'||xml.length>MAX_XML)throw fail('Invalid save data',400);
 // A half-written or mangled document must never replace a good save.
 const doc=new DOMParser().parseFromString(xml,'application/xml');
 if(doc.getElementsByTagName('parsererror').length||doc.documentElement?.localName!==root)throw fail('Invalid save XML',400);
}
// A Web Lock stands in for the cloud play lease: one tab per browser profile may play and save at a time.
async function acquire(takeOver){
 if(heldLease&&!stolen&&!takeOver)return {lease:heldLease};
 const token=crypto.randomUUID();
 if(!navigator.locks){heldLease=token;stolen=false;return {lease:token};}
 return new Promise((resolve,reject)=>{
  navigator.locks.request('ftl-local-play',{ifAvailable:!takeOver,steal:takeOver},lock=>{
   if(!lock){reject(fail('This browser is already playing in another tab',409));return;}
   heldLease=token;stolen=false;resolve({lease:token});
   return new Promise(()=>{});  // Held until the page goes away.
  }).catch(e=>{if(e?.name==='AbortError')stolen=true;else reject(e);});
 });
}
function requireLease(lease){if(stolen||(heldLease&&lease!==heldLease))throw fail('Play lease is no longer valid',409);}

export async function localApi(path,{method='GET',body,headers={}}={}){
 const [route,query='']=path.split('?');
 if(route==='/game/config')return gameConfig();
 if(route==='/play-lease/acquire'&&method==='POST')return acquire(!!body?.takeOver);
 if(route==='/play-lease/heartbeat'&&method==='POST'){requireLease(body?.lease);return null;}
 // Save and close: the browser lock ends with the page, which reloads right after.
 if(route==='/play-lease/release'&&method==='POST')return null;
 if(route==='/saves/current'&&method==='GET'){
  const {record}=await saves.load();
  if(record)return saves.backup(record);
  const c=await gameConfig();return {schema:1,engineBuild:c.engineBuild,assetVersion:c.assetVersion,revision:0,run:null,profile:null};
 }
 if(route==='/saves/current'&&method==='PUT'){
  requireLease(body?.lease);
  const state=body?.state,c=await gameConfig();
  if(!state||state.schema!==1||state.engineBuild!==c.engineBuild||state.assetVersion!==c.assetVersion)throw fail('Incompatible save format or assets',400);
  validateXml(state.run,'xftlSaveGame');validateXml(state.profile,'xftl-profile');
  const expected=Number(String(headers['If-Match']??'').replaceAll('"',''));
  if(!Number.isSafeInteger(expected)||expected!==state.revision)throw fail('A newer save exists',409);
  const result=await saves.save(state,expected);
  return {revision:result.revision,durable:result.durable};
 }
 if(route==='/saves/snapshots'&&method==='GET')return saves.history();
 if(route.startsWith('/saves/snapshots/')&&method==='GET')return saves.snapshot(Number(route.split('/').pop()));
 if(route.startsWith('/debug/'))return logs.handle(route,method,body,query);
 throw fail('This is not available in local-only mode.',404);
}
export {logs,saves};
