import assert from 'node:assert/strict';
import {withOnboarding} from './lib/onboarding.mjs';
import {readFile,writeFile,mkdir} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {chromium,firefox} from 'playwright';
const origin=process.env.WEBSITE_ORIGIN??'http://localhost:8083',root=process.env.WEBSITE_RESULTS_DIR??'.build/website-roles-assets',fixture=process.env.DEBUG_FIXTURE_DIR??root+'/fixture-data';
if(!['http://localhost:8083','http://localhost:8084'].includes(origin))throw Error('Use a disposable website fixture.');
const credentials=new Map((await readFile(fixture+'/bootstrap-credentials.txt','utf8')).trim().split(/\r?\n/).map(l=>l.split('\t'))),checks=[];
await mkdir(root+'/screenshots',{recursive:true});
for(let attempt=0;;attempt++){try{if((await fetch(origin+'/health/ready')).ok)break;}catch{}if(attempt===30)throw Error('Website fixture did not become ready.');await new Promise(r=>setTimeout(r,1000));}
async function api(page,path,method='GET',data){const token=method==='GET'?null:(await(await page.request.get(origin+'/api/auth/csrf')).json()).token;return page.request.fetch(origin+'/api'+path,{method,data,headers:{Origin:origin,...(token?{'X-CSRF-TOKEN':token}:{})}});}
async function login(page,name,initial){let used;for(const password of (name==='admin'?['website-fixture-password-'+name,'account-fixture-password-'+name,initial]:[initial]).filter(Boolean)){let r=await api(page,'/auth/login','POST',{username:name,password});if(r.status()===409)r=await api(page,'/auth/login','POST',{username:name,password});if(r.ok()){used=password;break;}}assert.ok(used,'Fixture login failed');if((await(await api(page,'/auth/me')).json()).mustChangePassword){assert.ok((await api(page,'/auth/password','POST',{currentPassword:used,newPassword:'website-fixture-password-'+name})).ok());const relogin=await api(page,'/auth/login','POST',{username:name,password:'website-fixture-password-'+name});assert.ok(relogin.ok(),'Fixture relogin HTTP '+relogin.status());}await page.goto(origin);await page.waitForFunction(()=>document.body.dataset.authState==='authenticated');}
const selected=(process.env.MENU_BROWSERS??'Chrome,Edge,Firefox').split(',');
for(const [name,type,options] of [['Chrome',chromium,{channel:'chrome'}],['Edge',chromium,{channel:'msedge'}],['Firefox',firefox,{}]].filter(([name])=>selected.includes(name))){
 const browser=withOnboarding(await type.launch({headless:true,...options}),origin),context=await browser.newContext({viewport:{width:1440,height:1000}}),page=await context.newPage(),errors=[];
 page.setDefaultTimeout(30000);page.on('pageerror',e=>errors.push(e.message));
 let lastPage=page;const pass=t=>{checks.push(name+': '+t);console.log('PASS '+name+': '+t);};
 await context.addInitScript(()=>{window.authFlashFrames=0;const sample=()=>{const login=document.getElementById('login-panel');if(document.body?.dataset.authState==='authenticated'&&login&&!login.hidden&&getComputedStyle(login).display!=='none')window.authFlashFrames++;requestAnimationFrame(sample);};requestAnimationFrame(sample);});
 try{
  await page.goto(origin);
  for(const design of ['scifi','minimal','classic','retro']){await page.evaluate(design=>{localStorage.setItem('ftl-design',design);localStorage.setItem('ftl-ui-theme',design);localStorage.setItem('ftl-theme','light');},design);await page.reload();assert.equal(await page.locator('html').getAttribute('data-theme'),'ftl');assert.equal(await page.locator('.appearance').count(),0);}
  for(const colorScheme of ['light','dark']){await page.emulateMedia({colorScheme});assert.equal(await page.locator('html').getAttribute('data-theme'),'ftl');}
  for(const width of [320,390,768,1280,1920]){await page.setViewportSize({width,height:1000});await page.evaluate(()=>document.fonts.ready);assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));await page.screenshot({path:root+'/screenshots/'+name+'-signin-'+width+'.png',fullPage:true});}
  await page.setViewportSize({width:1440,height:1000});
  const storageContext=await browser.newContext(),storagePage=await storageContext.newPage();
  await storageContext.addInitScript(()=>Object.defineProperty(window,'localStorage',{get(){throw new DOMException('Deliberately unavailable fixture storage','SecurityError');}}));
  await storagePage.goto(origin);await storagePage.waitForFunction(()=>document.body.dataset.sessionReady==='true');assert.equal(await storagePage.locator('html').getAttribute('data-theme'),'ftl');assert.ok(await storagePage.locator('#login-form').isVisible());await storageContext.close();
  pass('Legacy preferences, OS color scheme and unavailable storage retain the fixed FTL design');

  // First-visit screens, local-only mode and the move dialog, at every width.
  {
   const widths=[320,390,768,1280,1920],fresh=await browser.newContextWithoutOnboarding({viewport:{width:1440,height:1000}}),shot=await fresh.newPage();shot.on('pageerror',e=>errors.push(e.message));shot.setDefaultTimeout(30000);
   const capture=async label=>{for(const width of widths){await shot.setViewportSize({width,height:1000});await shot.evaluate(()=>document.fonts.ready);assert.ok(await shot.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'Horizontal overflow '+width+' '+label);await shot.screenshot({path:root+'/screenshots/'+name+'-'+label+'-'+width+'.png',fullPage:true});}await shot.setViewportSize({width:1440,height:1000});};
   const ready=()=>shot.waitForSelector('body[data-session-ready="true"]');
   await shot.goto(origin);await ready();await shot.locator('#consent-panel').waitFor();await capture('consent');
   await shot.locator('#consent-agree').click();await shot.locator('#mode-panel').waitFor();await capture('mode');
   await shot.locator('#mode-cloud').click();await shot.locator('#register-panel').waitFor();await capture('register');
   await shot.locator('#register-signin').click();await shot.locator('#login-panel').waitFor();await capture('signin-with-local');
   await shot.locator('#play-local').click();await shot.waitForSelector('body[data-auth-state="local"][data-session-ready="true"]');await capture('local-play');
   for(const view of ['saves','logs','about']){await shot.goto(origin+'/?view='+view);await ready();await capture('local-'+view);}
   // A local save, then Enable cloud saves: register, and the move dialog appears.
   await shot.goto(origin);await ready();
   await shot.evaluate(async()=>{const {saves,gameConfig}=await import('/web/local-api.mjs'),c=await gameConfig();await saves.save({schema:1,engineBuild:c.engineBuild,assetVersion:c.assetVersion,run:'<xftlSaveGame/>',profile:'<xftl-profile/>'},0);});
   await shot.locator('#enable-cloud').click();await shot.locator('#register-panel').waitFor();await capture('register-from-local');
   const mover='site-mover-'+name.toLowerCase()+'-'+Date.now().toString(36);
   await shot.locator('#register-form [name=username]').fill(mover);await shot.locator('#register-form [name=password]').fill('website-mover-password-2026');await shot.locator('#register-form [name=confirm]').fill('website-mover-password-2026');await shot.locator('#register-form button[type=submit]').click();
   await shot.locator('#migrate-dialog[open]').waitFor();await capture('migrate-dialog');
   await shot.locator('#migrate-confirm').click();await shot.waitForFunction(()=>!document.getElementById('migrate-dialog').open);
   await shot.goto(origin+'/?view=saves');await ready();await capture('cloud-saves');
   await shot.goto(origin+'/?view=account');await ready();await shot.locator('#account-panel').waitFor();await capture('account-external');
   await fresh.close();
   pass('First-visit, choice, register, local launcher, local saves and logs, move dialog and the external-user account panel fit at 320, 390, 768, 1280 and 1920 px');
   // Local-only mode with a real game file: launch, save durably, leave, come back.
   const local=await browser.newContextWithoutOnboarding({viewport:{width:1440,height:1000}}),lp=await local.newPage(),traffic=[];lp.setDefaultTimeout(60000);lp.on('pageerror',e=>errors.push(e.message));
   lp.on('request',r=>{const u=new URL(r.url());if(u.origin===origin&&u.pathname.startsWith('/api/'))traffic.push(r.method()+' '+u.pathname);});
   await lp.goto(origin);await lp.locator('#consent-agree').click();await lp.locator('#mode-local').click();await lp.waitForSelector('body[data-auth-state="local"][data-session-ready="true"]');
   await lp.locator('#local-assets input').setInputFiles(process.env.FTL_DAT_PATH??'game-files/ftl.dat');await lp.waitForFunction(()=>document.querySelector('[data-local-status]').textContent.startsWith('Ready'),{},{timeout:120000});
   await lp.locator('#launch').click();await lp.waitForFunction(()=>!!window.ftlEngine,{},{timeout:120000});await lp.locator('#game-loading').waitFor({state:'hidden'});
   const durable=await lp.evaluate(()=>window.ftlSaveDurably());assert.equal(durable.saved,true);assert.equal(durable.backend,'local');assert.ok(durable.revision>=1);
   const stored=await lp.evaluate(async()=>(await (await import('/web/local-saves.mjs')).load()).record);assert.equal(stored.revision,durable.revision);assert.ok(stored.profile&&stored.profile.length>50);
   const again=await lp.evaluate(()=>window.ftlSaveDurably());assert.equal(again.revision,durable.revision+1,'every durable save is a new revision');
   assert.match(await lp.locator('#save-status').textContent(),/^Saved/);
   await lp.locator('#save').click();await lp.waitForFunction(()=>document.getElementById('save-status').textContent.startsWith('Saved'));
   await Promise.all([lp.waitForEvent('load'),lp.evaluate(()=>window.ftlExit()).catch(()=>{})]);await lp.waitForSelector('body[data-session-ready="true"]');
   assert.equal((await lp.evaluate(async()=>(await (await import('/web/local-saves.mjs')).load()).record)).revision>=again.revision,true,'leaving the game keeps the save');
   await lp.waitForFunction(()=>document.querySelector('[data-local-status]').textContent.startsWith('Ready'));
   await lp.locator('#launch').click();await lp.waitForFunction(()=>!!window.ftlEngine,{},{timeout:120000});await lp.locator('#game-loading').waitFor({state:'hidden'});
   assert.ok((await lp.evaluate(()=>window.ftlEngine.diagnosticProfile())).length>50,'the second launch starts from the stored profile');
   assert.ok(traffic.filter(c=>!c.startsWith('GET ')).every(c=>c.startsWith('POST /api/onboarding/')),'local play writes nothing to the server: '+traffic.join(', '));
   assert.ok(!traffic.some(c=>/\/api\/(assets|saves|debug|game\/|play-lease)/.test(c)),'local play never touches private or account APIs: '+traffic.join(', '));
   await lp.locator('#saves-button').click();await lp.waitForFunction(()=>/Persistent storage/.test(document.getElementById('storage-status').textContent));
   await local.close();
   pass('Local-only mode launches with the visitor’s own file, saves durably in this browser, restores it after leaving, and uses no private API');
  }
  await login(page,'admin',credentials.get('admin'));
  await page.route('**/web/app.mjs*',async route=>{await new Promise(r=>setTimeout(r,250));await route.continue();});await page.route('**/api/auth/me',async route=>{await new Promise(r=>setTimeout(r,450));await route.continue();});
  for(const view of ['account','saves','logs','users','play']){await page.goto(origin+'/?view='+view);await page.waitForFunction(panel=>!document.getElementById(panel).hidden,view==='users'?'admin-panel':view==='play'?'launch-panel':view+'-panel');await page.waitForTimeout(700);assert.equal(await page.evaluate(()=>window.authFlashFrames),0);}
  await page.goto(origin+'/Diagnostics');await page.waitForFunction(()=>!document.getElementById('run-tests').disabled);assert.equal(await page.locator('#suite-server').count(),1);await page.getByRole('link',{name:'Account',exact:true}).click();await page.locator('#account-panel').waitFor();await page.waitForTimeout(700);assert.equal(await page.evaluate(()=>window.authFlashFrames),0);pass('Slow navigation renders the authenticated destination with zero login frames');
  await page.unrouteAll({behavior:'wait'});
  await page.getByRole('link',{name:'Users',exact:true}).click();await page.locator('.user-row').first().waitFor();
  const roleNames=[['website-normal','user'],['website-external','external-user']];let externalPassword;
  for(const [username,role] of roleNames){const users=await(await api(page,'/admin/users')).json(),existing=users.find(u=>u.userName===username);let r=existing?await api(page,'/admin/users/'+existing.id+'/reset-password','POST',{version:existing.version}):await api(page,'/admin/users','POST',{username,role});assert.ok(r.ok());if(username==='website-external')externalPassword=(await r.json()).temporaryPassword;
   if(existing&&existing.role!==role){const current=(await(await api(page,'/admin/users')).json()).find(u=>u.id===existing.id);assert.ok((await api(page,'/admin/users/'+existing.id+'/role','PATCH',{role,version:current.version})).ok());}
  }
  await page.locator('#users-refresh').click();await page.locator('#users-search').fill('website-external');const row=page.locator('.user-row').filter({hasText:'website-external'});await row.getByRole('button',{name:'Manage',exact:true}).click();await page.locator('#user-dialog').waitFor();assert.equal(await page.locator('#edit-role').inputValue(),'external-user');await page.screenshot({path:root+'/screenshots/'+name+'-account-drawer.png',fullPage:true});await page.locator('#user-dialog-close').click();
  for(const width of [320,390,768,1280,1920]){await page.setViewportSize({width,height:1000});for(const view of ['play','account','saves','logs','users','about','diagnostics','licenses']){await page.goto(origin+(view==='diagnostics'?'/Diagnostics':view==='licenses'?'/Licenses':'/?view='+view));if(view==='users')await page.locator('.user-row').first().waitFor();await page.evaluate(()=>document.fonts.ready);assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'Website horizontal overflow '+width+' '+view);await page.screenshot({path:root+'/screenshots/'+name+'-'+view+'-'+width+'.png',fullPage:true});}}
  pass('Fixed FTL design covers every screen, account drawer and five responsive widths');
  const externalContext=await browser.newContext({viewport:{width:1440,height:1000}}),external=await externalContext.newPage();lastPage=external;external.on('pageerror',e=>errors.push(e.message));await login(external,'website-external',externalPassword);
  const traffic=[];external.on('request',r=>{if(r.url().startsWith(origin))traffic.push({path:new URL(r.url()).pathname,method:r.method(),bytes:r.postDataBuffer()?.length??0,type:r.headers()['content-type']??''});});
  await external.goto(origin+'/Diagnostics');await external.waitForFunction(()=>!document.getElementById('measure-browser').disabled);assert.ok(await external.locator('#run-tests').isDisabled());assert.match(await external.locator('#preview-empty').textContent(),/Upload ftl\.dat/);assert.equal(await external.locator('#suite-server').count(),0);assert.equal(await external.locator('#runner-note').count(),0);assert.ok(!traffic.some(r=>r.path.startsWith('/api/tests')));await external.locator('#measure-browser').click();await external.waitForFunction(()=>document.getElementById('test-status').textContent==='Browser timing measured');pass('External users see file guidance and can measure callbacks without a DAT');
  await external.goto(origin);await external.locator('#local-assets').waitFor();await external.locator('#local-assets input').setInputFiles(process.env.FTL_DAT_PATH??'game-files/ftl.dat');await external.waitForFunction(()=>document.querySelector('[data-local-status]').textContent.startsWith('Ready'),{},{timeout:120000});
  await external.reload();await external.waitForFunction(()=>document.querySelector('[data-local-status]').textContent.startsWith('Ready'));assert.equal(await external.locator('#launch').isEnabled(),true);
  if(name==='Chrome'){
   // A matching cached file is final (it is checked against this deployment's hash): no picker, only Remove.
   assert.ok(await external.locator('[data-local-drop]').isHidden());assert.ok(await external.locator('[data-local-remove]').isVisible());
   await external.locator('[data-local-remove]').click();await external.locator('[data-local-drop]').waitFor();assert.equal(await external.evaluate(async()=>{const {getLocalAsset}=await import('/web/local-assets.mjs');return (await getLocalAsset(document.body.dataset.account))?.importedUtc??null;}),null);
   await external.locator('#local-assets input').setInputFiles({name:'ftl.dat',mimeType:'application/octet-stream',buffer:Buffer.from('invalid fixture')});await external.waitForFunction(()=>document.querySelector('[data-local-status]').textContent.includes('does not match'));
   assert.equal(await external.evaluate(async()=>{const {getLocalAsset}=await import('/web/local-assets.mjs');return (await getLocalAsset(document.body.dataset.account))?.importedUtc??null;}),null);
   await external.locator('#local-assets input').setInputFiles(process.env.FTL_DAT_PATH??'game-files/ftl.dat');await external.locator('[data-local-cancel]').click();await external.waitForFunction(()=>document.querySelector('[data-local-status]').textContent.includes('cancelled'));
   assert.equal(await external.evaluate(async()=>{const {getLocalAsset}=await import('/web/local-assets.mjs');return (await getLocalAsset(document.body.dataset.account))?.importedUtc??null;}),null);
   await external.evaluate(()=>{window.originalTransaction=IDBDatabase.prototype.transaction;IDBDatabase.prototype.transaction=function(names,mode,...rest){if(this.name==='ftl-local-assets'&&mode==='readwrite')throw new DOMException('Fixture quota rejection','QuotaExceededError');return window.originalTransaction.call(this,names,mode,...rest);};});
   await external.locator('#local-assets input').setInputFiles(process.env.FTL_DAT_PATH??'game-files/ftl.dat');await external.locator('[data-local-session]').waitFor();await external.locator('[data-local-session]').click();await external.waitForFunction(()=>document.querySelector('[data-local-status]').textContent.includes('this session only'));
   assert.ok(await external.evaluate(async()=>{const {createAssetProvider}=await import('/web/asset-provider.mjs');const p=await createAssetProvider({accountId:document.body.dataset.account,api:async path=>(await fetch('/api'+path)).json(),fetchBytes:async url=>(await fetch(url)).arrayBuffer()});const boot=await p.fetchBoot();p.dispose();return p.source==='local'&&boot.byteLength>0;}));
   await external.evaluate(()=>IDBDatabase.prototype.transaction=window.originalTransaction);await external.reload();await external.locator('[data-local-drop]').waitFor();
   await external.locator('#local-assets input').setInputFiles(process.env.FTL_DAT_PATH??'game-files/ftl.dat');await external.waitForFunction(()=>document.querySelector('[data-local-status]').textContent.startsWith('Ready'),{},{timeout:120000});
   await external.reload();await external.waitForFunction(()=>document.querySelector('[data-local-status]').textContent.startsWith('Ready'));pass('A cached matching ftl.dat hides the picker and offers Remove; invalid and cancelled files cache nothing; quota rejection has a working explicit session fallback');
  }

  if(name==='Chrome'){
   const actual=await external.evaluate(async()=>{const {createAssetProvider}=await import('/web/asset-provider.mjs'),api=async path=>{const r=await fetch('/api'+path,{cache:'no-store'});if(!r.ok)throw Error('Fixture provider HTTP '+r.status);return r.json();},provider=await createAssetProvider({api,accountId:document.body.dataset.account,fetchBytes:async url=>(await fetch(url)).arrayBuffer()});const packed=await provider.fetchBoot();const hash=[...new Uint8Array(await crypto.subtle.digest('SHA-256',packed))].map(b=>b.toString(16).padStart(2,'0')).join('');provider.dispose();return hash;});
   const expected=createHash('sha256').update(await readFile(root+'/assets/boot.pack')).digest('hex');assert.equal(actual,expected);pass('Browser preparation matches the Java boot bundle byte for byte');
  }
  await external.locator('#logs-button').click();await external.locator('#debug-enabled').check();await external.waitForFunction(()=>document.getElementById('debug-status').textContent.includes('Recording'));await external.locator('#play-button').click();await external.locator('#launch').click();await external.waitForFunction(()=>!!window.ftlEngine,{},{timeout:120000});await external.locator('#game-loading').waitFor({state:'hidden'});await external.evaluate(()=>window.websiteEngine=window.ftlEngine);
  await external.locator('#account-button').click();await external.locator('#play-button').click();assert.equal(await external.locator('.ftl-banner').isVisible(),false);assert.equal(await external.evaluate(()=>window.websiteEngine===window.ftlEngine),true);await external.locator('#save').click();await external.waitForFunction(()=>document.getElementById('save-status').textContent.startsWith('Saved'));await external.screenshot({path:root+'/screenshots/'+name+'-external-play.png',fullPage:true});
  assert.ok(!traffic.some(r=>r.path.startsWith('/api/assets/')||r.path.startsWith('/api/tests')));assert.ok(traffic.every(r=>r.bytes<2*1024*1024&&!r.type.includes('multipart')));pass('Local DAT persists, launches and saves with diagnostics; no private asset fetches or file uploads');
  await external.locator('#tests-button').click();await external.waitForURL('**/Diagnostics');await external.locator('#individual-tests').evaluate(e=>e.parentElement.open=true);await external.locator('#test-mechanics').click();await external.waitForFunction(()=>['Completed','Completed with failures'].includes(document.getElementById('test-status').textContent),{},{timeout:120000});assert.equal(await external.locator('#test-status').textContent(),'Completed');pass('External users run engine checks through their own cached file');if(name==='Chrome'){await external.locator('#test-audio').click();await external.waitForFunction(()=>document.getElementById('test-status').textContent==='Completed');await external.locator('.preset[data-preset="benchmark"]').click();await external.locator('#benchmark-duration').selectOption('5000');await external.locator('#benchmark-limit').selectOption('60');await external.locator('#run-tests').click();await external.waitForFunction(()=>['Completed','Completed with failures'].includes(document.getElementById('test-status').textContent),{},{timeout:120000});assert.equal(await external.locator('#test-status').textContent(),'Completed');pass('External audio checks and three-phase benchmark use local resources');}
  await externalContext.close();assert.deepEqual(errors,[]);
 }catch(e){console.error('Website fixture failure:',e.message,'Local storage status:',await lastPage.locator('[data-local-status]').textContent().catch(()=>''));await lastPage.screenshot({path:root+'/screenshots/'+name+'-failure.png',fullPage:true}).catch(()=>{});throw e;}
 finally{await browser.close();}
}
await writeFile(root+'/website-results.json',JSON.stringify({checks},null,2));
