import assert from 'node:assert/strict';
import {withOnboarding} from './lib/onboarding.mjs';
import {mkdir,readFile,writeFile} from 'node:fs/promises';
import {chromium,firefox} from 'playwright';

const origin=process.env.LAUNCHER_ORIGIN??'http://localhost:8084',fixture=process.env.DEBUG_FIXTURE_DIR;
assert.equal(origin,'http://localhost:8084');assert.ok(fixture?.includes('fixture-diagnostics-assets-ui'),'Use a dedicated disposable fixture.');
const credentials=new Map((await readFile(fixture+'/bootstrap-credentials.txt','utf8')).trim().split(/\r?\n/).map(line=>line.split('\t'))),checks=[];
await mkdir('.build/diagnostics-assets-ui',{recursive:true});
async function api(page,path,method='GET',data){const token=method==='GET'?null:(await(await page.request.get(origin+'/api/auth/csrf')).json()).token;const r=await page.request.fetch(origin+'/api'+path,{method,data,headers:{Origin:origin,...(token?{'X-CSRF-TOKEN':token}:{})}});assert.ok(r.ok(),path+' HTTP '+r.status());return r.status()===204?null:r.json();}
async function login(page,username,initial){
 const password='diagnostics-assets-fixture-'+username;
 const post=async value=>{const token=(await(await page.request.get(origin+'/api/auth/csrf')).json()).token;return page.request.post(origin+'/api/auth/login',{data:{username,password:value},headers:{Origin:origin,'X-CSRF-TOKEN':token}});};
 let r=await post(password);if(r.status()===401)r=await post(initial);assert.ok(r.ok(),'Fixture login HTTP '+r.status());
 if((await api(page,'/auth/me')).mustChangePassword){await api(page,'/auth/password','POST',{currentPassword:initial,newPassword:password});assert.ok((await post(password)).ok());}
}
async function cacheFixture(page,matching=true){
 // Lightweight metadata fixture checks readiness and eviction, not DAT validation.
 await page.evaluate(async matching=>{const config=await(await fetch('/api/game/config')).json(),{persistLocalAsset}=await import('/web/local-assets.mjs');await persistLocalAsset(document.body.dataset.account,{file:new Blob(['readiness fixture']),index:{assetVersion:matching?config.assetVersion:'stale-fixture-version',parserVersion:1,size:17,audio:[]}});},matching);
}
const selected=(process.env.MENU_BROWSERS??'Chrome,Edge,Firefox').split(',');
for(const [name,type,options] of [['Chrome',chromium,{channel:'chrome'}],['Edge',chromium,{channel:'msedge'}],['Firefox',firefox,{}]].filter(([name])=>selected.includes(name))){
 const browser=withOnboarding(await type.launch({headless:true,...options}),origin),adminPage=await browser.newPage(),page=await browser.newPage({viewport:{width:1366,height:900}}),errors=[],traffic=[];
 page.on('pageerror',e=>errors.push(e.message));page.on('request',r=>{if(r.url().startsWith(origin))traffic.push({path:new URL(r.url()).pathname,method:r.method()});});
 const pass=text=>{checks.push(name+': '+text);console.log('PASS '+name+': '+text);};
 try{
  await login(adminPage,'admin',credentials.get('admin'));
  const username='diagnostics-assets-'+name.toLowerCase(),existing=(await api(adminPage,'/admin/users')).find(user=>user.userName===username),created=existing?await api(adminPage,'/admin/users/'+existing.id+'/reset-password','POST',{version:existing.version}):await api(adminPage,'/admin/users','POST',{username,role:'external-user'});
  await login(page,username,created.temporaryPassword);
  await page.goto(origin+'/Diagnostics');await page.waitForFunction(()=>!document.getElementById('measure-browser').disabled);
  await page.waitForFunction(()=>document.getElementById('preview-status').textContent==='ftl.dat required');
  assert.match(await page.locator('#preview-empty').textContent(),/Upload ftl\.dat.*engine tests and benchmarks/);
  assert.ok(await page.locator('#run-tests').isDisabled());assert.ok(await page.locator('#test-mechanics').isDisabled());assert.ok(await page.locator('.diagnostic-local').evaluate(element=>element.open));assert.ok(await page.locator('#local-assets input').isVisible());
  await page.locator('[data-preset="benchmark"]').click();assert.ok(await page.locator('#run-tests').isDisabled());
  await page.evaluate(()=>document.getElementById('test-mechanics').onclick());assert.match(await page.locator('#preview-empty').textContent(),/Upload ftl\.dat/);assert.ok(!traffic.some(r=>r.path==='/engine/engine.js'));
  await page.screenshot({path:'.build/diagnostics-assets-ui/'+name.toLowerCase()+'-missing.png',fullPage:true});pass('Missing DAT explains the requirement, exposes file selection and prevents engine/benchmark startup');

  await page.locator('[data-preset="quick"]').click();await page.locator('#custom-selection').evaluate(element=>element.open=true);
  for(const input of await page.locator('#suite-options input').all())await input.uncheck();await page.locator('#suite-units').check();assert.ok(await page.locator('#run-tests').isEnabled());
  await page.locator('#run-tests').click();await page.waitForFunction(()=>document.getElementById('test-status').textContent==='Completed');assert.equal(await page.locator('#test-results tr').count(),3);
  await page.locator('#measure-browser').click();await page.waitForFunction(()=>document.getElementById('test-status').textContent==='Browser timing measured');pass('Browser modules and callback measurement work without game files');

  await page.locator('#local-assets input').setInputFiles({name:'ftl.dat',mimeType:'application/octet-stream',buffer:Buffer.from('invalid fixture')});await page.waitForFunction(()=>document.querySelector('[data-local-status]').textContent.includes('does not match'));await page.locator('[data-preset="quick"]').click();assert.ok(await page.locator('#run-tests').isDisabled());pass('Invalid file selection cannot enable engine tests');

  await cacheFixture(page,false);await page.reload();await page.waitForFunction(()=>document.getElementById('preview-status').textContent==='ftl.dat required');assert.ok(await page.locator('#run-tests').isDisabled());pass('Stale cached metadata still requires a matching file');
  await cacheFixture(page);await page.reload();await page.waitForFunction(()=>!document.getElementById('run-tests').disabled);assert.ok(await page.locator('#test-mechanics').isEnabled());assert.doesNotMatch(await page.locator('#preview-empty').textContent(),/Upload/);pass('Matching cached metadata restores engine controls after reload');

  await page.evaluate(async()=>{const {removeLocalAsset}=await import('/web/local-assets.mjs');await removeLocalAsset(document.body.dataset.account);});await page.locator('#run-tests').click();await page.waitForFunction(()=>document.getElementById('test-status').textContent==='Completed with failures');assert.match(await page.locator('#preview-empty').textContent(),/Upload ftl\.dat/);assert.ok(await page.locator('#run-tests').isDisabled());assert.equal(await page.locator('#preview-status').textContent(),'ftl.dat required');pass('Eviction between readiness and Run replaces Loading with file guidance and resets controls');

  await cacheFixture(page);await page.reload();await page.waitForFunction(()=>!document.getElementById('run-tests').disabled);await page.locator('.diagnostic-local').evaluate(element=>element.open=true);await page.locator('[data-local-remove]').click();await page.waitForFunction(()=>document.getElementById('preview-status').textContent==='ftl.dat required');assert.ok(await page.locator('#run-tests').isDisabled());pass('Removing the cached file immediately restores the upload prompt');

  if(process.env.FTL_DAT_PATH){await page.locator('#local-assets input').setInputFiles(process.env.FTL_DAT_PATH);await page.waitForFunction(()=>!document.getElementById('run-tests').disabled,{},{timeout:120000});assert.match(await page.locator('[data-local-status]').textContent(),/^Ready/);await page.reload();await page.waitForFunction(()=>!document.getElementById('run-tests').disabled);await page.locator('.diagnostic-local').evaluate(element=>element.open=true);await page.locator('[data-local-remove]').click();await page.waitForFunction(()=>document.getElementById('run-tests').disabled);pass('Actual matching DAT import, reload and removal update engine readiness');}
  assert.ok(!traffic.some(r=>r.path.startsWith('/api/assets/')||r.path.startsWith('/api/tests')));assert.ok(!traffic.some(r=>r.method==='POST'&&!r.path.startsWith('/api/auth/')));assert.deepEqual(errors,[]);pass('External checks issue no private asset, server-job or file-upload requests');
 }catch(e){await page.screenshot({path:'.build/diagnostics-assets-ui/'+name.toLowerCase()+'-failure.png',fullPage:true}).catch(()=>{});throw e;}
 finally{await browser.close();}
}
await writeFile('.build/diagnostics-assets-ui/results.json',JSON.stringify({checks},null,2));
