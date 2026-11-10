const version=new URL(import.meta.url).search;
const $=id=>document.getElementById(id);let csrf,me,engine,manifest,diagnostics,assetProvider,localPanel,usersUI;
let L,accountUI,modUI,savesPromise=null,saves=null,session=null,missingTimer=null;
const modsEnabledModule=import('./mods-enabled.mjs'+version);// the enabled-mods mirror for the one Launch button (no mod code)
const savesUiModule=()=>import('./saves-ui.mjs'+version),dialogsModule=()=>import('./dialogs.mjs'+version);
function message(text){$('message').textContent=text;$('message').hidden=!text;}
const shell=document.body.dataset;
if(shell.authState!=='signed-out')me={id:shell.account,username:$('username').textContent,role:shell.role,admin:shell.role==='admin',mustChangePassword:shell.authState==='password-required'};
if(shell.authState==='local'){L=await import('./local-api.mjs'+version);me={id:L.LOCAL_ID,username:'Local only',role:'local',admin:false,local:true,mustChangePassword:false};}
const routes={play:'launch-panel',account:'account-panel',saves:'saves-panel',logs:'logs-panel',users:'admin-panel',about:'about-panel'};
let sessionGeneration=0,revalidating=false;
// The page's saves screens are built here (save model 2, docs/SAVES.md): the Saves panel, the game's stop notice.
buildSavesPanel();buildStopPanel();$('migrate-dialog')?.remove();$('takeover')?.remove();
function unsignedPanel(){const view=new URL(location.href).searchParams.get('view');return shell.consent!=='1'?'consent-panel':view==='register'?'register-panel':view==='signin'||shell.mode==='cloud'?'login-panel':'mode-panel';}
function destination(){const view=new URL(location.href).searchParams.get('view')??'play';return view==='about'?'about-panel':me?.local&&['register','signin'].includes(view)||!me?unsignedPanel():me.mustChangePassword?'password-panel':view==='users'&&!me.admin||me.local&&['account','users'].includes(view)?'launch-panel':routes[view]??'launch-panel';}
function show(id,{history=true}={}){
 if(!me&&!['login-panel','about-panel','checking-panel','consent-panel','mode-panel','register-panel'].includes(id))id=unsignedPanel();if(me?.local&&['account-panel','admin-panel','password-panel'].includes(id))id='launch-panel';
 if(me?.mustChangePassword&&!['password-panel','about-panel','checking-panel'].includes(id))id='password-panel';
 if(id==='launch-panel'&&engine)id='game-panel';
 shell.panel=id;
 if(id==='saves-panel'&&me&&!me.mustChangePassword)savesPage().catch(e=>message(e.message));if(id==='launch-panel'&&me&&!me.mustChangePassword){refreshLaunch();if(modUI&&$('mods-panel').open)modUI.then(u=>u.refresh()).catch(()=>{});}if(id==='account-panel')accountTools().then(t=>t.refresh()).catch(e=>message(e.message));
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
  const {createDiagnostics}=await import('./debug.mjs'+version);if(me?.id!==current.id||me.role!==current.role)return;diagnostics??=createDiagnostics({api:(path,options={})=>{if(me?.id!==current.id)return Promise.reject(Error('This account session is no longer active'));return api(path,{...options,headers:{...options.headers,'X-FTL-Account':current.id}});},account:me.id,message,getEngine:()=>engine,exportSource:current.local?L.logs.exportBlob:undefined});await diagnostics.init();
 }catch(e){if(e.status!==401)message('Account tools are temporarily unavailable. '+e.message);}
}
// Signed out while a game runs: the game goes on, its saves stay in this browser and sync after the next sign-in.
async function authenticationLost(error){sessionGeneration++;if(engine){$('save-status').textContent='Signed out: saves stay in this browser';return;}me=null;shell.authState='signed-out';$('identity').hidden=true;document.querySelector('.account-menu').hidden=true;$('credentials-value').textContent='';$('new-credentials').hidden=true;localPanel?.dispose();diagnostics?.dispose();diagnostics=null;show(destination(),{history:false});}
async function token(){csrf=(await (await fetch('/api/auth/csrf',{cache:'no-store'})).json()).token;}
// With onProgress the body is read as a stream into one buffer of the expected length (the bundle arrives gzip-encoded,
// so Content-Length is not the byte count), reporting the bytes received a few times per second.
async function fetchAssetBytes(url,{onProgress,length}={}){const owner=me?.id;const r=await fetch(url,{cache:'no-cache',headers:me?.local?{}:{'X-FTL-Account':owner}});if(!r.ok){const e=Error('Game resources are unavailable. Reconnect and retry.');e.status=r.status;if(r.status===401)await authenticationLost(e);if(r.status===409)revalidate();throw e;}if(owner!==me?.id)throw Error('The active account changed. Reload to continue.');
 if(!onProgress||!r.body)return r.arrayBuffer();
 const reader=r.body.getReader();let out=length>0?new Uint8Array(length):null,chunks=[],done=0,last=0;
 for(;;){const {value,done:end}=await reader.read();if(end)break;if(out&&done+value.length<=out.length)out.set(value,done);else{if(out){chunks.push(out.subarray(0,done));out=null;}chunks.push(value);}done+=value.length;const now=performance.now();if(now-last>100){last=now;onProgress(done);}}
 onProgress(done);if(owner!==me?.id)throw Error('The active account changed. Reload to continue.');
 if(out)return done===out.length?out.buffer:out.buffer.slice(0,done);
 const all=new Uint8Array(done);let o=0;for(const c of chunks){all.set(c,o);o+=c.length;}return all.buffer;}
