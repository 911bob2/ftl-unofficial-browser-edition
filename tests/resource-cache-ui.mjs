import assert from 'node:assert/strict';
import {withOnboarding} from './lib/onboarding.mjs';
import {readFile,writeFile} from 'node:fs/promises';
import {chromium,firefox} from 'playwright';

const origin=process.env.LAUNCHER_ORIGIN??'http://localhost:8084';
if(!['http://localhost:8083','http://localhost:8084'].includes(origin))throw Error('Use a disposable resource-cache fixture');
const credentials=new Map((await readFile((process.env.DEBUG_FIXTURE_DIR??'.build/debug-data')+'/bootstrap-credentials.txt','utf8')).trim().split(/\r?\n/).map(line=>line.split('\t')));
const checks=[],evidence=[];
const selected=(process.env.MENU_BROWSERS??'Chrome,Edge,Firefox').split(',');
const browsers=[['Chrome',chromium,{channel:'chrome'}],['Edge',chromium,{channel:'msedge'}],['Firefox',firefox,{}]].filter(([name])=>selected.includes(name));
if(!browsers.length)throw Error('No resource-cache browsers selected');
async function login(page){
 await page.goto(origin);await page.waitForFunction(()=>document.body.dataset.sessionReady==='true');
 await page.evaluate(async initial=>{
  const token=async()=>(await(await fetch('/api/auth/csrf')).json()).token;
  let csrf=await token();const post=(path,body)=>fetch('/api/auth/'+path,{method:'POST',headers:{'Content-Type':'application/json','X-CSRF-TOKEN':csrf},body:JSON.stringify(body)});
  const username='user09',password='resource-cache-fixture-password';
  let response=await post('login',{username,password});if(response.status===401)response=await post('login',{username,password:initial});if(!response.ok)throw Error('Fixture login '+response.status);
  csrf=await token();if((await(await fetch('/api/auth/me')).json()).mustChangePassword){
   response=await post('password',{currentPassword:initial,newPassword:password});if(!response.ok)throw Error('Fixture password rotation');
   csrf=await token();if(!(await post('login',{username,password})).ok)throw Error('Fixture relogin');
  }
 },credentials.get('user09'));
 await openTests(page);
}
async function openTests(page){
 await page.goto(origin+'/Diagnostics');await page.waitForFunction(()=>!document.getElementById('run-tests').disabled);
 await page.locator('#individual-tests').evaluate(element=>element.parentElement.open=true);
}
async function done(page){await page.waitForFunction(()=>['Completed','Completed with failures','Stopped'].includes(document.getElementById('test-status').textContent),{},{timeout:120000});}
async function run(page,id){
 await page.locator('#test-'+id).click();await done(page);
 assert.equal(await page.locator('#test-status').textContent(),'Completed',await page.locator('#results').textContent());
}
async function benchmark(page){
 await page.locator('.preset[data-preset="benchmark"]').click();await page.locator('#benchmark-duration').selectOption('5000');await page.locator('#benchmark-limit').selectOption('30');
 await page.locator('#run-tests').click();await done(page);
 assert.equal(await page.locator('#test-status').textContent(),'Completed',await page.locator('#results').textContent());
 assert.equal(await page.locator('#benchmark-rows tr').count(),3);
}
for(const [name,type,options] of browsers){
 // Firefox derives its group quota from the job's 512 MiB tmpfs, resulting
 // in a quota below the prepared bundle size. Emulate a 1 GiB quota only
 // in the disposable packaged cache fixture; physical tmpfs limits still apply.
 // https://searchfox.org/firefox-main/source/dom/quota/ActorsParent.cpp
 const fixtureQuota=name==='Firefox'&&process.env.RUNNER_FIREFOX_WINDOWED==='1'?{firefoxUserPrefs:{'dom.quotaManager.temporaryStorage.fixedLimit':1048576}}:{};
 const browser=withOnboarding(await type.launch({headless:!(name==='Firefox'&&process.env.RUNNER_FIREFOX_WINDOWED==='1'),...options,...fixtureQuota}),origin);
 const page=await browser.newPage({viewport:{width:1440,height:1000}}),errors=[],requests=new Map();let manifests=0;
 page.setDefaultTimeout(30000);page.on('pageerror',error=>errors.push(error.message));
 // Routing disables HTTP caching: any reuse here must come from Cache Storage.
 await page.route('**/api/assets/**',async route=>{
  const path=new URL(route.request().url()).pathname;
  if(path==='/api/assets/manifest')manifests++;else requests.set(path,(requests.get(path)??0)+1);
  await route.continue();
 });
 const pass=label=>{checks.push(name+': '+label);console.log('PASS '+name+': '+label);};
 try{
  await login(page);
  const before=await page.evaluate(async()=>(await(await fetch('/api/saves/current')).json()));
  const manifest=await page.evaluate(async()=>(await(await fetch('/api/assets/manifest',{cache:'no-store'})).json()));
  assert.match(manifest.bootHash,/^[a-f0-9]{64}$/);pass('Authorized manifest includes the exact bundle hash');
  await run(page,'mechanics');assert.equal(requests.get('/api/assets/boot'),1);
  await run(page,'audio');await benchmark(page);await run(page,'mechanics');await run(page,'audio');
  assert.equal(requests.get('/api/assets/boot'),1);assert.ok([...requests.keys()].some(path=>path.includes('/audio/')));
  assert.ok([...requests.values()].every(count=>count===1),JSON.stringify([...requests]));
  pass('Suites, repeated runs and the three-phase benchmark share one boot download and lazy audio');
  await page.reload();await page.waitForFunction(()=>!document.getElementById('run-tests').disabled);
  await page.locator('#individual-tests').evaluate(element=>element.parentElement.open=true);
  await run(page,'mechanics');await run(page,'audio');await benchmark(page);
  assert.equal(requests.get('/api/assets/boot'),1);assert.ok([...requests.values()].every(count=>count===1),JSON.stringify([...requests]));
  assert.ok(manifests>=9,'Every run must fetch a fresh manifest');pass('Page reload preserves the cache with fresh authorization on every run');
  assert.deepEqual(await page.evaluate(async()=>(await(await fetch('/api/saves/current')).json())),before);pass('Cached tests and benchmarks preserve cloud progress');
  const downloaded=[...requests];
  await page.evaluate(async()=>{
   const csrf=(await(await fetch('/api/auth/csrf')).json()).token;
   const response=await fetch('/api/auth/logout',{method:'POST',headers:{'X-CSRF-TOKEN':csrf}});if(!response.ok)throw Error('Fixture logout failed');
  });
  await page.locator('#test-mechanics').click();await done(page);
  assert.equal(await page.locator('#test-status').textContent(),'Completed with failures');
  assert.deepEqual([...requests],downloaded);assert.equal((await page.request.get(origin+'/api/assets/boot')).status(),401);
  pass('A warmed cache cannot start a run after real logout');
  // The next visitor of this browser profile must not find the signed-out account's server game files.
  await page.goto(origin+'/?view=about');await page.waitForFunction(async()=>!(await caches.keys()).includes('ftl-test-resources-v1'));
  assert.equal((await page.request.get(origin+'/api/assets/audio/'+manifest.audio[0].hash)).status(),401);
  pass('A signed-out page removes the cached server game files from this browser profile');assert.deepEqual(errors,[]);
  evidence.push({browser:name,bootRequests:requests.get('/api/assets/boot'),audioRequests:[...requests].filter(([path])=>path.includes('/audio/')).length,manifestRequests:manifests});
 }catch(error){console.log(name+' resource-cache output:',await page.locator('#results').textContent().catch(()=>''));throw error;}
 finally{await browser.close();}
}
await writeFile('.build/resource-cache-ui-results.json',JSON.stringify({at:new Date().toISOString(),checks,evidence},null,2));
