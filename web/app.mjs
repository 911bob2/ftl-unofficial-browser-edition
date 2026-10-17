const version=new URL(import.meta.url).search;
const $=id=>document.getElementById(id);let csrf,me,engine,manifest,state,lease,heartbeat,autosave,diagnostics,assetProvider,localPanel,usersUI,blocked=false,syncPromise=Promise.resolve(),lastSaved='';
let L,localUI,accountUI,capturing=false,localTimer;
function message(text){$('message').textContent=text;$('message').hidden=!text;}
const shell=document.body.dataset;
if(shell.authState!=='signed-out')me={id:shell.account,username:$('username').textContent,role:shell.role,admin:shell.role==='admin',mustChangePassword:shell.authState==='password-required'};
if(shell.authState==='local'){L=await import('./local-api.mjs'+version);me={id:L.LOCAL_ID,username:'Local only',role:'local',admin:false,local:true,mustChangePassword:false};}
const routes={play:'launch-panel',account:'account-panel',saves:'saves-panel',logs:'logs-panel',users:'admin-panel',about:'about-panel'};
let sessionGeneration=0,revalidating=false;
function unsignedPanel(){const view=new URL(location.href).searchParams.get('view');return shell.consent!=='1'?'consent-panel':view==='register'?'register-panel':view==='signin'||shell.mode==='cloud'?'login-panel':'mode-panel';}
function destination(){const view=new URL(location.href).searchParams.get('view')??'play';return view==='about'?'about-panel':me?.local&&['register','signin'].includes(view)||!me?unsignedPanel():me.mustChangePassword?'password-panel':view==='users'&&!me.admin||me.local&&['account','users'].includes(view)?'launch-panel':routes[view]??'launch-panel';}
function show(id,{history=true}={}){
 if(!me&&!['login-panel','about-panel','checking-panel','consent-panel','mode-panel','register-panel'].includes(id))id=unsignedPanel();if(me?.local&&['account-panel','admin-panel','password-panel'].includes(id))id='launch-panel';
 if(me?.mustChangePassword&&!['password-panel','about-panel','checking-panel'].includes(id))id='password-panel';
 if(id==='launch-panel'&&engine)id='game-panel';
 shell.panel=id;
 if(id==='saves-panel')localTools().then(t=>t.savesPanel()).catch(()=>{});if(id==='account-panel')accountTools().then(t=>t.refresh()).catch(e=>message(e.message));
 for(const p of document.querySelectorAll('main>section'))p.hidden=p.id!==id;
 for(const [button,panel] of [['play-button',engine?'game-panel':'launch-panel'],['account-button','account-panel'],['saves-button','saves-panel'],['logs-button','logs-panel'],['admin-button','admin-panel'],['about-nav-button','about-panel']]){const current=button==='play-button'?['game-panel','launch-panel'].includes(id):button==='account-button'?['account-panel','password-panel'].includes(id):id===panel;$(button).classList.toggle('btn-active',current);if(current)$(button).setAttribute('aria-current','page');else $(button).removeAttribute('aria-current');}
 const local=$('local-assets');if(local)$(id==='account-panel'?'account-assets':'play-assets').append(local);
 if(history&&['launch-panel','game-panel','account-panel','saves-panel','logs-panel','admin-panel','about-panel'].includes(id)){const view=id==='game-panel'?'play':Object.keys(routes).find(k=>routes[k]===id);const url='/?view='+view;if(location.pathname+location.search!==url)window.history.pushState({},'',url);}
 diagnostics?.record('ui','panel.open',{panel:id});
}
async function optionalAccountSetup(){
 try{
  const current=me,config=await api('/game/config');if(me?.id!==current.id||me.role!==current.role)return;
  $('resource-ready').replaceChildren();const dot=document.createElement('i');dot.className='status-dot';$('resource-ready').append(dot,config.assetSource==='local'?'Your game files stay in this browser':'Game resources ready · cloud saves enabled');
  if(config.assetSource==='local'){const {mountLocalAssets}=await import('./local-assets.mjs');if(me?.id!==current.id||me.role!==current.role)return;localPanel?.dispose();localPanel=mountLocalAssets({account:me.id,config,message,onChanged:record=>{if(me?.id!==current.id||me.role!==current.role)return;$('launch').disabled=!record||record.index.assetVersion!==config.assetVersion;$('resource-ready').lastChild.textContent=record?.index.assetVersion===config.assetVersion?'ftl.dat ready':'ftl.dat required';}});}
  api('/saves/current').then(save=>{if(me?.id===current.id)$('cloud-ready').textContent=save.run?'Saved run ready · '+(me.local?'stored in this browser':'cloud revision '+save.revision):'No active run · start from the hangar';}).catch(()=>{$('cloud-ready').textContent=(me?.local?'Local':'Cloud')+' save status unavailable.';});
  const {createDiagnostics}=await import('./debug.mjs'+version);if(me?.id!==current.id||me.role!==current.role)return;diagnostics??=createDiagnostics({api:(path,options={})=>{if(me?.id!==current.id)return Promise.reject(Error('This account session is no longer active'));return api(path,{...options,headers:{...options.headers,'X-FTL-Account':current.id}});},account:me.id,message,getEngine:()=>engine,exportSource:current.local?L.logs.exportBlob:undefined});await diagnostics.init();
 }catch(e){if(e.status!==401)message('Account tools are temporarily unavailable. '+e.message);}
}
async function authenticationLost(error){sessionGeneration++;if(engine){await pauseForConnection(error);return;}me=null;shell.authState='signed-out';$('identity').hidden=true;document.querySelector('.account-menu').hidden=true;$('credentials-value').textContent='';$('new-credentials').hidden=true;localPanel?.dispose();diagnostics?.dispose();diagnostics=null;show(destination(),{history:false});}
async function token(){csrf=(await (await fetch('/api/auth/csrf',{cache:'no-store'})).json()).token;}
async function fetchAssetBytes(url){const owner=me?.id;const r=await fetch(url,{cache:'no-cache',headers:me?.local?{}:{'X-FTL-Account':owner}});if(!r.ok){const e=Error('Game resources are unavailable. Reconnect and retry.');e.status=r.status;if(r.status===401)await authenticationLost(e);if(r.status===409)revalidate();throw e;}if(owner!==me?.id)throw Error('The active account changed. Reload to continue.');return r.arrayBuffer();}
async function api(path,{method='GET',body,headers={},refreshedCsrf=false,diagnostic=true}={}){
  if(me?.local&&!path.startsWith('/auth')&&!path.startsWith('/onboarding'))return L.localApi(path,{method,body,headers});
  const owner=me?.id,role=me?.role,started=performance.now(),session=diagnostic?diagnostics?.session():null;
  const r=await fetch('/api'+path,{method,headers:{...(!path.startsWith('/auth')&&me&&!me.local?{'X-FTL-Account':me.id}:{}),...(body?{'Content-Type':'application/json'}:{}),...(method!=='GET'?{'X-CSRF-TOKEN':csrf}:{}),...(session?{'X-FTL-Debug-Session':session}:{}),...headers},body:body?JSON.stringify(body):undefined,cache:'no-store'});
  if(r.ok&&!path.startsWith('/auth')&&(owner!==me?.id||role!==me?.role))throw Error('This account changed. Refresh to continue.');
  if(diagnostic&&!path.startsWith('/auth'))diagnostics?.record('http','request.completed',{path:path.split('?')[0],method,status:r.status,durationMs:performance.now()-started},r.status>=500?'error':'info');
  if(!r.ok){const detail=await r.json().catch(()=>null);if(r.status===400&&detail?.error==='Invalid CSRF token'&&method!=='GET'&&!refreshedCsrf){await token();return api(path,{method,body,headers,refreshedCsrf:true,diagnostic});}const e=Error(detail?.error??(r.status===401?'Sign in again to continue':r.status===429?'Too many attempts. Please wait before trying again.':'Request failed'));e.status=r.status;e.code=detail?.code;if(e.code==='account_changed')revalidate();if(r.status===401&&path!=='/auth/login')await authenticationLost(e);throw e;}return r.status===204?null:r.json();
}
function download(value,name){const a=document.createElement('a'),url=URL.createObjectURL(new Blob([JSON.stringify(value,null,2)],{type:'application/json'}));a.href=url;a.download=name;a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);}
let dbPromise;
function database(){return dbPromise??=new Promise((resolve,reject)=>{const r=indexedDB.open('ftl-pending',1);r.onupgradeneeded=()=>r.result.createObjectStore('pending');r.onsuccess=()=>resolve(r.result);r.onerror=()=>reject(r.error);});}
async function pending(mode,value){const db=await database();return new Promise((resolve,reject)=>{const tx=db.transaction('pending',mode==='get'?'readonly':'readwrite'),s=tx.objectStore('pending');const r=mode==='get'?s.get(me.id):mode==='delete'?s.delete(me.id):s.put(value,me.id);tx.oncomplete=()=>resolve(r.result);tx.onerror=()=>reject(tx.error);tx.onabort=()=>reject(tx.error);});}
async function signedIn({navigate=true}={}){
 const generation=++sessionGeneration,current=await api('/auth/me');if(generation!==sessionGeneration)return;
 if(engine&&assetProvider?.source==='server'&&!current.capabilities.serverAssets){await pauseForConnection(Error('Your role now requires local game files. Download pending progress, reload, and choose your ftl.dat.'));return;}if(me&&me.id!==current.id&&engine){await pauseForConnection(Error('The active account changed. Download pending progress before reloading.'));return;}
 if(shell.account&&shell.account!==current.id){location.replace(location.href);return;}me=current;shell.mode='cloud';shell.consent='1';shell.account=me.id;shell.role=me.role;if(navigate)$('launch').disabled=me.role==='external-user';shell.authState=me.mustChangePassword?'password-required':'authenticated';$('username').textContent=me.username;$('identity').hidden=false;document.querySelector('.account-menu').hidden=false;$('admin-button').hidden=!me.admin;
 if(navigate)show(destination(),{history:false});
 await token();if(!me.mustChangePassword){if(navigate){optionalAccountSetup();offerMigration();}if(navigate&&destination()==='admin-panel')admin();}
}
async function signIn(username,password){try{return await api('/auth/login',{method:'POST',body:{username,password}});}catch(e){if(e.status!==409)throw e;await token();return api('/auth/login',{method:'POST',body:{username,password}});}}
$('login-form').addEventListener('submit',async e=>{e.preventDefault();const f=new FormData(e.target);try{await signIn(f.get('username'),f.get('password'));e.target.reset();message('');if(me?.local){location.assign('/');return;}await signedIn();}catch(e){message(e.status===401?'Username or password is incorrect.':e.message);}});
$('password-form').addEventListener('submit',async e=>{e.preventDefault();const f=new FormData(e.target);if(f.get('next')!==f.get('confirm'))return message('The new passwords do not match.');try{if(engine){await queueSync(true);if(blocked)throw Error('Reconnect and save your flight before changing your password.');}await api('/auth/password',{method:'POST',body:{currentPassword:f.get('current'),newPassword:f.get('next')}});await token();await api('/auth/login',{method:'POST',body:{username:me.username,password:f.get('next')}});e.target.reset();if(engine){engine.stop();clearInterval(heartbeat);clearInterval(autosave);location.reload();return;}await signedIn();message('Password changed. Other sessions have been signed out.');}catch(e){message(e.message);}});
async function capture(strict=false){if(!engine)return state;capturing=true;try{engine.save();}catch(e){if(strict)throw e;console.error('Could not capture the engine; preserving the last checkpoint',e);}finally{capturing=false;}return state;}
async function synchronize(){
  await diagnostics?.checkpoint('save');
  const captured=structuredClone(state);await pending('put',captured);$('save-status').textContent='Saving…';
  const data=await api('/saves/current',{method:'PUT',body:{lease,state:captured},headers:{'If-Match':`"${captured.revision}"`}});
  state.revision=data.revision;lastSaved=JSON.stringify([captured.run,captured.profile]);if(JSON.stringify([state.run,state.profile])===lastSaved)await pending('delete');else await pending('put',structuredClone(state));$('save-status').textContent=(data.durable===false?'Saved to backup storage only · ':'Saved · ')+new Date().toLocaleTimeString();
}
function queueSync(force=false,strict=false){syncPromise=syncPromise.catch(()=>{}).then(async()=>{if(blocked)return;await capture(strict);const payload=JSON.stringify([state.run,state.profile]);if(!force&&payload===lastSaved)return;try{await synchronize();}catch(e){await pauseForConnection(e);}});return syncPromise;}
async function pauseForConnection(e){if(!engine||blocked)return;blocked=true;engine.suspend(true);await capture();if(state)await pending('put',structuredClone(state));$('connection-panel').hidden=false;$('connection-message').textContent=e.message;$('save-status').textContent='Pending save · flight paused';}
async function launch(takeOver=false){
  const audioContext=new AudioContext();audioContext.resume().catch(()=>{});
  $('launch').disabled=true;message('');if(me.local)localTools().then(t=>t.requestPersistence()).catch(()=>{});try{
    const {createAssetProvider}=await import('./asset-provider.mjs'+version);
    assetProvider=await createAssetProvider({api,accountId:me.id,fetchBytes:fetchAssetBytes,onProgress:s=>{ $('save-status').textContent=s;$('loading-phase').textContent=s; }});
    manifest=assetProvider.manifest;state=await api('/saves/current');if(state.assetVersion!==manifest.assetVersion||state.engineBuild!==manifest.engineBuild)throw Error('Your '+(me.local?'local':'cloud')+' save needs a compatible engine build before it can be loaded. Download a backup from Saves.');
    diagnostics?.setBuild(manifest.engineBuild);
    const held=await api('/play-lease/acquire',{method:'POST',body:{takeOver,lease}});lease=held.lease;
    const recovery=await pending('get');if(recovery){if(JSON.stringify([recovery.run,recovery.profile])===JSON.stringify([state.run,state.profile]))await pending('delete');else if(recovery.revision!==state.revision||recovery.engineBuild!==manifest.engineBuild||recovery.assetVersion!==manifest.assetVersion){download(recovery,'ftl-pending-recovery.json');message('The cloud save or engine has changed. Your pending progress has been downloaded for recovery.');}else if(confirm('Restore unsynchronized progress from this browser?'))state=recovery;}
    show('game-panel');blocked=false;$('game-loading').hidden=false;document.body.dataset.workload='active';await new Promise(requestAnimationFrame);

    const ae=state.run?state.run.includes('enableAE="true"'):true;
    const {loadGame}=await import('./game.mjs'+version);
    engine=await loadGame({canvas:$('game'),manifest,accountId:me.id,fetchBoot:assetProvider.fetchBoot,fetchAudio:assetProvider.fetchAudio,onProgress:s=>{ $('save-status').textContent=s;$('loading-phase').textContent=s; },onSave:(kind,xml)=>{state[kind]=xml;if(me.local&&!capturing)scheduleLocalSave();pending('put',structuredClone(state)).catch(e=>message('Local recovery storage failed: '+e.message));},run:state.run,profile:state.profile,ae,audioContext});
    $('game-loading').hidden=true;
    engine.debugConfig?.(diagnostics?.enabled()??false);await diagnostics?.checkpoint('engine.start');
    lastSaved=JSON.stringify([state.run,state.profile]);heartbeat=setInterval(async()=>{try{await api('/play-lease/heartbeat',{method:'POST',body:{lease}});}catch(e){await pauseForConnection(e);}},30000);autosave=setInterval(()=>queueSync(),30000);
    window.ftlExit=async()=>{await queueSync(true);engine.stop();clearInterval(heartbeat);clearInterval(autosave);assetProvider?.dispose();location.reload();};
  }catch(e){diagnostics?.record('runtime','engine.launch.failed',{message:e.message},'error');await diagnostics?.checkpoint('engine.launch.failed');assetProvider?.dispose();document.body.dataset.workload='';audioContext.close();console.error('Game launch failed',e);show('launch-panel');if(e.status===409){$('takeover').hidden=false;message(e.message);}else message(e.message);$('launch').disabled=false;}
}
$('launch').addEventListener('click',()=>launch());$('takeover').addEventListener('click',()=>launch(true));
// Save and close: one durable save (it throws when the run could not be written, leaving the game running), then shut down.
async function saveDurably(){
  if(!engine)throw Error('The game is not running.');
  engine.freeze?.(true);
  try{
    engine.saveSettle?.();
    await queueSync(true,true);
    if(blocked)throw Error($('connection-message').textContent||'The save could not be written. Reconnect and try again.');
  }catch(e){if(!blocked)engine.freeze?.(false);throw e;}
}
async function closeGame(){const running=engine;engine=null;running.stop();clearInterval(heartbeat);clearInterval(autosave);assetProvider?.dispose();try{await api('/play-lease/release',{method:'POST',body:{lease}});}catch{}location.reload();}
async function saveAndClose(){
  if(!engine)throw Error('The game is still loading.');
  if(engine.saveKind?.()==='tutorial'&&!confirm('The tutorial cannot be saved. Close the game anyway?'))return false;
  await saveDurably();await closeGame();
}
const {initGameBar}=await import('./gamebar.mjs'+version);initGameBar({$,getEngine:()=>engine,save:()=>queueSync(true),saveAndClose,message});
$('recovery-export').addEventListener('click',async()=>download(await capture(),'ftl-backup.json'));
$('reconnect').addEventListener('click',async()=>{try{const current=me.local?me:await api('/auth/me');if(current.id!==me.id)throw Error('Sign in with the account that owns this flight.');if(assetProvider?.source==='server'&&!current.capabilities.serverAssets)throw Error('Download pending progress, reload, and choose your own ftl.dat for this role.');try{await api('/play-lease/heartbeat',{method:'POST',body:{lease}});}catch(e){if(e.status!==409)throw e;lease=(await api('/play-lease/acquire',{method:'POST',body:{takeOver:false,lease}})).lease;}const cloud=await api('/saves/current');if(cloud.revision!==state.revision)throw Error('A newer save exists. Download pending progress before reloading this page.');blocked=false;await queueSync(true);if(!blocked){engine.suspend(false);$('connection-panel').hidden=true;message('');}}catch(e){message(e.message);}});
window.addEventListener('offline',()=>{if(!me?.local)pauseForConnection(Error('Connection lost. Progress is preserved on this browser.'));});
window.addEventListener('ftl-engine-error',e=>pauseForConnection(Error('The engine stopped: '+(e.detail?.message??e.detail))));
document.addEventListener('visibilitychange',()=>{if(document.hidden&&engine)queueSync(true);});
window.addEventListener('beforeunload',e=>{if(engine){capture();if(blocked||JSON.stringify([state.run,state.profile])!==lastSaved){pending('put',structuredClone(state));e.preventDefault();e.returnValue='';}else pending('delete');}});
$('logout').addEventListener('click',async()=>{try{if(engine){await queueSync(true);if(blocked){download(await capture(),'ftl-pending-progress.json');return message('Pending progress downloaded. Keep it before signing out; reconnect or reload to finish.');}engine.stop();}await api('/auth/logout',{method:'POST'});await pending('delete');await import('./asset-cache.mjs').then(m=>m.clearServerAssets()).catch(()=>{});try{new BroadcastChannel('ftl-session').postMessage('changed');}catch{}location.reload();}catch(e){message(e.message);}});
$('account-button').addEventListener('click',e=>{e.preventDefault();engine?.pause();show('account-panel');});$('change-password').addEventListener('click',()=>{engine?.pause();show('password-panel');});
$('play-button').addEventListener('click',e=>{e.preventDefault();show(engine?'game-panel':'launch-panel');});$('saves-button').addEventListener('click',e=>{e.preventDefault();engine?.pause();show('saves-panel');});$('logs-button').addEventListener('click',e=>{e.preventDefault();engine?.pause();show('logs-panel');diagnostics?.refresh();});for(const id of ['about-button','about-nav-button'])$(id).addEventListener('click',e=>{e.preventDefault();engine?.pause();show('about-panel');});
document.querySelectorAll('.close-panel').forEach(b=>b.addEventListener('click',()=>show(engine?'game-panel':'launch-panel')));
$('backup-download').addEventListener('click',async()=>{try{download(await api('/saves/current'),me.local?'ftl-local-backup.json':'ftl-cloud-backup.json');}catch(e){message(e.message);}});
$('backup-import').addEventListener('change',async e=>{const file=e.target.files[0];if(!file)return;try{if(file.size>16*1024*1024)throw Error('Backup exceeds 16 MiB.');const imported=JSON.parse(await file.text());const cloud=await api('/saves/current');const assets=await api('/game/config');if(imported.schema!==1||imported.assetVersion!==assets.assetVersion||imported.engineBuild!==assets.engineBuild)throw Error('Backup format, assets, or engine version do not match.');if(!confirm(me.local?'Replace the save in this browser with the backup? Your last ten saves stay available as recovery snapshots.':'Replace this account’s cloud profile and run with the backup? A recovery snapshot will be retained.'))return;const acquired=await api('/play-lease/acquire',{method:'POST',body:{takeOver:false,lease}});await api('/saves/current',{method:'PUT',body:{lease:acquired.lease,state:{...imported,revision:cloud.revision}},headers:{'If-Match':`"${cloud.revision}"`}});location.reload();}catch(e){message(e.message);}finally{e.target.value='';}});
$('snapshots-refresh').addEventListener('click',async()=>{try{$('snapshots').replaceChildren();for(const s of await api('/saves/snapshots')){const b=document.createElement('button');b.className='btn';b.textContent='Download revision '+s.revision+' · '+new Date(s.createdUtc).toLocaleString();b.onclick=async()=>download(await api('/saves/snapshots/'+s.revision),'ftl-snapshot-'+s.revision+'.json');$('snapshots').append(b);}}catch(e){message(e.message);}});
async function admin(){try{show('admin-panel');if(!usersUI){const {createUsersUI}=await import('./users.mjs'+version);usersUI=createUsersUI({api,getMe:()=>me,message});}await usersUI.refresh();}catch(e){message(e.message);}}
$('admin-button').addEventListener('click',e=>{e.preventDefault();engine?.pause();admin();});
async function openTests(e){e?.preventDefault();try{engine?.pause();if(engine){await queueSync(true);if(blocked)throw Error('Reconnect and save before opening tests.');}location.assign('/Diagnostics');}catch(e){message(e.message);}}
for(const id of ['tests-button','launch-tests'])$(id).addEventListener('click',openTests);
for(const a of document.querySelectorAll('[data-view]'))a.onclick=e=>{e.preventDefault();engine?.pause();show(routes[a.dataset.view]);};
window.addEventListener('popstate',()=>{engine?.pause();show(destination(),{history:false});if(destination()==='admin-panel')admin();});
async function revalidate(){if(revalidating||me?.local)return;revalidating=true;try{await signedIn({navigate:false});}catch(e){if(e.status===401){sessionGeneration++;if(engine)await pauseForConnection(e);else{me=null;shell.authState='signed-out';$('identity').hidden=true;document.querySelector('.account-menu').hidden=true;show('login-panel',{history:false});}}else message('Connection interrupted. Your session will be checked again when connected.');}finally{revalidating=false;}}
window.addEventListener('pageshow',e=>{if(e.persisted)revalidate();});window.addEventListener('focus',()=>{if(me)revalidate();});
try{const channel=new BroadcastChannel('ftl-session');channel.onmessage=()=>revalidate();}catch{}
$('session-retry').onclick=()=>initialize();
async function initialize(){if(me?.local)return enterLocal();try{await signedIn();}catch(e){if(e.status===401){me=null;shell.authState='signed-out';$('identity').hidden=true;document.querySelector('.account-menu').hidden=true;show(destination(),{history:false});await token().catch(()=>{});}else{shell.authState='connection-error';show('checking-panel',{history:false});$('checking-message').textContent='Could not connect. Retry to check your account.';$('session-retry').hidden=false;}}}
const localTools=()=>localUI??=import('./local-mode.mjs'+version).then(m=>m.mountLocalMode({api,getMe:()=>me,message,download,isPlaying:()=>!!engine,acquireLease:async()=>lease=(await api('/play-lease/acquire',{method:'POST',body:{takeOver:false,lease}})).lease}));
const accountTools=()=>accountUI??=import('./account.mjs'+version).then(m=>m.mountAccount({api,getMe:()=>me,message,download,beforeDeletion:async()=>{if(engine){engine.stop();engine=null;clearInterval(heartbeat);clearInterval(autosave);}await pending('delete').catch(()=>{});await import('./local-assets.mjs').then(a=>a.removeLocalAsset(me.id)).catch(()=>{});}}));
function offerMigration(){try{if(localStorage.getItem('ftl-enable-cloud'))localTools().then(t=>t.offer()).catch(()=>{});}catch{}}
function scheduleLocalSave(){clearTimeout(localTimer);localTimer=setTimeout(()=>queueSync(),2500);}
// Local mode keeps the save in this browser: commit shortly after the engine saves, and copy it to localStorage as the page closes.
function flushLocal(){if(!me?.local||!engine||!state)return;capture();if(JSON.stringify([state.run,state.profile])!==lastSaved)L.saves.flushMirror(state,state.revision+1);}
window.addEventListener('pagehide',flushLocal);document.addEventListener('visibilitychange',()=>{if(document.hidden)flushLocal();});
// One awaitable "save durably now" for both backends: resolves after the cloud write or the local IndexedDB commit finished.
window.ftlSaveDurably=async()=>{if(!engine)return {saved:false,reason:'no-game'};await queueSync(true);if(blocked)throw Error('The save could not be stored. '+($('connection-message').textContent||'Retry or download pending progress.'));return {saved:true,backend:me.local?'local':'cloud',revision:state.revision};};
async function enterLocal(){show(destination(),{history:false});$('reconnect').textContent='Retry saving';$('takeover').textContent='Take over from another tab';await token().catch(()=>{});optionalAccountSetup();localTools().then(t=>t.launcherStatus()).catch(()=>{});}
const {mountOnboarding}=await import('./onboarding.mjs'+version);mountOnboarding({api,show,shell,message,token,signedIn,getMe:()=>me});
await import('./shell.mjs'+version);
await initialize();document.body.dataset.sessionReady='true';
try{const flash=sessionStorage.getItem('ftl-flash');if(flash){sessionStorage.removeItem('ftl-flash');message(flash);}}catch{}
document.addEventListener('click',async e=>{const link=e.target.closest('a[href]');if(!engine||e.defaultPrevented||!link||e.button||e.ctrlKey||e.metaKey||e.shiftKey||e.altKey||link.target==='_blank')return;e.preventDefault();engine.pause();await queueSync(true);if(blocked)return message('Reconnect and save before leaving this page.');location.assign(link.href);});