// Errors carry status, code and detail (the JSON body: a save lock's holder, a revision); a failed network has no status.
async function api(path,{method='GET',body,headers={},refreshedCsrf=false,diagnostic=true}={}){
  if(me?.local&&!path.startsWith('/auth')&&!path.startsWith('/onboarding'))return L.localApi(path,{method,body,headers});
  const owner=me?.id,role=me?.role,started=performance.now(),session=diagnostic?diagnostics?.session():null;
  const r=await fetch('/api'+path,{method,headers:{...(!path.startsWith('/auth')&&me&&!me.local?{'X-FTL-Account':me.id}:{}),...(body!=null?{'Content-Type':'application/json'}:{}),...(method!=='GET'?{'X-CSRF-TOKEN':csrf}:{}),...(session?{'X-FTL-Debug-Session':session}:{}),...headers},body:body!=null?JSON.stringify(body):undefined,cache:'no-store'});
  if(r.ok&&!path.startsWith('/auth')&&(owner!==me?.id||role!==me?.role))throw Error('This account changed. Refresh to continue.');
  if(diagnostic&&!path.startsWith('/auth'))diagnostics?.record('http','request.completed',{path:path.split('?')[0],method,status:r.status,durationMs:performance.now()-started},r.status>=500?'error':'info');
  if(!r.ok){const detail=await r.json().catch(()=>null);if(r.status===400&&detail?.error==='Invalid CSRF token'&&method!=='GET'&&!refreshedCsrf){await token();return api(path,{method,body,headers,refreshedCsrf:true,diagnostic});}const e=Error(detail?.error??(r.status===401?'Sign in again to continue':r.status===429?'Too many attempts. Please wait before trying again.':'Request failed'));e.status=r.status;e.code=detail?.code;e.detail=detail;if(e.code==='account_changed')revalidate();if(r.status===401&&path!=='/auth/login')await authenticationLost(e);throw e;}return r.status===204?null:r.json();
}
function download(value,name){const a=document.createElement('a'),url=URL.createObjectURL(new Blob([JSON.stringify(value,null,2)],{type:'application/json'}));a.href=url;a.download=name;a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);}
async function signedIn({navigate=true}={}){
 const generation=++sessionGeneration,current=await api('/auth/me');if(generation!==sessionGeneration)return;
 if(engine&&assetProvider?.source==='server'&&!current.capabilities.serverAssets){await haltGame('Your role now requires local game files. Your progress is saved in this browser; reload and choose your ftl.dat.');return;}if(me&&me.id!==current.id&&engine){await haltGame('The active account changed. Your progress is saved in this browser for the account that played it.');return;}
 if(shell.account&&shell.account!==current.id){location.replace(location.href);return;}me=current;shell.mode='cloud';shell.consent='1';shell.account=me.id;shell.role=me.role;if(navigate)$('launch').disabled=me.role==='external-user';shell.authState=me.mustChangePassword?'password-required':'authenticated';$('username').textContent=me.username;$('identity').hidden=false;document.querySelector('.account-menu').hidden=false;$('admin-button').hidden=!me.admin;
 if(navigate)show(destination(),{history:false});
 await token();if(!me.mustChangePassword){if(navigate){optionalAccountSetup();startSaves().catch(e=>message('Saves are unavailable: '+e.message));}if(navigate&&destination()==='admin-panel')admin();}
}
async function signIn(username,password){try{return await api('/auth/login',{method:'POST',body:{username,password}});}catch(e){if(e.status!==409)throw e;await token();return api('/auth/login',{method:'POST',body:{username,password}});}}
$('login-form').addEventListener('submit',async e=>{e.preventDefault();const f=new FormData(e.target);try{await signIn(f.get('username'),f.get('password'));e.target.reset();message('');if(me?.local){location.assign('/');return;}await signedIn();}catch(e){message(e.status===401?'Username or password is incorrect.':e.message);}});
$('password-form').addEventListener('submit',async e=>{e.preventDefault();const f=new FormData(e.target);if(f.get('next')!==f.get('confirm'))return message('The new passwords do not match.');try{if(engine)await saveToDevice();await api('/auth/password',{method:'POST',body:{currentPassword:f.get('current'),newPassword:f.get('next')}});await token();await api('/auth/login',{method:'POST',body:{username:me.username,password:f.get('next')}});e.target.reset();if(engine){engine.stop();location.reload();return;}await signedIn();message('Password changed. Other sessions have been signed out.');}catch(e){message(e.message);}});

// ---- Saves (save model 2, docs/SAVES.md) ----
// The device store and the syncer of this account (or of local-only play), made once the account is known.
function saveService(){return savesPromise??=import('./save-service.mjs'+version).then(m=>m.createSaveService({account:me.id,local:!!me.local,api,version})).then(s=>{saves=s;return s;}).catch(e=>{savesPromise=null;throw e;});}
async function startSaves(){
 const svc=await saveService();
 const {applyUnwritten}=await import('./game-session.mjs'+version);await applyUnwritten(svc).catch(()=>{});
 await migrateOld(svc).catch(e=>console.error('Old saves were not moved yet',e));
 const stores=await import('./device-store.mjs'+version);for(const a of [stores.LOCAL_ACCOUNT,...svc.local?[]:[me.id]])await stores.mergeProfiles(a,svc.core).catch(e=>console.error('Old profiles were not moved yet',e));
 if(!me.local&&(()=>{try{return !!localStorage.getItem('ftl-enable-cloud');}catch{return false;}})()){
  // Enable cloud saves: the saves of local-only play in this browser move into the account and upload.
  try{await adoptLocalGameFile();const n=await svc.adoptLocalSaves();try{localStorage.removeItem('ftl-enable-cloud');}catch{}if(n)message(n===1?'Your save from playing without an account is now in your account.':'Your '+n+' saves from playing without an account are now in your account.');}
  catch(e){message('Your local saves were not moved yet: '+e.message);}
 }
 svc.on(()=>{refreshSoon();syncStatus();});
 if(svc.sync.cloudEnabled){svc.sync.syncAll();setInterval(()=>{if(!document.hidden)svc.sync.syncAll();},120000);}else svc.sync.syncAll();
 syncStatus();refreshLaunch();if(shell.panel==='saves-panel')savesPage().catch(()=>{});
}
// Once per browser profile and account: the saves of the old save model become device saves (web/old-saves.mjs).
async function migrateOld(svc){
 const [{migrateAccount},{getState,LOCAL_ACCOUNT}]=await Promise.all([import('./old-saves.mjs'+version),import('./device-store.mjs'+version)]);
 const accounts=[LOCAL_ACCOUNT,...svc.local?[]:[me.id]],todo=[];
 for(const a of accounts)if(!await getState('migrated|'+a))todo.push(a);
 if(!todo.length)return;
 // Mod code loads only when this browser has a mod library (a modded save names its archives).
 const names=indexedDB.databases?await indexedDB.databases().then(l=>l.map(d=>d.name),()=>null):null;
 const library=!names||names.includes('ftl-mods')?await import('./mods/store.mjs'+version).catch(()=>null):null;
 const catalogue=library?await catalogueOnce().catch(()=>null):null;
 const cloud=svc.sync.cloudEnabled?{saves:async()=>(await api('/saves')).saves,run:id=>svc.sync.fetchCloud(id)}:null;
 const common={core:svc.core,device:svc.device,catalogue,presets:library?await presets(true):[],archiveSize:async sha=>(await library?.getArchive(sha))?.size??0,archiveLists:async a=>(await library?.getList(a))??[]};
 let moved=0;
 for(const account of todo){const r=await migrateAccount({...common,account,cloud:account===LOCAL_ACCOUNT?null:cloud});moved+=(r?.moved??0)+(r?.recovered??0);}
 if(moved){svc.sync.requestSync();svc.notify();}
}
async function adoptLocalGameFile(){if(me.role!=='external-user')return;try{const {getLocalAsset,persistLocalAsset}=await import('./local-assets.mjs');if(await getLocalAsset(me.id))return;const {LOCAL_ACCOUNT}=await import('./device-store.mjs'+version);const record=await getLocalAsset(LOCAL_ACCOUNT);if(record)await persistLocalAsset(me.id,record);}catch{}}

