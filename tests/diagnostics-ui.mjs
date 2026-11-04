import assert from 'node:assert/strict';
const skipPerformance=process.env.FTL_SKIP_PERFORMANCE==='1';
import {withOnboarding} from './lib/onboarding.mjs';
import {readFile,writeFile} from 'node:fs/promises';
import {chromium,firefox} from 'playwright';
const origin=process.env.LAUNCHER_ORIGIN??'http://localhost:8084',checks=[];
const credentials=new Map((await readFile((process.env.DEBUG_FIXTURE_DIR??'.build/debug-data')+'/bootstrap-credentials.txt','utf8')).trim().split(/\r?\n/).map(l=>l.split('\t')));
async function login(page){
 await page.goto(origin);await page.waitForFunction(()=>document.body.dataset.sessionReady==='true');
 await page.evaluate(async initial=>{
  const token=async()=>(await(await fetch('/api/auth/csrf')).json()).token;
  let csrf=await token();const post=(path,body)=>fetch('/api/auth/'+path,{method:'POST',headers:{'Content-Type':'application/json','X-CSRF-TOKEN':csrf},body:JSON.stringify(body)}),password='diagnostics-layout-fixture-password';
  let response=await post('login',{username:'admin',password});if(response.status===401)response=await post('login',{username:'admin',password:initial});if(!response.ok)throw Error('Fixture login '+response.status);
  csrf=await token();if((await(await fetch('/api/auth/me')).json()).mustChangePassword){if(!(await post('password',{currentPassword:initial,newPassword:password})).ok)throw Error('Fixture password rotation');csrf=await token();if(!(await post('login',{username:'admin',password})).ok)throw Error('Fixture relogin');}
 },credentials.get('admin'));
 await page.goto(origin+'/Diagnostics');await page.waitForFunction(()=>!document.getElementById('run-tests').disabled);
}
async function exported(page){const pending=page.waitForEvent('download');await page.locator('#export-results').click();return JSON.parse(await readFile(await(await pending).path(),'utf8'));}
const bounds=page=>page.evaluate(()=>{const r=document.getElementById('preview').getBoundingClientRect();return {top:r.top,height:r.height,pageHeight:document.documentElement.scrollHeight,scrollY};});
const selected=(process.env.MENU_BROWSERS??'Chromium,Firefox').split(',');
for(const [name,type,options] of [['Chromium',chromium,{channel:'chromium'}],['Firefox',firefox,{}]].filter(([name])=>selected.includes(name))){
 const browser=withOnboarding(await type.launch({headless:!(name==='Firefox'&&process.env.RUNNER_FIREFOX_WINDOWED==='1'),...options}),origin),page=await browser.newPage({viewport:{width:1440,height:1000}}),errors=[];
 page.setDefaultTimeout(20000);page.on('pageerror',error=>errors.push(error.message));
 // A player's browser: no navigator.webdriver, so the engine's first start would show the language list (2.0.1 failed
 // Menu & jump warnings and Tutorial isolation there; the Tests page now marks its runs, window.ftlTestRuns).
 await page.addInitScript(()=>Object.defineProperty(Navigator.prototype,'webdriver',{get:()=>false,configurable:true}));
 const pass=label=>{checks.push(name+': '+label);console.log('PASS '+name+': '+label);};
 try{
  await login(page);const before=await page.evaluate(async()=>(await(await fetch('/api/saves')).json()).saves),initial=await bounds(page);
  assert.ok(initial.pageHeight<=1000,'Desktop workspace should fit the viewport');
  await page.locator('#individual-tests').evaluate(e=>e.parentElement.open=true);await page.locator('#test-encounters').click();
  await page.waitForFunction(()=>document.getElementById('test-results').rows.length>20,{},{timeout:90000});
  assert.deepEqual(await bounds(page),initial,'Growing live results must not move the preview or page');
  await page.waitForFunction(()=>document.getElementById('test-status').textContent==='Completed',{},{timeout:180000});
  const report=await exported(page);assert.equal(report.status,'passed');assert.equal(report.results.length,137);
  assert.equal(await page.locator('#test-results tr:visible').count(),0,'Passed cases are summarized by default');
  assert.match(await page.locator('#case-empty').innerText(),/No cases need attention/);
  await page.locator('#result-filter').selectOption('all');assert.equal(await page.locator('#test-results tr:visible').count(),137);
  await page.locator('.diagnostic-case-scroll').first().evaluate(e=>e.scrollTop=e.scrollHeight);
  assert.deepEqual(await bounds(page),initial,'Scrolling all cases stays inside the results pane');
  await page.locator('#test-results button').last().click();assert.ok(await page.locator('#case-detail').evaluate(e=>e.open));assert.match(await page.locator('#case-detail-output').innerText(),/^PASS/);
  assert.deepEqual(await bounds(page),initial,'Case details stay inside the workspace');
  await page.locator('#result-filter').selectOption('failed');assert.equal(await page.locator('#test-results tr:visible').count(),0);
  await page.locator('#tab-cases').focus();await page.keyboard.press('End');assert.equal(await page.locator('#tab-activity').getAttribute('aria-selected'),'true');assert.ok(await page.locator('#results').isVisible());
  await page.locator('#follow-output').uncheck();await page.locator('#results').evaluate(e=>e.scrollTop=0);assert.equal(await page.locator('#results').evaluate(e=>e.scrollTop),0);
  await page.keyboard.press('Tab');await page.locator('#tab-activity').focus();await page.keyboard.press('Home');assert.equal(await page.locator('#tab-cases').getAttribute('aria-selected'),'true');
  pass('137 live encounters, bounded results/output, passed summary, filters, case details and keyboard tabs preserve preview geometry');
  assert.equal(await page.evaluate(()=>navigator.webdriver),false);
  // Two categories: the loading screen shows for a second between them; then both start at the main menu.
  await page.locator('#custom-selection').evaluate(e=>e.open=true);for(const checkbox of await page.locator('#suite-options input:enabled').all())await checkbox.uncheck();
  await page.locator('#suite-warnings').check();await page.locator('#suite-tutorial-isolation').check();const started=Date.now();await page.locator('#run-tests').click();
  await page.waitForFunction(()=>/Loading Tutorial isolation/.test(document.getElementById('preview-empty').textContent)&&!document.getElementById('preview-empty').hidden,{},{timeout:120000});
  await page.waitForFunction(()=>/^Completed/.test(document.getElementById('test-status').textContent),{},{timeout:180000});
  const two=await exported(page);assert.equal(two.status,'passed',JSON.stringify(two.results.map(r=>[r.name,r.status,r.error])));assert.deepEqual(two.results.map(r=>r.name),['Menu & jump warnings','Tutorial isolation']);
  const gap=new Date(two.results[1].started)-new Date(two.results[0].started)-two.results[0].durationMs;assert.ok(gap>=1000,'the pause between categories is '+gap+' ms');
  pass('Without navigator.webdriver both categories start at the main menu and pass, with a 1 s loading screen between them ('+gap+' ms, '+(Date.now()-started)+' ms in all)');
  // Hyperspace/Multiverse need their files in this browser's mod library: without them they are disabled and say so.
  for(const id of ['hs-core','mv-core','mv-rules']){assert.ok(await page.locator('#suite-'+id).isDisabled(),id+' enabled without mods');assert.match(await page.locator('#suite-'+id).locator('xpath=..').innerText(),/in Mods first/);}
  await page.locator('.preset[data-preset="all"]').click();assert.equal(await page.locator('#suite-mv-core').isChecked(),false,'All tests without Multiverse');
  // The results pane and the case details can be resized by their corner; the case list keeps several rows.
  assert.deepEqual(await page.evaluate(()=>['.diagnostic-results','#case-detail-output'].map(q=>getComputedStyle(document.querySelector(q)).resize)),['vertical','vertical']);
  pass('Mod suites stay out of All tests without the mod files; results and case details resize vertically');
  if(skipPerformance)console.log('SKIP '+name+': browser timing and flagship benchmarks (FTL_SKIP_PERFORMANCE=1)');
  else {
  await page.locator('#measure-browser').click();await page.waitForFunction(()=>document.getElementById('test-status').textContent==='Browser timing measured');
  const timing=await exported(page),sample=timing.browserTiming.at(-1);assert.ok(sample.callbacks>10);assert.ok(sample.fps>0&&sample.intervalP95>0);assert.equal(timing.results.length,2);assert.equal(sample.screen.width>0,true);
  await page.locator('#measure-browser').click();await page.locator('#stop-tests').click();await page.waitForFunction(()=>document.getElementById('test-status').textContent==='Browser timing stopped');assert.ok(await page.locator('#run-tests').isEnabled());
  await page.locator('#measure-browser').click();await page.evaluate(()=>{Object.defineProperty(document,'hidden',{value:true,configurable:true});document.dispatchEvent(new Event('visibilitychange'));delete document.hidden;});
  await page.waitForFunction(()=>document.getElementById('test-status').textContent==='Browser timing stopped');assert.match(await page.locator('#browser-rate').innerText(),/visible/);
  pass('Browser-only measurement exports callback/screen data, Stop and hidden-tab cancellation restore controls');
  if(name==='Firefox'){
   await page.locator('.preset[data-preset="benchmark"]').click();await page.locator('#benchmark-duration').selectOption('5000');await page.locator('#benchmark-limit').selectOption('0');await page.locator('#run-tests').click();
   await page.waitForFunction(()=>document.getElementById('test-status').textContent==='Completed',{},{timeout:90000});const unlimited=await exported(page);assert.equal(unlimited.status,'passed');assert.equal(unlimited.benchmark.length,3);
   for(const phase of unlimited.benchmark){assert.equal(phase.fpsLimit,0);assert.equal(phase.frames,phase.browserCallbacks);assert.ok(Math.abs(phase.fps-phase.browserFps)<.1);}
   await writeFile('.build/diagnostics-unlimited-firefox.json',JSON.stringify(unlimited,null,2));pass('Unlimited flagship benchmark draws on every supplied Firefox callback in all three phases');
  }
  }
  // The case list is measured on its own tab (the timing and benchmark runs leave the benchmark tab open).
  await page.locator('#tab-cases').click();
  await page.locator('.preset[data-preset="quick"]').click();await page.locator('#custom-selection').evaluate(e=>e.open=false);await page.screenshot({path:'.build/diagnostics-workspace-'+name.toLowerCase()+'.png'});
  for(const view of [{width:1000,height:700},{width:900,height:600},{width:390,height:844},{width:320,height:640}]){await page.setViewportSize(view);assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'Horizontal page overflow at '+view.width);
   assert.ok(await page.evaluate(()=>{document.getElementById('case-detail').open=true;const h=document.querySelector('.diagnostic-case-scroll').getBoundingClientRect().height;document.getElementById('case-detail').open=false;return h>=8*parseFloat(getComputedStyle(document.documentElement).fontSize)-1;}),'The case list is squashed at '+view.width+'x'+view.height);await page.locator('#custom-selection').evaluate(e=>e.open=true);assert.ok(await page.locator('#suite-encounters').isVisible());await page.locator('#custom-selection').evaluate(e=>e.open=false);assert.ok(await page.locator('#preview').isVisible());}
  await page.screenshot({path:'.build/diagnostics-workspace-narrow-'+name.toLowerCase()+'.png',fullPage:true});
  pass('Configuration remains usable on desktop, short windows and 320px screens without horizontal page overflow');
  // Fake only the server API, exercising a real failed UI run without starting jobs.
  await page.route('**/api/tests',route=>route.fulfill({json:route.request().method()==='POST'?{id:'ui-failure'}:{available:true,runs:[]}}));
  await page.route('**/api/tests/ui-failure',route=>route.fulfill({json:{id:'ui-failure',status:'failed',suites:['unit'],results:[{status:'failed'}],log:'x'.repeat(300000)+'\nFAIL deliberate UI fixture'}}));
  await page.goto(origin+'/Diagnostics');await page.waitForFunction(()=>!document.getElementById('run-tests').disabled);await page.locator('#custom-selection').evaluate(e=>e.open=true);
  for(const checkbox of await page.locator('#suite-options input').all())await checkbox.uncheck();await page.locator('#suite-server').check();await page.locator('#run-tests').click();await page.waitForFunction(()=>document.getElementById('test-status').textContent==='Completed with failures');
  assert.equal(await page.locator('#test-results tr:visible').count(),1);assert.match(await page.locator('#test-summary').innerText(),/1 failed/);await page.locator('#test-results button').click();assert.match(await page.locator('#case-detail-output').innerText(),/integration suites failed/);
  await page.locator('#tab-activity').click();assert.ok(await page.locator('#results').evaluate(e=>e.textContent.length<=256000));assert.match(await page.locator('#results').innerText(),/Earlier display output truncated/);assert.match(await page.locator('#results').innerText(),/FAIL deliberate/);
  const failed=await exported(page);assert.equal(failed.status,'failed');assert.equal(failed.server.log.length>300000,true,'Full server report remains exportable');
  assert.deepEqual(await page.evaluate(async()=>(await(await fetch('/api/saves')).json()).saves),before);assert.deepEqual(errors,[]);
  pass('Failures stay visible, long activity is bounded, full reports remain exportable and cloud saves are unchanged');
 }catch(error){console.log('Diagnostic UI status:',await page.locator('#test-status').textContent().catch(()=>''));await page.screenshot({path:'.build/diagnostics-ui-failure-'+name.toLowerCase()+'.png',fullPage:true}).catch(()=>{});throw error;}finally{await browser.close();}
}
await writeFile('.build/diagnostics-workspace-results.json',JSON.stringify({at:new Date().toISOString(),checks},null,2));
