const version=new URL(import.meta.url).search;
const {loadGame}=await import('./game.mjs'+version);
const {createAssetProvider}=await import('./asset-provider.mjs'+version);
const {audioClockChecks,framePacingChecks,diagnosticExportChecks,assert:pageAssert}=await import('./self-tests.mjs'+version);
const {measureBrowserRate}=await import('./benchmark.mjs'+version);
const $=id=>document.getElementById(id);
const admin=document.body.dataset.role==='admin';
const external=document.body.dataset.role==='external-user';
const needsEngine=id=>!['units','server'].includes(id);
// The Tests page's temporary runs stand for the reference captures' setup: no first-start language list (LanguageJs).
window.ftlTestRuns=true;
const errorText=e=>{const m=e?.message;return m&&m!=='null'?m:(e==null?'Unknown error':String(e))||'Unknown error';};
const uploadPrompt='Upload ftl.dat in Local game files to run engine tests and benchmarks.';
const suites=[
 ['mechanics','Mechanics','Lockdown, drones, ASB and equipment overflow'],
 ['progress','Run statistics','Scores, crew records, lifetime counters and save restoration'],
 ['combat','Targeting & final sector','Target selection, fleet and flagship countdown'],
 ['interaction','Desktop weapons','Weapon swaps, autofire, input and animation timing'],
 ['warnings','Menu & jump warnings','Restart, hangar, New Game, stranded crew and abandonment scores'],
 ['gameplay-options','Gameplay options','Event number keys/delay, focus pausing, defaults and save restoration'],
 ['gameplay-parity','Gameplay parity','Native events, maps, rewards and the other ported gameplay rules'],
 ['ae-mode','Hangar & AE','Mode changes, Continue and credits'],
 ['drones','Shield & battle drones','Deployment, power, cooldowns and persistence'],
 ['unlocks','Ship unlocks','Eligibility, layout predicates and debug commands'],
 ['achievements','Achievements','Earning conditions, counters, notices and restoration'],
 ['audio','Game audio','Original music/effects decoding and playback'],
 ['units','Browser modules','Audio clock, FPS pacing, ZIP exports, save sync rules, mod merge and Lua patch scripts (in memory)'],
 ['tutorial','Tutorial data','Original desktop instruction metadata; actions and rendering are tested separately'],
 ['tutorial-isolation','Tutorial isolation','Protect normal saves and progression through tutorial saves, exits and failures'],
 ['tutorial-gates','Tutorial gates','Desktop controller transitions, wrong actions and window acceptance'],
 ['tutorial-adapter','Tutorial actions','Live engine selection, power, fire, pause, weapons and window gates'],
 ['tutorial-entry','Tutorial setup','Power, damage, named crew, fire, real repair/venting and save restoration'],
 ['tutorial-encounters','Tutorial encounters','Map jump, missile event, ammunition and both reward routes; controlled fixtures'],
 ['tutorial-play','Tutorial traversal','All lessons using normal mechanics and combat; automated action traversal'],
 ['tutorial-overlay','Tutorial instructions','Panels, native arrow/power pulses, Continue/Exit, captured input and modal priority'],
 ['vanilla','Vanilla layouts','18 layouts across three difficulties'],
 ['ae','AE layouts','28 layouts across three difficulties'],
 ['encounters','Encounters','137 enemy specifications, combat and save restoration'],
 ['campaign','Campaign traversal','12 accelerated fixtures through flagship phases; not played victories'],
 ['server','Server & browser integration','Security, saves, logs, storage failures, HTTPS, menus and real-time tutorial input'],
 ['performance','Flagship benchmark','FPS and frame/update/render timing across three phases'],
 ['hs-lua','Hyperspace Lua runtime','Bridge cost against real objects, in-situ frames and script API semantics'],
 ['hs-core','Hyperspace core','Hyperspace tags, capability report, modded run and profile saves','hyperspace'],
 ['hs-rules','Hyperspace gameplay','Mechanics, combat, drones, weapons, parity, statistics, achievements and unlocks with Hyperspace data','hyperspace'],
 ['mv-core','Multiverse core','Multiverse hyperspace.xml values, capability report, modded run and profile saves','multiverse'],
 ['mv-weapons','Multiverse weapons & drones','Hyperspace weapon and drone features on Multiverse data','multiverse'],
 ['mv-systems','Multiverse systems','Custom systems, limits and upgrades','multiverse'],
 ['mv-crew','Multiverse crew','Crew movement, tooltips, cloning and crew Lua','multiverse'],
 ['mv-hazards','Multiverse hazards','Equipment chains, hazards, planets and removeHazards','multiverse'],
 ['mv-lua','Multiverse Lua',"The Lua runtime with Multiverse's scripts",'multiverse'],
 ['mv-rules','Multiverse gameplay','Mechanics, combat, drones, weapons, parity (including the over-capacity store), statistics, achievements and unlocks','multiverse']
].map(([id,label,description,mods])=>({id,label,description,mods})).filter(s=>s.id!=='server'||admin);
// Hyperspace and Multiverse suites run on the mod files already in this browser's mod library (the catalogue's presets,
// by SHA-256; nothing is downloaded or added) or, in a packaged test fixture, on the preloaded mod session's data.
const preloaded=window.ftlModSessionSource==='hs-test-preload'?window.ftlModSession:null;
const modSets={},modNotes={hyperspace:'Add Hyperspace in Mods first',multiverse:'Add Hyperspace and both Multiverse files in Mods first'};
const quick=suites.filter(s=>['mechanics','progress','combat','interaction','warnings','gameplay-options','gameplay-parity','ae-mode','drones','unlocks','achievements','audio','units'].includes(s.id)).map(s=>s.id);
let engine,audioContext,manifest,assets,csrf,busy=false,cancelled=false,serverId,preset='quick',runnerAvailable=false,ready=false,report,timingController,selectedItem,runController,localAssetsReady=!external;
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
const yieldFrame=()=>new Promise(requestAnimationFrame);
const output=$('results');
const buttons=[];
for(const suite of suites){
 const label=document.createElement('label');label.className='flex items-start gap-3 p-2 rounded hover:bg-base-200';
 const input=document.createElement('input');input.type='checkbox';input.className='checkbox checkbox-sm mt-1';input.value=suite.id;input.id='suite-'+suite.id;
 const text=document.createElement('span');text.className='min-w-0';const title=document.createElement('span');title.className='block font-medium text-sm';title.textContent=suite.label;
 const description=document.createElement('span');description.className='block muted text-xs';description.textContent=suite.description;
 if(suite.id==='server'){input.disabled=true;description.textContent+=' · Checking runner availability…';}
 if(suite.mods){input.disabled=true;description.textContent+=' · Looking for the mod files…';}
 text.append(title,description);label.append(input,text);$('suite-options').append(label);input.addEventListener('change',()=>{preset='custom';updateSelection();});
 if(!['server','units'].includes(suite.id)){const b=document.createElement('button');b.id='test-'+suite.id;b.className='btn btn-sm';b.textContent=suite.label;b.onclick=()=>run([suite.id]);buttons.push(b);$('individual-tests').append(b);}
}
function selected(){return suites.filter(s=>$('suite-'+s.id).checked&&!$('suite-'+s.id).disabled).map(s=>s.id);}
const modReady=suite=>!suite.mods||!!modSets[suite.mods];
async function findModSets(){
 try{
  const catalogue=await (await fetch('/web/mods/catalogue.json'+version,{cache:'no-cache'})).json(),byId=new Map(catalogue.files.map(f=>[f.id,f.sha256]));
  for(const kind of ['hyperspace','multiverse']){
   const shas=catalogue.presets.find(p=>p.id===kind)?.files.map(id=>byId.get(id))??[];
   if(preloaded){if(preloaded.mods.length===shas.length&&preloaded.mods.every((m,i)=>m.sha256===shas[i]))modSets[kind]={preloaded:true,shas};continue;}
   const {getArchive}=await import('./mods/store.mjs'+version);
   if(shas.length&&(await Promise.all(shas.map(sha=>getArchive(sha).catch(()=>null)))).every(Boolean))modSets[kind]={shas};
  }
 }catch(e){console.warn('Mod library unavailable for the Tests page',e);}
 for(const suite of suites.filter(s=>s.mods)){const ready=modReady(suite),input=$('suite-'+suite.id);input.disabled=!ready;if(!ready)input.checked=false;
  input.parentElement.querySelector('.muted').textContent=suite.description+(ready?'':' · '+modNotes[suite.mods]);if(preset==='all')input.checked=ready;}
 updateSelection();
}
function updateSelection(){
 $('custom-selection').hidden=preset==='benchmark';
 $('selection-count').textContent=`Customize checks · ${suites.filter(s=>$('suite-'+s.id).checked).length} selected`;
 $('benchmark-settings').hidden=!$('suite-performance').checked;
 $('run-tests').textContent=preset==='quick'?'Run quick checks':preset==='all'?'Run all tests':preset==='benchmark'?'Run benchmark':'Run selected';
 $('run-tests').disabled=!ready||busy||!selected().length||(!localAssetsReady&&selected().some(needsEngine));
 buttons.forEach(b=>b.disabled=busy||!localAssetsReady||!modReady(suites.find(s=>'test-'+s.id===b.id)));
 $('measure-browser').disabled=!ready||busy;
 $('selection-description').textContent=preset==='quick'?'Focused engine, audio and browser checks.':preset==='all'?(admin?'Every available test, including isolated server and browser integration checks.':'Every available local browser test. Your cloud progress is preserved.'):preset==='benchmark'?'Measure performance on this browser and PC.':'Run the checked items in order.';
 document.querySelectorAll('.preset').forEach(b=>{b.classList.toggle('btn-primary',b.dataset.preset===preset);b.setAttribute('aria-pressed',String(b.dataset.preset===preset));});
}
function choose(name){preset=name;for(const suite of suites)$('suite-'+suite.id).checked=name==='quick'?quick.includes(suite.id):name==='all'?suite.id!=='performance'&&modReady(suite):suite.id==='performance';updateSelection();}
document.querySelectorAll('.preset').forEach(b=>b.onclick=()=>choose(b.dataset.preset));
choose('quick');
findModSets();
async function api(path,method='GET',body){
 if(method!=='GET')csrf=(await(await fetch('/api/auth/csrf',{cache:'no-store'})).json()).token;
 const r=await fetch('/api'+path,{method,cache:'no-store',headers:method==='GET'?{}:{'Content-Type':'application/json','X-CSRF-TOKEN':csrf},body:body?JSON.stringify(body):undefined});
 if(!r.ok){const e=await r.json().catch(()=>null);throw Error(e?.error??'Request failed (HTTP '+r.status+')');}return r.status===204?null:r.json();
}
if(admin){try{const state=await api('/tests');runnerAvailable=state.available;$('suite-server').disabled=!runnerAvailable;$('runner-note').textContent=runnerAvailable?'Integration checks run in disposable server instances. One server run at a time.':'The packaged server runner is unavailable.';$('suite-server').parentElement.querySelector('.muted').textContent=suites.find(s=>s.id==='server').description;const latest=state.runs.at(-1);if(latest?.status==='running')queueMicrotask(()=>run(['server'],latest.id));}catch(e){$('runner-note').textContent=e.message;}finally{ready=true;updateSelection();}}else{ready=true;updateSelection();}
function localAssetReadiness(available){
 localAssetsReady=available;updateSelection();
 if(busy)return;
 if(!available){$('preview-empty').hidden=false;$('preview-empty').textContent=uploadPrompt;$('preview-status').textContent='ftl.dat required';document.querySelector('.diagnostic-local').open=true;status(uploadPrompt);}
 else if(!$('preview-empty').hidden){$('preview-empty').textContent='Start an engine check or benchmark to view the temporary run.';$('preview-status').textContent='No engine running';status('Ready');}
}
if(external){try{const config=await api('/game/config'),{mountLocalAssets}=await import('./local-assets.mjs');mountLocalAssets({account:document.body.dataset.account,config,onChanged:record=>localAssetReadiness(config.assetSource==='server'||!!record&&record.index.assetVersion===config.assetVersion&&record.index.parserVersion===1),message:text=>status(text)});}catch(e){$('preview-empty').textContent=e.message;$('preview-status').textContent='Game files unavailable';status(e.message);}}
function setOutput(text){output.textContent=text.length>256000?'[Earlier display output truncated; case results remain in the report]\n'+text.slice(-192000):text;if($('follow-output').checked)output.scrollTop=output.scrollHeight;}
function log(text){setOutput(output.textContent+(output.textContent.endsWith('\n')?'':'\n')+text+'\n');}
window.ftlTestProgress=phase=>{log(phase);console.info('TEST '+phase);};
function status(text){$('test-status').textContent=text;}
async function authorize(){const identity=await api('/auth/me');if(!identity.capabilities?.play)throw Error('Sign in and complete any required password change before running checks.');if(identity.id!==document.body.dataset.account||identity.role!==document.body.dataset.role)throw Error('The active account changed. Reload before running checks.');}
const resultViews={cases:'case-results',benchmark:'benchmark-results',activity:'activity-results'};
function showView(name){for(const [key,id] of Object.entries(resultViews)){const active=key===name;$(id).hidden=!active;const tab=$('tab-'+key);tab.classList.toggle('tab-active',active);tab.setAttribute('aria-selected',String(active));tab.tabIndex=active?0:-1;}if(name==='activity'&&$('follow-output').checked)output.scrollTop=output.scrollHeight;}
for(const [index,tab] of [...document.querySelectorAll('[data-result-view]')].entries()){
 tab.onclick=()=>showView(tab.dataset.resultView);
 tab.onkeydown=e=>{const tabs=[...document.querySelectorAll('[data-result-view]')];let next;if(e.key==='ArrowRight')next=(index+1)%tabs.length;else if(e.key==='ArrowLeft')next=(index+tabs.length-1)%tabs.length;else if(e.key==='Home')next=0;else if(e.key==='End')next=tabs.length-1;else return;e.preventDefault();tabs[next].click();tabs[next].focus();};
}
function filterResults(){const filter=$('result-filter').value;let visible=0;for(const row of $('test-results').rows){const value=row.dataset.status;row.hidden=filter==='attention'?value==='passed':filter!=='all'&&value!==filter;if(!row.hidden)visible++;}const empty=$('case-empty');empty.hidden=visible>0;empty.textContent=report?.error?'Run failed: '+report.error:!report?.results.length?'No completed cases yet.':filter==='attention'?'No cases need attention. Select All cases to inspect passed checks.':'No cases match this filter.';}
$('result-filter').onchange=filterResults;
function detail(item){selectedItem=item;$('case-detail-name').textContent=item.name+' · '+item.status;$('case-detail-output').textContent=item.error??(typeof item.output==='string'?item.output:JSON.stringify(item.output??'Running…',null,2));$('case-detail').open=true;}
function summarize(){const counts={passed:0,failed:0,cancelled:0};for(const item of report.results)counts[item.status]=(counts[item.status]??0)+1;$('test-summary').textContent=`${counts.passed} passed · ${counts.failed} failed${counts.cancelled?' · '+counts.cancelled+' cancelled':''}`;filterResults();}
function lockControls(value){document.querySelectorAll('.preset,#suite-options input,#benchmark-settings select').forEach(e=>e.disabled=value);if(!value){if(admin)$('suite-server').disabled=!runnerAvailable;for(const suite of suites.filter(s=>s.mods))$('suite-'+suite.id).disabled=!modReady(suite);}updateSelection();$('stop-tests').disabled=!value&&!serverId;}
async function record(label,action){
 if(cancelled)throw new DOMException('Stopped','AbortError');
 status(label);await yieldFrame();
 log('RUN '+label);
 const item={name:label,status:'running',started:new Date().toISOString()},row=document.createElement('tr');
 if(engine)item.before=JSON.parse(engine.debugSnapshot());
 const cells=[label,'Running','—'].map(text=>{const td=document.createElement('td');td.textContent=text;return td;});const inspect=document.createElement('button');inspect.textContent=label;inspect.onclick=()=>detail(item);cells[0].replaceChildren(inspect);row.dataset.status='running';row.append(...cells);$('test-results').append(row);filterResults();
 const start=performance.now();
 try{const result=await action();if(typeof result==='string'&&!result.startsWith('PASS'))throw Error(result);item.status='passed';item.output=result;log(typeof result==='string'?result:'PASS '+label);return result;}
 catch(e){if(cancelled)e=new DOMException('Stopped','AbortError');item.status=e?.name==='AbortError'?'cancelled':'failed';item.error=errorText(e);log(item.status.toUpperCase()+' '+label+': '+item.error);if(e?.name==='AbortError')throw e;}
 finally{item.durationMs=performance.now()-start;row.dataset.status=item.status;cells[1].textContent=item.status;cells[1].className=item.status==='passed'?'text-success':item.status==='failed'?'text-error':'muted';cells[2].textContent=(item.durationMs/1000).toFixed(2)+' s';report.results.push(item);if(item.status==='failed'){$('result-filter').value='attention';showView('cases');}summarize();if(selectedItem===item)detail(item);}
}
async function fetchBytes(path){const r=await fetch(path,{cache:'no-cache'});if(!r.ok)throw Object.assign(Error('Game resources unavailable (HTTP '+r.status+')'),{status:r.status});return r.arrayBuffer();}
// The loading screen over the preview while an engine starts, instead of the new canvas's black.
function showLoading(text){$('preview-empty').hidden=false;$('preview-empty').textContent=text;$('preview-status').textContent='Loading…';}
let modLaunch=null;
/** The merged mod files of `kind` for this run (one set kept at a time; a packaged fixture's data is already merged). */
async function modFiles(kind){
 if(modSets[kind].preloaded)return null;
 if(modLaunch?.kind===kind)return modLaunch.launch;
 modLaunch=null;
 const {prepareModdedLaunch}=await import('./mods/boot.mjs'+version);
 const launch=await prepareModdedLaunch({account:document.body.dataset.account,list:modSets[kind].shas.map(sha256=>({sha256})),provider:assets,onProgress:status});
 modLaunch={kind,launch};return launch;
}
async function boot(ae,mods=null,label='the temporary engine'){
 showLoading('Loading '+label+'…');
 engine?.stop();engine=null;window.ftlGL?.gl.getExtension('WEBGL_lose_context')?.loseContext();
 const canvas=$('game').cloneNode(false);$('game').replaceWith(canvas);
 if(!preloaded)delete window.ftlModSession;// a vanilla run after a modded one
 // The Lua runtime answers through the engine it was made with (hs-lua.mjs keeps one per page): each engine gets its own,
 // or a later engine's scripts call into the stopped one (and keep it in memory).
 delete window.ftlHsLua;
 const launch=mods?await modFiles(mods):null;
 engine=await loadGame({canvas:$('game'),manifest:launch?.manifest??manifest,fetchBoot:launch?.fetchBoot??assets.fetchBoot,fetchAudio:launch?.fetchAudio??assets.fetchAudio,mods:launch?.overlay??null,onProgress:status,onSave:()=>{},ae,audioContext,closeAudioContext:false});engine.suspend(true);window.diagnosticSeed('71');$('preview-empty').hidden=true;$('preview-status').textContent='Temporary run';
}
const probes={mechanics:'mechanicsProbe',progress:'progressProbe',combat:'combatProbe',interaction:'interactionProbe',warnings:'warningsProbe','gameplay-options':'optionsProbe','gameplay-parity':'gameplayParityProbe','ae-mode':'aeProbe',drones:'droneProbe',unlocks:'unlockProbe',achievements:'achievementProbe',tutorial:'tutorialProbe','tutorial-isolation':'tutorialIsolationProbe','tutorial-gates':'tutorialGatesProbe','tutorial-adapter':'tutorialAdapterProbe','tutorial-overlay':'tutorialOverlayProbe','tutorial-entry':'tutorialEntryProbe','tutorial-encounters':'tutorialEncountersProbe','tutorial-play':'tutorialPlayProbe'};
// Browser modules: checks of the page's own code with in-memory inputs only (no saves, account, mod library or storage).
const vectors='/web/test-vectors/mods/',loadVector=name=>name.endsWith('.json')?fetch(vectors+name,{cache:'no-cache'}).then(r=>{if(!r.ok)throw Error('Test vectors unavailable (HTTP '+r.status+')');return r.json();}):import(vectors+name);
const unitCases=[['Audio clock',audioClockChecks],['Frame pacing',framePacingChecks],['Diagnostic ZIP export',diagnosticExportChecks],
 ['Save sync rules',async()=>{const [{loadSaveCore},{saveSyncChecks}]=await Promise.all([import('./savecore.mjs'+version),import('./self-tests-saves.mjs'+version)]);return saveSyncChecks({core:await loadSaveCore(version),assert:pageAssert});}],
 ['Mod merge',async()=>{const m=await import('./self-tests-mods.mjs'+version);return (await m.appendChecks({assert:pageAssert,load:loadVector}))+'\n'+(await m.f1Checks({assert:pageAssert,load:loadVector}));}],
 ['Mod Lua patch scripts',async()=>{const m=await import('./self-tests-mods.mjs'+version);return (await m.luaCaseChecks({assert:pageAssert,load:loadVector}))+'\n'+(await m.l1Checks({assert:pageAssert,load:loadVector}));}]];