const SWITCH_BLOCKED='A game is running: use Save and close first.';
let catalogueCache=null;
function catalogueOnce(){return catalogueCache??=(me.local?fetch('/api/public/mods/catalogue',{cache:'no-store'}).then(r=>{if(!r.ok)throw Error('The mod catalogue is unavailable.');return r.json();}):api('/mods/catalogue')).catch(e=>{catalogueCache=null;throw e;});}
/** The catalogue's presets as setups ({id, title, setup, hyperspace}); [] when not needed or unavailable. */
async function presets(needed){
 if(!needed)return [];
 try{const cat=await catalogueOnce(),svc=await saveService();return (cat?.presets??[]).map(p=>{const files=p.files.map(id=>cat.files.find(f=>f.id===id));if(!files.length||files.some(f=>!f))return null;return {id:p.id,title:p.title,hyperspace:files.some(f=>f.mod==='hyperspace'),setup:svc.setupOf(files.map(f=>({name:f.title,version:f.version??'',sha256:f.sha256,size:f.size,verified:true})))};}).filter(Boolean);}catch{return [];}
}
/** The setup of the enabled mods ({key, mods, hyperspace}): no mod code is loaded without mods. */
async function enabledSetup(shas=null){
 shas??=await (await modsEnabledModule).enabledMods(me.id);
 if(!shas.length)return {key:'vanilla',mods:[],hyperspace:false};
 const [{describeSetup},svc,cat]=await Promise.all([import('./mods/boot.mjs'+version),saveService(),catalogueOnce().catch(()=>null)]);
 return describeSetup(shas,{core:svc.core,catalogue:cat});
}
const modName=m=>m.name+(m.version?' '+m.version:'');
async function setupLabel(setup){if(!setup.mods.length)return 'No mods';const preset=(await presets(true)).find(p=>p.setup.key===setup.key);if(preset)return preset.title;return setup.mods.length===1?modName(setup.mods[0]):setup.mods.length+' mods';}
/** The mods of a setup this browser does not have ({...mod, inCatalogue}); only modded setups load mod code. */
async function missingMods(setup){
 if(!setup.mods.length)return [];
 const [{getArchive},cat]=await Promise.all([import('./mods/store.mjs'+version),catalogueOnce().catch(()=>null)]);
 const out=[];for(const m of setup.mods)if(!await getArchive(m.sha256).catch(()=>null))out.push({...m,inCatalogue:!!cat?.files.some(f=>f.sha256===m.sha256)});return out;
}
/** The mod files in this browser as a save names them (Saves > a save > Advanced). */
async function libraryMods(){const [{listArchives},cat,svc]=await Promise.all([import('./mods/store.mjs'+version),catalogueOnce().catch(()=>null),saveService()]);return (await listArchives()).map(a=>({...svc.core.classify(cat,a.name,a.sha256,a.size),title:a.title}));}
const savesCtx=()=>({service:saves,enabled:()=>enabledSetup(),label:setupLabel,presets,blocked:()=>engine?SWITCH_BLOCKED:null,playing:()=>engine?session?.id??null:null,play:id=>playSave(id),missing:missingMods,libraryMods,message,download,showAll:()=>show('saves-panel'),openMods,hyperspace:setup=>hyperspaceStep(setup,null,{show:false})});
function openMods(){show('launch-panel');const m=$('mods-panel');if(m){m.open=true;m.scrollIntoView({block:'start'});}}
/**
 * "Hyperspace mod without Hyperspace" (docs/MODS.md, mods/hs-needed.mjs), once per start of a set with mods: a mod that
 * uses Hyperspace while Hyperspace.ftl is not among them asks Enable Hyperspace / Run anyway. Enable gets the file (asking
 * before it downloads), then for a new save puts it first in the mod picker and shows it there; an existing save changes
 * its mods only in Advanced (or the player starts a new save with Hyperspace). Resolves null (go on as it is), 'stop',
 * 'again' (the enabled mods changed: start again with them) or 'played' (the save was started from Advanced).
 */
async function hyperspaceStep(setup,save,{show:showPicker=true}={}){
 if(!setup.mods.length)return null;
 const hs=await import('./mods/hs-needed.mjs'+version),mod=await hs.hyperspaceMissing(setup.mods,await catalogueOnce().catch(()=>null));
 if(!mod)return null;
 const answer=await hs.askHyperspace({mod,save:save?.name??null});
 if(answer!=='enable')return answer==='run'?null:'stop';
 const tools=await modsTools(),{getArchive}=await import('./mods/store.mjs'+version);
 const entry=await hs.getHyperspace({catalogue:fresh=>{if(fresh)catalogueCache=null;return catalogueOnce();},have:async sha=>!!await getArchive(sha).catch(()=>null),
  download:e=>tools.download(e,'server'),error:e=>tools.downloadError(e),signIn:()=>location.assign('/?view=signin'),openMods});
 if(!entry)return 'stop';
 const hsMod={name:entry.title,version:entry.version??'',sha256:entry.sha256,size:entry.size,verified:true};
 if(save){
  const how=await hs.askSaveHyperspace(save.name);
  if(how==='advanced'){const row=(await (await saveService()).rows()).find(r=>r.id===save.id);if(!row||!await (await savesUiModule()).modsDialog(savesCtx(),row,{first:hsMod}))return 'stop';await playSave(save.id,{hsAsked:true});return 'played';}
  if(how!=='new')return 'stop';
  if(!await tools.requireSet([hsMod,...setup.mods.filter(m=>m.sha256!==hsMod.sha256)]))return 'stop';
 }
 await tools.enableFirst(entry.sha256);
 if(showPicker){openMods();document.querySelector('#mods-panel [data-highlight="true"]')?.scrollIntoView({block:'center'});}
 return 'again';
}

