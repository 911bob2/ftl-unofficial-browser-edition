const sessions=new Map();
let db;
function database(){return db??=new Promise((resolve,reject)=>{const r=indexedDB.open('ftl-local-assets',1);r.onupgradeneeded=()=>r.result.createObjectStore('files');r.onsuccess=()=>{r.result.onversionchange=()=>{r.result.close();db=null;};resolve(r.result);};r.onerror=()=>{db=null;reject(Error('Browser storage is unavailable.'));};r.onblocked=()=>reject(Error('Close other FTL tabs to update browser storage.'));});}
async function stored(mode,account,record,signal){const d=await database();if(signal?.aborted)throw new DOMException('Cancelled','AbortError');return new Promise((resolve,reject)=>{const tx=d.transaction('files',mode==='get'?'readonly':'readwrite'),s=tx.objectStore('files'),r=mode==='get'?s.get(account):mode==='delete'?s.delete(account):s.put(record,account);const abort=()=>tx.abort();signal?.addEventListener('abort',abort,{once:true});const finish=()=>signal?.removeEventListener('abort',abort);tx.oncomplete=()=>{finish();resolve(r.result);};tx.onerror=tx.onabort=()=>{finish();reject(signal?.aborted?new DOMException('Cancelled','AbortError'):Error('There is not enough available browser storage to cache this file.'));};});}
export async function getLocalAsset(account,{strict=false}={}){if(sessions.has(account))return sessions.get(account);try{return await stored('get',account);}catch(e){if(strict)throw e;return null;}}
export async function removeLocalAsset(account){const transient=sessions.delete(account);try{await stored('delete',account);}catch(e){if(!transient)throw e;}}
export async function persistLocalAsset(account,record,signal){await stored('put',account,record,signal);sessions.delete(account);}
export function useSessionAsset(account,record){sessions.set(account,record);}
export function workerTask(action,payload,onProgress=()=>{},signal){
 return new Promise((resolve,reject)=>{
  if(signal?.aborted)return reject(new DOMException('Cancelled','AbortError'));
  const worker=new Worker(new URL('./dat-worker.mjs',import.meta.url),{type:'module'}),id=1;
  const abort=()=>{finish();reject(new DOMException('Cancelled','AbortError'));};
  const finish=()=>{signal?.removeEventListener('abort',abort);worker.terminate();};
  signal?.addEventListener('abort',abort,{once:true});
  worker.onmessage=({data})=>{if(data.id!==id)return;if(data.progress){onProgress(data.progress);return;}finish();if(data.error)reject(Error(data.error));else resolve(data.result);};
  worker.onerror=()=>{finish();reject(Error('Local file preparation failed. Please retry.'));};
  worker.postMessage({id,action,...payload});
 });
}
export async function validateLocalAsset(file,config,onProgress,signal){const index=await workerTask('validate',{file,config},onProgress,signal);return {file,index,importedUtc:new Date().toISOString()};}

export function mountLocalAssets({account,config,onChanged=()=>{},message=()=>{}}){
 const root=document.getElementById('local-assets');if(!root)return;
 root.hidden=config.assetSource!=='local';if(root.hidden)return;
 const status=root.querySelector('[data-local-status]'),input=root.querySelector('input'),cancel=root.querySelector('[data-local-cancel]'),fallback=root.querySelector('[data-local-session]'),remove=root.querySelector('[data-local-remove]'),drop=root.querySelector('[data-local-drop]'),intro=root.querySelector('[data-local-intro]');
 let controller,pending,disposed=false;
 const changed=async()=>{let record;try{record=await getLocalAsset(account,{strict:true});}catch{if(disposed)return;status.textContent='Browser storage is unavailable. Choose a file and use the explicit session-only option.';onChanged(null);return;}if(disposed)return;// A cached file is checked against this deployment's hash, so there is nothing to replace: the picker only
  // returns when no file is cached or the cached one belongs to another version (remove it first or choose again).
  const ready=record?.index.assetVersion===config.assetVersion;drop.hidden=ready;intro.hidden=ready;
  status.textContent=ready?'Ready · '+(record.index.size/1024/1024).toFixed(1)+' MiB · '+(sessions.has(account)?'this session only':'cached in this browser'):record?'The cached ftl.dat is for a different version. Choose the matching file.':'';remove.hidden=!record;onChanged(record);};
 const busy=value=>{root.setAttribute('aria-busy',String(value));input.disabled=value;remove.disabled=value;cancel.hidden=!value;};
 const choose=async file=>{
  if(!file||controller)return;controller=new AbortController();pending=null;fallback.hidden=true;busy(true);
  try{
   pending=await validateLocalAsset(file,config,text=>status.textContent=text,controller.signal);
   status.textContent='Caching in your browser…';
   try{await persistLocalAsset(account,pending,controller.signal);cancel.hidden=true;pending=null;await changed();navigator.storage?.persist?.().catch(()=>{});}
   catch(e){if(e.name==='AbortError')throw e;status.textContent='Your file is verified, but browser caching failed. Use it on this page until it is closed or reloaded.';fallback.hidden=false;}
  }catch(e){if(!disposed)status.textContent=e.name==='AbortError'?'File selection cancelled.':e.message;}
  finally{controller=null;if(!disposed){busy(false);input.value='';}}
 };
 input.onchange=()=>choose(input.files[0]);cancel.onclick=()=>controller?.abort();
 fallback.onclick=async()=>{if(!pending||controller)return;useSessionAsset(account,pending);pending=null;fallback.hidden=true;await changed();};
 remove.onclick=async()=>{try{await removeLocalAsset(account);await changed();}catch(e){message(e.message);}};
 drop.ondragover=e=>{e.preventDefault();drop.classList.add('dragging');};drop.ondragleave=()=>drop.classList.remove('dragging');drop.ondrop=e=>{e.preventDefault();drop.classList.remove('dragging');choose(e.dataTransfer.files[0]);};
 changed();return {refresh:changed,dispose:()=>{disposed=true;pending=null;controller?.abort();input.onchange=cancel.onclick=fallback.onclick=remove.onclick=drop.ondragover=drop.ondragleave=drop.ondrop=null;}};
}
