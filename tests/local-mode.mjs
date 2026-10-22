// Browser tests for first-visit onboarding, local-only mode (saves in this browser), moving a local save into an account,
// persistent sign-in and the account panel. They do not need a game file: local saves are written through the same
// module the game uses. Launching the engine in local mode is covered in website-ui.mjs, which has an ftl.dat.
import assert from 'node:assert/strict';
import {withOnboarding} from './lib/onboarding.mjs';
import {readFile} from 'node:fs/promises';
import {randomBytes} from 'node:crypto';
import {chromium,firefox} from 'playwright';
const origin=process.env.LAUNCHER_ORIGIN??'http://localhost:8084',port=new URL(origin).port,fixture=process.env.DEBUG_FIXTURE_DIR;
if(origin!=='http://localhost:8084')throw Error('Use the disposable 8084 fixture.');
for(let attempt=0;;attempt++){try{if((await fetch(origin+'/health/ready')).ok)break;}catch{}if(attempt===30)throw Error('Fixture did not become ready.');await new Promise(r=>setTimeout(r,1000));}
const checks=[],check=name=>{checks.push(name);console.log('PASS '+name);};
const selected=(process.env.MENU_BROWSERS??'Chrome,Edge,Firefox').split(',');
const credentials=new Map((await readFile(fixture+'/bootstrap-credentials.txt','utf8')).split(/\r?\n/).filter(l=>l.includes('\t')).map(l=>l.split('\t')));
const suffix=randomBytes(3).toString('hex'),password='local-mode-password-'+suffix,adminPassword='local-mode-admin-'+suffix;
const RUN='<xftlSaveGame><marker id="alpha"/></xftlSaveGame>',RUN2='<xftlSaveGame><marker id="beta"/></xftlSaveGame>',PROFILE='<xftl-profile><unlock id="one"/></xftl-profile>';
const ready=page=>page.waitForSelector('body[data-session-ready="true"]');
const visiblePanels=page=>page.evaluate(()=>[...document.querySelectorAll('main>section')].filter(s=>!s.hidden).map(s=>s.id));
const publicConfig=page=>page.evaluate(()=>fetch('/api/public/game/config').then(r=>r.json()));
// Writes a save the way the game does: through local-saves.save(state, expectedRevision).
const writeLocal=(page,run,profile,expected)=>page.evaluate(async({run,profile,expected})=>{const {saves,gameConfig}=await import('/web/local-api.mjs'),c=await gameConfig();return saves.save({schema:1,engineBuild:c.engineBuild,assetVersion:c.assetVersion,run,profile},expected);},{run,profile,expected});
const readLocal=page=>page.evaluate(async()=>{const {load}=await import('/web/local-saves.mjs');const r=await load();return {source:r.source,revision:r.record?.revision??null,run:r.record?.run??null,profile:r.record?.profile??null};});
const idbRecord=page=>page.evaluate(async()=>{try{if(!(await indexedDB.databases()).some(d=>d.name==='ftl-local-saves'))return null;const db=await new Promise((res,rej)=>{const r=indexedDB.open('ftl-local-saves');r.onsuccess=()=>res(r.result);r.onerror=()=>rej(r.error);});if(!db.objectStoreNames.contains('current')){db.close();return null;}const rec=await new Promise(res=>{const r=db.transaction('current').objectStore('current').get('save');r.onsuccess=()=>res(r.result);});db.close();return rec?{revision:rec.revision,run:rec.run}:null;}catch{return null;}});
async function cloudSave(page){return page.evaluate(async()=>{const r=await fetch('/api/saves/current');return r.json();});}
async function startLocal(browser,{persist,width=1280}={}){
 const context=await browser.newContextWithoutOnboarding({viewport:{width,height:900},acceptDownloads:true}),page=await context.newPage(),errors=[],apiCalls=[];
 page.setDefaultTimeout(30000);page.on('pageerror',e=>errors.push(e.message));const failed=[];page.on('response',r=>{if(r.status()>=400)failed.push(r.status()+' '+r.request().method()+' '+new URL(r.url()).pathname);});
 page.on('request',r=>{const u=new URL(r.url());if(u.origin===origin&&u.pathname.startsWith('/api/'))apiCalls.push(r.method()+' '+u.pathname);});
 if(persist!==undefined)await page.addInitScript(granted=>{Object.defineProperty(navigator.storage,'persist',{value:async()=>granted,configurable:true});Object.defineProperty(navigator.storage,'persisted',{value:async()=>granted,configurable:true});},persist);
 await page.goto(origin);await ready(page);
 await page.locator('#consent-agree').click();await page.locator('#mode-panel').waitFor();await page.locator('#mode-local').click();
 try{await page.waitForSelector('body[data-auth-state="local"][data-session-ready="true"]');}catch(e){throw Error(e.message+' | Failed responses: '+JSON.stringify(failed)+' | Page errors: '+JSON.stringify(errors)+' | Visible panels: '+JSON.stringify(await visiblePanels(page).catch(()=>null)));}
 return {context,page,errors,apiCalls};
}