// The Saves panel: the list, New save, Import, the sync line, and the cards for saves still to move.
function buildSavesPanel(){
 const panel=$('saves-panel'),back=panel.querySelector('.close-panel');
 const h=(tag,props={},...kids)=>{const e=document.createElement(tag);Object.assign(e,props);e.append(...kids.filter(k=>k!=null));return e;};
 const input=h('input',{id:'backup-import',type:'file',accept:'application/json,.json'});
 panel.replaceChildren(
  h('div',{className:'section-heading'},h('div',{},h('h1',{},'Saves'),h('p',{id:'saves-sync',className:'muted text-sm',role:'status'},''))),
  h('div',{className:'actions'},h('button',{id:'new-save',className:'btn btn-primary',type:'button'},'New save'),h('label',{className:'btn file-button'},'Import save',input),h('button',{id:'native-import',className:'btn',type:'button'},'Import from FTL'),h('button',{id:'saves-sync-now',className:'btn btn-ghost',type:'button',hidden:true},'Sync now')),
  h('div',{id:'saves-list',className:'saves-card'},h('p',{className:'muted',role:'status'},'Loading…')),
  h('p',{id:'saves-usage',className:'muted text-sm'}),
  h('div',{id:'saves-local-card',className:'subcard',hidden:true},h('h2',{},'Saves from playing without an account'),h('p',{id:'saves-local-info',className:'muted'}),h('div',{className:'actions'},h('button',{id:'saves-local-upload',className:'btn btn-primary btn-sm',type:'button'},'Move them to this account'))),
  h('div',{id:'saves-old-card',className:'subcard',hidden:true},h('h2',{},'Saves from before'),h('p',{className:'muted'},'Saves of the old save model in this browser were moved into the list above. The old data is kept until you remove it.'),h('div',{className:'actions'},h('button',{id:'saves-old-download',className:'btn btn-sm',type:'button'},'Download the old saves'),h('button',{id:'saves-old-remove',className:'btn btn-ghost btn-sm',type:'button'},'Remove the old saves'))),
  h('div',{id:'storage-card',className:'subcard',hidden:true},h('h2',{},'Browser storage'),h('p',{id:'storage-status',className:'muted',role:'status'})),
  back);
 panel.dataset.built='true';
}
// The pause panel of the old model becomes the notice of a stopped game (engine error, role change).
function buildStopPanel(){const p=$('connection-panel');const title=document.createElement('h2');title.textContent='The game stopped';const text=document.createElement('p');text.id='connection-message';const close=document.createElement('button');close.id='connection-close';close.className='btn';close.textContent='Back to saves';close.onclick=()=>location.assign('/?view=saves');p.replaceChildren(title,text,close);}
async function savesPage(){
 if(!me||me.mustChangePassword)return;const svc=await saveService(),ui=await savesUiModule();
 await ui.renderSaves({root:$('saves-list'),ctx:savesCtx()});
 const rows=await svc.rows(),local=svc.local;
 $('saves-usage').textContent=local?rows.length+' saves in this browser':rows.length+' of 30 saves'+(svc.sync.state.usedBytes!=null?' · '+(svc.sync.state.usedBytes/1048576).toFixed(1)+' of '+Math.round((svc.sync.state.limits?.bytes??268435456)/1048576)+' MB in your account':'');
 $('saves-sync-now').hidden=local;
 if(!local){const n=await svc.localSaveCount();$('saves-local-card').hidden=!n;$('saves-local-info').textContent=n===1?'One save from playing without an account is in this browser.':n+' saves from playing without an account are in this browser.';}
 const {oldBackup}=await import('./old-saves.mjs'+version);$('saves-old-card').hidden=!await oldBackup();
 $('storage-card').hidden=!local;if(local){const d=await import('./device-store.mjs'+version);$('storage-status').textContent=d.describeStorage(await d.storageStatus());}
 syncStatus();
}
function syncStatus(){
 if(!saves)return;const s=saves.sync.state;
 const text=saves.local?'Saves are kept in this browser only.':s.syncing?'Syncing…':s.error?s.error:s.lastSync?'Synced '+new Date(s.lastSync).toLocaleTimeString([],{hour:'2-digit',minute:'2-digit'}):'Saves sync with your account in the background.';
 $('saves-sync').textContent=text;$('cloud-ready').textContent=saves.local?'Saves are kept in this browser':text;
}
$('new-save').addEventListener('click',async()=>{try{const ui=await savesUiModule();await saveService();await ui.newSave({ctx:savesCtx()});refreshLaunch();}catch(e){message(e.message);}});
$('backup-import').addEventListener('change',async e=>{const file=e.target.files[0];if(!file)return;try{if(file.size>16*1024*1024)throw Error('Backup exceeds 16 MiB.');const made=await (await saveService()).importBackup(await file.text());message('Imported as '+made.name+'.');(await savesUiModule()).refreshSaves();}catch(err){message(err.message);}finally{e.target.value='';}});
$('native-import').addEventListener('click',async()=>{try{const [{importFromGame},ui]=await Promise.all([import('./native-import.mjs'+version),savesUiModule(),saveService()]);const made=await importFromGame(savesCtx());if(made){message('Imported as '+made.name+'.');ui.refreshSaves();refreshLaunch();}}catch(e){message(e.message);}});
$('saves-sync-now').addEventListener('click',async()=>{const svc=await saveService();await svc.sync.syncAll();savesPage().catch(()=>{});});
$('saves-local-upload').addEventListener('click',async()=>{try{const n=await (await saveService()).adoptLocalSaves();message(n===1?'The save is now in your account.':n+' saves are now in your account.');savesPage();}catch(e){message(e.message);}});
$('saves-old-download').addEventListener('click',async()=>{const {oldBackup}=await import('./old-saves.mjs'+version);const b=await oldBackup();if(b)download({schema:'ftl-old-saves',savedUtc:b.at,...b.data},'ftl-old-saves.json');});
$('saves-old-remove').addEventListener('click',async()=>{const {confirmAction}=await dialogsModule();if(!await confirmAction({id:'old-saves-remove-dialog',title:'Remove the old saves?',text:'The old save data is deleted from this browser. The saves moved into the list stay.',warning:'Download the old saves first if you may need them.',yes:'Remove',danger:true}))return;const {removeOldData}=await import('./old-saves.mjs'+version);await removeOldData();savesPage();});

