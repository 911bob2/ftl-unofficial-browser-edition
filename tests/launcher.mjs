import assert from 'node:assert/strict';
import {withOnboarding} from './lib/onboarding.mjs';
import {readFile,writeFile} from 'node:fs/promises';
import {chromium,firefox} from 'playwright';
const origin=process.env.LAUNCHER_ORIGIN??'http://localhost:8084',checks=[],jobs=process.env.LAUNCHER_TEST_JOBS==='1';
const credentials=new Map((await readFile((process.env.DEBUG_FIXTURE_DIR??'.build/debug-data')+'/bootstrap-credentials.txt','utf8')).trim().split(/\r?\n/).map(l=>l.split('\t')));
const deadline=Date.now()+30000;
while(!await fetch(origin+'/health/ready').then(r=>r.ok).catch(()=>false)){if(Date.now()>deadline)throw Error('Disposable launcher fixture did not start');await new Promise(r=>setTimeout(r,250));}
const pass=label=>{checks.push(label);console.log('PASS '+label);};
async function login(page,user='admin'){
 await page.goto(origin);await page.waitForFunction(()=>document.body.dataset.sessionReady==='true');
 await page.evaluate(async({user,initial})=>{
  const token=async()=>(await(await fetch('/api/auth/csrf')).json()).token;
  let csrf=await token();const post=(path,body)=>fetch('/api/auth/'+path,{method:'POST',headers:{'Content-Type':'application/json','X-CSRF-TOKEN':csrf},body:JSON.stringify(body)});
  const password='launcher-fixture-password-'+user;let r=await post('login',{username:user,password});
  if(r.status===401)r=await post('login',{username:user,password:initial});if(!r.ok)throw Error('Fixture login: '+r.status);
  csrf=await token();if((await(await fetch('/api/auth/me')).json()).mustChangePassword){r=await post('password',{currentPassword:initial,newPassword:password});if(!r.ok)throw Error('Fixture rotation');csrf=await token();if(!(await post('login',{username:user,password})).ok)throw Error('Rotated login');}
 },{user,initial:credentials.get(user)});
}
async function api(page,path,body,method=body?'POST':'GET'){return page.evaluate(async({path,body,method})=>{
 const token=body?(await(await fetch('/api/auth/csrf')).json()).token:null;
 const r=await fetch('/api'+path,{method,headers:body?{'Content-Type':'application/json','X-CSRF-TOKEN':token}:{},body:body?JSON.stringify(body):undefined});return {status:r.status,body:r.status===204?null:await r.json().catch(()=>null)};
},{path,body,method});}
// Playwright's waitForFunction treats a returned Promise as truthy before it
// settles. Poll network state here so a false async result cannot end the wait.
async function waitForJob(page,id,predicate,timeout=90000){
 const deadline=Date.now()+timeout;
 while(true){
  const response=await api(page,'/tests/'+id);
  assert.equal(response.status,200,'Job polling HTTP status');
  if(predicate(response.body))return response.body;
  if(Date.now()>=deadline)throw Error('Job did not reach the expected state: '+response.body.status);
  await new Promise(resolve=>setTimeout(resolve,250));
 }
}
async function exported(page){const download=page.waitForEvent('download');await page.locator('#export-results').click();return JSON.parse(await readFile(await(await download).path(),'utf8'));}
async function done(page,timeout=120000){await page.waitForFunction(()=>['Completed','Completed with failures','Stopped'].includes(document.getElementById('test-status').textContent),{},{timeout});}
async function clearSelection(page){await page.locator('#custom-selection').evaluate(e=>e.open=true);for(const input of await page.locator('#suite-options input').all())if(await input.isEnabled())await input.uncheck();}
for(const [name,type,options] of [['Chrome',chromium,{channel:'chrome'}],['Edge',chromium,{channel:'msedge'}],['Firefox',firefox,{}]]){
 const browser=withOnboarding(await type.launch({headless:!(name==='Firefox'&&process.env.RUNNER_FIREFOX_WINDOWED==='1'),...options}),origin),page=await browser.newPage({viewport:{width:1440,height:1000}}),errors=[];
 page.setDefaultTimeout(20000);page.on('pageerror',e=>errors.push(e.message));
 try{
  assert.equal((await page.request.get(origin+'/Diagnostics')).status(),403);
  assert.equal((await page.request.get(origin+'/api/tests')).status(),401);
  await login(page);await page.goto(origin);await page.locator('#launch-tests').click();await page.waitForURL('**/Diagnostics');
  await page.locator('#run-tests').waitFor({state:'visible'});await page.waitForFunction(()=>!document.getElementById('run-tests').disabled);
  assert.equal(await page.locator('[aria-current="page"]').innerText(),'Tests');
  assert.ok(await page.evaluate(()=>{const ids=[...document.querySelectorAll('[id]')].map(e=>e.id);return new Set(ids).size===ids.length;}),'Duplicate launcher control IDs');
  const before=await api(page,'/saves/current');
  await page.evaluate(()=>{localStorage.setItem('ftl-theme','light');localStorage.setItem('ftl-design','minimal');});
  await page.emulateMedia({colorScheme:'light'});await page.reload();
  assert.equal(await page.locator('html').getAttribute('data-theme'),'ftl');
  assert.equal(await page.locator('.appearance').count(),0);
  const available=(await api(page,'/tests')).body.available;assert.equal(available,jobs);
  if(name==='Chrome'){
   await page.locator('#run-tests').click();await done(page);
   const report=await exported(page);assert.equal(report.status,'passed',JSON.stringify(report));assert.equal(report.results.length,16);
   assert.equal(report.results.find(r=>r.name.includes('seed 35')).before.rng,'35');
   assert.ok(await page.locator('#test-progressbar').evaluate(p=>p.value===p.max));
   await writeFile('.build/launcher-quick-results.json',JSON.stringify(report,null,2));
   pass('Chrome: Quick checks execute all eleven engine probes, original audio and shared module tests');
  }else{
   await clearSelection(page);await page.locator('#suite-units').check();await page.locator('#run-tests').click();await done(page);
   const report=await exported(page);assert.equal(report.status,'passed');assert.equal(report.results.length,3);
   pass(name+': Module checks and JSON report export work');
  }
  await page.locator('.preset[data-preset="all"]').click();assert.ok(await page.locator('#suite-campaign').isChecked());assert.ok(await page.locator('#suite-server').isChecked());assert.ok(!await page.locator('#suite-performance').isChecked());
  assert.ok(await page.locator('#suite-tutorial').isChecked());
  assert.ok(await page.locator('#suite-warnings').isChecked());
  assert.ok(await page.locator('#suite-gameplay-options').isChecked());
  assert.ok(await page.locator('#suite-gameplay-parity').isChecked());
  assert.ok(await page.locator('#suite-tutorial-isolation').isChecked());
  assert.ok(await page.locator('#suite-tutorial-gates').isChecked());
  assert.ok(await page.locator('#suite-tutorial-adapter').isChecked());
  assert.ok(await page.locator('#suite-tutorial-overlay').isChecked());
  assert.ok(await page.locator('#suite-tutorial-entry').isChecked());
  assert.ok(await page.locator('#suite-tutorial-encounters').isChecked());
  assert.ok(await page.locator('#suite-tutorial-play').isChecked());
  await page.locator('#individual-tests').evaluate(e=>e.parentElement.open=true);
  await page.locator('#test-tutorial').click();await done(page);
  const tutorialReport=await exported(page);assert.equal(tutorialReport.status,'passed');assert.deepEqual(tutorialReport.selected,['tutorial']);assert.equal(tutorialReport.results.length,1);
  assert.ok(tutorialReport.results[0].output.includes('33 official desktop tutorial steps'));
  assert.ok(tutorialReport.results[0].output.includes('Data coverage only'));
  pass(name+': Tutorial data is included in All tests and exports its limited coverage clearly');
  await page.locator('#test-tutorial-isolation').click();await done(page);
  const isolationReport=await exported(page);assert.equal(isolationReport.status,'passed');assert.deepEqual(isolationReport.selected,['tutorial-isolation']);assert.equal(isolationReport.results.length,1);
  assert.ok(isolationReport.results[0].output.includes('failed initialization and shutdown'));
  assert.ok(isolationReport.results[0].output.includes('instruction flow is tested separately'));
  pass(name+': Tutorial isolation covers saves, failures and exit without claiming instruction flow');
  await page.locator('#test-tutorial-gates').click();await done(page);
  const gatesReport=await exported(page);assert.equal(gatesReport.status,'passed');assert.deepEqual(gatesReport.selected,['tutorial-gates']);assert.equal(gatesReport.results.length,1);
  assert.ok(gatesReport.results[0].output.includes('15 automatic gates, 4 latched Continue gates'));
  assert.ok(gatesReport.results[0].output.includes('native presentation and human play remain unverified'));
  pass(name+': Tutorial gates test and export distinguish controller coverage from interactive flow');
  await page.locator('#test-tutorial-adapter').click();await done(page);
  const adapterReport=await exported(page);assert.equal(adapterReport.status,'passed');assert.deepEqual(adapterReport.selected,['tutorial-adapter']);assert.equal(adapterReport.results.length,1);
  assert.ok(adapterReport.results[0].output.includes('observed from live engine actions'));
  assert.ok(adapterReport.results[0].output.includes('native presentation and human play remain unverified'));
  pass(name+': Tutorial actions test/export covers live engine gates without claiming native setup or UI');
  await page.locator('#test-tutorial-overlay').click();await done(page);
  const overlayReport=await exported(page);assert.equal(overlayReport.status,'passed');assert.deepEqual(overlayReport.selected,['tutorial-overlay']);assert.equal(overlayReport.results.length,1);
  assert.ok(overlayReport.results[0].output.includes('33 tutorial instruction panels/arrows'));
  assert.ok(overlayReport.results[0].output.includes('captured click/drag/reload'));
  assert.ok(overlayReport.results[0].output.includes('native power-box flashes/arrow pulse'));
  assert.ok(overlayReport.results[0].output.includes('native presentation and human play remain unverified'));
  pass(name+': Tutorial instructions test/export covers panels, native power flashes/arrow pulse and captured input');
  await page.locator('#test-tutorial-entry').click();await done(page);
  const entryReport=await exported(page);assert.equal(entryReport.status,'passed');assert.deepEqual(entryReport.selected,['tutorial-entry']);assert.equal(entryReport.results.length,1);
  assert.ok(entryReport.results[0].output.includes('real repair/venting'));
  assert.ok(entryReport.results[0].output.includes('encounter coverage is separate'));
  pass(name+': Tutorial setup test/export covers native entry effects and restoration with separate encounter coverage');
  await page.locator('#test-tutorial-encounters').click();await done(page);
  const encountersReport=await exported(page);assert.equal(encountersReport.status,'passed');assert.deepEqual(encountersReport.selected,['tutorial-encounters']);assert.equal(encountersReport.results.length,1);
  assert.ok(encountersReport.results[0].output.includes('both pirate reward routes'));
  assert.ok(encountersReport.results[0].output.includes('not a complete played tutorial'));
  pass(name+': Tutorial encounters test/export covers events and reward restoration with explicit fixture limits');
  await page.locator('#test-tutorial-play').click();await done(page);
  const playReport=await exported(page);assert.equal(playReport.status,'passed');assert.deepEqual(playReport.selected,['tutorial-play']);assert.equal(playReport.results.length,1);
  assert.ok(playReport.results[0].output.includes('normal combat/rewards'));
  assert.ok(playReport.results[0].output.includes('human play and native presentation comparison remain pending'));
  pass(name+': Tutorial traversal uses normal mechanics and exports remaining human/native acceptance limits');
  await page.locator('.preset[data-preset="benchmark"]').click();await page.locator('#benchmark-duration').selectOption('5000');await page.locator('#benchmark-limit').selectOption('30');
  await page.locator('#run-tests').click();await page.waitForFunction(()=>document.getElementById('test-status').textContent.startsWith('Benchmark phase'));
  assert.ok(await page.locator('#run-tests').isDisabled());assert.ok(await page.locator('.preset').first().isDisabled());
  if(name==='Chrome'){
   await done(page);const report=await exported(page);assert.equal(report.status,'passed',JSON.stringify(report));assert.equal(report.benchmark.length,3);
   for(const sample of report.benchmark){assert.equal(sample.fpsLimit,30);assert.ok(sample.fps>=25&&sample.fps<=35,JSON.stringify(sample));assert.ok(sample.frames>100);assert.ok(sample.frameP95>0);assert.ok(sample.browserFps>=sample.fps-.5,JSON.stringify(sample));assert.ok(sample.browserCallbacks>=sample.frames);if(sample.browserFps>33)assert.ok(sample.browserCallbacks>sample.frames);}
   await writeFile('.build/launcher-benchmark-results.json',JSON.stringify(report,null,2));pass('Chrome: Three measured flagship phases respect the benchmark-only 30 FPS cap');
   await page.locator('#run-tests').click();await page.waitForFunction(()=>document.getElementById('test-status').textContent.startsWith('Benchmark phase'));
  }
  await page.locator('#stop-tests').click();await done(page);assert.equal((await exported(page)).status,'cancelled');
  assert.equal((await api(page,'/saves/current')).body.revision,before.body.revision);assert.deepEqual((await api(page,'/saves/current')).body,before.body);
  pass(name+': Stop cancels a benchmark, restores controls and preserves cloud progress');
  await page.locator('.preset[data-preset="quick"]').click();await page.screenshot({path:'.build/launcher-'+name.toLowerCase()+'.png',fullPage:true});
  await page.setViewportSize({width:390,height:844});await page.screenshot({path:'.build/launcher-mobile-'+name.toLowerCase()+'.png',fullPage:true});
  assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'Page overflows the mobile viewport');
  for(const [label,view] of [['Account','account'],['Saves','saves'],['Logs','logs']]){await page.getByRole('link',{name:label,exact:true}).click();await page.waitForFunction(id=>!document.getElementById(id+'-panel').hidden,view);assert.equal(await page.locator('[aria-current="page"]').innerText(),label);await page.locator('#tests-button').click();await page.waitForURL('**/Diagnostics');}
  pass(name+': Navigation, remembered theme and narrow layout work');
  if(jobs&&name==='Chrome'){
   const otherAdmin=(await api(page,'/admin/users')).body.find(u=>u.userName==='user09');
   assert.equal((await api(page,'/admin/users/'+otherAdmin.id+'/role',{role:'admin',version:otherAdmin.version},'PATCH')).status,204);
   const otherContext=await browser.newContext(),other=await otherContext.newPage();await login(other,'user09');
   for(const suites of [[],['not-a-command'],['unit','../../etc/passwd']])assert.equal((await api(page,'/tests',{suites})).status,400);
   const started=await api(page,'/tests',{suites:['engine']});assert.equal(started.status,200);const id=started.body.id;
   assert.equal((await api(other,'/tests/'+id)).status,404);assert.equal((await api(other,'/tests/'+id+'/stop',{})).status,404);assert.deepEqual((await api(other,'/tests')).body.runs,[]);
   assert.equal((await api(other,'/tests',{suites:['unit']})).status,409);
   await waitForJob(page,id,job=>job.log.includes('PASS DONE')||job.status!=='running',180000);
   assert.equal((await api(page,'/tests/'+id+'/stop',{})).status,204);
   const cancelled=await waitForJob(page,id,job=>job.status!=='running');
   assert.equal(cancelled.status,'cancelled');assert.ok(cancelled.finishedUtc);
   await other.close();pass('Runner: Script whitelist, single-job limit, cancellation and owner isolation');
   await clearSelection(page);await page.locator('#suite-server').check();await page.locator('#run-tests').click();
   await page.waitForFunction(()=>document.getElementById('results').textContent.includes('BEGIN'));
   page.once('dialog',dialog=>dialog.accept());await page.reload();await page.waitForFunction(()=>document.getElementById('test-status').textContent.startsWith('Server integration'));
   await done(page,1200000);const report=await exported(page);await writeFile('.build/launcher-server-results.json',JSON.stringify(report,null,2));
   assert.equal(report.status,'passed',JSON.stringify(report));assert.equal(report.server.status,'passed');assert.equal(report.server.results.length,7);assert.ok(report.server.results.every(r=>r.status==='passed'));
   assert.deepEqual((await api(page,'/saves/current')).body,before.body);pass('Runner: All packaged tests pass, reload resumes progress and live account saves stay unchanged');
  }
  assert.deepEqual(errors,[]);
 }catch(e){console.log('Launcher state:',await page.locator('#test-status').textContent().catch(()=>''));console.log('Launcher output:',await page.locator('#results').textContent().catch(()=>''));console.log('Browser errors:',errors);throw e;}finally{await browser.close();}
}
await writeFile('.build/launcher-results.json',JSON.stringify({at:new Date().toISOString(),checks},null,2));