for(const [name,type,options] of [['Chrome',chromium,{channel:'chrome'}],['Edge',chromium,{channel:'msedge'}],['Firefox',firefox,{}]].filter(([name])=>selected.includes(name))){
 console.log('BROWSER '+name);
 const browser=withOnboarding(await type.launch({headless:!(name==='Firefox'&&process.env.RUNNER_FIREFOX_WINDOWED==='1'),...options}),origin);
 try{
  // ---- First visit -----------------------------------------------------------------------------------------------------
  {
   const context=await browser.newContextWithoutOnboarding({viewport:{width:1280,height:900}}),page=await context.newPage();page.setDefaultTimeout(30000);
   await page.goto(origin);await ready(page);
   assert.deepEqual(await visiblePanels(page),['consent-panel'],'only the notice is visible on the first visit');
   assert.match(await page.locator('#consent-panel').innerText(),/unofficial fan project/i);assert.match(await page.locator('#consent-panel').innerText(),/not made, endorsed or supported by Subset Games/i);assert.match(await page.locator('#consent-panel').innerText(),/belongs to Subset Games/);
   assert.equal(await page.locator('#consent-panel button').count(),1);assert.equal(await page.locator('.site-header #identity').isHidden(),true);
   await page.locator('#consent-agree').click();await page.locator('#mode-panel').waitFor();assert.deepEqual(await visiblePanels(page),['mode-panel']);
   const consent=(await context.cookies()).find(c=>c.name==='ftl-consent-local-'+port);assert.ok(consent&&consent.expires>Date.now()/1000+390*86400&&consent.httpOnly&&consent.sameSite==='Lax','consent cookie: 400 days, HttpOnly, Lax');
   await page.reload();await ready(page);assert.deepEqual(await visiblePanels(page),['mode-panel'],'the choice screen is next, rendered by the server');
   assert.match(await page.locator('#mode-panel').innerText(),/Cloud saves/);assert.match(await page.locator('#mode-panel').innerText(),/Local only/);
   check(name+': first visit shows the notice, Agree stores a 400-day cookie and leads to the cloud/local choice');
   await page.locator('#mode-cloud').click();await page.locator('#register-panel').waitFor();assert.deepEqual(await visiblePanels(page),['register-panel']);
   assert.equal(await page.locator('#register-signin').isVisible(),true);assert.equal(await page.locator('#register-back').isVisible(),true);
   await page.locator('#register-back').click();await page.locator('#mode-panel').waitFor();await page.locator('#mode-cloud').click();await page.locator('#register-panel').waitFor();
   await page.locator('#register-signin').click();await page.locator('#login-panel').waitFor();assert.equal(await page.locator('#play-local').isVisible(),true);assert.equal(await page.locator('#login-register').isVisible(),true);
   await page.reload();await ready(page);assert.deepEqual(await visiblePanels(page),['login-panel'],'consent and cloud choice are remembered');
   check(name+': cloud leads to register, with a way back and to sign in; later visits go straight to sign in');
   // Registration through the screen
   await page.locator('#login-register').click();await page.locator('#register-panel').waitFor();
   const user='ui-'+name.toLowerCase()+'-'+suffix;
   await page.locator('#register-form [name=username]').fill(user);await page.locator('#register-form [name=password]').fill(password);await page.locator('#register-form [name=confirm]').fill(password+'x');await page.locator('#register-form button[type=submit]').click();
   assert.match(await page.locator('#message').textContent(),/do not match/);assert.equal((await context.cookies()).some(c=>c.name==='ftl-local-'+port),false);
   await page.locator('#register-form [name=username]').fill('x');await page.locator('#register-form [name=confirm]').fill(password);await page.locator('#register-form button[type=submit]').click();
   await page.waitForTimeout(300);assert.equal((await context.cookies()).some(c=>c.name==='ftl-local-'+port),false,'an invalid username does not register (client validation or server)');
   await page.locator('#register-form [name=username]').fill(user);await page.locator('#register-form button[type=submit]').click();
   await page.waitForSelector('body[data-auth-state="authenticated"]');await page.locator('#launch-panel').waitFor();
   assert.equal(await page.locator('#username').textContent(),user);
   const signCookie=(await context.cookies()).find(c=>c.name==='ftl-local-'+port);assert.ok(signCookie&&signCookie.expires>Date.now()/1000+29*86400,'the sign-in cookie is persistent');
   check(name+': registering through the screen signs in and sets a persistent cookie');
   // Browser restart: session cookies are dropped, persistent ones kept.
   const restarted=await browser.newContextWithoutOnboarding({viewport:{width:1280,height:900}});await restarted.addCookies((await context.cookies()).filter(c=>c.expires>0));
   const back=await restarted.newPage();await back.goto(origin);await ready(back);
   assert.equal(await back.evaluate(()=>document.body.dataset.authState),'authenticated');assert.equal(await back.locator('#username').textContent(),user);
   check(name+': the sign-in survives a browser restart (only persistent cookies are kept)');
   // Sign out clears it; the mode cookie keeps the next visit on the sign-in screen.
   await Promise.all([back.waitForEvent('load'),back.locator('#logout').click()]);await ready(back);assert.deepEqual(await visiblePanels(back),['login-panel']);
   assert.equal((await restarted.cookies()).some(c=>c.name==='ftl-local-'+port&&c.value),false);
   // Account panel
   const again=await restarted.newPage();await again.goto(origin);await ready(again);await again.locator('#login-form [name=username]').fill(user);await again.locator('#login-form [name=password]').fill(password);await again.locator('#login-form button[type=submit]').click();
   await again.waitForSelector('body[data-auth-state="authenticated"]');await again.locator('#account-button').click();await again.locator('#account-panel').waitFor();
   assert.equal(await again.locator('#sessions-refresh').count(),0);assert.equal(await again.locator('#sessions').count(),0);assert.doesNotMatch(await again.locator('#account-panel').innerText(),/sessions/i);
   // The panel is filled by a module that loads when it opens, so the summary appears a moment after the panel.
   await again.waitForFunction(()=>/Signed in as/.test(document.getElementById('account-summary').textContent));
   assert.match(await again.locator('#account-summary').textContent(),new RegExp(user));assert.equal(await again.locator('#change-password').isVisible(),true);assert.equal(await again.locator('#local-assets').count(),1,'the browser game files section stays on the account panel');
   assert.equal(await again.locator('#about-nav-button').isHidden(),true,'the About navigation item belongs to local mode; signed-in users keep the footer link');
   assert.match(await again.locator('#delete-zone').innerText(),/permanent/i);assert.match(await again.locator('#delete-zone').innerText(),/cannot be undone/i);assert.equal(await again.locator('#delete-backup').isVisible(),true);
   check(name+': the account panel has no session list, offers password change, game files and permanent deletion');
   // Delete: backup first, password required, ends signed out with a clear message
   const [backup]=await Promise.all([again.waitForEvent('download'),again.locator('#delete-backup').click()]);assert.equal(backup.suggestedFilename(),'ftl-cloud-backup.json');assert.equal(JSON.parse(await readFile(await backup.path(),'utf8')).schema,1);
   again.once('dialog',d=>d.accept());await again.locator('#delete-password').fill('wrong-password-wrong');await again.locator('#delete-account').click();
   await again.waitForFunction(()=>/incorrect/i.test(document.getElementById('message').textContent));assert.equal(await again.evaluate(()=>document.body.dataset.authState),'authenticated');
   again.once('dialog',d=>d.accept());await again.locator('#delete-password').fill(password);await again.locator('#delete-account').click();
   await again.waitForFunction(()=>document.body.dataset.sessionReady==='true'&&document.body.dataset.authState==='signed-out'&&/account was deleted/i.test(document.getElementById('message').textContent));
   assert.deepEqual(await visiblePanels(again),['login-panel']);
   await again.locator('#login-form [name=username]').fill(user);await again.locator('#login-form [name=password]').fill(password);await again.locator('#login-form button[type=submit]').click();
   await again.waitForFunction(()=>/incorrect/i.test(document.getElementById('message').textContent));
   check(name+': deleting an account needs the password, offers a backup first and signs out with a message');
   await context.close();await restarted.close();
  }
  // ---- Final administrator -----------------------------------------------------------------------------------------------
  {
   const context=await browser.newContext({viewport:{width:1280,height:900}}),page=await context.newPage();page.setDefaultTimeout(30000);
   await page.goto(origin);await ready(page);
   const api=async(path,method='GET',data)=>{const token=method==='GET'?null:(await(await page.request.get(origin+'/api/auth/csrf')).json()).token;return page.request.fetch(origin+'/api'+path,{method,data,headers:{Origin:origin,...(token?{'X-CSRF-TOKEN':token}:{})}});};
   let first=await api('/auth/login','POST',{username:'admin',password:credentials.get('admin')});
   if(first.status()===401)first=await api('/auth/login','POST',{username:'admin',password:adminPassword});
   assert.ok(first.ok(),'the administrator could not sign in (status '+first.status()+')');
   if(first.ok()&&(await(await api('/auth/me')).json()).mustChangePassword){assert.equal((await api('/auth/password','POST',{currentPassword:credentials.get('admin'),newPassword:adminPassword})).status(),204);assert.ok((await api('/auth/login','POST',{username:'admin',password:adminPassword})).ok());}
   await page.goto(origin+'/?view=account');await ready(page);await page.locator('#account-panel').waitFor();
   await page.waitForFunction(()=>!document.getElementById('delete-blocked').hidden);
   assert.equal(await page.locator('#delete-account-form').isHidden(),true);assert.match(await page.locator('#delete-blocked').textContent(),/final enabled administrator/i);
   check(name+': the final enabled administrator cannot delete their own account from the panel');
   await page.goto(origin+'/?view=users');await ready(page);await page.locator('#admin-panel').waitFor();
   await page.waitForFunction(()=>!document.getElementById('registration-toggle').disabled&&document.getElementById('registration-state').textContent!=='Loading…');
   assert.equal(await page.locator('#registration-toggle').isChecked(),true);
   await page.locator('#registration-toggle').uncheck();await page.waitForFunction(()=>/closed/i.test(document.getElementById('registration-state').textContent)&&!document.getElementById('registration-toggle').disabled);
   const closed=await browser.newContext({viewport:{width:1280,height:900}}),guest=await closed.newPage();await guest.goto(origin+'/?view=register');await ready(guest);
   assert.equal(await guest.locator('#register-closed').isVisible(),true);assert.match(await guest.locator('#register-closed').textContent(),/closed/i);assert.equal(await guest.locator('#register-form').isVisible(),false);
   await guest.goto(origin+'/?view=signin');await ready(guest);assert.equal(await guest.locator('#login-register').isHidden(),true);assert.match(await guest.locator('#login-closed').textContent(),/closed/i);
   await page.locator('#registration-toggle').check();await page.waitForFunction(()=>/Anyone can create/i.test(document.getElementById('registration-state').textContent));
   await guest.goto(origin+'/?view=register');await ready(guest);assert.equal(await guest.locator('#register-form').isVisible(),true);
   check(name+': the Users panel switches public registration and the screens react');
   await closed.close();await context.close();
  }
  // ---- Local-only mode ----------------------------------------------------------------------------------------------------
  {
   const {context,page,errors,apiCalls}=await startLocal(browser);
   assert.deepEqual(await visiblePanels(page),['launch-panel']);
   for(const id of ['account-button','tests-button','admin-button','logout'])assert.equal(await page.locator('#'+id).isHidden(),true,id+' is hidden in local mode');
   for(const id of ['play-button','saves-button','logs-button','about-nav-button','enable-cloud'])assert.equal(await page.locator('#'+id).isVisible(),true,id+' is available in local mode');
   assert.deepEqual(await page.locator('#identity a:visible').allTextContents(),['Play','Saves','Logs','About'],'the local navigation is Play, Saves, Logs, About');
   await page.locator('#about-nav-button').click();await page.locator('#about-panel').waitFor();assert.equal(await page.locator('#about-nav-button').getAttribute('aria-current'),'page');assert.match(page.url(),/view=about/);await page.locator('#play-button').click();await page.locator('#launch-panel').waitFor();
   assert.match(await page.locator('#username').textContent(),/Local only/);
   await page.reload();await ready(page);assert.deepEqual(await visiblePanels(page),['launch-panel'],'the local choice is remembered');
   assert.equal((await context.cookies()).find(c=>c.name==='ftl-mode-local-'+port)?.value,'local');
   check(name+': Local only opens the launcher with Play, Saves, Logs, About and Enable cloud saves, and is remembered');

   // Saves survive a reload; revisions count up; history is bounded; conflicts are refused.
   assert.equal((await readLocal(page)).source,'none');
   const first=await writeLocal(page,RUN,PROFILE,0);assert.deepEqual({revision:first.revision,durable:first.durable},{revision:1,durable:true});
   await page.reload();await ready(page);
   assert.deepEqual(await readLocal(page),{source:'indexeddb',revision:1,run:RUN,profile:PROFILE});
   await page.waitForFunction(()=>/Saved run ready/.test(document.getElementById('cloud-ready').textContent));assert.match(await page.locator('#cloud-ready').textContent(),/stored in this browser/);
   const second=await writeLocal(page,RUN2,PROFILE,1);assert.equal(second.revision,2);
   const stale=await page.evaluate(async({run,profile})=>{const {saves,gameConfig}=await import('/web/local-api.mjs'),c=await gameConfig();try{await saves.save({schema:1,engineBuild:c.engineBuild,assetVersion:c.assetVersion,run,profile},1);return 'accepted';}catch(e){return e.status;}},{run:RUN,profile:PROFILE});
   assert.equal(stale,409,'a writer that last saw an older revision cannot overwrite a newer save');assert.equal((await readLocal(page)).run,RUN2);
   for(let revision=2;revision<14;revision++)await writeLocal(page,RUN.replace('alpha','r'+revision),PROFILE,revision);
   const history=await page.evaluate(async()=>(await (await import('/web/local-saves.mjs')).history()).map(h=>h.revision));assert.equal(history.length,10);assert.equal(history[0],14);
   check(name+': a local save survives reload; revisions advance atomically; stale writers are refused; ten snapshots are kept');

   // The Saves panel: location, download, import, snapshots, storage status.
   await page.locator('#saves-button').click();await page.locator('#saves-panel').waitFor();
   assert.match(await page.locator('#backup-download').textContent(),/local save/i);assert.equal(await page.locator('#local-save-card').isHidden(),true);assert.equal(await page.locator('#storage-card').isVisible(),true);
   await page.waitForFunction(()=>/Persistent storage/.test(document.getElementById('storage-status').textContent));
   const [download]=await Promise.all([page.waitForEvent('download'),page.locator('#backup-download').click()]);assert.equal(download.suggestedFilename(),'ftl-local-backup.json');
   const backup=JSON.parse(await readFile(await download.path(),'utf8'));const config=await publicConfig(page);
   assert.deepEqual(Object.keys(backup).sort(),['assetVersion','engineBuild','profile','revision','run','schema']);assert.equal(backup.schema,1);assert.equal(backup.revision,14);assert.equal(backup.engineBuild,config.engineBuild);assert.equal(backup.assetVersion,config.assetVersion);assert.match(backup.run,/r13/);
   check(name+': the local backup has the same envelope as a cloud backup');
   await page.locator('#snapshots-refresh').click();await page.waitForFunction(()=>document.querySelectorAll('#snapshots .snapshot-row').length===10);
   page.once('dialog',d=>d.accept());
   const reloaded=page.waitForEvent('load');
   await page.setInputFiles('#backup-import',{name:'backup.json',mimeType:'application/json',buffer:Buffer.from(JSON.stringify({...backup,run:RUN,profile:PROFILE}))});
   await reloaded;await ready(page);
   assert.deepEqual(await readLocal(page),{source:'indexeddb',revision:15,run:RUN,profile:PROFILE});
   check(name+': importing a backup replaces the local save as a new revision');
   // A bad backup is refused
   await page.goto(origin+'/?view=saves');await ready(page);
   await page.setInputFiles('#backup-import',{name:'bad.json',mimeType:'application/json',buffer:Buffer.from(JSON.stringify({...backup,assetVersion:'other'}))});
   await page.waitForFunction(()=>/do not match/i.test(document.getElementById('message').textContent));assert.equal((await readLocal(page)).revision,15);
   // Restore: each snapshot says what made it; restoring one makes it the current save and deletes the newer snapshots.
   await page.locator('#snapshots-refresh').click();await page.waitForFunction(()=>document.querySelectorAll('#snapshots .snapshot-row').length===10);
   const rows=await page.locator('#snapshots .snapshot-row').allTextContents();
   assert.match(rows[0],/Revision 15 .*Imported.*Current save/);assert.doesNotMatch(rows[0],/Restore/);assert.match(rows[1],/Revision 14 .*Restore/);
   let warning='';page.once('dialog',d=>{warning=d.message();d.accept();});
   const back=page.waitForEvent('load');
   await page.locator('#snapshots .snapshot-row',{hasText:'Revision 12 '}).getByRole('button',{name:'Restore'}).click();
   await back;await ready(page);
   assert.match(warning,/All progress after this snapshot will be lost/);
   const RESTORED=RUN.replace('alpha','r11'),after=await readLocal(page);assert.equal(after.revision,16);assert.equal(after.run,RESTORED);
   assert.deepEqual((await page.evaluate(async()=>(await (await import('/web/local-saves.mjs')).history()).map(h=>[h.revision,h.kind]))).slice(0,2),[[16,'restore'],[12,null]]);
   check(name+': restoring a snapshot makes it the current save and deletes the newer snapshots');

   // IndexedDB loss: the localStorage copy restores the save and repairs the database.
   await page.evaluate(()=>new Promise((res,rej)=>{const r=indexedDB.deleteDatabase('ftl-local-saves');r.onsuccess=res;r.onerror=()=>rej(r.error);r.onblocked=res;}));
   assert.equal(await idbRecord(page),null);
   const restored=await readLocal(page);assert.deepEqual(restored,{source:'mirror',revision:16,run:RESTORED,profile:PROFILE});
   assert.deepEqual(await idbRecord(page),{revision:16,run:RESTORED},'the database is repaired from the copy');
   // IndexedDB unusable: saving still works through the copy and says so.
   await page.evaluate(()=>new Promise(res=>{const r=indexedDB.deleteDatabase('ftl-local-saves');r.onsuccess=res;r.onerror=res;r.onblocked=res;}));
   const broken=await context.newPage();await broken.addInitScript(()=>{const open=IDBFactory.prototype.open;IDBFactory.prototype.open=function(name,...rest){if(name==='ftl-local-saves'){const r={};setTimeout(()=>{r.error=new DOMException('blocked for the test','UnknownError');r.onerror?.({});},0);return r;}return open.call(this,name,...rest);};});
   await broken.goto(origin);await ready(broken);
   const degraded=await writeLocal(broken,RUN2,PROFILE,16);assert.deepEqual({revision:degraded.revision,durable:degraded.durable},{revision:17,durable:false});
   assert.deepEqual(await readLocal(broken),{source:'mirror',revision:17,run:RUN2,profile:PROFILE});
   await broken.close();
   assert.deepEqual(await readLocal(page),{source:'mirror',revision:17,run:RUN2,profile:PROFILE},'a normal page then finds the newer copy and restores IndexedDB');
   check(name+': losing or blocking IndexedDB falls back to the localStorage copy, and the database is repaired afterwards');

   // Browser storage persistence is requested and the result is shown.
   await context.close();
   for(const granted of [true,false]){
    const persistent=await startLocal(browser,{persist:granted});
    await persistent.page.locator('#saves-button').click();await persistent.page.locator('#storage-status').waitFor();
    await persistent.page.waitForFunction(()=>/Persistent storage/.test(document.getElementById('storage-status').textContent));
    assert.match(await persistent.page.locator('#storage-status').textContent(),granted?/Persistent storage granted/:/Persistent storage not granted/);
    await persistent.context.close();
   }
   check(name+': the browser is asked for persistent storage and the saves panel reports whether it was granted');

   // One tab plays at a time: a second tab is refused until it takes over, and the first tab then loses its lease and its writes.
   const tabA=await startLocal(browser),tabB=await tabA.context.newPage();await tabB.goto(origin);await ready(tabB);
   const call=(tab,path,body,method='POST')=>tab.evaluate(async({path,body,method})=>{const {localApi}=await import('/web/local-api.mjs');try{return await localApi(path,{method,body})??{};}catch(e){return {error:e.status};}},{path,body,method});
   const leaseA=await call(tabA.page,'/play-lease/acquire',{});assert.ok(leaseA.lease);
   assert.equal((await call(tabB,'/play-lease/acquire',{})).error,409);
   const leaseB=await call(tabB,'/play-lease/acquire',{takeOver:true});assert.ok(leaseB.lease&&leaseB.lease!==leaseA.lease);
   assert.equal((await call(tabA.page,'/play-lease/heartbeat',{lease:leaseA.lease})).error,409);assert.equal((await call(tabB,'/play-lease/heartbeat',{lease:leaseB.lease})).error,undefined);
   const live=await tabB.evaluate(async()=>{const {gameConfig}=await import('/web/local-api.mjs');const c=await gameConfig();return {schema:1,engineBuild:c.engineBuild,assetVersion:c.assetVersion,revision:0,run:null,profile:'<xftl-profile/>'};});
   const put=(tab,lease)=>tab.evaluate(async({lease,live})=>{const {localApi}=await import('/web/local-api.mjs');try{return await localApi('/saves/current',{method:'PUT',body:{lease,state:live},headers:{'If-Match':'"0"'}});}catch(e){return {error:e.status};}},{lease,live});
   assert.equal((await put(tabA.page,leaseA.lease)).error,409,'the tab that lost its lease cannot write');assert.equal((await put(tabB,leaseB.lease)).revision,1);
   assert.equal((await tabB.evaluate(async()=>{const {localApi}=await import('/web/local-api.mjs');try{await localApi('/auth/me');return 0;}catch(e){return e.status;}})),404,'local mode has no account endpoints');
   await tabA.context.close();
   check(name+': one tab plays at a time in local mode; taking over ends the other tab’s lease and writes');

   // Logs stay in this browser.
   const logs=await startLocal(browser);await logs.page.waitForFunction(()=>document.getElementById('debug-enabled').onchange!==null);
   await logs.page.locator('#logs-button').click();await logs.page.locator('#logs-panel').waitFor();await logs.page.locator('#debug-enabled').check();
   await logs.page.waitForFunction(()=>document.getElementById('debug-status').textContent.includes('Recording'));
   await logs.page.locator('#logs-mark').click();await logs.page.locator('#logs-refresh').click();
   await logs.page.waitForFunction(()=>document.querySelectorAll('#log-sessions tr td').length>2);
   assert.match(await logs.page.locator('#log-usage').textContent(),/stored in this browser only, never uploaded/);
   const [zip]=await Promise.all([logs.page.waitForEvent('download'),logs.page.locator('#log-sessions').getByRole('button',{name:'Export'}).first().click()]);assert.match(zip.suggestedFilename(),/^ftl-debug-.*\.zip$/);assert.ok((await readFile(await zip.path())).length>200);
   await logs.page.locator('#debug-enabled').uncheck();
   assert.ok(!logs.apiCalls.some(c=>c.includes('/api/debug')),'logs are not uploaded');
   assert.ok(logs.apiCalls.filter(c=>!c.startsWith('GET ')).every(c=>c.startsWith('POST /api/onboarding/')),'local mode writes nothing to the server: '+logs.apiCalls.join(', '));
   assert.ok(logs.apiCalls.filter(c=>c.startsWith('GET ')).every(c=>/^GET \/api\/(public\/game\/(config|resources)|auth\/(csrf|me))$/.test(c)),'local mode only reads public game metadata: '+logs.apiCalls.join(', '));
   assert.deepEqual(logs.errors,[]);
   await logs.context.close();
   check(name+': local logs are recorded, listed and exported from this browser and never uploaded; no server writes happen in local mode');
  }
  // ---- Enable cloud saves: both branches --------------------------------------------------------------------------------
  {
   const mover='mover-'+name.toLowerCase()+'-'+suffix;
   // A: the account has no cloud save yet -> upload, then the browser copy is removed.
   let local=await startLocal(browser);let page=local.page;
   await writeLocal(page,RUN,PROFILE,0);await page.reload();await ready(page);
   await page.locator('#enable-cloud').click();await page.locator('#register-panel').waitFor();
   await page.locator('#register-form [name=username]').fill(mover);await page.locator('#register-form [name=password]').fill(password);await page.locator('#register-form [name=confirm]').fill(password);await page.locator('#register-form button[type=submit]').click();
   await page.waitForSelector('body[data-auth-state="authenticated"][data-session-ready="true"]');await page.locator('#migrate-dialog[open]').waitFor();
   assert.equal(await page.locator('#migrate-confirm').isVisible(),true);assert.equal(await page.locator('#migrate-keep-cloud').isHidden(),true);assert.equal(await page.locator('#migrate-use-local').isHidden(),true);
   assert.match(await page.locator('#migrate-cloud').textContent(),/no cloud save yet/i);assert.match(await page.locator('#migrate-local').textContent(),/Run in progress/);
   const [localBackup]=await Promise.all([page.waitForEvent('download'),page.locator('#migrate-local-download').click()]);assert.equal(JSON.parse(await readFile(await localBackup.path(),'utf8')).run,RUN);
   await page.locator('#migrate-confirm').click();await page.waitForFunction(()=>!document.getElementById('migrate-dialog').open);
   const uploaded=await cloudSave(page);assert.deepEqual({revision:uploaded.revision,run:uploaded.run,profile:uploaded.profile},{revision:1,run:RUN,profile:PROFILE});
   assert.equal((await readLocal(page)).source,'none','the local copy is removed after the upload was verified');assert.equal(await page.evaluate(()=>localStorage.getItem('ftl-enable-cloud')),null);
   assert.match(await page.locator('#message').textContent(),/now in your account/i);assert.equal((await page.context().cookies()).find(c=>c.name==='ftl-mode-local-'+port)?.value,'cloud','the mode cookie is cloud afterwards');
   check(name+': Enable cloud saves, no cloud save yet: registration, then the local save is uploaded and removed from the browser');
   await local.context.close();
   // B: the account already has a cloud save -> ask which to keep, never overwrite silently.
   local=await startLocal(browser);page=local.page;
   await writeLocal(page,RUN2,PROFILE,0);await page.reload();await ready(page);
   await page.locator('#enable-cloud').click();await page.locator('#register-panel').waitFor();await page.locator('#register-signin').click();await page.locator('#login-panel').waitFor();
   await page.locator('#login-form [name=username]').fill(mover);await page.locator('#login-form [name=password]').fill(password);await page.locator('#login-form button[type=submit]').click();
   await page.waitForSelector('body[data-auth-state="authenticated"][data-session-ready="true"]');await page.locator('#migrate-dialog[open]').waitFor();
   assert.equal(await page.locator('#migrate-confirm').isHidden(),true);assert.equal(await page.locator('#migrate-keep-cloud').isVisible(),true);assert.equal(await page.locator('#migrate-use-local').isVisible(),true);
   assert.match(await page.locator('#migrate-cloud').textContent(),/Run in progress/);assert.match(await page.locator('#migrate-local').textContent(),/Run in progress/);
   const [cloudBackup]=await Promise.all([page.waitForEvent('download'),page.locator('#migrate-cloud-download').click()]);assert.equal(JSON.parse(await readFile(await cloudBackup.path(),'utf8')).run,RUN);
   assert.equal((await cloudSave(page)).run,RUN,'nothing changed yet');
   await page.locator('#migrate-keep-cloud').click();await page.waitForFunction(()=>!document.getElementById('migrate-dialog').open);
   assert.equal((await cloudSave(page)).run,RUN);assert.equal((await readLocal(page)).run,RUN2,'keeping the cloud save leaves the local save in the browser');
   await page.locator('#saves-button').click();await page.locator('#local-save-card').waitFor({state:'visible'});assert.match(await page.locator('#local-save-info').textContent(),/Run in progress/);
   // Replace the cloud save later from the Saves page (confirmation required).
   await page.locator('#local-save-move').click();await page.locator('#migrate-dialog[open]').waitFor();
   page.once('dialog',d=>d.dismiss());await page.locator('#migrate-use-local').click();await page.waitForTimeout(300);assert.equal((await cloudSave(page)).run,RUN,'declining the confirmation keeps the cloud save');
   page.once('dialog',d=>d.accept());await page.locator('#migrate-use-local').click();await page.waitForFunction(()=>!document.getElementById('migrate-dialog').open);
   const replaced=await cloudSave(page);assert.deepEqual({revision:replaced.revision,run:replaced.run},{revision:2,run:RUN2});assert.equal((await readLocal(page)).source,'none');
   assert.equal(await page.locator('#local-save-card').isHidden(),true);
   check(name+': Enable cloud saves with an existing cloud save asks which to keep, offers both backups and never overwrites silently');
   await local.context.close();
  }
 }finally{await browser.close();}
}
console.log(`${checks.length} local-mode checks passed.`);
