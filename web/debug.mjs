// Diagnostic persistence is independent of the game's save/recovery database.
import {diagnosticArchive,diagnosticCheckpointFiles} from './diagnostic-export.mjs';
const LIMIT=64*1024*1024,MAX_RECORD=256*1024,$=id=>document.getElementById(id);
let dbPromise;
function database(){return dbPromise??=new Promise((resolve,reject)=>{const r=indexedDB.open('ftl-diagnostics',1);r.onupgradeneeded=()=>{r.result.createObjectStore('records',{keyPath:'key',autoIncrement:true});r.result.createObjectStore('meta');};r.onsuccess=()=>resolve(r.result);r.onerror=()=>reject(r.error);});}
async function transaction(stores,mode,action){const db=await database();return new Promise((resolve,reject)=>{const tx=db.transaction(stores,mode);let result;action(tx,value=>result=value);tx.oncomplete=()=>resolve(result);tx.onerror=()=>reject(tx.error);tx.onabort=()=>reject(tx.error);});}
const all=()=>transaction(['records'],'readonly',(tx,done)=>{const r=tx.objectStore('records').getAll();r.onsuccess=()=>done(r.result);});
const meta=(key,value)=>transaction(['meta'],value===undefined?'readonly':'readwrite',(tx,done)=>{const s=tx.objectStore('meta'),r=value===undefined?s.get(key):s.put(value,key);r.onsuccess=()=>done(r.result);});
const bytes=value=>new TextEncoder().encode(JSON.stringify(value)).length;
const readable=n=>(n/1024/1024).toFixed(1)+' MiB';
function safe(value,depth=0){if(depth>20)return '[depth limit]';if(value==null||typeof value!=='object')return value;if(Array.isArray(value))return value.map(v=>safe(v,depth+1));return Object.fromEntries(Object.entries(value).map(([k,v])=>[k,/password|cookie|token|authorization|lease|credential/i.test(k)?'[redacted]':safe(v,depth+1)]));}
export function createDiagnostics({api,account,message,getEngine,build='loading',exportSource}){
  let disposed=false;const listeners=new AbortController();
  let session=null,sequence=0,enabled=false,writing=Promise.resolve(),uploading=false,memory=[],memoryBytes=0,pendingBytes=0,dropped=0,droppedRanges=[],status='Stopped',tick=0,activeDetail=null,after=0,previousState=null;
  function lost(records,reason){for(const item of records){if(item.account&&item.account!==account)continue;const id=item.session??item.record.sessionId,n=item.record.sequence,last=droppedRanges.at(-1);dropped++;if(last?.session===id&&last.reason===reason&&last.to+1===n)last.to=n;else if(droppedRanges.length<32)droppedRanges.push({session:id,from:n,to:n,reason});}}
  const update=()=>{if(disposed)return;for(const id of ['debug-enabled','game-debug'])$(id).checked=enabled;$('debug-indicator').hidden=!enabled;$('debug-status').textContent=status;$('debug-stop').disabled=!enabled;getEngine()?.debugConfig?.(enabled);};
  const enqueue=()=>{if(!memory.length)return writing;const records=memory.splice(0);memoryBytes=0;const id=session,persistedSequence=records.at(-1).record.sequence;
    const incoming=records.reduce((n,r)=>n+r.bytes,0);
    if(pendingBytes+incoming>4*1024*1024){lost(records,'Browser write backlog');return writing;}
    pendingBytes+=incoming;
    writing=writing.catch(()=>{}).then(async()=>{if(!id)return;const stored=await all();let used=stored.reduce((n,r)=>n+r.bytes,0),removed=[];const incoming=records.reduce((n,r)=>n+r.bytes,0);
      for(const r of stored){if(used+incoming<=LIMIT)break;used-=r.bytes;removed.push(r);}
      lost(removed,'Browser queue limit');
      await transaction(['records','meta'],'readwrite',tx=>{const s=tx.objectStore('records');for(const r of removed)s.delete(r.key);for(const r of records)s.add({account,session:id,...r});tx.objectStore('meta').put({id,sequence:persistedSequence,enabled,build},account);});
    }).catch(e=>{lost(records,'Browser storage write failed');status='Browser storage failed: '+e.message;update();}).finally(()=>pendingBytes-=incoming);return writing;
  };
  function record(category,eventName,payload={},severity='info'){
    if(disposed||!enabled||!session)return;
    try{const record={sessionId:session,sequence:++sequence,source:'client',timestamp:new Date().toISOString(),tick,category,eventName,severity,payload:safe(payload)};
      let size=bytes(record);if(size>MAX_RECORD){record.payload={truncated:true,originalBytes:size,reason:'Record exceeds 256 KiB'};size=bytes(record);}
      size+=bytes({account,session})+128;
      memory.push({record,bytes:size});memoryBytes+=size;if(memoryBytes>=1024*1024)enqueue();
    }catch{dropped++;}
  }
  async function checkpoint(reason){if(!enabled)return;const id=crypto.randomUUID();record('diagnostic','checkpoint.start',{id,reason});try{const e=getEngine(),state=e?.debugSnapshot?.();if(state)record('diagnostic','checkpoint.state',JSON.parse(state));
    for(const [name,text] of [['run',e?.diagnosticRun?.()??e?.runSave?.()],['profile',e?.diagnosticProfile?.()??e?.profileSave?.()]])if(text){const parts=Math.ceil(text.length/32768);for(let i=0;i<parts;i++)record('diagnostic','checkpoint.part',{id,reason,name,part:i,parts,data:text.slice(i*32768,(i+1)*32768)});}
    record('diagnostic','checkpoint.end',{id,reason});}catch(e){record('diagnostic','checkpoint.failed',{id,message:e.message},'error');}await enqueue();}
  async function flush(){await enqueue();if(disposed||uploading)return;uploading=true;try{
    let queued=(await all()).filter(r=>r.account===account),i=0,batchLimit=256;
    while(i<queued.length){const id=queued[i].session,batch=[];let size=0;while(i<queued.length&&queued[i].session===id&&batch.length<batchLimit&&size+queued[i].bytes<1024*1024){batch.push(queued[i]);size+=queued[i++].bytes;}
      let ack;try{ack=await api('/debug/sessions/'+id+'/batches',{method:'POST',body:{records:batch.map(r=>r.record)},diagnostic:false});}
      catch(e){
        if(e.status===409&&e.code==='session_full'){
          if(batch.length>1){i-=batch.length;batchLimit=Math.max(1,Math.floor(batch.length/2));continue;}
          const created=await api('/debug/sessions',{method:'POST',body:{build,device:navigator.userAgent,continuationOf:id},diagnostic:false});
          const active=id===session;
          if(active){session=created.id;previousState=null;}
          await enqueue();
          await transaction(['records'],'readwrite',tx=>{const r=tx.objectStore('records').openCursor();r.onsuccess=()=>{const c=r.result;if(!c)return;if(c.value.account===account&&c.value.session===id){const value=c.value;value.session=created.id;value.record.sessionId=created.id;c.update(value);}c.continue();};});
          const finished=await meta(account+':finished')??[];
          // Persist completion before another request: a disconnect between
          // rotation and /finish must not leave the old session active forever.
          if(!finished.includes(id))finished.push(id);
          if(!active&&!finished.includes(created.id))finished.push(created.id);
          await meta(account+':finished',finished);
          if(active){await meta(account,{id:session,sequence,enabled,build});record('diagnostic','session.continued',{previousSession:id});await checkpoint('session.continued');}
          queued=(await all()).filter(r=>r.account===account);i=0;batchLimit=256;continue;
        }
        if(e.status!==404)throw e;await transaction(['records'],'readwrite',tx=>{for(const r of batch)tx.objectStore('records').delete(r.key);});lost(batch,'Server session rotated or deleted');if(id===session){session=null;await start();}continue;
      }
      await transaction(['records'],'readwrite',tx=>{for(const r of batch)if(r.record.sequence<=ack.acknowledgedSequence)tx.objectStore('records').delete(r.key);});
    }
    const finished=await meta(account+':finished')??[];for(const id of finished){try{await api('/debug/sessions/'+id+'/finish',{method:'POST',diagnostic:false});}catch(e){if(e.status!==404)throw e;}}await meta(account+':finished',[]);
    if(dropped){const count=dropped,ranges=droppedRanges;dropped=0;droppedRanges=[];record('diagnostic','records.dropped',{count,ranges},'warn');}
    status=enabled?'Recording · synchronized':'Stopped';
  }catch(e){status=(enabled?'Recording · ':'')+'Upload pending: '+e.message;}finally{uploading=false;update();}}
  async function start(){const created=await api('/debug/sessions',{method:'POST',body:{build,device:navigator.userAgent},diagnostic:false});session=created.id;sequence=0;previousState=null;enabled=true;status='Recording';await meta(account,{id:session,sequence,enabled,build});update();record('diagnostic','session.start',{build});await checkpoint('session.start');}
  async function setEnabled(value){try{localStorage.setItem('ftl-debug-enabled',String(value));if(value&&!enabled){await start();}else if(!value&&enabled){await checkpoint('session.stop');record('diagnostic','session.stop');await enqueue();enabled=false;const id=session;session=null;await meta(account,{id,sequence,enabled:false,build});const finished=await meta(account+':finished')??[];if(!finished.includes(id))finished.push(id);await meta(account+':finished',finished);update();}await flush();}catch(e){status='Logging unavailable: '+e.message;enabled=false;update();message(status);}}
  async function mark(){if(!enabled)return message('Enable debug logging before marking an issue.');record('diagnostic','issue.mark',{label:'Issue '+new Date().toLocaleTimeString()});await checkpoint('issue.mark');await flush();message('Issue marked in the debug log.');}
  async function exportSession(id){
    if(!id)return;
    if(id===session)await checkpoint('export');
    await enqueue();
    // Keep a copy before flushing: records can be acknowledged after the server
    // starts its export. Include their identity so a reader can deduplicate them.
    const pending=(await all()).filter(r=>r.account===account&&r.session===id).map(r=>r.record);
    await flush();let server=null,failure=null;
    try{const r=exportSource?{ok:true,blob:()=>exportSource(id)}:await fetch('/api/debug/sessions/'+encodeURIComponent(id)+'/export',{cache:'no-store'});if(!r.ok)throw Error('Log export failed ('+r.status+')');server=await r.blob();}catch(e){failure=e.message;}
    if(!server&&!pending.length)throw Error(failure??'No retained records are available for export');
    const files={};
    if(pending.length){
      files['browser/pending.jsonl']=pending.map(r=>JSON.stringify(r)+'\n').join('');
      files['browser/metadata.json']=JSON.stringify({sessionId:id,build,exportedUtc:new Date().toISOString(),serverIncluded:!!server,serverError:failure,
        pendingRecords:pending.length,firstSequence:pending[0].sequence,lastSequence:pending.at(-1).sequence,droppedRecords:dropped,droppedRanges,
        deduplication:'Client records are identified by sessionId and sequence; the server archive may contain the same acknowledged records.'},null,2);
      files['browser/summary.txt']=server?'Browser records captured before export; deduplicate against the server log by sessionId and sequence.\n':
        'Partial offline export. Contains pending browser records and available checkpoints. Previously acknowledged server history is unavailable.\n';
      Object.assign(files,diagnosticCheckpointFiles(pending));
    }
    const blob=pending.length?await diagnosticArchive(server,files):server;
    const url=URL.createObjectURL(blob),a=document.createElement('a');a.href=url;a.download='ftl-debug-'+id+(server?'':'-offline')+'.zip';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);
    if(!server)message('Exported pending records and checkpoints. Server history was unavailable.');
  }
  async function refresh(){try{const data=await api('/debug/sessions',{diagnostic:false});$('log-usage').textContent=data.available===false?'Diagnostic storage is unavailable':readable(data.usedBytes)+' of '+readable(data.quotaBytes)+' retained'+(exportSource?' · stored in this browser only, never uploaded':'');$('log-sessions').replaceChildren();
    if(!data.sessions.length){const tr=document.createElement('tr'),td=document.createElement('td');td.colSpan=7;td.textContent='No diagnostic sessions';tr.append(td);$('log-sessions').append(tr);}
    for(const s of data.sessions){const tr=document.createElement('tr'),td=document.createElement('td'),check=document.createElement('input');check.type='checkbox';check.className='checkbox checkbox-sm';check.dataset.session=s.id;check.setAttribute('aria-label','Select session '+new Date(s.createdUtc).toLocaleString());td.append(check);tr.append(td);
      for(const text of [new Date(s.createdUtc).toLocaleString(),s.finishedUtc?'Completed':'Active',s.build+(s.ship?' · '+s.ship+' · AE '+(s.advancedEdition?'on':'off'):''),readable(s.bytes),String(s.errors)]){const cell=document.createElement('td');cell.textContent=text;tr.append(cell);}
      const actions=document.createElement('td');for(const [text,handler] of [['Open',()=>detail(s.id,true)],['Export',()=>exportSession(s.id)]]){const b=document.createElement('button');b.className='btn btn-xs';b.textContent=text;b.onclick=()=>handler().catch(e=>message(e.message));actions.append(b);}tr.append(actions);$('log-sessions').append(tr);}
  }catch(e){message(e.message);}}
  async function detail(id,reset=false){activeDetail=id;if(reset){after=0;$('log-records').textContent='';}const query=new URLSearchParams({after:String(after),search:$('log-search').value,category:$('log-category').value,severity:$('log-severity').value,markers:String($('log-markers').checked)});for(const name of ['from','to'])if($('log-'+name).value)query.set(name,new Date($('log-'+name).value).toISOString());const data=await api('/debug/sessions/'+id+'/records?'+query,{diagnostic:false});$('log-detail').hidden=false;$('log-truncation').textContent=[data.droppedRecords?data.droppedRecords+' records removed by rotation.':'',data.droppedServerRecords?data.droppedServerRecords+' server records dropped.':''].filter(Boolean).join(' ');const added=data.records.map(r=>{after=r.index;return JSON.stringify(r.record)+'\n';}).join('');let text=$('log-records').textContent+added;if(text.length>2*1024*1024){const at=text.indexOf('\n',text.length-2*1024*1024);text='[Earlier records omitted from this view; export the session for retained history.]\n'+text.slice(at+1);}$('log-records').textContent=text;$('log-more').disabled=data.records.length<200;}
  for(const id of ['debug-enabled','game-debug'])$(id).onchange=e=>setEnabled(e.target.checked);
  $('debug-stop').onclick=()=>setEnabled(false);for(const id of ['debug-mark','logs-mark'])$(id).onclick=mark;
  $('logs-refresh').onclick=refresh;
  $('logs-delete').onclick=async()=>{const ids=[...document.querySelectorAll('[data-session]:checked')].map(e=>e.dataset.session);if(!ids.length)return;if(!confirm('Delete selected diagnostic sessions?'))return;if(ids.includes(session))await setEnabled(false);await flush();for(const id of ids)await api('/debug/sessions/'+id,{method:'DELETE',diagnostic:false});await refresh();};
  $('logs-delete-all').onclick=async()=>{if(!confirm('Delete all diagnostic logs for your account?'))return;await setEnabled(false);await transaction(['records','meta'],'readwrite',tx=>{const r=tx.objectStore('records').openCursor();r.onsuccess=()=>{const c=r.result;if(c){if(c.value.account===account)c.delete();c.continue();}};tx.objectStore('meta').delete(account);});await api('/debug/sessions',{method:'DELETE',diagnostic:false});await refresh();};
  $('log-filter').onsubmit=e=>{e.preventDefault();if(activeDetail)detail(activeDetail,true).catch(e=>message(e.message));};$('log-more').onclick=()=>detail(activeDetail).catch(e=>message(e.message));
  window.ftlDebugEvent=(category,event,payload,severity)=>{let parsed=payload;try{if(typeof payload==='string')parsed=JSON.parse(payload);}catch{parsed={message:payload};}
    if(category==='simulation'&&event==='state'){tick=parsed.state.tick;const changes={};for(const [key,value] of Object.entries(parsed.state))if(JSON.stringify(previousState?.[key])!==JSON.stringify(value))changes[key]=value;previousState=parsed.state;parsed={dt:parsed.dt,changes};}
    record(category,event,parsed,severity);};
  window.ftlDebugMark=mark;
  window.addEventListener('error',e=>{record('runtime','uncaught.error',{message:e.message,file:e.filename?.split('?')[0],line:e.lineno},'error');checkpoint('runtime.error');},{signal:listeners.signal});
  window.addEventListener('unhandledrejection',e=>{record('runtime','unhandled.rejection',{message:String(e.reason?.message??e.reason)},'error');checkpoint('runtime.rejection');},{signal:listeners.signal});
  window.addEventListener('online',flush,{signal:listeners.signal});document.addEventListener('visibilitychange',()=>{enqueue();if(document.hidden)flush();},{signal:listeners.signal});const interval=setInterval(()=>{enqueue();flush();},2000);
  async function init(){try{const saved=await meta(account);if(disposed)return;if(saved?.enabled&&localStorage.getItem('ftl-debug-enabled')==='true'){session=saved.id;sequence=saved.sequence;enabled=true;status='Recording';update();record('diagnostic','session.resume');await checkpoint('reload');await flush();}else if(localStorage.getItem('ftl-debug-enabled')==='true')await setEnabled(true);else{update();await flush();}}catch(e){status='Logging unavailable: '+e.message;update();}}
  return {dispose(){enqueue().catch(()=>{});disposed=true;enabled=false;clearInterval(interval);listeners.abort();},init,record,checkpoint,flush,refresh,mark,setBuild:value=>{build=value;record('diagnostic','build.loaded',{build});},setTick:value=>tick=value,enabled:()=>enabled,session:()=>enabled?session:null};
}
