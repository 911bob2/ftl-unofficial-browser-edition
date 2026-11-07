// Browser tests for first-visit onboarding, local-only mode (saves in this browser's device store, docs/SAVES.md),
// moving local saves into an account (Enable cloud saves), persistent sign-in and the account panel. They do not need a
// game file: runs are written into the device store as the game would. Launching the engine is covered in
// saves-browser.mjs and website-ui.mjs.
import assert from 'node:assert/strict';
import {withOnboarding} from './lib/onboarding.mjs';
import {readFile} from 'node:fs/promises';
import {randomBytes} from 'node:crypto';
import {chromium,firefox} from 'playwright';
const origin=process.env.LAUNCHER_ORIGIN??'http://localhost:8084',port=new URL(origin).port,fixture=process.env.DEBUG_FIXTURE_DIR;
if(origin!=='http://localhost:8084')throw Error('Use the disposable 8084 fixture.');
for(let attempt=0;;attempt++){try{if((await fetch(origin+'/health/ready')).ok)break;}catch{}if(attempt===30)throw Error('Fixture did not become ready.');await new Promise(r=>setTimeout(r,1000));}
const checks=[],check=name=>{checks.push(name);console.log('PASS '+name);};
const selected=(process.env.MENU_BROWSERS??'Chromium,Firefox').split(',');
const credentials=new Map((await readFile(fixture+'/bootstrap-credentials.txt','utf8')).split(/\r?\n/).filter(l=>l.includes('\t')).map(l=>l.split('\t')));
const suffix=randomBytes(3).toString('hex'),password='local-mode-password-'+suffix,adminPassword='local-mode-admin-'+suffix;
const RUN='<xftlSaveGame><marker id="alpha"/></xftlSaveGame>',RUN2='<xftlSaveGame><marker id="beta"/></xftlSaveGame>',PROFILE='<xftl-profile><unlock id="one"/></xftl-profile>';
const ready=page=>page.waitForSelector('body[data-session-ready="true"]');
const visiblePanels=page=>page.evaluate(()=>[...document.querySelectorAll('main>section')].filter(s=>!s.hidden).map(s=>s.id));
const row=(page,id)=>page.locator(`#saves-list li.save-row[data-id="${id}"]`);
/** A new save through Saves > New save; resolves its id. */
async function newLocalSave(page,name){
 await page.locator('#saves-button').click();await page.locator('#new-save').click();await page.locator('dialog#new-save-dialog[open]').waitFor();
 await page.locator('#new-save-name').fill(name);await page.locator('#new-save-create').click();
 const r=page.locator('#saves-list li.save-row',{hasText:name});await r.waitFor();return r.getAttribute('data-id');
}
/** A run and profile written into this browser's local saves, as the game writes them. */
const writeRun=(page,id,run,profile)=>page.evaluate(async({id,run,profile})=>{
 const {deviceStore,LOCAL_ACCOUNT}=await import('/web/device-store.mjs'),s=deviceStore(LOCAL_ACCOUNT);
 await s.update(id,cur=>({...cur,run,hasRun:true,profile,hasProfile:true,dirty:true}));
},{id,run,profile});
const localSave=(page,id)=>page.evaluate(async id=>{const {deviceStore,LOCAL_ACCOUNT}=await import('/web/device-store.mjs');return deviceStore(LOCAL_ACCOUNT).get(id);},id);
const cloudList=async page=>(await page.request.get(origin+'/api/saves')).json();
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