// Play: the save Launch FTL uses now ("Save: Save 2 · Multiverse 5.5.1") with a Change link.
let launchSeq=0;
function launchLabel(shas){$('launch-label').textContent=shas.length?'Launch FTL with mods':'Launch FTL';$('launch').dataset.alt1=shas.length?'Launch FTL':'Launch FTL with mods';}
async function refreshLaunch(){
 if(!me||me.mustChangePassword)return;const seq=++launchSeq;
 try{
  const shas=await (await modsEnabledModule).enabledMods(me.id);if(seq!==launchSeq)return;launchLabel(shas);
  const svc=await saveService(),setup=await enabledSetup(shas),cur=await svc.currentFor(setup.key),label=await setupLabel(setup);if(seq!==launchSeq)return;
  const name=document.createElement('strong');name.textContent=cur?.name??'none';
  $('launch-save-text').replaceChildren('Save: ',name,' · '+label);$('launch-save').hidden=false;syncStatus();
 }catch{if(seq===launchSeq)$('launch-save').hidden=true;}
}
$('launch-save-change').addEventListener('click',async()=>{try{const [ui,setup]=await Promise.all([savesUiModule(),enabledSetup(),saveService()]);await ui.manageSaves({ctx:savesCtx(),enabled:setup,label:await setupLabel(setup)});}catch(e){message(e.message);}refreshLaunch();});
// Fresh data: Play, Saves and Mods re-read their data whenever they become visible (a tab switch, the window regaining focus,
// a change made in another tab of this browser) and after every action. No reload is needed anywhere.
async function refreshAll(){
 if(!me||me.mustChangePassword||shell.panel==='game-panel')return;
 refreshLaunch();savesUiModule().then(ui=>ui.refreshSaves()).catch(()=>{});
 if(shell.panel==='saves-panel')savesPage().catch(()=>{});
 if(modUI&&$('mods-panel').open)modUI.then(u=>u.refresh()).catch(()=>{});
}
let freshTimer,lastWake=0;function refreshSoon(){clearTimeout(freshTimer);freshTimer=setTimeout(()=>{if(!document.hidden)refreshAll();},150);}
function wake(){refreshSoon();if(saves?.sync.cloudEnabled&&Date.now()-lastWake>20000){lastWake=Date.now();saves.sync.requestSync();}}
document.addEventListener('visibilitychange',()=>{if(!document.hidden)wake();});window.addEventListener('focus',wake);window.addEventListener('pageshow',refreshSoon);
let modsChannel=null;try{modsChannel=new BroadcastChannel('ftl-saves');modsChannel.addEventListener('message',e=>{if(e.data?.type==='mods')refreshSoon();});}catch{}
/** Tell the other tabs of this browser that the mods changed (saves announce themselves through the device store). */
function announce(){try{modsChannel?.postMessage({type:'mods',account:me?.id??''});}catch{}}
window.addEventListener('storage',e=>{if(e.key&&/^ftl-(mods-enabled:|current-save:)/.test(e.key))refreshSoon();});
window.addEventListener('online',()=>saves?.sync.setOnline(true));window.addEventListener('offline',()=>saves?.sync.setOnline(false));

// One Launch button: it plays the save Play names for the enabled mods ("Launch FTL with mods") or, with none enabled,
// the save without mods (the first one is made at once). Mods with no save ask first and never create one silently.
async function launchFromButton({hsAsked=false}={}){
 try{
  const m=await modsEnabledModule;let shas=await m.enabledMods(me.id);
  if(shas.length){const {getList}=await import('./mods/store.mjs'+version);shas=(await getList(me.id)).filter(e=>e.enabled).map(e=>e.sha256);m.writeEnabled(me.id,shas);launchLabel(shas);}
  const svc=await saveService(),setup=await enabledSetup(shas);
  let cur=await svc.currentFor(setup.key);
  // A new browser may not have the account's saves yet: look before making one (offline this gives up at once or after the wait).
  if(!cur&&svc.sync.cloudEnabled){await withTimeout(svc.sync.syncAll().catch(()=>{}),8000);cur=await svc.currentFor(setup.key);}
  if(!hsAsked){const step=await hyperspaceStep(setup,cur);if(step==='again')return launchFromButton({hsAsked:true});if(step)return;}
  if(!cur&&setup.key==='vanilla')cur=await svc.create(setup,null);
  if(!cur){
   const rows=await svc.rows(),withoutMods=setup.hyperspace?null:rows.filter(r=>r.setup.key==='vanilla'&&r.hasRun).map(r=>({id:r.id,name:r.name}));
   const answer=await (await savesUiModule()).askNoSave({label:await setupLabel(setup),withoutMods});if(!answer)return;
   if(answer.action==='copy'){const from=rows.find(r=>r.id===answer.id);cur=await svc.create(setup,'Copy of '+from.name,await svc.dataOf(answer.id));}
   else cur=await svc.create(setup,null);
  }
  await playSave(cur.id,{hsAsked:true});
 }catch(e){message(e.message);$('launch').disabled=false;}
}
/**
 * Plays a save: its mods become the enabled ones (catalogue files download first, a missing Custom file stops here),
 * the syncer brings this device's copy up to date (or asks), the save's lock is taken (or the player overrides it),
 * and the game starts from the device copy. Offline it plays without a lock. Changed in two places: "Use the cloud
 * copy" applies at once; "Keep this device's copy" replaces the account's version only once this tab holds the lock
 * (a lock held elsewhere asks first, as for any Play), then the game starts.
 */