// Hyperspace / Multiverse: the vanilla probes again on the mod's data (each on a fresh engine, as the packaged p-F2/p-F3 modes)
// and the Hyperspace probes of tests/hs-*.mjs that need nothing but the mod files.
const gameplayRules=[['Mechanics','mechanicsProbe'],['Targeting & final sector','combatProbe'],['Shield & battle drones','droneProbe'],['Desktop weapons','interactionProbe'],
 ['Gameplay parity','gameplayParityProbe'],['Run statistics','progressProbe'],['Achievements','achievementProbe'],['Ship unlocks','unlockProbe']];
const modParts={'mv-weapons':['hsWeaponsProbe',['units','weapons','drones','pinpoint']],'mv-systems':['hsSystemsProbe',['units']],'mv-crew':['hsCrewUiProbe',['move','tooltip','clone','lua']],'mv-hazards':['hsWp1Probe',['chains','scene']]};
/** Let the engine run its frames until it is back at the main menu (a probe part may end in a transition), at most 10 s. */
async function settleMenu(){engine.suspend(false);try{const end=performance.now()+10000;while(JSON.parse(engine.debugSnapshot()).state!=='MainMenuState'){if(cancelled)throw new DOMException('Stopped','AbortError');if(performance.now()>end)throw Error('The engine did not return to the main menu');await sleep(100);}}finally{engine.suspend(true);}}
/** The capability report and modded saves (tests/hs-core.mjs); for Multiverse also its hyperspace.xml values, read by hand from that file. */
function hsCore(multiverse){
 const report=JSON.parse(window.hsCapabilityReport());
 if(!report.modded||!report.hyperspace)throw Error('The mod data did not load: modded '+report.modded+', hyperspace '+report.hyperspace);
 if(multiverse){const v=report.config.values;
  pageAssert.deepEqual({forceDlc:v.forceDlc,seedsEnabled:v.seedsEnabled,defaultCheckCargo:v.defaultCheckCargo,ion:[v.controllableIonDroneDelay,v.controllableIonDroneDelayInitial],
   dummy:v.dummyEquipmentSlotsOpacity,invertCaps:v.consoleInvertCaps,rounding:v.advancedCrewTooltipRounding,purchaseLimit:v.purchaseLimit,threshold:v.systemNoPurchaseThreshold,save:[v.savePrefix],stores:v.nodes.store,versionOk:report.config.versionOk},
   {forceDlc:true,seedsEnabled:true,defaultCheckCargo:true,ion:[10,6],dummy:0.3,invertCaps:false,rounding:2,purchaseLimit:[0,-16,106],threshold:[true,999,'--'],save:['hs_mv'],stores:2,versionOk:true},'Multiverse hyperspace.xml values');}
 // The run round trip on Multiverse holds the page for many minutes (hs-core.mjs with HS_EXPECT=mv covers it): here only
 // the profile and the mod set's checks run for Multiverse.
 const probe=window.hsCoreProbe(multiverse?'':'PLAYER_SHIP_HARD');
 return probe.startsWith('PASS')?probe+(multiverse?' · Multiverse hyperspace.xml values':''):probe;
}
async function checkAudio(){
 const audio=window.ftlAudio,paths=[manifest.audio.find(a=>/title/i.test(a.name))?.name,manifest.audio.find(a=>a.name.includes('BATTLE'))?.name,manifest.audio.find(a=>a.name.endsWith('.wav'))?.name,manifest.audio.find(a=>a.name.includes('Hacking_Drone_Land'))?.name];
 for(const path of paths){if(!path)throw Error('Missing representative audio asset');const id=audio.buffer(path),decoded=await audio.buffers.get(id).promise;if(!decoded||decoded.duration<=0)throw Error('Audio decode failed: '+path);log('PASS decoded '+path+' '+decoded.duration.toFixed(2)+' s');}
 const source=audio.call('source');try{audio.call('sourcei',source,0x1009,audio.buffer(paths[2]));audio.call('play',source);const deadline=performance.now()+5000;while(audio.context.state!=='running'||!audio.sources.get(source).node){if(cancelled)throw new DOMException('Stopped','AbortError');if(performance.now()>deadline)throw Error('Web Audio playback did not start within 5 seconds');await sleep(25);}audio.call('pause',source);if(audio.call('getSource',source,0x1010)!==0x1013)throw Error('Audio pause failed');audio.call('play',source);await yieldFrame();audio.call('stop',source);}finally{audio.call('deleteSource',source);}return 'PASS original audio decoding, playback, pause/resume and stop';
}
async function benchmark(){
 showView('benchmark');$('benchmark-note').textContent='Measuring browser callbacks and game drawings. Work timings measure CPU submission, not GPU completion.';engine.suspend(false);window.diagnosticFrameLimit(Number($('benchmark-limit').value));
 for(const phase of [1,2,3])await record('Benchmark phase '+phase,async()=>{
  window.performancePreset(phase);const sample=await engine.benchmark(Number($('benchmark-duration').value));if(cancelled)throw new DOMException('Stopped','AbortError');
  report.benchmark.push({phase,fpsLimit:engine.frameRateLimit(),...sample});const tr=document.createElement('tr');for(const value of [phase,engine.frameRateLimit()||'Unlimited',sample.fps.toFixed(1),sample.browserFps?.toFixed(1)??'Unavailable',sample.frameP95.toFixed(2)+' ms',sample.updateP95.toFixed(2)+' ms',sample.renderP95.toFixed(2)+' ms',sample.heapMiB===null?'Unavailable':sample.heapMiB+' MiB']){const td=document.createElement('td');td.textContent=value;tr.append(td);}$('benchmark-rows').append(tr);
  $('benchmark-note').textContent=sample.browserFps&&sample.fps>=sample.browserFps*.95?'Game drawings follow the available browser callbacks. Use Check browser rate to compare without game workload.':'Compare game FPS, browser callbacks and the selected limit. Browser timing can also slow under workload.';return sample;
 });engine.suspend(true);
}
async function serverTests(resumeId){
 serverId=resumeId??(await api('/tests','POST',{suites:['unit','security','menus','diagnostics','storage','production','engine']})).id;
 let previous='';
 while(true){
  const job=await api('/tests/'+serverId);report.server=job;
  if(job.log!==previous){setOutput(job.log);previous=job.log;}
  status('Server integration: '+(job.currentSuite??'starting')+' · '+job.results.length+'/'+job.suites.length+' suites complete');
  if(job.status!=='running'){serverId=null;if(job.status==='cancelled')throw new DOMException('Stopped','AbortError');if(job.status!=='passed')throw Error('One or more isolated integration suites failed. See detailed output.');return job.results;}
  if(cancelled)await api('/tests/'+serverId+'/stop','POST');await sleep(1000);
 }
}
async function run(ids,resumeId=null){
 if(!busy&&!localAssetsReady&&ids.some(needsEngine)){localAssetReadiness(false);return;}
 if(busy)return;busy=true;cancelled=false;runController=new AbortController();document.body.dataset.workload='active';output.textContent='';$('test-results').replaceChildren();$('benchmark-rows').replaceChildren();$('test-summary').textContent='Starting…';selectedItem=null;$('case-detail').open=false;$('result-filter').value='attention';showView(ids.includes('performance')?'benchmark':'cases');$('preview-status').textContent='Not used by selected checks';
 status('Starting…');$('benchmark-note').textContent=ids.includes('performance')?'Waiting for benchmark measurements…':'No benchmark selected for this run.';$('preview-empty').hidden=false;$('preview-empty').textContent=ids.some(id=>!['units','server'].includes(id))?'Loading the temporary engine…':'These checks do not use the game engine.';
 report={schema:2,status:'running',started:new Date().toISOString(),browser:navigator.userAgent,viewport:{width:innerWidth,height:innerHeight,devicePixelRatio},scenarioSeed:71,selected:ids,results:[],benchmark:[],browserTiming:browserTiming.slice()};$('export-results').disabled=false;filterResults();lockControls(true);
 const progress=$('test-progressbar');progress.max=ids.length;progress.value=0;
 try{
  await authorize();if(cancelled)throw new DOMException('Stopped','AbortError');
  if(ids.some(id=>!['units','server'].includes(id))){audioContext=new AudioContext();audioContext.resume().catch(e=>log('Audio startup failed: '+e.message));assets=await createAssetProvider({api,accountId:document.body.dataset.account,fetchBytes,onProgress:status,signal:runController.signal});manifest=assets.manifest;report.build=manifest.clientVersion;report.engineBuild=manifest.engineBuild;report.assetVersion=manifest.assetVersion;}
  for(const [index,id] of ids.entries()){
   if(cancelled)break;const suite=suites.find(s=>s.id===id),label=suite.label,initial=report.results.length;
   // A second between categories with the loading screen up, so the next start shows instead of a black flash.
   // Cases inside a category follow each other at once.
   if(index>0){showLoading('Loading '+label+'…');await sleep(1000);if(cancelled)break;}
   if(id==='units'){for(const [name,action] of unitCases)await record(name,action);}
   else if(id==='server')await record(label,()=>serverTests(resumeId));
   else if(id==='hs-rules'||id==='mv-rules'){for(const [name,probe] of gameplayRules){await boot(true,suite.mods,label);if(cancelled)throw new DOMException('Stopped','AbortError');await record((id==='hs-rules'?'Hyperspace':'Multiverse')+' · '+name,()=>window[probe]());}}
   else{
    await boot(id!=='vanilla',suite.mods??null,label);if(cancelled)throw new DOMException('Stopped','AbortError');
    if(suite.mods)await settleMenu();
    if(id==='hs-core'||id==='mv-core')await record(label,()=>hsCore(id==='mv-core'));
    else if(id==='mv-lua')await record(label,()=>window.hsLuaProbe(0));
    else if(modParts[id]){const [probe,parts]=modParts[id];for(const part of parts){await record(label+' · '+part,()=>window[probe](part));await settleMenu();}}
    else if(id==='combat'){await record(label,()=>window.combatProbe());window.diagnosticSeed('35');await record('Combat map regression · seed 35',()=>window.combatProbe());}
    else if(probes[id])await record(label,()=>window[probes[id]]());
    else if(id==='audio')await record(label,checkAudio);
    else if(id==='performance')await benchmark();
    else if(id==='hs-lua'){await (await import('./hs-lua.mjs'+version)).loadHsLua(version);await record(label,()=>window.hsLuaProbe(0));}
    else if(id==='campaign'){for(const ae of [true,false]){if(!ae)await boot(false);for(let difficulty=0;difficulty<3;difficulty++)for(const seed of [71,537])await record(`${ae?'AE':'Vanilla'} campaign · difficulty ${difficulty} · seed ${seed}`,()=>window.campaignProbe(difficulty,seed));}}
    else if(id==='encounters'){const [from,count]=(new URLSearchParams(location.search).get('encounters')??'').split(',').map(Number),all=window.encounterList().split('\n');for(const name of count?all.slice(from,from+count):all)if(await record('Encounter '+name,()=>window.encounterProbe(name,6))===undefined)await boot(true);/* an engine that threw is not fit for the next case */}
    else{const ships=window.shipList().split('\n');for(const ship of ships)for(let difficulty=0;difficulty<3;difficulty++)await record(ship+' · difficulty '+difficulty,()=>window.probe(ship,difficulty));}
   }
   const items=report.results.slice(initial),failed=items.filter(i=>i.status==='failed').length;
   log(`DONE ${id}: ${items.filter(i=>i.status==='passed').length} passed, ${failed} failed`);progress.value++;
  }
 }catch(e){if(e.name==='AbortError')cancelled=true;else{report.error=e.message;log('FATAL '+e.message);if(e.code==='LOCAL_ASSETS_REQUIRED')localAssetsReady=false;if(!$('preview-empty').hidden){$('preview-empty').textContent=e.code==='LOCAL_ASSETS_REQUIRED'?uploadPrompt:e.message;$('preview-status').textContent=e.code==='LOCAL_ASSETS_REQUIRED'?'ftl.dat required':'Unable to start';if(e.code==='LOCAL_ASSETS_REQUIRED')document.querySelector('.diagnostic-local').open=true;}}}
 finally{
  try{engine?.stop();}catch(e){report.error??=e.message;log('Cleanup failed: '+e.message);}engine=null;if($('preview-empty').hidden)$('preview-status').textContent='Finished · last frame';report.finished=new Date().toISOString();report.status=cancelled?'cancelled':report.error||report.results.some(r=>r.status==='failed')?'failed':'passed';
  await audioContext?.close().catch(()=>{});audioContext=null;
  assets?.dispose();assets=null;modLaunch=null;runController=null;document.body.dataset.workload='';busy=false;
  status(report.status==='passed'?'Completed':report.status==='cancelled'?'Stopped':'Completed with failures');log('DONE '+report.status);summarize();
  if(report.error)showView('cases');lockControls(false);
 }
}
$('run-tests').onclick=()=>run(selected());
$('stop-tests').onclick=async()=>{cancelled=true;runController?.abort();timingController?.abort();status('Stopping after the current case…');try{if(serverId)await api('/tests/'+serverId+'/stop','POST');const active=engine;engine=null;active?.stop();}catch(e){status(e.message);}};
const browserTiming=[];
document.addEventListener('visibilitychange',()=>{if(document.hidden&&timingController)timingController.abort(new DOMException('Keep this page visible while measuring browser timing','AbortError'));});
$('measure-browser').onclick=async()=>{
 if(busy)return;document.body.dataset.workload='active';busy=true;cancelled=false;timingController=new AbortController();lockControls(true);status('Measuring browser callbacks…');$('browser-rate').textContent='Measuring for 3 seconds…';
 try{await authorize();if(timingController.signal.aborted)throw timingController.signal.reason;if(cancelled)throw new DOMException('Stopped','AbortError');const sample={at:new Date().toISOString(),...await measureBrowserRate(3000,{signal:timingController.signal})};browserTiming.push(sample);if(report)report.browserTiming=browserTiming.slice();else report={schema:2,status:'passed',started:sample.at,finished:new Date().toISOString(),browser:navigator.userAgent,selected:[],results:[],benchmark:[],browserTiming:browserTiming.slice()};$('export-results').disabled=false;$('browser-rate').textContent=sample.fps.toFixed(1)+' callbacks/s · '+sample.intervalP95.toFixed(2)+' ms p95';log('BROWSER TIMING '+JSON.stringify(sample));status('Browser timing measured');}
 catch(e){$('browser-rate').textContent=e.message;log('BROWSER TIMING STOPPED: '+e.message);status('Browser timing stopped');}
 finally{document.body.dataset.workload='';timingController=null;busy=false;lockControls(false);}
};
$('export-results').onclick=()=>{const blob=new Blob([JSON.stringify(report,null,2)],{type:'application/json'}),url=URL.createObjectURL(blob),a=document.createElement('a');a.href=url;a.download='ftl-tests-'+new Date().toISOString().replaceAll(':','-')+'.json';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);};
window.addEventListener('beforeunload',e=>{if(busy){e.preventDefault();e.returnValue='';}});

window.addEventListener('ftl-session-changed',()=>{cancelled=true;runController?.abort();timingController?.abort();engine?.stop();busy=false;});