for(const [name,type,options] of [['Chromium',chromium,{channel:'chromium'}],['Firefox',firefox,{}]].filter(([name])=>selected.includes(name))){
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
   // Sign out always asks first (web/sign-out.mjs, the Android launcher's words): Cancel keeps the account.
   const signedIn=p=>p.evaluate(async()=>(await fetch('/api/auth/me',{cache:'no-store'})).status===200);
   await back.locator('#logout').click();await back.locator('dialog#logout-dialog[open]').waitFor();
   assert.equal(await back.locator('#logout-dialog-title').textContent(),'Sign out?');assert.match(await back.locator('#logout-dialog').innerText(),/Sign in again to play this account's saves here\./);
   assert.equal(await back.locator('#logout-dialog-warning').count(),0,'no unsynced line without unsynced progress');
   assert.equal(await back.locator('#logout-dialog-no').textContent(),'Cancel');assert.equal(await back.locator('#logout-dialog-yes').textContent(),'Sign out');
   await back.screenshot({path:'.build/sign-out/'+name.toLowerCase()+'-asked.png'});
   await back.locator('#logout-dialog-no').click();await back.locator('#logout-dialog').waitFor({state:'detached'});
   assert.equal(await back.evaluate(()=>document.body.dataset.authState),'authenticated');assert.equal(await signedIn(back),true,'Cancel keeps the account');
   // A save of this account with progress the account does not have: the stronger line (offline, so it stays unsynced).
   const account=await back.evaluate(()=>document.body.dataset.account);
   await restarted.setOffline(true);
   await back.evaluate(async account=>{const {deviceStore}=await import('/web/device-store.mjs');await deviceStore(account).put({id:'sign-out-unsynced',name:'Unsynced',setup:{mods:[]},createdUtc:new Date().toISOString(),meta:{},base:0,dirty:true,hasRun:true,run:'<xftlSaveGame/>',profile:null,hasProfile:false,nameDirty:false,overridden:null});},account);
   await back.locator('#logout').click();await back.locator('dialog#logout-dialog[open]').waitFor();
   assert.match(await back.locator('#logout-dialog-warning').textContent(),/^Your latest progress is saved on this device but not in your account yet\. It syncs the next time you sign in here\.$/);
   await back.screenshot({path:'.build/sign-out/'+name.toLowerCase()+'-unsynced.png'});
   await back.locator('#logout-dialog-no').click();await back.locator('#logout-dialog').waitFor({state:'detached'});
   await back.evaluate(async account=>{const {deviceStore}=await import('/web/device-store.mjs');await deviceStore(account).remove('sign-out-unsynced');},account);
   await restarted.setOffline(false);assert.equal(await signedIn(back),true);
   // The other pages' header (web/shell.mjs) asks the same.
   const other=await restarted.newPage();await other.goto(origin+'/Licenses');await other.locator('#logout').click();await other.locator('dialog#logout-dialog[open]').waitFor();
   assert.equal(await other.locator('#logout-dialog-title').textContent(),'Sign out?');await other.screenshot({path:'.build/sign-out/'+name.toLowerCase()+'-licenses.png'});
   await other.locator('#logout-dialog-no').click();await other.locator('#logout-dialog').waitFor({state:'detached'});assert.equal(await signedIn(other),true);await other.close();
   check(name+': Sign out asks first on the play page and the other pages, Cancel keeps the account, unsynced progress adds the stronger line');
   // Sign out clears it; the mode cookie keeps the next visit on the sign-in screen.
   await back.locator('#logout').click();await back.locator('dialog#logout-dialog[open]').waitFor();
   await Promise.all([back.waitForEvent('load'),back.locator('#logout-dialog-yes').click()]);await ready(back);assert.deepEqual(await visiblePanels(back),['login-panel']);
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
   const [backup]=await Promise.all([again.waitForEvent('download'),again.locator('#delete-backup').click()]);assert.equal(backup.suggestedFilename(),'ftl-saves-backup.json');const all=JSON.parse(await readFile(await backup.path(),'utf8'));assert.deepEqual([all.schema,all.kind],[3,'ftl-saves']);
   await again.locator('#delete-password').fill('wrong-password-wrong');await again.locator('#delete-account').click();await again.locator('#delete-account-dialog-yes').click();
   await again.waitForFunction(()=>/incorrect/i.test(document.getElementById('message').textContent));assert.equal(await again.evaluate(()=>document.body.dataset.authState),'authenticated');
   await again.locator('#delete-password').fill(password);await again.locator('#delete-account').click();await again.locator('#delete-account-dialog-yes').click();
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

   // Saves live in this browser's device store, one plain list; nothing syncs.
   const id=await newLocalSave(page,'Save 1');
   await writeRun(page,id,RUN,PROFILE);
   await page.reload();await ready(page);await page.locator('#saves-button').click();await row(page,id).waitFor();
   assert.equal((await localSave(page,id)).run,RUN,'the save survives a reload');
   assert.equal(await row(page,id).locator('[data-badge="local"]').textContent(),'This browser');
   assert.equal(await page.locator('#saves-sync').textContent(),'Saves are kept in this browser only.');
   assert.equal(await page.locator('#saves-sync-now').isHidden(),true);assert.equal(await page.locator('#saves-local-card').isHidden(),true);
   await row(page,id).locator('details.row-menu > summary').click();
   assert.equal(await row(page,id).locator('[data-action="snapshots"]').count(),0,'recovery snapshots are kept by the account only');
   check(name+': a local save is kept in this browser across reloads, marked "This browser", with no sync and no snapshots');
   // Backups: download one save, import it as a new one; anything else is refused.
   const [download]=await Promise.all([page.waitForEvent('download'),row(page,id).locator('[data-action="download"]').click()]);
   assert.equal(download.suggestedFilename(),'ftl-save-Save-1.json');
   const backup=JSON.parse(await readFile(await download.path(),'utf8'));
   assert.deepEqual([backup.schema,backup.kind,backup.name,backup.run,backup.setup.key],[3,'ftl-save','Save 1',RUN,'vanilla']);
   await page.setInputFiles('#backup-import',{name:'backup.json',mimeType:'application/json',buffer:Buffer.from(JSON.stringify({...backup,run:RUN2}))});
   await page.waitForFunction(()=>/^Imported as /.test(document.getElementById('message').textContent));
   await page.waitForFunction(()=>document.querySelectorAll('#saves-list li.save-row').length===2);
   const imported=await page.locator('#saves-list li.save-row').evaluateAll(rows=>rows.map(r=>r.dataset.id));
   const copy=imported.find(x=>x!==id);assert.equal((await localSave(page,copy)).run,RUN2);
   await page.setInputFiles('#backup-import',{name:'bad.json',mimeType:'application/json',buffer:Buffer.from(JSON.stringify({...backup,run:'<notASave/>'}))});
   await page.waitForFunction(()=>/not a save backup/.test(document.getElementById('message').textContent));
   assert.equal(await page.locator('#saves-list li.save-row').count(),2);
   // The old save model's backups import too (as a save without mods).
   await page.setInputFiles('#backup-import',{name:'old.json',mimeType:'application/json',buffer:Buffer.from(JSON.stringify({schema:1,revision:4,engineBuild:'old',assetVersion:'old',run:RUN,profile:PROFILE}))});
   await page.waitForFunction(()=>document.querySelectorAll('#saves-list li.save-row').length===3);
   check(name+': a save downloads as a backup and imports as a new save (old backups too); other files are refused');
   // Rename and delete use the page's own dialogs.
   await row(page,copy).locator('details.row-menu > summary').click();await row(page,copy).locator('[data-action="rename"]').click();
   await page.locator('#save-rename-dialog-input').fill('Imported run');await page.locator('#save-rename-dialog-ok').click();
   await row(page,copy).locator('strong',{hasText:'Imported run'}).waitFor();
   await row(page,copy).locator('details.row-menu > summary').click();await row(page,copy).locator('[data-action="delete"]').click();
   assert.match(await page.locator('#save-delete-dialog').innerText(),/deleted from this device/);
   await page.locator('#save-delete-dialog-yes').click();await row(page,copy).waitFor({state:'detached'});
   assert.equal(await localSave(page,copy),null);
   check(name+': rename and delete ask in the page\'s own dialogs');
   // One game per save: a save another tab plays is "In use on another tab" and asks before it is taken.
   const other=await context.newPage();await other.goto(origin+'/?view=saves');await ready(other);
   await other.evaluate(id=>{navigator.locks.request('ftl-play:local-device:'+id,()=>new Promise(()=>{}));},id);
   await page.reload();await ready(page);await page.locator('#saves-button').click();
   await row(page,id).locator('[data-action="play"]').click();
   await page.locator('dialog#save-lock-dialog[open]').waitFor();
   assert.equal(await page.locator('#save-lock-dialog-title').textContent(),'In use on another tab of this browser');
   assert.match(await page.locator('#save-lock-dialog-warning').textContent(),/That tab stops at once/);
   await page.locator('#save-lock-dialog-no').click();await other.close();
   check(name+': in local mode a save played in another tab asks before Override');
   await context.close();
   assert.deepEqual(errors,[]);

   // Browser storage persistence is requested and the result is shown.
   for(const granted of [true,false]){
    const persistent=await startLocal(browser,{persist:granted});
    await persistent.page.locator('#saves-button').click();await persistent.page.locator('#storage-status').waitFor();
    await persistent.page.waitForFunction(()=>/Persistent storage/.test(document.getElementById('storage-status').textContent));
    assert.match(await persistent.page.locator('#storage-status').textContent(),granted?/Persistent storage granted/:/Persistent storage not granted/);
    await persistent.context.close();
   }
   check(name+': the saves panel reports whether the browser keeps this site\'s storage persistent');

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
  // ---- Enable cloud saves: local saves move into the account ------------------------------------------------------------
  {
   const mover='mover-'+name.toLowerCase()+'-'+suffix;
   // A: a new account; the local save and its profile upload, and the local store is empty afterwards.
   let local=await startLocal(browser);let page=local.page;
   const first=await newLocalSave(page,'Local run');await writeRun(page,first,RUN,PROFILE);
   await page.locator('#enable-cloud').click();await page.locator('#register-panel').waitFor();
   await page.locator('#register-form [name=username]').fill(mover);await page.locator('#register-form [name=password]').fill(password);await page.locator('#register-form [name=confirm]').fill(password);await page.locator('#register-form button[type=submit]').click();
   await page.waitForSelector('body[data-auth-state="authenticated"][data-session-ready="true"]');
   await page.waitForFunction(()=>/now in your account/.test(document.getElementById('message').textContent),null,{timeout:30000});
   let cloud=await cloudList(page);
   assert.deepEqual(cloud.saves.map(s=>[s.id,s.name,s.hasRun]),[[first,'Local run',true]]);
   const moved=await (await page.request.get(origin+'/api/saves/'+first)).json();
   assert.equal(moved.run,RUN);assert.equal(moved.profile,PROFILE,'the save\'s unlocks move with it');
   assert.deepEqual(await page.evaluate(async()=>{const {deviceStore,LOCAL_ACCOUNT}=await import('/web/device-store.mjs');return (await deviceStore(LOCAL_ACCOUNT).list()).map(s=>s.id);}),[]);
   assert.equal(await page.evaluate(()=>localStorage.getItem('ftl-enable-cloud')),null);
   assert.equal((await page.context().cookies()).find(c=>c.name==='ftl-mode-local-'+port)?.value,'cloud','the mode cookie is cloud afterwards');
   check(name+': Enable cloud saves with a new account: the local save and profile move into the account and upload');
   await local.context.close();
   // B: an account that has saves already: nothing is replaced, the local save is added. Signing in without "Enable
   // cloud saves" leaves the local saves where they are and the Saves page offers to move them.
   local=await startLocal(browser);page=local.page;
   const second=await newLocalSave(page,'Second run');await writeRun(page,second,RUN2,PROFILE);
   await page.locator('#enable-cloud').click();await page.locator('#register-panel').waitFor();await page.locator('#register-signin').click();await page.locator('#login-panel').waitFor();
   await page.evaluate(()=>localStorage.removeItem('ftl-enable-cloud'));
   await page.locator('#login-form [name=username]').fill(mover);await page.locator('#login-form [name=password]').fill(password);await page.locator('#login-form button[type=submit]').click();
   await page.waitForSelector('body[data-auth-state="authenticated"][data-session-ready="true"]');
   await page.locator('#saves-button').click();await page.locator('#saves-local-card').waitFor({state:'visible'});
   assert.match(await page.locator('#saves-local-info').textContent(),/One save from playing without an account/);
   assert.equal((await cloudList(page)).saves.length,1,'nothing moves without asking');
   // The card hides once the save leaves this browser's local list; the upload pass ends later, with the message.
   await page.locator('#saves-local-upload').click();await page.locator('#saves-local-card').waitFor({state:'hidden'});
   await page.waitForFunction(()=>/now in your account/.test(document.getElementById('message').textContent),null,{timeout:30000});
   cloud=await cloudList(page);
   assert.deepEqual(cloud.saves.map(s=>s.name).sort(),['Local run','Second run']);
   assert.equal((await (await page.request.get(origin+'/api/saves/'+first)).json()).run,RUN,'the save already in the account is unchanged');
   check(name+': signing in with local saves offers to move them; moving adds them as saves of their own, never over another');
   await local.context.close();
  }
 }finally{await browser.close();}
}
console.log(`${checks.length} local-mode checks passed.`);