async function playSave(id,{override=false,hsAsked=false}={}){
 if(engine)throw Error(SWITCH_BLOCKED);
 const svc=await saveService(),sync=svc.sync,ui=await savesUiModule();
 const row=(await svc.rows()).find(r=>r.id===id);if(!row)throw Error('That save no longer exists.');
 if(row.setup.key==='vanilla'){if((await (await modsEnabledModule).enabledMods(me.id)).length)await (await modsTools()).disableAll();}
 else if(!await (await modsTools()).requireSet(row.setup.mods))return;
 if(!hsAsked){const step=await hyperspaceStep(row.setup,row);if(step==='again')return launchFromButton({hsAsked:true});if(step)return;}
 const d=await sync.prepare(id);let keepMine=false;
 if(d?.action==='ASK_CONFLICT'){
  const a=await ui.askConflict(row.name);if(!a){ui.refreshSaves();return;}
  if(a==='cloud'){await sync.resolveConflict(id,false);message(row.name+' is now the copy from your account.');}else keepMine=true;
 }
 // The takeover notice and "deleted on another device" return to the list, as on Android (Play again to start).
 if(d?.action==='NOTIFY_OVERRIDDEN'){const l=await svc.store.get(id);await ui.showOverridden(l?.overridden?.device,l?.overridden?.at);await sync.acknowledgeOverride(id,l?.overridden?.at);ui.refreshSaves();return;}
 if(d?.action==='ASK_DELETED'){const a=await ui.askDeleted(row.name);if(a)await sync.resolveDeleted(id,a==='upload',crypto.randomUUID());ui.refreshSaves();return;}
 if(d?.removed){message(ui.words('deletedRemoved',{name:row.name}));ui.refreshSaves();return;}
 let lock=await sync.lock(id,override);
 if(lock.result==='held'){if(!await ui.askOverride(lock.lock,sync.sameDevice(lock.lock)))return;lock=await sync.lock(id,true);}
 if(lock.result==='overridden'){await ui.showOverridden(lock.lock?.device,lock.at);await sync.acknowledgeOverride(id,lock.at);ui.refreshSaves();return;}
 if(lock.result==='deleted'){message(ui.words('deletedRemoved',{name:row.name}));ui.refreshSaves();return;}
 if(lock.result==='refused')throw Error(lock.decision?.error??'The account refused this save.');
 if(keepMine){
  if(lock.result!=='granted'){message(ui.words('keepOffline',{name:row.name}));ui.refreshSaves();return;}
  const kept=await sync.resolveConflict(id,true);
  if(kept?.status!=='SYNCED'){await sync.release(id).catch(()=>{});ui.refreshSaves();throw Error(kept?.error??'This browser\'s copy of '+row.name+' could not replace the account\'s version.');}
  message('This browser\'s copy of '+row.name+' is now in your account.');
 }
 const save=await svc.store.get(id);
 if(!save){message(row.name+' is in your account and not downloaded to this browser yet. Connect to play it.');return;}
 svc.choose(save.setup.key,id);
 return launch(save);
}
// Launch overlay: the step (game files, mods, engine), a progress bar with bytes or files, speed and time left.
let P=null,loadingRate=null,modLaunch=null;
function loadingReset(modded){$('loading-phase').textContent='Preparing…';$('save-status').textContent='Loading…';for(const li of $('loading-steps').children)li.dataset.state='';$('loading-steps').querySelector('[data-step="mods"]').hidden=!modded;$('loading-progress').hidden=true;$('loading-note').hidden=true;loadingRate=null;}
function loadingUpdate(text,detail){
 if(detail?.stage==='hs-error-box'){$('game-loading').hidden=true;return;}// the game area stays black under the box
 $('save-status').textContent=text;$('loading-phase').textContent=text;
 const steps=[...$('loading-steps').children].filter(li=>!li.hidden),at=steps.findIndex(li=>li.dataset.step===detail?.stage);
 if(at>=0)steps.forEach((li,i)=>li.dataset.state=i<at?'done':i===at?'active':'');
 const bar=$('loading-bar');
 if(detail?.total&&P){$('loading-progress').hidden=false;bar.max=detail.total;bar.value=Math.min(detail.total,detail.done??0);
  if(detail.unit==='files')$('loading-detail').textContent=`${detail.done??0} of ${detail.total} files`;
  else{const key=text+'|'+detail.total;if(loadingRate?.key!==key)loadingRate={key,next:P.createRate(detail.total,detail.done??0)};$('loading-detail').textContent=P.formatProgress(loadingRate.next(detail.done??0));}}
 else if(detail?.stage||detail?.blocking)$('loading-progress').hidden=true;
 $('loading-note').hidden=!detail?.blocking;if(detail?.blocking)$('loading-note').textContent='The page does not respond while the engine starts'+(modLaunch?' (with large mods such as Multiverse this can take a minute)':'')+'.';
}
const withTimeout=(promise,ms)=>Promise.race([promise,new Promise(r=>setTimeout(()=>r(false),ms))]);
async function launch(save){
 const svc=saves,setup=save.setup,modded=setup.key!=='vanilla';modLaunch=null;
 const audioContext=new AudioContext();audioContext.resume().catch(()=>{});
 $('launch').disabled=true;message('');if(me.local)import('./device-store.mjs'+version).then(d=>d.requestPersistence()).catch(()=>{});try{
  P??=await import('./progress.mjs'+version);
  // The overlay appears at once: the game files (138 MB on a first launch) and a mod merge can take a while.
  loadingReset(modded);show('game-panel');$('game-loading').hidden=false;await new Promise(requestAnimationFrame);
  const {createAssetProvider}=await import('./asset-provider.mjs'+version);
  assetProvider=await createAssetProvider({api,accountId:me.id,fetchBytes:fetchAssetBytes,onProgress:(text,detail)=>loadingUpdate(text,{stage:'files',...detail})});
  manifest=assetProvider.manifest;
  if(modded){const {prepareModdedLaunch}=await import('./mods/boot.mjs'+version);modLaunch=await prepareModdedLaunch({account:me.id,list:setup.mods.map(m=>({sha256:m.sha256})),provider:assetProvider,onProgress:loadingUpdate});manifest=modLaunch.manifest;
   // Ask before running (sweep M1b): scripts that use io, os, debug or package wait for the player's Run (kept per mod version).
   if(modLaunch.session.scripts.length){const lc=await import('./mods/lua-consent.mjs'+version);const found=lc.scanLaunch({mods:modLaunch.mods,scripts:modLaunch.session.scripts,files:modLaunch.overlay.files,owners:await modLaunch.scriptOwners()});
    if(found.length&&!await lc.askLuaConsent({account:me.id,found}))throw Error('The game did not start: you chose not to run the scripts of '+found.map(m=>m.name).join(', ')+'.');}}
  diagnostics?.setBuild(manifest.engineBuild);
  const {createGameSession}=await import('./game-session.mjs'+version);
  session=createGameSession({service:svc,save,manifest,onStatus:(text,bad)=>{$('save-status').textContent=text;if(bad)message(text);},onOverridden:(lock,at)=>takenOver(lock,at)});
  document.body.dataset.workload='active';await new Promise(requestAnimationFrame);
  const ae=session.run?session.run.includes('enableAE="true"'):true;
  const {loadGame}=await import('./game.mjs'+version);const {readSettings}=await import('./device-store.mjs'+version);
  engine=await loadGame({canvas:$('game'),manifest,accountId:me.id,fetchBoot:modLaunch?modLaunch.fetchBoot:assetProvider.fetchBoot,fetchEngine:assetProvider.fetchEngine,fetchAudio:modLaunch?modLaunch.fetchAudio:assetProvider.fetchAudio,mods:modLaunch?.overlay,onProgress:(text,detail)=>loadingUpdate(text,detail),onSave:(kind,xml)=>session?.onSave(kind,xml),run:session.run,profile:session.profileXml,settings:readSettings(),ae,audioContext});
  $('game-loading').hidden=true;$('save-status').textContent=save.name;
  engine.debugConfig?.(diagnostics?.enabled()??false);engine.consoleConfig?.(consoleSetting.consoleEnabled());await diagnostics?.checkpoint('engine.start');
  // A save that names content these game files lack loads without it (the engine drops it); say what went.
  missingTimer=setInterval(async()=>{const names=engine?.missingContent?.();if(names)(await savesUiModule()).showMissingContent(names.split('\n'));},700);
  window.ftlExit=()=>saveAndClose().catch(e=>message(e.message));
  // A mod script called os.exit (the original closes FTL): the game ends without another save, the launcher says which mod.
  window.ftlLuaExit=source=>luaExit(source).catch(e=>message(e.message));
 }catch(e){const id=session?.id??save.id;session?.close();session=null;delete window.ftlModSession;diagnostics?.record('runtime','engine.launch.failed',{message:e.message},'error');await diagnostics?.checkpoint('engine.launch.failed');assetProvider?.dispose();document.body.dataset.workload='';audioContext.close();console.error('Game launch failed',e);svc.sync.setPlaying(null);svc.sync.release(id).catch(()=>{});show('launch-panel');message(e.message);$('launch').disabled=false;}
}
$('launch').addEventListener('click',()=>launchFromButton());
// Mods panel (its code loads the first time it opens). Catalogue: this account's server downloads, or the public list.
function modsTools(){return modUI??=Promise.all([import('./mods/library.mjs'+version),saveService()]).then(([m,svc])=>m.mountModLibrary({root:$('mods-root'),account:me.id,role:me.role,local:!!me.local,message,core:svc.core,onChange:shas=>{launchLabel(shas);refreshLaunch();announce();},
 catalogue:catalogueOnce,
 fetchServer:(id,start,end,signal)=>fetch('/api/mods/files/'+encodeURIComponent(id),{headers:{Range:`bytes=${start}-${end}`,'X-FTL-Account':me.id},cache:'no-store',signal})})).catch(err=>{modUI=null;message(err.message);throw err;});}
$('mods-panel')?.addEventListener('toggle',e=>{if(!e.target.open||!me)return;if(modUI)modUI.then(u=>u.refresh()).catch(()=>{});else modsTools().catch(()=>{});});

// ---- The running game ----
/** The engine's save goes to the device store; resolves once it is there (throws when this browser could not store it). */
async function saveToDevice(){if(!engine||!session)return;engine.save();await session.flush();}
/** Save: to this browser now, to the account in the background. */
async function saveNow(){
 if(!engine||!session)return;
 try{await saveToDevice();}catch(e){$('save-status').textContent='Not saved: '+e.message;message(e.message);return;}
 const at=new Date().toLocaleTimeString();$('save-status').textContent='Saved on this device · '+at;
 if(saves.sync.cloudEnabled)saves.sync.uploadNow(session.id,'manual').then(ok=>{if(engine)$('save-status').textContent=ok?'Saved · synced '+at:'Saved on this device · syncs when it can';}).catch(()=>{});
}
// Save and close: one save into this browser (it throws when that failed, leaving the game running), then the upload and
// the release of the lock (later, when offline now), and the page returns to the launcher.
async function saveAndClose(){
 if(!engine||!session)throw Error('The game is still loading.');
 if(engine.saveKind?.()==='tutorial'&&!await (await dialogsModule()).confirmAction({id:'tutorial-close-dialog',title:'Close the tutorial?',text:'The tutorial cannot be saved.',yes:'Close the game'}))return false;
 engine.freeze?.(true);
 try{engine.saveSettle?.();await saveToDevice();}catch(e){engine.freeze?.(false);throw e;}
 const id=session.id;session.close();session=null;clearInterval(missingTimer);
 const running=engine;engine=null;running.stop();assetProvider?.dispose();
 $('save-status').textContent='Syncing…';
 await withTimeout(saves.sync.finish(id).catch(()=>false),10000);
 location.reload();
}
/** os.exit in a mod script: stop as the original's process ends (nothing more is saved), then name the mod. */
async function luaExit(source){
 const running=engine,id=session?.id;if(!running)return;
 const mods=modLaunch?.mods??[],owners=modLaunch?await modLaunch.scriptOwners().catch(()=>new Map()):new Map();
 const mod=mods[owners.get(source)??-1]?.name??(source?source:'A mod');
 engine=null;clearInterval(missingTimer);session?.close();session=null;
 try{running.stop();}catch{}assetProvider?.dispose();
 if(id)await withTimeout(saves.sync.finish(id).catch(()=>false),10000);
 await (await dialogsModule()).notice({id:'lua-exit-dialog',title:'The game was closed by a mod',text:[mod+"'s script called os.exit, which closes the game in the original. Your last save is kept."],okLabel:'Back to the launcher'});
 location.reload();
}
/** Another game took this save: stop at once (nothing more is kept) and say so. */
async function takenOver(lock,at){
 const running=engine,id=session?.id;if(!running)return;
 engine=null;clearInterval(missingTimer);session?.close();session=null;
 try{running.stop();}catch{}assetProvider?.dispose();
 const here=saves.sync.sameDevice(lock),left=here&&id?saves.sync.leave(id).catch(()=>{}):null;
 await (await savesUiModule()).showOverridden(lock?.device,at,here);
 if(id)await (left??saves.sync.acknowledgeOverride(id,at).catch(()=>{}));
 location.assign('/?view=saves');
}
/** A problem the game cannot go on with (engine error, role change): its saves are in this browser; show why. */
async function haltGame(text){
 if(!engine)return;engine.suspend?.(true);try{await saveToDevice();}catch{}
 const id=session?.id;session?.close();session=null;clearInterval(missingTimer);if(id)saves?.sync.uploadNow(id).catch(()=>{});
 $('connection-panel').hidden=false;$('connection-message').textContent=text+' Your last save is kept in this browser.';$('save-status').textContent='Stopped';
}
const consoleSetting=await import('./console-setting.mjs'+version);
const {initGameBar}=await import('./gamebar.mjs'+version);const gameBar=initGameBar({$,getEngine:()=>engine,save:saveNow,saveAndClose,message,consoleEnabled:consoleSetting.consoleEnabled});
// Logs page: the browser's "Debug console" setting (docs/CONSOLE.md), for the running game too.
$('console-enabled').checked=consoleSetting.consoleEnabled();
$('console-enabled').addEventListener('change',e=>{consoleSetting.setConsoleEnabled(e.target.checked);engine?.consoleConfig?.(e.target.checked);gameBar.render();});
window.addEventListener('ftl-engine-error',e=>haltGame('The engine stopped: '+(e.detail?.message??e.detail)+'.'));
document.addEventListener('visibilitychange',()=>{if(document.hidden&&session&&saves?.sync.cloudEnabled){const id=session.id;setTimeout(()=>session?.flush().then(()=>saves.sync.uploadNow(id)).catch(()=>{}),0);}});
// Leaving the page: the engine saves once more; a write still queued then is kept by the session's pagehide handler
// (localStorage) and stored at the next start, so leaving never needs a question.
window.addEventListener('beforeunload',()=>{if(engine&&session){try{engine.save();}catch{}}});
// Sign out always asks first (web/sign-out.mjs); an open game is saved and sent to the account before the question.
$('logout').addEventListener('click',async()=>{try{let unsynced=false;if(engine){await saveToDevice();unsynced=!await withTimeout(saves.sync.uploadNow(session.id),10000).catch(()=>false);}const ask=await import('./sign-out.mjs'+version);unsynced||=await ask.unsyncedSaves(me?.id);if(!await ask.confirmSignOut(unsynced))return;if(engine){session.close();engine.stop();}await api('/auth/logout',{method:'POST'});await import('./asset-cache.mjs').then(m=>m.clearServerAssets()).catch(()=>{});try{new BroadcastChannel('ftl-session').postMessage('changed');}catch{}location.reload();}catch(e){message(e.message);}});
$('account-button').addEventListener('click',e=>{e.preventDefault();engine?.pause();show('account-panel');});$('change-password').addEventListener('click',()=>{engine?.pause();show('password-panel');});
$('play-button').addEventListener('click',e=>{e.preventDefault();show(engine?'game-panel':'launch-panel');});$('saves-button').addEventListener('click',e=>{e.preventDefault();engine?.pause();show('saves-panel');});$('logs-button').addEventListener('click',e=>{e.preventDefault();engine?.pause();show('logs-panel');diagnostics?.refresh();});for(const id of ['about-button','about-nav-button'])$(id).addEventListener('click',e=>{e.preventDefault();engine?.pause();show('about-panel');});
document.querySelectorAll('.close-panel').forEach(b=>b.addEventListener('click',()=>show(engine?'game-panel':'launch-panel')));
async function admin(){try{show('admin-panel');if(!usersUI){const {createUsersUI}=await import('./users.mjs'+version);usersUI=createUsersUI({api,getMe:()=>me,message});}await usersUI.refresh();}catch(e){message(e.message);}}
$('admin-button').addEventListener('click',e=>{e.preventDefault();engine?.pause();admin();});
async function openTests(e){e?.preventDefault();try{engine?.pause();if(engine)await saveToDevice();location.assign('/Diagnostics');}catch(e){message(e.message);}}
for(const id of ['tests-button','launch-tests'])$(id).addEventListener('click',openTests);
for(const a of document.querySelectorAll('[data-view]'))a.onclick=e=>{e.preventDefault();engine?.pause();show(routes[a.dataset.view]);};
window.addEventListener('popstate',()=>{engine?.pause();show(destination(),{history:false});if(destination()==='admin-panel')admin();});
async function revalidate(){if(revalidating||me?.local)return;revalidating=true;try{await signedIn({navigate:false});}catch(e){if(e.status===401){sessionGeneration++;if(!engine){me=null;shell.authState='signed-out';$('identity').hidden=true;document.querySelector('.account-menu').hidden=true;show('login-panel',{history:false});}}else message('Connection interrupted. Your session will be checked again when connected.');}finally{revalidating=false;}}
window.addEventListener('pageshow',e=>{if(e.persisted)revalidate();});window.addEventListener('focus',()=>{if(me)revalidate();});
try{const channel=new BroadcastChannel('ftl-session');channel.onmessage=()=>revalidate();}catch{}
$('session-retry').onclick=()=>initialize();
async function initialize(){if(me?.local)return enterLocal();try{await signedIn();}catch(e){if(e.status===401){me=null;shell.authState='signed-out';$('identity').hidden=true;document.querySelector('.account-menu').hidden=true;show(destination(),{history:false});await token().catch(()=>{});}else{shell.authState='connection-error';show('checking-panel',{history:false});$('checking-message').textContent='Could not connect. Retry to check your account.';$('session-retry').hidden=false;}}}
const accountTools=()=>accountUI??=import('./account.mjs'+version).then(m=>m.mountAccount({api,getMe:()=>me,message,download,backup:async()=>(await saveService()).backupAll(),beforeDeletion:async()=>{if(engine){session?.close();engine.stop();engine=null;}await (await saveService()).store.clear().catch(()=>{});await import('./local-assets.mjs').then(a=>a.removeLocalAsset(me.id)).catch(()=>{});}}));
// One awaitable "save durably now": resolves after the device store committed the save, then tries the upload
// ({saved, backend: 'device', id, synced}); it throws when this browser could not store the save.
window.ftlSaveDurably=async()=>{if(!engine||!session)return {saved:false,reason:'no-game'};await saveToDevice();const id=session.id;const synced=saves.sync.cloudEnabled?await withTimeout(saves.sync.uploadNow(id,'manual').catch(()=>false),15000):false;return {saved:true,backend:'device',id,synced:!!synced};};
/** Runs a sync pass now and resolves its state (tests and diagnostics). */
window.ftlSyncNow=async()=>{const svc=await saveService();await svc.sync.syncAll();return {online:svc.sync.state.online,error:svc.sync.state.error,lastSync:svc.sync.state.lastSync};};
async function enterLocal(){show(destination(),{history:false});await token().catch(()=>{});optionalAccountSetup();startSaves().catch(e=>message('Saves are unavailable: '+e.message));}
const {mountOnboarding}=await import('./onboarding.mjs'+version);mountOnboarding({api,show,shell,message,token,signedIn,getMe:()=>me});
await import('./shell.mjs'+version);
await initialize();document.body.dataset.sessionReady='true';
try{const flash=sessionStorage.getItem('ftl-flash');if(flash){sessionStorage.removeItem('ftl-flash');message(flash);}}catch{}
document.addEventListener('click',async e=>{const link=e.target.closest('a[href]');if(!engine||e.defaultPrevented||!link||e.button||e.ctrlKey||e.metaKey||e.shiftKey||e.altKey||link.target==='_blank')return;e.preventDefault();engine.pause();try{await saveToDevice();}catch(err){return message('Not saved: '+err.message);}location.assign(link.href);});
